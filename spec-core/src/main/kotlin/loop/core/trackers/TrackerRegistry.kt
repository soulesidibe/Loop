package loop.core.trackers

import loop.api.TrackerAdapter

/**
 * The tracker registry of a project (m4-11, PLAN 6.1): each tracker id of [config] with its adapter, states and
 * `required_labels`. Each adapter is handed out as a [NamespacedTracker], so its issues reach the core with namespaced
 * ids (m4-12), and `loop run` polls them all through a [TrackerPoll] (m4-13).
 */
class TrackerRegistry private constructor(
    val config: TrackersConfig,
    private val adapters: Map<String, NamespacedTracker>,
) {
    val primary: TrackerEntry get() = config.primary

    val primaryAdapter: NamespacedTracker get() = adapters.getValue(primary.id)

    /** The tracker of [id], or null when no tracker has it. */
    fun entry(id: String): TrackerEntry? = config.entry(id)

    /** The adapter of the tracker [id], or null when no tracker has it. */
    fun adapter(id: String): NamespacedTracker? = adapters[id]

    companion object {
        /** The registry of [config], whose native adapters [create] builds, one per tracker, the primary first. */
        fun build(
            config: TrackersConfig,
            create: (TrackerEntry) -> TrackerAdapter,
        ): TrackerRegistry =
            TrackerRegistry(config, config.all.associate { it.id to NamespacedTracker(it.id, config.primary.id, create(it)) })
    }
}
