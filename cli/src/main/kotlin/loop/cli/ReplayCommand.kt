package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
import loop.core.replay.RecordedPromptVariables
import loop.core.replay.RecoveredVariables
import loop.core.replay.UnifiedDiff
import loop.core.template.TemplateEngine
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowFile
import loop.core.workflow.workflowHash
import loop.store.GzipAttemptRecords

/**
 * `loop replay <attempt> --prompt [--workflow WORKFLOW.md]` (m4-40, story 137, PLAN 10.4): the turn 1 prompt of a
 * recorded attempt rendered again with the prompt template of the current `WORKFLOW.md`, then diffed with the prompt
 * the Attempt file recorded, as `loop show --prompt` prints it. The variables are the attempt's own as far as the store
 * recorded them ([RecordedPromptVariables]); the issue is the one the tracker returns now. The render goes through the
 * project's redactor before it is diffed. Read only: nothing is written to the store, the tracker or the workspace.
 * Exit 0 printed, 1 startup failure, 2 usage or an unknown attempt, 3 store or Attempt file failure, 4 no recorded
 * prompt, 5 the issue cannot be read or the template does not render.
 */
class ReplayCommand(
    private val host: Host,
) : GuardedCommand("replay", host) {
    private val attempt by argument(name = "attempt", help = "The attempt id, or a unique prefix of it")
    private val prompt by option(
        "--prompt",
        help = "Render the turn 1 prompt again with the current template and diff it with the recorded one",
    ).flag()
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Replay a recorded attempt with the current config"

    override fun body(scope: RedactorScope) {
        if (!prompt) throw UsageError("pick a mode: --prompt, the only mode so far")
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
        val (kind, kindNote) = kindOf(workflow, started?.data?.get(AttemptStarted.KIND), issue)
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
                ?: fail("$where has no issue $name now, nothing to render", EXIT_RENDER)
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
    ): Pair<IssueKind?, String?> =
        when (val pinned = KindLookup.pinned(workflow, recorded, issue)) {
            is PinnedKind.Found -> pinned.kind to null
            is PinnedKind.Gone -> {
                val now = KindLookup.of(workflow, issue)
                now to "The kind ${pinned.name} is no longer in the workflow, rendered with the kind ${now?.name} the issue matches now."
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
        const val EXIT_RENDER = 5
        const val VERSION_CHARS = 12
    }
}
