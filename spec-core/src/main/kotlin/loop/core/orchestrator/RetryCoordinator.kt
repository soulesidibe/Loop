package loop.core.orchestrator

import loop.api.AttemptId
import loop.api.EventSink
import loop.api.HumanTouch
import loop.api.Issue
import loop.api.IssueComment
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ParkedIssues
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.RetryState
import loop.api.RetryStates
import loop.api.RunId
import loop.core.events.EventDefinition
import loop.core.events.HumanTouched
import loop.core.events.IssueCommented
import loop.core.events.IssueParked
import loop.core.events.IssueUnparked
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.events.Tracker
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.trackers.TrackerPoll
import loop.core.trackers.TrackerRead
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import loop.core.events.Spend as SpendKeys

/**
 * A due retry whose by-id refresh found the issue eligible: dispatch it with [attempt]. [firstDueAt]
 * orders it among the due retries of a tick (bl-34). [retryKind] is its `loop.retry_kind` (m3-11).
 */
internal data class DueRetry(
    val issue: Issue,
    val attempt: Int,
    val firstDueAt: Instant,
    val retryKind: String? = null,
)

/**
 * What firing the due retries did: the issues to dispatch, and every issue id the
 * retry path handled this tick (dispatched, rescheduled or dropped), which the
 * candidate path must skip.
 */
internal data class RetryFire(
    val dispatch: List<DueRetry>,
    val handled: Set<String>,
)

/**
 * Retries, the attempt cap and parking for the [Orchestrator] (m1-07, spec 8.4,
 * spec decisions answer 5). Parked issues live in [parked], read on every tick. The
 * retry queue and its counters live in memory and in [retryStates] (m2-02): every
 * issue a call changes is written at the end of that call, a failed write is tried
 * again at the end of the next one, and [restore] reads them back on the first tick
 * of a new process. The workflow is passed on every call, never cached, since m1-15 swaps it.
 */
