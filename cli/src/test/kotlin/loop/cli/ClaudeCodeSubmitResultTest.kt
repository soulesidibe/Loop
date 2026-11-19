package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m2-16, end to end with no model call: a replayed Claude Code transcript that calls `submit_result` maps to a
 * submitted result. The fake `claude` replays `streams/submit_result.jsonl` and makes its tool calls for real, the
 * MCP one on the server of the config the runner generated, so the call reaches `loop tool mcp` and Loop verifies
 * and moves the issue on the result file's path.
 */
class ClaudeCodeSubmitResultTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val transcript: Path =
        checkNotNull(javaClass.getResource("/streams/submit_result.jsonl")) { "no transcript" }.toURI().toPath()

    @Test
    fun `a replayed transcript that calls submit_result is verified and moved like a done result file`() {
        val record = root.resolve("claude-record").createDirectories()
        val claude = TranscriptReplay.fakeClaude(root.resolve("claude-bin").createDirectories(), transcript, record)
        fixture.workflow.writeText(
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
            |agent:
            |  max_turns: 1
            |retries:
            |  max_attempts: 1
            |  max_continuations: 1
            |kinds:
            |  code:
            |    delivery: { policy: auto, auto_merge: { enabled: true } }
            |    verify:
            |      checks:
            |        - { type: command, run: "test -f hello.txt" }
            |---
            |Work on {{ issue.identifier }}
            |
            """.trimMargin(),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        assertEquals("review", fixture.stateOf("t-1"), result.output + result.stderr)
        val kinds =
            fixture
                .query("SELECT kind FROM events WHERE issue_id = 't-1' ORDER BY seq")
                .mapNotNull { it["kind"] }
                .filter { it in setOf("result_requested", "result_submitted", "check_started", "check_finished", "verdict", "issue_moved") }
        assertEquals(listOf("result_requested", "result_submitted", "check_started", "check_finished", "verdict", "issue_moved"), kinds)
        val submitted = fixture.query("SELECT data FROM events WHERE kind = 'result_submitted'").single()["data"].orEmpty()
        assertContains(submitted, "\"source\":\"mcp\"")
        assertContains(submitted, "\"status\":\"done\"")
        assertContains(submitted, "\"acceptance\":\"1\"")

        fun dataOf(kind: String) = fixture.query("SELECT data FROM events WHERE kind = '$kind'").single()["data"].orEmpty()
        assertContains(dataOf("verdict"), "\"verdict\":\"verified\"")
        assertContains(dataOf("issue_moved"), "\"verified\":\"true\"")
        // The runner gave claude Loop's MCP server: the launcher's `tool mcp`, and the call went through it live.
        val server =
            Json
                .parseToJsonElement(record.resolve("mcp.json").readText())
                .jsonObject
                .getValue("mcpServers")
                .jsonObject
                .getValue("loop")
                .jsonObject
        assertEquals(System.getProperty("loop.launcher"), server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("tool", "mcp"), server.getValue("args").jsonArray.map { it.jsonPrimitive.content })
        val outputs = fixture.query("SELECT data FROM events WHERE kind = 'runner_output'").map { it["data"].orEmpty() }
        assertTrue(outputs.any { "Queued a done result for t-1." in it && "tool_result" in it }, outputs.joinToString("\n"))
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertTrue(trace.stdout.lines().any { "result_submitted" in it && "from submit_result" in it }, trace.stdout)
    }

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
}
