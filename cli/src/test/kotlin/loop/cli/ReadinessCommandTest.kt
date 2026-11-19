package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * m2-11, end to end with the files tracker and the command runner: in Loop mode a kind's
 * `ready: { require_sections: [Acceptance criteria] }` keeps a vague issue from being dispatched,
 * and Loop bounces it once with a comment and the `needs-info` label.
 */
class ReadinessCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun loopModeWithReadiness() =
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    ready: { require_sections: [Acceptance criteria] }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )

    /** The fixture's issue file without its acceptance criteria section. */
    private fun vagueIssue(id: String): Path {
        val file = fixture.issue(id)
        file.writeText(file.readText().replace("## Acceptance criteria\n\n- [ ] hello.txt exists.\n\n", ""))
        return file
    }

    @Test
    fun `an issue without acceptance criteria is not dispatched, commented and labeled needs-info once`() {
        val file = vagueIssue("t-1")
        loopModeWithReadiness()

        val first = fixture.loop("run", fixture.workflow.toString(), "--once")
        val second = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, first.exitCode, first.toString())
        assertEquals(0, second.exitCode, second.toString())
        assertEquals("todo", fixture.stateOf("t-1"))
        assertFalse(Files.exists(fixture.workspace("t-1")), "no workspace, so no attempt ran")
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size)
        val text = file.readText()
        assertContains(text, "\nlabels: [ready-for-agent, needs-info]\n")
        assertEquals(1, Regex("""Z loop: Loop did not dispatch this issue""").findAll(text).count(), text)
        assertContains(text, "`## Acceptance criteria`")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'issue_commented'").size)
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'issue_labeled'").size)

        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        assertContains(trace.stdout, "candidate_skipped")
        assertContains(trace.stdout, "reason=\"not_ready (missing Acceptance criteria)\"")
        assertContains(trace.stdout, "issue_labeled")
        assertContains(trace.stdout, "add=needs-info")
    }

    @Test
    fun `an issue with acceptance criteria dispatches as before`() {
        val file = fixture.issue("t-1")
        val before = file.readText()
        loopModeWithReadiness()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals(before.replace("state: todo", "state: review"), file.readText())
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind IN ('candidate_skipped', 'issue_labeled')").size)
    }
}
