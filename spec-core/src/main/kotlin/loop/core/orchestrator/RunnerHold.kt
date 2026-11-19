package loop.core.orchestrator

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The run level hold of a runner that cannot reach its model (bl-39): a `turn_failed` with the category
 * `runner_unavailable` says nothing about its issue, so it takes no `max_attempts` slot and stops every dispatch of the
 * run instead. The hold lasts until a turn succeeds. It is made of periods: the first lasts [FIRST], each next one twice
 * the last up to [MAX], or the runtime's `retryAfter` when longer, and it never gives up. Before a period's probe time
 * nothing is dispatched; at the probe time one attempt, the next due retry, is the probe, and nothing else is
 * dispatched while it runs. A probe that fails the same way starts the next period; one that ends any other way
 * without a successful turn makes the next probe due at once. Attempts already running are left alone: one that fails
 * before the probe time falls into the current period, one that fails after it starts the next.
 *
 * An issue that hits it three times in a row while another issue had a successful turn is the problem itself: from
 * that third hit on, its failures are [Outcome.Ordinary] until it ends any other way.
 *
 * Kept in memory only, a restart forgets it. Not thread safe: the orchestrator calls it from its single threaded
 * dispatcher only.
 */
internal class RunnerHold(
    private val clock: Clock,
) {
    /** What the hold lets the dispatch do now. */
    enum class Gate {
        /** No hold: dispatch as usual. */
        Open,

        /** Held: dispatch nothing, no retry fires. */
        Closed,

        /** The probe is due: dispatch exactly one attempt, the next due retry. */
        Probe,
    }

    /** One period of the hold: its [number] since the last lift, why, how long and when its probe is due. */
    data class Period(
        val number: Int,
        val reason: String,
        val delay: Duration,
        val probeAt: Instant,
        val retryAfter: Duration?,
    )

    /** A hold a successful turn lifted, after [holds] periods and [held] since it started. */
    data class Lifted(
        val holds: Int,
        val held: Duration,
    )

    /** What a `runner_unavailable` failure leads to. */
    sealed interface Outcome {
        /** The issue hit it three times in a row while others succeeded: count it as an ordinary failure. */
        data object Ordinary : Outcome

        /** Held: requeue the issue at [dueAt], the probe time; [started] is set when this failure began a period. */
        data class Held(
            val dueAt: Instant,
            val started: Period?,
        ) : Outcome
    }

    /** One issue's `runner_unavailable` hits in a row, and whether another issue had a successful turn meanwhile. */
    private class Streak {
        var hits = 0
        var othersSucceeded = false
    }

    /** When the probe of the current period is due, null without a hold. */
    var probeAt: Instant? = null
        private set

    /** The periods since the hold started, zero without a hold. */
    var holds: Int = 0
        private set

    private var heldSince: Instant? = null

    /** The issue whose attempt is the running probe. */
    private var probing: String? = null

    private val streaks = mutableMapOf<String, Streak>()

    fun gate(): Gate {
        val due = probeAt ?: return Gate.Open
        return if (probing != null || clock.now() < due) Gate.Closed else Gate.Probe
    }

    /** The attempt of [issueId] was dispatched as the probe. */
    fun probeStarted(issueId: String) {
        probing = issueId
    }

    /**
     * A turn of [issueId] failed with `runner_unavailable` for [reason], the runtime asking to wait [retryAfter]. Call it
     * before [onAttemptEnded] for the same attempt.
     */
    fun onUnavailable(
        issueId: String,
        reason: String,
        retryAfter: Duration?,
    ): Outcome {
        val streak = streaks.getOrPut(issueId, ::Streak)
        streak.hits++
        if (streak.hits >= ORDINARY_AFTER && streak.othersSucceeded) return Outcome.Ordinary
        val now = clock.now()
        val due = probeAt
        val wasProbe = probing == issueId
        if (wasProbe) probing = null
        // A running attempt that fails before the probe time adds nothing: the period already holds.
        if (due != null && !wasProbe && now < due) return Outcome.Held(due, started = null)
        holds++
        if (heldSince == null) heldSince = now
        val backoff = backoff(holds)
        val delay = if (retryAfter != null && retryAfter > backoff) retryAfter else backoff
        val period = Period(holds, reason, delay, now + delay, retryAfter)
        probeAt = period.probeAt
        return Outcome.Held(period.probeAt, period)
    }

    /** A turn of [issueId] succeeded: the hold lifts and its backoff resets. Null when nothing was held. */
    fun onTurnSucceeded(issueId: String): Lifted? {
        streaks -= issueId
        streaks.values.forEach { it.othersSucceeded = true }
        val since = heldSince ?: return null
        val lifted = Lifted(holds, clock.now() - since)
        probeAt = null
        holds = 0
        heldSince = null
        probing = null
        return lifted
    }

    /** The attempt of [issueId] ended; [unavailable] is true when its last turn failed with `runner_unavailable`. */
    fun onAttemptEnded(
        issueId: String,
        unavailable: Boolean,
    ) {
        if (!unavailable) streaks -= issueId
        if (probing != issueId) return
        // The probe ended without a verdict on the runner: the next due retry probes at once.
        probing = null
        if (probeAt != null) probeAt = clock.now()
    }

    companion object {
        val FIRST: Duration = 1.minutes
        val MAX: Duration = 15.minutes

        /** The hit of a streak from which, with another issue succeeding meanwhile, it counts as an ordinary failure. */
        const val ORDINARY_AFTER = 3

        /** Period [number]'s delay: [FIRST] doubled for each period after the first, at most [MAX]. */
        fun backoff(number: Int): Duration {
            var delay = FIRST
            repeat((number - 1).coerceAtLeast(0)) {
                delay *= 2
                if (delay >= MAX) return MAX
            }
            return delay
        }
    }
}
