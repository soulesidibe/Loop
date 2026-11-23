package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m2-02: claims, the retry queue and its counters live in the machine level store, so a `loop run`
 * restart resumes where the last one stopped. Time is controlled through `agent.max_retry_backoff_ms`,
 * which puts each due time a few seconds out, and checked against the timestamps the store recorded.
 */
class RestartStateTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a queued retry survives a restart and fires at its original due time, not at once`() {
        fixture.issue("t-1")
        fixture.useFailingAgent()
        fixture.writeWorkflow()

        val first = fixture.loop("run", "--once", fixture.workflow.toString())
        assertEquals(0, first.exitCode, first.toString())
        val stored = fixture.query("SELECT attempt, due_at, error, failures FROM retry_states").single()
        assertEquals("1", stored["attempt"], stored.toString())
        assertEquals("1", stored["failures"], stored.toString())
        // The first retry waits 10 s, too short for a loaded machine to end one JVM and start the next
        // (bl-107). The test moves the due time out, so the restart lands before it.
        val dueAt = Clock.System.now() + RESTART_WINDOW
        fixture.update("UPDATE retry_states SET due_at = '$dueAt'")

        val second = fixture.start("run", fixture.workflow.toString())
        try {
            awaitAgentCalls(2) ?: fail("the restored retry never fired\n${second.await(30)}")
        } finally {
            second.process.destroy()
        }
        val ended = second.await(30)
        val firstRun = fixture.query("SELECT run_id FROM events WHERE kind = 'attempt_started' ORDER BY seq").first()["run_id"]
        val restored =
            fixture
                .query("SELECT ts, run_id, data FROM events WHERE kind = 'retry_scheduled' AND data LIKE '%\"restored\":\"true\"%'")
                .single()
        assertTrue(restored["run_id"] != firstRun, "the restored retry is recorded in the new run\n$ended")
        assertContains(restored["data"]!!, "\"due_at\":\"$dueAt\"")
        // The new run started, and restored the entry, before the due time: it waited for it.
        val restoredAt = Instant.parse(restored["ts"]!!)
        assertTrue(restoredAt < dueAt, "the restart came after the due time ($restoredAt, $dueAt), the test proves nothing\n$ended")
        val retried =
            fixture
                .query("SELECT ts FROM events WHERE kind = 'attempt_started' AND run_id = '${restored["run_id"]}' ORDER BY seq")
                .first()
        val retriedAt = Instant.parse(retried["ts"]!!)
        assertTrue(retriedAt >= dueAt, "the retry fired at $retriedAt, before its due time $dueAt\n$ended")
    }

    @Test
    fun `the failure counter survives a restart, so max_attempts parks on the attempts of both runs`() {
        fixture.issue("t-1")
        fixture.useFailingAgent()
        fixture.writeWorkflow(extra = "agent:\n  max_retry_backoff_ms: 1000\nretries:\n  max_attempts: 2")

        val first = fixture.loop("run", "--once", fixture.workflow.toString())
        assertEquals(0, first.exitCode, first.toString())
        assertEquals("1", fixture.query("SELECT failures FROM retry_states").single()["failures"])
        val dueAt = Instant.parse(fixture.query("SELECT due_at FROM retry_states").single()["due_at"]!!)
        while (Clock.System.now() <= dueAt) Thread.sleep(POLL_MS)

        val second = fixture.loop("run", "--once", fixture.workflow.toString())
        assertEquals(0, second.exitCode, second.toString())
        assertEquals(2, fixture.agentCalls(), second.toString())
        val parked = fixture.query("SELECT issue_id, reason FROM parked_issues").single()
        assertEquals("t-1", parked["issue_id"])
        assertEquals("max_attempts", parked["reason"])
        val event = fixture.query("SELECT data FROM events WHERE kind = 'issue_parked'").single()
        assertContains(event["data"]!!, "\"failures\":\"2\"")
        assertEquals(emptyList(), fixture.query("SELECT * FROM retry_states"))
    }

    @Test
    fun `a claim left by a dead run is released at startup, recorded, and its issue dispatched once`() {
        fixture.issue("t-1")
        val pidFile = root.resolve("agent.pid")
        val failedOnce = root.resolve("failed-once")
        // The first attempt fails, so a retry is queued; the retry's attempt hangs until the run is killed.
        writeAgent(
            """
            |#!/bin/sh
            |if [ ! -e '$failedOnce' ]; then touch '$failedOnce'; exit 1; fi
            |echo ${'$'}${'$'} > '$pidFile'
            |exec sleep 60
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(extra = "agent:\n  max_retry_backoff_ms: 1000")
        val dead = fixture.start("run", fixture.workflow.toString())
        val deadClaim: Map<String, String?>
        try {
            awaitPid(pidFile) ?: fail("the retry never started\n${dead.await(30)}")
            deadClaim = fixture.query("SELECT issue_id, attempt_id, run_id, session_id FROM claims").single()
            kill9(dead.process.pid())
            assertTrue(dead.process.waitFor(30, TimeUnit.SECONDS), "loop run outlived kill -9")
        } finally {
            dead.process.destroyForcibly()
            readPid(pidFile)?.let(::kill9)
        }
        assertEquals("t-1", deadClaim["issue_id"])
        val deadAttempt = assertNotNull(deadClaim["attempt_id"])
        val deadRun = assertNotNull(deadClaim["run_id"])
        val deadSession = assertNotNull(deadClaim["session_id"], "the claim carries the session of its attempt")
        assertEquals(
            deadAttempt,
            fixture.query("SELECT attempt_id FROM events WHERE kind = 'attempt_started' ORDER BY seq").last()["attempt_id"],
        )
        // The dead run also left its retry entry, already due.
        assertEquals(1, fixture.query("SELECT * FROM retry_states WHERE due_at IS NOT NULL").size)

        writeAgent("#!/bin/sh\necho \"${'$'}LOOP_ATTEMPT_ID\" >> '${fixture.agentCallsFile}'\nexit 0\n")
        val restarted = fixture.loop("run", "--once", fixture.workflow.toString())
        assertEquals(0, restarted.exitCode, restarted.toString())

        assertEquals(emptyList(), fixture.query("SELECT * FROM claims"), restarted.toString())
        val released = fixture.query("SELECT run_id, attempt_id, session_id, data FROM events WHERE kind = 'claim_released'").single()
        assertEquals(deadRun, released["run_id"])
        assertEquals(deadAttempt, released["attempt_id"])
        assertEquals(deadSession, released["session_id"])
        assertContains(released["data"]!!, "\"reason\":\"abandoned\"")
        val newRun = fixture.query("SELECT run_id FROM events WHERE kind = 'attempt_started' ORDER BY seq").last()["run_id"]!!
        assertContains(released["data"]!!, "\"detected_by_run\":\"$newRun\"")
        val warning = restarted.stderr.lines().single { "event=claim outcome=released" in it }
        assertContains(warning, "attempt_id=$deadAttempt")
        assertContains(warning, "reason=abandoned")
        // Never dispatched twice: one attempt in the new run, through the retry entry, not also as a candidate.
        assertEquals(1, fixture.query("SELECT * FROM events WHERE kind = 'attempt_started' AND run_id = '$newRun'").size)
        // The agent logs one line per turn; every line carries the one new attempt.
        val calledAttempts =
            fixture.agentCallsFile
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .toSet()
        assertEquals(1, calledAttempts.size, restarted.toString())
    }

    private fun writeAgent(script: String) {
        fixture.agentScript.writeText(script)
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun awaitAgentCalls(count: Int): Int? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (fixture.agentCalls() < count && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return fixture.agentCalls().takeIf { it >= count }
    }

    private fun kill9(pid: Long) {
        ProcessBuilder("kill", "-9", pid.toString()).start().waitFor(10, TimeUnit.SECONDS)
    }

    private fun readPid(file: Path): Long? = if (file.exists()) file.readText().trim().toLongOrNull() else null

    private fun awaitPid(file: Path): Long? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (readPid(file) == null && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return readPid(file)
    }

    private companion object {
        /**
         * How far out the queued retry falls due: long enough for a JVM to start and restore it first, short
         * enough for a test. The 10 s of the first backoff were too short with three agents running at once:
         * the restart came up to 8 s after the due time (bl-107).
         */
        val RESTART_WINDOW = 30.seconds
        const val POLL_MS = 50L
        val WAIT_NANOS = TimeUnit.SECONDS.toNanos(60)
    }
}
