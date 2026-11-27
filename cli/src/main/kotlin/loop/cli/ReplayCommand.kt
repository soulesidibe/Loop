package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.Issue
import loop.api.ProjectId
import loop.api.Redactor
import loop.core.events.AttemptStarted
import loop.core.kinds.IssueKind
import loop.core.kinds.KindLookup
import loop.core.kinds.KindPrompt
import loop.core.kinds.PinnedKind
import loop.core.orchestrator.attemptFile
import loop.core.replay.Placement
import loop.core.replay.RecordedPromptVariables
import loop.core.replay.RecordedVerdict
import loop.core.replay.RecoveredVariables
import loop.core.replay.ReplaySite
import loop.core.replay.ReplaySites
import loop.core.replay.ReplayTarget
import loop.core.replay.UnifiedDiff
import loop.core.replay.VerificationReplay
import loop.core.template.TemplateEngine
import loop.core.trackers.NamespacedId
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.workflow.CheckConfig
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowFile
import loop.core.workflow.workflowHash
import loop.core.workspace.Workspaces
import loop.runner.process.agentEnvironment
import loop.store.GzipAttemptRecords
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * `loop replay <attempt> --prompt [--workflow WORKFLOW.md]` (m4-40, story 137, PLAN 10.4): the turn 1 prompt of a
 * recorded attempt rendered again with the prompt template of the current `WORKFLOW.md`, then diffed with the prompt
 * the Attempt file recorded, as `loop show --prompt` prints it. The variables are the attempt's own as far as the store
 * recorded them ([RecordedPromptVariables]); the issue is the one the tracker returns now. The render goes through the
 * project's redactor before it is diffed. Read only: nothing is written to the store, the tracker or the workspace.
 * Exit 0 printed, 1 startup failure, 2 usage or an unknown attempt, 3 store or Attempt file failure, 4 no recorded
 * prompt, 5 the issue cannot be read or the template does not render.
 *
 * `loop replay <attempt> --verify [--workflow WORKFLOW.md]` (m4-41, story 138): the checks of the current `WORKFLOW.md`
 * for the issue's kind run again, through the verification of `loop.core.verify` and the check runner of `loop run`, on
 * the state the attempt's last verdict recorded ([ReplaySites]): a copy of the kept workspace while it is still at that
 * fingerprint, else a temporary git worktree at its commit, else nothing. Each check prints with its result, the
 * findings under a failed one, then the new verdict under the recorded one. Nothing is written to the tracker, the
 * issue or the attempt's records; one `replay_verdict` event records the replay. A signal stops the checks and removes
 * the site, and records nothing. Exit 0 printed whatever the new verdict, 1, 2, 3 and 5 as `--prompt`, 4 no recorded
 * verdict, or a recorded state that is gone or changed and cannot be rebuilt.
 */
