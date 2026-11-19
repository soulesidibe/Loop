package loop.cli

import com.github.ajalt.clikt.testing.test
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bl-33, end to end with no model call, like [ClaudeCodeTrackerToolsTest]: a turn whose result is a success and whose
 * only permission denials are policy denials, a heredoc body holding `git push` in backticks and a `python3 -` script
 * quoting `loop approve`, completes, so what it queued is applied as for any successful turn. The fake `claude`
 * replays the transcript, runs its allowed tool calls for real and leaves the denied ones unrun.
 */
class ClaudeCodePolicyDenialTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val comment = "Wrote hello.txt. Two commands were denied by policy."

    @Test
    fun `a success turn with only policy denials applies its queued comment and move to review`() {
        val result = run("policy_denials_move")

        assertEquals("review", fixture.stateOf("t-1"), result)
        assertEquals(
            listOf(
                "issue_comment_requested",
                "issue_move_requested",
                "turn_completed",
                "issue_commented",
                "result_submitted",
                "verdict",
                "issue_moved",
            ),
            kinds(),
        )
        assertContains(dataOf("result_submitted"), "\"to\":\"review\"")
        assertNotDenied()
    }

    @Test
    fun `a success turn with only policy denials applies its queued comment and submitted result`() {
        val result = run("policy_denials_submit")

        assertEquals("review", fixture.stateOf("t-1"), result)
        assertEquals(
            listOf(
                "issue_comment_requested",
                "result_requested",
                "turn_completed",
                "issue_commented",
                "result_submitted",
                "verdict",
                "issue_moved",
            ),
            kinds(),
        )
        assertContains(dataOf("result_submitted"), "\"source\":\"mcp\"")
        assertNotDenied()
    }

    /** Runs Loop once on issue `t-1` with a fake `claude` that replays `streams/<transcript>.jsonl`; its output. */
    private fun run(transcript: String): String {
        val stream = checkNotNull(javaClass.getResource("/streams/$transcript.jsonl")) { "no transcript" }.toURI().toPath()
        val record = root.resolve("claude-record").createDirectories()
        val claude = TranscriptReplay.fakeClaude(root.resolve("claude-bin").createDirectories(), stream, record)
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
        assertContains(fixture.issueFile("t-1").readText(), comment, message = "events: ${kinds()}")
        return result.output + result.stderr
    }

    private val tracked =
        setOf(
            "turn_completed",
            "turn_input_required",
            "turn_failed",
            "tool_request_dropped",
            "issue_comment_requested",
            "issue_move_requested",
            "result_requested",
            "issue_commented",
            "result_submitted",
            "verdict",
            "issue_moved",
        )

    private fun kinds(): List<String> =
        fixture
            .query("SELECT kind FROM events WHERE issue_id = 't-1' ORDER BY seq")
            .mapNotNull { it["kind"] }
            .filter { it in tracked }

    private fun dataOf(kind: String) = fixture.query("SELECT data FROM events WHERE kind = '$kind'").single()["data"].orEmpty()

    /** The allowed command ran and wrote `hello.txt`, the denied ones never ran: neither wrote `notes.md`. */
    private fun assertNotDenied() {
        assertTrue(fixture.workspace("t-1").resolve("hello.txt").exists(), "the allowed command did not run")
        assertFalse(fixture.workspace("t-1").resolve("notes.md").exists(), "a denied command ran")
        assertContains(dataOf("verdict"), "\"verdict\":\"verified\"")
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
