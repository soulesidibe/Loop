package loop.core.orchestrator

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Why a retry is scheduled: the previous attempt failed, or it exited normally (spec 16.6). */
enum class RetryKind(
    val wire: String,
) {
    Error("error"),
    Continuation("continuation"),
}

/**
 * A scheduled retry (spec 4.1.7): the spec [attempt], when it is due, and the last error,
 * null for a continuation retry after a normal exit. [firstDueAt] is when the retry first fell
 * due: a slot requeue moves [dueAt] and keeps it, so the retry stays due and keeps its place among
 * due retries, and [dueAt] then only says when to wake the daemon (bl-34). It lives in memory only,
 * a restart takes the stored [dueAt].
 *
 * Two more facts of a retry that waits for a slot (m4-15, bl-77) live in memory only, kept by a requeue and gone with
 * any other schedule: [waitingFor], the error of the last requeue recorded, so a requeue for the same reason records
 * nothing, and [refreshedAt], when its last by id refresh read its issue, so it is refreshed at most once per poll.
 */
data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val attempt: Int,
    val dueAt: Instant,
    val error: String?,
    val firstDueAt: Instant = dueAt,
    val waitingFor: String? = null,
    val refreshedAt: Instant? = null,
) {
    val kind: RetryKind get() = if (error == null) RetryKind.Continuation else RetryKind.Error
}

/**
 * What the queue keeps for one issue (m2-02), as the store holds it: its [entry] when one is queued
 * and both counters. [identifier] is the last one the queue saw for the issue.
 */
data class RetrySnapshot(
    val issueId: String,
    val identifier: String,
    val entry: RetryEntry?,
    val failures: Int,
    val noProgress: Int,
)

/** What a failed attempt leads to. */
sealed interface FailureDecision {
    /** The issue retries at [RetryEntry.dueAt]; [failures] is the consecutive failure count. */
    data class Retry(
        val entry: RetryEntry,
        val delayMs: Long,
        val failures: Int,
    ) : FailureDecision

    /**
     * The issue reached the attempt cap. Its entry is dropped and its counters zeroed,
     * the no progress count included.
     */
    data class Park(
        val failures: Int,
        val error: String,
    ) : FailureDecision
}

/** What a normal exit leads to; [noProgress] is the consecutive no progress count. */
sealed interface ContinuationDecision {
    val noProgress: Int

    /** The issue gets its continuation retry. [noProgress] is zero after progress. */
    data class Continue(
        override val noProgress: Int,
    ) : ContinuationDecision

    /** The issue reached `retries.max_continuations`. Its entry is dropped and its counters zeroed. */
    data class Park(
        override val noProgress: Int,
    ) : ContinuationDecision
}

/**
 * Retry bookkeeping of one process, keyed by issue id (spec decisions
 * answer 5). Three counters per issue: the spec attempt, which drives the backoff
 * and the `attempt` template variable; Loop's consecutive failure counter, which
 * parks at `retries.max_attempts`; and Loop's consecutive no progress counter, the
 * normal exits that changed neither the issue state nor its labels, which parks at
 * `retries.max_continuations`. Every reset path zeroes the failure and no progress
 * counters together, except a normal exit, which resets only the failure counter.
 * The caps are passed on every call, never cached, since the workflow can be reloaded.
 * It lives in memory; the caller writes each issue's [snapshot] to the store after a change
 * and [restore]s them after a restart (m2-02). Not thread safe: the orchestrator calls it from its single threaded dispatcher only.
 */
class RetryQueue {
    private val entries = mutableMapOf<String, RetryEntry>()

    /** The spec attempt of the last scheduled retry, kept after its entry fires. */
    private val attempts = mutableMapOf<String, Int>()

    private val failureCounts = mutableMapOf<String, Int>()

    private val noProgressCounts = mutableMapOf<String, Int>()

    /** The last identifier seen for each issue, for [snapshot]. */
    private val identifiers = mutableMapOf<String, String>()

    /**
     * Records a failed attempt: increments the spec attempt and the failure counter, then returns [FailureDecision.Park]
     * when the failures reach [maxAttempts], else a [FailureDecision.Retry] due after the backoff.
     */
    fun onFailure(
        issueId: String,
        identifier: String,
        error: String,
        now: Instant,
        maxAttempts: Int,
        maxBackoffMs: Long,
    ): FailureDecision {
        identifiers[issueId] = identifier
        val failures = failures(issueId) + 1
        if (failures >= maxAttempts) {
            clear(issueId)
            return FailureDecision.Park(failures, error)
        }
        failureCounts[issueId] = failures
        val attempt = (attempts[issueId] ?: 0) + 1
        val delayMs = backoffMs(attempt, maxBackoffMs)
        val entry = schedule(issueId, identifier, attempt, now, delayMs, error)
        return FailureDecision.Retry(entry, delayMs, failures)
    }

