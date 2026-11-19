package loop.core.events

import loop.api.RecordedEvent

/**
 * The context occupancy of the attempt's agent session at the end of a turn (plan 8.3, m3-08): the latest and the peak
 * input size of its main session requests, cached tokens included. [WINDOW] and [FRACTION], the latest over the
 * window, are written only when `context.windows` knows the [MODEL]. Distinct from spend: it is never summed.
 */
object ContextUsage : EventDefinition("context_usage") {
    const val LATEST_TOKENS = "latest_tokens"
    const val PEAK_TOKENS = "peak_tokens"
    const val WINDOW = "window"
    const val FRACTION = "fraction"

    /** The `provider/model` the runner named, absent when it named none. */
    const val MODEL = "model"

    override val trace = TraceShow.TIMELINE
    override val sample =
        mapOf(
            LATEST_TOKENS to "120000",
            PEAK_TOKENS to "300000",
            MODEL to "anthropic/claude-opus-5-5",
            WINDOW to "1000000",
            FRACTION to "0.1200",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { listOf(LATEST_TOKENS, PEAK_TOKENS, WINDOW, FRACTION, MODEL).forEach { add(it, event.data[it]) } }
}
