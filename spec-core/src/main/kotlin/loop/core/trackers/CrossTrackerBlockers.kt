package loop.core.trackers

import loop.api.Issue

/** One item of a blocker line (m4-16): the [tracker] word as written and the [native] id that follows its colon. */
data class BlockerRef(
    val tracker: String,
    val native: String,
) {
    /** The item as written, `<tracker>:<native>`, which is also its namespaced id when [tracker] is a tracker id. */
    val text: String get() = "$tracker:$native"
}

/**
 * The cross tracker blocker lines of an issue description (m4-16, story 116, PLAN 6.1). A blocker line is a column 0
 * line that reads `Blocked by ` followed only by one or more `<word>:<id>` items separated by commas, such as
 * `Blocked by gh:12, local:m4-03`. The phrase is read case insensitively, a period at the end of the line is not part
 * of the last id, and the word is read whatever it is, so that the check can keep an issue blocked by an unknown or
 * misspelled tracker. Any other `Blocked by` line is prose and ignored, as are the lines of fenced code blocks.
 */
object BlockerLines {
    private val WORD = "[A-Za-z][A-Za-z0-9_-]*"
    private val ID = "[^\\s,:][^\\s,]*"
    private val ITEM = Regex("($WORD):($ID)")
    private val LINE = Regex("(?i:Blocked by) +($WORD:$ID(?: *, *$WORD:$ID)*) *")
    private val FENCE = Regex(" {0,3}(`{3,}|~{3,})(.*)")

    /** The blockers named by the blocker lines of [description], in the order written, each once. */
    fun parse(description: String?): List<BlockerRef> {
        if (description.isNullOrEmpty()) return emptyList()
        val refs = linkedSetOf<BlockerRef>()
        var fence: String? = null
        for (raw in description.lines()) {
            val line = raw.removeSuffix("\r")
            val marker = FENCE.matchEntire(line)
            if (fence == null && marker != null) {
                val open = marker.groupValues[1]
                // An info string of a backtick fence holds no backtick (CommonMark).
                if (open[0] == '~' || '`' !in marker.groupValues[2]) {
                    fence = open
                    continue
                }
            }
            if (fence != null) {
                if (marker != null &&
                    marker.groupValues[1][0] == fence[0] &&
                    marker.groupValues[1].length >= fence.length &&
                    marker.groupValues[2].isBlank()
                ) {
                    fence = null
                }
                continue
            }
            val items =
                LINE
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.removeSuffix(".") ?: continue
            ITEM.findAll(items).forEach { refs += BlockerRef(it.groupValues[1], it.groupValues[2]) }
        }
        return refs.toList()
    }
}

/**
 * The cross tracker blocker check of the dispatch (m4-16, story 116). An issue named in a blocker line of
 * [BlockerLines] blocks the issue that names it while it is not in a terminal state of its own tracker, read by id
 * through the [TrackerPoll] of the run, so a GitHub blocker goes through the run's shared rate limit hold (m4-48).
 * Fail closed: a tracker that is not in both the poll and the workflow in effect, a blocker its tracker cannot read
 * and a blocker its tracker does not find keep the issue blocked. A blocked issue gets [Issue.blockedReason]
 * `blocked_cross_tracker <blocker> (<why>), ...`, naming each blocker that holds it, so the orchestrator records it
 * as a `candidate_skipped` reason like a native blocker's. Within a tracker, the native `blocked_by` is unchanged.
 */
object CrossTrackerBlockers {
    /** The `candidate_skipped` reason of an issue a cross tracker blocker holds back. */
    const val REASON = "blocked_cross_tracker"

    /**
     * [issues] with a [REASON] on each one a named blocker holds back. Issues already blocked, and issues with no
     * blocker line, are returned as they are and read nothing. Each blocker is read at most once per call, all of a
     * tracker's in one by id read, whatever the number of issues naming it.
     */
    suspend fun check(
        issues: List<Issue>,
        config: TrackersConfig,
        poll: TrackerPoll,
    ): List<Issue> {
        val named = issues.associate { it.id to if (it.dispatchable) BlockerLines.parse(it.description) else emptyList() }
        val all = named.values.flatten().distinct()
        if (all.isEmpty()) return issues
        val known = all.filter { it.tracker in poll.ids && config.entry(it.tracker) != null }.map { it.text }
        val read = if (known.isEmpty()) null else poll.readByIds(known)
        val found = read?.issues.orEmpty().associateBy { NamespacedId.read(it.id, config.primary.id).value }
        val why = all.associateWith { ref -> holdOf(ref, config, poll, read, found) }
        return issues.map { issue ->
            val holding = named.getValue(issue.id).mapNotNull { ref -> why.getValue(ref)?.let { "${ref.text} ($it)" } }
            if (holding.isEmpty()) issue else issue.copy(blockedReason = "$REASON ${holding.joinToString(", ")}")
        }
    }

    /** Why [ref] holds the issues naming it, or null when it is in a terminal state of its own tracker. */
    private fun holdOf(
        ref: BlockerRef,
        config: TrackersConfig,
        poll: TrackerPoll,
        read: TrackerRead?,
        found: Map<String, Issue>,
    ): String? {
        val entry = config.entry(ref.tracker)?.takeIf { ref.tracker in poll.ids }
        if (entry == null || read == null) return "unknown tracker ${ref.tracker}"
        read.failures[ref.tracker]?.let { return "unreadable: ${it.message ?: it::class.simpleName}".take(MAX_WHY) }
        val blocker = found[ref.text] ?: return "not found"
        return if (entry.isTerminal(blocker.state)) null else blocker.state
    }

    /** The longest `why` kept for an unreadable blocker, so a long error message never floods the skip. */
    private const val MAX_WHY = 200
}
