package loop.cli

import com.github.ajalt.clikt.testing.test
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** m4-09, bl-156: `loop run` holds `caffeinate -i -w <pid>` while an attempt runs and stops it when the run ends. */
class KeepAwakeRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `loop run starts caffeinate with -i -w and its own pid for the attempt, then stops it`() {
        fixture.issue("t-1")
        val launcher = RecordingLauncher()

        val result = LoopCommand(host(launcher)).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        assertEquals(listOf(listOf("caffeinate", "-i", "-w", "${ProcessHandle.current().pid()}")), launcher.started, result.output)
        assertEquals(1, launcher.stops, result.output)
        val lines = result.stderr.lines().filter { "event=keep_awake" in it }
        assertEquals(2, lines.size, result.stderr)
        assertTrue(" level=info event=keep_awake outcome=started " in lines[0], lines[0])
        assertTrue(" level=info event=keep_awake outcome=released " in lines[1], lines[1])
    }

    @Test
    fun `a run with no eligible issue never starts caffeinate`() {
        val launcher = RecordingLauncher()

        val result = LoopCommand(host(launcher)).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(emptyList(), launcher.started)
        assertTrue("event=keep_awake" !in result.stderr, result.stderr)
    }

    @Test
    fun `without caffeinate loop run logs one line and works the issues as before`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        val missing = AwakeLauncher { Result.failure(IOException("Cannot run program \"caffeinate\": error=2, No such file or directory")) }

        val result = LoopCommand(host(missing)).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        assertEquals("review", fixture.stateOf("t-2"), result.output)
        val lines = result.stderr.lines().filter { "event=keep_awake" in it }
        assertEquals(1, lines.size, result.stderr)
        assertTrue(" level=warn event=keep_awake outcome=failed " in lines.single(), lines.single())
    }

    @Test
    fun `on another system loop run logs one line and works the issue as before`() {
        fixture.issue("t-1")

        val result = LoopCommand(host(null)).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        val lines = result.stderr.lines().filter { "event=keep_awake" in it }
        assertEquals(1, lines.size, result.stderr)
        assertTrue(" level=info event=keep_awake outcome=failed " in lines.single(), lines.single())
    }

    @Test
    fun `SIGTERM to loop run during an attempt stops its caffeinate before the JVM exits`() {
        // The real launcher runs only on macOS; a fake caffeinate first on the PATH records its arguments and its stop.
        assumeTrue(AwakeLauncher.forSystem() != null, "caffeinate is started on macOS only")
        val bin = root.resolve("fake-bin").createDirectories()
        val argsFile = root.resolve("caffeinate.args")
        val stoppedFile = root.resolve("caffeinate.stopped")
        val caffeinate = bin.resolve("caffeinate")
        caffeinate.writeText(
            "#!/bin/sh\n" +
                "trap 'echo stopped > \"$stoppedFile\"; exit 0' TERM\n" +
                "echo \"\$@\" > \"$argsFile\"\n" +
                "while :; do sleep 1; done\n",
        )
        Files.setPosixFilePermissions(caffeinate, PosixFilePermissions.fromString("rwxr-xr-x"))
        fixture.issue("t-1")
        fixture.agentScript.writeText("#!/bin/sh\nsleep 30\n")
        val running = fixture.start("run", fixture.workflow.toString(), env = mapOf("PATH" to "$bin:${System.getenv("PATH")}"))
        try {
            if (!awaitFile(argsFile)) {
                running.process.destroy()
                fail("caffeinate never started\n${running.await(30)}")
            }
            // The start script execs java, so the launcher process is the JVM that runs loop.
            assertEquals("-i -w ${running.process.pid()}", argsFile.readText().trim())
            assertTrue(!stoppedFile.exists(), "caffeinate stopped while the attempt runs")

            running.process.destroy()
            val result = running.await(30)

            assertEquals(0, result.exitCode, result.toString())
            assertTrue(stoppedFile.exists(), "caffeinate was not stopped when loop run ended\n$result")
            assertTrue(" event=keep_awake outcome=released " in result.stderr, result.stderr)
        } finally {
            running.process.destroyForcibly()
        }
    }

    private fun awaitFile(file: Path): Boolean {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!(file.exists() && file.readText().isNotBlank()) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return file.exists() && file.readText().isNotBlank()
    }

    private fun host(launcher: AwakeLauncher?): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
            awakeLauncher = launcher,
        )

    /** Records each command and each stop in place of `caffeinate`. */
    private class RecordingLauncher : AwakeLauncher {
        val started = mutableListOf<List<String>>()

        @Volatile
        var stops = 0

        override fun start(command: List<String>): Result<AwakeChild> {
            started += command
            return Result.success(AwakeChild { stops++ })
        }
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
    }
}
