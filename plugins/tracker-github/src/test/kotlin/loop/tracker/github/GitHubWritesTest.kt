package loop.tracker.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.IssueComment
import loop.api.TrackerError
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-23: the write side of the GitHub tracker against recorded responses. A move to a handoff state is a label
 * change (add the handoff label, then remove every required label), a move to `open` or `closed` patches the issue
 * state, a comment is posted as given. Writes are never retried inside a call and respect the rate limit hold.
 */
class GitHubWritesTest {
    private val token = "ghp_m223WriteToken0123456789abcdefghijk"
    private val github = FakeGitHub()
    private val clock = TestClock(Instant.parse("2026-11-12T15:00:00Z"))
    private var required = listOf("ready-for-agent")

    private fun tracker(handoffLabels: Map<String, String> = GitHubTrackerSettings.DEFAULT_HANDOFF_LABELS) =
        GitHubTracker(
            GitHubTrackerSettings(
                owner = "octocat",
                repo = "Hello-World",
                token = token,
                apiUrl = URI(FakeGitHub.API),
                handoffLabels = handoffLabels,
            ),
            github,
            clock,
            requiredLabels = { required },
        )

    private val issueUrl = FakeGitHub.issueUrl(26)
    private val labelsUrl = "$issueUrl/labels"
    private val commentsUrl = "$issueUrl/comments"

    private fun labelUrl(encoded: String) = "$labelsUrl/$encoded"

    private fun comment(text: String) = IssueComment("agent a1b2c3d4", clock.now(), text)

    /** Each write as `METHOD url body`, the body parsed so key order and escaping do not matter. */
    private fun writes(): List<Triple<String, String, JsonObject?>> =
        github.writes.map { Triple(it.method, it.url, it.body?.let { body -> Json.parseToJsonElement(body) as JsonObject }) }

    private fun labelsBody(vararg labels: String) = JsonObject(mapOf("labels" to JsonArray(labels.map(::JsonPrimitive))))

    @Test
    fun `defined states are GitHub's two states and the two handoff states`() {
        assertEquals(listOf("open", "closed", "review", "needs_info"), tracker().definedStates)
        assertEquals(listOf("open", "closed", "review", "needs_info"), GitHubTracker.DEFINED_STATES)
    }

    @Test
    fun `a move to review adds needs-review first, then removes every required label`() =
        runTest {
            required = listOf("ready-for-agent", "ready for agent")
            github.on("POST", labelsUrl, FakeGitHub.recorded("add-labels-26"))
            github.on("DELETE", labelUrl("ready-for-agent"), FakeGitHub.recorded("remove-label-26"))
            github.on("DELETE", labelUrl("ready%20for%20agent"), FakeGitHub.recorded("remove-label-26"))

            val result = tracker().moveIssue("26", "review")

            assertTrue(result.isSuccess, result.toString())
            assertEquals(
                listOf(
                    Triple("POST", labelsUrl, labelsBody("needs-review")),
                    Triple("DELETE", labelUrl("ready-for-agent"), null),
                    Triple("DELETE", labelUrl("ready%20for%20agent"), null),
                ),
                writes(),
            )
            for (write in github.writes) {
                assertEquals("Bearer $token", write.headers["Authorization"])
                assertEquals(GitHubTracker.API_VERSION, write.headers["X-GitHub-Api-Version"])
            }
            assertEquals("application/json", github.writes.first().headers["Content-Type"])
        }

    @Test
    fun `a move to needs_info adds needs-info, and handoff_labels sets another label`() =
        runTest {
            github.on("POST", labelsUrl, FakeGitHub.recorded("add-labels-26"))
            github.on("DELETE", labelUrl("ready-for-agent"), FakeGitHub.recorded("remove-label-26"))

            assertTrue(tracker().moveIssue("26", "needs_info").isSuccess)
            assertTrue(tracker(mapOf("review" to "In Review", "needs_info" to "question")).moveIssue("26", "needs_info").isSuccess)
            assertTrue(tracker(mapOf("review" to "In Review", "needs_info" to "question")).moveIssue("26", "review").isSuccess)

            val added = writes().filter { it.first == "POST" }.map { it.third }
            assertEquals(listOf(labelsBody("needs-info"), labelsBody("question"), labelsBody("In Review")), added)
        }

