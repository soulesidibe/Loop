package loop.cli

import com.github.ajalt.clikt.testing.test
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * bl-108: a claude-code turn that printed a `result` line with usage, then went silent until a stall or a `loop stop`,
 * records that usage and cost on its attempt row, its ledger line and the budgets, end to end with a fake `claude`.
 * Since m3-07 the `result` line ends its turn, so the silence falls in the next turn of the same process.
 */
class ClaudeCodeStalledUsageTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a result line then silence until the stall timeout records its usage and cost on the attempt and its ledger line`() {
        fixture.workflow.writeText(claudeWorkflow(silentClaude(cost = "2.5"), stallTimeoutMs = STALL_MS))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val columns = "outcome, reason, tokens, input_tokens, cache_read_input_tokens, cost_usd"
        val row = fixture.query("SELECT $columns FROM attempts WHERE issue_id = 't-1'").single()
        assertEquals("failed", row["outcome"], "$row")
        assertEquals("stalled", row["reason"], "$row")
        assertEquals("$TOKENS", row["tokens"], "$row")
        assertEquals("1000", row["input_tokens"], "$row")
        assertEquals("50000", row["cache_read_input_tokens"], "$row")
        assertEquals(2.5, assertNotNull(row["cost_usd"], "$row").toDouble(), 1e-9)
        // m3-07: the result line ends turn 1, which completes with the usage on its line; turn 2 is the silent one.
        val lines = ledgerLines()
        assertEquals(2, lines.size, "$lines")
        val (completed, line) = lines
        assertContains(completed, "\"outcome\":\"completed\"")
        assertContains(completed, "\"tokens\":$TOKENS")
        assertContains(completed, "\"cost_usd\":2.5")
        assertContains(line, "\"outcome\":\"failed\"")
        assertContains(line, "\"tokens\":null")
        assertContains(line, "\"attempt_tokens\":$TOKENS")
        assertContains(line, "\"cost_usd\":null")
        assertContains(line, "\"attempt_cost_usd\":2.5")
        val trace = LoopCommand(host()).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertEquals(0, trace.statusCode, trace.output)
        val header = trace.output.lines().single { it.startsWith("Attempt 1 ") }
        assertContains(header, "$TOKENS tokens")
    }

    @Test
    fun `a stalled turn with no result line records no usage, its tokens and cost stay null`() {
        fixture.workflow.writeText(claudeWorkflow(silentClaude(cost = null), stallTimeoutMs = STALL_MS))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val row = fixture.query("SELECT reason, tokens, cost_usd FROM attempts WHERE issue_id = 't-1'").single()
        assertEquals("stalled", row["reason"], "$row")
        assertNull(row["tokens"], "$row")
        assertNull(row["cost_usd"], "$row")
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind = 'usage'").size)
        val line = ledgerLines().single()
        assertContains(line, "\"tokens\":null")
        assertContains(line, "\"cost_usd\":null")
    }

    @Test
    fun `a turn stopped by loop stop after a result line records its usage and cost on the canceled attempt`() {
        fixture.workflow.writeText(claudeWorkflow(silentClaude(cost = "2.5"), stallTimeoutMs = 600_000))
        fixture.issue("t-1")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            awaitUsage()
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val run = running.await(30)

            assertEquals(0, stop.exitCode, stop.toString())
            assertEquals(0, run.exitCode, run.toString())
            val row = fixture.query("SELECT outcome, reason, tokens, cost_usd FROM attempts WHERE issue_id = 't-1'").single()
            assertEquals("canceled", row["outcome"], "$row\n$run")
            assertEquals("stop_requested", row["reason"], "$row\n$run")
            assertEquals("$TOKENS", row["tokens"], "$row\n$run")
            assertEquals(2.5, assertNotNull(row["cost_usd"], "$row\n$run").toDouble(), 1e-9)
        } finally {
            running.process.descendants().forEach { it.destroyForcibly() }
            running.process.destroyForcibly()
        }
    }

    @Test
    fun `the cost of a result line before the silence counts toward the per attempt dollar budget`() {
        val workflow = claudeWorkflow(silentClaude(cost = "12.5"), stallTimeoutMs = 600_000)
        fixture.workflow.writeText(workflow.replace("workspace:", "budgets:\n  per_attempt_usd: 10\nworkspace:"))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("budget", fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 't-1'").single()["reason"], result.output)
        val reached = fixture.query("SELECT data FROM events WHERE kind = 'budget_reached'").single()["data"].orEmpty()
        assertContains(reached, "\"budget\":\"per_attempt_usd\"")
        assertContains(reached, "\"used\":\"12.5\"")
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind = 'attempt_stalled'").size)
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind = 'retry_scheduled'").size)
        val row = fixture.query("SELECT cost_usd FROM attempts WHERE issue_id = 't-1'").single()
        assertEquals(12.5, assertNotNull(row["cost_usd"], "$row").toDouble(), 1e-9)
    }

    /** Waits until the run recorded the usage of the fake's result line, at most [WAIT_MS]; the test fails later without it. */
    private fun awaitUsage() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val recorded = runCatching { fixture.query("SELECT kind FROM events WHERE kind = 'usage'") }.getOrNull().orEmpty()
            if (recorded.isNotEmpty()) return
            Thread.sleep(POLL_MS)
        }
    }

    private fun ledgerLines(): List<String> =
        fixture.loopHome
            .resolve("ledger")
            .resolve("${fixture.projectId()}.jsonl")
            .readLines()

    private fun claudeWorkflow(
        claude: Path,
        stallTimeoutMs: Long,
    ): String =
        """
        |---
        |tracker:
        |  kind: files
        |  active_states: [todo]
        |  required_labels: [ready-for-agent]
        |workspace:
        |  root: ./workspaces
        |runner:
        |  kind: claude-code
        |runners:
        |  claude-code:
        |    command: "$claude"
        |codex:
        |  stall_timeout_ms: $stallTimeoutMs
        |---
        |Work on {{ issue.identifier }}
        |
        """.trimMargin()

    private fun host(): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
        )

    /**
     * A fake `claude` whose agent started a background task: it reads the first turn's input, prints init and, with a
     * [cost], a result line with [TOKENS] tokens costing that much, then says nothing until it is killed. Since m3-07
     * that result line ends turn 1, and the next turn, whose input the fake never reads, is the silent one.
     */
    private fun silentClaude(cost: String?): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-stalled-session"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""
        val result =
            cost?.let {
                """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                    """"total_cost_usd":$it,"usage":{"input_tokens":1000,"output_tokens":200,""" +
                    """"cache_read_input_tokens":50000,"cache_creation_input_tokens":3000},"permission_denials":[]}"""
            }
        script.writeText(
            listOfNotNull(
                "#!/bin/sh",
                // m3-07: one long lived process, stdin stays open, so the fake reads the turn's one user message line.
                "read -r line",
                "echo '$init'",
                result?.let { "echo '$it'" },
                "exec sleep 60",
                "",
            ).joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private companion object {
        /** The tokens of the fake's result line: 1000 input, 200 output, 50000 cache read, 3000 cache creation. */
        const val TOKENS = 54_200L

        /** The stall timeout of the tests that end on a stall, long enough for the fake's first lines on a loaded machine. */
        const val STALL_MS = 5_000L
        const val WAIT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