internal class RetryCoordinator(
    /** m4-13: every tracker of the run, each issue read by id from its own. */
    private val tracker: TrackerPoll,
    private val parked: ParkedIssues,
    private val sink: EventSink,
    private val sinkName: String,
    private val log: KeyValueLog,
    private val clock: Clock,
    private val runId: RunId,
    private val projectId: ProjectId,
    /** Runs when a retry is dropped because its issue is terminal (plan D8 revised). */
    private val onTerminalRetryDropped: suspend (Issue) -> Unit,
    private val retryStates: RetryStates = RetryStates.NONE,
    /** m4-30: runs once per park, with the park and why, to tell a human; a restart reads parks and never calls it. */
    private val onParked: suspend (ParkedIssue, String) -> Unit = { _, _ -> },
) {
    private val queue = RetryQueue()

    /** True once [restore] read the stored retry states. */
    private var restored = false

    /** Issues whose retry state changed and is not in [retryStates] yet. */
    private val unsaved = mutableSetOf<String>()

    /**
     * m4-34: the issues a reaction sent back since their counter was last reset, so the approval exit of a reaction
     * attempt keeps the failure counter. In memory: a restart forgets it, and the next approval exit resets the counter.
     */
    private val reacted = mutableSetOf<String>()

    /** Parks whose store write failed, by issue id: parked for this process, written again every tick. */
    private val pendingParks = mutableMapOf<String, ParkedIssue>()

    /** m4-15: the issue each queued retry's last by id refresh read, used in its place until the next poll interval. */
    private val lastRead = mutableMapOf<String, Issue>()

    private val pollContext = LogContext(runId = runId, projectId = projectId)

    fun isWaiting(
        issueId: String,
        now: Instant,
    ): Boolean = queue.isWaiting(issueId, now)

    /**
     * Reads the retry states a previous process left (m2-02), once: each queued retry keeps its due time
     * and spec attempt, each issue its counters, and each queued retry is recorded again in this run as a
     * `retry_scheduled` with `restored=true`. Returns false when the store cannot be read: the caller then
     * dispatches nothing this tick, rather than dispatch with a lost due time or counter, and calls again.
     */
    suspend fun restore(workflow: WorkflowFile): Boolean {
        if (restored) return true
        val states =
            safely { retryStates.retryStates(projectId) }.getOrElse { error ->
                log.log(LogLevel.ERROR, RETRY_STATE, LogOutcome.FAILED, pollContext, reason = error.describe())
                return false
            }
        restored = true
        val now = clock.now()
        for (state in states.sortedBy { it.retry?.dueAt }) {
            val entry = state.retry?.let { RetryEntry(state.issueId, state.identifier, it.attempt, it.dueAt, it.error) }
            queue.restore(RetrySnapshot(state.issueId, state.identifier, entry, state.failures, state.noProgress))
            if (entry == null) continue
            val delayMs = (entry.dueAt - now).inWholeMilliseconds.coerceAtLeast(0)
            val counts = rescheduleCounts(entry, workflow) + mapOf(RetryScheduled.RESTORED to "true")
            scheduled(entry, delayMs, state.failures, attemptId = null, counts)
        }
        return true
    }

    /** The issues with a queued retry, by id, each with its identifier (m4-10). */
    fun queued(): Map<String, String> = queue.queued()

    /** The earliest due time among issues not in [busy] (claimed or running), whose entries do not fire. */
    fun nextDueAt(busy: Set<String> = emptySet()): Instant? = queue.nextDueAt(busy)

    /**
     * Unparks the parked issues whose tracker state or labels changed, and returns
     * the ids still parked. Parked issues among [candidates] are compared with them;
     * the others are fetched by id in one call. Not found drops the record; a failed
     * fetch keeps them parked this tick. Parks whose store write failed are written
     * again first and stay parked until then. Null when the store cannot be read: the
     * caller dispatches nothing, rather than dispatch a parked issue.
     */
    suspend fun stillParked(candidates: List<Issue>): Set<String>? {
        pendingParks.values.toList().forEach { write(it) }
        val pending = pendingParks.keys.toSet()
        val records =
            parked.list(projectId).getOrElse { error ->
                log.log(LogLevel.ERROR, "parked", LogOutcome.FAILED, pollContext, reason = error.describe())
                return null
            }
        if (records.isEmpty()) return pending
        val candidatesById = candidates.associateBy { it.id }
        val missing = records.map { it.issueId }.filter { it !in candidatesById }
        val read = fetchByIds(missing)
        // m4-13: the parks of a tracker whose read failed stay parked this tick, the others go on.
        val fetched: Map<String, Issue> = read.issues.associateBy { it.id }
        val still = pending.toMutableSet()
        for (record in records) {
            val candidate = candidatesById[record.issueId]
            if (candidate == null && !read.answered(record.issueId)) {
                // The by-id fetch failed: no change can be seen this tick.
                still += record.issueId
                continue
            }
            val current = candidate ?: fetched[record.issueId]
            if (current == null) {
                // The tracker no longer has the issue: nothing left to keep parked.
                parked.unpark(projectId, record.issueId).onFailure { warnStore(it, record.issueId, record.identifier) }
                continue
            }
            if (!changed(record, current)) {
                still += record.issueId
                continue
            }
            val unparked = parked.unpark(projectId, record.issueId)
            if (unparked.isFailure) {
                unparked.exceptionOrNull()?.let { warnStore(it, record.issueId, record.identifier) }
                still += record.issueId
                continue
            }
            record(
                record.issueId,
                record.identifier,
                IssueUnparked,
                mapOf(
                    IssueUnparked.REASON to TRACKER_CHANGED,
                    IssueUnparked.FROM_STATE to record.state,
                    IssueUnparked.TO_STATE to current.state,
                ),
            )
            // A human changed the parked issue in the tracker: a human touch for the ledger (m2-19).
            record(
                record.issueId,
                record.identifier,
                HumanTouched,
                mapOf(
                    HumanTouched.TOUCH to HumanTouch.MOVE,
                    HumanTouched.FROM_STATE to record.state,
                    HumanTouched.TO_STATE to current.state,
                ),
            )
            log.log(
                LogLevel.INFO,
                "issue",
                LogOutcome.RESUMED,
                context(record.issueId, record.identifier),
                fields = mapOf("by" to TRACKER_CHANGED, "from" to record.state, "to" to current.state),
            )
        }
        return still
    }

    /**
     * Fires the retries due now (spec 8.4, 16.6): one by-id refresh for all of them.
     * A failed refresh reschedules each with the next spec attempt and no failure
     * counted. Otherwise each entry is dropped or dispatched by its issue's state.
     * Entries of [busy] issues (claimed or running) wait: a terminal refresh would
     * otherwise remove the workspace of a running attempt. A [parked] issue keeps nothing: a park drops
     * its entry and counters, so whatever the queue holds for it (a stored row whose delete failed) goes.
     * Since m4-15 (bl-77) a retry refreshed less than one poll interval ago, one that waits for a slot, is not read by id
     * again: it is judged on this tick's polled copy of its issue, else on the copy its last refresh read. Only the
     * entries this changes are written to the store.
     */
    suspend fun fireDue(
        candidates: List<Issue>,
        workflow: WorkflowFile,
        busy: Set<String> = emptySet(),
        parked: Set<String> = emptySet(),
        /** m4-13: false for an issue whose tracker failed this tick's poll, so its absence says nothing. */
        polled: (String) -> Boolean = { true },
    ): RetryFire {
        for (issueId in parked) {
            if (queue.snapshot(issueId) == null) continue
            queue.drop(issueId, resetCounter = true)
            unsaved += issueId
        }
        // An issue with a kept counter and no entry that is no longer fetched by the
        // active states left them: its counter resets (spec decisions answer 5).
        unsaved += queue.forgetAbsent(candidates.map { it.id }.toSet(), polled)
        val now = clock.now()
        val due = queue.due(now).filter { it.issueId !in busy }
        if (due.isEmpty()) {
            save()
            return RetryFire(emptyList(), emptySet())
        }
        val handled = due.map { it.issueId }.toSet()
        lastRead.keys.retainAll(queue.queued().keys)
        val known = recentlyRead(due, candidates, now, workflow.pollingIntervalMs.coerceAtLeast(1))
        val refreshed = fetchByIds(due.map { it.issueId }.filter { it !in known })
        for (issue in refreshed.issues) {
            queue.refreshed(issue.id, now)
            lastRead[issue.id] = issue
        }
        val issues = refreshed.issues.associateBy { it.id } + known
        val dispatch = mutableListOf<DueRetry>()
        for (entry in due) {
            // m4-13: the retries of a tracker whose refresh failed are rescheduled, the others go on.
            if (entry.issueId !in known && !refreshed.answered(entry.issueId)) {
                val counts = rescheduleCounts(entry, workflow)
                val next = queue.onRefreshFailed(entry.issueId, now, workflow.config.agent.maxRetryBackoffMs) ?: continue
                scheduled(next, (next.dueAt - now).inWholeMilliseconds, queue.failures(entry.issueId), attemptId = null, counts)
                continue
            }
            val issue = issues[entry.issueId]
            if (issue == null) {
                queue.drop(entry.issueId, resetCounter = false)
                dropped(entry, ExitReason.NOT_FOUND)
                continue
            }
            when (RetryTarget.of(issue, workflow)) {
                RetryTarget.Terminal -> {
                    queue.drop(entry.issueId, resetCounter = true)
                    dropped(entry, ExitReason.TERMINAL)
                    onTerminalRetryDropped(issue)
                }
                RetryTarget.NotActive -> {
                    queue.drop(entry.issueId, resetCounter = true)
                    dropped(entry, ExitReason.NOT_ACTIVE)
                }
                RetryTarget.NotEligible -> {
                    queue.drop(entry.issueId, resetCounter = false)
                    dropped(entry, ExitReason.NOT_ELIGIBLE, blockedReason = issue.blockedReason)
                }
                RetryTarget.Eligible -> dispatch += DueRetry(issue, entry.attempt, entry.firstDueAt, retryKindOf(entry))
            }
        }
        // A retry to dispatch is unchanged in the queue; every other one was dropped or rescheduled.
        unsaved += handled - dispatch.map { it.issue.id }.toSet()
        save()
        return RetryFire(dispatch, handled)
    }

    /**
     * m4-15: the issues of the [due] retries whose last by id refresh is less than [pollMs] old, by id, so they are not
     * read again: this tick's polled copy among [candidates] when the poll has one, else the copy of that refresh.
     */
    private fun recentlyRead(
        due: List<RetryEntry>,
        candidates: List<Issue>,
        now: Instant,
        pollMs: Long,
    ): Map<String, Issue> {
        val polled = candidates.associateBy { it.id }
        return due
            .filter { entry -> entry.refreshedAt?.let { (now - it).inWholeMilliseconds < pollMs } == true }
            .mapNotNull { entry -> (polled[entry.issueId] ?: lastRead[entry.issueId])?.let { entry.issueId to it } }
            .toMap()
    }

    /**
     * The `loop.retry_kind` of [entry] (m3-11): `continuation` after a normal exit, `refuted` after an attempt whose
     * failure was a refutation, `error` after any other failure.
     */
    private fun retryKindOf(entry: RetryEntry): String =
        when {
            entry.kind == RetryKind.Continuation -> PromptVariables.RETRY_CONTINUATION
            entry.error == PromptVariables.RETRY_REFUTED -> PromptVariables.RETRY_REFUTED
            entry.error == PromptVariables.RETRY_REACTION -> PromptVariables.RETRY_REACTION
            else -> PromptVariables.RETRY_ERROR
        }

    /**
     * A due retry found no free orchestrator slot: requeued with error `no available orchestrator slots`
     * and no failure counted (spec 8.4, 17.4). Since bl-34 it keeps its spec attempt and stays due from its
     * first due time, so every tick sees it, instead of the next attempt and its backoff (spec 16.6). Its due
     * time moves one poll interval ahead, which only says when the daemon wakes. A due retry that waits for the probe
     * of the runner hold (bl-39) is requeued the same way, with [error] `runner_unavailable`. Since m3-16 [error] may
     * also say it found no machine slot or the machine day budget held it.
     *
     * Since m4-15 (bl-77) a requeue for the reason the retry already waits for records no `retry_scheduled`, no WARN
     * line and no store write: only the moved due time changes, in memory. The stored due time stays the earlier one,
     * which is just as due after a restart.
     */
    suspend fun requeueNoSlot(
        issue: Issue,
        workflow: WorkflowFile,
        error: String = NO_SLOTS,
    ) {
        val now = clock.now()
        val current = queue.entry(issue.id) ?: return
        val counts = rescheduleCounts(current, workflow)
        val next = queue.requeue(issue.id, now, workflow.pollingIntervalMs.coerceAtLeast(1), error) ?: return
        if (current.waitingFor == error) return
        unsaved += issue.id
        save()
        scheduled(next, (next.dueAt - now).inWholeMilliseconds, queue.failures(issue.id), attemptId = null, counts)
    }

    /**
     * bl-39: the attempt of [issue] failed `runner_unavailable` and the run is held: it takes no `max_attempts` slot, its
     * issue is requeued at [dueAt], the hold's probe time, with the next spec attempt and its failure counter unchanged.
     */
    suspend fun requeueUnavailable(
        issue: Issue,
        summary: AttemptSummary,
        dueAt: Instant,
    ) {
        try {
            val now = clock.now()
            val error = summary.runnerUnavailable?.reason ?: summary.reason ?: RUNNER_UNAVAILABLE
            val entry = queue.onUnavailable(issue.id, issue.identifier, error, now, dueAt)
            scheduled(entry, (entry.dueAt - now).inWholeMilliseconds, queue.failures(issue.id), summary.attemptId)
        } finally {
            unsaved += issue.id
            save()
        }
    }

    /**
     * The no progress pair for a reschedule of [entry], read before it: the rescheduled entry
     * carries an error, so a continuation would otherwise lose its count after its first
     * reschedule. Empty for an error retry of an issue with no count.
     */
    private fun rescheduleCounts(
        entry: RetryEntry,
        workflow: WorkflowFile,
    ): Map<String, String> {
        val noProgress = queue.noProgress(entry.issueId)
        val carry = entry.kind == RetryKind.Continuation || noProgress > 0
        return if (carry) noProgressData(noProgress, workflow) else emptyMap()
    }

    /**
     * Bookkeeping after an attempt ran to its end: a success resets the failure counter and
     * counts or resets the no progress count, then schedules the continuation retry or parks
     * the issue at `retries.max_continuations`; a failure schedules a retry or parks the issue.
     * A canceled attempt never gets here.
     */
    suspend fun afterAttempt(
        issue: Issue,
        summary: AttemptSummary,
        workflow: WorkflowFile,
    ) {
        try {
            countAttempt(issue, summary, workflow)
        } finally {
            unsaved += issue.id
            save()
        }
    }

    private suspend fun countAttempt(
        issue: Issue,
        summary: AttemptSummary,
        workflow: WorkflowFile,
    ) {
        when (summary.outcome) {
            AttemptOutcome.Succeeded -> {
                if (summary.exitReason == ExitReason.WAITING_ASK) {
                    // m4-31: an ask waits for a human. It takes no max_attempts slot and counts toward no
                    // max_continuations, so the counters stay as they were, neither counted nor reset.
                    queue.drop(issue.id, resetCounter = false)
                    return
                }
                if (summary.exitReason == ExitReason.WAITING_APPROVAL) {
                    // m2-24: a verified result waiting for approval is progress and needs no continuation. m4-34: after a
                    // reaction it keeps the failure counter, so a base that keeps moving cannot send the issue back forever.
                    queue.drop(issue.id, resetCounter = issue.id !in reacted)
                    return
                }
                if (summary.handedOff) {
                    // m2-23: the attempt moved its issue to a handoff state, so it waits for a human and needs no
                    // continuation, even on a label tracker where it still reads active or a stale read shows it eligible.
                    queue.drop(issue.id, resetCounter = true)
                    return
                }
                val maxContinuations = workflow.config.retries.maxContinuations
                // not_found keeps the count on purpose; a reload that lowers the limit parks at the next counted exit.
                val decision =
                    when (normalExitProgress(summary, workflow)) {
                        NormalExitProgress.NotCounted -> ContinuationDecision.Continue(queue.onUncountedNormalExit(issue.id))
                        NormalExitProgress.Progressed -> queue.onNormalExit(issue.id, progressed = true, maxContinuations)
                        NormalExitProgress.NoProgress -> queue.onNormalExit(issue.id, progressed = false, maxContinuations)
                    }
                val counts = noProgressData(decision.noProgress, workflow)
                when (decision) {
                    // The queue already dropped the entry and zeroed the counters.
                    is ContinuationDecision.Park -> park(issue, summary, ParkReason.MaxContinuations, counts, clock.now())
                    is ContinuationDecision.Continue -> {
                        // Spec 16.6: below the cap a normal exit always schedules the 1 s continuation retry; the
                        // timer drops it when the issue is no longer eligible.
                        val entry = queue.scheduleContinuation(issue.id, issue.identifier, clock.now())
                        scheduled(entry, RetryQueue.CONTINUATION_DELAY_MS, failures = 0, attemptId = summary.attemptId, counts)
                    }
                }
            }
            AttemptOutcome.Failed -> {
                val error = summary.reason ?: "failed"
                val now = clock.now()
                val parkReason = summary.parkReason
                if (parkReason != null) {
                    // Parked on its own reason, before the failure count: it takes no `max_attempts` slot, but
                    // the park drops the entry and zeroes the counter like any other (spec decisions answer 5).
                    queue.drop(issue.id, resetCounter = true)
                    park(issue, summary, parkReason, summary.parkData, now)
                    return
                }
                val decision =
                    queue.onFailure(
                        issueId = issue.id,
                        identifier = issue.identifier,
                        error = error,
                        now = now,
                        maxAttempts = workflow.config.retries.maxAttempts,
                        maxBackoffMs = workflow.config.agent.maxRetryBackoffMs,
                    )
                when (decision) {
                    is FailureDecision.Retry -> scheduled(decision.entry, decision.delayMs, decision.failures, summary.attemptId)
                    is FailureDecision.Park ->
                        park(
                            issue,
                            summary,
                            ParkReason.MaxAttempts,
                            mapOf(IssueParked.FAILURES to decision.failures.toString(), IssueParked.ERROR to decision.error),
                            now,
                        )
                }
            }
        }
    }

    /**
     * m4-34: a reaction of `reactions:` ended the approval wait of [issue], started by [attemptId], to send it back to its
     * agent. It takes a `max_attempts` slot like a failure, error `reaction`, so its retry renders `loop.retry_kind` =
     * `reaction`; the reaction that takes the last slot parks the issue with `max_attempts` instead. True when the retry
     * is scheduled, false when the issue parked.
     */
    suspend fun onReaction(
        issue: Issue,
        attemptId: AttemptId?,
        workflow: WorkflowFile,
    ): Boolean {
        try {
            val now = clock.now()
            val decision =
                queue.onFailure(
                    issueId = issue.id,
                    identifier = issue.identifier,
                    error = PromptVariables.RETRY_REACTION,
                    now = now,
                    maxAttempts = workflow.config.retries.maxAttempts,
                    maxBackoffMs = workflow.config.agent.maxRetryBackoffMs,
                )
            return when (decision) {
                is FailureDecision.Retry -> {
                    reacted += issue.id
                    scheduled(decision.entry, decision.delayMs, decision.failures, attemptId)
                    true
                }
                is FailureDecision.Park -> {
                    park(
                        issue,
                        issue,
                        attemptId,
                        ParkReason.MaxAttempts,
                        mapOf(IssueParked.FAILURES to decision.failures.toString(), IssueParked.ERROR to decision.error),
                        now,
                    )
                    false
                }
            }
        } finally {
            unsaved += issue.id
            save()
        }
    }

    /**
     * Reconciliation stopped the attempt of [issueId] for [reason], an [ExitReason] value (m1-16): no retry
     * follows, so its entry is dropped. Like the retry refresh, a terminal or not active issue resets its
     * counters, a not eligible or vanished one keeps them.
     */
    suspend fun onReconciled(
        issueId: String,
        reason: String,
    ) {
        val reset = reason == ExitReason.TERMINAL || reason == ExitReason.NOT_ACTIVE
        queue.drop(issueId, resetCounter = reset)
        if (reset) reacted -= issueId
        unsaved += issueId
        save()
    }

    /**
     * Parks [issue] for [reason] outside an attempt, for the checks of a restored wait (m2-08). Like a park at
     * the end of an attempt it takes no `max_attempts` slot, drops the retry entry and zeroes the counters.
     * [issue] is the issue as last read, its parked snapshot; [data] is what `issue_parked` says about it.
     */
    suspend fun parkNow(
        issue: Issue,
        attemptId: AttemptId?,
        reason: ParkReason,
        data: Map<String, String>,
    ) {
        queue.drop(issue.id, resetCounter = true)
        try {
            park(issue, issue, attemptId, reason, data, clock.now())
        } finally {
            unsaved += issue.id
            save()
        }
    }

    /**
     * Writes the retry state of every issue in [unsaved] to [retryStates], or deletes it when the queue keeps
     * nothing for the issue. A failed write is logged and the issue stays in [unsaved] for the next call.
     */
    private suspend fun save() {
        for (issueId in unsaved.toList()) {
            val snapshot = queue.snapshot(issueId)
            val written =
                safely {
                    if (snapshot == null) {
                        retryStates.deleteRetryState(projectId, issueId)
                    } else {
                        retryStates.saveRetryState(snapshot.toRetryState())
                    }
                }
            written
                .onSuccess { unsaved -= issueId }
                .onFailure { error ->
                    val identifier = snapshot?.identifier ?: issueId
                    log.log(LogLevel.WARN, RETRY_STATE, LogOutcome.FAILED, context(issueId, identifier), reason = error.describe())
                }
        }
    }

    private fun RetrySnapshot.toRetryState(): RetryState =
        RetryState(
            projectId = projectId,
            issueId = issueId,
            identifier = identifier,
            retry = entry?.let { QueuedRetry(it.attempt, it.dueAt, it.error) },
            failures = failures,
            noProgress = noProgress,
        )

    /** [block], with a thrown exception turned into a failed Result. */
    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Parks [issue] for [reason]; [data] is what the `issue_parked` event says about it (its `error` also goes to the log). */
    private suspend fun park(
        issue: Issue,
        summary: AttemptSummary,
        reason: ParkReason,
        data: Map<String, String>,
        now: Instant,
    ) {
        // The snapshot is the issue as read after the attempt, so a change the agent made is not seen as a change later.
        park(issue, summary.refreshedIssue ?: issue, summary.attemptId, reason, data, now)
    }

    /** Parks [issue] with the tracker state and labels of [snapshot], see the overload above. */
    private suspend fun park(
        issue: Issue,
        snapshot: Issue,
        attemptId: AttemptId?,
        reason: ParkReason,
        data: Map<String, String>,
        now: Instant,
    ) {
        val record =
            ParkedIssue(
                projectId = projectId,
                issueId = issue.id,
                identifier = issue.identifier,
                reason = reason,
                state = snapshot.state,
                labels = snapshot.labels.normalizedSet(),
                parkedAt = now,
                runId = runId,
            )
        val storeError = write(record, attemptId)
        val event = mapOf(IssueParked.REASON to reason.wire) + data + listOfNotNull(storeError?.let { IssueParked.STORE_ERROR to it })
        record(issue.id, issue.identifier, IssueParked, event, attemptId)
        log.log(
            // Only the failure cap is an error; the other caps are guards.
            if (reason == ParkReason.MaxAttempts) LogLevel.ERROR else LogLevel.WARN,
            "issue",
            LogOutcome.PARKED,
            context(issue.id, issue.identifier, attemptId),
            fields = mapOf<String, Any?>("park_reason" to reason.wire) + (data - "error"),
            reason = data["error"] ?: reason.wire,
        )
        if (reason == ParkReason.SplitIssue) commentSplit(issue, data, attemptId, now)
        reacted -= issue.id
        onParked(record, data["error"]?.let { "${reason.wire}: $it" } ?: reason.wire)
    }

    /**
     * m3-12: a `split_issue` park tells a human on the issue, as `loop`, with the finding "split this issue". A failed
     * comment is logged at warn; the issue stays parked.
     */
    private suspend fun commentSplit(
        issue: Issue,
        data: Map<String, String>,
        attemptId: AttemptId?,
        now: Instant,
    ) {
        val text = splitComment(issue.identifier, data[IssueParked.WITHOUT_PROGRESS]?.toIntOrNull() ?: SPLIT_AFTER)
        val failure = safely { tracker.addComment(issue, IssueComment(LOOP_AUTHOR, now, text)) }.exceptionOrNull()
        if (failure == null) {
            val event =
                mapOf(Tracker.CHARS to text.length.toString(), Tracker.BY to Tracker.BY_HOST, Tracker.REASON to IssueCommented.SPLIT_ISSUE)
            record(issue.id, issue.identifier, IssueCommented, event, attemptId)
        } else {
            log.log(
                LogLevel.WARN,
                "issue",
                LogOutcome.FAILED,
                context(issue.id, issue.identifier, attemptId),
                reason = "cannot comment the split_issue park: ${failure.describe()}",
            )
        }
    }

    /**
     * Writes [record] to the store. On failure the park is kept in [pendingParks], so the
     * issue stays parked in this process and the write is retried on every tick.
     * Returns the store error, or null when the write succeeded.
     */
    private suspend fun write(
        record: ParkedIssue,
        attemptId: AttemptId? = null,
    ): String? {
        val error = safePark(record).exceptionOrNull()
        if (error == null) {
            pendingParks -= record.issueId
            return null
        }
        pendingParks[record.issueId] = record
        log.log(
            LogLevel.ERROR,
            "parked",
            LogOutcome.FAILED,
            context(record.issueId, record.identifier, attemptId),
            reason = error.describe(),
        )
        return error.describe()
    }

    /** [ParkedIssues.park], with a thrown exception turned into a failed Result. */
    private suspend fun safePark(record: ParkedIssue): Result<Unit> = safely { parked.park(record) }

    /** Records `retry_scheduled`; [counts] is the no progress pair of a continuation, empty otherwise. */
    private suspend fun scheduled(
        entry: RetryEntry,
        delayMs: Long,
        failures: Int,
        attemptId: AttemptId?,
        counts: Map<String, String> = emptyMap(),
    ) {
        record(
            entry.issueId,
            entry.identifier,
            RetryScheduled,
            mapOfNotNull(
                RetryScheduled.KIND to entry.kind.wire,
                RetryScheduled.ATTEMPT to entry.attempt.toString(),
                RetryScheduled.DELAY_MS to delayMs.toString(),
                RetryScheduled.DUE_AT to entry.dueAt.toString(),
                RetryScheduled.ERROR to entry.error,
                RetryScheduled.FAILURES to failures.toString(),
            ) + counts,
            attemptId,
        )
        log.log(
            if (entry.kind == RetryKind.Continuation) LogLevel.INFO else LogLevel.WARN,
            "retry",
            LogOutcome.SCHEDULED,
            context(entry.issueId, entry.identifier, attemptId),
            fields = mapOf("kind" to entry.kind.wire, "attempt" to entry.attempt, "delay_ms" to delayMs, "due_at" to entry.dueAt),
            reason = entry.error,
        )
    }

    /** Records `retry_dropped`; [blockedReason] is set when a blocker made the issue not eligible. */
    private suspend fun dropped(
        entry: RetryEntry,
        reason: String,
        blockedReason: String? = null,
    ) = record(
        entry.issueId,
        entry.identifier,
        RetryDropped,
        mapOfNotNull(RetryDropped.REASON to reason, RetryDropped.BLOCKED_REASON to blockedReason),
    )

    private suspend fun record(
        issueId: String,
        identifier: String,
        definition: EventDefinition,
        data: Map<String, String>,
        attemptId: AttemptId? = null,
    ) {
        val event =
            definition.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = issueId,
                issueIdentifier = identifier,
                attemptId = attemptId,
                data = data,
            )
        log.guardSink(sinkName, context(issueId, identifier, attemptId)) { sink.record(event).getOrThrow() }
    }

    private fun warnStore(
        error: Throwable,
        issueId: String,
        identifier: String,
    ) = log.log(LogLevel.WARN, "parked", LogOutcome.FAILED, context(issueId, identifier), reason = error.describe())

    private fun context(
        issueId: String,
        identifier: String,
        attemptId: AttemptId? = null,
    ): LogContext = LogContext(runId = runId, projectId = projectId, issueId = issueId, issueIdentifier = identifier, attemptId = attemptId)

    /** [ids] read by id, each from its own tracker (m4-13); each tracker that fails is logged with its id. */
    private suspend fun fetchByIds(ids: List<String>): TrackerRead {
        val read = tracker.readByIds(ids)
        read.failures.forEach { (id, error) ->
            log.log(LogLevel.WARN, "tracker", LogOutcome.FAILED, pollContext, fields = mapOf("tracker" to id), reason = error.describe())
        }
        return read
    }

    private companion object {
        const val TRACKER_CHANGED = IssueUnparked.TRACKER_CHANGED
        const val NO_SLOTS = "no available orchestrator slots"
        const val RUNNER_UNAVAILABLE = "runner_unavailable"
        const val RETRY_STATE = "retry_state"
        const val LOOP_AUTHOR = "loop"
        const val SPLIT_AFTER = RotationProgress.SPLIT_AFTER

        /** The comment of a `split_issue` park of [identifier] after [withoutProgress] rotations in a row (m3-12). */
        fun splitComment(
            identifier: String,
            withoutProgress: Int,
        ): String =
            "Loop parked this issue with the finding: ${IssueParked.SPLIT_FINDING}.\n\n" +
                "$withoutProgress session rotations in a row came without progress, no new commit on the issue's branch, " +
                "no change to its working tree and no verification with fewer failing checks, so the issue looks too big " +
                "for one agent session. " +
                "Split it into smaller issues, then run `loop resume $identifier` or change it in the tracker."

        fun changed(
            record: ParkedIssue,
            current: Issue,
        ): Boolean = stateOrLabelsChanged(record.state, record.labels, current.state, current.labels)

        /** The `no_progress` and `max_continuations` pair of `retry_scheduled` and `issue_parked`. */
        fun noProgressData(
            noProgress: Int,
            workflow: WorkflowFile,
        ): Map<String, String> {
            val retries = workflow.config.retries
            return mapOf(
                SpendKeys.NO_PROGRESS to noProgress.toString(),
                SpendKeys.MAX_CONTINUATIONS to retries.maxContinuations.toString(),
            )
        }

        fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"
    }
}

