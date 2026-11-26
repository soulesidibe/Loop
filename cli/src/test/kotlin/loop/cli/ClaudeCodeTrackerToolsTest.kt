package loop.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
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
 * m2-17, end to end with no model call, like [ClaudeCodeSubmitResultTest]: a replayed Claude Code transcript that
 * calls `mcp__loop__comment` and `mcp__loop__move` maps to the requests `loop tool comment` and `loop tool move`
 * queue. The fake `claude` replays `streams/tracker_tools.jsonl` and makes its MCP calls for real on the server of
 * the config the runner generated, and Loop applies them at the drain when the turn ends.
 */
class ClaudeCodeTrackerToolsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val transcript: Path =
        checkNotNull(javaClass.getResource("/streams/tracker_tools.jsonl")) { "no transcript" }.toURI().toPath()

    private val question = "Should the export include archived items? Options: include them, or skip them. I would include them."

    @Test
    fun `a replayed transcript that calls comment and move over MCP queues and applies the loop tool requests`() {
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

        assertEquals(0, result.statusCode, diagnostics(result, "default:t-1"))
        // In Loop mode a move to needs_info is not a submitted result: it applies as asked, with no verification.
        assertEquals("needs_info", fixture.stateOf("t-1"), diagnostics(result, "default:t-1"))
        assertContains(fixture.issueFile("t-1").readText(), question)
        val applied = listOf("issue_comment_requested", "issue_move_requested", "issue_commented", "issue_moved")
        val kinds =
            fixture
                .query("SELECT kind FROM events WHERE issue_id = 'default:t-1' ORDER BY seq")
                .mapNotNull { it["kind"] }
                .filter { it in applied + "result_submitted" }
        assertEquals(applied, kinds, diagnostics(result, "default:t-1"))

        fun dataOf(kind: String) = fixture.query("SELECT data FROM events WHERE kind = '$kind'").single()["data"].orEmpty()
        // The requests of loop tool comment and loop tool move, with no source.
        assertEquals("""{"chars":"${question.length}"}""", dataOf("issue_comment_requested"))
        assertEquals("""{"to":"needs_info"}""", dataOf("issue_move_requested"))
        assertContains(dataOf("issue_moved"), "\"to\":\"needs_info\"")
        assertContains(dataOf("issue_commented"), "\"chars\":\"${question.length}\"")
        val outputs = fixture.query("SELECT data FROM events WHERE kind = 'runner_output'").map { it["data"].orEmpty() }
        assertTrue(outputs.any { "Queued a comment on t-1 (${question.length} characters)." in it }, outputs.joinToString("\n"))
        assertTrue(outputs.any { "Queued a move of t-1 to" in it && "needs_info" in it }, outputs.joinToString("\n"))
        assertTrue(record.resolve("mcp.json").readText().contains("\"args\":[\"tool\",\"mcp\"]"), record.resolve("mcp.json").readText())
    }

    @Test
    fun `m4-14 with a files and a GitHub tracker each agent's comment and move reach its issue's own tracker only`() {
        RecordedGitHubIssue().use { github ->
            val record = root.resolve("claude-record").createDirectories()
            val claude = TranscriptReplay.fakeClaude(root.resolve("claude-bin").createDirectories(), transcript, record)
            fixture.workflow.writeText(
                "---\n" +
                    "tracker:\n" +
                    "  id: local\n" +
                    "  kind: files\n" +
                    "  active_states: [todo]\n" +
                    "  required_labels: [ready-for-agent]\n" +
                    github.trackersYaml +
                    "workspace:\n" +
                    "  root: ./workspaces\n" +
                    "runner:\n" +
                    "  kind: claude-code\n" +
                    "runners:\n" +
                    "  claude-code:\n" +
                    "    command: \"$claude\"\n" +
                    "agent:\n" +
                    "  max_turns: 1\n" +
                    "retries:\n" +
                    "  max_attempts: 1\n" +
                    "  max_continuations: 1\n" +
                    "---\n" +
                    "Work on {{ issue.identifier }}\n",
            )
            fixture.issue("l-1")

            val result = LoopCommand(host(GITHUB_TOKEN)).test(listOf("run", fixture.workflow.toString(), "--once"))

            val both = { diagnostics(result, "local:l-1", "gh:26") }
            assertEquals(0, result.statusCode, both())
            // The files issue got its own comment and move, once each, and nothing of the GitHub issue's.
            assertEquals("needs_info", fixture.stateOf("l-1"), both())
            assertEquals(
                1,
                fixture
                    .issueFile("l-1")
                    .readText()
                    .split(question)
                    .size - 1,
                fixture.issueFile("l-1").readText() + both(),
            )
            // GitHub got the comment and the needs_info move of issue 26 only: one comment, the handoff label added,
            // the required label removed, and no write for the files issue.
            assertEquals(
                listOf(
                    "POST /api/repos/octocat/Hello-World/issues/26/comments",
                    "POST /api/repos/octocat/Hello-World/issues/26/labels",
                    "DELETE /api/repos/octocat/Hello-World/issues/26/labels/gh-ready",
                ),
                github.writePaths,
                both(),
            )
            assertContains(github.writes.first().body, "archived items")
            val applied = listOf("issue_comment_requested", "issue_move_requested", "issue_commented", "issue_moved")
            for (issue in listOf("local:l-1", "gh:26")) {
                val kinds =
                    fixture
                        .query("SELECT kind FROM events WHERE issue_id = '$issue' ORDER BY seq")
                        .mapNotNull { it["kind"] }
                        .filter { it in applied + "tool_request_failed" }
                assertEquals(applied, kinds, "$issue\n" + both())
            }
            assertTrue(result.stderr.lines().none { "No tracker" in it }, result.stderr)
        }
    }

    /**
     * What `run --once` printed, then the `runner_output` events of each of [issues], stderr included, so a fake
     * `claude` that exits before its transcript shows why in the failure message.
     */
    private fun diagnostics(
        result: CliktCommandTestResult,
        vararg issues: String,
    ): String =
        result.output + result.stderr +
            issues.joinToString("") { issue ->
                val lines =
                    fixture
                        .query("SELECT data FROM events WHERE kind = 'runner_output' AND issue_id = '$issue' ORDER BY seq")
                        .map { it["data"].orEmpty() }
                "\n--- runner_output of $issue (${lines.size})\n" + lines.joinToString("\n")
            }

    private fun host(githubToken: String? = null): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ) + listOfNotNull(githubToken?.let { "GITHUB_TOKEN" to it }),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
        )

    private companion object {
        const val GITHUB_TOKEN = "ghp_m414ToolsToken0123456789abcdefghij"
    }
}
