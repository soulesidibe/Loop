package loop.tracker.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import loop.api.Issue
import loop.api.TrackerError
import java.net.URI

/**
 * Reads GitHub's native issue dependencies into [Issue.blockedBy] and [Issue.blockedReason] (bl-158), profile in
 * `docs/plugins/tracker-github.md`, section "Blockers". An issue's `issue_dependencies_summary.total_blocked_by`
 * counts every blocker, open or closed: 0 sends no request, more sends one, the blocked_by list of the issue. Only a
 * blocker closed as `completed` resolves; everything else, a blocker the list does not show included, keeps the
 * issue blocked.
 */
internal class GitHubBlockers(
    private val client: GitHubClient,
    private val owner: String,
    private val repo: String,
) {
    private val repoPath = "${client.apiUrl}/repos/$owner/$repo"

    /**
     * [issue], read from [item], with its blockers; the caller sends only an issue nothing else blocks. An [item]
     * with no `issue_dependencies_summary` has no blocker, as on older GitHub Enterprise servers; a summary present
     * but unusable blocks with `blocked_by unreadable summary`. An error status or an unusable body of the blocker
     * request blocks this issue only; a rate limit or an unreachable GitHub fails the call, like any other request.
     */
    suspend fun apply(
        item: JsonObject,
        issue: Issue,
    ): Issue {
        if (SUMMARY !in item) return issue
        val total = item.totalBlockedBy() ?: return issue.copy(blockedReason = "blocked_by unreadable summary")
        if (total == 0L) return issue
        val url = URI("$repoPath/issues/${issue.id}/dependencies/blocked_by?per_page=${GitHubTracker.MAX_PAGE_SIZE}")
        val listed =
            try {
                val response = client.send("GET", url, absent = emptySet()) ?: error("no status is absent for blockers")
                (GitHubClient.parse(response.body, url) as? JsonArray)?.map { blocker(it as? JsonObject) }
            } catch (e: TrackerError.Status) {
                return issue.copy(blockedReason = "blocked_by unreadable (${e.status})")
            } catch (e: TrackerError.Malformed) {
                null
            }
        val readable =
            listed?.filterNotNull()?.takeIf { it.size == listed.size }
                ?: return issue.copy(blockedReason = "blocked_by unreadable (malformed)")
        return issue.copy(blockedBy = readable.map { it.ref }, blockedReason = reason(readable, total))
    }

    /**
     * The reason of the first blocker in [listed] that holds the issue, in GitHub's order; else, when the list shows
     * fewer blockers than [total], the count of those it does not show; null when nothing blocks.
     */
    private fun reason(
        listed: List<Blocker>,
        total: Long,
    ): String? {
        val holding = listed.firstOrNull { !it.resolved }
        val unreadable = total - listed.size
        return when {
            holding != null -> "blocked_by ${holding.display} (${holding.status})"
            unreadable > 0 -> "blocked_by $unreadable unreadable"
            else -> null
        }
    }

    /** `total_blocked_by` of the summary, a count; null when the summary or the count is unusable. */
    private fun JsonObject.totalBlockedBy(): Long? {
        val summary = this[SUMMARY] as? JsonObject ?: return null
        val total = summary["total_blocked_by"] as? JsonPrimitive ?: return null
        if (total.isString) return null
        return total.longOrNull?.takeIf { it >= 0 }
    }

    /**
     * One blocker as the list gives it: an issue with a positive `number`, a `state` and a `repository_url` that ends
     * in `/repos/<owner>/<repo>`; null when any is missing.
     */
    private fun blocker(item: JsonObject?): Blocker? {
        item ?: return null
        val number = (item["number"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
        val state = item.string("state")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val repository = item.string("repository_url")?.let { REPOSITORY.find(it) } ?: return null
        val (blockerOwner, blockerRepo) = repository.destructured
        val here = blockerOwner.equals(owner, ignoreCase = true) && blockerRepo.equals(repo, ignoreCase = true)
        val reason = item.string("state_reason")?.trim()?.takeIf { it.isNotEmpty() }
        return Blocker(
            ref = if (here) "$number" else "$blockerOwner/$blockerRepo#$number",
            display = if (here) "#$number" else "$blockerOwner/$blockerRepo#$number",
            resolved = state.equals(CLOSED, ignoreCase = true) && reason.equals(COMPLETED, ignoreCase = true),
            status = if (state.equals(CLOSED, ignoreCase = true) && reason != null) "$state, $reason" else state,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * A listed blocker: [ref] for [Issue.blockedBy], `12` in this repository or `owner/repo#12` in another, [display]
     * for the reason, `#12` or `owner/repo#12`, and [status], the state with the reason of a closed blocker.
     */
    private class Blocker(
        val ref: String,
        val display: String,
        val resolved: Boolean,
        val status: String,
    )

    private companion object {
        const val SUMMARY = "issue_dependencies_summary"

        const val CLOSED = "closed"

        const val COMPLETED = "completed"

        val REPOSITORY = Regex("/repos/([^/]+)/([^/]+)/?$")
    }
}
