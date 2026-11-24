package loop.cli

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m1-36: when `loop run` starts and holds the run lock, every Attempt left open by a dead process is closed
 * with the reason `abandoned`, and that process gets a `run_stopped` with the reason `abandoned` when it had none.
 */
class AbandonedAttemptTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val project by lazy { ProjectId(fixture.projectId()) }

    @Test
    fun `after kill -9 and a restart the dead attempt is abandoned in trace, status and show`() {
        fixture.issue("t-1")
        val firstPid = root.resolve("agent-1.pid")
        fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$firstPid'\nexec sleep 60\n")
        val first = fixture.start("run", fixture.workflow.toString())
        val secondPid = root.resolve("agent-2.pid")
        var second: LoopFixture.RunningLoop? = null
        try {
            awaitPid(firstPid) ?: fail("the first agent never started\n${first.await(30)}")
            // kill -9 of loop run mid turn: nothing of the run gets to record its end.
            kill9(first.process.pid())
            assertTrue(first.process.waitFor(30, TimeUnit.SECONDS), "loop run outlived kill -9")
            readPid(firstPid)?.let(::kill9)
            val dead = fixture.query("SELECT attempt_id, run_id FROM events WHERE kind = 'attempt_started'").single()
            val deadAttempt = dead["attempt_id"]!!
            val deadRun = dead["run_id"]!!

            // Trace stays read only: before a restart the dead attempt still shows as running.
            val before = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
            assertTrue(header(before.stdout, 1).endsWith("running"), before.toString())

            fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$secondPid'\nexec sleep 60\n")
            second = fixture.start("run", fixture.workflow.toString())
            awaitPid(secondPid) ?: fail("the restarted run started no attempt\n${second.await(30)}")
            val newRun =
                fixture
                    .query(
                        "SELECT run_id FROM events WHERE kind = 'attempt_started' AND run_id <> '$deadRun'",
                    ).single()["run_id"]!!

            val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
            assertEquals(0, trace.exitCode, trace.toString())
            val deadHeader = header(trace.stdout, 1)
            assertContains(deadHeader, deadAttempt.take(8))
            assertFalse("running" in deadHeader, trace.toString())
            assertTrue(deadHeader.endsWith(", abandoned"), trace.toString())
            assertTrue(deadHeader.contains("canceled in "), trace.toString())
            val finishedLine = trace.stdout.lines().single { "attempt_finished" in it }
            assertContains(finishedLine, "outcome=canceled reason=abandoned detected_by_run=${newRun.take(8)}")
            assertTrue(header(trace.stdout, 2).endsWith("running"), trace.toString())

            // The new attempt runs; the dead one is not running in status.
            val status = fixture.inProcess("status", fixture.workflow.toString())
            assertEquals(0, status.exitCode, status.toString())
            val running = status.stdout.lines().filter { it.startsWith("running") }
            assertEquals(1, running.size, status.toString())
            assertContains(running.single(), "attempt 2")
            assertFalse(deadAttempt.take(8) in status.stdout, status.toString())

            val show = fixture.inProcess("show", deadAttempt, "--events", "--workflow", fixture.workflow.toString())
            assertEquals(0, show.exitCode, show.toString())
            val finished = show.stdout.lines().single { "\"kind\":\"attempt_finished\"" in it }
            assertContains(finished, "\"reason\":\"abandoned\"")
            assertContains(finished, "\"outcome\":\"canceled\"")
            assertContains(finished, "\"detected_by_run\":\"$newRun\"")
            assertContains(finished, "\"run_id\":\"$deadRun\"")

            val stopped = fixture.query("SELECT run_id, data FROM events WHERE kind = 'run_stopped'").single()
            assertEquals(deadRun, stopped["run_id"])
            val data = stopped["data"]!!
            assertContains(data, "\"reason\":\"abandoned\"")
            assertContains(data, "\"attempts_canceled\":\"1\"")
            assertContains(data, "\"detected_by_run\":\"$newRun\"")

            second.process.destroy()
            val ended = second.await(30)
            val warnings = ended.stderr.lines().filter { "level=warn" in it && "event=attempt " in it && "reason=abandoned" in it }
            assertEquals(1, warnings.size, ended.toString())
            assertContains(warnings.single(), "attempt_id=$deadAttempt")
        } finally {
            first.process.destroyForcibly()
            second?.process?.destroyForcibly()
            readPid(firstPid)?.let(::kill9)
            readPid(secondPid)?.let(::kill9)
        }
    }

    @Test
    fun `open attempts of dead processes are closed once and each dead process without run_stopped gets one`() {
        // Issues in a terminal state: the run dispatches nothing, trace can still find them.
        fixture.issue("t-1", state = "done")
        fixture.issue("t-2", state = "done")
        fixture.seed(
            started(FINISHED, "t-1", RUN_A),
            finished(FINISHED, "t-1", RUN_A),
            started(OPEN_1, "t-1", RUN_A),
            started(OPEN_2, "t-2", RUN_A),
            // Run B stopped after its attempt started, so it keeps its own run_stopped.
            started(OPEN_3, "t-2", RUN_B),
            runStopped(RUN_B),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val closed =
            fixture.query(
                "SELECT run_id, issue_id, attempt_id, data FROM events WHERE kind = 'attempt_finished' AND data LIKE '%abandoned%' ORDER BY seq",
            )
        assertEquals(listOf(OPEN_1, OPEN_2, OPEN_3), closed.map { it["attempt_id"] })
        assertEquals(listOf(RUN_A, RUN_A, RUN_B), closed.map { it["run_id"] })
        assertEquals(listOf("default:t-1", "default:t-2", "default:t-2"), closed.map { it["issue_id"] })
        closed.forEach { row ->
            assertContains(row["data"]!!, "\"outcome\":\"canceled\"")
            assertContains(row["data"]!!, "\"reason\":\"abandoned\"")
            assertContains(row["data"]!!, "\"detected_by_run\"")
        }
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_finished' AND attempt_id = '$FINISHED'").size)
        val stopped = fixture.query("SELECT run_id, data FROM events WHERE kind = 'run_stopped' ORDER BY seq")
        assertEquals(listOf(RUN_B, RUN_A), stopped.map { it["run_id"] })
        assertContains(stopped.last()["data"]!!, "\"reason\":\"abandoned\"")
        assertContains(stopped.last()["data"]!!, "\"attempts_canceled\":\"2\"")
        val warnings = result.stderr.lines().filter { "level=warn" in it && "event=attempt " in it && "reason=abandoned" in it }
        assertEquals(3, warnings.size, result.toString())

        // A second start finds nothing left open and records nothing more.
        val again = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, again.exitCode, again.toString())
        assertEquals(3, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_finished' AND data LIKE '%abandoned%'").size)
        assertEquals(2, fixture.query("SELECT 1 FROM events WHERE kind = 'run_stopped'").size)
        assertFalse("reason=abandoned" in again.stderr, again.toString())
    }

    @Test
    fun `an abandoned attempt is no failure, the next attempt runs at once and its failure is the first`() {
        // With two attempts allowed, an abandoned attempt counted as a failure would park the issue now.
        fixture.writeWorkflow(extra = "retries:\n  max_attempts: 2")
        fixture.useFailingAgent()
        fixture.issue("t-1")
        fixture.seed(started(OPEN_1, "t-1", RUN_A))

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val kinds =
            fixture
                .query(
                    "SELECT kind, attempt_id FROM events " +
                        "WHERE kind IN ('attempt_started', 'attempt_finished', 'retry_scheduled', 'issue_parked') ORDER BY seq",
                ).map { "${it["kind"]}:${if (it["attempt_id"] == OPEN_1) "dead" else "new"}" }
        assertEquals(
            listOf("attempt_started:dead", "attempt_finished:dead", "attempt_started:new", "attempt_finished:new", "retry_scheduled:new"),
            kinds,
        )
        assertEquals(1, fixture.agentCalls(), "the next attempt is dispatched on the first tick, without a backoff")
        val retry = fixture.query("SELECT data FROM events WHERE kind = 'retry_scheduled'").single()["data"]!!
        assertContains(retry, "\"failures\":\"1\"")
        assertContains(retry, "\"attempt\":\"1\"")
        assertContains(retry, "\"delay_ms\":\"10000\"")
    }

    private fun started(
        attempt: String,
        issue: String,
        run: String,
    ) = RecordedEvent(
        ts = Clock.System.now(),
        projectId = project,
        kind = "attempt_started",
        runId = RunId(run),
        issueId = issue,
        issueIdentifier = issue,
        attemptId = AttemptId(attempt),
    )

    private fun finished(
        attempt: String,
        issue: String,
        run: String,
    ) = started(attempt, issue, run).copy(kind = "attempt_finished", data = mapOf("outcome" to "succeeded"))

    private fun runStopped(run: String) =
        RecordedEvent(
            ts = Clock.System.now(),
            projectId = project,
            kind = "run_stopped",
            runId = RunId(run),
            data = mapOf("reason" to "signal", "attempts_canceled" to "0"),
        )

    /** The header line of the [number]th attempt block of a trace. */
    private fun header(
        trace: String,
        number: Int,
    ): String = trace.lines().singleOrNull { it.startsWith("Attempt $number ") } ?: fail("no attempt $number in\n$trace")

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
        const val RUN_A = "aaaaaaaa-0000-4000-8000-000000000001"
        const val RUN_B = "bbbbbbbb-0000-4000-8000-000000000002"
        const val FINISHED = "f1000000-0000-4000-8000-000000000001"
        const val OPEN_1 = "a1000000-0000-4000-8000-000000000001"
        const val OPEN_2 = "a2000000-0000-4000-8000-000000000002"
        const val OPEN_3 = "b3000000-0000-4000-8000-000000000003"
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
    }
}
