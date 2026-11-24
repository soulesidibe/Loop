package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m2-05, end to end with the command runner: each check's output is saved as evidence beside the
 * Attempt file, each verification ends with a `verdict`, and `loop trace` and `loop show` print them.
 */
class EvidenceCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Loop mode with the command checks [runs], in order, one turn per attempt and one continuation at most. */
    private fun loopMode(vararg runs: String) =
        fixture.writeWorkflow(
            extra =
                buildString {
                    appendLine("agent:")
                    appendLine("  max_turns: 1")
                    appendLine("retries:")
                    appendLine("  max_continuations: 1")
                    appendLine("kinds:")
                    appendLine("  code:")
                    appendLine("    delivery: { policy: auto, auto_merge: { enabled: true } }")
                    appendLine("    verify:")
                    appendLine("      checks:")
                    runs.forEach { appendLine("        - { type: command, run: \"$it\" }") }
                },
        )

    /** The attempt of the first verdict. */
    private fun verdictAttempt(): String =
        fixture.query("SELECT attempt_id FROM events WHERE kind = 'verdict' ORDER BY seq").first()["attempt_id"]!!

    private fun evidenceFile(
        attemptId: String,
        number: Int,
    ): Path = fixture.loopHome.resolve("attempts/${fixture.projectId()}/$attemptId.evidence-$number.log")

    @Test
    fun `a verified result saves redacted evidence beside the attempt file and loop trace shows the verdict`() {
        fixture.issue("t-1")
        // The token is built at run time, so only the output holds it, never the command.
        loopMode("echo checked; echo to stderr >&2; echo ghp_\$(echo abcdefghijklmnopqrstuvwxyz0123456789); test -f hello.txt")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("review", fixture.stateOf("t-1"), run.toString())
        val attemptId = verdictAttempt()
        val file = evidenceFile(attemptId, 1)
        assertTrue(file.exists(), "evidence beside ${fixture.loopHome.resolve("attempts/${fixture.projectId()}/$attemptId.jsonl.gz")}")
        val text = file.readText()
        assertContains(text, "checked\n")
        assertContains(text, "to stderr\n")
        assertContains(text, "[redacted:github-token]")
        assertFalse("ghp_abcdefghijklmnopqrstuvwxyz0123456789" in text, text)
        assertContains(text, "exit_code: 0\n")
        val fingerprint = Regex("""fingerprint: (\S+)""").find(text)?.groupValues?.get(1) ?: kotlin.test.fail(text)

        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, trace.exitCode, trace.toString())
        val verdict = trace.stdout.lines().singleOrNull { "verdict" in it } ?: kotlin.test.fail(trace.toString())
        assertContains(verdict, "verified")
        assertContains(verdict, "fingerprint=$fingerprint")
        assertContains(verdict, "checks=1")
        assertContains(verdict, "evidence=1")
        val lines = trace.stdout.lines()
        assertTrue(lines.indexOf(verdict) > lines.indexOfFirst { "check_finished" in it }, trace.stdout)
        assertTrue(lines.indexOf(verdict) < lines.indexOfFirst { "issue_moved" in it }, trace.stdout)
    }

    @Test
    fun `loop show --evidence prints the last check's evidence, --check picks one`() {
        fixture.issue("t-1")
        loopMode("echo first output", "echo second output; exit 4")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        assertEquals("todo", fixture.stateOf("t-1"), run.toString())
        val attemptId = verdictAttempt()

        val last = fixture.inProcess("show", attemptId.take(8), "--evidence", "--workflow", fixture.workflow.toString())

        assertEquals(0, last.exitCode, last.toString())
        // Check 1 failed and was rerun (m2-08): its rerun is the third file, the last one.
        assertEquals(evidenceFile(attemptId, 3).readText(), last.stdout)
        assertContains(last.stdout, "check: 1\n")
        assertContains(last.stdout, "exit_code: 4\n")
        assertContains(last.stdout, "second output")

        val first = fixture.inProcess("show", attemptId, "--evidence", "--check", "0", "--workflow", fixture.workflow.toString())

        assertEquals(0, first.exitCode, first.toString())
        assertContains(first.stdout, "check: 0\n")
        assertContains(first.stdout, "first output")
        assertFalse("second output" in first.stdout, first.stdout)

        val none = fixture.inProcess("show", attemptId, "--evidence", "--check", "7", "--workflow", fixture.workflow.toString())
        assertEquals(0, none.exitCode, none.toString())
        assertEquals("No evidence recorded for check 7 of this attempt.\n", none.stdout)

        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        val verdicts = trace.stdout.lines().filter { "verdict" in it }
        assertTrue(verdicts.isNotEmpty(), trace.toString())
        verdicts.forEach { line ->
            assertContains(line, "refuted")
            assertContains(line, "checks=2")
        }
    }

    @Test
    fun `an attempt with no evidence says so, and --check alone is a usage error`() {
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!

        val none = fixture.inProcess("show", attemptId, "--evidence", "--workflow", fixture.workflow.toString())

        assertEquals(0, none.exitCode, none.toString())
        assertEquals("No evidence recorded for this attempt.\n", none.stdout)

        val usage = fixture.inProcess("show", attemptId, "--prompt", "--check", "0", "--workflow", fixture.workflow.toString())
        assertEquals(2, usage.exitCode, usage.toString())
        assertContains(usage.stderr, "--check")
    }
}
