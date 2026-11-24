package loop.core.trackers

import loop.core.workspace.WorkspaceKey

/**
 * The id of an issue with its tracker (m4-12, story 112): `<tracker id>:<native id>`, such as `gh:454` or
 * `local:research-03`, so the ids of two trackers never collide. The adapter keeps its native ids; [NamespacedTracker]
 * namespaces them where the registry hands issues to the core, and the store, the ledger, events and logs carry this
 * form. [tracker] matches [TrackersConfig.ID_PATTERN], so the first colon always ends it and [native] may hold more.
 */
data class NamespacedId(
    val tracker: String,
    val native: String,
) {
    init {
        require(TrackersConfig.ID_PATTERN.matches(tracker)) { "a tracker id must match ${TrackersConfig.ID_PATTERN.pattern}: $tracker" }
        require(native.isNotEmpty()) { "a native issue id must not be empty" }
    }

    /** The text the core carries, `<tracker>:<native>`. */
    val value: String get() = "$tracker$SEPARATOR$native"

    override fun toString(): String = value

    /**
     * The bare id a store row or a ledger line written before m4-12 holds for this issue: its [native] id when it is
     * the [primary] tracker's issue, null for an extra tracker's, which had none.
     */
    fun bareFor(primary: String): String? = native.takeIf { tracker == primary }

    companion object {
        private const val SEPARATOR = ':'

        /** The namespaced id of [text], or null when it is bare: no colon, or a part before the first one that is no tracker id. */
        fun parse(text: String): NamespacedId? {
            val colon = text.indexOf(SEPARATOR)
            if (colon <= 0 || colon == text.length - 1) return null
            val tracker = text.substring(0, colon)
            if (!TrackersConfig.ID_PATTERN.matches(tracker)) return null
            return NamespacedId(tracker, text.substring(colon + 1))
        }

        /**
         * The reading rule: [text] as a namespaced id, a bare id read as the [primary] tracker's. Store rows and ledger
         * lines written before m4-12 hold bare ids; they are read this way, never rewritten.
         */
        fun read(
            text: String,
            primary: String,
        ): NamespacedId = parse(text) ?: NamespacedId(primary, text)

        /** The native id of the core id [issueId]: what follows the tracker id, or the whole id when it is bare. */
        fun nativeOf(issueId: String): String = parse(issueId)?.native ?: issueId

        /** The tracker id of the core id [issueId], or null when it is bare. */
        fun trackerOf(issueId: String): String? = parse(issueId)?.tracker

        /**
         * The workspace key of the issue [issueId] with the native [identifier]. The [primary] tracker's issues keep
         * `WorkspaceKey.of(identifier)` as before m4-12, so no workspace folder or `loop/<key>` branch in flight is
         * renamed; a bare id is the primary's. An extra tracker's issue gets `WorkspaceKey.of("<tracker id>:<identifier>")`.
         */
        fun workspaceKey(
            issueId: String,
            identifier: String,
            primary: String,
        ): WorkspaceKey {
            val tracker = read(issueId, primary).tracker
            return if (tracker == primary) WorkspaceKey.of(identifier) else WorkspaceKey.of("$tracker$SEPARATOR$identifier")
        }
    }
}
