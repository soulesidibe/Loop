package loop.runner.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.RunnerEvent.Output.Stream
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class ProcessGroupLauncherTest {
    @TempDir
    lateinit var workspace: Path

    private fun launchAll(spec: LaunchSpec): List<ProcessEvent> =
        runBlocking { withTimeout(10.seconds) { ProcessGroupLauncher().launch(spec).toList() } }

    private fun List<ProcessEvent>.stdout(): List<String> =
        filterIsInstance<ProcessEvent.Line>().filter { it.stream == Stream.Stdout }.map { it.text }

    @Test
    fun `the process sees exactly the given environment, nothing from the host`() {
        val spec =
            LaunchSpec(
                argv = listOf("sh", "-c", "env | sort"),
                cwd = workspace,
                environment = mapOf("PATH" to System.getenv("PATH"), "ONLY_VAR" to "only-value"),
            )

        val names = launchAll(spec).stdout().map { it.substringBefore('=') }.toSet()

        // sh may add PWD, SHLVL and _ itself.
        assertEquals(setOf("ONLY_VAR", "PATH"), names - setOf("PWD", "SHLVL", "_"))
    }

    @Test
    fun `m3-07 stdin stays open while its channel is open, each text reaches the running process, closing it ends stdin`() {
        val stdin = Channel<String>(Channel.UNLIMITED)
        val spec =
            LaunchSpec(
                // Echoes each line it reads, then says it saw the end of stdin.
                argv = listOf("sh", "-c", "while IFS= read -r line; do echo \"got \$line\"; done; echo eof"),
                cwd = workspace,
                environment = mapOf("PATH" to System.getenv("PATH")),
                stdin = "first\n",
                stdinLines = stdin,
            )
        val seen = mutableListOf<String>()

        val events =
            runBlocking {
                withTimeout(10.seconds) {
                    ProcessGroupLauncher()
                        .launch(spec)
                        .onEach { event ->
                            if (event is ProcessEvent.Line) seen += event.text
                            // Each answer comes while stdin is still open, then the next text is sent.
                            when ((event as? ProcessEvent.Line)?.text) {
                                "got first" -> stdin.send("second\n")
                                "got second" -> stdin.close()
                            }
                        }.toList()
                }
            }

        assertEquals(listOf("got first", "got second", "eof"), seen)
        assertEquals(ProcessEvent.Exited(0), events.last())
    }

    @Test
    fun `a cancel right after the process starts leaves no process or group member behind`() {
        val childPidFile = workspace.resolve("child.pid")
        // A background child in the group writes its pid, then the root becomes a long sleep.
        val script = "sleep 30 & echo \$! > \"\$1/child.pid\"; exec sleep 30"
        val spec =
            LaunchSpec(
                argv = listOf("sh", "-c", script, "sh", workspace.toString()),
                cwd = workspace,
                environment = mapOf("PATH" to System.getenv("PATH")),
            )
        var started: Process? = null
        var thrown: Throwable? = null
        try {
            // Fail fast on a hang instead of waiting for the Gradle timeout.
            runBlocking {
                withTimeout(15.seconds) {
                    launch {
                        val self = coroutineContext.job
                        val cancelling =
                            ProcessGroupLauncher(start = { builder ->
                                builder.start().also {
                                    started = it
                                    // If awaitPid fails, its AssertionError escapes the seam before the launcher's try, so the test's finally cleans up.
                                    awaitPid(childPidFile)
                                    self.cancel()
                                }
                            })
                        try {
                            cancelling.launch(spec).toList()
                        } catch (e: CancellationException) {
                            thrown = e
                            throw e
                        }
                    }
                }
            }
            val root = assertNotNull(started, "the process was started").pid()
            assertTrue(thrown is CancellationException, "the collection threw $thrown")
            assertTrue(thrown !is TimeoutCancellationException, "the test timed out instead of the self cancel: $thrown")
            val child = assertNotNull(readPid(childPidFile), "the child wrote its pid")
            assertTrue(gone(root), "root process $root still alive")
            assertTrue(gone(child), "child process $child still alive")
            assertTrue(groupGone(root), "process group $root still has members")
        } finally {
            started?.pid()?.let { killGroup(it) }
            readPid(childPidFile)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    /** The pid written in [file], or null while the file is missing or empty. */
    private fun readPid(file: Path): Long? = if (file.exists()) file.readText().trim().toLongOrNull() else null

    /** Waits up to 5 s for a pid in [file], so the group has its second member. */
    private fun awaitPid(file: Path) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (readPid(file) != null) return
            Thread.sleep(20)
        }
        fail("no pid written to $file within 5 s")
    }

    /** True once process group [pgid] has no member, polled up to 2 s. */
    private fun groupGone(pgid: Long): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!signalGroup(pgid, "0")) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Sends [signal] to process group [pgid]; true when a member received it, false when none is left. Any other outcome fails the test. */
    private fun signalGroup(
        pgid: Long,
        signal: String,
    ): Boolean {
        val (exitCode, stderr) = runKill(pgid, signal)
        return when {
            exitCode == 0 -> true
            "No such process" in stderr -> false
            else -> fail("kill -s $signal -- -$pgid exited $exitCode: $stderr")
        }
    }

    /** The KILL safety net: sends KILL to process group [pgid] and ignores the outcome. */
    private fun killGroup(pgid: Long) {
        runKill(pgid, "KILL")
    }

    /** Runs the shell kill on process group [pgid] with the C locale, returns the exit code and stderr. */
    private fun runKill(
        pgid: Long,
        signal: String,
    ): Pair<Int, String> {
        val builder =
            ProcessBuilder("sh", "-c", "kill -s \"\$1\" -- \"-\$2\"", "sh", signal, pgid.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment()["LC_ALL"] = "C"
        val kill = builder.start()
        kill.outputStream.close()
        val stderr =
            kill.errorStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .trim()
        return kill.waitFor() to stderr
    }

    /** True once [pid] is not alive, polled up to 2 s. */
    private fun gone(pid: Long): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false) return true
            Thread.sleep(50)
        }
        return false
    }
}
