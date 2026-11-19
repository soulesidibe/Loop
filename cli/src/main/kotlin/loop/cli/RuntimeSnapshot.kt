package loop.cli

import loop.api.AttemptId
import loop.api.ParkedIssue
import loop.api.RecordedEvent
import loop.api.RunId
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.ContextUsage
import loop.core.events.IssueParked
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.events.RunnerHoldLifted
import loop.core.events.RunnerHoldStarted
import loop.core.events.TurnStarted
import loop.core.events.Usage
import loop.core.waiting.Wait
import loop.store.StoredEvent
import kotlin.time.Instant

/**
 * What `loop status` prints (m1-14, spec 13.3): the issues the live run is working on or will retry, and the
 * waiting (m2-04) and parked issues of the project. Built from the store only, so it works from any terminal.
 */
internal data class RuntimeSnapshot(
    val running: List<RunningRow>,
    val retrying: List<RetryingRow>,
    val parked: List<ParkedRow>,
    val waiting: List<WaitingRow> = emptyList(),
    /** The runner hold of the live run (bl-39), null when none holds it. */
    val hold: HoldRow? = null,
)

/** The latest `runner_hold_started` of the live run with no `runner_hold_lifted` after it (bl-39). */
internal data class HoldRow(
    /** The hold period's number, 1 for the first. */
    val hold: String?,
    val probeAt: String?,
    val reason: String?,
)

/** An attempt as `loop trace` numbers it: its rank among the issue's attempts, in start order, and its id. */
internal data class AttemptRef(
    val number: Int,
    val id: AttemptId,
)

internal data class RunningRow(
    val identifier: String,
    val attempt: AttemptRef,
    val startedAt: Instant,
    /** The `turn_started` events of the attempt (spec 13.3 `turn_count`). */
    val turns: Int,
    /** `attempt_tokens` of the attempt's latest `usage` event, null before its first. */
    val tokens: String?,
    /** The occupancy of the session of the attempt's latest turn (m3-09), null before that session's first report. */
    val occupancy: SessionOccupancy? = null,
    /**
     * Whether the attempt's runner declares occupancy, from its `attempt_started` (m3-09); null for an attempt recorded
     * before m3-09, which said nothing about it.
     */
    val declaresOccupancy: Boolean? = null,
)

/** A retry the live run scheduled and has not fired yet; the fields are those of its `retry_scheduled` event. */
internal data class RetryingRow(
    val identifier: String,
    /** The attempt the retry follows, null when none was recorded. */
    val attempt: AttemptRef?,
    val kind: String?,
    /** The spec retry `attempt`, not the trace number. */
    val retry: String?,
    val dueAt: String?,
    val error: String?,
)

internal data class ParkedRow(
    val identifier: String,
    /** The attempt that parked the issue, else its latest attempt, null when none was recorded. */
    val attempt: AttemptRef?,
    val reason: String,
    val state: String,
    val parkedAt: Instant,
)

/** An issue Loop is waiting on (m2-04), from its record in the store. */
internal data class WaitingRow(
    val identifier: String,
    /** The attempt that started the wait, else the issue's latest attempt, null when none was recorded. */
    val attempt: AttemptRef?,
    val reason: String,
    val since: Instant,
)

/** The event kinds [runtimeSnapshot] reads. */
internal val SNAPSHOT_KINDS: Set<String> =
    setOf(
        AttemptStarted.name,
        AttemptFinished.name,
        TurnStarted.name,
        Usage.name,
        ContextUsage.name,
        RetryScheduled.name,
        RetryDropped.name,
        IssueParked.name,
        RunnerHoldStarted.name,
        RunnerHoldLifted.name,
    )

/**
 * The snapshot of [events] (any kinds, every run of the project), the [parked] and the [waiting] records. Running and
 * retrying only come from [liveRun]'s events: the retry queue lives in the run's memory and dies with it, and
 * an attempt a dead run left open is not running. Null [liveRun] means no run is in progress. Per issue, in seq
 * order: a started attempt without its `attempt_finished` is running; a `retry_scheduled` is pending until a
 * later `attempt_started`, `retry_dropped` or `issue_parked`; running wins over a pending retry, because a
 * dispatched retry keeps its entry until the attempt ends. A waiting issue is listed as waiting only, whatever its
 * events: the attempt whose checks run is still open, and the wait outlives the run that started it. The runner hold
 * (bl-39) is the live run's latest `runner_hold_started` when no `runner_hold_lifted` follows it.
 */
