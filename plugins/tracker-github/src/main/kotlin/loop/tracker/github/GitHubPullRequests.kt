package loop.tracker.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.Mergeability
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.TrackerError
import java.net.URI
import java.net.URLEncoder

/**
 * The pull requests of [owner]/[repo] through [client] (m2-25), opened against [base] from a branch of the same
 * repository: looked up in every state, opened, updated, and since m2-26 merged behind a sha guard, with the tip of
 * [base] read right before. Each request is sent once, with the rate limit hold of the shared [GitHubClient].
 */
class GitHubPullRequests(
    private val client: GitHubClient,
    private val owner: String,
    private val repo: String,
    private val base: String,
) {
    private val pullsPath = "${client.apiUrl}/repos/$owner/$repo/pulls"
    private val commitsPath = "${client.apiUrl}/repos/$owner/$repo/commits"
    private val runsPath = "${client.apiUrl}/repos/$owner/$repo/actions/runs"

    /**
     * The pull request whose head is [owner]:[branch] and whose base is [base], whatever its state: the open one when
     * there is one, else the merged one with the highest number, else the closed one with the highest number; null
     * when there is none. GitHub filters by head and base, and the answer is filtered again by `head.ref` and
     * `base.ref`. An answer that cannot be read fails with [TrackerError.Malformed]; other failures are those of
     * [GitHubClient.send].
     */
    suspend fun find(branch: String): PullRequest? {
        val head = encode("$owner:$branch")
        val url = URI("$pullsPath?head=$head&base=${encode(base)}&state=all&per_page=$PER_PAGE")
        val response = client.send("GET", url, absent = emptySet()) ?: error("no status is absent for a pull request lookup")
        val items = GitHubClient.parse(response.body, url) as? JsonArray ?: throw TrackerError.Malformed(url.toString())
        val found =
            items.mapIndexed { index, item ->
                val pull = item as? JsonObject ?: throw TrackerError.Malformed("$url, item $index")
                pull to (read(pull, created = false) ?: throw TrackerError.Malformed("$url, item $index"))
            }
        val matching =
            found
                .filter { (pull, _) -> pull.ref("head") == branch && pull.ref("base") == base }
                .map { it.second }
        return PullRequestState.entries
            .firstNotNullOfOrNull { state -> matching.filter { it.state == state }.maxByOrNull { it.number } }
    }

    /**
     * Creates a pull request from [branch] against [base]. A create answered 422 because the pull request already
     * exists, a race with another delivery, looks it up exactly once more and patches the open one; no open one then,
     * or any other 422, fails with [TrackerError.Status]. A [body] over [MAX_BODY_CHARS] characters, counted as code
     * points like GitHub, fails with [TrackerError.Rejected] before any request. A success that cannot be read as a
     * pull request fails with [TrackerError.Malformed]. Other failures are those of [GitHubClient.send].
     */
    suspend fun open(
        branch: String,
        title: String,
        body: String,
    ): PullRequest {
        checkBody(body)
        val url = URI(pullsPath)
        val request =
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive(title),
                    "head" to JsonPrimitive(branch),
                    "base" to JsonPrimitive(base),
                    "body" to JsonPrimitive(body),
                ),
            ).toString()
        val response = client.exchange("POST", url, expected = setOf(UNPROCESSABLE), body = request)
        if (response.status != UNPROCESSABLE) return readResponse(response, url, created = true)
        val reasons = validationDetail(response.body)
        if (!reasons.contains(ALREADY_EXISTS, ignoreCase = true)) throw TrackerError.Status(UNPROCESSABLE, reasons)
        val number =
            find(branch)?.takeIf { it.state == PullRequestState.Open }?.number
                ?: throw TrackerError.Status(
                    UNPROCESSABLE,
                    "$reasons, but no open pull request has the head $owner:$branch",
                )
        return update(number, title, body)
    }

    /** Patches the [title] and [body] of the pull request [number]; the body limit and the failures are those of [open]. */
    suspend fun update(
        number: Long,
        title: String,
        body: String,
    ): PullRequest {
        checkBody(body)
        val url = URI("$pullsPath/$number")
        val request = JsonObject(mapOf("title" to JsonPrimitive(title), "body" to JsonPrimitive(body))).toString()
        val response = client.send("PATCH", url, absent = emptySet(), body = request) ?: error("no status is absent for a patch")
        return readResponse(response, url, created = false)
    }

    /**
     * The commit [base] is at on GitHub now (m2-26), `object.sha` of `GET /repos/<owner>/<repo>/git/ref/heads/<base>`.
     * A base that is not a safe branch name ([GitPush.isBaseName]) fails with [TrackerError.Rejected] before any
     * request; an answer that is not that ref with a full commit id fails with [TrackerError.Malformed]; other failures
     * are those of [GitHubClient.send], a missing base a 404 [TrackerError.Status].
     */
    suspend fun baseTip(): String {
        if (!GitPush.isBaseName(base)) throw TrackerError.Rejected("\"$base\" is not a branch name Loop reads. Nothing was sent")
        val url = URI("${client.apiUrl}/repos/$owner/$repo/git/ref/heads/${base.split('/').joinToString("/") { encode(it) }}")
        val response = client.send("GET", url, absent = emptySet()) ?: error("no status is absent for a ref read")
        val item = GitHubClient.parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
        val ref = item.string("ref")
        if (ref != null && ref != "refs/heads/$base") throw TrackerError.Malformed(url.toString())
        return (item["object"] as? JsonObject)?.string("sha")?.takeIf { COMMIT_ID.matches(it) }
            ?: throw TrackerError.Malformed(url.toString())
    }

    /**
     * Merges the pull request [number] with [method] (`merge` or `squash`) only when its head is still [sha], GitHub's
     * sha guard (m2-26), and returns the `sha` of the commit the merge made. A [sha] that is not a full commit id fails
     * with [TrackerError.Rejected] before any request. A refusal is a [TrackerError.Status] whose message says why: a
     * 403 names the permissions the token needs, a 405 says the pull request is not mergeable, a 409 that its head
     * moved. A success without a full commit id fails with [TrackerError.Malformed]; other failures, a rate limit
     * included, are those of [GitHubClient.send].
     */
    suspend fun merge(
        number: Long,
        sha: String,
        method: String,
    ): String {
        if (!COMMIT_ID.matches(sha)) throw TrackerError.Rejected("\"$sha\" is not a full commit id. Pull request #$number was not merged")
        val url = URI("$pullsPath/$number/merge")
        val request = JsonObject(mapOf("sha" to JsonPrimitive(sha), "merge_method" to JsonPrimitive(method))).toString()
        val response =
            try {
                client.exchange("PUT", url, expected = setOf(NOT_ALLOWED, CONFLICT), body = request)
            } catch (e: TrackerError.Status) {
                if (e.status != FORBIDDEN) throw e
                throw TrackerError.Status(
                    FORBIDDEN,
                    "the token may not merge pull request #$number on $owner/$repo, it needs the contents write and the " +
                        "pull requests write permissions. GitHub said: ${e.message.orEmpty().substringAfter(": ")}",
                )
            }
        when (response.status) {
            NOT_ALLOWED ->
                throw TrackerError.Status(
                    NOT_ALLOWED,
                    "pull request #$number is not mergeable. GitHub said: ${GitHubClient.detail(response.body)}",
                )
            CONFLICT ->
                throw TrackerError.Status(
                    CONFLICT,
                    "pull request #$number was not merged, the head moved: it is no longer at $sha, so the sha guard refused the merge. " +
                        "GitHub said: ${GitHubClient.detail(response.body)}",
                )
        }
        val item = GitHubClient.parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
        return item.string("sha")?.takeIf { COMMIT_ID.matches(it) } ?: throw TrackerError.Malformed(url.toString())
    }

    /**
     * Whether GitHub would merge the pull request [number] now (m2-28), `mergeable` and `mergeable_state` of
     * `GET /repos/<owner>/<repo>/pulls/<number>`: `mergeable` is null while GitHub computes it. An answer that is not a
     * pull request object fails with [TrackerError.Malformed]; other failures are those of [GitHubClient.send].
     */
    suspend fun mergeability(number: Long): Mergeability {
        val url = URI("$pullsPath/$number")
        val response = client.send("GET", url, absent = emptySet()) ?: error("no status is absent for a pull request read")
        val item = GitHubClient.parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
        val mergeable = (item["mergeable"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return Mergeability(mergeable, item.string("mergeable_state"))
    }

    /**
     * The Actions runs of the commit [sha] (m3-15), `GET /repos/<owner>/<repo>/actions/runs?head_sha=<sha>&per_page=100`:
     * one page with GitHub's `total_count`, so the verdict never judges a part of a longer listing. Runs are matched by
     * their head commit only, since the `pull_requests` of a run can be empty. A 403 or a 404 says the token cannot read
     * the runs and names the "Actions: read" permission. An answer that is not a runs listing, or a run without its id,
     * name, workflow, event, run number, status or web URL, fails with [TrackerError.Malformed]; other failures, a rate
     * limit included, are those of [GitHubClient.send].
     */
    suspend fun actionsRuns(sha: String): ActionsRuns {
        val url = URI("$runsPath?head_sha=${encode(sha)}&per_page=$PER_PAGE")
        val response =
            try {
                client.send("GET", url, absent = emptySet()) ?: error("no status is absent for a runs listing")
            } catch (e: TrackerError.Status) {
                if (e.status != FORBIDDEN && e.status != NOT_FOUND) throw e
                throw TrackerError.Status(
                    e.status,
                    "the token needs the Actions: read permission to list the Actions runs of $owner/$repo. " +
                        "GitHub said: ${e.message.orEmpty().substringAfter(": ")}",
                )
            }
        val item = GitHubClient.parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
        val runs = item["workflow_runs"] as? JsonArray ?: throw TrackerError.Malformed(url.toString())
        val total =
            (item["total_count"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw TrackerError.Malformed(url.toString())
        return ActionsRuns(
            total,
            runs.mapIndexed { index, run ->
                val obj = run as? JsonObject ?: throw TrackerError.Malformed("$url, item $index")
                readRun(obj) ?: throw TrackerError.Malformed("$url, item $index")
            },
        )
    }

    /** [run] as an Actions run, null when a field the verdict or the comment needs is missing; a missing attempt is 1. */
    private fun readRun(run: JsonObject): ActionsRun? =
        ActionsRun(
            id = run.long("id") ?: return null,
            name = run.string("name") ?: return null,
            workflowId = run.long("workflow_id") ?: return null,
            event = run.string("event") ?: return null,
            runNumber = run.long("run_number") ?: return null,
            runAttempt = run.long("run_attempt") ?: 1,
            status = run.string("status") ?: return null,
            conclusion = run.string("conclusion"),
            url = run.string("html_url") ?: return null,
        )

    private fun checkBody(body: String) {
        val chars = body.codePointCount(0, body.length)
        if (chars > MAX_BODY_CHARS) {
            throw TrackerError.Rejected(
                "The pull request body has $chars characters, over GitHub's limit of $MAX_BODY_CHARS. Nothing was sent, shorten it",
            )
        }
    }

    private fun readResponse(
        response: GitHubResponse,
        url: URI,
        created: Boolean,
    ): PullRequest {
        val item = GitHubClient.parse(response.body, url) as? JsonObject ?: throw TrackerError.Malformed(url.toString())
        return read(item, created) ?: throw TrackerError.Malformed(url.toString())
    }

    /** [item] as a pull request, null when it has no positive number or no web URL. */
    private fun read(
        item: JsonObject,
        created: Boolean,
    ): PullRequest? {
        val number = item.number() ?: return null
        val htmlUrl = item.string("html_url")?.takeIf { it.isNotBlank() } ?: return null
        val state =
            when {
                item.string("merged_at") != null -> PullRequestState.Merged
                item.string("state") == "closed" -> PullRequestState.Closed
                else -> PullRequestState.Open
            }
        val headSha = (item["head"] as? JsonObject)?.string("sha")
        return PullRequest(number, htmlUrl, created, state, headSha)
    }

    override fun toString(): String = "GitHubPullRequests(repo=$owner/$repo, base=$base)"

    companion object {
        /** GitHub refuses a pull request body longer than this many characters. */
        const val MAX_BODY_CHARS: Int = 65_536

        private const val UNPROCESSABLE = 422

        private const val FORBIDDEN = 403

        private const val NOT_FOUND = 404

        private const val NOT_ALLOWED = 405

        private const val CONFLICT = 409

        private val COMMIT_ID = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

        /** The most GitHub answers in one page: a branch has far fewer pull requests, a commit far fewer runs. */
        private const val PER_PAGE = 100

        private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** The `ref` of the [side] (`head` or `base`) of a pull request. */
        private fun JsonObject.ref(side: String): String? = (this[side] as? JsonObject)?.string("ref")

        /** What GitHub's 422 says, in `message` or an `errors[].message`, when the head already has a pull request. */
        private const val ALREADY_EXISTS = "pull request already exists"

        /** GitHub's `message` and every `errors[].message` of a 422 body, joined, kept short. */
        private fun validationDetail(body: String): String {
            val json = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            val messages =
                listOfNotNull(json?.string("message")) +
                    (json?.get("errors") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("message") }
            return if (messages.isEmpty()) GitHubClient.detail(body) else GitHubClient.short(messages.joinToString(": "))
        }

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.number(): Long? = (this["number"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
