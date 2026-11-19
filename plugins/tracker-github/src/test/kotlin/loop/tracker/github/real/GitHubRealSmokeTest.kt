package loop.tracker.github.real

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.Issue
import loop.api.IssueComment
import loop.api.RealIntegration
import loop.tracker.github.GitHubClient
import loop.tracker.github.GitHubTracker
import loop.tracker.github.GitHubTrackerSettings
import java.net.URI
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * m3-25, story 96, spec 17.8 items 1 to 4: the GitHub tracker against the real playground repository, the `github`
 * target of the real integration profile (see [RealIntegration] and `docs/spec-decisions.md`). Skipped unless
 * `LOOP_REAL_INTEGRATION` names `github`; then it fails when `LOOP_PLAYGROUND_GITHUB_TOKEN` is missing, or when a
 * tracker call does not do what it should.
 *
 * The run is isolated: a fresh [RealIntegration.runId] names two labels of its own and the one issue it creates. The
 * tracker is scoped to the first label, so it sees no other issue, and its required label is the second, `<id>-ready`,
 * never `ready-for-agent`, so no Loop pointed at the playground ever takes the issue as a candidate. At the end the
 * issue is closed and both labels are deleted, whatever happened before; a fine grained token cannot delete an issue,
 * so each run leaves one closed issue on the playground.
 */
class GitHubRealSmokeTest {
    @Test
    fun `m3-25 17_8 the github tracker fetches, reads, comments on and moves its own playground issue, left closed`() {
        val env = System.getenv()
        RealIntegration.require("github", env) { GitHubRealProfile.missing(env) }
        val id = RealIntegration.runId()
        val ready = "$id-ready"
        val settings = GitHubRealProfile.settings(env, id).getOrThrow()
        val run = Run(id, settings)

        val failure = runCatching { runBlocking { withTimeout(RUN_CAP) { run.exercise(ready) } } }.exceptionOrNull()
        val cleanup = runBlocking { run.cleanUp() }
        println("m3-25 real github run $id on ${GitHubRealProfile.REPO}:\n" + run.steps.joinToString("\n") { "  $it" })

        if (failure != null) {
            cleanup.forEach(failure::addSuppressed)
            throw failure
        }
        if (cleanup.isNotEmpty()) {
            throw AssertionError("the cleanup of $id failed, see the suppressed errors; ${run.leftBehind()}").apply {
                cleanup.forEach(::addSuppressed)
            }
        }
    }

    /** One run against the playground: the issue it created and what it did, for the printed summary. */
    private class Run(
        val id: String,
        val settings: GitHubTrackerSettings,
    ) {
        val steps = mutableListOf<String>()
        private val repo = "${settings.apiUrl}/repos/${settings.owner}/${settings.repo}"
        private val labels = mutableListOf<String>()
        private var number: String? = null
        private val title = "Loop real integration smoke test $id"

        suspend fun exercise(ready: String) {
            // The setup goes straight to the API: the tracker has no way to create a label or an issue.
            val client = GitHubClient(settings.apiUrl, settings.token)
            for (label in listOf(id, ready)) {
                val body = json("name" to label, "color" to "ededed", "description" to "Loop real integration run $id, deleted at its end")
                client.send("POST", URI("$repo/labels"), absent = emptySet(), body = body)
                labels += label
                steps += "created the label $label"
            }
            val issueBody =
                json(
                    "title" to title,
                    "body" to "A throwaway issue of Loop's real integration profile (m3-25). The test that opened it closes it.",
                    "labels" to JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(ready))),
                )
            val url = URI("$repo/issues")
            val created = client.send("POST", url, absent = emptySet(), body = issueBody) ?: error("no status is absent")
            val number =
                (GitHubClient.parse(created.body, url) as JsonObject)
                    .getValue("number")
                    .jsonPrimitive.long
                    .toString()
            this.number = number
            steps += "created issue #$number, labelled $id and $ready"

            val tracker = GitHubTracker(settings, requiredLabels = { listOf(ready) })

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
            val reviewed = settle { readIssue(tracker, number) }.until { "needs-review" in it.labels }
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
         * Closes the issue if it is still open, deletes the run's labels, then checks through the API that the issue
         * is closed. Each step runs on its own, with a client of its own, so one failure never skips the next; the
         * failures are returned, never thrown.
         */
        suspend fun cleanUp(): List<Throwable> {
            val failures = mutableListOf<Throwable>()

            suspend fun step(
                what: String,
                block: suspend (GitHubClient) -> Unit,
            ) {
                runCatching { withTimeout(CLEANUP_CAP) { block(GitHubClient(settings.apiUrl, settings.token)) } }
                    .onSuccess { steps += "cleanup: $what" }
                    .onFailure {
                        failures += AssertionError("cleanup: $what failed: $it", it)
                        steps += "cleanup: $what FAILED: $it"
                    }
            }

            val number = number
            if (number != null) {
                step(
                    "closed #$number",
                ) { it.send("PATCH", URI("$repo/issues/$number"), absent = emptySet(), body = json("state" to "closed")) }
            }
            for (label in labels.toList()) {
                step("deleted the label $label") {
                    it.send("DELETE", URI("$repo/labels/${pathSegment(label)}"), absent = setOf(404))
                    labels -= label
                }
            }
            if (number != null) {
                step("checked #$number is closed") {
                    val url = URI("$repo/issues/$number")
                    val response = it.send("GET", url, absent = emptySet()) ?: error("no status is absent")
                    val state = (GitHubClient.parse(response.body, url) as JsonObject).getValue("state").jsonPrimitive.content
                    check(state == "closed") { "#$number is $state" }
                }
            }
            if (failures.isNotEmpty()) steps += "cleanup failed, ${leftBehind()}"
            return failures
        }

        /** What a failed cleanup may have left on the playground, to remove by hand. */
        fun leftBehind(): String = "check issue #${number ?: "none"} is closed and delete the labels ${labels.ifEmpty { listOf("none") }}"

        private suspend fun readIssue(
            tracker: GitHubTracker,
            number: String,
        ): Issue = tracker.fetchIssuesByIds(listOf(number)).getOrThrow().single()
    }

    /**
     * Reads [read] up to [TRIES] times, [PAUSE] apart, until its value meets the condition, and returns the last value
     * either way, for the caller to assert on. GitHub's lists and counts can lag a write by a moment.
     */
    private class Settle<T>(
        private val read: suspend () -> T,
    ) {
        suspend fun until(condition: (T) -> Boolean): T {
            var value = read()
            repeat(TRIES - 1) {
                if (condition(value)) return value
                delay(PAUSE)
                value = read()
            }
            return value
        }
    }

    private companion object {
        const val TRIES = 5
        val PAUSE = 1.seconds
        val RUN_CAP = 3.minutes
        val CLEANUP_CAP = 1.minutes

        fun <T> settle(read: suspend () -> T): Settle<T> = Settle(read)

        fun json(vararg fields: Pair<String, Any>): String =
            JsonObject(
                fields.associate { (key, value) ->
                    key to (value as? JsonArray ?: JsonPrimitive(value as String))
                },
            ).toString()

        fun pathSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