internal fun runtimeSnapshot(
    events: List<StoredEvent>,
    liveRun: RunId?,
    parked: List<ParkedIssue>,
    waiting: List<Wait> = emptyList(),
): RuntimeSnapshot {
    val ordered = events.sortedBy { it.seq }.map { it.event }
    val numbers = attemptNumbers(ordered)
    val running = mutableListOf<RunningRow>()
    val retrying = mutableListOf<RetryingRow>()
    val waitingIds = waiting.map { it.issueId }.toSet()
    if (liveRun != null) {
        ordered
            .filter { it.runId == liveRun && it.issueId != null && it.issueId !in waitingIds }
            .groupBy { it.issueId }
            .forEach { (_, issueEvents) ->
                val open = openAttempt(issueEvents)
                if (open != null) {
                    val ref = numbers[open.attemptId] ?: return@forEach
                    val ofAttempt = issueEvents.filter { it.attemptId == open.attemptId }
                    val turns = ofAttempt.count { it.kind == TurnStarted.name }
                    val tokens = ofAttempt.lastOrNull { it.kind == Usage.name }?.data?.get(Usage.ATTEMPT_TOKENS)
                    running +=
                        RunningRow(
                            identifier = open.issueIdentifier ?: open.issueId.orEmpty(),
                            attempt = ref,
                            startedAt = open.ts,
                            turns = turns,
                            tokens = tokens,
                            occupancy = runningSession(ofAttempt)?.takeIf { it.latest != null },
                            declaresOccupancy = open.data[AttemptStarted.OCCUPANCY]?.toBooleanStrictOrNull(),
                        )
                    return@forEach
                }
                pendingRetry(issueEvents)?.let { retry ->
                    val latest = ordered.lastOrNull { it.kind == AttemptStarted.name && it.issueId == retry.issueId }
                    retrying +=
                        RetryingRow(
                            identifier = retry.issueIdentifier ?: retry.issueId.orEmpty(),
                            attempt = latest?.attemptId?.let(numbers::get),
                            kind = retry.data["kind"],
                            retry = retry.data["attempt"],
                            dueAt = retry.data["due_at"],
                            error = retry.data["error"],
                        )
                }
            }
    }
    val waitingRows =
        waiting
            .sortedWith(compareBy({ it.since }, { it.issueId }))
            .map { record ->
                val latest = ordered.lastOrNull { it.kind == AttemptStarted.name && it.issueId == record.issueId }
                val attempt = (record.attemptId ?: latest?.attemptId)?.let(numbers::get)
                WaitingRow(record.identifier, attempt, record.reason.wire, record.since)
            }
    val parkedRows =
        parked
            .sortedWith(compareBy({ it.parkedAt }, { it.issueId }))
            .map { record ->
                val parkedBy = ordered.lastOrNull { it.kind == IssueParked.name && it.issueId == record.issueId && it.attemptId != null }
                val latest = ordered.lastOrNull { it.kind == AttemptStarted.name && it.issueId == record.issueId }
                val attempt = (parkedBy?.attemptId ?: latest?.attemptId)?.let(numbers::get)
                ParkedRow(record.identifier, attempt, record.reason.wire, record.state, record.parkedAt)
            }
    return RuntimeSnapshot(
        running.sortedBy { it.startedAt },
        retrying.sortedBy { it.dueAt.orEmpty() },
        parkedRows,
        waitingRows,
        liveRun?.let { holdOf(ordered, it) },
    )
}

/** The hold of [liveRun]: its latest hold event when it started a hold, null when it lifted one or there is none. */
private fun holdOf(
    ordered: List<RecordedEvent>,
    liveRun: RunId,
): HoldRow? {
    val latest = ordered.lastOrNull { it.runId == liveRun && (it.kind == RunnerHoldStarted.name || it.kind == RunnerHoldLifted.name) }
    if (latest?.kind != RunnerHoldStarted.name) return null
    val data = latest.data
    return HoldRow(data[RunnerHoldStarted.HOLD], data[RunnerHoldStarted.PROBE_AT], data[RunnerHoldStarted.REASON])
}

/** Each attempt id with its number among its issue's attempts, counted in `attempt_started` order over every run. */
private fun attemptNumbers(ordered: List<RecordedEvent>): Map<AttemptId, AttemptRef> {
    val counts = mutableMapOf<String?, Int>()
    return ordered
        .filter { it.kind == AttemptStarted.name && it.attemptId != null }
        .associate { event ->
            val id = checkNotNull(event.attemptId)
            val number = counts.merge(event.issueId, 1, Int::plus) ?: 1
            id to AttemptRef(number, id)
        }
}

/** The `attempt_started` of the issue's latest attempt when no `attempt_finished` followed it, else null. */
private fun openAttempt(issueEvents: List<RecordedEvent>): RecordedEvent? {
    val started = issueEvents.lastOrNull { it.kind == AttemptStarted.name && it.attemptId != null } ?: return null
    val finished = issueEvents.any { it.kind == AttemptFinished.name && it.attemptId == started.attemptId }
    return if (finished) null else started
}

/** The latest `retry_scheduled` of the issue when nothing after it fired, dropped or parked it, else null. */
private fun pendingRetry(issueEvents: List<RecordedEvent>): RecordedEvent? {
    val index = issueEvents.indexOfLast { it.kind == RetryScheduled.name }
    if (index < 0) return null
    val ended = issueEvents.drop(index + 1).any { it.kind in RETRY_ENDS }
    return if (ended) null else issueEvents[index]
}

/** What ends a pending retry: it fired and dispatched, it was dropped, or the issue was parked. */
private val RETRY_ENDS = setOf(AttemptStarted.name, RetryDropped.name, IssueParked.name)
