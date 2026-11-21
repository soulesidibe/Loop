package loop.tracker.github.real

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.Issue
import loop.api.IssueComment
import loop.tracker.github.GitHubClient
import loop.tracker.github.GitHubTracker
import loop.tracker.github.GitHubTrackerSettings
import loop.tracker.github.GitHubTransport
import loop.tracker.github.JdkGitHubTransport
import java.net.URI
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One run of [GitHubRealSmokeTest] against the playground: the labels and the issue it created, and what it did, for
 * the printed summary. [transport] and [pause] are seams for `GitHubRealRunTest`, which drives the run against a fake
 * GitHub.
 */
internal class GitHubRealRun(
    val id: String,
    val settings: GitHubTrackerSettings,
    private val transport: GitHubTransport = JdkGitHubTransport(),
    private val pause: Duration = PAUSE,
) {
    val steps = mutableListOf<String>()
    private val repo = "${settings.apiUrl}/repos/${settings.owner}/${settings.repo}"
    private val labels = mutableListOf<String>()
    private var number: String? = null

    /** True once the issue create was sent: from then on GitHub may hold an issue whose number the run never read. */
    private var issueSent = false

    /** Issues found by the run label in the cleanup, when the create's response was lost. */
    private val found = mutableListOf<String>()

    /** True when the cleanup could not look the run's issues up by label, so an orphan may remain. */
    private var lookupFailed = false
    private val title = "Loop real integration smoke test $id"

    private fun client(): GitHubClient = GitHubClient(settings.apiUrl, settings.token, transport)

    suspend fun exercise(ready: String) {
        // The setup goes straight to the API: the tracker has no way to create a label or an issue.
        val client = client()
        for (label in listOf(id, ready)) {
            val body = json("name" to label, "color" to "ededed", "description" to "Loop real integration run $id, deleted at its end")
            // Kept before the POST: a label GitHub created whose response was lost is still deleted, a 404 is fine.
            labels += label
            client.send("POST", URI("$repo/labels"), absent = emptySet(), body = body)
            steps += "created the label $label"
        }
        val issueBody =
            json(
                "title" to title,
                "body" to "A throwaway issue of Loop's real integration profile (m3-25). The test that opened it closes it.",
                "labels" to JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(ready))),
            )
        val url = URI("$repo/issues")
        issueSent = true
        val created = client.send("POST", url, absent = emptySet(), body = issueBody) ?: error("no status is absent")
        val number =
            (GitHubClient.parse(created.body, url) as JsonObject)
                .getValue("number")
                .jsonPrimitive.long
                .toString()
        this.number = number
        steps += "created issue #$number, labelled $id and $ready"

        val tracker = GitHubTracker(settings, transport, requiredLabels = { listOf(ready) })

        val candidates = settle { tracker.fetchIssuesByStates(listOf("open")).getOrThrow() }.until { it.size == 1 }
        assertEquals(listOf(number), candidates.map { it.id }, "the open candidates of the run label")
        steps += "fetched the open candidates: #$number only"

        val read = tracker.fetchIssuesByIds(listOf(number)).getOrThrow().single()
        assertEquals(title, read.title)
        assertEquals("open", read.state)
        assertTrue(read.labels.containsAll(listOf(id, ready)), "${read.labels}")
        steps += "read #$number by id: open, labels ${read.labels}"

        val text = "Loop real integration comment of run $id."
        tracker.addComment(number, IssueComment("loop", Clock.System.now(), text)).getOrThrow()
        steps += "commented on #$number"
        // No read of the issue between the comment and completeIssue: a read would overwrite the comment count
        // the tracker keeps, and a count GitHub has not updated yet (0) makes completeIssue send nothing.
        val completed = settle { tracker.completeIssue(read).getOrThrow() }.until { text in it.description.orEmpty() }
        assertTrue(text in completed.description.orEmpty(), "the comment is not in the completed issue: ${completed.description}")
        steps += "completed #$number: the comment is in its description"

        tracker.moveIssue(number, "review").getOrThrow()
        val reviewed = settle { readIssue(tracker, number) }.until { "needs-review" in it.labels && ready !in it.labels }
        assertTrue("needs-review" in reviewed.labels, "${reviewed.labels}")
        assertFalse(ready in reviewed.labels, "${reviewed.labels}")
        steps += "moved #$number to review: labels ${reviewed.labels}"

        tracker.moveIssue(number, "closed").getOrThrow()
        val closed = settle { readIssue(tracker, number) }.until { it.state == "closed" }
        assertEquals("closed", closed.state)
        val open = settle { tracker.fetchIssuesByStates(listOf("open")).getOrThrow() }.until { it.isEmpty() }
        assertEquals(emptyList(), open.map { it.id }, "open candidates after the close")
        steps += "moved #$number to closed: no open candidate left"
    }

    /**
     * Closes the run's issue and checks the close response reads `closed`, then deletes the run's labels. When the
     * create's response was lost, so the number is unknown, the issues labelled with the run id are looked up first,
     * and each one still open is closed the same way; when that lookup fails, the labels are kept, since they are the
     * only way to find the issue by hand. Each step runs on its own, with a client of its own, so one failure never
     * skips the next; the failures are returned, never thrown.
     */
    suspend fun cleanUp(): List<Throwable> {
        val failures = mutableListOf<Throwable>()

        suspend fun step(
            what: String,
            block: suspend (GitHubClient) -> Unit,
        ): Boolean =
            runCatching { withTimeout(CLEANUP_CAP) { block(client()) } }
                .onSuccess { steps += "cleanup: $what" }
                .onFailure {
                    failures += AssertionError("cleanup: $what failed: $it", it)
                    steps += "cleanup: $what FAILED: $it"
                }.isSuccess

        val toClose = mutableListOf<String>()
        val number = number
        if (number != null) {
            toClose += number
        } else if (issueSent) {
            lookupFailed =
                !step("looked up the issues labelled $id") { client ->
                    // The list can lag the create, so an empty answer is read again before it counts.
                    val issues = settle { labelled(client) }.until { it.isNotEmpty() }
                    found += issues.keys
                    toClose += issues.filterValues { it == "open" }.keys
                    steps += "cleanup: found ${issues.entries.joinToString { "#${it.key} ${it.value}" }.ifEmpty { "no issue" }}"
                }
        }
        for (issue in toClose) {
            step("closed #$issue") {
                // The PATCH response is the issue after the write, so its state never lags the way a read can.
                val url = URI("$repo/issues/$issue")
                val response = it.send("PATCH", url, absent = emptySet(), body = json("state" to "closed")) ?: error("no status is absent")
                val state = (GitHubClient.parse(response.body, url) as JsonObject).getValue("state").jsonPrimitive.content
                check(state == "closed") { "#$issue is $state" }
            }
        }
        if (!lookupFailed) {
            for (label in labels.toList()) {
                step("deleted the label $label") {
                    it.send("DELETE", URI("$repo/labels/${pathSegment(label)}"), absent = setOf(404))
                    labels -= label
                }
            }
        }
        if (failures.isNotEmpty()) steps += "cleanup failed, ${leftBehind()}"
        return failures
    }

    /** What a failed cleanup may have left on the playground, to remove by hand. */
    fun leftBehind(): String {
        val known = listOfNotNull(number) + found
        val issues =
            when {
                lookupFailed -> "look for issues labelled $id and close them"
                known.isEmpty() -> "check issue none is closed"
                else -> "check issue ${known.joinToString { "#$it" }} is closed"
            }
        return "$issues, and delete the labels ${labels.ifEmpty { listOf("none") }}"
    }

    /** The number and state of each issue labelled with the run id, pull requests left out. */
    private suspend fun labelled(client: GitHubClient): Map<String, String> {
        val url = URI("$repo/issues?labels=${pathSegment(id)}&state=all&per_page=100")
        val response = client.send("GET", url, absent = emptySet()) ?: error("no status is absent")
        return (GitHubClient.parse(response.body, url) as JsonArray)
            .map { it as JsonObject }
            .filter { "pull_request" !in it }
            .associate { it.getValue("number").jsonPrimitive.content to it.getValue("state").jsonPrimitive.content }
    }

    private suspend fun readIssue(
        tracker: GitHubTracker,
        number: String,
    ): Issue = tracker.fetchIssuesByIds(listOf(number)).getOrThrow().single()

    private fun <T> settle(read: suspend () -> T): Settle<T> = Settle(read, pause)

    /**
     * Reads [read] up to [TRIES] times, [pause] apart, until its value meets the condition, and returns the last value
     * either way, for the caller to assert on. GitHub's lists and counts can lag a write by a moment.
     */
    private class Settle<T>(
        private val read: suspend () -> T,
        private val pause: Duration,
    ) {
        suspend fun until(condition: (T) -> Boolean): T {
            var value = read()
            repeat(TRIES - 1) {
                if (condition(value)) return value
                delay(pause)
                value = read()
            }
            return value
        }
    }

    companion object {
        const val TRIES = 5
        val PAUSE = 1.seconds
        val CLEANUP_CAP = 1.minutes

        fun json(vararg fields: Pair<String, Any>): String =
            JsonObject(
                fields.associate { (key, value) ->
                    key to (value as? JsonArray ?: JsonPrimitive(value as String))
                },
            ).toString()

        fun pathSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
