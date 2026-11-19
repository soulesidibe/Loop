package loop.cli

import loop.api.ProjectId
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.core.orchestrator.attemptsFolder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m1-23: `loop run` sweeps the attempt directories a dead run left, dropping their requests unapplied. */
class AttemptSweepTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a leftover attempt directory is swept at startup and its requests are dropped, not applied`() {
        // No issue file: the run itself starts no attempt.
        fixture.seed(fixture.attemptStarted(LEFTOVER, "t-1"))
        val dir = fixture.attemptDir(LEFTOVER)
        dir.resolve("requests").createDirectories()
        ToolRequestQueue(dir).enqueue(ToolRequest.Move("review"))
        // m1-22's attempt files share the parent folder: a file is never swept.
        val attemptFile = attemptsFolder(fixture.loopHome, ProjectId(fixture.projectId())).resolve("$LEFTOVER.jsonl.gz")
        attemptFile.writeText("kept")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(dir.exists(), "the leftover attempt directory is deleted")
        assertTrue(attemptFile.exists(), "a file next to the attempt directories is kept")
        val dropped =
            fixture.query("SELECT issue_id, attempt_id, data FROM events WHERE kind = 'tool_request_dropped'").single()
        assertEquals("t-1", dropped["issue_id"])
        assertEquals(LEFTOVER, dropped["attempt_id"])
        val data = dropped["data"]!!
        assertTrue("\"request\"" in data && "\"move\"" in data && "\"to\"" in data && "\"review\"" in data, data)
        assertEquals(0, fixture.query("SELECT count(*) AS n FROM events WHERE kind = 'issue_moved'").single()["n"]!!.toInt())
    }

    private companion object {
        const val LEFTOVER = "3d9a0c1e-0000-4000-8000-000000000001"
    }
}
