package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.DeliveryError
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.runner.acp.GeminiRiskReviewer
import loop.runner.claudecode.ClaudeCodeRiskReviewer
import loop.runner.codex.CodexRiskReviewer
import loop.tracker.github.GitHubResponse
import loop.tracker.github.GitHubTransport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m3-15, note 12: the delivery executor of a run is kept by repository, API URL, token and base, so the rate limit hold
 * of its GitHub client survives every tick, and no HTTP client is built per poll. m4-06: the merge risk reviewer of a
 * delivery is chosen apart from the runner, Claude Code's whatever `runner.kind` is.
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

    /**
     * m4-06: a workflow under `policy: auto` with merge and `risk.reviewer`, whose runner is [runner] with [runners] as
     * its `runners` block.
     */
    private fun autoMergeWorkflow(
        runner: String,
        runners: String,
        folder: String = "auto",
        reviewer: String = "claude-sonnet-5-5",
    ): WorkflowFile {
        val file = Files.createDirectories(root.resolve(folder)).resolve("WORKFLOW.md")
        file.writeText(
            """
            |---
            |tracker:
            |  kind: files
            |runner:
            |  kind: $runner
            |runners:
            |$runners
            |kinds:
            |  code:
            |    delivery:
            |      policy: auto
            |      auto_merge: { enabled: true }
            |      risk: { reviewer: $reviewer }
            |      pull_request:
            |        repo: acme/widgets
            |        token: ${'$'}LOOP_PR_TOKEN
            |        base: main
            |        merge: { method: merge, ci: none }
            |      on_delivered: { move_to: done }
            |    verify:
            |      checks: []
            |---
            |Prompt
            """.trimMargin(),
        )
        return WorkflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    /** A fake `claude` in [dir] that logs its arguments and directory to `claude.log` and answers one result line. */
    private fun fakeClaude(dir: Path): Path {
        Files.createDirectories(dir)
        val log = dir.resolve("claude.log")
        return dir.resolve("claude").also {
            it.writeText(
                "#!/bin/sh\n{ echo \"ARGS \$*\"; echo \"PWD \$(pwd)\"; } >> '$log'\ncat > /dev/null\n" +
                    "echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"{}\"}'\n",
            )
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `m4-06 under codex-app-server the risk reviewer is Claude Code's, run with the configured model in the workspace`() =
        runBlocking {
            val bin = root.resolve("bin")
            fakeClaude(bin)
            val workflow = autoMergeWorkflow("codex-app-server", "  codex:\n    command: codex app-server")
            val host = mapOf("PATH" to "$bin:/usr/bin:/bin", "HOME" to root.toString())

            val reviewer = assertIs<ClaudeCodeRiskReviewer>(riskReviewerFor(workflow, host, emptySet()))
            assertEquals(
                "ClaudeCodeRiskReviewer(command=${bin.resolve("claude")})",
                reviewer.toString(),
                "the default command claude, with no claude-code section, found on the host PATH",
            )

            val model =
                checkNotNull(
                    workflow.config.kinds
                        ?.code
                        ?.delivery
                        ?.risk
                        ?.reviewer,
                )
            val workspace = Files.createDirectories(root.resolve("workspaces/t-1")).toRealPath()
            reviewer.review(RiskReviewRequest(workspace, model, "Review this.", 10_000)).getOrThrow()

            val logged = bin.resolve("claude.log").readText()
            assertContains(logged, "--model claude-sonnet-5-5 --permission-mode plan")
            assertContains(logged, "PWD $workspace")
        }

    @Test
    fun `m4-06 under codex-app-server the reviewer takes runners claude-code command when it is set`() {
        val claude = fakeClaude(root.resolve("elsewhere"))
        val workflow =
            autoMergeWorkflow(
                "codex-app-server",
                "  codex:\n    command: codex app-server\n  claude-code:\n    command: \"$claude\"",
            )

        val reviewer = riskReviewerFor(workflow, mapOf("PATH" to "/usr/bin:/bin"), emptySet())

        assertEquals("ClaudeCodeRiskReviewer(command=$claude)", reviewer.toString())
    }

    @Test
    fun `m4-06 the command runner gets the same reviewer, and the claude-code runner keeps its own command`() {
        val claude = fakeClaude(root.resolve("own"))
        val command = autoMergeWorkflow("command", "  command:\n    command: ./agent.sh", folder = "command")
        assertNotNull(riskReviewerFor(command, mapOf("PATH" to "${root.resolve("own")}:/usr/bin:/bin"), emptySet()))

        val claudeCode = autoMergeWorkflow("claude-code", "  claude-code:\n    command: \"$claude\"", folder = "claude-code")
        assertEquals(
            "ClaudeCodeRiskReviewer(command=$claude)",
            riskReviewerFor(claudeCode, mapOf("PATH" to "/usr/bin:/bin"), emptySet()).toString(),
        )
    }

    @Test
    fun `m4-06 a reviewer command that cannot be found gives no reviewer, so the gate falls back`() {
        val codex = autoMergeWorkflow("codex-app-server", "  codex:\n    command: codex app-server")
        assertNull(riskReviewerFor(codex, mapOf("PATH" to root.resolve("empty").toString()), emptySet()), "claude is not on PATH")

        val missing = root.resolve("no-such/claude")
        val pinned =
            autoMergeWorkflow(
                "codex-app-server",
                "  codex:\n    command: codex app-server\n  claude-code:\n    command: \"$missing\"",
                folder = "pinned",
            )
        assertNull(riskReviewerFor(pinned, mapOf("PATH" to "/usr/bin:/bin"), emptySet()), "an absolute path that does not exist")
    }

    /** bl-214: a fake `codex` in [dir] that logs its arguments and directory to `codex.log`, then exits 1. */
    private fun fakeCodex(dir: Path): Path {
        Files.createDirectories(dir)
        val log = dir.resolve("codex.log")
        return dir.resolve("codex").also {
            it.writeText("#!/bin/sh\n{ echo \"ARGS \$*\"; echo \"PWD \$(pwd)\"; } >> '$log'\nexit 1\n")
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `bl-214 a reviewer model naming a Codex model gets the Codex reviewer, its command found on the host PATH, in the workspace`() =
        runBlocking {
            val bin = root.resolve("bin-codex")
            fakeCodex(bin)
            val workflow = autoMergeWorkflow("claude-code", "  claude-code:\n    command: claude", reviewer = "gpt-6.1-sol")
            val host = mapOf("PATH" to "$bin:/usr/bin:/bin", "HOME" to root.toString())

            val reviewer = assertIs<CodexRiskReviewer>(riskReviewerFor(workflow, host, emptySet()))
            assertEquals(
                "CodexRiskReviewer(command='${bin.resolve("codex")}' 'app-server')",
                reviewer.toString(),
                "the default command codex app-server, with no codex section, its program found on the host PATH",
            )

            val workspace = Files.createDirectories(root.resolve("workspaces/t-2")).toRealPath()
            val result = reviewer.review(RiskReviewRequest(workspace, "gpt-6.1-sol", "Review this.", 10_000))

            assertIs<RiskReviewError.Crashed>(result.exceptionOrNull(), "the fake exits before any answer")
            val logged = bin.resolve("codex.log").readText()
            assertContains(logged, "ARGS app-server")
            assertContains(logged, "PWD $workspace")
        }

    @Test
    fun `bl-214 under codex-app-server the Codex reviewer takes runners codex command, its other words kept`() {
        val codex = fakeCodex(root.resolve("pinned-codex"))
        val workflow =
            autoMergeWorkflow(
                "codex-app-server",
                "  codex:\n    command: \"$codex app-server -c x=y\"",
                reviewer = "gpt-6.1-sol",
            )

        val reviewer = riskReviewerFor(workflow, mapOf("PATH" to "/usr/bin:/bin"), emptySet())

        assertEquals("CodexRiskReviewer(command='$codex' 'app-server' '-c' 'x=y')", reviewer.toString())
    }

    @Test
    fun `bl-214 a Claude reviewer model keeps the Claude Code reviewer under codex-app-server`() {
        val claude = fakeClaude(root.resolve("claude-bin"))
        fakeCodex(root.resolve("claude-bin"))
        val workflow = autoMergeWorkflow("codex-app-server", "  codex:\n    command: codex app-server")

        val reviewer = riskReviewerFor(workflow, mapOf("PATH" to "${root.resolve("claude-bin")}:/usr/bin:/bin"), emptySet())

        assertEquals("ClaudeCodeRiskReviewer(command=$claude)", reviewer.toString())
    }

    @Test
    fun `bl-214 a Codex reviewer whose command cannot be found gives no reviewer, so the gate falls back`() {
        fakeClaude(root.resolve("only-claude"))
        val workflow = autoMergeWorkflow("claude-code", "  claude-code:\n    command: claude", reviewer = "gpt-6.1-sol")

        assertNull(
            riskReviewerFor(workflow, mapOf("PATH" to root.resolve("only-claude").toString()), emptySet()),
            "codex is not on PATH, and claude does not stand in for it",
        )
    }

    /** bl-205: a fake `agy` in [dir] that logs its arguments and directory to `agy.log`, then exits 1. */
    private fun fakeAgy(dir: Path): Path {
        Files.createDirectories(dir)
        val log = dir.resolve("agy.log")
        return dir.resolve("agy").also {
            it.writeText("#!/bin/sh\n{ echo \"ARGS \$*\"; echo \"PWD \$(pwd)\"; } >> '$log'\nexit 1\n")
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `bl-205 gemini-4-neon-high picks the Gemini reviewer, sonnet keeps Claude Code and gpt-6_1-sol keeps Codex`() {
        val bin = root.resolve("bin-all")
        val claude = fakeClaude(bin)
        val codex = fakeCodex(bin)
        val agy = fakeAgy(bin)
        val host = mapOf("PATH" to "$bin:/usr/bin:/bin", "HOME" to root.toString())
        val runners = "  claude-code:\n    command: claude"

        assertEquals(
            "GeminiRiskReviewer(command='$agy' acp)",
            riskReviewerFor(autoMergeWorkflow("claude-code", runners, "gemini", "gemini-4-neon-high"), host, emptySet()).toString(),
        )
        assertEquals(
            "ClaudeCodeRiskReviewer(command=$claude)",
            riskReviewerFor(autoMergeWorkflow("claude-code", runners, "sonnet", "sonnet"), host, emptySet()).toString(),
        )
        assertEquals(
            "CodexRiskReviewer(command='$codex' 'app-server')",
            riskReviewerFor(autoMergeWorkflow("claude-code", runners, "gpt", "gpt-6.1-sol"), host, emptySet()).toString(),
        )
    }

    @Test
    fun `bl-205 a Gemini reviewer runs agy acp under preset agy, found on the host PATH, in the workspace`() =
        runBlocking {
            val bin = root.resolve("bin-agy")
            fakeAgy(bin)
            val workflow =
                autoMergeWorkflow("codex-app-server", "  codex:\n    command: codex app-server", reviewer = "gemini-4-neon-high")
            val host = mapOf("PATH" to "$bin:/usr/bin:/bin", "HOME" to root.toString())

            val reviewer = assertIs<GeminiRiskReviewer>(riskReviewerFor(workflow, host, emptySet()))
            assertEquals(
                "GeminiRiskReviewer(command='${bin.resolve("agy")}' acp)",
                reviewer.toString(),
                "the default command agy acp, its program found on the host PATH",
            )

            val workspace = Files.createDirectories(root.resolve("workspaces/t-3")).toRealPath()
            val result = reviewer.review(RiskReviewRequest(workspace, "gemini-4-neon-high", "Review this.", 10_000))

            assertIs<RiskReviewError.Crashed>(result.exceptionOrNull(), "the fake exits before any answer")
            val logged = bin.resolve("agy.log").readText()
            assertContains(logged, "ARGS acp --disable-telemetry --sandbox --gemini-dir ")
            assertContains(logged, "PWD $workspace")
        }

    @Test
    fun `bl-205 under runner acp with preset agy the Gemini reviewer takes runners acp command`() {
        val agy = fakeAgy(root.resolve("pinned agy"))
        val workflow =
            autoMergeWorkflow(
                "acp",
                "  acp:\n    command: \"'$agy' acp\"\n    preset: agy\n    model: gemini-4-neon-medium",
                reviewer = "gemini-4-neon-high",
            )

        val reviewer = riskReviewerFor(workflow, mapOf("PATH" to "/usr/bin:/bin"), emptySet())

        assertEquals("GeminiRiskReviewer(command='$agy' acp)", reviewer.toString())
    }

    @Test
    fun `bl-205 a Gemini reviewer whose agy cannot be found gives no reviewer, so the gate falls back`() {
        fakeClaude(root.resolve("only-claude-2"))
        val workflow = autoMergeWorkflow("claude-code", "  claude-code:\n    command: claude", reviewer = "gemini-4-neon-high")

        assertNull(
            riskReviewerFor(workflow, mapOf("PATH" to root.resolve("only-claude-2").toString()), emptySet()),
            "agy is not on PATH, and claude does not stand in for it",
        )
    }
}
