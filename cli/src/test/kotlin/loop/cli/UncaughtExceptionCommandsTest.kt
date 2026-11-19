package loop.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import loop.core.workflow.WorkflowFile
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
 * m1-26: an exception thrown on the real path of each command is reported through that command's
 * redactor. The failure comes from an env map whose `get` throws for one name, which the workflow
 * loader reads for `workspace.root: $LOOP_TEST_BOOM`, so no production seam exists for the tests.
 */
class UncaughtExceptionCommandsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }
    private val secret = "Lp7secretQ93xZ0vK2mN5"

    /** An env map whose `get` throws a runtime exception carrying the secret for [boomName]. */
    private class ThrowingEnv(
        private val base: Map<String, String>,
        private val boomName: String,
        private val message: String,
    ) : Map<String, String> by base {
        override fun get(key: String): String? = if (key == boomName) throw IllegalStateException(message) else base[key]
    }

    /** The workflow of the fixture with a token ref and, when [boom], a `workspace.root` that throws when read. */
    private fun useWorkflow(boom: Boolean) {
        val text = fixture.workflow.readText().replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n")
        fixture.workflow.writeText(if (boom) text.replace("  root: ./workspaces\n", "  root: \$LOOP_TEST_BOOM\n") else text)
    }

    private fun host(
        extraEnv: Map<String, String> = emptyMap(),
        runnerPreflight: (WorkflowFile) -> loop.runner.claudecode.PreflightResult = ::runnerPreflight,
        boomName: String = "LOOP_TEST_BOOM",
        boomMessage: String = "boom $secret",
    ): Host {
        val base =
            mapOf(
                "LOOP_TEST_SECRET" to secret,
                "LOOP_HOME" to fixture.loopHome.toString(),
                "HOME" to fixture.homeDir.toString(),
            ) + extraEnv
        return Host(
            ThrowingEnv(base, boomName, boomMessage),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = "/tmp",
            runnerPreflight,
        )
    }

    private fun assertRedacted(result: CliktCommandTestResult) {
        assertFalse(secret in result.output, result.output)
        assertFalse(Regex("""(?m)^\s+at \S+\(""").containsMatchIn(result.output), result.output)
    }

    @Test
    fun `loop run reports an internal error as an error line of the operator log`() {
        useWorkflow(boom = false)
        fixture.issue("t-1")
        val command = LoopCommand(host(runnerPreflight = { throw IllegalStateException("boom $secret") }))

        val result = command.test("run", fixture.workflow.toString(), "--once")

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        val line = result.stderr.lines().single { "event=run outcome=failed" in it }
        assertContains(line, "level=error")
        assertContains(line, "reason=\"internal error: boom [redacted:LOOP_TEST_SECRET]\"")
        assertTrue("run_id=" in line && "project_id=" in line, line)
    }

    @Test
    fun `loop trace reports an internal error with the secrets of its workflow redacted`() {
        useWorkflow(boom = true)
        fixture.issue("t-1")

        val result = LoopCommand(host()).test("trace", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        assertEquals("loop trace: internal error: boom [redacted:LOOP_TEST_SECRET]\n", result.stderr)
    }

    @Test
    fun `loop show reports an internal error with the secrets of its workflow redacted`() {
        useWorkflow(boom = true)

        val result = LoopCommand(host()).test("show", "a-1", "--cost", "--workflow", fixture.workflow.toString())

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        assertEquals("loop show: internal error: boom [redacted:LOOP_TEST_SECRET]\n", result.stderr)
    }

    @Test
    fun `loop resume reports an internal error with the secrets of its workflow redacted`() {
        useWorkflow(boom = true)
        fixture.issue("t-1")

        val result = LoopCommand(host()).test("resume", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        assertEquals("loop resume: internal error: boom [redacted:LOOP_TEST_SECRET]\n", result.stderr)
    }

    @Test
    fun `loop tool move reports an internal error with token patterns redacted`() {
        // m1-23: loop tool reads no workflow, so it knows no workflow secret; the token patterns still apply.
        fixture.issue("t-1")
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val attempt = fixture.toolEnv("a-1", "t-1").filterValues { it != null }.mapValues { it.value!! }

        val result =
            LoopCommand(host(attempt, boomName = "LOOP_ISSUE_IDENTIFIER", boomMessage = "boom $token")).test("tool", "move", "review")

        assertEquals(1, result.statusCode, result.output)
        assertFalse(token in result.output, result.output)
        assertEquals("loop tool move: internal error: boom [redacted:github-token]\n", result.stderr)
    }

    /** `loop run` without `--once`; [fail] runs during the runner preflight, then the command must stop on its own. */
    private fun runUntilBackgroundFailure(
        extraEnv: Map<String, String> = emptyMap(),
        fail: () -> Unit,
    ): CliktCommandTestResult {
        useWorkflow(boom = false)
        val command = LoopCommand(host(extraEnv, runnerPreflight = { fail().let { loop.runner.claudecode.PreflightResult() } }))
        return assertTimeoutPreemptively<CliktCommandTestResult>(Duration.ofSeconds(WAIT_SECONDS)) {
            command.test(listOf("run", fixture.workflow.toString()))
        }
    }

    @Test
    fun `an uncaught exception on a background thread stops loop run with a redacted error line and exit 1`() {
        val result =
            runUntilBackgroundFailure {
                val before = Thread.getDefaultUncaughtExceptionHandler()
                thread(name = "loop-test-background") {
                    awaitRunStarted(before)
                    throw IllegalStateException("thread boom $secret")
                }
            }

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        val line = result.stderr.lines().singleOrNull { "level=error" in it } ?: fail("no error line\n${result.output}")
        assertContains(line, "event=run outcome=failed")
        assertContains(line, "reason=\"internal error: thread boom [redacted:LOOP_TEST_SECRET]\"")
        assertTrue("outcome=started" in result.stderr, result.stderr)
        assertFalse("outcome=completed" in result.stderr, result.stderr)
    }

    @Test
    fun `an uncaught exception in a detached coroutine stops loop run with a redacted error line and exit 1`() {
        val result =
            runUntilBackgroundFailure {
                val before = Thread.getDefaultUncaughtExceptionHandler()
                CoroutineScope(Dispatchers.IO).launch {
                    awaitRunStarted(before)
                    throw IllegalStateException("coroutine boom $secret")
                }
            }

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        val line = result.stderr.lines().singleOrNull { "level=error" in it } ?: fail("no error line\n${result.output}")
        assertContains(line, "reason=\"internal error: coroutine boom [redacted:LOOP_TEST_SECRET]\"")
    }

    @Test
    fun `an Error on a background thread reaches the previous handler and does not stop loop run`() {
        val seen = LinkedBlockingQueue<Throwable>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val fake = Thread.UncaughtExceptionHandler { _, error -> seen += error }
        Thread.setDefaultUncaughtExceptionHandler(fake)
        try {
            val result =
                runUntilBackgroundFailure {
                    thread(name = "loop-test-error") {
                        awaitRunStarted(fake)
                        val bg = thread(name = "loop-test-raw-error") { throw AssertionError("raw error $secret") }
                        bg.join()
                        // The Error must not stop the run; this exception does, so the test ends.
                        throw IllegalStateException("stop")
                    }
                }

            assertEquals(1, result.statusCode, result.output)
            val error = seen.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: fail("the Error never reached the previous handler")
            assertTrue(error is AssertionError, error.toString())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `an Error on a background thread is printed to stderr when there is no previous handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val realErr = System.err
        val captured = ByteArrayOutputStream()
        Thread.setDefaultUncaughtExceptionHandler(null)
        System.setErr(PrintStream(captured, true))
        try {
            val result =
                runUntilBackgroundFailure {
                    thread(name = "loop-test-error") {
                        // The run's handler is the only default one once it replaced null.
                        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
                        while (Thread.getDefaultUncaughtExceptionHandler() == null && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
                        thread(name = "loop-test-raw-error") { throw AssertionError("raw error") }.join()
                        throw IllegalStateException("stop")
                    }
                }

            assertEquals(1, result.statusCode, result.output)
            System.err.flush()
            val printed = captured.toString()
            assertContains(printed, "Exception in thread \"loop-test-raw-error\" ")
            assertContains(printed, "java.lang.AssertionError: raw error")
        } finally {
            System.setErr(realErr)
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `a later exception is logged redacted and the first failure stays`() {
        val result =
            runUntilBackgroundFailure {
                thread(name = "loop-test-two") {
                    val before = Thread.getDefaultUncaughtExceptionHandler()
                    awaitRunStarted(before)
                    val handler = Thread.getDefaultUncaughtExceptionHandler()
                    val me = Thread.currentThread()
                    handler.uncaughtException(me, IllegalStateException("first $secret"))
                    handler.uncaughtException(me, IllegalStateException("second $secret"))
                }
            }

        assertEquals(1, result.statusCode, result.output)
        assertRedacted(result)
        val lines = result.stderr.lines().filter { "level=error" in it }
        assertEquals(2, lines.size, result.stderr)
        assertContains(lines[0], "internal error: first [redacted:LOOP_TEST_SECRET]")
        assertContains(lines[1], "internal error: second [redacted:LOOP_TEST_SECRET]")
    }

    @Test
    fun `a run stopped by an internal error records internal_error as the stop reason, not signal`() {
        fixture.issue("t-1")
        val started = root.resolve("agent-started")
        fixture.agentScript.writeText("#!/bin/sh\ntouch '$started'\nsleep 30\n")

        // The agent process needs the host PATH, which the in-process host env does not have by default.
        val result =
            runUntilBackgroundFailure(extraEnv = mapOf("PATH" to System.getenv("PATH"))) {
                thread(name = "loop-test-crash") {
                    // Well inside the run's own timeout, so a missing agent shows as a failed assertion below.
                    val deadline = System.nanoTime() + Duration.ofSeconds(AGENT_WAIT_SECONDS).toNanos()
                    while (!started.exists() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
                    throw IllegalStateException("crash")
                }
            }

        assertTrue(started.exists(), "the agent never started\n${result.output}")
        assertEquals(1, result.statusCode, result.output)
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").single()["data"] ?: ""
        assertContains(finished, "\"reason\":\"internal_error\"")
        val stopped = fixture.query("SELECT data FROM events WHERE kind = 'run_stopped'").single()["data"] ?: ""
        assertContains(stopped, "\"reason\":\"internal_error\"")
    }

    /** Waits until `loop run` replaced the default handler [before], which it does just before it starts working. */
    private fun awaitRunStarted(before: Thread.UncaughtExceptionHandler?) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (Thread.getDefaultUncaughtExceptionHandler() === before && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
    }

    private companion object {
        const val WAIT_SECONDS = 30L
        const val POLL_MS = 20L
        const val AGENT_WAIT_SECONDS = 10L
    }
}
