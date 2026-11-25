package loop.core.trackers

import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError

/**
 * The tracker [trackerId] as the core sees it (m4-12): the [native] adapter keeps its own ids, and every issue it
 * hands over gets the [NamespacedId] `<trackerId>:<id>`, its identifier staying native. An id the core passes back is
 * read with [NamespacedId.read], a bare id being the [primary] tracker's: the adapter gets the native id of its own
 * issues, a read by id drops the others and a write for another tracker's issue fails with [TrackerError.NotFound].
 * A write by issue hands the adapter the current issue with its native id, its `native_ref` kept (m4-14).
 */
class NamespacedTracker(
    val trackerId: String,
    private val primary: String,
    val native: TrackerAdapter,
) : TrackerAdapter {
    override val definedStates: List<String> get() = native.definedStates

    override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> =
        native.fetchIssuesByStates(states).map { issues -> issues.map(::namespaced) }

    override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
        val own = ids.mapNotNull(::nativeOf)
        if (own.isEmpty()) return Result.success(emptyList())
        return native.fetchIssuesByIds(own).map { issues -> issues.map(::namespaced) }
    }

    override suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> = write(issueId) { native.moveIssue(it, toState) }

    override suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> = write(issueId) { native.addComment(it, comment) }

    override suspend fun moveIssue(
        issue: Issue,
        toState: String,
    ): Result<Unit> = write(issue.id) { native.moveIssue(issue.copy(id = it), toState) }

    override suspend fun addComment(
        issue: Issue,
        comment: IssueComment,
    ): Result<Unit> = write(issue.id) { native.addComment(issue.copy(id = it), comment) }

    override suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> = write(issueId) { native.updateLabels(it, add, remove) }

    override suspend fun completeIssue(issue: Issue): Result<Issue> {
        val id = nativeOf(issue.id) ?: return Result.failure(TrackerError.NotFound(issue.id))
        return native.completeIssue(issue.copy(id = id)).map(::namespaced)
    }

    /** Creates the issue in the native tracker (m4-33) and gives it with its namespaced id. */
    override suspend fun createIssue(
        title: String,
        body: String,
        labels: List<String>,
    ): Result<Issue> = native.createIssue(title, body, labels).map(::namespaced)

    override fun toString(): String = "NamespacedTracker($trackerId, $native)"

    /** The native id of [issueId] when it names an issue of this tracker, else null. */
    private fun nativeOf(issueId: String): String? = NamespacedId.read(issueId, primary).takeIf { it.tracker == trackerId }?.native

    private inline fun write(
        issueId: String,
        block: (String) -> Result<Unit>,
    ): Result<Unit> = nativeOf(issueId)?.let(block) ?: Result.failure(TrackerError.NotFound(issueId))

    private fun namespaced(issue: Issue): Issue = issue.copy(id = NamespacedId(trackerId, issue.id).value)
}