    @Test
    fun `a removal answered 404 counts as success, the label was not there`() =
        runTest {
            required = listOf("ready-for-agent", "afk")
            github.on("POST", labelsUrl, FakeGitHub.recorded("add-labels-26"))
            github.on("DELETE", labelUrl("ready-for-agent"), FakeGitHub.recorded("label-not-found"))
            github.on("DELETE", labelUrl("afk"), FakeGitHub.recorded("label-not-found"))

            val result = tracker().moveIssue("26", "review")

            assertTrue(result.isSuccess, result.toString())
            assertEquals(3, github.writes.size, "every removal is sent")
        }

    @Test
    fun `a move to a handoff state is refused when there is no required label, and nothing is sent`() =
        runTest {
            for (labels in listOf(emptyList(), listOf(" "))) {
                required = labels

                val error = tracker().moveIssue("26", "review").exceptionOrNull()

                assertIs<TrackerError.Rejected>(error)
                assertEquals(
                    "Cannot move to review: tracker.required_labels is empty, so the issue would never leave the active states. " +
                        "Nothing was moved",
                    error.message,
                )
            }
            assertEquals(emptyList(), github.requests)
        }

    @Test
    fun `a move to a handoff state whose label is also a required label is refused, and nothing is sent`() =
        runTest {
            required = listOf("Needs-Review")

            val error = tracker().moveIssue("26", "review").exceptionOrNull()

            assertIs<TrackerError.Rejected>(error)
            assertEquals(
                "Cannot move to review: its label needs-review is also in tracker.required_labels, so the move would remove it. " +
                    "Nothing was moved",
                error.message,
            )
            assertEquals(emptyList(), github.requests)
        }

    @Test
    fun `a failure after the add fails the move, and the next move repeats both calls`() =
        runTest {
            github.on("POST", labelsUrl, FakeGitHub.recorded("add-labels-26"))
            github.on("DELETE", labelUrl("ready-for-agent"), FakeGitHub.recorded("write-forbidden"), FakeGitHub.recorded("remove-label-26"))
            val tracker = tracker()

            val first = tracker.moveIssue("26", "review").exceptionOrNull()
            val second = tracker.moveIssue("26", "review")

            assertIs<TrackerError.Status>(first)
            assertEquals(403, first.status)
            assertTrue(second.isSuccess, second.toString())
            assertEquals(listOf("POST", "DELETE", "POST", "DELETE"), github.writes.map { it.method })
        }

    @Test
    fun `a move to open or closed patches the issue state`() =
        runTest {
            github.on("PATCH", issueUrl, FakeGitHub.recorded("update-issue-26"))

            assertTrue(tracker().moveIssue("26", "closed").isSuccess)
            assertTrue(tracker().moveIssue("26", "open").isSuccess)

            assertEquals(
                listOf(
                    Triple("PATCH", issueUrl, JsonObject(mapOf("state" to JsonPrimitive("closed")))),
                    Triple("PATCH", issueUrl, JsonObject(mapOf("state" to JsonPrimitive("open")))),
                ),
                writes(),
            )
        }

    @Test
    fun `an undefined state or an id that is not an issue number is refused without a request`() =
        runTest {
            val undefined = tracker().moveIssue("26", "done").exceptionOrNull()
            val notIssue = tracker().moveIssue("example-app-26", "review").exceptionOrNull()

            assertIs<TrackerError.UndefinedState>(undefined)
            assertEquals(listOf("open", "closed", "review", "needs_info"), undefined.definedStates)
            assertIs<TrackerError.NotFound>(notIssue)
            assertIs<TrackerError.NotFound>(tracker().addComment("x", comment("hi")).exceptionOrNull())
            assertEquals(emptyList(), github.requests)
        }

