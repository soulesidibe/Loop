package loop.core.trackers

/**
 * The follow ups an agent files with `file_followup` (m4-33, story 130, PLAN 6.10). `loop tool mcp` queues the call
 * like `comment`, and the orchestrator files it host side when the turn ends: an issue labelled [LABEL] in the tracker
 * of the parent issue, or the one its kind names with `kinds.<kind>.followup.tracker` ([trackerOf]), whose body ends
 * with a line naming the parent and the attempt ([body]). Its namespaced id goes in `followup_filed` and in a comment
 * on the parent ([parentComment]). An attempt files at most [MAX_PER_ATTEMPT], so a looping agent cannot flood a
 * tracker.
 */
object Followups {
    /** The most follow ups one attempt files. `loop tool mcp` refuses a call past it, and so does the drain. */
    const val MAX_PER_ATTEMPT = 3

    /** The label of every follow up, so a human triages it before an agent picks it up. */
    const val LABEL = "needs-triage"

    /** A follow up as filed: its namespaced [id], its native [identifier] and its [tracker] id. */
    data class Filed(
        val id: String,
        val identifier: String,
        val tracker: String,
    )

    /**
     * The tracker id of a follow up of [parentId]: [override], the parent kind's `followup.tracker`, when set, else the
     * parent's own tracker, a bare id being the [primary]'s (m4-12).
     */
    fun trackerOf(
        parentId: String,
        override: String?,
        primary: String,
    ): String = override ?: NamespacedId.read(parentId, primary).tracker

    /** The agent's [text], trimmed, then a line naming the parent issue and the attempt. */
    fun body(
        text: String,
        parentIdentifier: String,
        parentId: String,
        attemptId: String,
    ): String {
        val footer = "Follow up of $parentIdentifier ($parentId), filed by Loop from attempt $attemptId."
        val written = text.trim()
        return if (written.isEmpty()) footer else "$written\n\n$footer"
    }

    /** Why one more follow up is refused when the attempt already [filed] some, null while it is under the limit. */
    fun refusal(filed: Int): String? =
        if (filed >= MAX_PER_ATTEMPT) "this attempt already filed $filed follow ups, the most Loop files per attempt" else null

    /**
     * Creates the follow up [title] with [body] and [LABEL] in the tracker [tracker] of [trackers]. A tracker the poll
     * does not have fails with nothing written, and so does one that cannot create issues.
     */
    suspend fun file(
        trackers: TrackerPoll,
        tracker: String,
        title: String,
        body: String,
    ): Result<Filed> =
        trackers.createIssueIn(tracker, title, body, listOf(LABEL)).map { issue ->
            Filed(NamespacedId.read(issue.id, tracker).value, issue.identifier, tracker)
        }

    /** The comment Loop adds on the parent issue once the follow up is filed. */
    fun parentComment(
        filed: Filed,
        title: String,
    ): String = "Filed the follow up ${filed.id} in the tracker ${filed.tracker}: $title"
}
