package loop.core.trackers

/**
 * The per tracker limit of `agent.max_concurrent_agents_by_tracker` (m4-15, story 115), so one tracker's backlog cannot
 * take every slot. It holds beside the spec's `max_concurrent_agents` and `max_concurrent_agents_by_state`: a launch
 * needs a free slot under all three. Like the per state caps it counts attempts only, never a merge check.
 */
object TrackerSlots {
    /**
     * Whether an attempt of [tracker] fits under its limit in [limits], with [running] the tracker id of each attempt
     * that holds a slot. A tracker with no entry, or an issue of no tracker of the workflow ([tracker] null), has no
     * limit of its own.
     */
    fun hasRoom(
        limits: Map<String, Int>,
        running: List<String>,
        tracker: String?,
    ): Boolean {
        val limit = tracker?.let { limits[it] } ?: return true
        return running.count { it == tracker } < limit
    }

    /** The tracker id of each of the [issueIds] that hold a slot, as [trackers] reads it; an id of no tracker counts nowhere. */
    fun runningTrackers(
        issueIds: Collection<String>,
        trackers: TrackersConfig,
    ): List<String> = issueIds.mapNotNull { trackers.trackerOf(it)?.id }
}
