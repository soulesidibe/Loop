package loop.core.verify

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import loop.api.AttemptId
import loop.api.HumanTouch
import loop.api.Issue
import loop.api.IssueComment
import loop.api.WaitReason
import loop.core.delivery.AttachDelivery
import loop.core.delivery.AutoMerge
import loop.core.delivery.DeliveryFacts
import loop.core.delivery.DeliveryOutcome
import loop.core.delivery.EvidenceSummary
import loop.core.delivery.PullRequestDelivery
import loop.core.events.AcceptanceCommands
import loop.core.events.AcceptanceCommandsChanged
import loop.core.events.AcceptanceCommandsEmpty
import loop.core.events.ApprovalRequested
import loop.core.events.BaseFetched
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.CheckStarted
import loop.core.events.Delivery
import loop.core.events.DiffBaseUnpinned
import loop.core.events.EventDefinition
import loop.core.events.EvidenceRecordFailed
import loop.core.events.HumanTouched
import loop.core.events.IssueCommented
import loop.core.events.IssueMoved
import loop.core.events.Results
import loop.core.events.Tracker
import loop.core.events.Verdict
import loop.core.kinds.DeliverableFile
import loop.core.kinds.DeliverableFiles
import loop.core.kinds.DeliverableListing
import loop.core.kinds.IssueKind
import loop.core.kinds.KindWorkspaces
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.trackers.NamespacedId
import loop.core.waiting.Wait
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.TokenCounters
import loop.core.workflow.WorkflowFile
import loop.core.workflow.acceptanceCommandsNeedFiles
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import loop.core.events.JudgmentCalls as JudgmentCallsEvent

/**
 * The checks of one submitted result and the move they gate (m2-03). An attempt runs one in its drain; the
 * first tick after a restart runs one for each `Waiting(verify)` a dead process left, outside any attempt
 * (m2-04), recording in this run with the attempt id of the wait. [record] records an event of that attempt.
 * [description] is the issue's, read for the commands of its acceptance criteria when the kind declares
 * `acceptance_commands` (m2-09).
 */
