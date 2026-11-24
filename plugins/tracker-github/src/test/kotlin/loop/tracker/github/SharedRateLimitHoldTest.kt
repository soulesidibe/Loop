package loop.tracker.github

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import loop.api.DeliveryError
import loop.api.IssueComment
import loop.api.TrackerError
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m4-48, bl-50: one rate limit hold per API origin and token, shared by the GitHub tracker, pull request delivery and
 * the CI wait's polls through one [GitHubClients] per run. A held tracker read stays [TrackerError.RateLimited], a held
 * delivery step [DeliveryError.Unavailable], and nothing is sent while the hold lasts.
 */
class SharedRateLimitHoldTest {
    @TempDir
    lateinit var tmp: Path

    private val token = "ghp_m448SharedHoldToken0123456789abcdefgh"
    private val otherToken = "ghp_m448OtherHoldToken0123456789abcdefghi"
    private val clock = TestClock(Instant.parse("2026-11-24T10:00:00Z"))
    private val reset = Instant.parse("2026-11-24T10:20:00Z")
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    /** Every request, as `<method> <url>`, and the token it carried. */
    private val sent = mutableListOf<Pair<String, String?>>()

    /** Set to make the next answer spend the last request of the window, until [windowReset]. */
    private var spendLast = false

    /** The reset time of the window GitHub's answers name. */
    private var windowReset = reset

    /** A GitHub that answers every read the tracker and delivery make in these tests. */
    private val transport =
        GitHubTransport { method, url, headers, _ ->
            sent += "$method $url" to headers["Authorization"]
            val path = url.path
            val body =
                when {
                    path.endsWith("/issues/26") -> FakeGitHub.issue(26).toString()
                    path.endsWith("/pulls") -> "[]"
                    path.endsWith("/jobs") -> """{"total_count":0,"jobs":[]}"""
                    path.endsWith("/actions/runs") -> """{"total_count":0,"workflow_runs":[]}"""
                    else -> "{}"
                }
            val limit =
                if (spendLast) {
                    spendLast = false
                    arrayOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${windowReset.epochSeconds}")
                } else {
                    arrayOf("x-ratelimit-remaining" to "4999", "x-ratelimit-reset" to "${windowReset.epochSeconds}")
                }
            FakeGitHub.response(200, body, *limit)
        }

    private val clients = GitHubClients(transport, clock)

    private fun tracker(
        apiUrl: String = FakeGitHub.API,
        token: String = this.token,
        shared: GitHubClients = clients,
    ) = GitHubTracker(
        GitHubTrackerSettings(owner = "octocat", repo = "Hello-World", token = token, apiUrl = URI(apiUrl)),
        clients = shared,
    )

    private fun delivery(
        apiUrl: String = FakeGitHub.API,
        token: String = this.token,
        shared: GitHubClients = clients,
    ) = GitHubDelivery(
        owner = "octocat",
        repo = "Hello-World",
        token = token,
        projectDir = tmp,
        apiUrl = URI(apiUrl),
        env = mapOf("PATH" to "/usr/bin:/bin", "HOME" to tmp.toString()),
        clients = shared,
    )

    @Test
    fun `a tracker read that spends the last request holds the next pull request call, which sends nothing`() =
        runTest {
            val tracker = tracker()
            val delivery = delivery()

            spendLast = true
            assertTrue(tracker.fetchIssuesByIds(listOf("26")).isSuccess)
            val held = delivery.findPullRequest("loop/m4-48").exceptionOrNull()

            assertIs<DeliveryError.Unavailable>(held)
            val cause = assertIs<TrackerError.RateLimited>(held.cause)
            assertEquals(reset, cause.retryAt)
            assertEquals(1, sent.size, "only the tracker read was sent: $sent")
        }

    @Test
    fun `a pull request call that spends the last request holds the next tracker read, which sends nothing`() =
        runTest {
            val tracker = tracker()
            val delivery = delivery()

            spendLast = true
            assertNull(delivery.findPullRequest("loop/m4-48").getOrThrow())
            val held = tracker.fetchIssuesByIds(listOf("26")).exceptionOrNull()
            val writeHeld = tracker.addComment("26", IssueComment("loop", clock.now, "Still here.")).exceptionOrNull()

            assertEquals(reset, assertIs<TrackerError.RateLimited>(held).retryAt)
            assertIs<TrackerError.RateLimited>(writeHeld)
            assertEquals(1, sent.size, "only the pull request lookup was sent: $sent")
        }

    @Test
    fun `a rate limit answer to a tracker read holds delivery until its retry after`() =
        runTest {
            val limited =
                GitHubClients(
                    GitHubTransport { method, url, headers, _ ->
                        sent += "$method $url" to headers["Authorization"]
                        if (url.path.endsWith("/issues/26")) {
                            FakeGitHub.response(429, """{"message":"secondary rate limit"}""", "retry-after" to "90")
                        } else {
                            FakeGitHub.response(200, "[]")
                        }
                    },
                    clock,
                )

            val read = tracker(shared = limited).fetchIssuesByIds(listOf("26")).exceptionOrNull()
            val held = delivery(shared = limited).findPullRequest("loop/m4-48").exceptionOrNull()
            clock.advance(90.seconds)
            val after = delivery(shared = limited).findPullRequest("loop/m4-48")

            assertIs<TrackerError.RateLimited>(read)
            assertIs<DeliveryError.Unavailable>(held)
            assertTrue(after.isSuccess, after.toString())
            assertEquals(2, sent.size, "the read, then the lookup once the hold ended: $sent")
        }

