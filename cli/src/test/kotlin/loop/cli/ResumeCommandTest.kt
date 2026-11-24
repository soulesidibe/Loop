package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `loop resume` and parking across processes, end to end through the installed launcher (m1-07). */
class ResumeCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** A failing agent and `retries.max_attempts: 1`, so the first failure parks the issue. */
    private fun parkOnFirstFailure(activeStates: List<String> = listOf("todo")) {
        fixture.useFailingAgent()
        fixture.writeWorkflow(activeStates = activeStates, extra = "retries:\n  max_attempts: 1")
        fixture.issue("t-1")
    }

    private fun runOnce(): LoopResult {
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        return run
    }

    private fun touchedLines(): List<String> =
        fixture
            .workspace("t-1")
            .resolve("touched.log")
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    @Test
    fun `a parked issue is not dispatched by a new process until loop resume`() {
        parkOnFirstFailure()
        runOnce()
        assertEquals(1, fixture.agentCalls())
        assertEquals(1, fixture.query("SELECT issue_id FROM parked_issues").size)

        val second = runOnce()

        assertEquals(1, fixture.agentCalls(), second.toString())
        assertEquals(1, touchedLines().size)
        assertContains(second.stdout, "No eligible issue.")

        val parkedAt =
            fixture
                .query("SELECT parked_at FROM parked_issues WHERE issue_id = 'default:t-1'")
                .single()["parked_at"]!!
                .take(SECONDS_PREFIX) + "Z"
        val resume = fixture.loop("resume", "t-1", cwd = fixture.projectDir)

        assertEquals(0, resume.exitCode, resume.toString())
        assertEquals(
            "Resumed t-1, parked since $parkedAt (max_attempts). It can be dispatched again on the next tick.",
            resume.stdout.trim(),
        )
        assertTrue(Regex("""parked since \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z """).containsMatchIn(resume.stdout), resume.stdout)
        assertEquals(emptyList(), fixture.query("SELECT issue_id FROM parked_issues"))

        runOnce()

        assertEquals(2, fixture.agentCalls())
    }

    @Test
    fun `resume records issue unparked with reason resume and no attempt id`() {
        parkOnFirstFailure()
        runOnce()

        val resume = fixture.loop("resume", "t-1", cwd = fixture.projectDir)
        assertEquals(0, resume.exitCode, resume.toString())

        val row =
            fixture
                .query("SELECT issue_id, project_id, attempt_id, data FROM events WHERE kind = 'issue_unparked'")
                .single()
        assertEquals("default:t-1", row["issue_id"])
        assertEquals(fixture.projectId(), row["project_id"])
        assertNull(row["attempt_id"])
        assertEquals("""{"reason":"resume"}""", row["data"])
    }

    @Test
    fun `a resume is a human touch, counted by the next attempt row of the ledger`() {
        parkOnFirstFailure()
        runOnce()

        val resume = fixture.loop("resume", "t-1", cwd = fixture.projectDir)
        assertEquals(0, resume.exitCode, resume.toString())
        val touch = fixture.query("SELECT issue_id, attempt_id, data FROM events WHERE kind = 'human_touch'").single()
        assertEquals("default:t-1", touch["issue_id"])
        assertNull(touch["attempt_id"])
        assertEquals("""{"touch":"resume"}""", touch["data"])

        runOnce()

        val rows =
            fixture.query(
                "SELECT attempt_number, human_touches, outcome, runner, tokens, cost_usd, model FROM attempts ORDER BY seq",
            )
        assertEquals(listOf("1", "2"), rows.map { it["attempt_number"] })
        assertEquals(listOf("0", "1"), rows.map { it["human_touches"] })
        assertEquals(listOf("failed", "failed"), rows.map { it["outcome"] })
        assertEquals(listOf("command", "command"), rows.map { it["runner"] })
        // The command runner reports no usage, no cost and no model: null, never zero.
        assertTrue(rows.all { it["tokens"] == null && it["cost_usd"] == null && it["model"] == null }, rows.toString())
        val ledger = fixture.loopHome.resolve("ledger").resolve("${fixture.projectId()}.jsonl")
        val lines = ledger.readText().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines.all { "\"issue_identifier\":\"t-1\"" in it && "\"tokens\":null" in it }, lines.toString())
    }

    @Test
    fun `resume refuses to run inside an attempt and changes nothing in the store`() {
        parkOnFirstFailure()
        runOnce()
        val parked = fixture.query("SELECT issue_id, reason, parked_at FROM parked_issues")
        val events = fixture.query("SELECT seq FROM events").size

        val refused = fixture.loop("resume", "t-1", cwd = fixture.projectDir, env = mapOf("LOOP_ATTEMPT_ID" to "attempt-1"))

        assertEquals(5, refused.exitCode, refused.toString())
        assertEquals(
            "loop resume: refused inside a Loop attempt, only an operator resumes a parked issue. Nothing changed.",
            refused.stderr.trim(),
        )
        assertEquals("", refused.stdout.trim())
        assertEquals(parked, fixture.query("SELECT issue_id, reason, parked_at FROM parked_issues"))
        assertEquals(events, fixture.query("SELECT seq FROM events").size)

        // The same command from an operator shell, without the variable, still resumes.
        assertEquals(0, fixture.loop("resume", "t-1", cwd = fixture.projectDir).exitCode)
    }

    @Test
    fun `resuming an issue that is not parked changes nothing and exits 4`() {
        fixture.issue("t-1")

        val result = fixture.loop("resume", "t-1", cwd = fixture.projectDir)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals("loop resume: t-1 is not parked. Nothing changed.", result.stderr.trim())
        assertEquals("", result.stdout.trim())
    }

    @Test
    fun `resuming an unknown issue exits 2`() {
        fixture.issue("t-1")

        val result = fixture.loop("resume", "t-9", cwd = fixture.projectDir)

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("loop resume: no issue \"t-9\" in the tracker at ${fixture.issuesDir.toRealPath()}.", result.stderr.trim())
        assertEquals("", result.stdout.trim())
    }

    @Test
    fun `a state change in the tracker file unparks the issue for the next run`() {
        parkOnFirstFailure(activeStates = listOf("todo", "in_progress"))
        runOnce()
        assertEquals(1, fixture.agentCalls())

        fixture.issue("t-1", state = "in_progress")
        runOnce()

        assertEquals(2, fixture.agentCalls())
        val data = fixture.query("SELECT data FROM events WHERE kind = 'issue_unparked'").single()["data"]!!
        assertContains(data, "\"reason\":\"tracker_changed\"")
        assertContains(data, "\"from_state\":\"todo\"")
        assertContains(data, "\"to_state\":\"in_progress\"")
    }

    @Test
    fun `resume unparks an issue parked with max_continuations`() {
        // An agent that exits 0 without moving the issue, and a cap of one normal exit without progress.
        // One turn per attempt: the command runner keeps an eligible issue in further turns up to max_turns.
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}LOOP_ATTEMPT_ID" >> "${fixture.agentCallsFile}"
            |exit 0
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(extra = "agent:\n  max_turns: 1\nretries:\n  max_continuations: 1")
        fixture.issue("t-1")
        runOnce()
        assertEquals(1, fixture.agentCalls())
        assertEquals(
            "max_continuations",
            fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").single()["reason"],
        )
        runOnce()
        assertEquals(1, fixture.agentCalls())

        val resume = fixture.loop("resume", "t-1", cwd = fixture.projectDir)

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(resume.stdout, "(max_continuations). It can be dispatched again on the next tick.")
        assertEquals(emptyList(), fixture.query("SELECT issue_id FROM parked_issues"))

        runOnce()

        assertEquals(2, fixture.agentCalls())
    }

    @Test
    fun `resume clears an unverified park like any other (m2-08)`() {
        // The default agent submits its result; a kind with no check cannot verify it.
        fixture.writeWorkflow(extra = "agent:\n  max_turns: 1\nkinds:\n  code:\n    verify:\n      checks: []")
        fixture.issue("t-1")
        runOnce()
        assertEquals(
            "unverified",
            fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").single()["reason"],
        )
        val parkedRun = runOnce()
        assertContains(parkedRun.stdout, "No eligible issue.")
        assertEquals(1, attemptsStarted())

        val resume = fixture.loop("resume", "t-1", cwd = fixture.projectDir)

        assertEquals(0, resume.exitCode, resume.toString())
        assertContains(resume.stdout, "(unverified). It can be dispatched again on the next tick.")
        assertEquals(emptyList(), fixture.query("SELECT issue_id FROM parked_issues"))

        runOnce()

        assertEquals(2, attemptsStarted(), "dispatched again after the resume")
    }

    private fun attemptsStarted(): Int = fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size

    private companion object {
        const val SECONDS_PREFIX = 19
    }
}
