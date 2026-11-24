package loop.core.trackers

import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter

/**
 * Where a tracker write goes (m4-14, story 114, bl-153): to the tracker of the issue's namespaced id (m4-12), a bare id
 * being the [primary]'s, never to the primary by default. A write for an issue whose tracker is not in [adapters]
 * fails with [UnroutedWrite], which names the tracker id, and nothing is written anywhere. Every write of the core,
 * the agent's `move` and `comment` and Loop's own, goes through the [TrackerPoll] that holds this routing.
 *
 * A lone adapter that is not a [NamespacedTracker] takes every id unchanged: its ids carry no tracker, as in the
 * orchestrator's tests, which hand a single fake adapter over.
 */
class WriteRouting(
    private val primary: String,
    /** The adapter of each tracker id, the primary first. */
    private val adapters: Map<String, TrackerAdapter>,
) {
    private val lone: TrackerAdapter? = adapters.values.singleOrNull()?.takeIf { it !is NamespacedTracker }

    /** The adapter of the tracker of [issueId], or an [UnroutedWrite] when the registry has no such tracker. */
    fun route(issueId: String): Result<TrackerAdapter> {
        lone?.let { return Result.success(it) }
        val tracker = NamespacedId.read(issueId, primary).tracker
        return adapters[tracker]?.let { Result.success(it) } ?: Result.failure(UnroutedWrite(tracker, issueId))
    }

    suspend fun moveIssue(
        issue: Issue,
        toState: String,
    ): Result<Unit> = write(issue.id) { it.moveIssue(issue, toState) }

    suspend fun addComment(
        issue: Issue,
        comment: IssueComment,
    ): Result<Unit> = write(issue.id) { it.addComment(issue, comment) }

    suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> = write(issueId) { it.moveIssue(issueId, toState) }

    suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> = write(issueId) { it.addComment(issueId, comment) }

    suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> = write(issueId) { it.updateLabels(issueId, add, remove) }

    /** [block] on the adapter of [issueId], or the failure of [route]. */
    private inline fun write(
        issueId: String,
        block: (TrackerAdapter) -> Result<Unit>,
    ): Result<Unit> = route(issueId).fold(block) { Result.failure(it) }
}

/** A write for the issue [issueId], whose tracker [trackerId] is not in the project's registry: nothing was written. */
class UnroutedWrite(
    val trackerId: String,
    val issueId: String,
) : IllegalStateException("No tracker \"$trackerId\" in this project for the issue $issueId, so nothing was written")
