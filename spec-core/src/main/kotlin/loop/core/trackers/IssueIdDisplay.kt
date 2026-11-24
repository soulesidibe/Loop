package loop.core.trackers

/**
 * How commands and agent tools read issue ids, and how ids are shown (m4-47, story 112). The stored form always
 * carries the tracker id (m4-12); what a person types or reads depends on the project:
 *
 * - **Reading.** A bare id typed in a command means the [primary] tracker. A namespaced id names its tracker, and one
 *   whose tracker is not in [trackerIds] is refused with [UnknownTrackerId], see [typed].
 * - **Showing.** A project with no extra tracker, so no `trackers:`, shows bare ids everywhere and reads as before
 *   m4-12. A project with extra trackers shows namespaced ids, the primary's included, so the tracker of each issue is
 *   always named: [namespaced] is true. An id of a tracker that is not the primary stays namespaced in any project.
 */
class IssueIdDisplay(
    val primary: String,
    val trackerIds: List<String>,
    /** True when the project lists extra trackers in `trackers:`, so ids are shown namespaced. */
    val namespaced: Boolean,
) {
    /**
     * The issue [text] names, as typed in a command or written by an agent tool: a bare id is the [primary] tracker's,
     * a namespaced id its tracker's. Fails with [UnknownTrackerId] when that tracker is not in [trackerIds].
     */
    fun typed(text: String): Result<NamespacedId> {
        val id = NamespacedId.read(text, primary)
        if (id.tracker !in trackerIds) return Result.failure(UnknownTrackerId(id.tracker, trackerIds))
        return Result.success(id)
    }

    /**
     * The issue of an agent tool, from `LOOP_TRACKER_ID` [trackerId] and `LOOP_ISSUE_ID` [issueId], the native id. With
     * no tracker id, an attempt started before m4-12, [issueId] is read like a typed id, so a bare one is the
     * [primary] tracker's. Fails with [UnknownTrackerId] when the tracker is not in [trackerIds].
     */
    fun ofAgent(
        trackerId: String?,
        issueId: String,
    ): Result<NamespacedId> {
        if (trackerId == null) return typed(issueId)
        if (trackerId !in trackerIds) return Result.failure(UnknownTrackerId(trackerId, trackerIds))
        return Result.success(NamespacedId(trackerId, issueId))
    }

    /** True when the core id [issueId], namespaced or a bare id of the [primary] tracker, is the issue [id]. */
    fun isIssue(
        issueId: String,
        id: NamespacedId,
    ): Boolean = NamespacedId.read(issueId, primary) == id

    /** The core id [issueId], namespaced or a bare id of a row written before m4-12, as it is shown. */
    fun id(issueId: String): String {
        val id = NamespacedId.read(issueId, primary)
        return if (bare(id)) id.native else id.value
    }

    /**
     * The [identifier] of the issue [issueId] as it is shown where Loop shows identifiers: as is in a project with no
     * extra tracker, `<tracker id>:<identifier>` otherwise. With no [issueId], the identifier as is.
     */
    fun name(
        issueId: String?,
        identifier: String,
    ): String {
        val id = issueId?.let { NamespacedId.read(it, primary) } ?: return identifier
        return if (bare(id)) identifier else NamespacedId(id.tracker, identifier).value
    }

    /**
     * True when the record of the issue [issueId] with [identifier] is the [typed] issue: the same tracker, and the
     * typed native id is its native id or its identifier. For a lookup among stored records, without the tracker.
     */
    fun matches(
        typed: NamespacedId,
        issueId: String,
        identifier: String?,
    ): Boolean {
        val id = NamespacedId.read(issueId, primary)
        return id.tracker == typed.tracker && (id.native == typed.native || identifier == typed.native)
    }

    private fun bare(id: NamespacedId): Boolean = !namespaced && id.tracker == primary

    companion object {
        /** The display of the trackers of [config]. */
        fun of(config: TrackersConfig): IssueIdDisplay = IssueIdDisplay(config.primary.id, config.ids, config.extras.isNotEmpty())
    }
}

/** A namespaced issue id whose [tracker] is not one of the project's [known] trackers (m4-47). */
class UnknownTrackerId(
    val tracker: String,
    val known: List<String>,
) : IllegalArgumentException(
        "no tracker \"$tracker\" in this project, " +
            if (known.size == 1) "its tracker is ${known.single()}" else "its trackers are ${listed(known)}",
    ) {
    private companion object {
        fun listed(ids: List<String>): String = ids.dropLast(1).joinToString(", ") + " and " + ids.last()
    }
}
