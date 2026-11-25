package loop.cli

import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.orchestrator.attemptFile
import loop.store.GzipAttemptRecords
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** m4-40: `loop replay <attempt> --prompt` renders the turn 1 prompt again with the current template and diffs it. */
class ReplayCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `an unchanged template prints the same prompt, exit 0, and nothing is written`() {
        fixture.issue("t-1", title = "Say hello")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = onlyAttempt()
        val store = Files.readAllBytes(fixture.storeFile)
        val issue = fixture.issueFile("t-1").readText()

        val result = fixture.inProcess("replay", attemptId, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Replay of the turn 1 prompt of attempt $attemptId, issue t-1.\n")
        assertContains(result.stdout, "No difference: the same prompt.\n")
        assertFalse("--- recorded prompt" in result.stdout, result.stdout)
        val versions = Regex("template ([0-9a-f]{12})").findAll(result.stdout).map { it.groupValues[1] }.toList()
        assertEquals(2, versions.size, result.stdout)
        assertEquals(versions[0], versions[1], "an unchanged template keeps its version")
        assertEquals("", result.stderr)
        assertTrue(store.contentEquals(Files.readAllBytes(fixture.storeFile)), "the store is not written")
        assertEquals(issue, fixture.issueFile("t-1").readText(), "the tracker is not written")
    }

    @Test
    fun `a changed template prints a unified diff, the recorded prompt first, with both template versions`() {
        fixture.issue("t-1", title = "Say hello")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = onlyAttempt()
        val recordedVersion = fixture.query("SELECT data FROM events WHERE kind = 'attempt_started'").single()["data"]!!
        fixture.writeWorkflow(prompt = "Work carefully on {{ issue.identifier }}: {{ issue.title }}\n")

        val result = fixture.inProcess("replay", attemptId, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        val diff = result.stdout.substringAfter("--- recorded prompt\n")
        assertEquals(
            "+++ rendered prompt\n@@ -1,1 +1,1 @@\n-Work on t-1: Say hello\n+Work carefully on t-1: Say hello\n",
            diff,
            result.stdout,
        )
        val versions = Regex("template ([0-9a-f]{12})").findAll(result.stdout).map { it.groupValues[1] }.toList()
        assertEquals(2, versions.size, result.stdout)
        assertContains(recordedVersion, versions[0], message = "the recorded version is the one attempt_started holds")
        assertTrue(versions[0] != versions[1], result.stdout)
        assertContains(result.stdout, "as the tracker returns it now")
    }

    @Test
    fun `a sent back retry renders with its recorded retry kind and findings`() {
        fixture.issue("t-1")
        fixture.writeWorkflow(prompt = RETRY_TEMPLATE)
        fixture.seed(
            fixture.attemptStarted(A1, "t-1"),
            fixture.attemptFinished(A1, "t-1"),
            issueEvent("sent_back", mapOf("by" to "alice", "reason" to "Use the word Save.")),
            fixture.attemptStarted(A2, "t-1"),
            hook(A2, "after_create"),
        )
        recordPrompt(A2, "Work on t-1, retry sent_back.\nSent back by alice: Use the word Save.\n")

        val result = fixture.inProcess("replay", A2, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "loop.retry_kind sent_back")
        assertContains(result.stdout, "No difference: the same prompt.\n")
        assertFalse("Not recovered" in result.stdout, result.stdout)
    }

    @Test
    fun `a refuted retry renders with its retry kind and attempt, and names the findings it cannot recover`() {
        fixture.issue("t-1")
        fixture.writeWorkflow(prompt = RETRY_TEMPLATE)
        fixture.seed(
            fixture.attemptStarted(A1, "t-1"),
            fixture.attemptFinished(A1, "t-1"),
            issueEvent("retry_scheduled", mapOf("kind" to "error", "attempt" to "1", "error" to "refuted")),
            fixture.attemptStarted(A2, "t-1"),
        )
        recordPrompt(A2, "Work on t-1, retry refuted.\nCheck: ./gradlew test\nExit code: 1\n")

        val result = fixture.inProcess("replay", A2, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Recovered: attempt 1, loop.retry_kind refuted.\n")
        assertContains(
            result.stdout,
            "Not recovered, rendered empty or false: loop.verify_findings, loop.workspace_reused, loop.session_brief.\n",
        )
        assertContains(result.stdout, "@@ -1,4 +1,2 @@\n Work on t-1, retry refuted.\n-Check: ./gradlew test\n-Exit code: 1\n")
    }

    @Test
    fun `the new render is redacted before it is printed`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        fixture.writeWorkflow(prompt = "{{ issue.description }}\n")
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(text.replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"))
        fixture.issue("t-1", criteria = "- [ ] The token $secret works.")
        fixture.seed(fixture.attemptStarted(A1, "t-1"))
        recordPrompt(A1, "An older prompt.\n")

        val result = fixture.inProcess("replay", A1, "--prompt", env = mapOf("LOOP_TEST_SECRET" to secret))

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "+- [ ] The token [redacted:LOOP_TEST_SECRET] works.\n")
        assertFalse(secret in result.stdout + result.stderr, result.toString())
    }

    @Test
    fun `an unknown attempt exits 2`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(A1, "t-1"))

        val result = fixture.inProcess("replay", "nope", "--prompt")

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "loop replay: no attempt \"nope\" in project ${fixture.projectId()}.")
        assertEquals("", result.stdout)
    }

    @Test
    fun `an attempt with no recorded prompt says so, exit 4`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(A1, "t-1"))

        val result = fixture.inProcess("replay", A1, "--prompt")

        assertEquals(4, result.exitCode, result.toString())
        assertContains(result.stderr, "loop replay: no prompt recorded for attempt $A1, nothing to replay.")
        assertEquals("", result.stdout)
    }

    @Test
    fun `no mode names the modes, exit 2`() {
        val result = fixture.inProcess("replay", A1)

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "pick a mode: --prompt")
    }

    private fun onlyAttempt(): String =
        fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!

    private fun recordPrompt(
        attemptId: String,
        text: String,
    ) {
        GzipAttemptRecords(Redactor.NONE) { project, attempt -> attemptFile(fixture.loopHome, project, attempt) }
            .append(ProjectId(fixture.projectId()), AttemptId(attemptId), AttemptRecord.Prompt(Clock.System.now(), turn = 1, text = text))
            .getOrThrow()
    }

    private fun issueEvent(
        kind: String,
        data: Map<String, String>,
    ): RecordedEvent = fixture.attemptStarted(A1, "t-1").copy(kind = kind, attemptId = null, data = data)

    private fun hook(
        attemptId: String,
        name: String,
    ): RecordedEvent =
        fixture.attemptStarted(attemptId, "t-1").copy(kind = "hook_finished", data = mapOf("hook" to name, "exit_code" to "0"))

    private companion object {
        const val A1 = "3d9a0c1e-0000-4000-8000-000000000001"
        const val A2 = "3d9a0c1e-0000-4000-8000-000000000002"

        const val RETRY_TEMPLATE =
            "Work on {{ issue.identifier }}{% if loop.retry_kind %}, retry {{ loop.retry_kind }}{% endif %}.\n" +
                "{% if loop.verify_findings %}{{ loop.verify_findings }}\n{% endif %}"
    }
}
