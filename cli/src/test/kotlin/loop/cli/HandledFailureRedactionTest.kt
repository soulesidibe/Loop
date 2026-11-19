package loop.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * m1-26 (D6): a failure a command handles and prints is redacted too. The secret reaches the
 * message through a path: the tracker folder, or the loop home, is named after the secret, so the
 * error of the tracker or of the store quotes it.
 */
class HandledFailureRedactionTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }
    private val secret = "Lp7secretQ93xZ0vK2mN5"

    /** The workflow's tracker folder is `./$LOOP_TEST_SECRET`, which does not exist, and its token is the same secret. */
    private fun useSecretNamedTrackerDir() {
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(
            text.replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n    dir: \$LOOP_TEST_SECRET\n"),
        )
    }

    private fun host(
        loopHome: Path = fixture.loopHome,
        extraEnv: Map<String, String> = emptyMap(),
    ) = Host(
        mapOf("LOOP_TEST_SECRET" to secret, "LOOP_HOME" to loopHome.toString(), "HOME" to fixture.homeDir.toString()) + extraEnv,
        launcherProperty = System.getProperty("loop.launcher"),
        userHome = "/tmp",
    )

    private fun assertRedactedFailure(result: CliktCommandTestResult) {
        assertNotEquals(0, result.statusCode, result.output)
        assertFalse(secret in result.output, result.output)
        assertContains(result.stderr, "[redacted:LOOP_TEST_SECRET]")
    }

    @Test
    fun `loop resume prints a tracker failure and its closing failure redacted`() {
        useSecretNamedTrackerDir()

        val result = LoopCommand(host()).test("resume", "t-1", "--workflow", fixture.workflow.toString())

        assertRedactedFailure(result)
        val lines = result.stderr.lines().filter { it.startsWith("loop resume: ") }
        assertEquals(2, lines.size, result.stderr)
        assertContains(lines[0], "cannot read \"t-1\" in the tracker at")
        assertContains(lines[0], "[redacted:LOOP_TEST_SECRET]")
        assertFalse(lines[1].startsWith("loop resume: cannot read"), lines[1])
    }

    @Test
    fun `loop trace prints a tracker failure redacted`() {
        useSecretNamedTrackerDir()

        val result = LoopCommand(host()).test("trace", "t-1", "--workflow", fixture.workflow.toString())

        assertRedactedFailure(result)
    }

    @Test
    fun `loop run prints a store failure redacted`() {
        useTokenRef()
        val loopHome = root.resolve(secret)
        loopHome.writeText("a file where the loop home folder should be")

        val result = LoopCommand(host(loopHome)).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertRedactedFailure(result)
    }

    private fun useTokenRef() {
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(text.replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"))
    }
}
