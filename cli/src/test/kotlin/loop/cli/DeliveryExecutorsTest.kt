package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.DeliveryError
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.tracker.github.GitHubResponse
import loop.tracker.github.GitHubTransport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m3-15, note 12: the delivery executor of a run is kept by repository, API URL, token and base, so the rate limit hold
 * of its GitHub client survives every tick, and no HTTP client is built per poll.
 */
class DeliveryExecutorsTest {
    private val root: Path = createTempDirectory("loop-delivery-executors").toRealPath()
    private val token = "ghp_m315MemoToken0123456789abcdefghijklmn"
    private val env = mapOf("LOOP_PR_TOKEN" to token, "OTHER_TOKEN" to "ghp_m315OtherToken0123456789abcdefghijkl")

    private fun workflow(
        base: String = "main",
        tokenName: String = "LOOP_PR_TOKEN",
        repo: String = "acme/widgets",
        folder: String = "p",
    ): WorkflowFile {
        val dir = Files.createDirectories(root.resolve(folder))
        val file = dir.resolve("WORKFLOW.md")
        file.writeText(
            """
            |---
            |tracker:
            |  kind: files
            |runner:
            |  kind: command
            |runners:
            |  command:
            |    command: ./agent.sh
            |kinds:
            |  code:
            |    delivery:
            |      pull_request:
            |        repo: $repo
            |        token: ${'$'}$tokenName
            |        base: $base
            |    verify:
            |      checks: []
            |---
            |Prompt
            """.trimMargin(),
        )
        return WorkflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    private class Counting(
        val answer: () -> GitHubResponse,
    ) : GitHubTransport {
        var requests = 0

        override suspend fun send(
            method: String,
            url: java.net.URI,
            headers: Map<String, String>,
            body: String?,
        ): GitHubResponse {
            requests++
            return answer()
        }
    }

    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Test
    fun `the same settings give the same executor, a reload included`() {
        val executors = DeliveryExecutors(env)

        val first = executors.of(workflow())
        assertSame(first, executors.of(workflow()))
        assertSame(first, executors.of(workflow(folder = "p")), "a reloaded workflow with the same settings")
    }

    @Test
    fun `another base, token or repository gives another executor`() {
        val executors = DeliveryExecutors(env)
        val first = executors.of(workflow())

        assertNotSame(first, executors.of(workflow(base = "develop")))
        assertNotSame(first, executors.of(workflow(tokenName = "OTHER_TOKEN")))
        assertNotSame(first, executors.of(workflow(repo = "acme/gadgets")))
    }

    @Test
    fun `a workflow without pull request delivery has none`() {
        val file = Files.createDirectories(root.resolve("none")).resolve("WORKFLOW.md")
        file.writeText(
            "---\ntracker:\n  kind: files\nrunner:\n  kind: command\nrunners:\n  command:\n    command: ./a.sh\n---\nPrompt\n",
        )
        assertNull(DeliveryExecutors(env).of(WorkflowLoader(env = env, home = root).load(file).getOrThrow()))
    }

    @Test
    fun `a rate limit hold lasts across ticks, each tick looking the executor up again`() =
        runBlocking {
            val reset = now.epochSeconds + 600
            val transport =
                Counting {
                    GitHubResponse(
                        200,
                        mapOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("$reset")),
                        """{"total_count":0,"workflow_runs":[]}""",
                    )
                }
            val executors = DeliveryExecutors(env, transport, clock)
            val sha = "0123456789abcdef0123456789abcdef01234567"

            // Tick 1 spends the last request of the window.
            assertTrue(checkNotNull(executors.of(workflow())).actionsRuns(sha).isSuccess)
            // Ticks 2 and 3 look the executor up again, as the orchestrator does, and send nothing while held.
            repeat(2) {
                assertIs<DeliveryError.Unavailable>(checkNotNull(executors.of(workflow())).actionsRuns(sha).exceptionOrNull())
            }
            assertEquals(1, transport.requests)
        }

    @Test
    fun `the key never shows the token`() {
        val executors = DeliveryExecutors(env)
        executors.of(workflow())

        assertTrue(token !in executors.toString())
    }
}
