package loop.core.orchestrator

import loop.api.RecordedEvent
import loop.core.events.TokenThreshold

/**
 * The token thresholds of one attempt (m1-38): a step every [every] tokens of the attempt's total,
 * 10M, 20M, 30M and so on for the default. A step is given once: the highest one given is kept, so a
 * total that drops (a real report below an earlier estimate) and climbs again gives nothing new.
 * [recorded] is the highest step the attempt already recorded, for example before a restart.
 * An [every] of 0 or less turns thresholds off. Thresholds only report, they never stop anything.
 */
internal class TokenThresholds(
    val every: Long,
    recorded: Long = 0,
) {
    /** The highest step given so far, or recorded before. */
    private var highest: Long = recorded.coerceAtLeast(0)

    /**
     * The steps [total] crossed that were not given yet, in order, at most [MAX_STEPS_PER_REPORT] of them:
     * a bogus total or a tiny [every] cannot loop for ever, the rest come with the next report.
     */
    fun crossed(total: Long): List<Long> {
        if (every <= 0) return emptyList()
        val steps = mutableListOf<Long>()
        var next = highest / every + 1
        while (steps.size < MAX_STEPS_PER_REPORT && next <= total / every) {
            steps += next * every
            next++
        }
        steps.lastOrNull()?.let { highest = it }
        return steps
    }

    companion object {
        const val MAX_STEPS_PER_REPORT = 100L
    }
}

/** The highest `threshold` among the `token_threshold` events in [events], 0 when there is none. */
fun highestRecordedThreshold(events: List<RecordedEvent>): Long =
    events
        .filter { it.kind == TokenThreshold.name }
        .mapNotNull { it.data[TokenThreshold.THRESHOLD]?.toLongOrNull() }
        .maxOrNull() ?: 0L