    @Test
    fun `the hold ends at the reset time`() =
        runTest {
            val tracker = tracker()
            val delivery = delivery()

            spendLast = true
            tracker.fetchIssuesByIds(listOf("26")).getOrThrow()
            clock.now = reset - 1.seconds
            assertIs<DeliveryError.Unavailable>(delivery.findPullRequest("loop/m4-48").exceptionOrNull())
            assertEquals(1, sent.size)
            clock.now = reset
            val resumed = delivery.findPullRequest("loop/m4-48")

            assertTrue(resumed.isSuccess, resumed.toString())
            assertEquals(2, sent.size)
        }

    @Test
    fun `two tokens do not hold each other`() =
        runTest {
            spendLast = true
            tracker().fetchIssuesByIds(listOf("26")).getOrThrow()
            val other = delivery(token = otherToken).findPullRequest("loop/m4-48")

            assertTrue(other.isSuccess, other.toString())
            assertEquals(listOf("Bearer $token", "Bearer $otherToken"), sent.map { it.second })
        }

    @Test
    fun `two origins do not hold each other, and one origin is one hold whatever its default port`() =
        runTest {
            spendLast = true
            tracker().fetchIssuesByIds(listOf("26")).getOrThrow()
            val elsewhere = delivery(apiUrl = "https://ghe.example.com/api/v3").findPullRequest("loop/m4-48")
            val samePort = delivery(apiUrl = "https://API.github.com:443").findPullRequest("loop/m4-48")

            assertTrue(elsewhere.isSuccess, elsewhere.toString())
            assertIs<DeliveryError.Unavailable>(samePort.exceptionOrNull(), "the same origin with its default port written out")
            assertEquals(2, sent.size, sent.toString())
        }

    @Test
    fun `the CI wait's polls respect the hold a tracker read set, and the other way round`() =
        runTest {
            val tracker = tracker()
            val delivery = delivery()

            spendLast = true
            tracker.fetchIssuesByIds(listOf("26")).getOrThrow()
            val runs = delivery.actionsRuns(sha).exceptionOrNull()
            val jobs = delivery.actionsJobs(7).exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(runs)
            assertIs<DeliveryError.Unavailable>(jobs)
            assertEquals(1, sent.size, sent.toString())

            // The next window: a poll of the CI wait spends its last request.
            clock.now = reset
            windowReset = reset + 1.hours
            spendLast = true
            assertEquals(0, delivery.actionsRuns(sha).getOrThrow().totalCount)
            val read = tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull()
            assertEquals(windowReset, assertIs<TrackerError.RateLimited>(read).retryAt)
            assertEquals(2, sent.size, sent.toString())
        }

    @Test
    fun `one client per API URL and token, and one hold per origin and token`() {
        val first = clients.client(URI(FakeGitHub.API), token)

        assertSame(first, clients.client(URI(FakeGitHub.API), token))
        assertNotSame(first, clients.client(URI(FakeGitHub.API), otherToken))
        assertNotSame(first, clients.client(URI("https://ghe.example.com/api/v3"), token))
    }

    @Test
    fun `a clear never overwrites a newer hold`() =
        runTest {
            // The answer to the first read waits until the test releases it.
            val release = CompletableDeferred<Unit>()
            val racing =
                GitHubClients(
                    GitHubTransport { method, url, headers, _ ->
                        sent += "$method $url" to headers["Authorization"]
                        if (url.path.endsWith("/issues/26")) {
                            release.await()
                            FakeGitHub.response(200, FakeGitHub.issue(26).toString(), "x-ratelimit-remaining" to "4000")
                        } else {
                            FakeGitHub.response(429, """{"message":"secondary rate limit"}""", "retry-after" to "60")
                        }
                    },
                    clock,
                )
            val tracker = tracker(shared = racing)
            val delivery = delivery(shared = racing)

            // The tracker read is sent before the hold, and answered after it with requests to spare.
            val slow = async { tracker.fetchIssuesByIds(listOf("26")) }
            yield()
            assertIs<DeliveryError.Unavailable>(delivery.findPullRequest("loop/m4-48").exceptionOrNull())
            release.complete(Unit)
            assertTrue(slow.await().isSuccess)

            val held = delivery.actionsRuns(sha).exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(held)
            assertEquals(clock.now + 60.seconds, assertIs<TrackerError.RateLimited>(held.cause).retryAt)
            assertEquals(2, sent.size, "the late answer did not clear the newer hold: $sent")
        }

    @Test
    fun `a later hold is never shortened by an earlier one`() {
        val hold = RateLimitHold(clock)
        hold.hold(reset)
        hold.hold(clock.now + 60.seconds)

        clock.now = reset - 1.seconds
        assertEquals(reset, assertIs<TrackerError.RateLimited>(runCatching { hold.ticket() }.exceptionOrNull()).retryAt)
    }

    @Test
    fun `the token is in no toString and no message of a held call`() =
        runTest {
            val tracker = tracker()
            val delivery = delivery()
            spendLast = true
            tracker.fetchIssuesByIds(listOf("26")).getOrThrow()

            val texts =
                listOf(
                    clients.toString(),
                    clients.client(URI(FakeGitHub.API), token).toString(),
                    RateLimitHold(clock).toString(),
                    tracker.toString(),
                    delivery.toString(),
                    delivery.findPullRequest("loop/m4-48").exceptionOrNull().toString(),
                    delivery.actionsRuns(sha).exceptionOrNull()?.message.orEmpty(),
                    tracker.fetchIssuesByIds(listOf("26")).exceptionOrNull().toString(),
                )
            for (text in texts) assertTrue(token !in text && "m448SharedHold" !in text, text)
        }
}
