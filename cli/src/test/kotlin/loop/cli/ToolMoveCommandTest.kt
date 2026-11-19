package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.ToolRequest
import loop.tracker.github.GitHubTracker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Plan seam 9, D11 and U2, end to end through the installed launcher; acceptance 6. */
class ToolMoveCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `an undefined state is refused with exit 2`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.loop("tool", "move", "reviewed", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(2, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: \"reviewed\" is not a state of the files tracker. " +
                "Valid states: todo, in_progress, review, needs_info, done, canceled. Nothing queued.",
            result.stderr.trim(),
        )
        assertContentEquals(before, file.readBytes())
        assertEquals(0, movedEvents())
    }

    @Test
    fun `outside an attempt nothing moves and the exit is 4`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        val env = fixture.toolEnv(ATTEMPT, "t-1") + ("LOOP_ATTEMPT_ID" to null)

        val result = fixture.loop("tool", "move", "review", cwd = root, env = env)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: not inside an attempt. Run it from the agent process Loop started, " +
                "where LOOP_ATTEMPT_ID, LOOP_ISSUE_ID and LOOP_TOOL_CONFIG are set. Nothing queued.",
            result.stderr.trim(),
        )
        assertContentEquals(before, file.readBytes())
        assertEquals(0, movedEvents())
    }

    @Test
    fun `a finished attempt moves nothing and the exit is 5`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptFinished(ATTEMPT, "t-1"))

        val result = fixture.loop("tool", "move", "review", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(5, result.exitCode, result.toString())
        val message = result.stderr.trim()
        assertTrue(message.startsWith("loop tool move: attempt ${ATTEMPT.take(8)} is not live (finished at "), message)
        assertTrue(message.endsWith("). Nothing queued."), message)
        assertContentEquals(before, file.readBytes())
        assertEquals(0, movedEvents())
    }

    @Test
    fun `an attempt of another issue moves nothing and the exit is 5`() {
        val mine = fixture.issue("t-1")
        val other = fixture.issue("t-3")
        val before = mapOf(mine to mine.readBytes(), other to other.readBytes())
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-3"))

        val result = fixture.loop("tool", "move", "review", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(5, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: attempt ${ATTEMPT.take(8)} belongs to issue t-3, not t-1. Nothing queued.",
            result.stderr.trim(),
        )
        before.forEach { (file, bytes) -> assertContentEquals(bytes, file.readBytes(), "$file changed") }
        assertEquals(0, movedEvents())
    }

    @Test
    fun `17_5 an unsupported tool name fails at once with a usage error and queues nothing`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.loop("tool", "close", "done", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "close")
        assertEquals("todo", fixture.stateOf("t-1"))
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind LIKE 'issue_%_requested'").size)
    }

    @Test
    fun `a missing state is a usage error with exit 2`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.loop("tool", "move", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"))
    }

    @Test
    fun `m2-23 with the github tracker a move to review or needs_info is queued, and another state is refused`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        // The snapshot of a github workflow: its kind and the tracker's defined states, as the orchestrator writes them.
        val config = Path.of(checkNotNull(env["LOOP_TOOL_CONFIG"]))
        val snapshot = Json.parseToJsonElement(config.readText()).jsonObject
        val github =
            snapshot +
                mapOf(
                    "tracker_kind" to JsonPrimitive("github"),
                    "defined_states" to JsonArray(GitHubTracker.DEFINED_STATES.map(::JsonPrimitive)),
                )
        config.writeText(JsonObject(github).toString())

        val review = fixture.loop("tool", "move", "review", cwd = root, env = env)
        val needsInfo = fixture.loop("tool", "move", "needs_info", cwd = root, env = env)
        val done = fixture.loop("tool", "move", "done", cwd = root, env = env)

        assertEquals(0, review.exitCode, review.toString())
        assertEquals(0, needsInfo.exitCode, needsInfo.toString())
        assertEquals(
            listOf<ToolRequest>(ToolRequest.Move("review"), ToolRequest.Move("needs_info")),
            fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request },
        )
        assertEquals(2, done.exitCode, done.toString())
        assertEquals(
            "loop tool move: \"done\" is not a state of the github tracker. Valid states: open, closed, review, needs_info. Nothing queued.",
            done.stderr.trim(),
        )
    }

    @Test
    fun `a move is queued from the snapshot alone, with no LOOP_WORKFLOW and WORKFLOW md deleted`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val projectId = fixture.projectId()
        Files.delete(fixture.workflow)

        val result = fixture.loop("tool", "move", "review", cwd = root, env = env)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            "Queued a move of t-1 to \"review\". Loop applies this in the tracker when this turn ends. Do not run it again.",
            result.stdout.trim(),
        )
        assertContentEquals(before, file.readBytes(), "loop tool writes nothing to the tracker")
        assertEquals(
            listOf<ToolRequest>(ToolRequest.Move("review")),
            fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request },
        )
        assertEquals(0, movedEvents())
        val requested =
            fixture.query("SELECT project_id, issue_id, attempt_id, data FROM events WHERE kind = 'issue_move_requested'").single()
        assertEquals(projectId, requested["project_id"])
        assertEquals("t-1", requested["issue_id"])
        assertEquals(ATTEMPT, requested["attempt_id"])
        assertTrue(requested["data"]!!.contains("\"to\"") && requested["data"]!!.contains("review"), requested["data"])
    }

    @Test
    fun `an unreadable snapshot moves nothing and the exit is 4`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val config = fixture.attemptDir(ATTEMPT).resolve("tool-config.json")
        config.writeText("not json")

        val result = fixture.loop("tool", "move", "review", cwd = root, env = env)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: cannot read the attempt config at $config: it is not valid JSON. " +
                "The attempt may have ended; Loop removes this file when an attempt finishes. Nothing queued.",
            result.stderr.trim(),
        )
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
    }

    @Test
    fun `a snapshot of another version moves nothing and the exit is 4`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val config = fixture.attemptDir(ATTEMPT).resolve("tool-config.json")
        config.writeText(config.readText().replace("\"version\":1", "\"version\":2"))

        val result = fixture.loop("tool", "move", "review", cwd = root, env = env)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: the attempt config at $config has version 2, this loop reads version 1. " +
                "Update the loop launcher on PATH. Nothing queued.",
            result.stderr.trim(),
        )
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
    }

    @Test
    fun `a requests folder that is gone queues nothing and the exit is 5`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        Files.delete(fixture.attemptDir(ATTEMPT).resolve("requests"))

        val result = fixture.loop("tool", "move", "review", cwd = root, env = env)

        assertEquals(5, result.exitCode, result.toString())
        assertEquals(
            "loop tool move: attempt ${ATTEMPT.take(8)} is not live (its attempt directory is gone). Nothing queued.",
            result.stderr.trim(),
        )
        assertEquals(0, fixture.query("SELECT count(*) AS n FROM events WHERE kind = 'issue_move_requested'").single()["n"]!!.toInt())
    }

    private fun movedEvents(): Int =
        if (!fixture.storeFile.exists()) {
            0
        } else {
            fixture.query("SELECT count(*) AS n FROM events WHERE kind = 'issue_moved'").single()["n"]!!.toInt()
        }

    private companion object {
        const val ATTEMPT = "7c1e9d2a-0000-4000-8000-000000000001"
    }
}