    /**
     * bl-39: a `runner_unavailable` failure while the run is held. Schedules the next spec attempt at [dueAt], the hold's
     * probe time (at once when it is past), leaving the failure counter as is: such a failure takes no `max_attempts` slot.
     */
    fun onUnavailable(
        issueId: String,
        identifier: String,
        error: String,
        now: Instant,
        dueAt: Instant,
    ): RetryEntry {
        val attempt = (attempts[issueId] ?: 0) + 1
        return schedule(issueId, identifier, attempt, now, (dueAt - now).inWholeMilliseconds.coerceAtLeast(0), error)
    }

    /**
     * Records a normal exit: drops the entry and the spec attempt and zeroes the failure counter,
     * then zeroes the no progress count when [progressed], else increments it. Returns
     * [ContinuationDecision.Park] when the count reaches [maxContinuations], with every counter
     * zeroed, else [ContinuationDecision.Continue]. The caller schedules the continuation.
     */
    fun onNormalExit(
        issueId: String,
        progressed: Boolean,
        maxContinuations: Int,
    ): ContinuationDecision {
        onUncountedNormalExit(issueId)
        if (progressed) {
            noProgressCounts -= issueId
            return ContinuationDecision.Continue(noProgress = 0)
        }
        val noProgress = noProgress(issueId) + 1
        if (noProgress >= maxContinuations) {
            clear(issueId)
            return ContinuationDecision.Park(noProgress)
        }
        noProgressCounts[issueId] = noProgress
        return ContinuationDecision.Continue(noProgress)
    }

    /**
     * A normal exit that does not count (its issue was not found): drops the entry and the spec
     * attempt and zeroes the failure counter, leaving the no progress count, which it returns.
     */
    fun onUncountedNormalExit(issueId: String): Int {
        // Not through clear: that path also zeroes the no progress count.
        entries -= issueId
        attempts -= issueId
        failureCounts -= issueId
        return noProgress(issueId)
    }

    /** The scheduled retry of [issueId], null when none is queued. */
    fun entry(issueId: String): RetryEntry? = entries[issueId]

    /** The issues with an entry, by id, each with its identifier. */
    fun queued(): Map<String, String> = entries.mapValues { it.value.identifier }

    /** What the queue keeps for [issueId], null when it keeps nothing: no entry and both counters at zero. */
    fun snapshot(issueId: String): RetrySnapshot? {
        val entry = entries[issueId]
        val failures = failures(issueId)
        val noProgress = noProgress(issueId)
        if (entry == null && failures == 0 && noProgress == 0) return null
        val identifier = entry?.identifier ?: identifiers[issueId] ?: issueId
        return RetrySnapshot(issueId, identifier, entry, failures, noProgress)
    }

    /** Puts back what [snapshot] gave before a restart, replacing whatever the queue keeps for that issue. */
    fun restore(snapshot: RetrySnapshot) {
        val issueId = snapshot.issueId
        identifiers[issueId] = snapshot.identifier
        val entry = snapshot.entry
        if (entry == null) {
            entries -= issueId
            attempts -= issueId
        } else {
            entries[issueId] = entry
            attempts[issueId] = entry.attempt
        }
        if (snapshot.failures > 0) failureCounts[issueId] = snapshot.failures else failureCounts -= issueId
        if (snapshot.noProgress > 0) noProgressCounts[issueId] = snapshot.noProgress else noProgressCounts -= issueId
    }

    /**
     * Schedules the continuation retry after a normal exit (spec 16.6): attempt 1 due after
     * [CONTINUATION_DELAY_MS], no error. A later failure of that attempt gets attempt 2.
     */
    fun scheduleContinuation(
        issueId: String,
        identifier: String,
        now: Instant,
    ): RetryEntry = schedule(issueId, identifier, attempt = 1, now, CONTINUATION_DELAY_MS, error = null)

    /**
     * The by-id refresh of a due retry failed: reschedules with the spec attempt plus one
     * and error `retry refresh failed`, leaving the failure counter as is. Null without an entry.
     */
    fun onRefreshFailed(
        issueId: String,
        now: Instant,
        maxBackoffMs: Long,
    ): RetryEntry? = reschedule(issueId, now, maxBackoffMs, REFRESH_FAILED)

    /**
     * Reschedules the entry of [issueId] with the spec attempt plus one, its backoff and [error],
     * leaving the failure counter as is (a refresh failure: spec 16.6).
     * Null without an entry.
     */
    fun reschedule(
        issueId: String,
        now: Instant,
        maxBackoffMs: Long,
        error: String,
    ): RetryEntry? {
        val current = entries[issueId] ?: return null
        val attempt = current.attempt + 1
        return schedule(issueId, current.identifier, attempt, now, backoffMs(attempt, maxBackoffMs), error)
    }

