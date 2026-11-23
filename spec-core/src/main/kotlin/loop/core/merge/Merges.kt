package loop.core.merge

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.EventSink
import loop.api.HumanTouch
import loop.api.Issue
import loop.api.IssueComment
import loop.api.MergeMethod
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerAdapter
import loop.api.WaitReason
import loop.core.delivery.AutoMerge
import loop.core.delivery.CiDecision
import loop.core.delivery.CiVerdict
import loop.core.delivery.CiWait
import loop.core.delivery.MergeCheck
import loop.core.delivery.PullRequestDelivery
import loop.core.events.AutoMergeFallback
import loop.core.events.BaseFetched
import loop.core.events.BranchPushFailed
import loop.core.events.BranchPushed
import loop.core.events.CiFinished
import loop.core.events.CiWaiting
import loop.core.events.Delivery
import loop.core.events.DeliveryFailed
import loop.core.events.EarlyCheckFailed
import loop.core.events.EventDefinition
import loop.core.events.HumanTouched
import loop.core.events.IssueCommented
import loop.core.events.IssueMoved
import loop.core.events.MergeCheckFinished
import loop.core.events.MergeCheckStarted
import loop.core.events.MergeConflictEarly
import loop.core.events.MergeRoundSkipped
import loop.core.events.PullRequestMerged
import loop.core.events.Tracker
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.verify.Fingerprinter
import loop.core.verify.ProtectedPaths
import loop.core.verify.WorkspaceFingerprint
import loop.core.verify.WorkspaceGit
import loop.core.waiting.MergePhase
import loop.core.waiting.Wait
import loop.core.waiting.Waits
import loop.core.workflow.CiMode
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowFile
import loop.core.workspace.WorkspaceKey
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The merge waits of a project (m2-26, m2-28, m3-15, bl-85): the merge check, the CI wait, the merge and the auto merge
 * gate of each approved pull request. It holds [waits] and saves the wait itself, mid phase, where the crash safety
 * needs it: the base as the intent before the merge commit, a moved base, a retry, a refusal, a fallback. [run] and
 * [settleInactive] say only whether the wait goes on or ended.
 *
 * The orchestrator keeps the slots: it settles the waits of issues no longer active through [settleInactive], lets
 * [advance] run what a wait needs without a slot, the CI poll, once per tick, and launches [run] in one background job
 * on a free slot, the loop to the end (check, merge, check again on a moved base) inside it. It passes in the rerun of
 * the checks ([recheck]), the live workflow and its rejected reload ([workflow], [rejected]), where the findings of a
 * failed check go ([onFindings]), the removal of a merged issue's workspace ([removeWorkspace]), the wake of its
 * poll sleep ([wake]) and the pause of the queue after a CI timeout ([onCiTimeout], bl-175).
 *
 * Not thread safe: like the orchestrator, it runs on one single threaded dispatcher, so the tick's calls and the one
 * background [run] interleave only where they suspend, and the tick leaves the wait of a running check alone.
 */
