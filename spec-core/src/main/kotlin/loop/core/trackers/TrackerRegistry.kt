package loop.core.trackers

import loop.api.TrackerAdapter

/**
 * The tracker registry of a project (m4-11, PLAN 6.1): each tracker id of [config] with its adapter, states and
 * `required_labels`. The orchestrator still takes only the [primaryAdapter] until m4-13.
 */
class TrackerRegistry private constructor(
    val config: TrackersConfig,
    private val adapters: Map<String, TrackerAdapter>,
) {
    val primary: TrackerEntry get() = config.primary

    val primaryAdapter: TrackerAdapter get() = adapters.getValue(primary.id)

    /** The tracker of [id], or null when no tracker has it. */
    fun entry(id: String): TrackerEntry? = config.entry(id)

    /** The adapter of the tracker [id], or null when no tracker has it. */
    fun adapter(id: String): TrackerAdapter? = adapters[id]

    companion object {
        /** The registry of [config], whose adapters [create] builds, one per tracker, the primary first. */
        fun build(
            config: TrackersConfig,
            create: (TrackerEntry) -> TrackerAdapter,
        ): TrackerRegistry = TrackerRegistry(config, config.all.associate { it.id to create(it) })
    }
}