/** Where a due retry's issue stands after its by-id refresh (plan review item 1). */
internal enum class RetryTarget {
    Eligible,
    Terminal,
    NotActive,
    NotEligible,
    ;

    companion object {
        fun of(
            issue: Issue,
            workflow: WorkflowFile,
        ): RetryTarget {
            // m4-13: judged by the issue's own tracker; an issue whose tracker left the workflow is no longer active.
            val own = workflow.trackers.trackerOf(issue.id) ?: return NotActive
            return when {
                own.isTerminal(issue.state) -> Terminal
                !own.isActive(issue.state) -> NotActive
                !own.hasRequiredLabels(issue.labels) -> NotEligible
                // A blocked issue also ends a running attempt at its next turn boundary.
                !issue.dispatchable -> NotEligible
                else -> Eligible
            }
        }
    }
}

/** Whether a normal exit moved its issue, for the no progress counter (m1-27). */
internal enum class NormalExitProgress {
    /** The state or labels changed, or the issue left the active states: the count resets. */
    Progressed,

    /** Neither changed: the count goes up. */
    NoProgress,

    /** The issue is gone (`not_found`): the count neither goes up nor resets. */
    NotCounted,
}

/**
 * The progress rule of the no progress counter: the issue as dispatched against the issue as
 * read after the attempt. Body, description and comments are not compared. An issue that could
 * not be read after an exit other than `not_found` is no progress, so a flaky read never defeats
 * the cap. A refreshed issue now terminal or not active counts as progress even with the same
 * state, since a workflow reload can change the states.
 */