    /**
     * A due retry found no free slot (bl-34): moves the entry of [issueId] to [delayMs] from [now] with [error],
     * keeping its spec attempt, its first due time, its last refresh and the failure counter, and waiting for [error]
     * (m4-15). Null without an entry.
     */
    fun requeue(
        issueId: String,
        now: Instant,
        delayMs: Long,
        error: String,
    ): RetryEntry? {
        val current = entries[issueId] ?: return null
        val entry = current.copy(dueAt = now + delayMs.milliseconds, error = error, waitingFor = error)
        entries[issueId] = entry
        return entry
    }

    /** m4-15: the by id refresh of the due retry of [issueId] read its issue at [at]. Nothing without an entry. */
    fun refreshed(
        issueId: String,
        at: Instant,
    ) {
        entries[issueId]?.let { entries[issueId] = it.copy(refreshedAt = at) }
    }

    /**
     * Entries due at [now], first due earliest first. They stay queued until replaced or dropped. An entry is due from
     * its [RetryEntry.firstDueAt]: a slot requeue moves [RetryEntry.dueAt] only to wake the daemon, so a tick that comes
     * earlier still sees the waiting retry and gives it the free slot first (bl-34).
     */
    fun due(now: Instant): List<RetryEntry> = entries.values.filter { it.firstDueAt <= now }.sortedBy { it.firstDueAt }

    /** True when [issueId] has an entry that is not due yet at [now], by its first due time as in [due]. */
    fun isWaiting(
        issueId: String,
        now: Instant,
    ): Boolean = entries[issueId]?.let { it.firstDueAt > now } ?: false

    /**
     * Drops the entry and the spec attempt; [resetCounter] also zeroes the failure counter
     * and the no progress count.
     */
    fun drop(
        issueId: String,
        resetCounter: Boolean,
    ) {
        entries -= issueId
        attempts -= issueId
        if (resetCounter) {
            failureCounts -= issueId
            noProgressCounts -= issueId
            identifiers -= issueId
        }
    }

    /**
     * Zeroes every counter of every issue that has no entry and is not in [present],
     * the ids fetched by the active states: such an issue left the active states. An issue whose tracker was not
     * [polled] this tick, its poll having failed (m4-13), keeps its counters. Returns the issues it forgot.
     */
    fun forgetAbsent(
        present: Set<String>,
        polled: (String) -> Boolean = { true },
    ): Set<String> {
        val idle =
            (failureCounts.keys + noProgressCounts.keys + attempts.keys)
                .filter { it !in present && it !in entries && polled(it) }
                .toSet()
        failureCounts -= idle
        noProgressCounts -= idle
        attempts -= idle
        identifiers -= idle
        return idle
    }

    /**
     * The earliest due time of the issues not in [skip], null when none is queued. It takes [RetryEntry.dueAt], so a
     * retry requeued for lack of a slot wakes the daemon one poll interval later, not at once.
     */
    fun nextDueAt(skip: Set<String> = emptySet()): Instant? = entries.values.filter { it.issueId !in skip }.minOfOrNull { it.dueAt }

    /** The consecutive failure count of [issueId], zero when unknown. */
    fun failures(issueId: String): Int = failureCounts[issueId] ?: 0

    /** The consecutive no progress count of [issueId], zero when unknown. */
    fun noProgress(issueId: String): Int = noProgressCounts[issueId] ?: 0

    private fun schedule(
        issueId: String,
        identifier: String,
        attempt: Int,
        now: Instant,
        delayMs: Long,
        error: String?,
    ): RetryEntry {
        val entry = RetryEntry(issueId, identifier, attempt, now + delayMs.milliseconds, error)
        entries[issueId] = entry
        attempts[issueId] = attempt
        identifiers[issueId] = identifier
        return entry
    }

    private fun clear(issueId: String) = drop(issueId, resetCounter = true)

    companion object {
        const val BASE_BACKOFF_MS = 10_000L

        /** The fixed delay of the retry after a normal exit (spec 16.6). */
        const val CONTINUATION_DELAY_MS = 1_000L

        /** Above this shift `BASE_BACKOFF_MS shl shift` no longer fits in a Long. */
        private const val MAX_SAFE_SHIFT = 49

        private const val REFRESH_FAILED = "retry refresh failed"

        /** `min(10000 * 2^(attempt - 1), maxBackoffMs)`, safe from overflow for any attempt. */
        fun backoffMs(
            attempt: Int,
            maxBackoffMs: Long,
        ): Long {
            val shift = (attempt - 1).coerceAtLeast(0)
            if (shift > MAX_SAFE_SHIFT) return maxBackoffMs
            return minOf(BASE_BACKOFF_MS shl shift, maxBackoffMs)
        }
    }
}
