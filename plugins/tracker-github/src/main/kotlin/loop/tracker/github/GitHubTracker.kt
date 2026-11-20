package loop.tracker.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The `github` tracker: issues of one repository through the GitHub REST API, profile in
 * `docs/plugins/tracker-github.md`. Reads (m2-22): lists follow the `Link` header page by page and
 * fail as a whole on any page error. After a rate limit answer, or a success that spends the last
 * request, no request is sent until GitHub's reset time, and every call, a write included, fails fast with
 * [TrackerError.RateLimited]. [completeIssue] appends the issue's comments to its description just before its
 * prompt is rendered (bl-06). Only authors in `trusted_associations` reach the agent (bl-157): an issue by another
 * author is blocked, a comment by one is left out. Writes (m2-23): a move to a handoff state is a label change, a move to `open` or
 * `closed` patches the issue state, a comment is posted as given. A write is never retried inside a call.
 */
class GitHubTracker(
    private val settings: GitHubTrackerSettings,
    private val transport: GitHubTransport = JdkGitHubTransport(),
    private val clock: Clock = Clock.System,
    /** Gets every issue a list skipped because it could not be normalized. */
    private val onMalformed: (TrackerError.Malformed) -> Unit = {},
    /** Issues per page, `per_page`, 1 to 100. */
    private val pageSize: Int = MAX_PAGE_SIZE,
    /** `tracker.required_labels` of the effective workflow, read at each move to a handoff state (m2-23). */
    private val requiredLabels: () -> List<String> = { emptyList() },
) : TrackerAdapter {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be 1 to $MAX_PAGE_SIZE, got $pageSize" }
    }

    override val definedStates: List<String> = DEFINED_STATES

    /** The requests, their headers and the rate limit hold, shared with pull request delivery (m2-25). */
    private val client = GitHubClient(settings.apiUrl, settings.token, transport, clock)

    private val base = settings.apiUrl.toString()

    private val repoPath = "$base/repos/${settings.owner}/${settings.repo}"

    private val scope = settings.scopeLabels.map { it.lowercase() }

    private val trusted = settings.trustedAssociations.map { it.uppercase() }.toSet()

    /** GitHub's comment count of every issue this tracker read, by id, as of its latest read (bl-06). */
    private val commentCounts = ConcurrentHashMap<String, Long>()

    /**
     * Issues in [states], compared case insensitively with `open` and `closed`. Other names match
     * nothing, the handoff states included, so a list with neither sends no request (spec 17.3).
     */
    override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
        val wanted = states.map { it.trim().lowercase() }.filter { it in ISSUE_STATES }.toSet()
        if (wanted.isEmpty()) return Result.success(emptyList())
        return catching { list(if (wanted.size == ISSUE_STATES.size) "all" else wanted.single()) }
    }

    /**
     * One request per distinct id. An id that is not an issue number, a pull request and an issue out
     * of scope are omitted. A 404 or 410 answer (not found, hidden or deleted) omits the issue only
     * when the repository itself answers, asked once per call (bl-22): otherwise the call fails, so a
     * token that lost access never reads as every issue gone. A 301 (a renamed repository or a
     * transferred issue) fails the call too. A requested issue that cannot be normalized fails the
     * call (spec 11.1).
     */
    override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> =
        catching {
            var repositoryChecked = false
            ids.filter { ISSUE_NUMBER.matches(it) }.distinct().mapNotNull { number ->
                val url = URI("$base/repos/${settings.owner}/${settings.repo}/issues/$number")
                val response = send(url, absent = GONE)
                if (response == null) {
                    // Fails the call with the repository's own error when it does not answer.
                    if (!repositoryChecked) send(URI("$base/repos/${settings.owner}/${settings.repo}"), absent = emptySet())
                    repositoryChecked = true
                    return@mapNotNull null
                }
                val item = parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
                if (item.isPullRequest()) return@mapNotNull null
                val issue = normalize(item) ?: throw TrackerError.Malformed(url.toString())
                issue.takeIf { item.inScope() }
            }
        }

    /**
     * Appends the comments of [issue] to its description under `## Comments`, oldest first, one block per comment
     * in the files tracker's shape: `YYYY-MM-DD HH:MMZ <login>: <first line>`, the other lines indented two spaces,
     * blank lines dropped (bl-06). One request: none when the latest read of the issue counted no comment, the last
     * page when it counted more than one page, so the newest comments are the ones kept. An issue this tracker never
     * read is asked for anyway. Blank comments are left out, and an issue with no other comment is returned as is.
     * A comment whose `author_association` is missing or outside `trusted_associations` is left out too (bl-157):
     * the section then opens with one line that counts them, and shows nothing of their text or author.
     */
    override suspend fun completeIssue(issue: Issue): Result<Issue> =
        catching {
            if (!ISSUE_NUMBER.matches(issue.id)) return@catching issue
            val count = commentCounts[issue.id]
            if (count == 0L) return@catching issue
            val page = count?.let { (it + MAX_PAGE_SIZE - 1) / MAX_PAGE_SIZE }?.takeIf { it > 1 }
            val url =
                URI(
                    "$base/repos/${settings.owner}/${settings.repo}/issues/${issue.id}/comments?per_page=$MAX_PAGE_SIZE" +
                        (page?.let { "&page=$it" } ?: ""),
                )
            val response = send(url, absent = emptySet()) ?: error("no status is absent for comments")
            val items = parse(response.body, url) as? JsonArray ?: throw TrackerError.Malformed(url.toString())
            val (kept, untrusted) =
                items
                    .mapIndexed { index, element -> comment(element) ?: throw TrackerError.Malformed("$url, comment $index") }
                    .partition { isTrusted(it.association) }
            val blocks = kept.map { it.comment }.sortedBy { it.at }.mapNotNull(::formatComment)
            if (blocks.isEmpty() && untrusted.isEmpty()) return@catching issue
            val hidden =
                when (untrusted.size) {
                    0 -> null
                    1 -> "1 comment from outside tracker.provider.trusted_associations is not shown."
                    else -> "${untrusted.size} comments from outside tracker.provider.trusted_associations are not shown."
                }
            val older = page?.let { "${(it - 1) * MAX_PAGE_SIZE} older comments are not shown." }
            val section = listOfNotNull("## Comments", hidden, older) + blocks
            issue.copy(description = (listOfNotNull(issue.description) + section).joinToString("\n\n"))
        }

    /**
     * Moves the issue (m2-23). A move to `open` or `closed` patches the issue state. A move to a handoff state,
     * `review` or `needs_info`, is a label change: its label from `handoff_labels` is added first, then every
     * `tracker.required_labels` label is removed, so the issue leaves the active set and keeps `open`. The move is
     * refused, with nothing sent, when there is no required label, or when the handoff label is one of them: the
     * issue would stay in the active set. A failure after the add leaves the handoff label on with a required label
     * still there; the next move repeats both calls, which are idempotent.
     */
    override suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> {
        if (toState !in DEFINED_STATES) return Result.failure(TrackerError.UndefinedState(toState, definedStates))
        if (!ISSUE_NUMBER.matches(issueId)) return Result.failure(TrackerError.NotFound(issueId))
        if (toState in ISSUE_STATES) {
            return catching {
                val body = JsonObject(mapOf("state" to JsonPrimitive(toState))).toString()
                send("PATCH", URI("$repoPath/issues/$issueId"), absent = emptySet(), body = body)
            }.map {}
        }
        val required = requiredLabels().map { it.trim() }.filter { it.isNotEmpty() }
        if (required.isEmpty()) {
            return Result.failure(
                TrackerError.Rejected(
                    "Cannot move to $toState: tracker.required_labels is empty, so the issue would never leave the active states. " +
                        "Nothing was moved",
                ),
            )
        }
        val label = settings.handoffLabels[toState] ?: GitHubTrackerSettings.DEFAULT_HANDOFF_LABELS.getValue(toState)
        if (required.any { it.equals(label, ignoreCase = true) }) {
            return Result.failure(
                TrackerError.Rejected(
                    "Cannot move to $toState: its label $label is also in tracker.required_labels, so the move would remove it. " +
                        "Nothing was moved",
                ),
            )
        }
        return updateLabels(issueId, add = listOf(label), remove = required)
    }

    /**
     * Posts [comment]'s text as given, the author being the token's account (m2-23). A blank text and a text over
     * GitHub's [MAX_COMMENT_CHARS] characters, counted as code points like GitHub, are refused with nothing sent,
     * never cut. After the write the issue's comment count goes up by one, so the next [completeIssue] asks for its
     * comments, on the right page.
     */
    override suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> {
        val text = comment.text
        if (text.isBlank()) return Result.failure(TrackerError.BlankComment())
        if (!ISSUE_NUMBER.matches(issueId)) return Result.failure(TrackerError.NotFound(issueId))
        val chars = text.codePointCount(0, text.length)
        if (chars > MAX_COMMENT_CHARS) {
            return Result.failure(
                TrackerError.Rejected(
                    "The comment has $chars characters, over GitHub's limit of $MAX_COMMENT_CHARS. " +
                        "Nothing was posted, shorten it and comment again",
                ),
            )
        }
        return catching {
            val body = JsonObject(mapOf("body" to JsonPrimitive(text))).toString()
            send("POST", URI("$repoPath/issues/$issueId/comments"), absent = emptySet(), body = body)
            commentCounts.computeIfPresent(issueId) { _, count -> count + 1 }
        }.map {}
    }

    /**
     * Adds [add] in one request, then removes each of [remove], one request each (m2-23). Labels are trimmed and
     * blanks ignored; a label given twice, or both added and removed, compared case insensitively, is added once
     * and not removed. GitHub keeps a label already there once, and a removal answered 404 means the label was not
     * there, so both are idempotent. An id that is not an issue number fails with [TrackerError.NotFound] without a
     * request; any other failure stops the call at that request.
     */
    override suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> {
        if (!ISSUE_NUMBER.matches(issueId)) return Result.failure(TrackerError.NotFound(issueId))
        val adding = add.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val added = adding.map { it.lowercase() }.toSet()
        val removing = remove.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in added }.distinctBy { it.lowercase() }
        return catching {
            if (adding.isNotEmpty()) {
                val body = JsonObject(mapOf("labels" to JsonArray(adding.map(::JsonPrimitive)))).toString()
                send("POST", URI("$repoPath/issues/$issueId/labels"), absent = emptySet(), body = body)
            }
            for (label in removing) {
                send("DELETE", URI("$repoPath/issues/$issueId/labels/${pathSegment(label)}"), absent = LABEL_ABSENT)
            }
        }
    }

    private suspend fun list(state: String): List<Issue> {
        val labels =
            if (settings.scopeLabels.isEmpty()) {
                ""
            } else {
                "&labels=" +
                    URLEncoder.encode(settings.scopeLabels.joinToString(","), Charsets.UTF_8)
            }
        var url: URI? =
            URI("$base/repos/${settings.owner}/${settings.repo}/issues?state=$state$labels&sort=created&direction=asc&per_page=$pageSize")
        val seenPages = mutableSetOf<String>()
        val seenNumbers = mutableSetOf<Long>()
        val issues = mutableListOf<Issue>()
        while (url != null) {
            if (seenPages.size == MAX_PAGES) throw TrackerError.Pagination("more than $MAX_PAGES pages of $pageSize issues")
            if (!seenPages.add(url.toString())) throw TrackerError.Pagination("the next page link repeats $url")
            val response = send(url, absent = emptySet()) ?: error("no status is absent in a list")
            val items = parse(response.body, url) as? JsonArray ?: throw TrackerError.Malformed(url.toString())
            items.forEachIndexed { index, element ->
                val item = element as? JsonObject
                val number = item?.number()
                if (number != null && !seenNumbers.add(number)) {
                    throw TrackerError.Pagination("issue $number is on two pages, the list changed while it was read")
                }
                when {
                    item != null && item.isPullRequest() -> Unit
                    item == null -> onMalformed(TrackerError.Malformed("$url, item $index"))
                    else ->
                        normalize(item)?.let { if (item.inScope()) issues += it }
                            ?: onMalformed(TrackerError.Malformed("$url, item $index"))
                }
            }
            url = nextPage(response, url)
        }
        return issues
    }

    /**
     * Sends one GET of [url], see the other [send].
     */
    private suspend fun send(
        url: URI,
        absent: Set<Int>,
    ): GitHubResponse? = send("GET", url, absent)

    /** Sends one request through the shared [GitHubClient.send]. */
    private suspend fun send(
        method: String,
        url: URI,
        absent: Set<Int>,
        body: String? = null,
    ): GitHubResponse? = client.send(method, url, absent, body)

    /** The `rel="next"` URL of the `Link` header, null on the last page; it must stay on the API origin. */
    private fun nextPage(
        response: GitHubResponse,
        current: URI,
    ): URI? {
        val link = response.header("link") ?: return null
        val next =
            link.split(",").firstNotNullOfOrNull { part ->
                val target = LINK_PART.matchEntire(part.trim()) ?: return@firstNotNullOfOrNull null
                target.groupValues[1].takeIf { target.groupValues[2].split(" ").contains("next") }
            } ?: return null
        val uri =
            try {
                current.resolve(next)
            } catch (e: IllegalArgumentException) {
                throw TrackerError.Pagination("the next page link is not an address: $next")
            }
        if (!sameOrigin(uri, settings.apiUrl)) throw TrackerError.Pagination("the next page link leaves ${settings.apiUrl}: $uri")
        return uri
    }

    private fun normalize(item: JsonObject): Issue? {
        val number = item.number() ?: return null
        val title = item.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val state = item.string("state")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val names =
            item
                .labelNames()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
        val priorities =
            names.mapNotNull {
                PRIORITY_LABEL
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
        val labels =
            names.filterNot { name ->
                PRIORITY_LABEL
                    .matchEntire(name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() != null
            }
        val association = item.association()
        return Issue(
            id = "$number",
            identifier = "${settings.repo}-$number",
            title = title,
            state = state,
            priority = priorities.minOrNull(),
            labels = labels,
            blockedBy = emptyList(),
            blockedReason = if (isTrusted(association)) null else "untrusted_author (${association ?: "missing"})",
            description = item.string("body")?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = item.string("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            nativeRef =
                buildMap {
                    put("repo", "${settings.owner}/${settings.repo}")
                    put("number", "$number")
                    item.string("node_id")?.let { put("node_id", it) }
                    (item["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.let { put("database_id", "$it") }
                    item.string("html_url")?.let { put("url", it) }
                },
        ).also { issue -> item.commentCount()?.let { commentCounts[issue.id] = it } ?: commentCounts.remove(issue.id) }
    }

    /** GitHub's `comments` count; null when it is missing or not a count. */
    private fun JsonObject.commentCount(): Long? {
        val count = (this["comments"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return count?.takeIf { it >= 0 }
    }

    /** A comment as GitHub gives it, with its author's `author_association`, null when GitHub gives none (bl-157). */
    private class GitHubComment(
        val comment: IssueComment,
        val association: String?,
    )

    /**
     * One comment: its author's login (`ghost` for a deleted account), its creation time, its text and its author's
     * association; null when unusable.
     */
    private fun comment(element: JsonElement): GitHubComment? {
        val item = element as? JsonObject ?: return null
        val at = item.string("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val login = (item["user"] as? JsonObject)?.string("login")?.trim()?.takeIf { it.isNotEmpty() } ?: GHOST
        return GitHubComment(IssueComment(login, at, item.string("body").orEmpty()), item.association())
    }

    /** `author_association`, trimmed and upper case; null when missing, blank or not a string (bl-157). */
    private fun JsonObject.association(): String? = string("author_association")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /** True when [association] is in `trusted_associations`; a missing one never is (bl-157). */
    private fun isTrusted(association: String?): Boolean = association != null && association in trusted

    /** The comment's lines in the files tracker's shape; null when it is blank. */
    private fun formatComment(comment: IssueComment): String? {
        val lines =
            comment.text
                .split(NEWLINE)
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val iso = comment.at.toString()
        val first = "${iso.substring(0, 10)} ${iso.substring(11, 16)}Z ${comment.author}: ${lines.first().trimStart()}"
        return (listOf(first) + lines.drop(1).map { "  $it" }).joinToString("\n")
    }

    private fun JsonObject.number(): Long? = (this["number"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.isPullRequest(): Boolean = this["pull_request"] is JsonObject

    /** Label names as GitHub gives them: objects with a `name`, or plain strings; anything else is dropped. */
    private fun JsonObject.labelNames(): List<String> =
        (this["labels"] as? JsonArray).orEmpty().mapNotNull { label ->
            when (label) {
                is JsonObject -> (label["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                is JsonPrimitive -> label.takeIf { it.isString }?.content
                else -> null
            }
        }

    /** Carries every scope label, compared trimmed and case insensitively like GitHub's filter. */
    private fun JsonObject.inScope(): Boolean {
        if (scope.isEmpty()) return true
        val names = labelNames().map { it.trim().lowercase() }.toSet()
        return scope.all { it in names }
    }

    private fun parse(
        body: String,
        url: URI,
    ): JsonElement = GitHubClient.parse(body, url)

    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: TrackerError) {
            Result.failure(e)
        }

    companion object {
        /** GitHub's two issue states, in its spelling. */
        val ISSUE_STATES: List<String> = listOf("open", "closed")

        /** The handoff states, made of labels on an `open` issue (m2-23); `handoff_labels` names each label. */
        val HANDOFF_STATES: List<String> = listOf("review", "needs_info")

        /** Every state a move can target: GitHub's two, then the two handoff states. */
        val DEFINED_STATES: List<String> = ISSUE_STATES + HANDOFF_STATES

        /** GitHub refuses a comment body longer than this many characters. */
        const val MAX_COMMENT_CHARS: Int = 65_536

        /** A list stops with [TrackerError.Pagination] past this many pages: 10,000 issues at 100 a page. */
        const val MAX_PAGES: Int = 100

        const val MAX_PAGE_SIZE: Int = 100

        const val API_VERSION: String = GitHubClient.API_VERSION

        const val USER_AGENT: String = GitHubClient.USER_AGENT

        /** Answers to an issue request that mean it is no longer visible, not found or deleted, once the repository answers. */
        private val GONE = setOf(404, 410)

        /** The answer to a label removal that means the issue does not carry the label: a success (m2-23). */
        private val LABEL_ABSENT = setOf(404)

        /** [value] as one URL path segment: a space is `%20`, never `+`, and `/` is escaped. */
        private fun pathSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private val ISSUE_NUMBER = Regex("[1-9][0-9]{0,17}")

        /** The login GitHub shows for a deleted account. */
        private const val GHOST = "ghost"

        private val NEWLINE = Regex("\\r\\n|\\r|\\n")

        private val PRIORITY_LABEL = Regex("priority:\\s*(\\d+)")

        private val LINK_PART = Regex("<([^>]*)>\\s*;\\s*rel=\"([^\"]*)\"")

        private fun sameOrigin(
            a: URI,
            b: URI,
        ): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b)

        private fun effectivePort(uri: URI): Int =
            if (uri.port != -1) {
                uri.port
            } else if (uri.scheme.equals("https", ignoreCase = true)) {
                443
            } else {
                80
            }
    }
}
