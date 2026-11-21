package loop.tracker.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.TrackerError
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GitHubTrackerTest {
    private val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"
    private val github = FakeGitHub()
    private val clock = TestClock(Instant.parse("2026-11-12T15:00:00Z"))
    private val malformed = mutableListOf<TrackerError.Malformed>()

    private fun tracker(
        repo: String = "Hello-World",
        labels: List<String> = emptyList(),
        pageSize: Int = 100,
        apiUrl: String = FakeGitHub.API,
    ) = GitHubTracker(
        GitHubTrackerSettings(owner = "octocat", repo = repo, token = token, apiUrl = URI(apiUrl), scopeLabels = labels),
        github,
        clock,
        onMalformed = { malformed += it },
        pageSize = pageSize,
    )

    private fun simulate(vararg issues: JsonObject) {
        github.fallback = SimulatedIssues(issues.toList())
    }

    private val firstPage = FakeGitHub.listUrl("open")

    private val secondPage =
        FakeGitHub
            .recorded("open-issues-page-1")
            .header("link")!!
            .substringAfter('<')
            .substringBefore('>')

    @Test
    fun `17_3 item 4 recorded pages are followed by their next link and keep their order`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("open-issues-page-1"))
            github.on(secondPage, FakeGitHub.recorded("open-issues-page-2"))

            val issues = tracker().fetchIssuesByStates(listOf("open")).getOrThrow()

            assertEquals(listOf("26", "27", "28", "29", "30", "31"), issues.map { it.id })
            assertEquals(listOf(firstPage, secondPage), github.requests.map { it.url })
            val first = issues.first()
            assertEquals("Hello-World-26", first.identifier)
            assertEquals("test issue", first.title)
            assertEquals("open", first.state)
            assertEquals(Instant.parse("2012-03-18T17:53:15Z"), first.createdAt)
            assertEquals(emptyList(), first.labels)
            assertNull(first.priority)
            // bl-157: the recorded issue is by an author with no access to the repository.
            assertEquals("untrusted_author (NONE)", first.blockedReason)
            assertFalse(first.dispatchable)
            assertEquals(emptyList(), first.blockedBy)
        }

    @Test
    fun `bl-157 an issue by an untrusted or unknown author keeps its labels and state but is blocked, from both reads`() =
        runTest {
            simulate(
                FakeGitHub.issue(1, labels = listOf("ready-for-agent"), authorAssociation = "OWNER"),
                FakeGitHub.issue(2, labels = listOf("ready-for-agent"), authorAssociation = "NONE"),
                FakeGitHub.issue(3, labels = listOf("ready-for-agent"), authorAssociation = "CONTRIBUTOR"),
                FakeGitHub.issue(4, labels = listOf("ready-for-agent"), authorAssociation = null),
                FakeGitHub.issue(5, labels = listOf("ready-for-agent"), authorAssociation = "collaborator"),
            )
            val expected =
                listOf(
                    "1" to null,
                    "2" to "untrusted_author (NONE)",
                    "3" to "untrusted_author (CONTRIBUTOR)",
                    "4" to "untrusted_author (missing)",
                    "5" to null,
                )

            val listed = tracker().fetchIssuesByStates(listOf("open")).getOrThrow()
            val byId = tracker().fetchIssuesByIds(listOf("1", "2", "3", "4", "5")).getOrThrow()

            for (issues in listOf(listed, byId)) {
                assertEquals(expected, issues.map { it.id to it.blockedReason })
                assertTrue(issues.all { it.labels == listOf("ready-for-agent") && it.state == "open" }, "$issues")
            }
            assertEquals(emptyList(), malformed)
        }

    @Test
    fun `bl-157 the author check follows the configured list`() =
        runTest {
            simulate(FakeGitHub.issue(1, authorAssociation = "CONTRIBUTOR"), FakeGitHub.issue(2, authorAssociation = "COLLABORATOR"))
            val tracker =
                GitHubTracker(
                    GitHubTrackerSettings(
                        owner = "octocat",
                        repo = "Hello-World",
                        token = token,
                        apiUrl = URI(FakeGitHub.API),
                        trustedAssociations = listOf("OWNER", "CONTRIBUTOR"),
                    ),
                    github,
                    clock,
                )

            val issues = tracker.fetchIssuesByStates(listOf("open")).getOrThrow()

            assertEquals(listOf(null, "untrusted_author (COLLABORATOR)"), issues.map { it.blockedReason })
        }

    @Test
    fun `every request carries the token, the api version and the json media type`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("open-issues-page-1"))
            github.on(secondPage, FakeGitHub.recorded("open-issues-page-2"))

            tracker().fetchIssuesByStates(listOf("open")).getOrThrow()

            for (request in github.requests) {
                assertEquals("Bearer $token", request.headers["Authorization"])
                assertEquals("application/vnd.github+json", request.headers["Accept"])
                assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
                assertEquals("loop", request.headers["User-Agent"])
            }
        }

    @Test
    fun `17_3 item 9 GitHub's distinct ids are kept in the native ref`() =
        runTest {
            github.on(FakeGitHub.issueUrl(26), FakeGitHub.recorded("issue-26"))

            val issue = tracker().fetchIssuesByIds(listOf("26")).getOrThrow().single()

            assertEquals("26", issue.id)
            assertEquals(
                mapOf(
                    "repo" to "octocat/Hello-World",
                    "number" to "26",
                    "node_id" to "MDU6SXNzdWUzNzAxMzcw",
                    "database_id" to "3701370",
                    "url" to "https://github.com/octocat/Hello-World/issues/26",
                ),
                issue.nativeRef,
            )
        }

    @Test
    fun `refresh by id returns the same snapshot as the list`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("open-issues-page-1"))
            github.on(secondPage, FakeGitHub.recorded("open-issues-page-2"))
            github.on(FakeGitHub.issueUrl(26), FakeGitHub.recorded("issue-26"))

            val listed = tracker().fetchIssuesByStates(listOf("open")).getOrThrow().first()
            val refreshed = tracker().fetchIssuesByIds(listOf("26")).getOrThrow().single()

            assertEquals(listed, refreshed)
        }

    @Test
    fun `a pull request is never an issue, in a list or by id`() =
        runTest {
            val pull = FakeGitHub.recordedJson("pull-request-1") as JsonObject
            github.on(FakeGitHub.listUrl("all"), FakeGitHub.json(listOf(pull, FakeGitHub.issue(2, state = "closed"))))
            github.on(FakeGitHub.issueUrl(1), FakeGitHub.recorded("pull-request-1"))

            val listed = tracker().fetchIssuesByStates(listOf("open", "closed")).getOrThrow()
            val byId = tracker().fetchIssuesByIds(listOf("1")).getOrThrow()

            assertEquals(listOf("2"), listed.map { it.id })
            assertEquals(emptyList(), byId)
            assertEquals(emptyList(), malformed)
        }

    @Test
    fun `states map to the state query, case insensitively, and unknown states ask nothing`() =
        runTest {
            simulate(FakeGitHub.issue(1), FakeGitHub.issue(2, state = "closed"))

            assertEquals(listOf("1"), tracker().fetchIssuesByStates(listOf(" OPEN ")).getOrThrow().map { it.id })
            assertEquals(listOf("2"), tracker().fetchIssuesByStates(listOf("closed")).getOrThrow().map { it.id })
            assertEquals(listOf("1", "2"), tracker().fetchIssuesByStates(listOf("Open", "closed")).getOrThrow().map { it.id })
            assertEquals(
                listOf(FakeGitHub.listUrl("open"), FakeGitHub.listUrl("closed"), FakeGitHub.listUrl("all")),
                github.requests.map { it.url },
            )

            assertEquals(emptyList(), tracker().fetchIssuesByStates(listOf("todo")).getOrThrow())
            assertEquals(3, github.requests.size)
        }

    @Test
    fun `scope labels filter the list on GitHub and hide an issue out of scope by id`() =
        runTest {
            simulate(FakeGitHub.issue(1, labels = listOf("Loop", "iOS")), FakeGitHub.issue(2, labels = listOf("loop")))
            val scoped = tracker(labels = listOf("loop", "ios"))

            val listed = scoped.fetchIssuesByStates(listOf("open")).getOrThrow()
            val byId = scoped.fetchIssuesByIds(listOf("1", "2")).getOrThrow()

            assertEquals(FakeGitHub.listUrl("open", labels = listOf("loop", "ios")), github.requests.first().url)
            assertEquals(listOf("1"), listed.map { it.id })
            assertEquals(listOf("1"), byId.map { it.id })
        }

    @Test
    fun `fetch by ids omits unknown and non numeric ids and asks once per distinct id`() =
        runTest {
            simulate(FakeGitHub.issue(5), FakeGitHub.issue(6, state = "closed"))

            val issues = tracker().fetchIssuesByIds(listOf("6", "5", "6", "404", "c-1", "-3", "0")).getOrThrow()

            assertEquals(setOf("5", "6"), issues.map { it.id }.toSet())
            assertEquals(issues.size, issues.map { it.id }.distinct().size)
            assertEquals(
                setOf(FakeGitHub.issueUrl(6), FakeGitHub.issueUrl(5), FakeGitHub.issueUrl(404), FakeGitHub.repoUrl()),
                github.requests.map { it.url }.toSet(),
            )
            assertEquals(4, github.requests.size, "one request per distinct id, plus the repository for the missing one")
        }

    @Test
    fun `bl-22 a deleted or missing issue is gone when the repository itself answers`() =
        runTest {
            github.on(FakeGitHub.issueUrl(8), FakeGitHub.response(410, """{"message":"This issue was deleted"}"""))
            github.on(FakeGitHub.issueUrl(9), FakeGitHub.recorded("issue-not-found"))
            github.on(FakeGitHub.repoUrl(), FakeGitHub.recorded("repository"))

            assertEquals(emptyList(), tracker().fetchIssuesByIds(listOf("8", "9")).getOrThrow())
            assertEquals(
                listOf(FakeGitHub.issueUrl(8), FakeGitHub.repoUrl(), FakeGitHub.issueUrl(9)),
                github.requests.map { it.url },
                "the repository is asked once per call",
            )
        }

    @Test
    fun `bl-22 a missing issue in a repository that does not answer is a tracker error, never gone`() =
        runTest {
            val repoAnswers =
                listOf(
                    // A token that lost access to a private repository sees it as not found.
                    FakeGitHub.recorded("issue-not-found"),
                    FakeGitHub.response(
                        301,
                        """{"message":"Moved Permanently"}""",
                        "location" to "https://api.github.com/repositories/2",
                    ),
                    FakeGitHub.response(401, """{"message":"Bad credentials"}"""),
                    FakeGitHub.response(403, """{"message":"Resource not accessible by personal access token"}"""),
                    FakeGitHub.response(500, "oops"),
                )
            for (answer in repoAnswers) {
                val github = FakeGitHub()
                github.on(FakeGitHub.issueUrl(9), FakeGitHub.recorded("issue-not-found"))
                github.on(FakeGitHub.repoUrl(), answer)
                val tracker =
                    GitHubTracker(GitHubTrackerSettings(owner = "octocat", repo = "Hello-World", token = token), github, clock)

                val error = assertIs<TrackerError.Status>(tracker.fetchIssuesByIds(listOf("9")).exceptionOrNull(), "$answer")
                assertEquals(answer.status, error.status)
                assertFalse(token in error.message!!)
            }
        }

    @Test
    fun `bl-22 a repository that cannot be reached or is rate limited fails the call too`() =
        runTest {
            github.on(FakeGitHub.issueUrl(9), FakeGitHub.recorded("issue-not-found"))
            github.on(FakeGitHub.repoUrl(), FakeGitHub.recorded("secondary-rate-limited"))

            assertIs<TrackerError.RateLimited>(tracker().fetchIssuesByIds(listOf("9")).exceptionOrNull())
        }

    @Test
    fun `bl-22 a moved issue is a tracker error, so a renamed repository never cancels attempts`() =
        runTest {
            github.on(
                FakeGitHub.issueUrl(7),
                FakeGitHub.response(
                    301,
                    """{"message":"Moved Permanently"}""",
                    "location" to "https://api.github.com/repositories/2/issues/7",
                ),
            )
            github.on(FakeGitHub.repoUrl(), FakeGitHub.recorded("repository"))

            val error = assertIs<TrackerError.Status>(tracker().fetchIssuesByIds(listOf("7")).exceptionOrNull())
            assertEquals(301, error.status)
            assertEquals("The tracker answered 301: Moved Permanently", error.message)
            assertEquals(listOf(FakeGitHub.issueUrl(7)), github.requests.map { it.url })
        }

    @Test
    fun `labels are trimmed, lowercased, deduplicated and blanks dropped`() =
        runTest {
            simulate(FakeGitHub.issue(1, labels = listOf(" Ready-For-Agent ", "ready-for-agent", " ", "AFK")))

            assertEquals(
                listOf("ready-for-agent", "afk"),
                tracker()
                    .fetchIssuesByStates(listOf("open"))
                    .getOrThrow()
                    .single()
                    .labels,
            )
        }

    @Test
    fun `a priority label gives the priority, the lowest wins, and it is not repeated in the labels`() =
        runTest {
            simulate(
                FakeGitHub.issue(1, labels = listOf("bug", "Priority: 3", "priority:1")),
                FakeGitHub.issue(2, labels = listOf("priority:high", "priority:")),
            )

            val (first, second) = tracker().fetchIssuesByStates(listOf("open")).getOrThrow()

            assertEquals(1, first.priority)
            assertEquals(listOf("bug"), first.labels)
            assertNull(second.priority)
            assertEquals(listOf("priority:high", "priority:"), second.labels)
        }

    @Test
    fun `17_3 item 6 unusable optional fields normalize to null and keep the required ones`() =
        runTest {
            val odd =
                JsonObject(
                    FakeGitHub.issue(1, body = "  ") +
                        mapOf(
                            "created_at" to JsonPrimitive("yesterday"),
                            "labels" to JsonPrimitive("not a list"),
                            "node_id" to JsonPrimitive(7),
                        ),
                )
            github.on(firstPage, FakeGitHub.json(listOf(odd)))

            val issue = tracker().fetchIssuesByStates(listOf("open")).getOrThrow().single()

            assertEquals("1", issue.id)
            assertEquals("Title of 1", issue.title)
            assertNull(issue.description)
            assertNull(issue.createdAt)
            assertEquals(emptyList(), issue.labels)
            assertFalse("node_id" in issue.nativeRef.orEmpty())
        }

    @Test
    fun `17_3 item 7 a list skips a malformed issue and reports it, a refresh fails on it`() =
        runTest {
            val noTitle = JsonObject(FakeGitHub.issue(2) + ("title" to JsonPrimitive("  ")))
            val noNumber = JsonObject(FakeGitHub.issue(3) - "number")
            simulate(FakeGitHub.issue(1), noTitle, noNumber)
            github.on(FakeGitHub.issueUrl(2), FakeGitHub.response(200, noTitle.toString()))

            val listed = tracker().fetchIssuesByStates(listOf("open")).getOrThrow()
            val refreshed = tracker().fetchIssuesByIds(listOf("1", "2"))

            assertEquals(listOf("1"), listed.map { it.id })
            assertEquals(2, malformed.size)
            assertIs<TrackerError.Malformed>(refreshed.exceptionOrNull(), refreshed.toString())
        }

    @Test
    fun `a body that is not the expected json fails the call as malformed`() =
        runTest {
            github.on(firstPage, FakeGitHub.response(200, "<html>proxy error</html>"))
            github.on(FakeGitHub.issueUrl(1), FakeGitHub.response(200, "[]"))

            assertIs<TrackerError.Malformed>(tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull())
            assertIs<TrackerError.Malformed>(tracker().fetchIssuesByIds(listOf("1")).exceptionOrNull())
        }

    @Test
    fun `17_3 item 12 a recorded rate limit response fails the call and holds requests until the reset`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("rate-limited"), FakeGitHub.recorded("open-issues-page-2"))
            val tracker = tracker()

            val limited = tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull()
            clock.now = Instant.fromEpochSeconds(1794500000) - 1.seconds
            val held = tracker.fetchIssuesByIds(listOf("26")).exceptionOrNull()
            val requestsWhileHeld = github.requests.size
            clock.now = Instant.fromEpochSeconds(1794500000)
            val resumed = tracker.fetchIssuesByStates(listOf("open"))

            assertIs<TrackerError.RateLimited>(limited)
            assertEquals(Instant.fromEpochSeconds(1794500000), limited.retryAt)
            assertIs<TrackerError.RateLimited>(held)
            assertEquals(1, requestsWhileHeld)
            assertEquals(listOf("29", "30", "31"), resumed.getOrThrow().map { it.id })
        }

    @Test
    fun `a recorded secondary rate limit holds requests for its retry after`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("secondary-rate-limited"), FakeGitHub.recorded("open-issues-page-2"))
            val tracker = tracker()

            val limited = tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull()
            clock.advance(59.seconds)
            val held = tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull()
            clock.advance(1.seconds)
            val resumed = tracker.fetchIssuesByStates(listOf("open"))

            assertIs<TrackerError.RateLimited>(limited)
            assertEquals(Instant.parse("2026-11-12T15:01:00Z"), limited.retryAt)
            assertIs<TrackerError.RateLimited>(held)
            assertTrue(resumed.isSuccess, resumed.toString())
            assertEquals(2, github.requests.size)
        }

    @Test
    fun `a success that spends the last request holds the next ones until the reset`() =
        runTest {
            val reset = Instant.parse("2026-11-12T15:30:00Z")
            val last =
                FakeGitHub.recorded("issue-26").let {
                    it.copy(
                        headers =
                            it.headers +
                                mapOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("${reset.epochSeconds}")),
                    )
                }
            github.on(FakeGitHub.issueUrl(26), last)
            val tracker = tracker()

            val served = tracker.fetchIssuesByIds(listOf("26"))
            val held = tracker.fetchIssuesByIds(listOf("26")).exceptionOrNull()

            assertTrue(served.isSuccess, served.toString())
            assertIs<TrackerError.RateLimited>(held)
            assertEquals(reset, held.retryAt)
            assertEquals(1, github.requests.size)
        }

    @Test
    fun `a 403 without rate limit headers is a status error, as are 401 and 500, without the token`() =
        runTest {
            github.on(
                FakeGitHub.issueUrl(1),
                FakeGitHub.response(403, """{"message":"Resource not accessible by personal access token"}"""),
            )
            github.on(FakeGitHub.issueUrl(2), FakeGitHub.response(401, """{"message":"Bad credentials"}"""))
            github.on(FakeGitHub.issueUrl(3), FakeGitHub.response(500, "oops"))

            val forbidden = assertIs<TrackerError.Status>(tracker().fetchIssuesByIds(listOf("1")).exceptionOrNull())
            val unauthorized = assertIs<TrackerError.Status>(tracker().fetchIssuesByIds(listOf("2")).exceptionOrNull())
            val broken = assertIs<TrackerError.Status>(tracker().fetchIssuesByIds(listOf("3")).exceptionOrNull())

            assertEquals(403, forbidden.status)
            assertEquals("The tracker answered 403: Resource not accessible by personal access token", forbidden.message)
            assertEquals("The tracker answered 401: Bad credentials", unauthorized.message)
            assertEquals("The tracker answered 500: oops", broken.message)
            for (error in listOf(forbidden, unauthorized, broken)) assertFalse(token in error.message!!)
        }

    @Test
    fun `a failure on a later page fails the whole call`() =
        runTest {
            github.on(firstPage, FakeGitHub.recorded("open-issues-page-1"))
            github.on(secondPage, FakeGitHub.response(502, """{"message":"Server Error"}"""))

            assertIs<TrackerError.Status>(tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull())
        }

    @Test
    fun `17_3 item 12 a next link to another origin fails the call and the token never goes there`() =
        runTest {
            val elsewhere = "https://evil.example.com/repositories/1/issues?page=2"
            github.on(firstPage, FakeGitHub.json(listOf(FakeGitHub.issue(1)), "link" to "<$elsewhere>; rel=\"next\""))

            val error = tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull()

            assertIs<TrackerError.Pagination>(error)
            assertEquals(listOf(firstPage), github.requests.map { it.url })
        }

    @Test
    fun `a next link that repeats fails the call`() =
        runTest {
            val again = "${FakeGitHub.API}/repositories/1/issues?page=2"
            github.on(firstPage, FakeGitHub.json(listOf(FakeGitHub.issue(1)), "link" to "<$again>; rel=\"next\""))
            github.on(again, FakeGitHub.json(listOf(FakeGitHub.issue(2)), "link" to "<$again>; rel=\"next\""))

            assertIs<TrackerError.Pagination>(tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull())
            assertEquals(2, github.requests.size)
        }

    @Test
    fun `an issue seen on two pages means the list moved, and fails the call`() =
        runTest {
            val next = "${FakeGitHub.API}/repositories/1/issues?page=2"
            github.on(firstPage, FakeGitHub.json(listOf(FakeGitHub.issue(1), FakeGitHub.issue(2)), "link" to "<$next>; rel=\"next\""))
            github.on(next, FakeGitHub.json(listOf(FakeGitHub.issue(2), FakeGitHub.issue(3))))

            assertIs<TrackerError.Pagination>(tracker().fetchIssuesByStates(listOf("open")).exceptionOrNull())
        }

    @Test
    fun `small pages are followed to the end, and more than the page cap fails the call`() =
        runTest {
            simulate(*(1..GitHubTracker.MAX_PAGES).map { FakeGitHub.issue(it) }.toTypedArray())

            val all = tracker(pageSize = 1).fetchIssuesByStates(listOf("open")).getOrThrow()

            assertEquals((1..GitHubTracker.MAX_PAGES).map { "$it" }, all.map { it.id })
            assertEquals(FakeGitHub.listUrl("open", perPage = 1), github.requests.first().url)

            simulate(*(1..GitHubTracker.MAX_PAGES + 1).map { FakeGitHub.issue(it) }.toTypedArray())
            github.requests.clear()

            assertIs<TrackerError.Pagination>(tracker(pageSize = 1).fetchIssuesByStates(listOf("open")).exceptionOrNull())
            assertEquals(GitHubTracker.MAX_PAGES, github.requests.size)
        }

    @Test
    fun `an enterprise api url is used as the base of every request`() =
        runTest {
            val base = "https://ghe.example.com/api/v3"
            github.fallback = { FakeGitHub.json(emptyList()) }

            tracker(apiUrl = base).fetchIssuesByStates(listOf("open")).getOrThrow()

            assertEquals(
                "$base/repos/octocat/Hello-World/issues?state=open&sort=created&direction=asc&per_page=100",
                github.requests.single().url,
            )
        }

    @Test
    fun `an unreachable server is a request error`() =
        runTest {
            github.unreachable = true

            val error = tracker().fetchIssuesByIds(listOf("1")).exceptionOrNull()

            assertIs<TrackerError.Unavailable>(error)
            assertEquals("Connection refused", error.message)
        }

    @Test
    fun `17_3 the published adapter profile covers config, scope, normalization, states and errors`() {
        val profile = Path.of(System.getProperty("loop.adapterProfile")).readText()
        val headings = profile.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

        for (section in listOf(
            "Configuration",
            "Scope",
            "Pagination",
            "Rate limits",
            "Normalization",
            "States",
            "Writes",
            "Errors",
            "Known gaps",
        )) {
            assertTrue(section in headings, "the profile has no \"## $section\" section: $headings")
        }
        for (key in listOf("repo", "token", "api_url", "labels", "handoff_labels", "trusted_associations")) {
            assertTrue("| `tracker.provider.$key` |" in profile, "the profile does not document tracker.provider.$key")
        }
        for (state in GitHubTracker.DEFINED_STATES) {
            assertTrue("| `$state` |" in profile, "the profile does not document the state $state")
        }
        for (error in listOf("Unavailable", "Status", "Malformed", "Pagination", "RateLimited", "Rejected", "UndefinedState", "NotFound")) {
            assertTrue("| `$error` |" in profile, "the profile does not map the error $error")
        }
        for (category in listOf("invalid_tracker_config", "missing_tracker_secret")) {
            assertTrue("`$category`" in profile, "the profile does not name the config error $category")
        }
    }
}

/** A clock a test moves by hand. */
class TestClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}