internal fun normalExitProgress(
    summary: AttemptSummary,
    workflow: WorkflowFile,
): NormalExitProgress {
    if (summary.exitReason == ExitReason.NOT_FOUND) return NormalExitProgress.NotCounted
    val refreshed = summary.refreshedIssue ?: return NormalExitProgress.NoProgress
    val dispatched = summary.dispatchedIssue
    val target = RetryTarget.of(refreshed, workflow)
    val moved =
        stateOrLabelsChanged(dispatched.state, dispatched.labels, refreshed.state, refreshed.labels) ||
            target == RetryTarget.Terminal ||
            target == RetryTarget.NotActive
    return if (moved) NormalExitProgress.Progressed else NormalExitProgress.NoProgress
}

/** One definition of an issue change, shared by unparking and the no progress counter. */
private fun stateOrLabelsChanged(
    fromState: String,
    fromLabels: Collection<String>,
    toState: String,
    toLabels: Collection<String>,
): Boolean = fromState.normalized() != toState.normalized() || fromLabels.normalizedSet() != toLabels.normalizedSet()

private fun String.normalized(): String = trim().lowercase()

private fun Collection<String>.normalizedSet(): Set<String> = map { it.normalized() }.toSet()

/** The pairs whose value is not null, as a map. */
internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .toMap()
