package loop.tracker.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.Issue
import loop.api.TrackerError
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** bl-158: GitHub's native issue dependencies give `blockedBy` and `blockedReason`. */
class GitHubBlockersTest {
    private val github = FakeGitHub()
    private val clock = TestClock(Instant.parse("2026-11-21T10:00:00Z"))
    private val ready = listOf("ready-for-agent")

    private fun tracker(required: List<String> = ready) =
        GitHubTracker(
            GitHubTrackerSettings(owner = "octocat", repo = "Hello-World", token = "ghp_token", apiUrl = URI(FakeGitHub.API)),
            github,
            clock,
            requiredLabels = { required },
        )

    private fun simulate(vararg issues: JsonObject) {
        github.fallback = SimulatedIssues(issues.toList())
    }

    /** An open candidate issue [number] with [total] blockers. */
    private fun candidate(
        number: Int,
        total: Int,
        authorAssociation: String = "OWNER",
    ) = FakeGitHub.issue(number, labels = ready, totalBlockedBy = total, authorAssociation = authorAssociation)

    /** A blocker the way the blocked_by endpoint lists it: a whole issue with its state and state reason. */
    private fun blocker(
        number: Int,
        state: String = "open",
        reason: String? = null,
        repo: String = FakeGitHub.REPO,
    ) = FakeGitHub.issue(number, state = state, stateReason = reason, repo = repo)

    private fun blockers(
        issue: Int,
        vararg listed: JsonObject,
    ) = github.on(FakeGitHub.blockedByUrl(issue), FakeGitHub.json(listed.toList()))

    private val dependencyRequests get() = github.requests.map { it.url }.filter { "/dependencies/" in it }

    /** The issues of a poll and of a refresh of the same ids, which must agree. */
    private suspend fun bothReads(vararg ids: String): List<List<Issue>> {
        val listed = tracker().fetchIssuesByStates(listOf("open")).getOrThrow()
        val byId = tracker().fetchIssuesByIds(ids.toList()).getOrThrow()
        return listOf(listed, byId)
    }

    @Test
    fun `an issue with no blocker sends no dependency request`() =
        runTest {
            simulate(candidate(1, total = 0))

            for (issues in bothReads("1")) {
                val issue = issues.single()
                assertTrue(issue.dispatchable, "$issue")
                assertEquals(emptyList(), issue.blockedBy)
            }
            assertEquals(emptyList(), dependencyRequests)
        }

    @Test
    fun `an open blocker holds the issue, from both reads`() =
        runTest {
            simulate(candidate(1, total = 1))
            blockers(1, blocker(12))

            for (issues in bothReads("1")) {
                val issue = issues.single()
                assertFalse(issue.dispatchable)
                assertEquals(listOf("12"), issue.blockedBy)
                assertEquals("blocked_by #12 (open)", issue.blockedReason)
            }
            assertEquals(List(2) { FakeGitHub.blockedByUrl(1) }, dependencyRequests, "one request per read")
        }

    @Test
    fun `only closed as completed resolves a blocker, the first one that holds gives the reason`() =
        runTest {
            val cases =
                listOf(
                    listOf(blocker(12, "closed", "completed"), blocker(13, "closed", "completed")) to null,
                    listOf(blocker(12, "closed", "completed"), blocker(13, "closed", "not_planned")) to
                        "blocked_by #13 (closed, not_planned)",
                    listOf(blocker(12, "closed", "duplicate"), blocker(13, "open")) to "blocked_by #12 (closed, duplicate)",
                    listOf(blocker(12, "open"), blocker(13, "closed", "not_planned")) to "blocked_by #12 (open)",
                    listOf(blocker(12, "closed"), blocker(13, "closed", "completed")) to "blocked_by #12 (closed)",
                    listOf(blocker(12, "closed", "archived"), blocker(13, "closed", "completed")) to "blocked_by #12 (closed, archived)",
                    listOf(blocker(12, " Closed ", "COMPLETED"), blocker(13, "closed", "completed")) to null,
                )
            for ((listed, reason) in cases) {
                simulate(candidate(1, total = 2))
                blockers(1, *listed.toTypedArray())

                for (issues in bothReads("1")) {
                    val issue = issues.single()
                    assertEquals(reason, issue.blockedReason, "$listed")
                    assertEquals(listOf("12", "13"), issue.blockedBy)
                }
            }
        }

    @Test
    fun `a blocker in another repository is named with its repository`() =
        runTest {
            simulate(candidate(1, total = 2))
            blockers(1, blocker(12, "closed", "completed", repo = "Octocat/hello-world"), blocker(3, repo = "acme/other"))

            for (issues in bothReads("1")) {
                val issue = issues.single()
                assertEquals(listOf("12", "acme/other#3"), issue.blockedBy)
                assertEquals("blocked_by acme/other#3 (open)", issue.blockedReason)
            }
        }

    @Test
    fun `fewer blockers listed than the summary counts keeps the issue blocked`() =
        runTest {
            simulate(candidate(1, total = 2), candidate(2, total = 2))
            blockers(1, blocker(12, "closed", "completed"))
            blockers(2, blocker(13, "open"))

            for (issues in bothReads("1", "2")) {
                assertEquals(
                    listOf(
                        Triple("1", listOf("12"), "blocked_by 1 unreadable"),
                        Triple("2", listOf("13"), "blocked_by #13 (open)"),
                    ),
                    issues.map { Triple(it.id, it.blockedBy, it.blockedReason) },
                )
            }
        }

