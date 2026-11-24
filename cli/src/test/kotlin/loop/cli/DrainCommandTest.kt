package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `loop drain` (m4-10, bl-110), end to end with the command runner through the installed launcher: the live run
 * dispatches nothing new, lets its running attempt finish, verify and deliver, then stops by itself with exit code 0.
 */
class DrainCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the agent marks each issue it started on, outside the project. */
    private val startedDir by lazy { root.resolve("started").createDirectories() }

    /** The agent waits for this file before it writes its result. */
    private val gate by lazy { root.resolve("gate") }

    /**
     * Loop mode with one agent at a time and a host side check: the agent marks its issue as started, waits for the
     * [gate], writes `hello.txt` and moves its issue to review, which the check verifies before the host delivers it.
     */
    private fun gatedAgent() {
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_concurrent_agents: 1
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |touch '$startedDir'/"${'$'}LOOP_ISSUE_IDENTIFIER"
            |n=0
            |while [ ! -f '$gate' ] && [ ${'$'}n -lt 600 ]; do sleep 0.1; n=${'$'}((n + 1)); done
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |loop tool move review
            |
            """.trimMargin(),
        )
    }

    private fun started(): List<String> = (startedDir.toFile().list() ?: emptyArray()).sorted()

    private fun await(
        what: String,
        running: LoopFixture.RunningLoop,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        stopForcibly(running)
        fail("$what never happened\n${running.await(30)}")
    }

    private fun stopForcibly(running: LoopFixture.RunningLoop) {
        running.process.descendants().forEach { it.destroyForcibly() }
        running.process.destroyForcibly()
    }

    @Test
    fun `loop drain lets the running attempt finish, verify and deliver, starts nothing new, then the run exits 0`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        gatedAgent()
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            await("an agent start", running) { started().isNotEmpty() }
            val first = started().single()
            val other = (listOf("t-1", "t-2") - first).single()

            val drain = fixture.loop("drain", fixture.workflow.toString())

            assertEquals(0, drain.exitCode, drain.toString())
            val pid = running.process.pid()
            assertContains(drain.stdout, "(pid $pid) is draining: nothing new starts, the running attempts finish, then the run stops.")
            val again = fixture.loop("drain", fixture.workflow.toString())
            assertEquals(0, again.exitCode, again.toString())
            assertContains(again.stdout, "(pid $pid) is already draining.")
            // The run notices the drain at its next poll; loop status then shows it with what still runs.
            var status = ""
            await("the drain in loop status", running) {
                status = fixture.loop("status", fixture.workflow.toString()).stdout
                "draining  run  since=" in status
            }
            assertContains(status, "  pid $pid  draining")
            assertContains(status, "still=$first  stops once these end, loop stop stops it at once")

            gate.writeText("")
            val run = running.await(60)

            assertEquals(0, run.exitCode, run.toString())
            assertEquals("review", fixture.stateOf(first), run.toString())
            assertEquals("todo", fixture.stateOf(other), run.toString())
            assertEquals(listOf(first), started(), "the drain started the agent of $other\n$run")
            val checks = fixture.query("SELECT data FROM events WHERE kind = 'check_finished'").map { it["data"].orEmpty() }
            assertContains(checks.single(), "\"exit_code\":\"0\"", message = run.toString())
            val draining = fixture.query("SELECT data FROM events WHERE kind = 'run_draining'").single()["data"].orEmpty()
            assertContains(draining, "\"attempts\":\"$first\"")
            assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'run_drained'").size, run.toString())
            assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'run_stopped'").size, run.toString())
            val runsDir = fixture.loopHome.resolve("runs")
            assertFalse(runsDir.resolve("${fixture.projectId()}.json").exists(), "run file left\n$run")
            assertFalse(runsDir.resolve("${fixture.projectId()}.drain").exists(), "drain marker left\n$run")
            val log = fixture.humanLog.readText()
            assertContains(log, "draining, nothing new starts, the run stops once $first end")
            assertContains(log, "run ended after loop drain")

            val trace = fixture.loop("trace", first, cwd = fixture.projectDir)
            assertEquals(0, trace.exitCode, trace.toString())
            val lines = trace.stdout.lines()
            assertTrue(lines.any { "run_draining" in it && "attempts=$first" in it }, trace.stdout)
            assertTrue(lines.any { "run_drained" in it && "drained=" in it }, trace.stdout)
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `loop drain with no live run says so and exits nonzero`() {
        fixture.issue("t-1")

        val result = fixture.loop("drain", fixture.workflow.toString())

        assertEquals(1, result.exitCode, result.toString())
        assertEquals("loop drain: no run in progress for project ${fixture.projectId()}. Nothing to drain.", result.stderr.trim())
        assertFalse(fixture.loopHome.resolve("runs/${fixture.projectId()}.drain").exists(), "a drain marker without a run")
    }

    private companion object {
        const val WAIT_NANOS = 60_000_000_000L
        const val POLL_MS = 200L
    }
}