    @Test
    fun `a comment posts the text as given`() =
        runTest {
            github.on("POST", commentsUrl, FakeGitHub.recorded("create-comment-26"))
            val text = "Done, see the diff.\nIt says \"ok\" \\ and 🎉."

            val result = tracker().addComment("26", comment(text))

            assertTrue(result.isSuccess, result.toString())
            assertEquals(listOf(Triple("POST", commentsUrl, JsonObject(mapOf("body" to JsonPrimitive(text))))), writes())
        }

    @Test
    fun `a comment over GitHub's 65,536 character limit is refused, never cut, and nothing is sent`() =
        runTest {
            github.on("POST", commentsUrl, FakeGitHub.recorded("create-comment-26"))

            val error = tracker().addComment("26", comment("a".repeat(65_537))).exceptionOrNull()

            assertIs<TrackerError.Rejected>(error)
            assertEquals(
                "The comment has 65537 characters, over GitHub's limit of 65536. Nothing was posted, shorten it and comment again",
                error.message,
            )
            assertEquals(emptyList(), github.requests)

            // At the limit it is posted whole; an emoji is one character, as GitHub counts it.
            assertTrue(tracker().addComment("26", comment("a".repeat(65_536))).isSuccess)
            assertTrue(tracker().addComment("26", comment("🎉".repeat(65_536))).isSuccess)
            assertEquals(
                listOf(65_536, 131_072),
                github.writes.map { (Json.parseToJsonElement(it.body!!) as JsonObject).string("body").length },
            )
        }

    @Test
    fun `a blank comment is refused without a request`() =
        runTest {
            assertIs<TrackerError.BlankComment>(tracker().addComment("26", comment(" \n ")).exceptionOrNull())
            assertEquals(emptyList(), github.requests)
        }

    @Test
    fun `a 403, 404 or 422 on a write is a status error, sent once and never retried, without the token`() =
        runTest {
            for ((name, status) in listOf("write-forbidden" to 403, "issue-not-found" to 404, "validation-failed" to 422)) {
                github.requests.clear()
                val answer = FakeGitHub.recorded(name)
                github.on("POST", labelsUrl, answer)
                github.on("PATCH", issueUrl, answer)
                github.on("POST", commentsUrl, answer)
                val tracker = tracker()

                val errors =
                    listOf(
                        tracker.moveIssue("26", "review").exceptionOrNull(),
                        tracker.moveIssue("26", "closed").exceptionOrNull(),
                        tracker.addComment("26", comment("hello")).exceptionOrNull(),
                        tracker.updateLabels("26", add = listOf("x")).exceptionOrNull(),
                    )

                for (error in errors) {
                    assertIs<TrackerError.Status>(error, name)
                    assertEquals(status, error.status)
                    assertFalse(token in error.message.orEmpty())
                }
                assertEquals(4, github.requests.size, "one request per call for $name: ${github.requests.map { it.method + " " + it.url }}")
            }
            assertEquals(
                "The tracker answered 403: Resource not accessible by personal access token",
                run {
                    github.on("POST", commentsUrl, FakeGitHub.recorded("write-forbidden"))
                    tracker().addComment("26", comment("hello")).exceptionOrNull()?.message
                },
            )
        }

