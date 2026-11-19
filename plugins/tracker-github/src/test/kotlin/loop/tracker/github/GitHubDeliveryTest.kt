package loop.tracker.github

import kotlinx.coroutines.test.runTest
import loop.api.DeliveryError
import loop.api.TrackerError
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-25: how [GitHubDelivery] classes a [TrackerError] of its GitHub client, and that it returns failures instead of
 * throwing them, cancellation aside.
 */
class GitHubDeliveryTest {
    @TempDir
    lateinit var tmp: Path

    private fun delivery(transport: GitHubTransport) =
        GitHubDelivery(
            owner = "octocat",
            repo = "Hello-World",
            token = "ghp_m225DeliveryToken0123456789abcdefghij",
            projectDir = tmp,
            apiUrl = URI(FakeGitHub.API),
            env =
                mapOf(
                    "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                    "HOME" to tmp.toString(),
                    "GIT_CONFIG_GLOBAL" to "/dev/null",
                    "GIT_CONFIG_SYSTEM" to "/dev/null",
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "GIT_CEILING_DIRECTORIES" to tmp.parent.toString(),
                ),
            transport = transport,
            clock = TestClock(Instant.parse("2026-11-12T15:00:00Z")),
        )

    @Test
    fun `unreachable, a rate limit and a 5xx answer are unavailable, anything else is rejected`() {
        val unavailable =
            listOf(
                TrackerError.Unavailable(IOException("Connection refused")),
                TrackerError.RateLimited(null),
                TrackerError.Status(500, "Internal"),
                TrackerError.Status(502, "Bad Gateway"),
                TrackerError.Status(503, "Unavailable"),
            )
        val rejected =
            listOf(
                TrackerError.Status(401, "Bad credentials"),
                TrackerError.Status(403, "Resource not accessible by personal access token"),
                TrackerError.Status(404, "Not Found"),
                TrackerError.Status(422, "Validation Failed"),
                TrackerError.Malformed("https://api.github.com/repos/octocat/Hello-World/pulls"),
                TrackerError.Rejected("The pull request body has 70000 characters"),
            )
        for (error in unavailable) {
            val mapped = GitHubDelivery.deliveryError("Cannot open the pull request", error)
            assertIs<DeliveryError.Unavailable>(mapped, error.message)
            assertSame(error, mapped.cause)
            assertEquals("Cannot open the pull request: ${error.message}", mapped.message)
        }
        for (error in rejected) {
            val mapped = GitHubDelivery.deliveryError("Cannot open the pull request", error)
            assertIs<DeliveryError.Rejected>(mapped, error.message)
            assertTrue(mapped.permanent)
            assertSame(error, mapped.cause)
        }
    }

    @Test
    fun `an unexpected error of the transport is a rejected result, not a throw`() =
        runTest {
            val result = delivery { _, _, _, _ -> throw IllegalStateException("broken transport") }.findPullRequest("loop/k1")

            val error = result.exceptionOrNull()
            assertIs<DeliveryError.Rejected>(error)
            assertTrue("broken transport" in error.message!!, error.message)
        }

    @Test
    fun `cancellation is thrown, not returned`() =
        runTest {
            assertFailsWith<CancellationException> {
                delivery { _, _, _, _ -> throw CancellationException("stopped") }.findPullRequest("loop/k1")
            }
        }

    @Test
    fun `a 405 or a 409 of a merge is rejected like any other refusal`() {
        for (status in listOf(405, 409)) {
            val mapped = GitHubDelivery.deliveryError("Cannot merge pull request #1", TrackerError.Status(status, "Refused"))
            assertIs<DeliveryError.Rejected>(mapped, "status $status")
        }
    }

    @Test
    fun `a base fetch from a directory that is not a git repository fails without throwing`() =
        runTest {
            val result = delivery(FakeGitHub()).fetchBase()

            assertIs<DeliveryError>(result.exceptionOrNull())
        }

    @Test
    fun `the base fetch uses the configured base, refused before git when it is not a safe branch name`() =
        runTest {
            val pullRequests =
                GitHubPullRequests(GitHubClient(URI(FakeGitHub.API), "t", FakeGitHub()), "octocat", "Hello-World", "a..b")
            val git =
                GitPush(tmp, URI("https://github.com/octocat/Hello-World.git"), "t", emptyMap(), git = tmp.resolve("no-git").toString())
            val delivery = GitHubDelivery("octocat/Hello-World", git, pullRequests, base = "a..b")

            val error = delivery.fetchBase().exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("a..b" in error.message!!, error.message)
            assertIs<DeliveryError.Rejected>(delivery.baseTip().exceptionOrNull())
        }

    @Test
    fun `a delivery built without a base fetches main`() {
        val pullRequests = GitHubPullRequests(GitHubClient(URI(FakeGitHub.API), "t", FakeGitHub()), "octocat", "Hello-World", "main")
        val git = GitPush(tmp, URI("https://github.com/octocat/Hello-World.git"), "t", emptyMap())

        assertEquals("main", GitHubDelivery("octocat/Hello-World", git, pullRequests).base)
    }

    @Test
    fun `a push from a directory that is not a git repository fails without throwing`() =
        runTest {
            val result = delivery(FakeGitHub()).push("0123456789abcdef0123456789abcdef01234567", "loop/k1")

            assertIs<DeliveryError>(result.exceptionOrNull())
        }
}
