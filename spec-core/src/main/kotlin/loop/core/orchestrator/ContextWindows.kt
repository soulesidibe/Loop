package loop.core.orchestrator

import loop.core.events.ContextWindowUnknown

/**
 * The context windows of one attempt (m4-02, bl-125): the window `context.windows` gives a session's model, and the
 * models of the attempt's reports that have none. Such a session reports its occupancy in tokens without a fraction, so
 * no soft or hard limit holds for it. The first report of each such model in the attempt is [Unknown], logged as a
 * warning and, when [records] is true, recorded as `context_window_unknown`. A new attempt starts a new instance, so
 * every attempt says it again, unlike the once per process warning of m3-08.
 */
internal class ContextWindows(
    /** `context.windows`: the windows Loop ships with the workflow's entries over them. */
    private val windows: Map<String, Long>,
    /** The runner kind of the workflow, `runner.kind`. */
    private val runner: String,
    /** Whether an unknown window is recorded as an event: in Loop mode with a runner that declares occupancy. */
    private val records: Boolean,
) {
    /** The models of this attempt already found with no window; an empty string stands for a runner that named none. */
    private val unknown = mutableSetOf<String>()

    /** The window of [model] in tokens, null when the runner named no model or `context.windows` has no entry for it. */
    fun windowOf(model: String?): Long? = model?.let { windows[it] }?.takeIf { it > 0 }

    /** The first report of [model] in this attempt when it has no window, null for a model with a window or seen before. */
    fun firstUnknown(model: String?): Unknown? {
        if (windowOf(model) != null) return null
        if (!unknown.add(model.orEmpty())) return null
        return Unknown(model, runner, records)
    }

    /** A [model] with no window, null when the [runner] named none; [recorded] when it becomes an event. */
    class Unknown(
        val model: String?,
        val runner: String,
        val recorded: Boolean,
    ) {
        /** The data of the `context_window_unknown` event. */
        val data: Map<String, String>
            get() =
                buildMap {
                    model?.let { put(ContextWindowUnknown.MODEL, it) }
                    put(ContextWindowUnknown.RUNNER, runner)
                }

        /** The reason of the operator log warning, naming the model and the key to add. */
        val reason: String
            get() =
                if (model == null) {
                    "the runner named no model, so context occupancy is reported in tokens without a fraction"
                } else {
                    "no window for $model in context.windows, so context occupancy is reported in tokens without a fraction. " +
                        "Add $model under context.windows"
                }
    }
}