class ReplayCommand(
    private val host: Host,
) : GuardedCommand("replay", host) {
    private val attempt by argument(name = "attempt", help = "The attempt id, or a unique prefix of it")
    private val prompt by option(
        "--prompt",
        help = "Render the turn 1 prompt again with the current template and diff it with the recorded one",
    ).flag()
    private val verify by option(
        "--verify",
        help = "Run the checks of the current workflow again on the workspace state the attempt's verdict recorded",
    ).flag()
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Replay a recorded attempt with the current config"

    override fun body(scope: RedactorScope) {
        if (prompt && verify) throw UsageError("pick one mode: --prompt or --verify")
        if (!prompt && !verify) throw UsageError("pick a mode: --prompt or --verify")
        if (verify) return replayVerification(scope)
        // As `loop show`: the store is read only and keeps Redactor.NONE, the messages and the render go through the redactor.
        val project = host.openProject(workflowPath, Access.READ, scope)
        val projectId = project.projectId
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        val (attemptId, issueEvents) =
            store.use {
                val attemptId = resolveAttempt(store, projectId, attempt, storeFile, ::fail)
                val own =
                    runBlocking { store.eventsForAttempt(attemptId) }
                        .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                        .map { it.event }
                        .filter { it.projectId == projectId }
                val issueId = own.firstNotNullOfOrNull { it.issueId } ?: fail("attempt ${attemptId.value} names no issue", EXIT_STORE)
                attemptId to
                    runBlocking { store.eventsForIssue(projectId, issueId) }
                        .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                        .map { it.event }
            }
        val recorded =
            recordedPrompt(projectId, attemptId)
                ?: fail("no prompt recorded for attempt ${attemptId.value}, nothing to replay", EXIT_NO_PROMPT)
        val started = issueEvents.firstOrNull { it.attemptId == attemptId && it.kind == AttemptStarted.name }
        val own = issueEvents.first { it.attemptId == attemptId }
        val issueId = own.issueId!!
        val name = own.issueIdentifier?.let { project.ids.name(issueId, it) } ?: project.ids.id(issueId)
        val issue = currentIssue(project, issueId, name)
        val workflow = project.workflow
        val (kind, kindNote) = kindOf(workflow, started?.data?.get(AttemptStarted.KIND), issue, "rendered")
        val template = KindPrompt.template(workflow, kind)
        val variables =
            RecordedPromptVariables.recover(
                attemptId,
                issueEvents,
                declaresContextSources =
                    kind
                        ?.config
                        ?.context
                        ?.files
                        .orEmpty()
                        .isNotEmpty(),
            )
        val rendered =
            TemplateEngine()
                .parse(template)
                .mapCatching { it.render(variablesOf(issue, variables)).getOrThrow() }
                .getOrElse { fail("the prompt template of ${workflow.path} does not render: ${it.sentence()}", EXIT_RENDER) }
        val header =
            listOfNotNull(
                "Replay of the turn 1 prompt of attempt ${attemptId.value}, issue $name.",
                "Recorded with template ${version(started?.data?.get(AttemptStarted.TEMPLATE_VERSION))}.",
                "Rendered with template ${version(workflowHash(template))} of ${workflow.path}, with the issue as the tracker " +
                    "returns it now, its text may have changed since the attempt.",
                kindNote,
                "Recovered: ${recoveredOf(variables).joinToString(", ")}.",
                variables.missing.takeIf { it.isNotEmpty() }?.let { "Not recovered, rendered empty or false: ${it.joinToString(", ")}." },
            )
        echo(redact(header.joinToString("\n")))
        echo("")
        echo(UnifiedDiff.of(recorded, redact(rendered), "recorded prompt", "rendered prompt") ?: "No difference: the same prompt.")
    }

    /**
     * `--verify` (m4-41): the checks of the current workflow run again on the state the attempt's last verdict recorded,
     * then the new verdict prints under the recorded one and one `replay_verdict` event records the replay.
     */
    private fun replayVerification(scope: RedactorScope) {
        // The store records the replay, so it opens as a writer, every write through the project's redactor.
        val project = host.openProject(workflowPath, Access.WRITE, scope)
        val projectId = project.projectId
        val storeFile = project.storeFile
        val opened = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        val (attemptId, own, frozen) =
            opened.use { store ->
                val attemptId = resolveAttempt(store, projectId, attempt, storeFile, ::fail)
                val own =
                    runBlocking { store.eventsForAttempt(attemptId) }
                        .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                        .map { it.event }
                        .filter { it.projectId == projectId }
                val issueId = own.firstNotNullOfOrNull { it.issueId } ?: fail("attempt ${attemptId.value} names no issue", EXIT_STORE)
                // Read once here: the checks run with the store closed, and the frozen list is never written.
                Triple(attemptId, own, runBlocking { store.frozen(projectId, issueId) })
            }
        val recorded =
            RecordedVerdict.of(own) ?: fail("no verdict recorded for attempt ${attemptId.value}, nothing to replay", EXIT_NOT_REPLAYABLE)
        val first = own.first { it.issueId != null }
        val issueId = first.issueId!!
        val name = first.issueIdentifier?.let { project.ids.name(issueId, it) } ?: project.ids.id(issueId)
        val issue = currentIssue(project, issueId, name)
        val workflow = project.workflow
        val started = own.firstOrNull { it.kind == AttemptStarted.name }
        val (kind, kindNote) = kindOf(workflow, started?.data?.get(AttemptStarted.KIND), issue, "checked")
        val identifier = first.issueIdentifier ?: issue.identifier
        val located = Workspaces(workflow.workspaceRoot).locate(NamespacedId.workspaceKey(issueId, identifier, workflow.trackerId))
        val target = ReplayTarget(attemptId, issueId, identifier, issue.description, workflow, kind)
        val replay = VerificationReplay(projectId, host.loopHome, project.redactor, checkRunner(workflow), frozen = { frozen })
        val sites = ReplaySites(warn = { echo(redact("loop replay: $it."), err = true) })
        // The site is placed and checked under the shutdown hook, so a signal during the copy or a check still removes it.
        val (site, replayed) =
            untilSignal {
                when (val placed = sites.place(recorded.fingerprint, located, workflow.path.parent)) {
                    is Placement.Refused ->
                        fail(
                            "cannot replay the verification of attempt ${attemptId.value}: ${placed.reason}, " +
                                "recorded fingerprint ${placed.recorded}, current fingerprint ${placed.current}",
                            EXIT_NOT_REPLAYABLE,
                        )
                    is Placement.Ready ->
                        placed.site.use { site ->
                            currentCoroutineContext().ensureActive()
                            val header =
                                listOfNotNull(
                                    "Replay of the verification of attempt ${attemptId.value}, issue $name.",
                                    "Checks of ${kind?.let { "the kind ${it.name} in " }.orEmpty()}${workflow.path}, with the issue as " +
                                        "the tracker returns it now, its text may have changed since the attempt.",
                                    kindNote,
                                ) + siteLines(site)
                            echo(redact(header.joinToString("\n")))
                            echo("")
                            site to replay.run(target, recorded, site)
                        }
                }
            } ?: throw ProgramResult(EXIT_STOPPED)
        replayed.checks.forEach { echo(redact(it.line())) }
        if (replayed.checks.isEmpty()) echo("No check ran.")
        // Under the failed check, the last line; a hidden check has no findings.
        replayed.findings?.let { findings -> echo(redact(findings.lines().joinToString("\n") { "  $it" })) }
        if (replayed.baseUnpinned) echo("No base was recorded for this verification, diff_policy read guards.base as it is now.")
        echo("")
        echo(redact("Recorded verdict: ${recorded.verdict}, on ${recorded.fingerprint}."))
        echo(redact("New verdict: ${replayed.verdict}, on ${replayed.fingerprint}${replayed.reason?.let { ", $it" }.orEmpty()}."))
        val event = VerificationReplay.event(Clock.System.now(), projectId, target, recorded, site, replayed)
        project
            .store()
            .mapCatching { store -> store.use { runBlocking { it.record(event) }.getOrThrow() } }
            .getOrElse { fail("cannot record the replay in the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
    }

    /** Where the checks run, as the header says it. */
    private fun siteLines(site: ReplaySite): List<String> =
        when (site) {
            is ReplaySite.WorkspaceCopy ->
                listOf("The checks run on a copy of the workspace ${site.workspace}, removed afterwards: ${site.dir}.")
            is ReplaySite.Worktree ->
                listOf(
                    "${site.state.replaceFirstChar { it.uppercase() }}, so the checks run on a temporary git worktree at ${site.head} " +
                        "of ${site.repository}, removed afterwards: ${site.dir}.",
                    "A rebuilt worktree holds the commit only: no ignored file and no workspace hook setup.",
                )
        }

    /**
     * Runs [block] under a shutdown hook, as `loop run` does: on SIGINT or SIGTERM the hook cancels it, which stops a
     * check's process group, and waits up to [SHUTDOWN_WAIT_SECONDS] for it to unwind, so the site it placed is removed
     * before the JVM exits. Returns null when a signal stopped it, after saying so on stderr.
     */
    private fun <T> untilSignal(block: suspend () -> T): T? {
        val job = Job()
        val stopped = CountDownLatch(1)
        val hook =
            Thread({
                job.cancel()
                stopped.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
            }, "loop-replay-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            return runBlocking(job) { block() }
        } catch (e: CancellationException) {
            if (!job.isCancelled) throw e
            // Before the latch: once it opens, the hook returns and the JVM exits.
            echo("loop replay: stopped by a signal, the site is removed and the replay is not recorded.", err = true)
            return null
        } finally {
            stopped.countDown()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (e: IllegalStateException) {
                // The JVM is already shutting down, the hook is running.
            }
        }
    }

    /**
     * The check runner of `loop run` (m2-03): a check runs code the agent wrote, so it gets the agent env policy, never
     * the full host env, plus the ids the verification adds. A check that declares `resource:` does not run: a replay
     * takes no resource lock, a store write, so the check would run with no lock and no `LOOP_RESOURCE_<KEY>` env. It
     * could not run, and the verdict is `unverified`.
     */
    private fun checkRunner(workflow: WorkflowFile): CheckRunner {
        val removed = agentEnvRemovedNames(workflow)
        val runner = coreCheckRunnerOf(workflow)
        return CheckRunner {
            check,
            cwd,
            env,
            ->
            val resource = (check as? CheckConfig.Command)?.resource
            if (resource != null) {
                CheckResult(null, false, 0, startError = "a replay holds no $resource resource, the check did not run")
            } else {
                runner.run(check, cwd, agentEnvironment(overrides = env, removed = removed, host = host.rawEnv))
            }
        }
    }

    /** The turn 1 prompt of the Attempt file, as written, redacted then; null when none was recorded. */
    private fun recordedPrompt(
        projectId: ProjectId,
        attemptId: AttemptId,
    ): String? {
        val file = attemptFile(host.loopHome, projectId, attemptId)
        // Read only: the redactor is never used on read.
        val read =
            GzipAttemptRecords(Redactor.NONE) { project, attempt -> attemptFile(host.loopHome, project, attempt) }
                .read(projectId, attemptId)
                .getOrElse { fail("cannot read the attempt file at $file: ${it.sentence()}", EXIT_STORE) }
        if (read.damaged > 0) {
            val parts = if (read.damaged == 1) "1 cut or unreadable part" else "${read.damaged} cut or unreadable parts"
            echo(redact("loop replay: the attempt file at $file has $parts, left out."), err = true)
        }
        return read.records
            .map { it.record }
            .filterIsInstance<AttemptRecord.Prompt>()
            .firstOrNull { it.turn == 1 }
            ?.text
    }

    /** The issue [issueId] as the tracker returns it now, completed as a dispatch completes it (bl-06). */
    private fun currentIssue(
        project: OpenedProject,
        issueId: String,
        name: String,
    ): Issue {
        val tracker = project.trackerPoll()
        val where = trackerLocation(project.workflow)
        val issue =
            runBlocking { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { fail("cannot read $name in $where: ${it.sentence()}", EXIT_RENDER) }
                .firstOrNull()
                ?: fail("$where has no issue $name now, nothing to replay", EXIT_RENDER)
        return runBlocking { tracker.completeIssue(issue) }
            .getOrElse { fail("cannot read $name in $where: ${it.sentence()}", EXIT_RENDER) }
    }

    /**
     * The kind the attempt rendered for, [recorded] by `attempt_started`, then a header line when the workflow no longer
     * holds it and the kind [issue] matches now is used instead.
     */
    private fun kindOf(
        workflow: WorkflowFile,
        recorded: String?,
        issue: Issue,
        done: String,
    ): Pair<IssueKind?, String?> =
        when (val pinned = KindLookup.pinned(workflow, recorded, issue)) {
            is PinnedKind.Found -> pinned.kind to null
            is PinnedKind.Gone -> {
                val now = KindLookup.of(workflow, issue)
                now to "The kind ${pinned.name} is no longer in the workflow, $done with the kind ${now?.name} the issue matches now."
            }
        }

    private fun variablesOf(
        issue: Issue,
        recovered: RecoveredVariables,
    ): Map<String, Any?> =
        PromptVariables.of(
            issue,
            recovered.attempt,
            recovered.verifyFindings,
            recovered.workspaceReused,
            recovered.sessionBrief,
            recovered.retryKind,
            recovered.contextSources,
            recovered.answer,
        )

    /** What the header says was recovered: the dispatch, then each short value the render used. */
    private fun recoveredOf(variables: RecoveredVariables): List<String> =
        listOfNotNull(
            "a first dispatch".takeIf { variables.attempt == null && variables.retryKind == null },
            variables.attempt?.let { "attempt $it" },
            variables.retryKind?.let { "loop.retry_kind $it" },
            variables.verifyFindings?.let { "loop.verify_findings of ${it.length} characters" },
            "loop.workspace_reused ${variables.workspaceReused}".takeIf { RecordedPromptVariables.WORKSPACE_REUSED !in variables.missing },
        )

    /** A template version as the header shows it, its first 12 hex characters. */
    private fun version(hash: String?): String = hash?.take(VERSION_CHARS) ?: "version not recorded"

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop replay: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val EXIT_NO_PROMPT = 4

        /** No verdict to replay, or a recorded state that is gone or changed and cannot be rebuilt (m4-41). */
        const val EXIT_NOT_REPLAYABLE = 4

        /** Stopped by a signal; the JVM exits with the signal's own status once the shutdown hook returns. */
        const val EXIT_STOPPED = 130

        /** How long the shutdown hook waits for the checks to stop and the site to go, as `loop run` waits. */
        const val SHUTDOWN_WAIT_SECONDS = 10L
        const val EXIT_RENDER = 5
        const val VERSION_CHARS = 12
    }
}
