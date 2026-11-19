package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** `loop log <WORKFLOW.md> [--follow]` prints the human readable run log of the project (m1-39). */
class LogCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun writeLog(count: Int) {
        fixture.humanLog.parent.createDirectories()
        fixture.humanLog.writeText((1..count).joinToString("") { "17:02:${"%02d".format(it % 60)}  line $it\n" })
    }

    @Test
    fun `prints the last 50 lines of the project's log`() {
        writeLog(60)

        val result = fixture.loop("log", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines().filter { it.isNotEmpty() }
        assertEquals(50, lines.size, result.toString())
        assertTrue(lines.first().endsWith("  line 11"), lines.first())
        assertTrue(lines.last().endsWith("  line 60"), lines.last())
    }

    @Test
    fun `defaults to WORKFLOW md in the current folder and says when there is no log yet`() {
        val result = fixture.loop("log")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("No human log yet for this project, loop run writes it to ${fixture.humanLog}.", result.stdout.trim())
    }

    @Test
    fun `follow prints a line appended after it started`() {
        writeLog(3)
        val running = fixture.start("log", fixture.workflow.toString(), "--follow")
        try {
            awaitOutput(running) { "line 3" in it }
            fixture.humanLog.appendText("17:03:00  m1-13  turn 1 of 10 started\n")

            awaitOutput(running) { "17:03:00  m1-13  turn 1 of 10 started" in it }
        } finally {
            running.process.destroy()
            running.process.waitFor()
        }
    }

    @Test
    fun `a workflow that does not load exits 1`() {
        val result = fixture.loop("log", root.resolve("missing/WORKFLOW.md").toString())

        assertEquals(1, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith("loop log: "), result.stderr)
    }

    private fun awaitOutput(
        running: LoopFixture.RunningLoop,
        check: (String) -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (check(running.stdoutSoFar())) return
            if (!running.process.isAlive) fail("loop log --follow exited\n${running.stdoutSoFar()}")
            Thread.sleep(POLL_MS)
        }
        fail("no expected output in time\n${running.stdoutSoFar()}")
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
    }
}
