package loop.core.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import loop.core.process.SessionProcess
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real shell hook runner, with real `sh` processes in temp folders (plan R12). */
class HookRunnerTest {
    private val root: Path = createTempDirectory("loop-hooks").toRealPath()
    private val home: Path = root.resolve("home").createDirectories()
    private val workspace: Path = root.resolve("workspaces/m1-04").createDirectories()
    private val runner = ShellHookRunner()

    private val env =
        mapOf(
            "HOME" to home.toString(),
            "LOOP_ISSUE_ID" to "id-m1-04",
            "LOOP_ISSUE_IDENTIFIER" to "m1-04",
            "LOOP_WORKSPACE_KEY" to "m1-04",
        )

    private fun run(
        script: String,
        timeoutMs: Long = 10_000,
    ): HookResult = runBlocking { runner.run(HookName.AFTER_CREATE, script, workspace, env, timeoutMs) }

    /** The `KEY=value` lines a hook wrote with `env > file`. */
    private fun envFile(name: String): Map<String, String> =
        workspace
            .resolve(name)
            .readLines()
            .filter { "=" in it }
            .associate { it.substringBefore("=") to it.substringAfter("=") }

    @Test
    fun `hook receives the three loop variables`() {
        run("env > env.txt")

        val written = envFile("env.txt")
        assertEquals("id-m1-04", written["LOOP_ISSUE_ID"])
        assertEquals("m1-04", written["LOOP_ISSUE_IDENTIFIER"])
        assertEquals("m1-04", written["LOOP_WORKSPACE_KEY"])
    }

    @Test
    fun `hook inherits the host environment with the loop variables on top`() {
        // Set by spec-core/build.gradle.kts on the test task.
        val hostKey = "HOOK_TEST_HOST_VAR"
        val hostValue = "x"
        assertEquals(hostValue, System.getenv(hostKey), "the test task sets $hostKey")

        run("env > env.txt")

        val written = envFile("env.txt")
        assertEquals(hostValue, written[hostKey], "host variable $hostKey")
        assertEquals(home.toString(), written["HOME"])
        assertTrue(written["PATH"].orEmpty().isNotEmpty())
    }

    @Test
    fun `exit code and the tail of the merged output are returned`() {
        val result =
            run("echo first; i=0; while [ \$i -lt 2000 ]; do echo \"line \$i of the hook\"; i=\$((i+1)); done; echo last >&2; exit 3")

        assertEquals(3, result.exitCode)
        assertFalse(result.ok)
        assertFalse(result.timedOut)
        assertTrue(result.output.endsWith("last\n"), result.output.takeLast(100))
        assertFalse("first" in result.output)
        assertTrue(result.output.length <= ShellHookRunner.OUTPUT_MAX, "length ${result.output.length}")
    }

    @Test
    fun `a cut output is reported as truncated and starts at a whole line`() {
        val result = run("i=0; while [ \$i -lt 2000 ]; do echo \"line \$i of the hook\"; i=\$((i+1)); done")
        val short = run("echo only line")

        assertTrue(result.outputTruncated)
        assertTrue(result.output.startsWith("line "), result.output.take(40))
        assertFalse(short.outputTruncated)
    }

    @Test
    fun `timeout stops the hook and its children and keeps the output`() {
        val result = run("echo before the timeout; sleep 60 & echo \$! > child.pid; wait", timeoutMs = 1_000)

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertFalse(result.ok)
        assertTrue("before the timeout" in result.output, result.output)
        assertTrue(result.durationMs < 10_000, "took ${result.durationMs} ms")
        assertEquals("hook after_create timed out after 1000 ms", result.reason)
        val child =
            workspace
                .resolve("child.pid")
                .readText()
                .trim()
                .toLong()
        assertTrue(gone(child), "child $child still alive")
    }

    @Test
    fun `a background child holding the output pipe does not hang a successful hook`() {
        val result = run("sleep 30 & echo \$! > child.pid; echo done", timeoutMs = 20_000)

        assertEquals(0, result.exitCode)
        assertTrue(result.ok)
        assertTrue("done" in result.output, result.output)
        assertTrue(result.durationMs < 10_000, "took ${result.durationMs} ms")
        val child =
            workspace
                .resolve("child.pid")
                .readText()
                .trim()
                .toLong()
        assertTrue(gone(child), "background child $child still alive")
    }

    @Test
    fun `a cancel while the hook starts still stops the hook group`() {
        var session: SessionProcess? = null
        var thrown: Throwable? = null
        try {
            runBlocking {
                launch {
                    val self = coroutineContext.job
                    val cancelling =
                        ShellHookRunner(start = { command, cwd, env ->
                            SessionProcess.start(command, cwd, env).also {
                                session = it
                                self.cancel()
                            }
                        })
                    try {
                        cancelling.run(HookName.AFTER_CREATE, "sleep 30", workspace, env, 20_000)
                    } catch (e: CancellationException) {
                        thrown = e
                        throw e
                    }
                }
            }
            val started = assertNotNull(session, "the hook was started")
            assertTrue(thrown is CancellationException, "run threw $thrown")
            val pgid = started.process.pid()
            assertTrue(gone(pgid), "hook process $pgid still alive")
            assertTrue(groupGone(pgid), "process group $pgid still has members")
        } finally {
            session?.process?.pid()?.let { signalGroup(it, "KILL") }
        }
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

    /** Sends [signal] to process group [pgid]; true when a member received it. */
    private fun signalGroup(
        pgid: Long,
        signal: String,
    ): Boolean =
        ProcessBuilder("sh", "-c", "kill -s \"\$1\" -- \"-\$2\" 2>/dev/null", "sh", signal, pgid.toString())
            .start()
            .waitFor() == 0

    /** True once [pid] is not alive, polled up to 2 s (plan R12). */
    private fun gone(pid: Long): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false) return true
            Thread.sleep(50)
        }
        return false
    }

    private companion object {
        /** Variables a login shell sets or changes itself. */
        val SHELL_SET = setOf("PATH", "HOME", "PWD", "OLDPWD", "SHLVL", "_", "SHELL", "LOGNAME", "USER")
    }
}
