package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m2-04, end to end: while the check of a submitted result runs, the issue is `Waiting(verify)` in the store,
 * `loop status` lists it and later polls never dispatch it. The wait survives a `kill -9` of the run, and the
 * next `loop run` runs the check again before anything else. Time is controlled by a check that blocks until
 * the test creates a release file, and by a 100 ms polling interval, so each wait spans many polls.
 */
class WaitingCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val release: Path by lazy { root.resolve("release-check") }
    private val checkPid: Path by lazy { root.resolve("check.pid") }

    /** Loop mode with a check that writes its pid, then blocks until [release] exists. The agent logs its calls. */
    private fun slowCheckWorkflow() {
        val check = root.resolve("check.sh")
        check.writeText(
            """
            |#!/bin/sh
            |echo ${'$'}${'$'} > '$checkPid'
            |while [ ! -e '$release' ]; do sleep 0.05; done
            |test -f hello.txt
            |
            """.trimMargin(),
        )
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${'$'}LOOP_ATTEMPT_ID" >> '${fixture.agentCallsFile}'
            |echo "hello" > hello.txt
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "sh $check" }
                """.trimMargin(),
        )
        fixture.workflow.writeText(fixture.workflow.readText().replace("interval_ms: 1000", "interval_ms: $POLL_INTERVAL_MS"))
    }

    @Test
    fun `while a slow check runs the issue waits for verify, loop status shows it and later polls do not dispatch it`() {
        fixture.issue("t-1")
        slowCheckWorkflow()
        val run = fixture.start("run", fixture.workflow.toString())
        try {
            val wait = awaitRow("SELECT * FROM waiting_issues") ?: fail("the issue never waited\n${run.stdoutSoFar()}")
            assertEquals("t-1", wait["identifier"])
            assertEquals("verify", wait["reason"])
            val attempt = fixture.query("SELECT attempt_id FROM events WHERE kind = 'attempt_started'").single()["attempt_id"]
            assertEquals(attempt, wait["attempt_id"])
            assertContains(wait["data"].orEmpty(), "\"to\":\"review\"")

            // Many polls go by while the check is held, with the issue still in todo.
            // The wait starts before the workspace fingerprint is taken (m2-05), so check_started can come a little later.
            val started =
                awaitRow("SELECT ts FROM events WHERE kind = 'check_started'")
                    ?: fail("the check never started\n${run.stdoutSoFar()}")
            val checkStarted = Instant.parse(started["ts"]!!)
            while (Clock.System.now() < checkStarted + kotlin.time.Duration.parse("${POLLS * POLL_INTERVAL_MS}ms")) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            assertEquals("todo", fixture.stateOf("t-1"))
            assertEquals(1, fixture.agentCalls(), "the waiting issue was dispatched again\n${run.stdoutSoFar()}")
            assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size)
            val status = fixture.loop("status", fixture.workflow.toString())
            assertEquals(0, status.exitCode, status.toString())
            val line = status.stdout.lines().singleOrNull { it.startsWith("waiting") } ?: fail("no waiting line\n$status")
            assertTrue(
                Regex("""^waiting +t-1 +attempt 1 +${attempt!!.take(8)}  reason=verify since=\S+Z$""").matches(line),
                line,
            )
            assertTrue(status.stdout.lines().none { it.startsWith("running") }, "a waiting issue is listed once\n$status")
            // Kept in the test report as the evidence of a waiting issue in loop status.
            println(status.stdout)

            release.createFile()
            awaitState("t-1", "review") ?: fail("the verified move never applied\n${run.stdoutSoFar()}")
            assertEquals(emptyList(), awaitEmpty("SELECT * FROM waiting_issues"))
            assertEquals(1, fixture.agentCalls())
        } finally {
            stop(run)
        }
    }

    @Test
    fun `a check interrupted by kill -9 still waits after the restart and runs again at startup, never dispatched meanwhile`() {
        fixture.issue("t-1")
        slowCheckWorkflow()
        val dead = fixture.start("run", fixture.workflow.toString())
        val deadAttempt: String
        try {
            awaitRow("SELECT * FROM waiting_issues") ?: fail("the issue never waited\n${dead.stdoutSoFar()}")
            val pid = awaitPid() ?: fail("the check never started\n${dead.stdoutSoFar()}")
            deadAttempt = fixture.query("SELECT attempt_id FROM events WHERE kind = 'attempt_started'").single()["attempt_id"]!!
            kill9(dead.process.pid())
            assertTrue(dead.process.waitFor(30, TimeUnit.SECONDS), "loop run outlived kill -9")
            kill9(pid)
        } finally {
            dead.process.destroyForcibly()
        }

        // The wait survives the dead process, and loop status still lists it without a live run.
        val kept = fixture.query("SELECT identifier, reason, attempt_id FROM waiting_issues").single()
        assertEquals(mapOf("identifier" to "t-1", "reason" to "verify", "attempt_id" to deadAttempt), kept)
        val between = fixture.loop("status", fixture.workflow.toString())
        assertContains(between.stdout, "No run in progress.")
        assertTrue(between.stdout.lines().any { it.startsWith("waiting") && "reason=verify" in it }, between.toString())

        checkPid.deleteIfExists()
        val restarted = fixture.start("run", fixture.workflow.toString())
        try {
            awaitPid() ?: fail("the check never ran again\n${restarted.stdoutSoFar()}")
            // The dead claim is released by the new run, which names itself in detected_by_run.
            val released = fixture.query("SELECT data FROM events WHERE kind = 'claim_released'").single()["data"]!!
            val newRun = Regex("\"detected_by_run\":\"([^\"]+)\"").find(released)!!.groupValues[1]
            val submitted =
                fixture.query("SELECT attempt_id, data FROM events WHERE kind = 'result_submitted' AND run_id = '$newRun'").single()
            assertEquals(deadAttempt, submitted["attempt_id"])
            assertContains(submitted["data"]!!, "\"restored\":\"true\"")
            val rerunStarted =
                Instant.parse(
                    fixture.query("SELECT ts FROM events WHERE kind = 'check_started' AND run_id = '$newRun'").single()["ts"]!!,
                )
            while (Clock.System.now() < rerunStarted + kotlin.time.Duration.parse("${POLLS * POLL_INTERVAL_MS}ms")) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            assertEquals("todo", fixture.stateOf("t-1"))
            assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started' AND run_id = '$newRun'").size)
            assertEquals(1, fixture.agentCalls())
            val status = fixture.loop("status", fixture.workflow.toString())
            assertTrue(status.stdout.lines().any { it.startsWith("waiting") && "reason=verify" in it }, status.toString())
            println(status.stdout)

            release.createFile()
            awaitState("t-1", "review") ?: fail("the rerun check never moved the issue\n${restarted.stdoutSoFar()}")
            assertEquals(emptyList(), awaitEmpty("SELECT * FROM waiting_issues"))
            val moved = fixture.query("SELECT run_id, attempt_id, data FROM events WHERE kind = 'issue_moved'").single()
            assertEquals(newRun, moved["run_id"])
            assertEquals(deadAttempt, moved["attempt_id"])
            assertContains(moved["data"]!!, "\"verified\":\"true\"")
            assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started' AND run_id = '$newRun'").size)
            assertEquals(1, fixture.agentCalls(), "the agent never ran again")
        } finally {
            stop(restarted)
        }
    }

    private fun stop(run: LoopFixture.RunningLoop) {
        release.takeIf { !it.exists() }?.createFile()
        run.process.destroy()
        if (!run.process.waitFor(30, TimeUnit.SECONDS)) {
            run.process.descendants().forEach { it.destroyForcibly() }
            run.process.destroyForcibly()
        }
    }

    private fun awaitRow(sql: String): Map<String, String?>? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            // The store does not exist until the run opened it.
            runCatching { fixture.query(sql) }.getOrNull()?.firstOrNull()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun awaitEmpty(sql: String): List<Map<String, String?>> {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (fixture.query(sql).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return fixture.query(sql)
    }

    private fun awaitState(
        id: String,
        state: String,
    ): String? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (fixture.stateOf(id) != state && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return fixture.stateOf(id).takeIf { it == state }
    }

    private fun awaitPid(): Long? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (checkPid.exists()) {
                checkPid
                    .readText()
                    .trim()
                    .toLongOrNull()
                    ?.let { return it }
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun kill9(pid: Long) {
        ProcessBuilder("kill", "-9", pid.toString()).start().waitFor(10, TimeUnit.SECONDS)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L

        /** How many polling intervals each wait spans before the test looks for a second dispatch. */
        const val POLLS = 15
        const val POLL_MS = 50L
        val WAIT_NANOS = TimeUnit.SECONDS.toNanos(40)
    }
}
