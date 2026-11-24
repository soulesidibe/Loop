package loop.cli

import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.ToolRequest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** m1-10 and m1-23: `loop tool comment <text>` end to end through the installed launcher; it queues, Loop writes. */
class ToolCommentCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val short = ATTEMPT.take(8)

    private fun queued(): List<ToolRequest> = fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request }

    @Test
    fun `outside an attempt nothing is queued and the exit is 4`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        val env = fixture.toolEnv(ATTEMPT, "t-1") + ("LOOP_ATTEMPT_ID" to null)

        val result = fixture.loop("tool", "comment", "hello", cwd = root, env = env)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals(
            "loop tool comment: not inside an attempt. Run it from the agent process Loop started, " +
                "where LOOP_ATTEMPT_ID, LOOP_ISSUE_ID and LOOP_TOOL_CONFIG are set. Nothing queued.",
            result.stderr.trim(),
        )
        assertContentEquals(before, file.readBytes())
        assertEquals(emptyList(), queued())
    }

    @Test
    fun `an unreadable snapshot queues nothing and the exit is 4`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val config = fixture.attemptDir(ATTEMPT).resolve("tool-config.json")
        Files.delete(config)

        val result = fixture.inProcess("tool", "comment", "hello", cwd = root, env = env)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals(
            "loop tool comment: cannot read the attempt config at $config: the file does not exist. " +
                "The attempt may have ended; Loop removes this file when an attempt finishes. Nothing queued.",
            result.stderr.trim(),
        )
        assertEquals(emptyList(), queued())
    }

    @Test
    fun `a finished attempt queues nothing and the exit is 5`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptFinished(ATTEMPT, "t-1"))

        val result = fixture.inProcess("tool", "comment", "hello", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(5, result.exitCode, result.toString())
        val message = result.stderr.trim()
        assertTrue(message.startsWith("loop tool comment: attempt $short is not live (finished at "), message)
        assertTrue(message.endsWith("). Nothing queued."), message)
        assertContentEquals(before, file.readBytes())
        assertEquals(emptyList(), queued())
        assertEquals(0, requestedEvents())
    }

    @Test
    fun `a requests folder that is gone queues nothing and the exit is 5`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        Files.delete(fixture.attemptDir(ATTEMPT).resolve("requests"))

        val result = fixture.inProcess("tool", "comment", "hello", cwd = root, env = env)

        assertEquals(5, result.exitCode, result.toString())
        assertEquals(
            "loop tool comment: attempt $short is not live (its attempt directory is gone). Nothing queued.",
            result.stderr.trim(),
        )
        assertEquals(0, requestedEvents())
    }

    @Test
    fun `a blank comment is refused with exit 2`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.inProcess("tool", "comment", "  \n ", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(2, result.exitCode, result.toString())
        assertEquals(
            "loop tool comment: the comment is empty. Pass the text to write, for example " +
                "loop tool comment \"Should the export include archived items?\". Nothing queued.",
            result.stderr.trim(),
        )
        assertContentEquals(before, file.readBytes())
        assertEquals(emptyList(), queued())
    }

    @Test
    fun `a token pattern in the text is redacted before it is queued`() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.inProcess("tool", "comment", "the key is $token, ok?", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf<ToolRequest>(ToolRequest.Comment("the key is [redacted:github-token], ok?")), queued())
    }

    @Test
    fun `text starting with a dash works after a double dash`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result =
            fixture.inProcess(
                "tool",
                "comment",
                "--",
                "-1 means unlimited, right?",
                cwd = root,
                env = fixture.toolEnv(ATTEMPT, "t-1"),
            )

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf<ToolRequest>(ToolRequest.Comment("-1 means unlimited, right?")), queued())
    }

    @Test
    fun `a comment is queued from the snapshot alone, with no LOOP_WORKFLOW and WORKFLOW md deleted`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val projectId = fixture.projectId()
        Files.delete(fixture.workflow)
        val text = "  Should the export include archived items?  "

        val result = fixture.inProcess("tool", "comment", text, cwd = root, env = env)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            "Queued a comment on t-1 (${text.trim().length} characters). " +
                "Loop applies this in the tracker when this turn ends. Do not run it again.",
            result.stdout.trim(),
        )
        assertContentEquals(before, file.readBytes(), "loop tool writes nothing to the tracker")
        assertEquals(listOf<ToolRequest>(ToolRequest.Comment(text.trim())), queued())
        assertEquals(0, commentedEvents())
        val requested =
            fixture.query("SELECT project_id, issue_id, attempt_id, data FROM events WHERE kind = 'issue_comment_requested'").single()
        assertEquals(projectId, requested["project_id"])
        assertEquals("t-1", requested["issue_id"])
        assertEquals(ATTEMPT, requested["attempt_id"])
        assertContains(requested["data"]!!, "\"chars\"")
        assertContains(requested["data"]!!, "${text.trim().length}")
        assertFalse(text.trim() in requested["data"]!!)
    }

    @Test
    fun `the request event records the length and never the text, and trace shows it`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val text = "Is two chars enough?"

        val result = fixture.inProcess("tool", "comment", text, cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(text in fixture.query("SELECT * FROM events").joinToString { it.toString() })

        val trace = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)

        assertEquals(0, trace.exitCode, trace.toString())
        assertTrue(trace.stdout.lines().any { "issue_comment_requested" in it && "chars=${text.length}" in it }, trace.stdout)
        assertFalse(text in trace.stdout, trace.stdout)
    }

    private fun commentedEvents(): Int = count("issue_commented")

    private fun requestedEvents(): Int = count("issue_comment_requested")

    private fun count(kind: String): Int =
        if (!fixture.storeFile.exists()) {
            0
        } else {
            fixture.query("SELECT count(*) AS n FROM events WHERE kind = '$kind'").single()["n"]!!.toInt()
        }

    private companion object {
        const val ATTEMPT = "7c1e9d2a-0000-4000-8000-000000000001"
    }
}
