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

/**
 * m4-02 (bl-125): the first `context_usage` report of a [MODEL] with no window in `context.windows` in the attempt, or
 * of a runner that named no model, in Loop mode with a runner that declares occupancy. The session's occupancy is then
 * recorded in tokens without a fraction, and no soft or hard limit holds for it. Once per attempt and model, at the
 * report, so `loop trace` and `loop show` say why the fraction is missing.
 */
object ContextWindowUnknown : EventDefinition("context_window_unknown") {
    /** The `provider/model` the runner named, absent when it named none. */
    const val MODEL = ContextUsage.MODEL

    /** The runner kind, `runner.kind` of the workflow. */
    const val RUNNER = "runner"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(MODEL to "openai/gpt-6.1-sol", RUNNER to "codex-app-server")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { listOf(MODEL, RUNNER).forEach { add(it, event.data[it]) } }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val model = data[MODEL]?.let { "runs $it, which has no window in context.windows" } ?: "runs a model its runner did not name"
        return "$model, so no context limit holds for its session"
    }
}
