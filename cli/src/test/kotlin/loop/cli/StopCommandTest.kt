package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
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

/** `loop stop` and the run file of `loop run` (m1-09, acceptance 5), end to end through the installed launcher. */
class StopCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the slow agent marks each issue it started on, outside the project. */
    private val startedDir by lazy { root.resolve("started").createDirectories() }

    /** An agent that marks its issue as started, then runs until it is stopped. */
    private fun useSlowAgent() {
        fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$startedDir'/\"\$LOOP_ISSUE_IDENTIFIER\"\nsleep 30\n")
    }

    private fun awaitStarted(vararg identifiers: String): Boolean {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (identifiers.all { startedDir.resolve(it).exists() }) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Starts `loop run` with the slow agent and waits until every issue of [identifiers] runs. */
    private fun startRunning(vararg identifiers: String): LoopFixture.RunningLoop {
        useSlowAgent()
        val running = fixture.start("run", fixture.workflow.toString())
        if (!awaitStarted(*identifiers)) {
            running.process.destroy()
            fail("the agents never started\n${running.await(30)}")
        }
        return running
    }

    private fun stopForcibly(running: LoopFixture.RunningLoop) {
        running.process.descendants().forEach { it.destroyForcibly() }
        running.process.destroyForcibly()
    }

    @Test
    fun `loop run refuses a second run for the same project`() {
        fixture.issue("t-1")
        val running = startRunning("t-1")
        try {
            val second = fixture.loop("run", fixture.workflow.toString(), "--once")

            assertEquals(1, second.exitCode, second.toString())
            assertContains(
                second.stderr,
                "loop run: a run is already in progress for project ${fixture.projectId()} " +
                    "(pid ${running.process.pid()}). Run loop stop first.",
            )
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `loop stop ends every running attempt keeps every workspace and the stop appears in the trace`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        val running = startRunning("t-1", "t-2")
        try {
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val run = running.await(30)

            assertEquals(0, stop.exitCode, stop.toString())
            // m1-34: a stop that Loop handled is clean, so the run exits 0 and not 143 (SIGTERM).
            assertEquals(0, run.exitCode, run.toString())
            listOf("t-1", "t-2").forEach { id ->
                val pid = agentPid(id)
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the agent of $id outlived the stop\n$run")
            }
            val runId = fixture.query("SELECT DISTINCT run_id FROM events WHERE run_id IS NOT NULL").single()["run_id"]!!
            val pid = running.process.pid()
            val stopped = Regex("""Stopped run ${runId.take(8)} \(pid $pid\) in \d+s, 2 attempts canceled, workspaces kept\.""")
            assertTrue(stopped.matches(stop.stdout.trim()), "$stop\n$run")
            listOf("t-1", "t-2").forEach { id -> assertTrue(fixture.workspace(id).exists(), "workspace of $id removed\n$run") }
            val runsDir = fixture.loopHome.resolve("runs")
            assertFalse(runsDir.resolve("${fixture.projectId()}.json").exists(), "run file left\n$run")
            assertFalse(runsDir.resolve("${fixture.projectId()}.stop").exists(), "stop marker left\n$run")
            val finished = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").map { it["data"] ?: "" }
            assertEquals(2, finished.count { "stop_requested" in it && "canceled" in it }, finished.toString())
            val runStopped = fixture.query("SELECT data FROM events WHERE kind = 'run_stopped'").single()["data"] ?: ""
            assertContains(runStopped, "stop_requested")
            assertContains(runStopped, "\"attempts_canceled\":\"2\"")
            assertContains(run.stderr, "attempts_canceled=2")

            listOf("t-1", "t-2").forEach { id ->
                val trace = fixture.loop("trace", id, cwd = fixture.projectDir)
                assertEquals(0, trace.exitCode, trace.toString())
                val header = trace.stdout.lines().single { it.startsWith("Attempt 1 ") }
                assertTrue(Regex("""canceled in \d+s, stop requested$""").containsMatchIn(header), header)
            }
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `17_7 loop run exits 0 when it starts and loop stop shuts it down normally`() {
        fixture.issue("t-1")
        val running = startRunning("t-1")
        try {
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val run = running.await(30)

            assertEquals(0, stop.exitCode, stop.toString())
            assertEquals(0, run.exitCode, run.toString())
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `m4-10 loop stop during a drain stops the run at once`() {
        fixture.issue("t-1")
        val running = startRunning("t-1")
        try {
            val drain = fixture.loop("drain", fixture.workflow.toString())
            assertEquals(0, drain.exitCode, drain.toString())
            // The run notices the drain at its next poll, then waits for the agent, which sleeps 30 seconds.
            val deadline = System.nanoTime() + WAIT_NANOS
            while (System.nanoTime() < deadline && "draining  run  since=" !in fixture.loop("status", fixture.workflow.toString()).stdout) {
                Thread.sleep(POLL_MS)
            }

            val started = System.nanoTime()
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val run = running.await(30)
            val tookSeconds = (System.nanoTime() - started) / 1_000_000_000

            assertEquals(0, stop.exitCode, stop.toString())
            assertEquals(0, run.exitCode, run.toString())
            assertTrue(tookSeconds < 20, "the stop waited for the drain, ${tookSeconds}s\n$run")
            assertFalse(ProcessHandle.of(agentPid("t-1")).map { it.isAlive }.orElse(false), "the agent outlived the stop\n$run")
            assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'run_draining'").size, run.toString())
            assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'run_drained'").size, run.toString())
            assertContains(fixture.query("SELECT data FROM events WHERE kind = 'run_stopped'").single()["data"].orEmpty(), "stop_requested")
            assertFalse(fixture.loopHome.resolve("runs/${fixture.projectId()}.drain").exists(), "drain marker left\n$run")
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `loop stop with nothing running says so`() {
        fixture.issue("t-1")

        val result = fixture.loop("stop", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("No run in progress for project ${fixture.projectId()}. Nothing to stop.", result.stdout.trim())
    }

    @Test
    fun `loop stop removes a stale run file`() {
        fixture.issue("t-1")
        // The pid of a process that already exited, as a crashed run leaves it.
        val gone = ProcessBuilder("true").start()
        gone.waitFor()
        val runFile =
            fixture.loopHome
                .resolve("runs")
                .createDirectories()
                .resolve("${fixture.projectId()}.json")
        runFile.writeText(
            """{"pid":${gone.pid()},"started_at":"2026-11-10T20:00:00Z","run_id":"${LoopFixture.RUN_ID}","workflow":"${fixture.workflow}"}""",
        )

        val result = fixture.loop("stop", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Removed a stale run file for pid ${gone.pid()}, that process is gone. Nothing to stop.", result.stdout.trim())
        assertFalse(runFile.exists(), "the stale run file is still there")
    }

    /** The pid the slow agent of [identifier] wrote when it started. */
    private fun agentPid(identifier: String): Long {
        val text = startedDir.resolve(identifier).readText()
        return text.trim().toLong()
    }

    private fun runFile(): Path =
        fixture.loopHome
            .resolve("runs")
            .createDirectories()
            .resolve("${fixture.projectId()}.json")

    @Test
    fun `loop stop removes a stale run file it cannot read`() {
        fixture.issue("t-1")
        val runFile = runFile()
        runFile.writeText("not json")

        val result = fixture.loop("stop", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Removed a stale run file that could not be read. Nothing to stop.", result.stdout.trim())
        assertFalse(runFile.exists(), "the stale run file is still there")
    }

    @Test
    fun `loop stop refuses a run file whose pid is not the run that holds it`() {
        fixture.issue("t-1")
        val runFile = runFile()
        // This test JVM holds the lock, the record names it with another start instant, as after a pid reuse.
        val self = ProcessHandle.current().pid()
        val record =
            """{"pid":$self,"started_at":"2000-01-01T00:00:00Z",""" +
                """"run_id":"${LoopFixture.RUN_ID}","workflow":"${fixture.workflow}"}"""
        FileChannel.open(runFile, CREATE, WRITE).use { channel ->
            channel.lock().use {
                // Through the locked channel: closing another descriptor of the file would drop the lock.
                channel.write(ByteBuffer.wrap(record.toByteArray()))

                val result = fixture.loop("stop", fixture.workflow.toString())

                assertEquals(3, result.exitCode, result.toString())
                assertEquals(
                    "loop stop: the run file at $runFile names pid $self, which is not the run that holds it. Nothing was stopped.",
                    result.stderr.trim(),
                )
            }
        }
        assertTrue(runFile.exists(), "the run file of a live holder was removed")
    }

    @Test
    fun `loop run fails at startup naming the run file when it cannot write it`() {
        fixture.issue("t-1")
        // A plain file where the runs folder should be.
        val loopHome = fixture.loopHome.createDirectories()
        loopHome.resolve("runs").writeText("")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(1, result.exitCode, result.toString())
        val runFile = fixture.loopHome.resolve("runs").resolve("${fixture.projectId()}.json")
        assertContains(result.stderr, "loop run: cannot write the run file at $runFile: ")
        assertFalse("internal error" in result.stderr, result.toString())
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
    }
}
