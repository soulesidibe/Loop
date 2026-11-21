package loop.tracker.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/**
 * A GitHub API with no network: exact URLs answer with scripted responses, usually recorded ones
 * ([recorded]), and every other URL goes to [fallback] for a GET, or [writeFallback] for a write, a 404 by
 * default. Every request is kept in [requests], method, headers and body included, so a test can see what was
 * sent and where.
 */
class FakeGitHub : GitHubTransport {
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val method: String = "GET",
        val body: String? = null,
    )

    val requests = mutableListOf<Request>()

    /** When true, every request fails as if the server could not be reached. */
    var unreachable = false

    var fallback: (URI) -> GitHubResponse = { recorded("issue-not-found") }

    /** Answers a write (method, URL, body) no route takes (m2-23). */
    var writeFallback: (String, URI, String?) -> GitHubResponse = { _, _, _ -> recorded("issue-not-found") }

    private val routes = mutableMapOf<String, ArrayDeque<GitHubResponse>>()

    /** A GET of [url] answers with [responses] in turn, the last one again once the others are used. */
    fun on(
        url: String,
        vararg responses: GitHubResponse,
    ) {
        routes["GET $url"] = ArrayDeque(responses.toList())
    }

    /** A [method] request to [url] answers with [responses] in turn, the last one again once the others are used. */
    fun on(
        method: String,
        url: String,
        vararg responses: GitHubResponse,
    ) {
        routes["$method $url"] = ArrayDeque(responses.toList())
    }

    /** The requests that were not a GET, in order. */
    val writes: List<Request> get() = requests.filter { it.method != "GET" }

    override suspend fun send(
        method: String,
        url: URI,
        headers: Map<String, String>,
        body: String?,
    ): GitHubResponse {
        requests += Request(url.toString(), headers, method, body)
        if (unreachable) throw IOException("Connection refused")
        val queue = routes["$method $url"] ?: return if (method == "GET") fallback(url) else writeFallback(method, url, body)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        const val API = "https://api.github.com"
        const val REPO = "octocat/Hello-World"

        /** The first list URL the tracker asks for, in its fixed query order. */
        fun listUrl(
            state: String,
            labels: List<String> = emptyList(),
            perPage: Int = 100,
            repo: String = REPO,
        ): String {
            val labelQuery = if (labels.isEmpty()) "" else "&labels=" + java.net.URLEncoder.encode(labels.joinToString(","), Charsets.UTF_8)
            return "$API/repos/$repo/issues?state=$state$labelQuery&sort=created&direction=asc&per_page=$perPage"
        }

        /** The repository URL the tracker asks when an issue answers 404 or 410. */
        fun repoUrl(repo: String = REPO): String = "$API/repos/$repo"

        fun issueUrl(
            number: Int,
            repo: String = REPO,
        ): String = "$API/repos/$repo/issues/$number"

        /** The URL that lists the blockers of issue [number] (bl-158). */
        fun blockedByUrl(
            number: Int,
            repo: String = REPO,
        ): String = "$API/repos/$repo/issues/$number/dependencies/blocked_by?per_page=100"

        /** The comments URL of issue [number], with `page` when given (bl-06). */
        fun commentsUrl(
            number: Int,
            page: Int? = null,
            repo: String = REPO,
        ): String = "$API/repos/$repo/issues/$number/comments?per_page=100" + (page?.let { "&page=$it" } ?: "")

        /**
         * One comment the way GitHub returns it: its author's login, its creation time and its body (bl-06), and its
         * `author_association` (bl-157), left out when [association] is null.
         */
        fun comment(
            login: String?,
            createdAt: String,
            body: String?,
            association: String? = "OWNER",
        ): JsonObject =
            JsonObject(
                buildMap {
                    put("id", JsonPrimitive(createdAt.hashCode()))
                    put("user", login?.let { JsonObject(mapOf("login" to JsonPrimitive(it))) } ?: JsonNull)
                    put("created_at", JsonPrimitive(createdAt))
                    put("body", body?.let(::JsonPrimitive) ?: JsonNull)
                    association?.let { put("author_association", JsonPrimitive(it)) }
                },
            )

        /** The recorded response `github/recorded/<name>`: its body and its `.headers` file. */
        fun recorded(name: String): GitHubResponse {
            val body = resource("$name.json")
            val lines = resource("$name.headers").lines().filter { it.isNotBlank() }
            val headers = lines.associate { it.substringBefore(':').trim().lowercase() to listOf(it.substringAfter(':').trim()) }
            return GitHubResponse(headers.getValue("status").single().toInt(), headers - "status", body)
        }

        /** The body of a recorded response, parsed. */
        fun recordedJson(name: String): JsonElement = Json.parseToJsonElement(resource("$name.json"))

        fun response(
            status: Int,
            body: String,
            vararg headers: Pair<String, String>,
        ): GitHubResponse = GitHubResponse(status, headers.associate { it.first.lowercase() to listOf(it.second) }, body)

        fun json(
            items: List<JsonObject>,
            vararg headers: Pair<String, String>,
        ): GitHubResponse = response(200, JsonArray(items).toString(), *headers)

        /**
         * The recorded issue 26 with the given fields replaced, the way GitHub would return an issue
         * with them. [labels] become label objects; [pullRequest] adds the `pull_request` key. [authorAssociation]
         * replaces the recorded `NONE`, `OWNER` by default so the issue is trusted (bl-157); null removes the key.
         * [totalBlockedBy] and [openBlockedBy] fill `issue_dependencies_summary` (bl-158), the open count defaulting to
         * the total; a null total removes the summary. [stateReason] replaces the recorded null `state_reason`.
         */
        fun issue(
            number: Int,
            title: String = "Title of $number",
            state: String = "open",
            labels: List<String> = emptyList(),
            body: String? = "Body of $number.",
            createdAt: String = "2013-06-24T08:03:25Z",
            pullRequest: Boolean = false,
            repo: String = REPO,
            comments: Int = 0,
            authorAssociation: String? = "OWNER",
            totalBlockedBy: Int? = 0,
            openBlockedBy: Int? = totalBlockedBy,
            stateReason: String? = null,
        ): JsonObject {
            val template = recordedJson("issue-26").jsonObject
            val labelObjects =
                labels.mapIndexed { i, name ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(1000 + i),
                            "node_id" to JsonPrimitive("LA_label$i"),
                            "url" to JsonPrimitive("$API/repos/$repo/labels/$name"),
                            "name" to JsonPrimitive(name),
                            "color" to JsonPrimitive("ededed"),
                            "default" to JsonPrimitive(false),
                            "description" to JsonNull,
                        ),
                    )
                }
            val fields =
                template +
                    mapOf(
                        "url" to JsonPrimitive("$API/repos/$repo/issues/$number"),
                        "html_url" to JsonPrimitive("https://github.com/$repo/issues/$number"),
                        "id" to JsonPrimitive(900_000 + number),
                        "node_id" to JsonPrimitive("I_node$number"),
                        "number" to JsonPrimitive(number),
                        "title" to JsonPrimitive(title),
                        "state" to JsonPrimitive(state),
                        "labels" to JsonArray(labelObjects),
                        "body" to (body?.let(::JsonPrimitive) ?: JsonNull),
                        "created_at" to JsonPrimitive(createdAt),
                        "comments" to JsonPrimitive(comments),
                        "state_reason" to (stateReason?.let(::JsonPrimitive) ?: JsonNull),
                        "repository_url" to JsonPrimitive("$API/repos/$repo"),
                        "issue_dependencies_summary" to
                            JsonObject(
                                mapOf(
                                    "blocked_by" to JsonPrimitive(openBlockedBy ?: 0),
                                    "total_blocked_by" to JsonPrimitive(totalBlockedBy ?: 0),
                                    "blocking" to JsonPrimitive(0),
                                    "total_blocking" to JsonPrimitive(0),
                                ),
                            ),
                    ) +
                    (authorAssociation?.let { mapOf("author_association" to JsonPrimitive(it)) } ?: emptyMap())
            val withSummary = if (totalBlockedBy == null) fields - "issue_dependencies_summary" else fields
            val withAssociation = if (authorAssociation == null) withSummary - "author_association" else withSummary
            val withPull =
                if (pullRequest) {
                    withAssociation + ("pull_request" to JsonObject(mapOf("url" to JsonPrimitive("$API/repos/$repo/pulls/$number"))))
                } else {
                    withAssociation
                }
            return JsonObject(withPull)
        }

        private fun resource(path: String): String =
            FakeGitHub::class.java
                .getResource("/github/recorded/$path")
                ?.readText()
                ?: error("no recorded response $path")
    }
}

