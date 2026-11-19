package loop.core.verify

import kotlinx.coroutines.runBlocking
import loop.core.workflow.CheckConfig
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m2-03: a `command` check runs host side in the workspace, real processes and real time (never `runTest`). */
class CommandCheckTest {
    private val workspace: Path = createTempDirectory("loop-check").toRealPath()
    private val path = System.getenv("PATH") ?: "/usr/bin:/bin"
    private val runner = CoreCheckRunner()

    private fun run(
        script: String,
        env: Map<String, String> = mapOf("PATH" to path),
        timeoutMs: Long = 30_000,
    ): CheckResult = runBlocking { runner.run(CheckConfig.Command(script, timeoutMs), workspace, env) }

    @Test
    fun `exit 0 passes and runs in the workspace`() {
        val result = run("pwd > where.txt")

        assertEquals(0, result.exitCode)
        assertTrue(result.passed)
        assertFalse(result.timedOut)
        assertEquals(workspace.toString(), workspace.resolve("where.txt").readText().trim())
    }

    @Test
    fun `a non zero exit fails with its code and duration`() {
        val result = run("sleep 0.2; exit 3")

        assertEquals(3, result.exitCode)
        assertFalse(result.passed)
        assertTrue(result.durationMs >= 200, result.durationMs.toString())
    }

    @Test
    fun `the env is the one given, nothing inherited from the host`() {
        val result = run("env > env.txt", env = mapOf("PATH" to path, "ONLY_THIS" to "x"))

        assertEquals(0, result.exitCode)
        val names =
            workspace
                .resolve("env.txt")
                .readLines()
                .map { it.substringBefore('=') }
                .toSet()
        assertTrue("ONLY_THIS" in names, names.toString())
        assertFalse("HOOK_TEST_HOST_VAR" in names, names.toString())
    }

    @Test
    fun `a check over its timeout is stopped and fails`() {
        val result = run("sleep 30", timeoutMs = 300)

        assertNull(result.exitCode)
        assertTrue(result.timedOut)
        assertFalse(result.passed)
        assertTrue(result.durationMs < 10_000, result.durationMs.toString())
    }

    @Test
    fun `the result holds the full stdout and stderr, not only the tail kept for hooks`() {
        // 20000 numbered lines on stdout, well past the 8 KiB tail, then one line on stderr.
        val result = run("i=1; while [ \$i -le 20000 ]; do echo \"out \$i\"; i=\$((i+1)); done; echo 'err last' >&2; exit 1")

        assertEquals(1, result.exitCode)
        assertFalse(result.outputTruncated)
        val lines = result.output.lines().filter { it.isNotEmpty() }
        assertEquals("out 1", lines.first())
        assertEquals("out 20000", lines[19_999])
        assertEquals("err last", lines.last())
    }

    private fun Path.readLines(): List<String> = readText().lines().filter { it.isNotBlank() }
}