internal class Merges(
    private val waits: Waits,
    private val tracker: TrackerAdapter,
    /** The executor of a workflow's pull request delivery, one that refuses every step when the host has none. */
    private val executorFor: (WorkflowFile) -> DeliveryExecutor,
    /** The workspace of an issue under a workflow, null when it is gone. */
    private val locate: (WorkflowFile, WorkspaceKey) -> Result<Path?>,
    private val git: MergeGit,
    private val fingerprinter: Fingerprinter,
    private val recheck: Recheck,
    private val gate: AutoMergeGate,
    /** The effective workflow, read live. */
    private val workflow: () -> WorkflowFile,
    /** The last rejected reload of the workflow file, null since the last passing preflight. */
    private val rejected: () -> PreflightResult.Failed?,
    /** Hands the findings of a failed merge check, by issue id, to the issue's next attempt. */
    private val onFindings: (String, String) -> Unit,
    /** Removes the workspace of a merged issue, its `before_remove` hook first. */
    private val removeWorkspace: suspend (Issue) -> Unit,
    /** Wakes the poll sleep once a wait ended. */
    private val wake: () -> Unit,
    /** Told once a CI wait ended in `timeout` and fell back to approval, so the orchestrator pauses the queue (bl-175). */
    private val onCiTimeout: suspend (Wait.Merge) -> Unit = {},
    private val sink: EventSink,
    private val sinkName: String,
    private val log: KeyValueLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val io: CoroutineContext,
    private val projectId: ProjectId,
    private val runId: RunId,
) {
    private val pollContext = LogContext(runId = runId, projectId = projectId)

    /** The last poll of a CI wait in this process (m3-15): when, and whether the provider answered. */
    private data class CiPoll(
        val at: Instant,
        val answered: Boolean,
    )

    /**
     * The last poll of each CI wait in this process, by issue id (m3-15): it keeps polls 30 seconds apart and says
     * whether the time since counts. Absent after a restart, so the time Loop was down never counts.
     */
    private val ciPolls = mutableMapOf<String, CiPoll>()

    /**
     * The base tip the merge waits fetched last in this process, with the base branch it was fetched for (bl-178): the
     * merge check's fetch and the round skip's. Null before the first fetch.
     */
    private var fetchedBase: Pair<String, String>? = null

    /**
     * The head and the base tip of the last early conflict check of each queued merge wait that answered, clean or
     * conflicting, by issue id (bl-178); a failed call leaves no entry (bl-182).
     */
    private val earlyChecked = mutableMapOf<String, Pair<String, String>>()

    /** True when a CI poll or an early conflict check of the issue [issueId] is held in memory (bl-182). */
    fun remembers(issueId: String): Boolean = issueId in ciPolls || issueId in earlyChecked

    /**
     * Drops what memory holds of every issue but [waiting], the issue ids that still hold a merge wait (bl-182): a wait
     * that ended by a path outside this class, a move or a new attempt, leaves no CI poll and no early check behind.
     */
    fun retainOnly(waiting: Set<String>) {
        ciPolls.keys.retainAll(waiting)
        earlyChecked.keys.retainAll(waiting)
    }

    /** Drops the CI poll and the early check of the issue [issueId], whose merge wait ended (bl-182). */
    private fun forget(issueId: String) {
        ciPolls.remove(issueId)
        earlyChecked.remove(issueId)
    }

    /**
     * Settles the merge [wait] of an issue no longer active (m2-26), [issue] as the by-id read shows it, null when it is
     * gone. The pull request is looked up first: merged at the stored head, the merge is delivered, a human touch only
     * when Loop did not call the merge in this round (bl-181), since `Closes #n` may close the issue as Loop merges. Otherwise
     * a human moved the issue: the wait ends with a human touch, like an approval wait. A failed lookup keeps the wait.
     */
    suspend fun settleInactive(
        wait: Wait.Merge,
        issue: Issue?,
    ): MergeWait {
        settleInactiveMerge(wait, issue)
        return after(wait)
    }

    /**
     * What [wait] needs this tick without a slot (m3-15): a wait in phase `ci` is polled, at most once every
     * [CiWait.POLL_FLOOR_MS]. The wait whose next step needs a slot, the check or the merge, for [run]; null when
     * nothing more runs this tick.
     */
    suspend fun advance(wait: Wait.Merge): Wait.Merge? = if (wait.phase == MergePhase.Ci) pollCi(wait) else wait

    /**
     * The early conflict check of [wait], a merge wait queued behind the head of the delivery queue (bl-178), inline in
     * the tick with no slot: `git merge-tree` of the base tip last fetched and the committed head of the wait's
     * fingerprint, in the issue's own workspace, read only. A clean or conflicting answer is given once per head and
     * base tip. A conflict ends the wait as a conflict of the merge check does, the m2-26 comment, the findings for the
     * next attempt, and records `merge_conflict_early`; the issue then leaves the queue. As for the merge check, a pull
     * request a human merged meanwhile is read first and never sent back (bl-181). A clean merge changes nothing. A call
     * that fails logs a warning, records `early_check_failed` and changes nothing, so the next tick tries again (bl-182):
     * the merge check finds the conflict in its turn. True when the wait ended with no dispatch due, the pull request
     * found merged, so the tick does not dispatch the issue it read before.
     */
    suspend fun checkEarly(
        wait: Wait.Merge,
        issue: Issue,
    ): Boolean {
        val workflow = workflow()
        val base =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.base ?: return false
        val (fetchedFor, tip) = fetchedBase ?: return false
        if (fetchedFor != base) return false
        val head = WorkspaceFingerprint.committedHead(wait.fingerprint.orEmpty()) ?: return false
        if (earlyChecked[wait.issueId] == head to tip) return false

        // bl-182: a failure is not a check, the next tick tries again.
        suspend fun failed(reason: String): Boolean {
            val redacted = redactor.redact(reason).take(REASON_MAX)
            val text = "the early conflict check failed, the wait stays queued: $redacted".take(REASON_MAX)
            log.log(LogLevel.WARN, MergeCheck.MARK, LogOutcome.FAILED, waitContext(wait), reason = text)
            recordWaitEvent(
                wait,
                EarlyCheckFailed,
                mapOf(
                    EarlyCheckFailed.ISSUE to wait.identifier,
                    Delivery.HEAD to head,
                    EarlyCheckFailed.BASE to tip,
                    Delivery.REASON to redacted,
                ),
            )
            return false
        }
        val workspace =
            locate(workflow, WorkspaceKey.of(wait.identifier)).getOrElse {
                return failed("cannot locate the workspace: ${it.describe()}")
            } ?: return failed("the workspace of ${wait.identifier} is gone")
        val outcome = gitStep { git.mergeTree(workspace, tip, head) }.getOrElse { return failed(it.describe()) }
        earlyChecked[wait.issueId] = head to tip
        val conflict = outcome as? WorkspaceGit.MergeTreeOutcome.Conflict ?: return false
        val paths = redactor.redact(conflict.paths.take(MergeConflictEarly.PATHS_MAX).joinToString(","))
        recordWaitEvent(
            wait,
            MergeConflictEarly,
            mapOf(MergeConflictEarly.ISSUE to wait.identifier, MergeConflictEarly.BASE to tip, MergeConflictEarly.PATHS to paths),
        )
        val detail = redactor.redact(conflict.detail).take(REASON_MAX)
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        return !sentBack(
            wait,
            issue,
            workflow,
            MergeCheck.conflictComment(wait.numberText, base, tip, branch, detail),
            MergeCheck.conflictFindings(base, tip, detail),
            "merging the base conflicts, found while queued: $detail",
        )
    }

    /**
     * The merge check and the merge of one approved pull request (m2-26), run in the background on one slot. Without
     * `merge` in the workflow now, a reload took it away: the wait goes back to an approved `Waiting(approval)` and m2-25
     * delivers it. The pull request is looked up first, so a restart never merges twice: merged at the stored head, it
     * is delivered; merged elsewhere or closed by hand, the merge is refused for good. Then the phases run from the one
     * stored, the check, then the merge, the check again when the base moved; a passing check under `ci: actions` stops
     * at the CI wait, which [advance] polls. A run that starts in phase `merge` holds no agent slot (bl-173), so when the
     * base moved it stops at the new round's check, which a later tick starts on a free slot.
     */
    suspend fun run(
        start: Wait.Merge,
        issue: Issue,
    ): MergeWait {
        runMergeCheck(start, issue)
        return after(start)
    }

    /**
     * True when [found] was merged at the stored head of [wait], or, in phase `check`, at the candidate head the check
     * pushed early (bl-174): only a human merges then, so both count as a merge at the stored head.
     */
    private fun mergedAtStoredHead(
        wait: Wait.Merge,
        found: PullRequest,
    ): Boolean {
        val head = WorkspaceFingerprint.committedHead(wait.fingerprint.orEmpty())
        if (head != null && PullRequestDelivery.delivered(found, head)) return true
        val candidate = wait.candidateHead.takeIf { wait.phase == MergePhase.Check } ?: return false
        return PullRequestDelivery.delivered(found, candidate)
    }

    private fun after(wait: Wait): MergeWait = if (waits.isBusy(wait.issueId)) MergeWait.Continues else MergeWait.Ended

    private suspend fun settleInactiveMerge(
        wait: Wait.Merge,
        issue: Issue?,
    ) {
        val context = waitContext(wait)
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        val found =
            deliveryStep { executorFor(workflow()).findPullRequest(branch) }.getOrElse { error ->
                log.log(
                    LogLevel.WARN,
                    WAITING,
                    LogOutcome.FAILED,
                    context,
                    reason = redactor.redact("cannot look up the pull request, it keeps waiting: ${error.describe()}").take(REASON_MAX),
                )
                return
            }
        if (issue != null && found != null && mergedAtStoredHead(wait, found)) {
            finishMerged(wait, issue, humanMerge = !wait.mergeCalled)
            return
        }
        endWaiting(wait)
        recordWaitEvent(wait, HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE))
        log.log(
            LogLevel.INFO,
            WAITING,
            LogOutcome.RELEASED,
            context,
            mapOf("wait_reason" to wait.reason.wire),
            "the issue is no longer active",
        )
    }

    private suspend fun runMergeCheck(
        start: Wait.Merge,
        issue: Issue,
    ) {
        val workflow = workflow()
        val pullRequest =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
        val merge = pullRequest?.merge
        if (pullRequest == null || merge == null) {
            storeWait(start.unmerged(), waitContext(start))
            log.log(
                LogLevel.INFO,
                WAITING,
                LogOutcome.RELEASED,
                waitContext(start),
                mapOf("wait_reason" to WaitReason.Merge.wire),
                "the workflow no longer merges",
            )
            return
        }
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(start.identifier).value)
        val found =
            deliveryStep { executorFor(workflow).findPullRequest(branch) }.getOrElse {
                mergeStepFailed(start, PullRequestDelivery.STEP_PULL_REQUEST, it)
                return
            }
        val refusal =
            when {
                found == null -> "no pull request has the head $branch"
                mergedAtStoredHead(start, found) -> {
                    finishMerged(start, issue, humanMerge = !start.mergeCalled)
                    return
                }
                found.state == PullRequestState.Merged -> "pull request #${found.number} was merged at another commit"
                found.state == PullRequestState.Closed -> "pull request #${found.number} was closed without a merge"
                else -> null
            }
        if (refusal != null) {
            val step = if (found == null) PullRequestDelivery.STEP_PULL_REQUEST else MergeCheck.STEP_MERGE
            mergeRefused(start, step, refusal)
            return
        }
        var wait = start.numbered(checkNotNull(found).number)
        while (true) {
            wait =
                when (wait.phase) {
                    MergePhase.Merge -> mergePhase(wait, issue, workflow, merge.method)
                    // m3-15: the tick polls the CI wait, with no slot.
                    MergePhase.Ci -> return
                    MergePhase.Check -> {
                        // bl-173: a run started in phase merge holds no agent slot, and the check needs one.
                        if (start.phase != MergePhase.Check) return
                        checkPhase(wait, issue, workflow)?.let { checked ->
                            if (merge.ci == CiMode.Actions && checked.phase == MergePhase.Merge) startCi(checked, workflow) else checked
                        }
                    }
                } ?: return
        }
    }

    /**
     * Starts the CI wait of [wait] after a passing merge check (m3-15): pushes the merged head unforced, so its Actions
     * runs start (`branch_pushed` when it is not the verified commit and was not pushed early, bl-174), stores the wait in phase `ci` from now, records
     * `ci_waiting` and counts the time from the push. Null: the tick polls from now on. A failed push is a merge step
     * that failed.
     */
    private suspend fun startCi(
        wait: Wait.Merge,
        workflow: WorkflowFile,
    ): Wait.Merge? {
        val mergedHead = wait.mergedHead ?: return mergeRefused(wait, CiWait.STEP, "the wait holds no merged head")
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        deliveryStep { executorFor(workflow).push(mergedHead, branch) }
            .getOrElse { return mergeStepFailed(wait, PullRequestDelivery.STEP_PUSH, it) }
        val verifiedHead = WorkspaceFingerprint.committedHead(wait.verifiedFingerprint.orEmpty())
        if (!mergedHead.equals(verifiedHead, ignoreCase = true) && !wait.pushedEarly(mergedHead)) {
            recordWaitEvent(wait, BranchPushed, mapOf(Delivery.SHA to mergedHead, Delivery.BRANCH to branch))
        }
        val now = clock.now()
        val next = wait.waitingForCi(now)
        if (!storeWait(next, waitContext(wait))) return null
        ciPolls[wait.issueId] = CiPoll(now, answered = true)
        recordWaitEvent(next, CiWaiting, mapOf(Delivery.SHA to mergedHead))
        log.log(LogLevel.INFO, DELIVERY, LogOutcome.STARTED, waitContext(wait), mapOf("ci" to CiMode.Actions.wire, "sha" to mergedHead))
        return null
    }

    /**
     * One poll of the CI wait [wait] (m3-15), inline in the tick, with no slot and no workspace, at most once every
     * [CiWait.POLL_FLOOR_MS]. Without `merge` in the workflow now, the wait goes back to an approved approval; under
     * `ci: none` now, it goes on to the merge. Otherwise the Actions runs of the merged head are listed once, then the
     * jobs of its failed runs, at most 10 (m4-07), to tell a run GitHub did not start from a red one: an answer
     * that can pass with time (unreachable, 5xx, a rate limit hold) keeps the wait, records nothing and counts nothing;
     * a refusal for good, a 403 or 404 naming "Actions: read" included, is refused. An answer adds the time since the
     * previous answered poll, then [CiWait.decide] rules: the wait in phase `merge` when CI allows it, null otherwise.
     */
    private suspend fun pollCi(wait: Wait.Merge): Wait.Merge? {
        val context = waitContext(wait)
        val workflow = workflow()
        val merge =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.merge
        if (merge == null) {
            storeWait(wait.unmerged(), context)
            log.log(
                LogLevel.INFO,
                WAITING,
                LogOutcome.RELEASED,
                context,
                mapOf("wait_reason" to WaitReason.Merge.wire),
                "the workflow no longer merges",
            )
            return null
        }
        if (merge.ci != CiMode.Actions) {
            val next = wait.ciPassed()
            if (!storeWait(next, context)) return null
            ciPolls.remove(wait.issueId)
            log.log(LogLevel.INFO, DELIVERY, LogOutcome.RELEASED, context, reason = "the workflow no longer waits for CI")
            return next
        }
        val now = clock.now()
        val last = ciPolls[wait.issueId]
        if (last != null && now - last.at < CiWait.POLL_FLOOR_MS.milliseconds) return null
        val head = wait.mergedHead ?: return mergeRefused(wait, CiWait.STEP, "the wait holds no merged head")
        val executor = executorFor(workflow)
        val listing = deliveryStep { executor.actionsRuns(head) }.getOrElse { return ciNotRead(wait, now, it) }
        // m4-07: the listing shows no step, so the jobs of the failed runs, at most 10, tell a run GitHub refused to start.
        val jobs =
            CiWait.jobsToRead(listing).associate { run ->
                run.id to deliveryStep { executor.actionsJobs(run.id) }.getOrElse { return ciNotRead(wait, now, it) }
            }
        val waited = wait.ciWaitedMs + if (last?.answered == true) (now - last.at).inWholeMilliseconds else 0
        ciPolls[wait.issueId] = CiPoll(now, answered = true)
        val verdict = CiWait.verdict(listing, jobs)
        val counted = wait.waited(waited)
        return when (val decision = CiWait.decide(verdict, waited, merge)) {
            CiDecision.Wait -> {
                if (waited != wait.ciWaitedMs) storeWait(counted, context)
                null
            }
            is CiDecision.Merge -> {
                val next = counted.ciPassed()
                if (!storeWait(next, context)) return null
                ciPolls.remove(wait.issueId)
                recordWaitEvent(wait, CiFinished, mapOf(Delivery.OUTCOME to decision.outcome, Delivery.RUNS to CiWait.runIds(verdict.kept)))
                next
            }
            is CiDecision.Fail -> {
                ciPolls.remove(wait.issueId)
                recordWaitEvent(wait, CiFinished, mapOf(Delivery.OUTCOME to decision.outcome, Delivery.RUNS to CiWait.runIds(verdict.kept)))
                val failed =
                    when (verdict) {
                        is CiVerdict.Red -> verdict.failed
                        is CiVerdict.NotStarted -> verdict.notStarted
                        else -> emptyList()
                    }
                val lines = CiWait.failedRunLines(failed) { redactor.redact(it) }
                val notStarted = verdict is CiVerdict.NotStarted
                mergeRefused(counted, CiWait.STEP, decision.reason) { reason ->
                    CiWait.failedComment(wait.numberText, reason, lines, notStarted) to IssueCommented.CI
                }.also { if (decision.outcome == CiWait.TIMEOUT) onCiTimeout(wait) }
            }
        }
    }

    /**
     * A CI read of [wait] at [now], the runs listing or the jobs of a failed run, that failed with [error]: the poll is
     * not answered, so it counts nothing. A refusal for good, a 403 or 404 naming "Actions: read" included, is refused;
     * an answer that can pass with time is logged and keeps the wait, null.
     */
    private suspend fun ciNotRead(
        wait: Wait.Merge,
        now: Instant,
        error: Throwable,
    ): Wait.Merge? {
        ciPolls[wait.issueId] = CiPoll(now, answered = false)
        if (PullRequestDelivery.permanent(error)) return mergeRefused(wait, CiWait.STEP, error.describe())
        val reason = redactor.redact("CI not read, it keeps waiting: ${error.describe()}").take(REASON_MAX)
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = reason)
        return null
    }

    /**
     * The `check` phase (m2-26): fetches the base host side, confirms the workspace is still at the stored fingerprint,
     * then merges the fetched commit into the branch in the issue's own workspace, unless it is already an ancestor,
     * and runs every check and holdout again on the result with `diff_policy` based on the fetched commit. When the
     * fetched commit is already in the branch, only the `diff_policy` checks run again, against it (bl-32). The fetched
     * commit is stored as `base_sha` before the merge, so a stop between the merge commit and the next write is
     * resumed: a workspace at a clean merge commit whose parents are the stored head and that base is Loop's own merge,
     * found before the fetch and rechecked as it is, against that base (send back 1). A merge commit, made or resumed, is
     * pushed before the checks run, see [pushEarly] (bl-174). A clean pass gives the next wait, phase `merge`, its merged head the delivered
     * commit everywhere; null when the wait ended, waits for a new approval or the next tick.
     */
    private suspend fun checkPhase(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
    ): Wait.Merge? {
        val context = waitContext(wait)
        val base =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.base ?: return null
        val round = wait.round
        val stored = wait.fingerprint.orEmpty()
        val intent = wait.baseSha.orEmpty()
        // Send back 1: a stopped check's merge commit is found before the fetch, so no later refusal can leave the
        // approval at the verified commit while the branch is at the merge commit.
        val resumed = resumeStopped(workflow, wait, stored, intent)
        val checkedBase =
            if (resumed != null) {
                intent
            } else {
                val fetched =
                    deliveryStep { executorFor(workflow).fetchBase() }
                        .getOrElse { return mergeStepFailed(wait, MergeCheck.STEP_FETCH, it) }
                recordWaitEvent(wait, BaseFetched, mapOf(Delivery.SHA to fetched, Delivery.BASE to base))
                fetchedBase = base to fetched
                fetched
            }
        val located = locate(workflow, WorkspaceKey.of(wait.identifier))
        val workspace =
            located.getOrElse { return mergeRefused(wait, MergeCheck.STEP_CHECK, "cannot locate the workspace: ${it.describe()}") }
                ?: return mergeRefused(wait, MergeCheck.STEP_CHECK, "the workspace of ${wait.identifier} is gone")
        val head =
            WorkspaceFingerprint.committedHead(stored)
                ?: return mergeRefused(wait, MergeCheck.STEP_CHECK, "the stored fingerprint $stored is not a clean commit")
        if (resumed == null) {
            val now =
                gitStep { fingerprinter.of(workspace).getOrThrow() }.getOrElse {
                    return mergeRefused(wait, MergeCheck.STEP_CHECK, "cannot take the workspace fingerprint: ${it.describe()}")
                }
            if (now != stored) return mergeRefused(wait, MergeCheck.STEP_CHECK, "the workspace is at $now, not at the verified $stored")
        }
        val started =
            mapOfNotNull(
                Delivery.BASE_SHA to checkedBase,
                Delivery.ROUND to round.toString(),
                MergeCheckStarted.RESUMED to resumed?.let { "true" },
            )
        recordWaitEvent(wait, MergeCheckStarted, started)
        log.log(LogLevel.INFO, MergeCheck.MARK, LogOutcome.STARTED, context, mapOf("base_sha" to checkedBase, "round" to round))
        val upToDate =
            resumed == null &&
                gitStep { git.isAncestor(workspace, checkedBase, head) }
                    .getOrElse {
                        return mergeRefused(
                            wait,
                            MergeCheck.STEP_CHECK,
                            "cannot compare the branch with the base: ${it.describe()}",
                        )
                    }
        var current = wait
        val mergedHead =
            when {
                resumed != null -> resumed
                upToDate -> null
                else -> {
                    // The intent: a stop after the merge commit finds the base it merged.
                    current = wait.intending(checkedBase)
                    if (!storeWait(current, context)) return null
                    val message = MergeCheck.message(wait.identifier, base, checkedBase)
                    val merged =
                        gitStep { git.merge(workspace, checkedBase, message) }
                            .getOrElse { return mergeRefused(current, MergeCheck.STEP_CHECK, "cannot merge the base: ${it.describe()}") }
                    when (merged) {
                        is WorkspaceGit.MergeOutcome.Conflict -> {
                            finishCheck(current, started, MergeCheck.CONFLICT, null)
                            val detail = redactor.redact(merged.detail).take(REASON_MAX)
                            val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
                            val number = wait.numberText
                            return mergeCheckFailed(
                                current,
                                issue,
                                workflow,
                                MergeCheck.conflictComment(number, base, checkedBase, branch, detail),
                                MergeCheck.conflictFindings(base, checkedBase, detail),
                                "merging the base conflicts: $detail",
                            )
                        }
                        is WorkspaceGit.MergeOutcome.Merged -> merged.head
                    }
                }
            }
        // bl-174: CI runs on the merge commit while the checks run.
        if (mergedHead != null) current = pushEarly(current, workflow, mergedHead)
        if (mergedHead == null) {
            // bl-32: the verdict the approval rests on read a base the agent could move, so diff_policy runs again.
            val findings = recheck(current, issue, workflow, workspace, checkedBase, head, diffPolicyOnly = true)
            if (findings != null) {
                finishCheck(current, started, MergeCheck.REFUTED, null)
                val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
                val number = wait.numberText
                val reason = findings.first
                return mergeCheckFailed(
                    current,
                    issue,
                    workflow,
                    MergeCheck.guardComment(number, base, checkedBase, branch, reason),
                    MergeCheck.guardFindings(base, checkedBase, findings.second),
                    "diff_policy failed against the base: $reason",
                )
            }
            finishCheck(current, started, MergeCheck.UP_TO_DATE, head)
        } else {
            val findings = recheck(current, issue, workflow, workspace, checkedBase, mergedHead)
            if (findings != null) {
                finishCheck(current, started, MergeCheck.REFUTED, mergedHead)
                val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
                val number = wait.numberText
                val reason = findings.first
                return mergeCheckFailed(
                    current,
                    issue,
                    workflow,
                    MergeCheck.refutedComment(number, base, checkedBase, branch, reason),
                    MergeCheck.refutedFindings(base, checkedBase, findings.second),
                    "the checks failed on the merged result: $reason",
                )
            }
            finishCheck(current, started, MergeCheck.MERGED, mergedHead)
        }
        val delivered = mergedHead ?: head
        val next = current.checked(checkedBase, delivered)
        storeWait(next, context)
        return next
    }

    /**
     * Pushes [head], the merge commit the check of [wait] made or resumed, unforced to the branch before the checks run
     * (bl-174), so its Actions runs start meanwhile: [head] is stored as the candidate head first, so a lookup after a
     * stop knows a pull request merged at it, then pushed, `branch_pushed` recorded and the push stored. Nothing is
     * pushed again when the stored wait says it was. Never fatal: a wait that cannot be written is not pushed, and a push
     * that fails is logged and recorded as `branch_push_failed` with `early` = `true` (bl-181), the check goes on and the
     * push that follows a passing check pushes it as before. The wait the check goes on with.
     */
    private suspend fun pushEarly(
        wait: Wait.Merge,
        workflow: WorkflowFile,
        head: String,
    ): Wait.Merge {
        if (wait.pushedEarly(head)) return wait
        val context = waitContext(wait)
        val candidate = wait.candidate(head)
        if (!wait.candidateHead.equals(head, ignoreCase = true) && !storeWait(candidate, context)) return wait
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        deliveryStep { executorFor(workflow).push(head, branch) }.getOrElse { error ->
            val reason = redactor.redact("the merge commit is pushed after the checks: ${error.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
            val failure =
                mapOf(
                    BranchPushFailed.ISSUE to wait.identifier,
                    Delivery.HEAD to head,
                    BranchPushFailed.EARLY to "true",
                    Delivery.REASON to
                        redactor
                            .redact(error.describe())
                            .lineSequence()
                            .first()
                            .take(REASON_MAX),
                )
            recordWaitEvent(candidate, BranchPushFailed, failure)
            return candidate
        }
        recordWaitEvent(candidate, BranchPushed, mapOf(Delivery.SHA to head, Delivery.BRANCH to branch))
        val pushed = candidate.candidatePushed()
        storeWait(pushed, context)
        return pushed
    }

    /**
     * What a stop in the middle of an earlier merge check left in the workspace of [wait] (send back 1), looked at
     * before anything else: a merge left in progress at the stored head is aborted, and the merged head of a merge
     * commit Loop made is returned, see [stoppedMerge]. Null for a workspace at the [stored] fingerprint, for anything
     * else, and when the workspace cannot be read: the usual steps then decide.
     */
    private suspend fun resumeStopped(
        workflow: WorkflowFile,
        wait: Wait,
        stored: String,
        intent: String,
    ): String? {
        val head = WorkspaceFingerprint.committedHead(stored) ?: return null
        val workspace = locate(workflow, WorkspaceKey.of(wait.identifier)).getOrNull() ?: return null
        val now = gitStep { fingerprinter.of(workspace).getOrThrow() }.getOrNull() ?: return null
        if (now == stored) return null
        if (gitStep { git.abortStrandedMerge(workspace, head) }.getOrDefault(false)) {
            log.log(
                LogLevel.INFO,
                MergeCheck.MARK,
                LogOutcome.RELEASED,
                waitContext(wait),
                reason = "a merge a stop left in progress was aborted",
            )
            return null
        }
        return stoppedMerge(workspace, head, intent)
    }

    /**
     * The merged head a stopped merge check left in [workspace] (send back 1): `HEAD` is a merge commit whose parents
     * are the stored [head] and the stored [intent], the base it was merging, and its work tree, once restored from
     * what an interrupted check may have written, is clean at it. Null for anything else, which is refused.
     */
    private suspend fun stoppedMerge(
        workspace: Path,
        head: String,
        intent: String,
    ): String? {
        if (intent.isBlank()) return null
        val top = gitStep { git.head(workspace) }.getOrNull() ?: return null
        val parents = gitStep { git.parents(workspace, top) }.getOrNull() ?: return null
        if (parents.size != 2 || !parents[0].equals(head, ignoreCase = true) || !parents[1].equals(intent, ignoreCase = true)) return null
        gitStep { fingerprinter.restore(workspace).getOrThrow() }
        val now = gitStep { fingerprinter.of(workspace).getOrThrow() }.getOrNull()
        if (now != MergeCheck.cleanFingerprint(top)) return null
        log.log(
            LogLevel.INFO,
            MergeCheck.MARK,
            LogOutcome.STARTED,
            pollContext,
            mapOf("head" to top),
            "a merge check a stop interrupted resumes at its merge commit",
        )
        return top
    }

    /**
     * Runs every check and holdout of [workflow] again on the merged head [mergedHead] in [workspace] (m2-26) through
     * the orchestrator's [Recheck], outside any attempt, with the same env policy and rerun rule as a verification,
     * `diff_policy` based on the fetched [base] and every event marked [MergeCheck.MARK]. With [diffPolicyOnly], only the
     * `diff_policy` checks run, and nothing when the kind has none (bl-32). The m2-25 restore puts the tree back after
     * the checks; a tree still not clean at [mergedHead] after it fails the check. Null when it passed, else the reason
     * in a line and the findings for the next attempt.
     */
    private suspend fun recheck(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        workspace: Path,
        base: String,
        mergedHead: String,
        diffPolicyOnly: Boolean = false,
    ): Pair<String, String>? {
        when (val outcome = recheck.run(wait, issue, workflow, workspace, base, diffPolicyOnly)) {
            RecheckOutcome.Skipped -> return null
            RecheckOutcome.Passed -> Unit
            is RecheckOutcome.Failed -> {
                val reason = outcome.unverified ?: "a check failed"
                return reason to (outcome.findings ?: "The checks could not prove the merged result: $reason.")
            }
        }
        val after = gitStep { fingerprinter.of(workspace).getOrThrow() }.getOrNull()
        if (after == MergeCheck.cleanFingerprint(mergedHead)) return null
        val reason = "the work tree is not clean after the checks, it is at ${after ?: UNKNOWN_FINGERPRINT}"
        return reason to
            "The checks left files in the work tree that the restore could not put back, so the merged result was refuted: $reason."
    }

    /** Records `merge_check_finished` with [outcome] and logs it. */
    private suspend fun finishCheck(
        wait: Wait,
        started: Map<String, String>,
        outcome: String,
        head: String?,
    ) {
        recordWaitEvent(wait, MergeCheckFinished, started + mapOfNotNull(Delivery.OUTCOME to outcome, Delivery.HEAD to head))
        val level = if (outcome == MergeCheck.UP_TO_DATE || outcome == MergeCheck.MERGED) LogLevel.INFO else LogLevel.WARN
        val logged = if (level == LogLevel.INFO) LogOutcome.COMPLETED else LogOutcome.FAILED
        log.log(level, MergeCheck.MARK, logged, waitContext(wait), mapOf("outcome" to outcome))
    }

    /**
     * The `merge` phase (m2-26): pushes the merged head unforced to the branch, so the pull request's head is what was
     * checked, then reads the pull request until its head shows the merged head, a bounded wait for the provider's
     * delayed update (send back 5). Reads the base tip once more and, when it moved since the check, redoes the check,
     * at most [MergeCheck.MAX_ROUNDS] rounds, unless the move is only in `recheck_skip_paths` (bl-176, [skipRound]).
     * Reads the issue once more: one a human moved out of the active states is not merged (send back 6). An approval of
     * the `auto` policy passes the auto merge gate, then, since the review can take minutes, the issue is read again,
     * the workflow file must not hold a rejected change and the effective workflow must still allow an auto merge, and
     * the base tip is read again (m2-28, rounds 2 and 3 and send back 4 of PR #75). Then merges the pull request with the merged head as its sha guard and [method], and delivers. A refusal because a branch moved under the merge is tried once more at the next tick before it
     * counts as permanent. Right before the merge call the wait stores `merge_called` (bl-181), so a merge found after a
     * stop or a failed move is Loop's own, with no human touch. The next wait when the check must run again, null otherwise.
     */
    private suspend fun mergePhase(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        method: MergeMethod,
    ): Wait.Merge? {
        val context = waitContext(wait)
        val executor = executorFor(workflow)
        val number =
            wait.pullRequestNumber
                ?: return mergeRefused(wait, MergeCheck.STEP_MERGE, "the wait holds no pull request number")
        val mergedHead =
            wait.mergedHead
                ?: return mergeRefused(wait, MergeCheck.STEP_MERGE, "the wait holds no merged head")
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        deliveryStep { executor.push(mergedHead, branch) }.getOrElse { return mergeStepFailed(wait, PullRequestDelivery.STEP_PUSH, it) }
        val verifiedHead = WorkspaceFingerprint.committedHead(wait.verifiedFingerprint.orEmpty())
        // m3-15 and bl-174: a head the CI wait or the early push pushed was recorded then.
        if (!mergedHead.equals(verifiedHead, ignoreCase = true) && wait.ciStartedAt == null && !wait.pushedEarly(mergedHead)) {
            recordWaitEvent(wait, BranchPushed, mapOf(Delivery.SHA to mergedHead, Delivery.BRANCH to branch))
        }
        val shownHead = awaitHead(executor, branch, mergedHead).getOrElse { return mergeStepFailed(wait, MergeCheck.STEP_MERGE, it) }
        val skipped =
            when (val read = baseMoved(wait, executor, workflow, mergedHead, skipped = null)) {
                is BaseRead.Moved -> return read.next
                is BaseRead.GoesOn -> read.skipped
            }
        var current = stillActive(wait) ?: return null
        // m2-28: an approval the auto policy gave itself merges only through the auto merge gate.
        if (wait.approvedBy == AutoMerge.AUTO) {
            val workspace =
                locate(workflow, WorkspaceKey.of(wait.identifier)).getOrNull()
                    ?: return autoMergeFallBack(
                        wait,
                        GateOutcome.FallBack(emptyList(), null, "the workspace is gone", "the workspace is gone"),
                    )
            val record: suspend (
                EventDefinition,
                Map<String, String>,
            ) -> Unit = { definition, data -> recordWaitEvent(wait, definition, data) }
            val warn: suspend (String) -> Unit = { reason -> log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason) }
            val outcome = gate.evaluate(wait, current, workflow, executor, workspace, number, mergedHead, shownHead, record, warn)
            val passed =
                when (outcome) {
                    is GateOutcome.FallBack -> return autoMergeFallBack(wait, outcome)
                    is GateOutcome.Merge -> outcome
                }
            // The review can take minutes. Rounds 2 and 3 of PR #75: a human may have stopped the issue meanwhile, or a
            // change to the workflow file turned the auto merge off, reloaded or rejected; the tick leaves a running
            // merge check alone, so both are read again here.
            current = stillActive(wait) ?: return null
            autoMergeStopped()?.let { stopped ->
                return autoMergeFallBack(wait, GateOutcome.FallBack(emptyList(), passed.risk, null, stopped, stopped))
            }
            // Send back 4 of PR #75: the merge's sha guard covers only the head.
            val again = baseMoved(wait, executor, workflow, mergedHead, skipped)
            if (again is BaseRead.Moved) return again.next
        }
        // bl-181: a wait that cannot say Loop called the merge is not merged now, the next tick tries again.
        val calling = wait.callingMerge()
        if (!wait.mergeCalled && !storeWait(calling, context)) return null
        val mergeSha =
            deliveryStep { executor.mergePullRequest(number, mergedHead, method) }
                .getOrElse { error ->
                    if (error is DeliveryError && error.retryOnce && !wait.retried) {
                        val reason = redactor.redact("the merge is tried again at the next tick: ${error.describe()}").take(REASON_MAX)
                        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
                        storeWait(calling.retrying(), context)
                        return null
                    }
                    return mergeStepFailed(calling, MergeCheck.STEP_MERGE, error)
                }
        recordWaitEvent(
            calling,
            PullRequestMerged,
            mapOf(
                Delivery.NUMBER to number.toString(),
                Delivery.SHA to mergedHead,
                Delivery.METHOD to method.wire,
                PullRequestMerged.MERGE_SHA to mergeSha,
            ),
        )
        finishMerged(calling, current, humanMerge = false)
        return null
    }

    /**
     * Why an auto merge the gate passed must not go on, null when it may: the workflow file changed and its reload was
     * rejected (round 3 of the PR #75 review: the loader rejects `auto_merge.enabled: false` under `policy: auto`, so
     * the effective workflow alone still allows it), or the effective workflow no longer allows an auto merge.
     */
    private fun autoMergeStopped(): String? =
        rejected()?.let { AutoMergeGate.rejectedReload(it.code, it.message) } ?: AutoMergeGate.notAllowedBy(workflow())

    /** What [baseMoved] decided. */
    private sealed interface BaseRead {
        /** The merge goes on: the base did not move, [skipped] null, or it moved to [skipped] with no new round (bl-176). */
        class GoesOn(
            val skipped: String?,
        ) : BaseRead

        /** The base moved: the [next] wait, null when the wait ended or keeps its state for the next tick. */
        class Moved(
            val next: Wait.Merge?,
        ) : BaseRead
    }

    /**
     * Reads the base tip of [executor] and compares it with the base the merge check of [wait] checked (m2-26): the
     * merge goes on when it did not move, or when it moved to the tip [skipped] already let through in this run, or
     * when [skipRound] lets the move through (bl-176). Otherwise a base that moved schedules the check again, at most
     * [MergeCheck.MAX_ROUNDS] rounds, then refuses; a read that fails is handled as a merge step that failed.
     */
    private suspend fun baseMoved(
        wait: Wait.Merge,
        executor: DeliveryExecutor,
        workflow: WorkflowFile,
        mergedHead: String,
        skipped: String?,
    ): BaseRead {
        val context = waitContext(wait)
        val tip =
            deliveryStep { executor.baseTip() }.getOrElse { return BaseRead.Moved(mergeStepFailed(wait, MergeCheck.STEP_MERGE, it)) }
        if (tip.equals(wait.baseSha, ignoreCase = true)) return BaseRead.GoesOn(null)
        if (skipped != null && tip.equals(skipped, ignoreCase = true)) return BaseRead.GoesOn(skipped)
        skipRound(wait, executor, workflow, mergedHead)?.let { return BaseRead.GoesOn(it) }
        val round = wait.round + 1
        if (round > MergeCheck.MAX_ROUNDS) {
            return BaseRead.Moved(
                mergeRefused(wait, MergeCheck.STEP_MERGE, "the base moved during ${MergeCheck.MAX_ROUNDS} merge checks, it is now at $tip"),
            )
        }
        log.log(LogLevel.INFO, MergeCheck.MARK, LogOutcome.SCHEDULED, context, mapOf("round" to round), "the base moved to $tip")
        val next = wait.again(round)
        storeWait(next, context)
        return BaseRead.Moved(next)
    }

    /**
     * Whether a base that moved after the check of [wait] costs no new round (bl-176): the new tip, fetched host side as
     * the merge check fetches it, null when a round must run. Only with `recheck_skip_paths` set, and only when the base
     * the check merged is an ancestor of the new tip, every file that changed between them matches a glob, and none of
     * those files is in the diff of [mergedHead] against its merge base with the new tip. A fetch or a git read that
     * fails starts a round. A skip records `merge_round_skipped`.
     */
    private suspend fun skipRound(
        wait: Wait.Merge,
        executor: DeliveryExecutor,
        workflow: WorkflowFile,
        mergedHead: String,
    ): String? {
        val pullRequest =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest ?: return null
        val globs = pullRequest.merge?.recheckSkipPaths.orEmpty()
        val checked = wait.baseSha
        if (globs.isEmpty() || checked.isNullOrEmpty()) return null
        val context = waitContext(wait)
        val stop: (String) -> String? = { reason ->
            val text = redactor.redact("the base moved, a new round of the merge check runs: $reason").take(REASON_MAX)
            log.log(LogLevel.WARN, MergeCheck.MARK, LogOutcome.FAILED, context, reason = text)
            null
        }
        val tip = deliveryStep { executor.fetchBase() }.getOrElse { return stop("cannot fetch the base: ${it.describe()}") }
        recordWaitEvent(wait, BaseFetched, mapOf(Delivery.SHA to tip, Delivery.BASE to pullRequest.base))
        fetchedBase = pullRequest.base to tip
        val workspace =
            locate(workflow, WorkspaceKey.of(wait.identifier)).getOrNull()
                ?: return stop("cannot locate the workspace of ${wait.identifier}")
        val moved =
            gitStep {
                if (!git.isAncestor(workspace, checked, tip)) return@gitStep null
                git.changedFiles(workspace, checked, tip, sinceMergeBase = false)
            }.getOrElse { return stop("cannot read the files the base changed: ${it.describe()}") } ?: return null
        val listed = ProtectedPaths(globs)
        if (moved.any { listed.match(it) == null }) return null
        val changed =
            gitStep { git.changedFiles(workspace, tip, mergedHead, sinceMergeBase = true).toSet() }
                .getOrElse { return stop("cannot read the files of the pull request: ${it.describe()}") }
        if (moved.any { it in changed }) return null
        recordWaitEvent(
            wait,
            MergeRoundSkipped,
            mapOf(
                Delivery.BASE_SHA to checked,
                MergeRoundSkipped.TIP to tip,
                MergeRoundSkipped.FILES to moved.take(MergeRoundSkipped.FILES_MAX).joinToString(","),
            ),
        )
        return tip
    }

    /**
     * Reads the pull request of [branch] until its head is [head] (send back 5): the provider may show a just pushed
     * head a moment late, and a merge sent before would be refused. At most [HEAD_READS] reads, [HEAD_READ_DELAY_MS]
     * apart; still another head after them, the merge goes on and its sha guard decides. The head last shown, null when
     * the provider did not say; an auto merge refuses another head (m2-28). A failed read is the failure.
     */
    private suspend fun awaitHead(
        executor: DeliveryExecutor,
        branch: String,
        head: String,
    ): Result<String?> {
        var shown: String? = null
        repeat(HEAD_READS) { read ->
            if (read > 0) delay(HEAD_READ_DELAY_MS)
            val found = deliveryStep { executor.findPullRequest(branch) }.getOrElse { return Result.failure(it) }
            shown = found?.headSha
            if (shown == null || shown.equals(head, ignoreCase = true)) return Result.success(shown)
        }
        return Result.success(shown)
    }

    /**
     * The issue of [wait] as the tracker shows it right before the merge (send back 6), null when the merge must not
     * happen now: a read that failed keeps the wait for the next tick; an issue gone or out of the active states was
     * moved by a human, so the wait ends with a human touch, as [settleInactiveMerge] does, the pull request left open.
     */
    private suspend fun stillActive(wait: Wait.Merge): Issue? {
        val read = fetch { withContext(io) { tracker.fetchIssuesByIds(listOf(wait.issueId)) } }
        val issues =
            read.getOrElse { error ->
                val reason = redactor.redact("cannot read the issue before the merge, it keeps waiting: ${error.describe()}")
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = reason.take(REASON_MAX))
                return null
            }
        val issue = issues.firstOrNull { it.id == wait.issueId }
        val active = workflow().activeStates.map { it.normalized() }
        if (issue != null && issue.state.normalized() in active) return issue
        settleInactiveMerge(wait, issue)
        return null
    }

    /**
     * Delivers a merged pull request (m2-26): moves [issue] to `on_delivered.move_to`, unless it is there already,
     * records it with `merged`, a human touch when [humanMerge], ends the wait and removes the workspace, whose
     * `before_remove` hook keeps the local branch. Loop never deletes the remote branch. A move that fails keeps the
     * wait, so the next tick finds the merged pull request and tries the move again; the human touch is recorded once,
     * with the move.
     */
    private suspend fun finishMerged(
        wait: Wait.Merge,
        issue: Issue,
        humanMerge: Boolean,
    ) {
        val context = waitContext(wait)
        val moveTo =
            workflow()
                .config.kinds
                ?.code
                ?.delivery
                ?.onDeliveredMoveTo
        if (moveTo != null && issue.state.normalized() != moveTo.normalized()) {
            val failure =
                try {
                    withContext(io) { tracker.moveIssue(wait.issueId, moveTo) }.exceptionOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            if (failure != null) {
                val reason = redactor.redact("cannot move the merged issue: ${failure.describe()}").take(REASON_MAX)
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
                return
            }
            val data =
                mapOfNotNull(
                    Tracker.FROM to issue.state,
                    Tracker.TO to moveTo,
                    Tracker.BY to Tracker.BY_HOST,
                    Tracker.VERIFIED to "true",
                    Tracker.APPROVED to "true",
                    Tracker.APPROVED_BY to wait.approvedBy,
                    Tracker.MERGED to "true",
                )
            recordWaitEvent(wait, IssueMoved, data)
        }
        if (humanMerge) recordWaitEvent(wait, HumanTouched, mapOf(HumanTouched.TOUCH to MergeCheck.HUMAN_MERGE))
        log.log(LogLevel.INFO, DELIVERY, LogOutcome.COMPLETED, context, mapOf("to" to moveTo, "merged" to "true"))
        endWaiting(wait)
        removeWorkspace(issue)
    }

    /**
     * A conflict or a failing recheck (m2-26): comments [comment] on the issue, hands [findings] to its next attempt,
     * and ends the wait, so the issue dispatches again. The comment is what survives a restart. A pull request merged
     * while the check ran is never sent back, see [mergedDuringCheck] (bl-181).
     */
    private suspend fun mergeCheckFailed(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        comment: String,
        findings: String,
        reason: String,
    ): Wait.Merge? {
        sentBack(wait, issue, workflow, comment, findings, reason)
        return null
    }

    /** The send back of [mergeCheckFailed]: true when the issue goes back to its agent, false when the wait ended otherwise. */
    private suspend fun sentBack(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        comment: String,
        findings: String,
        reason: String,
    ): Boolean {
        if (mergedDuringCheck(wait, issue, workflow)) return false
        commentMerge(wait, comment)
        onFindings(wait.issueId, redactor.redact(findings))
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = redactor.redact(reason).take(REASON_MAX))
        endWaiting(wait)
        return true
    }

    /**
     * Reads the pull request of [wait] before a failed check sends [issue] back to its agent (bl-181): a human may have
     * merged it while the check ran. Merged at the stored head or at the candidate head, the wait ends as a merged
     * delivery, with a human touch unless Loop called the merge; merged at another commit, the merge is refused as at the
     * start of a run, so nothing is dispatched. True when the wait ended here. A read that fails is logged and the
     * refusal goes on.
     */
    private suspend fun mergedDuringCheck(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
    ): Boolean {
        val branch = PullRequestDelivery.branch(WorkspaceKey.of(wait.identifier).value)
        val found =
            deliveryStep { executorFor(workflow).findPullRequest(branch) }.getOrElse { error ->
                val reason = redactor.redact("cannot read the pull request before the refusal: ${error.describe()}").take(REASON_MAX)
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = reason)
                return false
            } ?: return false
        when {
            mergedAtStoredHead(wait, found) -> finishMerged(wait, issue, humanMerge = !wait.mergeCalled)
            found.state == PullRequestState.Merged ->
                mergeRefused(wait, MergeCheck.STEP_MERGE, "pull request #${found.number} was merged at another commit")
            else -> return false
        }
        return true
    }

    /**
     * A merge step that failed with [error] (m2-26): one that can pass with time (a network error, a 5xx answer, a
     * rate limit hold) keeps the wait for the next tick; any other is refused for good, see [mergeRefused].
     */
    private suspend fun mergeStepFailed(
        wait: Wait.Merge,
        step: String,
        error: Throwable,
    ): Wait.Merge? {
        if (PullRequestDelivery.permanent(error)) return mergeRefused(wait, step, error.describe())
        val reason = redactor.redact("cannot merge yet, $step: ${error.describe()}").take(REASON_MAX)
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = reason)
        return null
    }

    /**
     * A merge step refused for good (m2-26): records `delivery_failed`, comments the reason on the issue, and writes the
     * wait back as an unapproved `Waiting(approval)` holding the fingerprint it holds now, the merged one after a
     * passing check, so a new approval replays it cleanly. The pull request stays open.
     */
    private suspend fun mergeRefused(
        wait: Wait.Merge,
        step: String,
        reason: String,
        comment: ((String) -> Pair<String, String>)? = null,
    ): Wait.Merge? {
        val redacted =
            redactor
                .redact(reason)
                .lineSequence()
                .first()
                .take(REASON_MAX)
        recordWaitEvent(wait, DeliveryFailed, mapOf(Delivery.STEP to step, Delivery.REASON to redacted))
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = "cannot merge, $step: $redacted")
        val (text, commentReason) =
            comment?.invoke(redacted)
                ?: (MergeCheck.failedComment(wait.numberText, step, redacted) to MergeCheck.COMMENT_REASON)
        commentMerge(wait, text, commentReason)
        storeWait(wait.refused(clock.now()), waitContext(wait))
        ciPolls.remove(wait.issueId)
        return null
    }

    /**
     * An auto merge that falls back to the `human` policy (m2-28): records `auto_merge_fallback` with the failed
     * conditions, the band when the risk was assessed and the reason, comments the conditions and the merge risk on the
     * issue, and writes the wait back as an unapproved `Waiting(approval)` holding the merged fingerprint, so an
     * operator's approval merges it after a new merge check. The pull request stays open. Not a `delivery_failed`.
     */
    private suspend fun autoMergeFallBack(
        wait: Wait.Merge,
        outcome: GateOutcome.FallBack,
    ): Wait.Merge? {
        val data =
            mapOfNotNull(
                AutoMergeFallback.CONDITIONS to outcome.failures.joinToString(",") { it.condition.wire }.ifEmpty { null },
                AutoMergeFallback.BAND to outcome.risk?.band?.wire,
                AutoMergeFallback.REASON to outcome.reason,
            )
        recordWaitEvent(wait, AutoMergeFallback, data)
        log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = "no auto merge, ${outcome.reason}")
        val number = wait.numberText
        commentMerge(
            wait,
            AutoMerge.fallbackComment(number, outcome.failures, outcome.risk, outcome.riskSkipped, outcome.stopped),
            AutoMerge.COMMENT_REASON,
        )
        storeWait(wait.refused(clock.now()), waitContext(wait))
        return null
    }

    /** Comments [text], redacted, on the issue of [wait] as `loop`, recorded with [reason]; a failure is logged at warn. */
    private suspend fun commentMerge(
        wait: Wait,
        text: String,
        reason: String = MergeCheck.COMMENT_REASON,
    ) {
        val redacted = redactor.redact(text)
        val failure =
            try {
                withContext(io) { tracker.addComment(wait.issueId, IssueComment(LOOP_AUTHOR, clock.now(), redacted)) }.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure == null) {
            recordWaitEvent(
                wait,
                IssueCommented,
                mapOf(
                    Tracker.CHARS to redacted.length.toString(),
                    Tracker.BY to Tracker.BY_HOST,
                    Tracker.REASON to reason,
                ),
            )
        } else {
            log.log(
                LogLevel.WARN,
                DELIVERY,
                LogOutcome.FAILED,
                waitContext(wait),
                reason = redactor.redact("cannot comment: ${failure.describe()}").take(REASON_MAX),
            )
        }
    }

    /** Ends the wait of [wait]'s issue, in the store and in memory, drops what memory holds of it and wakes the poll sleep. */
    private suspend fun endWaiting(wait: Wait) {
        forget(wait.issueId)
        val error = waits.end(wait).exceptionOrNull()
        // A failure is logged at warn and never stops anything: the wait in memory decides for this process (m2-04).
        if (error != null) log.log(LogLevel.WARN, WAITING, LogOutcome.FAILED, waitContext(wait), reason = error.describe())
        wake()
    }

    /**
     * Writes [wait] to the store, its issue then waiting in memory for the wait's reason, written or not; a failure is
     * logged. True when it was written. A wait that is no longer a merge wait drops what memory holds of it (bl-182).
     */
    private suspend fun storeWait(
        wait: Wait,
        context: LogContext,
    ): Boolean {
        if (wait !is Wait.Merge) forget(wait.issueId)
        val failure = waits.save(wait).exceptionOrNull()
        failure?.let {
            log.log(
                LogLevel.WARN,
                WAITING,
                LogOutcome.FAILED,
                context,
                reason = redactor.redact(it.describe()).take(REASON_MAX),
            )
        }
        return failure == null
    }

    /** Records a [definition] event for the issue and attempt of [wait], outside any attempt; a sink failure is logged. */
    private suspend fun recordWaitEvent(
        wait: Wait,
        definition: EventDefinition,
        data: Map<String, String>,
    ) {
        val event =
            definition.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = wait.issueId,
                issueIdentifier = wait.identifier,
                attemptId = wait.attemptId,
                data = data,
            )
        log.guardSink(sinkName, waitContext(wait)) { sink.record(event).getOrThrow() }
    }

    private fun waitContext(wait: Wait): LogContext =
        pollContext.copy(issueId = wait.issueId, issueIdentifier = wait.identifier, attemptId = wait.attemptId)

    /** The result of a delivery call on [io], an exception it throws turned into its failure. */
    private suspend fun <T> deliveryStep(block: suspend () -> Result<T>): Result<T> =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** The result of a blocking git call on [io], an exception it throws turned into its failure. */
    private suspend fun <T> gitStep(block: () -> T): Result<T> =
        try {
            Result.success(withContext(io) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** A tracker read with a thrown exception turned into a failed Result; a cancel still propagates. */
    private suspend fun <T> fetch(read: suspend () -> Result<T>): Result<T> =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /** The most reads of a pull request waiting for its head to show a just pushed commit (m2-26, send back 5). */
        const val HEAD_READS = 10

        /** The pause between two of those reads. */
        const val HEAD_READ_DELAY_MS = 1_000L

        /** The operator log events of a wait and of a delivery, as the orchestrator logs them. */
        private const val WAITING = "waiting"
        private const val DELIVERY = "delivery"

        /** The author of the comments Loop writes itself. */
        private const val LOOP_AUTHOR = "loop"
        private const val REASON_MAX = 200

        /** The fingerprint shown when the workspace fingerprint could not be taken. */
        private const val UNKNOWN_FINGERPRINT = "unknown"

        private fun String.normalized(): String = trim().lowercase()

        private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"

        private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }
}
