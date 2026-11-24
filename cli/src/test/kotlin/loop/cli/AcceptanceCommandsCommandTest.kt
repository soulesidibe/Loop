package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.FrozenCommands
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.verify.AcceptanceCriteria
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-09, end to end with the command runner and a real shell: a kind's `acceptance_commands` check runs the
 * commands of the issue's acceptance criteria in the workspace, with its timeout, and saves the judgment
 * calls as evidence; `loop trace` and the human log show both.
 */
class AcceptanceCommandsCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun loopMode(check: String) =
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - $check
                """.trimMargin(),
        )

    /** The one trace line of the event [kind], after its time column. */
    private fun List<String>.line(
        kind: String,
        result: Any,
    ): String = singleOrNull { "  $kind " in it } ?: kotlin.test.fail("no single $kind line in $result")

    @Test
    fun `the commands of the criteria run in the workspace and the judgment calls are saved as evidence`() {
        // The agent wrote hello.txt in its workspace: the command passes only when it runs there.
        fixture.issue(
            "t-1",
            criteria =
                """
                |- [ ] `test -f hello.txt && echo ${'$'}LOOP_ISSUE_IDENTIFIER > checked.txt` passes.
                |- [ ] A human reads `hello.txt`.
                """.trimMargin(),
        )
        loopMode("{ type: acceptance_commands }")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("review", fixture.stateOf("t-1"), run.toString())
        assertEquals(
            "t-1",
            fixture
                .workspace("t-1")
                .resolve("checked.txt")
                .readText()
                .trim(),
        )
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        val lines = trace.stdout.lines()
        assertContains(lines.line("check_finished", trace), "command=\"test -f hello.txt && echo \$LOOP_ISSUE_IDENTIFIER > checked.txt\"")
        val judgment = lines.line("judgment_calls", trace)
        assertContains(judgment, "count=1")
        val file = Regex("""evidence=(\S+)""").find(judgment)?.groupValues?.get(1) ?: kotlin.test.fail(judgment)
        val text = fixture.loopHome.resolve("attempts/${fixture.projectId()}/$file").readText()
        assertContains(text, "judgment_calls: 1\n")
        assertContains(text, "\n- A human reads `hello.txt`.\n")
        val verdict = lines.line("verdict", trace)
        assertContains(verdict, "verified")
        assertContains(verdict, "checks=1")
        assertContains(verdict, "judgment_calls=1")
        assertContains(fixture.humanLog.readText(), "verdict verified, evidence of 1 check saved, 1 judgment call left to a human")
    }

    @Test
    fun `a command over the declared timeout gives unverified and parks the issue, like a command check`() {
        fixture.issue("t-1", criteria = "- [ ] `sleep 30` passes.")
        loopMode("{ type: acceptance_commands, timeout_ms: 300 }")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("todo", fixture.stateOf("t-1"), run.toString())
        assertEquals(
            listOf("unverified"),
            fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").map { it["reason"] },
        )
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        // The measured duration varies with the machine: only the shape of the reason is asserted.
        assertContains(trace.stdout.lines().line("verdict", trace), "check 0 could not run: timed out after ")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size, "not rerun")
    }

    @Test
    fun `criteria changed since the first dispatch park the issue until loop resume accepts the new list`() {
        // The first dispatch froze two commands; the issue now holds one, as an agent could have rewritten it.
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt` passes.")
        loopMode("{ type: acceptance_commands }")
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            val first = AcceptanceCriteria.digest(listOf("test -f hello.txt", "./gradlew --no-daemon build"))
            val frozen = FrozenCommands(ProjectId(fixture.projectId()), "t-1", "t-1", first, 2, Instant.parse("2026-11-12T09:00:00Z"))
            runBlocking { store.freeze(frozen).getOrThrow() }
        }

        val parked = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, parked.exitCode, parked.toString())
        assertEquals("todo", fixture.stateOf("t-1"), parked.toString())
        assertEquals(listOf("unverified"), fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").map { it["reason"] })
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size, "no command ran")
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        val lines = trace.stdout.lines()
        assertContains(lines.line("acceptance_commands_changed", trace), "frozen=2 count=1")
        assertContains(lines.line("verdict", trace), "reason=\"acceptance commands changed\"")
        assertContains(
            fixture.humanLog.readText(),
            "acceptance criteria hold 1 command where 2 commands were frozen at the first dispatch, none runs until loop resume accepts them",
        )

        val resume = fixture.loop("resume", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(
            resume.stdout,
            "Accepted the changed acceptance commands of t-1, checked from its next verification:\n  1. test -f hello.txt\n",
        )
        val accepted = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertContains(accepted.stdout.lines().line("acceptance_commands_accepted", accepted), "count=1")

        val verified = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, verified.exitCode, verified.toString())
        assertEquals("review", fixture.stateOf("t-1"), verified.toString())
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size)
        val again = fixture.loop("resume", "t-1", "--workflow", fixture.workflow.toString())
        assertFalse("Accepted" in again.stdout, again.toString())
    }

    /** Freezes [commands] as the list of t-1 at its first dispatch, as an earlier run would have. */
    private fun freeze(
        vararg commands: String,
        changed: List<String>? = null,
    ) = SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
        val frozen =
            FrozenCommands(
                ProjectId(fixture.projectId()),
                "t-1",
                "t-1",
                AcceptanceCriteria.digest(commands.toList()),
                commands.size,
                FROZEN_AT,
            )
        runBlocking {
            store.freeze(frozen).getOrThrow()
            if (changed != null) store.markChanged(frozen.projectId, "t-1", AcceptanceCriteria.digest(changed), changed.size).getOrThrow()
        }
    }

    /** The frozen list row of t-1: its digest and its changed digest. */
    private fun frozenRow(): Map<String, String?> =
        fixture.query("SELECT digest, changed_digest FROM acceptance_commands WHERE issue_id = 'default:t-1'").single()

    @Test
    fun `restored criteria that match the frozen list forget the changed list, a later resume accepts nothing`() {
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt` passes.")
        loopMode("{ type: acceptance_commands }")
        freeze("test -f hello.txt", "echo restored")
        val parked = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(listOf("unverified"), fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").map { it["reason"] })
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt")), frozenRow()["changed_digest"], parked.toString())

        // A human restores the criteria and unparks the issue through a label change.
        fixture.issue(
            "t-1",
            labels = listOf("ready-for-agent", "restored"),
            criteria = "- [ ] `test -f hello.txt` passes.\n- [ ] `echo restored` passes.",
        )
        val verified = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, verified.exitCode, verified.toString())
        assertEquals("review", fixture.stateOf("t-1"), verified.toString())
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt", "echo restored")), frozenRow()["digest"])
        assertEquals(null, frozenRow()["changed_digest"], "nothing is left for loop resume to accept")
    }

    @Test
    fun `a resume of an issue parked for another reason leaves the frozen list as it is`() {
        // The agent's list waits as a changed list, the issue holds it, and the issue is now parked by max_attempts.
        val agentList = listOf("test -f hello.txt")
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt` passes.")
        loopMode("{ type: acceptance_commands }")
        freeze("test -f hello.txt", "echo restored", changed = agentList)
        val project = ProjectId(fixture.projectId())
        val parkedAt = Instant.parse("2026-11-12T11:00:00Z")
        val parkedEvent = { ts: Instant, data: Map<String, String> ->
            RecordedEvent(ts = ts, projectId = project, kind = "issue_parked", issueId = "t-1", issueIdentifier = "t-1", data = data)
        }
        fixture.seed(
            parkedEvent(Instant.parse("2026-11-12T10:00:00Z"), mapOf("reason" to "unverified", "error" to "acceptance commands changed")),
            parkedEvent(parkedAt, mapOf("reason" to "max_attempts", "error" to "the agent failed")),
        )
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking {
                store
                    .park(
                        ParkedIssue(project, "t-1", "t-1", ParkReason.MaxAttempts, "todo", setOf("ready-for-agent"), parkedAt),
                    ).getOrThrow()
            }
        }

        val resume = fixture.loop("resume", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(resume.stdout, "Resumed t-1")
        assertFalse("Accepted" in resume.stdout, resume.toString())
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt", "echo restored")), frozenRow()["digest"])
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'acceptance_commands_accepted'").size)
    }

    @Test
    fun `loop resume accepts only the list the issue holds now`() {
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt` passes.")
        loopMode("{ type: acceptance_commands }")
        freeze("test -f hello.txt", "echo restored")
        fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt")), frozenRow()["changed_digest"])
        // The issue changed again after the park: the human would accept a list Loop never compared.
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt` passes.\n- [ ] `echo third` passes.")

        val resume = fixture.loop("resume", "t-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(resume.stdout, "Resumed t-1")
        assertContains(
            resume.stdout,
            "The acceptance criteria of t-1 no longer hold the changed list Loop found, nothing accepted. " +
                "Its next verification compares them with the frozen list again.",
        )
        assertFalse("Accepted" in resume.stdout, resume.toString())
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt", "echo restored")), frozenRow()["digest"])
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'acceptance_commands_accepted'").size)
    }

    @Test
    fun `loop resume prints the commands it accepts, redacted`() {
        val secret = "Rz4secretW81kT6pB3vN0"
        fixture.issue("t-1", criteria = "- [ ] `test -f hello.txt && test $secret` passes.\n- [ ] A human reads it.")
        loopMode("{ type: acceptance_commands }")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"),
        )
        freeze("test -f hello.txt", "echo restored")
        val env = mapOf("LOOP_TEST_SECRET" to secret)
        fixture.loop("run", fixture.workflow.toString(), "--once", env = env)

        val resume = fixture.loop("resume", "t-1", "--workflow", fixture.workflow.toString(), env = env)

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(
            resume.stdout,
            "Accepted the changed acceptance commands of t-1, checked from its next verification:\n" +
                "  1. test -f hello.txt && test [redacted:LOOP_TEST_SECRET]\n",
        )
        assertFalse(secret in resume.stdout + resume.stderr, resume.toString())
        assertEquals(AcceptanceCriteria.digest(listOf("test -f hello.txt && test $secret")), frozenRow()["digest"])
    }

    @Test
    fun `the judgment calls file is numbered with the check runs, loop show --evidence --check names the expanded checks`() {
        fixture.issue(
            "t-1",
            criteria =
                """
                |- [ ] A human reads the copy.
                |- [ ] `echo first-check-output` passes.
                |- [ ] `echo second-check-output` passes.
                """.trimMargin(),
        )
        loopMode("{ type: acceptance_commands }")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        val attemptId = checkNotNull(fixture.query("SELECT attempt_id FROM events WHERE kind = 'verdict'").single()["attempt_id"])
        val judgment = fixture.query("SELECT data FROM events WHERE kind = 'judgment_calls'").single()["data"].orEmpty()
        assertContains(judgment, "$attemptId.evidence-1.log")
        val checks = fixture.query("SELECT data FROM events WHERE kind = 'check_finished' ORDER BY seq").map { it["data"].orEmpty() }
        assertContains(checks[0], "$attemptId.evidence-2.log")
        assertContains(checks[1], "$attemptId.evidence-3.log")
        val first = fixture.loop("show", attemptId, "--evidence", "--check", "0", "--workflow", fixture.workflow.toString())
        assertEquals(0, first.exitCode, first.toString())
        assertContains(first.stdout, "first-check-output")
        assertFalse("judgment_calls" in first.stdout, first.toString())
        val second = fixture.loop("show", attemptId, "--evidence", "--check", "1", "--workflow", fixture.workflow.toString())
        assertContains(second.stdout, "second-check-output")
        assertFalse("first-check-output" in second.stdout, second.toString())
        val last = fixture.loop("show", attemptId, "--evidence", "--workflow", fixture.workflow.toString())
        assertContains(last.stdout, "second-check-output")
        val none = fixture.loop("show", attemptId, "--evidence", "--check", "2", "--workflow", fixture.workflow.toString())
        assertContains(none.stdout, "No evidence recorded for check 2 of this attempt.")
    }

    @Test
    fun `a secret in a criterion command and in a judgment call is redacted everywhere Loop writes, the command still runs`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        fixture.issue(
            "t-1",
            criteria =
                """
                |- [ ] `echo $secret > token.txt && echo printed $secret` passes.
                |- [ ] A human checks that $secret never shows.
                """.trimMargin(),
        )
        loopMode("{ type: acceptance_commands }")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"),
        )

        val run = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_TEST_SECRET" to secret))

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("review", fixture.stateOf("t-1"), run.toString())
        assertEquals(
            secret,
            fixture
                .workspace("t-1")
                .resolve("token.txt")
                .readText()
                .trim(),
            "the command ran with the real text",
        )
        val command = fixture.query("SELECT data FROM events WHERE kind = 'check_finished'").single()["data"].orEmpty()
        assertContains(command, "echo [redacted:LOOP_TEST_SECRET] > token.txt")
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertFalse(secret in trace.stdout, trace.toString())
        val files = Files.walk(fixture.loopHome).use { paths -> paths.filter { it.isRegularFile() }.toList() }
        val evidence = files.filter { it.fileName.toString().contains(".evidence-") }
        assertEquals(2, evidence.size, files.toString())
        val judgment = evidence.map { it.readText() }.single { "judgment_calls: 1" in it }
        assertContains(judgment, "- A human checks that [redacted:LOOP_TEST_SECRET] never shows.")
        assertContains(evidence.map { it.readText() }.single { "judgment_calls" !in it }, "printed [redacted:LOOP_TEST_SECRET]")
        for (file in files) {
            val bytes = file.readBytes()
            val text = if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            assertFalse(secret in String(text, Charsets.ISO_8859_1), "the secret is in $file")
        }
        assertTrue(fixture.humanLog.readText().isNotEmpty())
    }

    private companion object {
        val FROZEN_AT: Instant = Instant.parse("2026-11-12T09:00:00Z")
    }
}
