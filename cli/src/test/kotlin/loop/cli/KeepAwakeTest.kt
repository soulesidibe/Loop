package loop.cli

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.CheckStarted
import loop.core.events.DeliveryFailed
import loop.core.events.MergeCheckFinished
import loop.core.events.MergeCheckStarted
import loop.core.events.TurnStarted
import loop.core.events.Verdict
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** m4-09, bl-156: `loop run` holds an idle sleep assertion while an attempt, a verification or a merge check runs. */
class KeepAwakeTest {
    @TempDir
    lateinit var root: Path

    private val lines = mutableListOf<String>()
    private val log = KeyValueLog({ lines += it }, Clock.System, Redactor.NONE)
    private val launcher = FakeLauncher()
    private val keepAwake = KeepAwake(launcher, log, LogContext(), pid = PID)

    @Test
    fun `the first attempt starts caffeinate with -i -w and the loop pid, the last one to end stops it`() {
        keepAwake.recorded(event(TurnStarted.name, issue = "1", attempt = "a1"))
        assertEquals(0, launcher.started.size, "nothing runs yet")

        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        assertEquals(listOf(listOf("caffeinate", "-i", "-w", "$PID")), launcher.started)

        keepAwake.recorded(event(AttemptStarted.name, issue = "2", attempt = "a2"))
        keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))
        assertEquals(1, launcher.started.size, "one assertion for every running attempt")
        assertEquals(0, launcher.stops, "an attempt still runs")

        keepAwake.recorded(event(AttemptFinished.name, issue = "2", attempt = "a2"))
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a new attempt after a release starts a new assertion`() {
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a2"))

        assertEquals(2, launcher.started.size)
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a verification outside an attempt holds the assertion from its first check to its verdict`() {
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a1"))
        assertEquals(1, launcher.started.size)
        keepAwake.recorded(event(CheckFinished.name, issue = "1", attempt = "a1", data = mapOf(CheckEvent.EXIT_CODE to "0")))
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(CheckFinished.name, issue = "1", attempt = "a1", data = mapOf(CheckEvent.EXIT_CODE to "0")))
        assertEquals(1, launcher.started.size, "no restart between two checks")
        assertEquals(0, launcher.stops, "the verification has no verdict yet")

        keepAwake.recorded(event(Verdict.name, issue = "1", attempt = "a1", data = mapOf(Verdict.VERDICT to "verified")))
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `the assertion stops when the last attempt or verification ends, whichever is last`() {
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(CheckStarted.name, issue = "2", attempt = "a0"))
        keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))
        assertEquals(0, launcher.stops, "the verification of issue 2 still runs")

        keepAwake.recorded(event(Verdict.name, issue = "2", attempt = "a0"))
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a canceled check ends its verification`() {
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(CheckFinished.name, issue = "1", attempt = "a1", data = mapOf(CheckEvent.CANCELED to "true")))

        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a merge check holds the assertion until it finishes, through the verdict of its rerun`() {
        keepAwake.recorded(event(MergeCheckStarted.name, issue = "1", attempt = "a1"))
        assertEquals(1, launcher.started.size)
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(Verdict.name, issue = "1", attempt = "a1", data = mapOf(Verdict.MERGE_CHECK to "true")))
        assertEquals(0, launcher.stops, "the merge check has not finished")

        keepAwake.recorded(event(MergeCheckFinished.name, issue = "1", attempt = "a1"))
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a merge check refused after its start ends with its delivery_failed`() {
        keepAwake.recorded(event(MergeCheckStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(DeliveryFailed.name, issue = "1", attempt = "a1"))

        assertEquals(1, launcher.stops)
    }

    @Test
    fun `an attempt of the issue ends a verification or merge check whose end was never recorded`() {
        keepAwake.recorded(event(MergeCheckStarted.name, issue = "1", attempt = "a0"))
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a0"))
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))

        assertEquals(1, launcher.started.size)
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a poll with no running attempt drops attempts whose end was never seen`() {
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        keepAwake.polled(emptyList(), running = 1, maxRunning = 2)
        assertEquals(0, launcher.stops, "the attempt still runs")

        keepAwake.polled(emptyList(), running = 0, maxRunning = 2)
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a poll with no running attempt keeps a running verification`() {
        keepAwake.recorded(event(CheckStarted.name, issue = "1", attempt = "a1"))
        keepAwake.polled(emptyList(), running = 0, maxRunning = 2)

        assertEquals(0, launcher.stops)
    }

    @Test
    fun `close stops a held assertion once, and nothing starts after it`() {
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))

        keepAwake.close()
        keepAwake.close()
        keepAwake.recorded(event(AttemptStarted.name, issue = "2", attempt = "a2"))

        assertEquals(1, launcher.started.size)
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a stop that throws is logged and never reaches the run`() {
        val throwing = KeepAwake({ Result.success(AwakeChild { throw IllegalStateException("no such process") }) }, log, LogContext(), PID)
        throwing.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))

        throwing.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))

        assertTrue(" level=warn event=keep_awake outcome=failed " in lines.last(), lines.joinToString("\n"))
        assertTrue("no such process" in lines.last(), lines.last())
    }

    @Test
    fun `close with no assertion held stops nothing`() {
        keepAwake.close()

        assertEquals(0, launcher.stops)
    }

    @Test
    fun `the start and the release are logged at info`() {
        keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a1"))
        keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a1"))

        assertEquals(2, lines.size, lines.joinToString("\n"))
        assertTrue(" level=info event=keep_awake outcome=started " in lines[0], lines[0])
        assertTrue(" level=info event=keep_awake outcome=released" in lines[1], lines[1])
    }

    @Test
    fun `a caffeinate that cannot start is logged in one line, never retried, and the run goes on`() {
        launcher.failure = IOException("Cannot run program \"caffeinate\": error=2, No such file or directory")

        repeat(2) { n ->
            keepAwake.recorded(event(AttemptStarted.name, issue = "1", attempt = "a$n"))
            keepAwake.recorded(event(AttemptFinished.name, issue = "1", attempt = "a$n"))
        }
        keepAwake.close()

        assertEquals(1, launcher.attempts, "tried once")
        assertEquals(1, lines.size, lines.joinToString("\n"))
        assertTrue(" level=warn event=keep_awake outcome=failed " in lines.single(), lines.single())
        assertTrue("caffeinate" in lines.single(), lines.single())
    }

    @Test
    fun `on a system other than macOS one line says the Mac is not kept awake, once`() {
        val other = KeepAwake(null, log, LogContext(), pid = PID)

        repeat(2) { n ->
            other.recorded(event(AttemptStarted.name, issue = "1", attempt = "a$n"))
            other.recorded(event(AttemptFinished.name, issue = "1", attempt = "a$n"))
        }
        other.close()

        assertEquals(1, lines.size, lines.joinToString("\n"))
        assertTrue(" level=info event=keep_awake outcome=failed " in lines.single(), lines.single())
        assertTrue("macOS" in lines.single(), lines.single())
    }

    @Test
    fun `a run with no work logs nothing`() {
        val other = KeepAwake(null, log, LogContext(), pid = PID)

        other.polled(listOf("1"), running = 0, maxRunning = 1)
        other.close()

        assertEquals(emptyList(), lines)
    }

    @Test
    fun `the system launcher is the process launcher on macOS and none elsewhere`() {
        assertSame(ProcessAwakeLauncher, AwakeLauncher.forSystem("Mac OS X"))
        assertNull(AwakeLauncher.forSystem("Linux"))
        assertNull(AwakeLauncher.forSystem("Windows 11"))
    }

    @Test
    fun `the process launcher fails with the IO error when the program is missing`() {
        val result = ProcessAwakeLauncher.start(listOf("loop-no-such-caffeinate-m4-09", "-i"))

        assertIs<IOException>(result.exceptionOrNull())
    }

    @Test
    fun `the process launcher starts the command and stop ends it`() {
        val pidFile = root.resolve("child.pid")
        val child = ProcessAwakeLauncher.start(listOf("sh", "-c", "echo \$\$ > '$pidFile'; exec sleep 30")).getOrThrow()
        val pid = awaitPid(pidFile)

        child.stop()

        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "the child outlived stop")
    }

    private fun awaitPid(file: Path): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (file.exists()) {
                file
                    .readText()
                    .trim()
                    .toLongOrNull()
                    ?.let { return it }
            }
            Thread.sleep(20)
        }
        error("the child never wrote its pid")
    }

    private fun event(
        kind: String,
        issue: String,
        attempt: String,
        data: Map<String, String> = emptyMap(),
    ) = RecordedEvent(
        ts = Instant.fromEpochSeconds(0),
        projectId = ProjectId("p1"),
        kind = kind,
        issueId = issue,
        issueIdentifier = "t-$issue",
        attemptId = AttemptId(attempt),
        data = data,
    )

    /** A launcher that records each command and each stop in place of `caffeinate`. */
    private class FakeLauncher : AwakeLauncher {
        val started = mutableListOf<List<String>>()
        var stops = 0
        var attempts = 0
        var failure: Throwable? = null

        override fun start(command: List<String>): Result<AwakeChild> {
            attempts++
            failure?.let { return Result.failure(it) }
            started += command
            return Result.success(AwakeChild { stops++ })
        }
    }

    private companion object {
        const val PID = 4242L
    }
}
