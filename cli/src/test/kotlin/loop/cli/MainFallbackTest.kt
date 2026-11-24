package loop.cli

import com.github.ajalt.clikt.core.CliktCommand
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** m1-26: what escapes every command, or happens before a command exists, still leaves redacted. */
class MainFallbackTest {
    @TempDir
    lateinit var root: Path

    private val secret = "Lp7secretQ93xZ0vK2mN5"

    private class Outcome(
        val exitCodes: MutableList<Int> = mutableListOf(),
        val stderr: MutableList<String> = mutableListOf(),
    )

    /** `LOOP_WORKFLOW` names a workflow whose `tracker.provider.token` is `$LOOP_TEST_SECRET`. */
    private fun env(): Map<String, String> {
        val workflow = root.resolve("WORKFLOW.md")
        workflow.writeText("---\ntracker:\n  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n---\nbody\n")
        return mapOf("LOOP_TEST_SECRET" to secret, "LOOP_WORKFLOW" to workflow.toString())
    }

    private fun run(
        vararg args: String,
        build: () -> CliktCommand,
    ): Outcome {
        val outcome = Outcome()
        runMain(args.toList().toTypedArray(), env(), { outcome.exitCodes += it }, { outcome.stderr += it }, build = build)
        return outcome
    }

    @Test
    fun `a failure while the command is built is printed redacted with exit 1`() {
        val outcome = run("run", "--once") { throw IllegalStateException("cannot build $secret") }

        assertEquals(listOf(1), outcome.exitCodes)
        assertEquals(listOf("loop run: internal error: cannot build [redacted:LOOP_TEST_SECRET]"), outcome.stderr)
        assertFalse(outcome.stderr.any { secret in it })
    }

    @Test
    fun `a failure that escapes a command is printed redacted and names loop when there are no arguments`() {
        val command =
            object : CliktCommand(name = "loop") {
                override fun run() = throw IllegalStateException("escaped $secret")
            }

        val outcome = run { command }

        assertEquals(listOf(1), outcome.exitCodes)
        assertEquals(listOf("loop: internal error: escaped [redacted:LOOP_TEST_SECRET]"), outcome.stderr)
    }

    @Test
    fun `the command path stops after the first subcommand name, except tool move`() {
        assertEquals("loop trace", commandPathOf(arrayOf("trace", "run")))
        assertEquals("loop", commandPathOf(arrayOf("move")))
        assertEquals("loop tool move", commandPathOf(arrayOf("tool", "move", "review")))
        assertEquals("loop tool", commandPathOf(arrayOf("tool", "run")))
        assertEquals("loop tool comment", commandPathOf(arrayOf("tool", "comment", "hi")))
        assertEquals("loop", commandPathOf(arrayOf("comment")))
        assertEquals("loop tool", commandPathOf(arrayOf("tool", "trace")))
    }

    @Test
    fun `inside an attempt the fallback reads no workflow and still applies the token patterns`() {
        // m1-23: with LOOP_TOOL_CONFIG set the process is a loop tool inside an agent, where no credential
        // is reachable. The fallback must not read LOOP_WORKFLOW, so the workflow's token name is not learned;
        // here its value is in the env only to prove that.
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val outcome = Outcome()
        val env = env() + ("LOOP_TOOL_CONFIG" to root.resolve("tool-config.json").toString())

        runMain(arrayOf("tool", "move", "review"), env, { outcome.exitCodes += it }, { outcome.stderr += it }) {
            throw IllegalStateException("boom $secret $token")
        }

        assertEquals(listOf(1), outcome.exitCodes)
        assertEquals(listOf("loop tool move: internal error: boom $secret [redacted:github-token]"), outcome.stderr)
    }

    @Test
    fun `a malformed LOOP_WORKFLOW does not hide the original error`() {
        val outcome = Outcome()
        val env = mapOf("LOOP_TEST_SECRET" to secret, "LOOP_WORKFLOW" to "bad\u0000path")

        runMain(arrayOf("run"), env, { outcome.exitCodes += it }, { outcome.stderr += it }) { throw IllegalStateException("boom") }

        assertEquals(listOf(1), outcome.exitCodes)
        assertEquals(listOf("loop run: internal error: boom"), outcome.stderr)
    }

    @Test
    fun `a relative LOOP_WORKFLOW resolves against workingDir when redacting fallback errors`() {
        val sub = root.resolve("sub").createDirectories()
        sub.resolve("custom.md").writeText("---\ntracker:\n  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n---\nbody\n")
        val outcome = Outcome()
        val env = mapOf("LOOP_TEST_SECRET" to secret, "LOOP_WORKFLOW" to "custom.md")

        runMain(arrayOf("trace", "t-1"), env, { outcome.exitCodes += it }, { outcome.stderr += it }, workingDir = sub) {
            throw IllegalStateException("boom $secret")
        }

        assertEquals(listOf(1), outcome.exitCodes)
        assertEquals(listOf("loop trace: internal error: boom [redacted:LOOP_TEST_SECRET]"), outcome.stderr)
    }
}
