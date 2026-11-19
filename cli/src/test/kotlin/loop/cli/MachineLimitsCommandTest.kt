package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

/**
 * m3-16, end to end through the installed launcher: two `loop run` processes on two projects of one machine share
 * `<LOOP_HOME>/config.yaml` and the store, and never run more agents together than the machine cap.
 */
class MachineLimitsCommandTest {
    @TempDir
    lateinit var root: Path

    private val loopHome: Path by lazy { root.resolve("loop-home").createDirectories() }
    private val first by lazy { LoopFixture(root.resolve("first").createDirectories(), loopHome) }
    private val second by lazy { LoopFixture(root.resolve("second").createDirectories(), loopHome) }

    /** One `start <identifier>` and one `end <identifier>` line per agent run, in the order they happened, on the whole machine. */
    private val agentLog: Path by lazy { root.resolve("agents.log") }

    /** An agent that logs its start, works for a while, logs its end and hands the issue off. */
    private fun useSlowAgent(fixture: LoopFixture) {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "start ${'$'}LOOP_ISSUE_IDENTIFIER" >> "$agentLog"
            |sleep 2
            |echo "end ${'$'}LOOP_ISSUE_IDENTIFIER" >> "$agentLog"
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The most agents the log shows running at once. */
    private fun peak(lines: List<String>): Int {
        var running = 0
        var peak = 0
        lines.forEach { line ->
            if (line.startsWith("start ")) {
                running++
            } else if (line.startsWith("end ")) {
                running--
            }
            peak = maxOf(peak, running)
        }
        return peak
    }

    @Test
    fun `two loop run processes on two projects never run more agents together than the machine cap`() {
        loopHome.resolve("config.yaml").writeText("machine:\n  max_concurrent_agents: 1\n")
        listOf(first to "a", second to "b").forEach { (fixture, prefix) ->
            useSlowAgent(fixture)
            fixture.issue("$prefix-1")
            fixture.issue("$prefix-2")
        }
        val runs = listOf(first, second).map { it.start("run", it.workflow.toString()) }
        try {
            val deadline = System.nanoTime() + WAIT_NANOS
            while (System.nanoTime() < deadline && !(agentLog.exists() && agentLog.readLines().count { it.startsWith("end ") } == 4)) {
                Thread.sleep(POLL_MS)
            }
            val lines = if (agentLog.exists()) agentLog.readLines() else emptyList()
            if (lines.count { it.startsWith("end ") } != 4) {
                fail("the four agents never ended\n$lines\n${runs.joinToString("\n") { it.stdoutSoFar() }}")
            }
            assertEquals(1, peak(lines), "at most one agent at a time on the machine: $lines")
            assertEquals(setOf("a-1", "a-2", "b-1", "b-2"), lines.filter { it.startsWith("end ") }.map { it.removePrefix("end ") }.toSet())
            // Every slot is deleted at its attempt end.
            val slotsDeadline = System.nanoTime() + WAIT_NANOS
            while (System.nanoTime() < slotsDeadline && first.query("SELECT holder FROM machine_slots").isNotEmpty()) Thread.sleep(POLL_MS)
            assertEquals(emptyList(), first.query("SELECT holder FROM machine_slots"))
            assertContains(
                first.query("SELECT DISTINCT data FROM events WHERE kind = 'candidate_skipped'").joinToString(),
                "no_machine_slot",
            )
            // A run checks its own slots without reading its own run file, so it keeps its lock and `loop stop` finds it live.
            listOf(first, second).zip(runs).forEach { (fixture, run) ->
                val stop = fixture.loop("stop", fixture.workflow.toString())
                assertEquals(0, stop.exitCode, stop.toString())
                assertContains(stop.stdout, "Stopped run")
                assertEquals(0, run.await(30).exitCode)
            }
        } finally {
            runs.forEach { run ->
                run.process.descendants().forEach { it.destroyForcibly() }
                run.process.destroyForcibly()
                run.process.waitFor()
            }
        }
    }

    @Test
    fun `a malformed machine config fails loop run at startup naming the key`() {
        loopHome.resolve("config.yaml").writeText("machine:\n  per_day_usd: lots\n")
        first.issue("a-1")

        val result = first.loop("run", "--once", first.workflow.toString())

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "machine.per_day_usd must be a positive number of dollars, got \"lots\"")
        assertEquals("todo", first.stateOf("a-1"))
    }

    private companion object {
        const val WAIT_NANOS = 90_000_000_000L
        const val POLL_MS = 200L
    }
}