/**
 * A [FakeGitHub.fallback] that answers like the GitHub issues API over [issues]: the list endpoint
 * filters by `state` and `labels` (all must match, case insensitively) and pages with `per_page` and
 * `page`, giving a `Link` header with `rel="next"`; the issue endpoint answers one issue or 404,
 * and the repository endpoint answers the recorded repository. [write] is a [FakeGitHub.writeFallback]
 * that changes the issues like GitHub's label and issue endpoints (m2-23).
 */
class SimulatedIssues(
    issues: List<JsonObject>,
    private val repo: String = FakeGitHub.REPO,
) : (URI) -> GitHubResponse {
    private val issues = issues.toMutableList()

    /**
     * Adds labels (`POST .../labels`, a label already there is kept once, compared case insensitively), removes one
     * (`DELETE .../labels/<name>`, 404 `Label does not exist` when absent), patches the state (`PATCH` the issue) or
     * takes a comment (`POST .../comments`). An unknown issue answers 404.
     */
    fun write(
        method: String,
        url: URI,
        body: String?,
    ): GitHubResponse {
        val prefix = "/repos/$repo/issues/"
        val rest =
            url.rawPath.removePrefix(prefix).takeIf { url.rawPath.startsWith(prefix) } ?: return FakeGitHub.recorded("issue-not-found")
        val parts = rest.split("/", limit = 3)
        val number = parts[0].toIntOrNull() ?: return FakeGitHub.recorded("issue-not-found")
        val index = issues.indexOfFirst { (it["number"] as JsonPrimitive).content.toInt() == number }
        if (index < 0) return FakeGitHub.recorded("issue-not-found")
        val issue = issues[index]
        val names = (issue["labels"] as JsonArray).map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        val json = body?.let { Json.parseToJsonElement(it).jsonObject }
        return when {
            method == "POST" && parts.getOrNull(1) == "labels" && parts.size == 2 -> {
                val added = (json!!["labels"] as JsonArray).map { (it as JsonPrimitive).content }
                val merged = added.fold(names) { acc, name -> if (acc.any { it.equals(name, ignoreCase = true) }) acc else acc + name }
                relabel(index, merged)
            }
            method == "DELETE" && parts.getOrNull(1) == "labels" && parts.size == 3 -> {
                val name = URLDecoder.decode(parts[2].replace("+", "%2B"), Charsets.UTF_8)
                if (names.none { it.equals(name, ignoreCase = true) }) return FakeGitHub.recorded("label-not-found")
                relabel(index, names.filterNot { it.equals(name, ignoreCase = true) })
            }
            method == "PATCH" && parts.size == 1 -> {
                issues[index] = JsonObject(issue + ("state" to json!!.getValue("state")))
                FakeGitHub.response(200, issues[index].toString())
            }
            method == "POST" && parts.getOrNull(1) == "comments" && parts.size == 2 -> FakeGitHub.recorded("create-comment-26")
            else -> FakeGitHub.recorded("issue-not-found")
        }
    }

    private fun relabel(
        index: Int,
        names: List<String>,
    ): GitHubResponse {
        val number = (issues[index]["number"] as JsonPrimitive).content.toInt()
        val template = issues[index]
        val rebuilt = FakeGitHub.issue(number, labels = names, repo = repo)
        issues[index] = JsonObject(template + ("labels" to rebuilt.getValue("labels")))
        return FakeGitHub.response(200, rebuilt.getValue("labels").toString())
    }

    override fun invoke(url: URI): GitHubResponse {
        val path = url.path
        val prefix = "/repos/$repo/issues"
        if (path == "/repos/$repo") return FakeGitHub.recorded("repository")
        if (path == prefix) return list(url)
        val number = path.removePrefix("$prefix/").toIntOrNull()
        if (!path.startsWith("$prefix/") || number == null) return FakeGitHub.recorded("issue-not-found")
        val issue = issues.firstOrNull { (it["number"] as JsonPrimitive).content.toInt() == number }
        return issue?.let { FakeGitHub.response(200, it.toString()) } ?: FakeGitHub.recorded("issue-not-found")
    }

    private fun list(url: URI): GitHubResponse {
        val query =
            url.rawQuery
                .split(
                    "&",
                ).associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        val state = query["state"] ?: "open"
        val wanted = query["labels"]?.split(",")?.map { it.trim().lowercase() }.orEmpty()
        val perPage = query["per_page"]?.toInt() ?: 30
        val page = query["page"]?.toInt() ?: 1
        val matching =
            issues.filter { issue ->
                val issueState = (issue["state"] as JsonPrimitive).content
                val names = (issue["labels"] as JsonArray).map { ((it as JsonObject)["name"] as JsonPrimitive).content.lowercase() }
                (state == "all" || issueState == state) && names.containsAll(wanted)
            }
        val chunk = matching.drop((page - 1) * perPage).take(perPage)
        val more = matching.size > page * perPage
        val headers =
            if (more) {
                val base = url.toString().replace(Regex("&page=\\d+"), "")
                arrayOf("link" to "<$base&page=${page + 1}>; rel=\"next\"")
            } else {
                emptyArray()
            }
        return FakeGitHub.json(chunk, *headers)
    }
}