    @Test
    fun `writes respect the rate limit hold of the read side, and a limited write holds the reads`() =
        runTest {
            github.on(FakeGitHub.listUrl("open"), FakeGitHub.recorded("rate-limited"))
            val tracker = tracker()
            assertIs<TrackerError.RateLimited>(tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull())
            github.requests.clear()

            assertIs<TrackerError.RateLimited>(tracker.moveIssue("26", "review").exceptionOrNull())
            assertIs<TrackerError.RateLimited>(tracker.moveIssue("26", "closed").exceptionOrNull())
            assertIs<TrackerError.RateLimited>(tracker.addComment("26", comment("hello")).exceptionOrNull())
            assertIs<TrackerError.RateLimited>(tracker.updateLabels("26", add = listOf("x")).exceptionOrNull())
            assertEquals(emptyList(), github.requests, "no write is sent while requests are held")

            // A write answered with a secondary limit holds the reads for its retry after.
            val other = tracker()
            github.on("POST", commentsUrl, FakeGitHub.recorded("secondary-rate-limited"))
            assertIs<TrackerError.RateLimited>(other.addComment("26", comment("hello")).exceptionOrNull())
            assertIs<TrackerError.RateLimited>(other.fetchIssuesByIds(listOf("26")).exceptionOrNull())
            assertEquals(1, github.requests.size)
        }

    @Test
    fun `after a comment write, the next completion reads the new comment`() =
        runTest {
            github.fallback = SimulatedIssues(listOf(FakeGitHub.issue(26, comments = 0)))
            val tracker = tracker()
            val issue = tracker.fetchIssuesByIds(listOf("26")).getOrThrow().single()
            assertEquals(issue, tracker.completeIssue(issue).getOrThrow(), "no comment counted, no request")
            assertEquals(1, github.requests.size)

            github.on("POST", commentsUrl, FakeGitHub.recorded("create-comment-26"))
            tracker.addComment("26", comment("Done, see the diff")).getOrThrow()
            github.on(
                FakeGitHub.commentsUrl(26),
                FakeGitHub.json(listOf(FakeGitHub.comment("octocat", "2026-11-13T10:00:00Z", "Done, see the diff"))),
            )

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertEquals(FakeGitHub.commentsUrl(26), github.requests.last().url)
            assertEquals("Body of 26.\n\n## Comments\n\n2026-11-13 10:00Z octocat: Done, see the diff", completed.description)
        }

    @Test
    fun `a comment that makes a 101st keeps the completion on the last page`() =
        runTest {
            github.fallback = SimulatedIssues(listOf(FakeGitHub.issue(26, comments = 100)))
            val tracker = tracker()
            val issue = tracker.fetchIssuesByIds(listOf("26")).getOrThrow().single()
            github.on("POST", commentsUrl, FakeGitHub.recorded("create-comment-26"))
            tracker.addComment("26", comment("one more")).getOrThrow()
            github.on(
                FakeGitHub.commentsUrl(26, page = 2),
                FakeGitHub.json(listOf(FakeGitHub.comment("octocat", "2026-11-13T10:00:00Z", "one more"))),
            )

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertEquals(FakeGitHub.commentsUrl(26, page = 2), github.requests.last().url)
            assertTrue("100 older comments are not shown." in completed.description.orEmpty(), completed.description)
        }

    @Test
    fun `a label write adds in one request, then removes each label, ignoring blanks and a label both added and removed`() =
        runTest {
            github.on("POST", labelsUrl, FakeGitHub.recorded("add-labels-26"))
            github.on("DELETE", labelUrl("AFK"), FakeGitHub.recorded("remove-label-26"))

            val result =
                tracker().updateLabels(
                    "26",
                    add = listOf("Needs-Info", " ", "needs-info", "loop"),
                    remove = listOf("AFK", "", "LOOP"),
                )

            assertTrue(result.isSuccess, result.toString())
            assertEquals(
                listOf(Triple("POST", labelsUrl, labelsBody("Needs-Info", "loop")), Triple("DELETE", labelUrl("AFK"), null)),
                writes(),
            )
        }

    @Test
    fun `an unreachable server fails a write with a request error`() =
        runTest {
            github.unreachable = true

            assertIs<TrackerError.Unavailable>(tracker().moveIssue("26", "review").exceptionOrNull())
            assertIs<TrackerError.Unavailable>(tracker().addComment("26", comment("hello")).exceptionOrNull())
        }

    private fun JsonObject.string(key: String): String = (this[key] as JsonPrimitive).content
}