class Verification(
    val issueId: String,
    val identifier: String,
    val description: String?,
    val attemptId: AttemptId?,
    val workflow: WorkflowFile,
    /**
     * The kind of the issue (m4-18), whose checks, holdouts and delivery apply, and whose name the approval wait
     * holds; null in Symphony mode.
     */
    val kind: IssueKind?,
    val workspace: Path,
    val checkRunner: CheckRunner,
    val record: suspend (EventDefinition, Map<String, String>) -> Unit,
    val logContext: () -> LogContext,
    /** True for a state the attempt itself moved the issue to, so its own move is never taken for a human's. */
    val ownMove: (String) -> Boolean = { false },
    /**
     * The base of every `diff_policy` check instead of `guards.base`, for the merge check (m2-26, bl-32): the base
     * commit it fetched, so other people's commits on the base never count as the attempt's diff. Null for an
     * ordinary verification, which fetches its own when the kind delivers a pull request, see [pinnedBase].
     */
    val diffBase: String? = null,
    /**
     * True to run only the `diff_policy` checks, visible and hidden, each at its number in the full list (bl-32):
     * the merge check of a branch the fetched base is already in. No acceptance command gate, no judgment call.
     */
    val diffPolicyOnly: Boolean = false,
    /** The run this verification records in and the steps that stay with the orchestrator (m4-54). */
    private val host: VerificationHost,
) {
    private val projectId get() = host.projectId
    private val runId get() = host.runId
    private val loopHome get() = host.loopHome
    private val tracker get() = host.tracker
    private val log get() = host.log
    private val clock get() = host.clock
    private val redactor get() = host.redactor
    private val io get() = host.io
    private val fingerprinter get() = host.fingerprinter
    private val evidence get() = host.evidence
    private val frozenCommands get() = host.frozenCommands
    private val postedDeliverables get() = host.postedDeliverables

    private val configured: List<CheckConfig> = kind?.config?.checks.orEmpty()

    /** The hidden checks (m2-14), run after every visible check passed and counted in the verdict like them. */
    private val holdouts: List<CheckConfig> = kind?.config?.holdout.orEmpty()

    /** The issue's acceptance criteria when the kind declares `acceptance_commands`, null otherwise (m2-09). */
    private val criteria: List<AcceptanceCriterion>? =
        if (configured.any { it is CheckConfig.AcceptanceCommands }) AcceptanceCriteria.of(description) else null

    /**
     * Why the commands of the criteria must not run, set by [prepare], null when they may (m2-09): the tracker
     * is not `files`, the frozen list cannot be read, or the criteria differ from the list frozen at the first
     * dispatch. When set, the verification runs nothing and ends `unverified` with it as the reason.
     */
    private var refusal: String? = null
    private var prepared = false

    /** The number of judgment calls of the last [verify], for the pull request body (m2-25). */
    private var judgmentCallCount = 0

    /**
     * The checks listed in config order: `acceptance_commands` stands for one `command` check per command of
     * the criteria, with its timeout, in its place next to the core checks (m2-09, m2-12). Numbered from 0 in
     * this order. Empty when the kind declares `acceptance_commands` and only `diff_policy` checks are left:
     * a guard alone proves no work.
     */
    private val listed: List<CheckConfig> =
        configured
            .flatMap { check ->
                when (check) {
                    is CheckConfig.AcceptanceCommands -> criteria.orEmpty().commands.map { CheckConfig.Command(it, check.timeoutMs) }
                    is CheckConfig.Command, is CheckConfig.FileExists, is CheckConfig.FileMatches, is CheckConfig.DiffPolicy,
                    is CheckConfig.ChangedLineCoverage,
                    -> listOf(check)
                }
            }.takeUnless { expanded ->
                !diffPolicyOnly && criteria != null && expanded.all { it is CheckConfig.DiffPolicy } && holdouts.isEmpty()
            }.orEmpty()

    /** The checks this verification runs: none once [prepare] refused the commands of the criteria (m2-09). */
    private val checks: List<CheckConfig> get() = if (refusal != null) emptyList() else listed

    /** The holdouts this verification runs: none once [prepare] refused the commands of the criteria (m2-09). */
    private val hidden: List<CheckConfig> get() = if (refusal != null) emptyList() else holdouts

    /** Whether there is a check to run; read after [prepare]. */
    val hasChecks: Boolean
        get() = (checks + hidden).any { !diffPolicyOnly || it is CheckConfig.DiffPolicy }

    /**
     * Why there is nothing to run: the refusal of the commands, the kind has no check, or `acceptance_commands`
     * found no command and no other check proves work (m2-09).
     */
    private val noCheck: String get() = refusal ?: if (configured.isEmpty()) NO_CHECK else NO_ACCEPTANCE_COMMAND

    /**
     * Checks the commands of the criteria against the trust gate and the list frozen at the first dispatch, once,
     * before anything runs (m2-09); [verify] calls it when the caller did not. A list that differs records
     * `acceptance_commands_changed`, keeps it in the store as the changed list `loop resume` can accept, and
     * refuses the commands: the issue parks `unverified` with the reason `acceptance commands changed`. A list
     * that matches forgets a changed list an earlier park left, so a later resume cannot accept it.
     */
    suspend fun prepare() {
        if (prepared) return
        prepared = true
        // bl-32: no command of the criteria runs, so their gate has nothing to refuse.
        if (diffPolicyOnly) return
        val commands = criteria?.commands ?: return
        val kind = workflow.trackers.entryOf(issueId).kind
        if (kind != FILES_TRACKER) {
            refusal = acceptanceCommandsNeedFiles(kind)
            return
        }
        val digest = AcceptanceCriteria.digest(commands)
        val stored =
            host.freezeAcceptanceCommands(issueId, identifier, commands, digest, record).getOrElse { error ->
                refusal = redactor.redact("cannot read the frozen acceptance commands: ${error.describe()}").take(REASON_MAX)
                return
            }
        if (stored.digest == digest) {
            if (stored.changedDigest != null) forgetChangedList()
            return
        }
        try {
            frozenCommands.markChanged(projectId, issueId, digest, commands.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }.onFailure { error ->
            val reason = redactor.redact("cannot keep the changed acceptance commands: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, ACCEPTANCE_COMMANDS, LogOutcome.FAILED, logContext(), reason = reason)
        }
        record(
            AcceptanceCommandsChanged,
            mapOf(AcceptanceCommands.FROZEN to stored.count.toString(), AcceptanceCommands.COUNT to commands.size.toString()),
        )
        log.log(
            LogLevel.WARN,
            AcceptanceCommandsChanged.name,
            LogOutcome.FAILED,
            logContext(),
            mapOf(
                "frozen" to stored.count,
                "count" to commands.size,
            ),
        )
        refusal = ACCEPTANCE_COMMANDS_CHANGED_REASON
    }

    /**
     * Forgets the changed list of the issue once its criteria match the frozen list again (m2-09). A failure is
     * logged and the verification goes on: `loop resume` accepts a changed list only for an issue parked
     * because of it, and only when the issue still holds it.
     */
    private suspend fun forgetChangedList() {
        try {
            frozenCommands.clearChanged(projectId, issueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }.onFailure { error ->
            val reason = redactor.redact("cannot forget the changed acceptance commands: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, ACCEPTANCE_COMMANDS, LogOutcome.FAILED, logContext(), reason = reason)
        }
    }

    /** The findings of the failed check when [verify] ended `refuted`, null otherwise (m2-06). */
    var findings: VerifyFindings? = null
        private set

    /** Why [verify] ended `unverified`, null otherwise (m2-08). */
    var unverified: String? = null
        private set

    /**
     * The checks the last [verify] did not pass, for the progress between rotations (m3-12): it stops at the first
     * failure, so the failing check and every check after it, plus one when a verified work tree is refuted for
     * its uncommitted files.
     */
    var failingChecks: Int = 0
        private set

    /** The fingerprint of the last [verify] and the check runs that passed in it, for the evidence summary (m2-24). */
    private var verifiedFingerprint: String = UNKNOWN_FINGERPRINT
    private val passed = mutableListOf<EvidenceSummary.Check>()

    /** The deliverable files the last [verify] listed (m4-21), which the attach delivery posts (m4-22). */
    private var deliverableFiles: List<DeliverableFile>? = null

    /** True when the last [verify] saved its deliverable evidence (m4-22). */
    private var deliverableSaved = false

    /**
     * Runs the kind's checks in order, then its holdouts (m2-14), stopping at the first failure; true only
     * when there is one and all passed. A holdout's index follows the visible checks'. A check that ran and
     * failed runs once more, right away, so a flaky test does not refute good work (m2-08); both runs are
     * evidence. The workspace fingerprint is taken once, before the first check, and stamps each check's
     * evidence. The verification ends with a `verdict` event (m2-05): `verified` when every check passed,
     * `unverified` when there is no check or one could not run (m2-08), `refuted` when a check failed twice.
     * A cancel records no verdict. The acceptance criteria with no command are saved as judgment calls before
     * the first check and counted by the verdict, never as passed, and criteria with no command at all record
     * `acceptance_commands_empty` (m2-09). When the kind delivers a pull request, a work tree whose fingerprint
     * holds uncommitted changes or untracked files is `refuted` even when every check passed, its findings naming
     * those files as they were when the fingerprint was taken, and the verdict says how many (m2-25). When it
     * delivers a pull request and the work tree was clean before the checks, it is restored to its commit after
     * them, so a file a check rewrote or created never refutes a later verification (m2-25).
     */
    suspend fun verify(): Boolean {
        prepare()
        val fingerprint = fingerprint()
        // m2-25: listed with the fingerprint, before a check can regenerate a file.
        val uncommittedAtStart = uncommittedFiles(fingerprint)
        // m4-21: listed with the fingerprint too, so a file a check writes is never taken for the deliverable.
        val deliverable = withContext(io) { DeliverableFiles.listing(kind, workspace) }
        // m2-09: a refused list uses nothing of the criteria. bl-32: a diff_policy only run reads none of them.
        val read = criteria?.takeIf { refusal == null && !diffPolicyOnly }
        val judgmentCalls = read.orEmpty().judgmentCalls
        judgmentCallCount = judgmentCalls.size
        if (judgmentCalls.isNotEmpty()) saveJudgmentCalls(judgmentCalls, fingerprint)
        if (read != null && read.commands.isEmpty()) {
            // A typo such as `pass` for `passes` leaves no command: say so, with what was read instead.
            record(AcceptanceCommandsEmpty, mapOf(AcceptanceCommands.JUDGMENT_CALLS to judgmentCalls.size.toString()))
            log.log(
                LogLevel.WARN,
                AcceptanceCommandsEmpty.name,
                LogOutcome.COMPLETED,
                logContext(),
                mapOf(
                    JUDGMENT_CALLS to judgmentCalls.size,
                ),
            )
        }
        val references = mutableListOf<String>()
        var ran = 0
        var verdict = if (hasChecks) VERIFIED else UNVERIFIED
        findings = null
        unverified = if (hasChecks) null else noCheck
        verifiedFingerprint = fingerprint
        passed.clear()
        val pinned = pinnedBase()
        val all = checks.map { it to false } + hidden.map { it to true }
        for ((index, entry) in all.withIndex()) {
            val (listedCheck, holdout) = entry
            if (diffPolicyOnly && listedCheck !is CheckConfig.DiffPolicy) continue
            // bl-117: changed_line_coverage reads the same diff as diff_policy, so it takes the same pinned base.
            val check =
                when {
                    pinned == null -> listedCheck
                    listedCheck is CheckConfig.DiffPolicy -> listedCheck.copy(base = pinned)
                    listedCheck is CheckConfig.ChangedLineCoverage -> listedCheck.copy(base = pinned)
                    else -> listedCheck
                }
            ran++
            var outcome = runCheck(index, check, fingerprint, holdout, rerun = false)
            outcome.evidence?.let { references += it }
            if (outcome.result.refutes) {
                outcome = runCheck(index, check, fingerprint, holdout, rerun = true)
                outcome.evidence?.let { references += it }
            }
            val result = outcome.result
            if (result.passed) {
                val label = redactor.redact(check.script)
                passed +=
                    EvidenceSummary.Check(index, label, result.exitCode, result.durationMs, outcome.rerun, outcome.evidence, holdout)
                continue
            }
            val couldNotRun = result.couldNotRun
            if (couldNotRun != null) {
                verdict = UNVERIFIED
                // m2-14: why a holdout could not run is its own, so the reason names only the check.
                unverified =
                    if (holdout) {
                        "hidden check $index could not run"
                    } else {
                        redactor
                            .redact(
                                "check $index could not run: $couldNotRun",
                            ).take(REASON_MAX)
                    }
            } else {
                verdict = REFUTED
                // m3-14: trimmed to context.tool_output_tokens; the full output stays in the evidence file.
                findings =
                    if (holdout) {
                        VerifyFindings.hidden()
                    } else {
                        VerifyFindings.of(
                            check.script,
                            result,
                            redactor,
                            workflow.config.context.toolOutputTokens,
                            TokenCounters.forModel(host.model),
                        )
                    }
            }
            break
        }
        // m2-25: what a check wrote is not the agent's work, so a work tree clean before the checks is clean after them.
        if (!plain && deliversPullRequest && WorkspaceFingerprint.committedHead(fingerprint) != null) restoreWorkTree()
        // m2-25: a pull request carries only commits, so a verified work tree that holds more is refuted.
        val uncommitted = uncommittedAtStart?.takeIf { verdict == VERIFIED }
        if (uncommitted != null) {
            verdict = REFUTED
            findings = VerifyFindings.uncommitted(uncommitted, redactor)
        }
        // m4-21: a kind with deliverable.files proves nothing without one. m4-23: nor a report kind without its whole report.
        val missing = (deliverable as? DeliverableListing.Listed)?.takeIf { it.refuted && verdict == VERIFIED }
        if (missing != null) {
            verdict = REFUTED
            findings = VerifyFindings.deliverable(missing, redactor)
        }
        if (verdict == VERIFIED && deliverable is DeliverableListing.Failed) {
            verdict = UNVERIFIED
            unverified = redactor.redact(deliverable.reason).take(REASON_MAX)
        }
        val listedFiles = (deliverable as? DeliverableListing.Listed)?.files
        deliverableFiles = listedFiles
        val deliverableEvidence = listedFiles?.let { saveDeliverable(DeliverableEvidence(fingerprint, it)) }
        deliverableSaved = deliverableEvidence != null
        val listed = all.count { (check, _) -> !diffPolicyOnly || check is CheckConfig.DiffPolicy }
        failingChecks = listed - passed.size + (if (uncommitted != null) 1 else 0) + (if (missing != null) 1 else 0)
        record(
            Verdict,
            mapOfNotNull(
                Verdict.VERDICT to verdict,
                Verdict.FINGERPRINT to fingerprint,
                Verdict.CHECKS to ran.toString(),
                Verdict.EVIDENCE to references.joinToString(",").ifEmpty { null },
                Verdict.REASON to unverified,
                Verdict.JUDGMENT_CALLS to judgmentCalls.size.takeIf { it > 0 }?.toString(),
                Verdict.UNCOMMITTED to uncommitted?.let { (it.changed.size + it.untracked.size).toString() },
                Verdict.DELIVERABLE to listedFiles?.size?.toString(),
                Verdict.DELIVERABLE_EVIDENCE to deliverableEvidence,
                Verdict.REPORT_FINDINGS to
                    missing
                        ?.problems
                        ?.size
                        ?.takeIf { it > 0 }
                        ?.toString(),
            ),
        )
        log.log(
            LogLevel.INFO,
            Verdict.name,
            LogOutcome.COMPLETED,
            logContext(),
            mapOf(
                Verdict.VERDICT to verdict,
                "fingerprint" to fingerprint,
            ),
        )
        return verdict == VERIFIED
    }

    /**
     * The commit every `diff_policy` check of this verification is based on instead of `guards.base` (bl-32), and
     * every `changed_line_coverage` check instead of its `base` (bl-117):
     * [diffBase] when the merge check gave one, else, when the kind delivers a pull request and a `diff_policy` is
     * to run, the base fetched host side now through the delivery executor. The commit id is a value, never a ref
     * read back from the repository, so an agent that moves `guards.base` or `refs/loop/base/<base>` cannot empty
     * the diff, and base commits it merged never count as its diff. Null otherwise, and when the fetch fails,
     * which records `diff_base_unpinned` with the reason and keeps `guards.base`: the verdict is the checks' alone.
     */
    private suspend fun pinnedBase(): String? {
        if (diffBase != null) return diffBase
        // m4-20: a plain kind has no git diff to base.
        if (plain) return null
        // m4-18: the issue's kind, like `attemptBase`; only the code kind delivers a pull request so far.
        val base =
            kind
                ?.config
                ?.delivery
                ?.pullRequest
                ?.base ?: return null
        if ((checks + hidden).none { it is CheckConfig.DiffPolicy }) return null
        return host.fetchBase(workflow).fold(
            onSuccess = { sha ->
                record(BaseFetched, mapOf(Delivery.SHA to sha, Delivery.BASE to base))
                sha
            },
            onFailure = { error ->
                val reason = redactor.redact("cannot fetch $base: ${error.describe()}").take(REASON_MAX)
                record(DiffBaseUnpinned, mapOf(DiffBaseUnpinned.REASON to reason))
                log.log(LogLevel.WARN, DiffBaseUnpinned.name, LogOutcome.FAILED, logContext(), reason = reason)
                null
            },
        )
    }

    /** True when the kind declares `workspace: plain` (m4-20): no git read, a `tree:` fingerprint. */
    private val plain: Boolean get() = KindWorkspaces.isPlain(kind)

    /** True when the kind delivers a pull request (m2-25). */
    private val deliversPullRequest: Boolean
        get() =
            kind
                ?.config
                ?.delivery
                ?.pullRequest != null

    /**
     * Puts the work tree back to its commit after the checks (m2-25): the tracked files a check rewrote and the
     * untracked files it created. A failure is a warning: the next verification refutes what is left.
     */
    private suspend fun restoreWorkTree() {
        val restored =
            try {
                withContext(io) { fingerprinter.restore(workspace) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        restored.onFailure { error ->
            val reason = redactor.redact("cannot restore the work tree after the checks: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, "restore", LogOutcome.FAILED, logContext(), reason = reason)
        }
    }

    /**
     * The files a commit does not hold when the kind delivers a pull request and [fingerprint], of a git work tree,
     * does not name a clean commit (m2-25); null otherwise, so the verdict stands. Files that cannot be listed give
     * empty lists, and the finding says so.
     */
    private suspend fun uncommittedFiles(fingerprint: String): UncommittedFiles? {
        if (plain || !deliversPullRequest) return null
        if (!fingerprint.startsWith("git:") || WorkspaceFingerprint.committedHead(fingerprint) != null) return null
        val listed =
            try {
                withContext(io) { fingerprinter.uncommitted(workspace) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return listed.getOrElse { error ->
            val reason = redactor.redact("cannot list the uncommitted files: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, "fingerprint", LogOutcome.FAILED, logContext(), reason = reason)
            UncommittedFiles(emptyList(), emptyList())
        }
    }

    /** The fingerprint of the workspace now, [UNKNOWN_FINGERPRINT] with a warning when it cannot be taken. */
    private suspend fun fingerprint(): String {
        val result =
            try {
                withContext(io) { PlainWorkspace.fingerprint(fingerprinter, workspace, plain) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return result.getOrElse { error ->
            val reason = redactor.redact("cannot take the workspace fingerprint: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, "fingerprint", LogOutcome.FAILED, logContext(), reason = reason)
            UNKNOWN_FINGERPRINT
        }
    }

    /**
     * Delivers the verified result by the kind's policy (m2-24): `auto` moves the issue to [to] at once, `human`
     * starts a `Waiting(approval)` with an evidence summary on the issue. The failure's reason, null otherwise.
     */
    suspend fun deliver(to: String): String? {
        val delivery = kind?.config?.delivery ?: DeliveryConfig()
        // m4-22: an attach delivery ends in on_delivered.move_to, its handoff state when absent.
        val target = if (delivery.attach) delivery.onDeliveredMoveTo ?: to else to
        if (delivery.policy != DeliveryPolicy.Auto) return requestApproval(target)
        // The loader refuses attach under auto without its opt in; a config built past it waits for approval.
        if (delivery.attach) return if (delivery.attachAutoEnabled) deliverAttached(target) else requestApproval(target)
        // m2-28: an auto merge goes through the m2-25 delivery and the m2-26 merge check, approved by `auto` itself;
        // the auto merge gate decides right before the merge.
        if (delivery.pullRequest?.merge != null) return requestAutoMerge(to)
        return if (delivery.pullRequest != null) deliverPullRequest(to) else move(to)
    }

    /**
     * Starts the auto merge of the verified result (m2-28): the issue is read again first, like for a move, then
     * a `Waiting(approval)` approved by `auto` is written, so the next tick delivers the pull request and starts
     * its merge check, and the auto merge gate decides right before the merge. The failure's reason, null when it
     * waits.
     */
    private suspend fun requestAutoMerge(to: String): String? {
        if (host.awaitsApproval(issueId)) return null
        val current =
            withContext(io) { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { return it.describe() }
                .firstOrNull()
                ?: return "no issue $identifier in the tracker"
        val from = current.state
        if (from.normalized() == to.normalized()) return null
        if (!workflow.trackers.entryOf(issueId).isActive(from)) {
            if (!ownMove(from)) record(HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to from))
            return "the issue is no longer active, it is in $from"
        }
        startApprovalWait(to, approvedBy = AutoMerge.AUTO)
        log.log(
            LogLevel.INFO,
            DELIVERY,
            LogOutcome.STARTED,
            logContext(),
            mapOf("wait_reason" to WaitReason.Approval.wire, "to" to to, "approved_by" to AutoMerge.AUTO),
        )
        return null
    }

    /**
     * Delivers the verified result as a pull request at once, under `auto` with `delivery.pull_request` (m2-25).
     * The issue is read again first, like for a move. The delivery's last step moves the issue to [to]. A step that
     * can be tried again, the move included, leaves an approved `Waiting(approval)` holding the pull request URL
     * once commented, so the next tick goes on without a second pull request or comment. A permanent failure,
     * recorded as `delivery_failed`, falls back to the `Waiting(approval)` of the `human` policy. The failure's
     * reason, null when it delivered or waits.
     */
    private suspend fun deliverPullRequest(to: String): String? {
        val current =
            withContext(io) { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { return it.describe() }
                .firstOrNull()
                ?: return "no issue $identifier in the tracker"
        val from = current.state
        if (from.normalized() == to.normalized()) return null
        if (!workflow.trackers.entryOf(issueId).isActive(from)) {
            if (!ownMove(from)) record(HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to from))
            return "the issue is no longer active, it is in $from"
        }
        val facts =
            DeliveryFacts(
                issue = current,
                identifier = identifier,
                attemptId = attemptId?.value,
                fingerprint = verifiedFingerprint,
                checks = EvidenceSummary.lines(passed.toList()),
                judgmentCalls = judgmentCallCount,
                workspaceKey = NamespacedId.workspaceKey(issueId, identifier, workflow.trackerId).value,
                commented = null,
            )
        val handoff = PullRequestDelivery.Handoff(from, to)
        return when (val outcome = host.pullRequestDelivery(workflow, record).deliver(facts, handoff)) {
            is DeliveryOutcome.Delivered -> null
            is DeliveryOutcome.Retry -> {
                val reason = redactor.redact("cannot deliver yet, ${outcome.step}: ${outcome.reason}").take(REASON_MAX)
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, logContext(), reason = reason)
                startApprovalWait(to, approvedBy = DeliveryPolicy.Auto.wire, pullRequest = outcome.commented)
                null
            }
            is DeliveryOutcome.Failed -> requestApproval(to, pullRequest = outcome.commented)
        }
    }

    /**
     * Starts the `Waiting(approval)` of a verified result (m2-24). The issue is read again first, like for a
     * move: one no longer active is left alone, a human changed it during the checks. Then the evidence
     * summary is posted on the issue, written by `loop` (a failed comment is logged and the wait still
     * starts, the approval is what protects), and the wait replaces the `Waiting(verify)` in memory and in the
     * store, with the target state and the fingerprint. Records `approval_requested`. The failure's reason,
     * null when it waits, was already there or already waits.
     */
    private suspend fun requestApproval(
        to: String,
        pullRequest: String? = null,
        deliverable: AttachDelivery.Prepared.Comment? = null,
    ): String? {
        if (host.awaitsApproval(issueId)) return null
        val current =
            withContext(io) { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { return it.describe() }
                .firstOrNull()
                ?: return "no issue $identifier in the tracker"
        val from = current.state
        if (from.normalized() == to.normalized()) return null
        if (!workflow.trackers.entryOf(issueId).isActive(from)) {
            // A human changed it during the checks, and the human's change wins, like for a move (m2-19).
            if (!ownMove(from)) record(HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to from))
            return "the issue is no longer active, it is in $from"
        }
        // m4-22: the deliverable first, so the evidence summary and its approval stay the last comment. A deliverable
        // that cannot be built starts no wait; one that could not be posted is named in the summary.
        val notes =
            if (kind?.config?.delivery?.attach == true) {
                val comment =
                    deliverable ?: when (val prepared = prepareDeliverable()) {
                        is AttachDelivery.Prepared.Comment -> prepared
                        is AttachDelivery.Prepared.Failed -> return prepared.reason
                    }
                attachNotes(current, comment, fallback = deliverable != null)
            } else {
                emptyList()
            }
        val policy = if (deliverable != null) DeliveryPolicy.Auto.wire else DeliveryPolicy.Human.wire
        postSummary(current, to, notes, policy)
        startApprovalWait(to, approvedBy = null, pullRequest = pullRequest)
        postedDeliverables -= deliverableKey()
        record(
            ApprovalRequested,
            mapOf(Delivery.TO to to, Delivery.POLICY to DeliveryPolicy.Human.wire, Delivery.FINGERPRINT to verifiedFingerprint),
        )
        log.log(LogLevel.INFO, DELIVERY, LogOutcome.STARTED, logContext(), mapOf("wait_reason" to WaitReason.Approval.wire, "to" to to))
        return null
    }

    /**
     * Delivers the verified result under `auto` with `delivery.attach` and its opt in (m4-22): the issue is read again
     * first, like for a move, then the deliverable is posted on it and the issue moves to [to], with no approval. A
     * deliverable that cannot be built or posted moves nothing; one with a file only listed, not inlined, falls back
     * to the approval wait, as an auto merge falls back to human, so the workspace stays. The failure's reason, null
     * when it delivered, waits or was already there.
     */
    private suspend fun deliverAttached(to: String): String? {
        val current =
            withContext(io) { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { return it.describe() }
                .firstOrNull()
                ?: return "no issue $identifier in the tracker"
        val from = current.state
        if (from.normalized() == to.normalized()) return null
        if (!workflow.trackers.entryOf(issueId).isActive(from)) {
            if (!ownMove(from)) record(HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to from))
            return "the issue is no longer active, it is in $from"
        }
        val comment =
            when (val prepared = prepareDeliverable()) {
                is AttachDelivery.Prepared.Comment -> prepared
                is AttachDelivery.Prepared.Failed -> return prepared.reason
            }
        if (comment.listed > 0) return requestApproval(to, deliverable = comment)
        postDeliverable(current, comment)?.let { return it }
        return move(to).also { if (it == null) postedDeliverables -= deliverableKey() }
    }

    /** The deliverable comment of the last [verify] (m4-22), or why there is none: a verified attach kind lists its files. */
    private suspend fun prepareDeliverable(): AttachDelivery.Prepared {
        val files = deliverableFiles ?: return AttachDelivery.Prepared.Failed("the verification listed no deliverable files")
        return attachDelivery().prepare(identifier, attemptId?.value, workspace, files, deliverableSaved)
    }

    /**
     * Posts the deliverable [comment] on [issue] (m4-22), once for an attempt and fingerprint; the failure, logged at
     * warn, null when it was posted now or before.
     */
    private suspend fun postDeliverable(
        issue: Issue,
        comment: AttachDelivery.Prepared.Comment,
    ): String? {
        val key = deliverableKey()
        if (key in postedDeliverables) return null
        val failure = attachDelivery().post(issue, comment)
        if (failure == null) {
            postedDeliverables += key
            return null
        }
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, logContext(), reason = failure)
        return failure
    }

    /**
     * Posts the deliverable [comment] before an approval wait (m4-22); the lines the evidence summary adds: why an
     * auto delivery waits when it is a [fallback], and a post that failed.
     */
    private suspend fun attachNotes(
        issue: Issue,
        comment: AttachDelivery.Prepared.Comment,
        fallback: Boolean,
    ): List<String> {
        val failure = postDeliverable(issue, comment)
        return listOfNotNull(AttachDelivery.fallbackNote(comment.listed).takeIf { fallback }, failure?.let(AttachDelivery::failureNote))
    }

    private fun attachDelivery() = AttachDelivery(tracker, redactor, clock, io, record)

    private fun deliverableKey() = "$issueId/${attemptId?.value}/$verifiedFingerprint"

    /**
     * Writes the `Waiting(approval)` of the verified result, in memory and in the store, with the target state and
     * the fingerprint (m2-24). With `delivery.pull_request` (m2-25) it also holds the rendered check lines and the
     * number of judgment calls, so a delivery after a restart builds the full body, and, when [approvedBy] is set,
     * the approval of the `auto` policy, so the next tick delivers it, with the [pullRequest] URL already commented.
     */
    private suspend fun startApprovalWait(
        to: String,
        approvedBy: String?,
        pullRequest: String? = null,
    ) {
        // The check lines, redacted, and the number of judgment calls of the last [verify], for the body (m2-25).
        val pullRequestDelivery =
            kind
                ?.config
                ?.delivery
                ?.pullRequest != null
        val checks = if (pullRequestDelivery) redactor.redact(EvidenceSummary.lines(passed.toList()).joinToString("\n")) else null
        val wait =
            Wait.approval(
                projectId = projectId,
                issueId = issueId,
                identifier = identifier,
                attemptId = attemptId,
                runId = runId,
                since = clock.now(),
                to = to,
                fingerprint = verifiedFingerprint,
                checks = checks,
                judgmentCalls = judgmentCallCount.takeIf { pullRequestDelivery },
                approvedBy = approvedBy,
                approvedAt = approvedBy?.let { clock.now() },
                pullRequest = pullRequest,
                kind = kind?.name,
            )
        // m4-30: an unapproved wait tells the human once, its notice key saved with it.
        host.startApprovalWait(wait, "a verified result waits for approval to move to $to")
    }

    /** Posts the evidence summary of the last [verify] on [issue], redacted; a failure is logged at warn. */
    private suspend fun postSummary(
        issue: Issue,
        to: String,
        notes: List<String> = emptyList(),
        policy: String = DeliveryPolicy.Human.wire,
    ) {
        val attempt = attemptId?.value ?: return
        val summary = EvidenceSummary.text(identifier, attempt, to, verifiedFingerprint, passed.toList(), notes, policy)
        val text = redactor.redact(summary).trim()
        val comment = IssueComment(LOOP_AUTHOR, clock.now(), text)
        val failure =
            try {
                withContext(io) { tracker.addComment(issue, comment) }.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure == null) {
            record(
                IssueCommented,
                mapOf(
                    Tracker.CHARS to text.length.toString(),
                    Tracker.BY to BY_HOST,
                    Tracker.REASON to EVIDENCE_SUMMARY,
                ),
            )
        } else {
            val reason = redactor.redact("cannot post the evidence summary: ${failure.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, logContext(), reason = reason)
        }
    }

    /**
     * Moves the issue to [to] after its checks passed, recording `issue_moved` with `verified`. The issue is
     * read again first: one no longer active is left alone, a human changed it during the checks. The
     * failure's reason, null when it moved or was already there.
     */
    suspend fun move(to: String): String? {
        val current =
            withContext(io) { tracker.fetchIssuesByIds(listOf(issueId)) }
                .getOrElse { return it.describe() }
                .firstOrNull()
                ?: return "no issue $identifier in the tracker"
        val from = current.state
        if (from.normalized() == to.normalized()) return null
        if (!workflow.trackers.entryOf(issueId).isActive(from)) {
            // A human changed it during the checks, and the human's change wins (m2-03): a human touch (m2-19).
            if (!ownMove(from)) record(HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to from))
            return "the issue is no longer active, it is in $from"
        }
        withContext(io) { tracker.moveIssue(current, to) }.getOrElse { return it.describe() }
        record(IssueMoved, mapOf(Tracker.FROM to from, Tracker.TO to to, Tracker.BY to BY_HOST, Tracker.VERIFIED to "true"))
        return null
    }

    /** What one check did and the reference of its evidence, null when none was saved; [rerun] for a second run. */
    private class CheckOutcome(
        val result: CheckResult,
        val evidence: String?,
        val rerun: Boolean,
    )

    /**
     * Runs one check, saves its evidence, then records `check_started` and `check_finished` with the
     * evidence reference and the [fingerprint], and `rerun` for the second run of a failed check (m2-08);
     * a cancel records `canceled` and goes on unwinding. A check runner that throws is a check that could
     * not run (m2-08), never a crash of the attempt. A [holdout] (m2-14) runs with the holdout folder in its
     * env, its events carry `holdout` = `true`, and its `check_finished`, log line and evidence say only
     * `result`, its [CheckResult.hiddenOutcome], never its exit code, duration, reason or output.
     */
    private suspend fun runCheck(
        index: Int,
        check: CheckConfig,
        fingerprint: String,
        holdout: Boolean,
        rerun: Boolean,
    ): CheckOutcome {
        val script = check.script
        val base =
            mapOfNotNull(
                CheckEvent.CHECK to index.toString(),
                CheckEvent.COMMAND to redactor.redact(script).take(HOOK_COMMAND_MAX),
                CheckEvent.HOLDOUT to if (holdout) "true" else null,
                CheckEvent.RERUN to if (rerun) "true" else null,
            )
        record(CheckStarted, base)
        log.log(LogLevel.INFO, "check", LogOutcome.STARTED, logContext(), mapOf("check" to index, "rerun" to rerun.takeIf { it }))
        val env = if (holdout) checkEnv() + (Holdouts.ENV to Holdouts.dir(loopHome).toString()) else checkEnv()
        val started = clock.now()
        val result =
            try {
                withContext(io) { checkRunner.run(check, workspace, env) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { record(CheckFinished, base + (CheckEvent.CANCELED to "true")) }
                throw e
            } catch (e: Exception) {
                val took = (clock.now() - started).inWholeMilliseconds
                CheckResult(null, timedOut = false, durationMs = took, startError = "check runner error: ${e.describe()}")
            }
        val reference = saveEvidence(index, script, result, fingerprint, holdout)
        val outcome =
            if (holdout) {
                mapOf(CheckEvent.RESULT to result.hiddenOutcome)
            } else {
                mapOfNotNull(
                    CheckEvent.EXIT_CODE to result.exitCode?.toString(),
                    CheckEvent.DURATION_MS to result.durationMs.toString(),
                    CheckEvent.TIMED_OUT to result.timedOut.toString(),
                    CheckEvent.REASON to result.startError?.let { redactor.redact(it).take(REASON_MAX) },
                )
            }
        record(CheckFinished, base + outcome + mapOfNotNull(CheckEvent.FINGERPRINT to fingerprint, CheckEvent.EVIDENCE to reference))
        val fields =
            if (holdout) {
                mapOf("check" to index, "result" to result.hiddenOutcome, "rerun" to rerun.takeIf { it })
            } else {
                mapOf(
                    "check" to index,
                    "exit_code" to result.exitCode,
                    "duration_ms" to result.durationMs,
                    "rerun" to rerun.takeIf { it },
                )
            }
        val failure =
            when {
                !holdout -> result.couldNotRun ?: "check failed"
                result.couldNotRun != null -> "hidden check could not run"
                else -> "hidden check failed"
            }
        if (result.passed) {
            log.log(LogLevel.INFO, "check", LogOutcome.COMPLETED, logContext(), fields)
        } else {
            log.log(LogLevel.WARN, "check", LogOutcome.FAILED, logContext(), fields, failure)
        }
        return CheckOutcome(result, reference, rerun)
    }

    /**
     * Saves the full output of one check run as evidence of this attempt, its reference, or null when the
     * writer keeps none or there is no attempt. A failed write records `evidence_record_failed` with a
     * redacted reason and a warning, like a failed prompt record, and never stops the verification.
     */
    private suspend fun saveEvidence(
        index: Int,
        script: String,
        result: CheckResult,
        fingerprint: String,
        holdout: Boolean,
    ): String? {
        val attempt = attemptId ?: return null
        // The evidence files are readable by the agent, so a holdout's keeps only its outcome (m2-14).
        val saved =
            if (holdout) {
                Evidence(index, script, null, false, 0, fingerprint, "", false, holdout = true, result = result.hiddenOutcome)
            } else {
                Evidence(
                    check = index,
                    command = script,
                    exitCode = result.exitCode,
                    timedOut = result.timedOut,
                    durationMs = result.durationMs,
                    fingerprint = fingerprint,
                    output = result.output,
                    outputTruncated = result.outputTruncated,
                    reason = result.startError,
                )
            }
        return try {
            withContext(io) { evidence.write(projectId, attempt, saved) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = redactor.redact("cannot save the evidence: ${e.describe()}").take(REASON_MAX)
            record(EvidenceRecordFailed, mapOf(EvidenceRecordFailed.CHECK to index.toString(), Results.REASON to reason))
            log.log(LogLevel.WARN, "evidence_record", LogOutcome.FAILED, logContext(), mapOf("check" to index), reason)
            null
        }
    }

    /** Saves the deliverable files of a verification as evidence (m4-21) and returns its reference, null when not saved. */
    private suspend fun saveDeliverable(deliverable: DeliverableEvidence): String? =
        attemptId?.let { attempt ->
            try {
                withContext(io) { evidence.writeDeliverable(projectId, attempt, deliverable) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = redactor.redact("cannot save the deliverable files: ${e.describe()}").take(REASON_MAX)
                record(EvidenceRecordFailed, mapOf(Results.REASON to reason))
                log.log(LogLevel.WARN, "evidence_record", LogOutcome.FAILED, logContext(), reason = reason)
                null
            }
        }

    /**
     * Saves the criteria of the issue that hold no command as evidence of their own (m2-09), then records
     * `judgment_calls` with their count and the evidence reference, absent when none was saved. A failed
     * write records `evidence_record_failed` without a check index and never stops the verification.
     */
    private suspend fun saveJudgmentCalls(
        criteria: List<String>,
        fingerprint: String,
    ) {
        val reference =
            attemptId?.let { attempt ->
                try {
                    withContext(io) { evidence.writeJudgmentCalls(projectId, attempt, JudgmentCalls(fingerprint, criteria)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = redactor.redact("cannot save the judgment calls: ${e.describe()}").take(REASON_MAX)
                    record(EvidenceRecordFailed, mapOf(Results.REASON to reason))
                    log.log(LogLevel.WARN, "evidence_record", LogOutcome.FAILED, logContext(), reason = reason)
                    null
                }
            }
        record(
            JudgmentCallsEvent,
            mapOfNotNull(
                JudgmentCallsEvent.COUNT to criteria.size.toString(),
                JudgmentCallsEvent.EVIDENCE to reference,
            ),
        )
        log.log(LogLevel.INFO, JUDGMENT_CALLS, LogOutcome.COMPLETED, logContext(), mapOf("count" to criteria.size))
    }

    /**
     * The Loop variables of a check: the issue and attempt ids only. Never `LOOP_TOOL_CONFIG`, the launcher
     * or `PATH` with its folder, so code the agent wrote cannot queue tool requests while it is checked.
     * The cli puts these over the agent env policy.
     */
    private fun checkEnv(): Map<String, String> =
        mapOfNotNull(
            "LOOP_ISSUE_ID" to NamespacedId.nativeOf(issueId),
            "LOOP_ISSUE_IDENTIFIER" to identifier,
            "LOOP_TRACKER_ID" to NamespacedId.trackerOf(issueId),
            "LOOP_WORKSPACE_KEY" to NamespacedId.workspaceKey(issueId, identifier, workflow.trackerId).value,
            "LOOP_PROJECT_ID" to projectId.value,
            "LOOP_RUN_ID" to runId.value,
            "LOOP_ATTEMPT_ID" to attemptId?.value,
        )

    companion object {
        /** The values of the verdict that ends each verification (m2-05). */
        const val VERIFIED = "verified"
        const val REFUTED = "refuted"
        const val UNVERIFIED = "unverified"

        /** Why a kind with an empty check list gives `unverified` (m2-08). */
        const val NO_CHECK = "the kind has no check"

        /** Why a kind with `acceptance_commands` and no other check but `diff_policy` gives `unverified` on criteria with no command (m2-09). */
        const val NO_ACCEPTANCE_COMMAND = "the acceptance criteria of the issue hold no command"

        /** The fingerprint of a verification whose workspace fingerprint could not be taken. */
        const val UNKNOWN_FINGERPRINT = "unknown"

        /** The log event and log field of the criteria with no command of a verification (m2-09). */
        private const val JUDGMENT_CALLS = AcceptanceCommands.JUDGMENT_CALLS

        /** The log event of the frozen acceptance command lists (m2-09). */
        private const val ACCEPTANCE_COMMANDS = "acceptance_commands"

        /** The `unverified` reason of a list that differs from the frozen one (m2-09). */
        private const val ACCEPTANCE_COMMANDS_CHANGED_REASON = AcceptanceCriteria.CHANGED_REASON

        /** The one tracker whose issue text Loop trusts to hold commands, until a trust check exists (m2-09). */
        private const val FILES_TRACKER = "files"

        /** The operator log event of a delivery: an approval wait started, an approved result delivered (m2-24). */
        private const val DELIVERY = "delivery"

        /** The `reason` of the `issue_commented` event of an evidence summary (m2-24). */
        private const val EVIDENCE_SUMMARY = IssueCommented.EVIDENCE_SUMMARY

        /** The author of the comments Loop writes itself, not on an agent's request. */
        private const val LOOP_AUTHOR = "loop"
        private const val BY_HOST = Tracker.BY_HOST
        private const val HOOK_COMMAND_MAX = 200
        private const val REASON_MAX = 200

        private fun String.normalized(): String = trim().lowercase()

        private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"

        private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs
                .mapNotNull { (key, value) -> value?.let { key to it } }
                .toMap()
    }
}

/**
 * How a check is shown in its events, its evidence and its findings: the shell text of a `command`. An
 * `acceptance_commands` entry never runs as such, a verification runs one command check per command (m2-09).
 */
private val CheckConfig.script: String get() = label