    @Test
    fun `a failed blocker request blocks that issue only, the others of the poll unaffected`() =
        runTest {
            simulate(
                candidate(1, total = 1),
                candidate(2, total = 1),
                candidate(3, total = 1),
                candidate(4, total = 1),
                candidate(5, total = 0),
            )
            blockers(1, blocker(11, "closed", "completed"))
            github.on(FakeGitHub.blockedByUrl(2), FakeGitHub.response(500, """{"message":"Server Error"}"""))
            github.on(FakeGitHub.blockedByUrl(3), FakeGitHub.recorded("issue-not-found"))
            github.on(FakeGitHub.blockedByUrl(4), FakeGitHub.response(200, """[{"number":"x"}]"""))

            for (issues in bothReads("1", "2", "3", "4", "5")) {
                assertEquals(
                    listOf(
                        "1" to null,
                        "2" to "blocked_by unreadable (500)",
                        "3" to "blocked_by unreadable (404)",
                        "4" to "blocked_by unreadable (malformed)",
                        "5" to null,
                    ),
                    issues.map { it.id to it.blockedReason },
                )
            }
        }

    @Test
    fun `a body that is not a list of issues is malformed`() =
        runTest {
            val bodies =
                listOf(
                    "<html>proxy error</html>",
                    "{}",
                    "[12]",
                    // No repository, so the reference cannot be told.
                    JsonObject(blocker(12) - "repository_url").let { "[$it]" },
                    JsonObject(blocker(12) + ("repository_url" to JsonPrimitive("https://api.github.com/users/octocat"))).let { "[$it]" },
                    JsonObject(blocker(12) - "state").let { "[$it]" },
                )
            for (body in bodies) {
                simulate(candidate(1, total = 1))
                github.on(FakeGitHub.blockedByUrl(1), FakeGitHub.response(200, body))

                for (issues in bothReads("1")) assertEquals("blocked_by unreadable (malformed)", issues.single().blockedReason, body)
            }
        }

    @Test
    fun `no summary counts as no blocker, a malformed one blocks, neither sends a request`() =
        runTest {
            val malformed =
                listOf(
                    JsonNull,
                    JsonPrimitive("2"),
                    JsonObject(mapOf("blocked_by" to JsonPrimitive(1))),
                    JsonObject(mapOf("total_blocked_by" to JsonPrimitive("1"))),
                    JsonObject(mapOf("total_blocked_by" to JsonPrimitive(-1))),
                    JsonObject(mapOf("total_blocked_by" to JsonPrimitive(1.5))),
                )
            val issues =
                listOf(FakeGitHub.issue(1, labels = ready, totalBlockedBy = null)) +
                    malformed.mapIndexed { i, summary ->
                        JsonObject(FakeGitHub.issue(2 + i, labels = ready) + ("issue_dependencies_summary" to summary))
                    }
            simulate(*issues.toTypedArray())

            for (read in bothReads(*(1..issues.size).map { "$it" }.toTypedArray())) {
                assertEquals(
                    listOf("1" to null) + malformed.indices.map { "${2 + it}" to "blocked_by unreadable summary" },
                    read.map { it.id to it.blockedReason },
                )
            }
            assertEquals(emptyList(), dependencyRequests)
        }

    @Test
    fun `a rate limit on a blocker request fails the call like any other request`() =
        runTest {
            simulate(candidate(1, total = 1))
            github.on(FakeGitHub.blockedByUrl(1), FakeGitHub.recorded("secondary-rate-limited"))

            assertIs<TrackerError.RateLimited>(tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull())
            assertIs<TrackerError.RateLimited>(tracker().fetchIssuesByIds(listOf("1")).exceptionOrNull())
        }

    @Test
    fun `a poll asks at most once per candidate, never for one with no blocker, an untrusted author, no required label or closed`() =
        runTest {
            simulate(
                candidate(1, total = 0),
                candidate(2, total = 1),
                candidate(3, total = 2, authorAssociation = "NONE"),
                FakeGitHub.issue(4, labels = listOf("needs-triage"), totalBlockedBy = 1),
                FakeGitHub.issue(5, state = "closed", labels = ready, totalBlockedBy = 1),
                candidate(6, total = 3),
            )
            blockers(2, blocker(12))
            blockers(6, blocker(13, "closed", "completed"), blocker(14, "closed", "completed"), blocker(15, "closed", "completed"))

            val issues = tracker().fetchIssuesByStates(listOf("open", "closed")).getOrThrow()

            assertEquals(listOf(FakeGitHub.blockedByUrl(2), FakeGitHub.blockedByUrl(6)), dependencyRequests)
            assertEquals(
                listOf(
                    "1" to null,
                    "2" to "blocked_by #12 (open)",
                    "3" to "untrusted_author (NONE)",
                    "4" to null,
                    "5" to null,
                    "6" to null,
                ),
                issues.map { it.id to it.blockedReason },
            )
            assertEquals(listOf("13", "14", "15"), issues.last().blockedBy)
        }

    @Test
    fun `with no required label every open trusted issue with a blocker is asked`() =
        runTest {
            simulate(FakeGitHub.issue(1, totalBlockedBy = 1))
            blockers(1, blocker(12))

            val issue =
                tracker(required = emptyList())
                    .fetchIssuesByStates(listOf("open"))
                    .getOrThrow()
                    .single()

            assertEquals("blocked_by #12 (open)", issue.blockedReason)
            assertEquals(listOf(FakeGitHub.blockedByUrl(1)), dependencyRequests)
        }
}
