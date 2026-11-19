package loop.cli

import com.github.ajalt.clikt.testing.test
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `loop run` writes the human readable run log at `<LOOP_HOME>/logs/<project_id>.log` (m1-39). */
class HumanLogCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a fake claude run writes run, attempt, turn, move and end lines in order`() {
        fixture.workflow.writeText(claudeWorkflow(fakeClaude()))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            listOf(
                "HH:MM:SS  run started for ${fixture.workflow.toRealPath()}",
                "HH:MM:SS  1 issue eligible (t-1)",
                "HH:MM:SS  t-1  attempt started in workspaces/t-1",
                "HH:MM:SS  t-1  turn 1 of 20 started",
                "HH:MM:SS  t-1  ran loop tool move review (exit 0)",
                "HH:MM:SS  t-1  turn 1 finished, 1.3K tokens, \$0.01",
                "HH:MM:SS  t-1  moved to review by the agent's request",
                "HH:MM:SS  t-1  attempt succeeded, the issue is in review",
                "HH:MM:SS  run ended",
            ),
            fixture.humanLog.readLines().map { it.replace(TIME, "HH:MM:SS  ") },
        )
    }

    @Test
    fun `the log is owner only, appended across runs, and stderr keeps its key value lines only`() {
        fixture.issue("t-1")
        // Keeps the bl-06 startup warning, which is not a log line, off stderr.
        val prompt = fixture.workflow.readText()
        fixture.workflow.writeText(prompt.replace("{{ issue.title }}\n", "{{ issue.title }}\n{{ issue.description }}\n"))

        val first = fixture.loop("run", fixture.workflow.toString(), "--once")
        val second = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, first.exitCode, first.toString())
        assertEquals(0, second.exitCode, second.toString())
        val lines = fixture.humanLog.readLines()
        assertEquals(2, lines.count { it.endsWith("run started for ${fixture.workflow.toRealPath()}") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.endsWith("t-1  attempt succeeded, the issue is in review") }, lines.joinToString("\n"))
        assertTrue(lines.last().endsWith("  run ended"), lines.joinToString("\n"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.humanLog)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.humanLog.parent)))
        listOf(first, second).forEach { result ->
            result.stderr.lines().filter { it.isNotBlank() }.forEach { line ->
                assertTrue(KEY_VALUE_LINE.matches(line), "not key=value: $line")
                assertFalse("human_log" in line, line)
            }
        }
    }

    @Test
    fun `an unwritable log folder leaves the run going and warns once on stderr`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        // A file where the logs folder should be: no line can be written.
        fixture.loopHome.createDirectories()
        fixture.loopHome.resolve("logs").writeText("not a folder")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals("review", fixture.stateOf("t-2"), result.toString())
        assertFalse(fixture.humanLog.exists())
        val warnings = result.stderr.lines().filter { "event=human_log" in it }
        assertEquals(1, warnings.size, result.stderr)
        assertTrue(KEY_VALUE_LINE.matches(warnings.single()), warnings.single())
        assertTrue(" level=warn event=human_log outcome=failed " in warnings.single(), warnings.single())
    }

    private fun claudeWorkflow(claude: Path): String =
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

    /** A fake `claude`: init, a Bash call that moves the issue with `loop tool`, its result, then a success result with usage. */
    private fun fakeClaude(): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-human-log"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""
        val call =
            """{"type":"assistant","message":{"id":"msg_1","role":"assistant","content":[{"type":"tool_use","id":"toolu_1",""" +
                """"name":"Bash","input":{"command":"loop tool move review"}}]},"parent_tool_use_id":null,"session_id":"$session"}"""
        val done =
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result",""" +
                """"content":"queued","is_error":false}]},"parent_tool_use_id":null,"session_id":"$session"}"""
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                """"total_cost_usd":0.01,"usage":{"input_tokens":1234,"output_tokens":56,""" +
                """"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"permission_denials":[]}"""
        script.writeText(
            listOf("#!/bin/sh", "set -e", "read -r line", "echo '$init'", "echo '$call'")
                .plus(listOf("loop tool move review > /dev/null", "echo '$done'", "echo '$result'", ""))
                .joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private companion object {
        val TIME = Regex("^\\d\\d:\\d\\d:\\d\\d  ")
        private const val VALUE = """("(?:[^"\\]|\\.)*"|[^\s"]+)"""
        val KEY_VALUE_LINE = Regex("""^[a-z_]+=$VALUE( [a-z_]+=$VALUE)*$""")
    }
}
