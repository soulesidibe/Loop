package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.DeliveryError
import loop.api.TrackerError
import loop.core.workflow.WorkflowFile
import loop.tracker.github.GitHubClients
import loop.tracker.github.GitHubResponse
import loop.tracker.github.GitHubTransport
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m4-48 (bl-50): `loop run` builds its GitHub trackers and its pull request delivery over one [GitHubClients], so a
 * request one of them spends holds the others for the life of the run, the CI wait's polls included. Each tick looks
 * the delivery executor up again, as the orchestrator does.
 */
class SharedGitHubHoldTest {
    private val root: Path = createTempDirectory("loop-shared-github-hold").toRealPath()
    private val token = "ghp_m448RunHoldToken0123456789abcdefghijkl"
    private val env = mapOf("GITHUB_TOKEN" to token)
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private var now = Instant.parse("2026-11-24T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val reset = now + 15.minutes

    /** Every request sent, as `<method> <path>`. */
    private val sent = mutableListOf<String>()

    /** Set to make the next answer spend the last request of the window. */
    private var spendLast = false

    private val transport =
        GitHubTransport { method, url: URI, _, _ ->
            sent += "$method ${url.path}"
            val body =
                when {
                    url.path.endsWith("/pulls") -> "[]"
                    url.path.endsWith("/actions/runs") -> """{"total_count":0,"workflow_runs":[]}"""
                    else -> "[]"
                }
            val remaining = if (spendLast) "0" else "4999"
            spendLast = false
            GitHubResponse(
                200,
                mapOf("x-ratelimit-remaining" to listOf(remaining), "x-ratelimit-reset" to listOf("${reset.epochSeconds}")),
                body,
            )
        }

    /** A GitHub tracker and pull request delivery to the same repository with the same token, the tracker's. */
    private fun workflow(): WorkflowFile {
        val dir = Files.createDirectories(root.resolve("project"))
        val file = dir.resolve("WORKFLOW.md")
        file.writeText(
            """
            |---
            |tracker:
            |  kind: github
            |  provider:
            |    repo: acme/widgets
            |    token: ${'$'}GITHUB_TOKEN
            |  required_labels: [ready-for-agent]
            |runner:
            |  kind: command
            |runners:
            |  command:
            |    command: ./agent.sh
            |kinds:
            |  code:
            |    delivery:
            |      pull_request:
            |        base: main
            |    verify:
            |      checks: []
            |---
            |Prompt
            """.trimMargin(),
        )
        return workflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    @Test
    fun `a delivery step held at one tick sends nothing at the next tick while the hold lasts, and delivers after it`() =
        runBlocking {
            val gitHub = GitHubClients(transport, clock)
            val workflow = workflow()
            val tracker = trackerRegistryFor(workflow, root.resolve("home"), gitHub = gitHub).primaryAdapter
            val executors = DeliveryExecutors(env, gitHub = gitHub)

            // Tick 1: the poll spends the last request of the window, then the delivery step is held.
            spendLast = true
            assertTrue(tracker.fetchIssuesByStates(listOf("open")).isSuccess)
            val first = checkNotNull(executors.of(workflow)).findPullRequest("loop/widgets-1").exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(first)
            assertEquals(reset, assertIs<TrackerError.RateLimited>(first.cause).retryAt)
            assertEquals(1, sent.size, sent.toString())

            // Tick 2, still inside the window: the step, the CI wait's poll and the poll send nothing.
            now = reset - 1.seconds
            val delivery = checkNotNull(executors.of(workflow))
            assertIs<DeliveryError.Unavailable>(delivery.findPullRequest("loop/widgets-1").exceptionOrNull())
            assertIs<DeliveryError.Unavailable>(delivery.actionsRuns(sha).exceptionOrNull())
            assertIs<TrackerError.RateLimited>(tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull())
            assertEquals(1, sent.size, sent.toString())

            // Tick 3, at the reset: the step is sent and delivers.
            now = reset
            assertNull(checkNotNull(executors.of(workflow)).findPullRequest("loop/widgets-1").getOrThrow())
            assertEquals(listOf("GET /repos/acme/widgets/issues", "GET /repos/acme/widgets/pulls"), sent)
        }

    @Test
    fun `a CI wait poll that spends the last request holds the next poll of the tracker`() =
        runBlocking {
            val gitHub = GitHubClients(transport, clock)
            val workflow = workflow()
            val tracker = trackerRegistryFor(workflow, root.resolve("home"), gitHub = gitHub).primaryAdapter
            val executors = DeliveryExecutors(env, gitHub = gitHub)

            spendLast = true
            assertEquals(0, checkNotNull(executors.of(workflow)).actionsRuns(sha).getOrThrow().totalCount)
            val held = tracker.fetchIssuesByStates(listOf("open")).exceptionOrNull()

            assertEquals(reset, assertIs<TrackerError.RateLimited>(held).retryAt)
            assertEquals(listOf("GET /repos/acme/widgets/actions/runs"), sent)
        }

    @Test
    fun `the token is in no message of a held step and no toString of the run's GitHub objects`() =
        runBlocking {
            val gitHub = GitHubClients(transport, clock)
            val workflow = workflow()
            val tracker = trackerRegistryFor(workflow, root.resolve("home"), gitHub = gitHub).primaryAdapter
            val executors = DeliveryExecutors(env, gitHub = gitHub)
            spendLast = true
            tracker.fetchIssuesByStates(listOf("open")).getOrThrow()

            val delivery = checkNotNull(executors.of(workflow))
            val texts =
                listOf(
                    gitHub.toString(),
                    executors.toString(),
                    delivery.toString(),
                    tracker.toString(),
                    delivery
                        .findPullRequest("loop/widgets-1")
                        .exceptionOrNull()
                        ?.message
                        .orEmpty(),
                    delivery
                        .actionsRuns(sha)
                        .exceptionOrNull()
                        ?.message
                        .orEmpty(),
                    tracker
                        .fetchIssuesByStates(listOf("open"))
                        .exceptionOrNull()
                        ?.message
                        .orEmpty(),
                )
            for (text in texts) assertTrue(token !in text, text)
        }
}
