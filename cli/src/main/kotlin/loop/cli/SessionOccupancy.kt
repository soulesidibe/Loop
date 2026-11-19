package loop.cli

import loop.api.RecordedEvent
import loop.core.events.ContextUsage
import loop.core.events.TurnEvent
import loop.core.events.TurnStarted
import java.util.Locale

/**
 * The context occupancy of one agent session of an attempt (plan 8.3, m3-09), as the store's `context_usage` events
 * give it. A session is the agent thread, [thread]: the spec's session id changes with every turn, so the events of a
 * session are found through the `turn_started` events of its thread. [latest] and [peak] are null when the session has
 * no report, as under a runner that does not declare occupancy, never zero; [window] is null when `context.windows`
 * did not know the model.
 */
internal data class SessionOccupancy(
    val thread: String,
    val latest: Long?,
    val peak: Long?,
    val window: Long?,
) {
    /** [latest] over [window] with 4 decimals, null without both. */
    val fraction: String? get() = fractionOf(latest)

    /** [peak] over [window] with 4 decimals, null without both. */
    val peakFraction: String? get() = fractionOf(peak)

    private fun fractionOf(tokens: Long?): String? {
        val window = window?.takeIf { it > 0 } ?: return null
        return tokens?.let { String.format(Locale.ROOT, "%.4f", it.toDouble() / window) }
    }
}

/**
 * Every session of the attempt whose events are [ofAttempt], in the order its thread first started a turn, each with
 * the latest `context_usage` of its turns. A `turn_started` without a `thread_id` counts in no session.
 */
internal fun sessionsOf(ofAttempt: List<RecordedEvent>): List<SessionOccupancy> {
    val starts = ofAttempt.filter { it.kind == TurnStarted.name && it.data[TurnEvent.THREAD_ID] != null }
    val threadOf = starts.filter { it.sessionId != null }.associate { it.sessionId to it.data.getValue(TurnEvent.THREAD_ID) }
    val usages = ofAttempt.filter { it.kind == ContextUsage.name }
    return starts.map { it.data.getValue(TurnEvent.THREAD_ID) }.distinct().map { thread ->
        val data = usages.lastOrNull { threadOf[it.sessionId] == thread }?.data
        SessionOccupancy(
            thread = thread,
            latest = data?.get(ContextUsage.LATEST_TOKENS)?.toLongOrNull(),
            peak = data?.get(ContextUsage.PEAK_TOKENS)?.toLongOrNull(),
            window = data?.get(ContextUsage.WINDOW)?.toLongOrNull(),
        )
    }
}

/** The session of the attempt's latest turn, null before its first turn. */
internal fun runningSession(ofAttempt: List<RecordedEvent>): SessionOccupancy? {
    val thread = ofAttempt.lastOrNull { it.kind == TurnStarted.name }?.data?.get(TurnEvent.THREAD_ID) ?: return null
    return sessionsOf(ofAttempt).firstOrNull { it.thread == thread }
}
