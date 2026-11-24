package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m2-03, end to end with the command runner: a `kinds:` key switches to Loop mode, the agent's
 * `loop tool move review` is a submitted result, and Loop runs the kind's check host side first.
 */
class LoopModeCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Loop mode with one command check [run], [maxTurns] turns per attempt. */
    private fun loopMode(
        run: String,
        extra: String = "",
        maxTurns: Int = 1,
    ) = fixture.writeWorkflow(
        extra =
            """
            |agent:
            |  max_turns: $maxTurns
            |kinds:
            |  code:
            |    delivery: { policy: auto, auto_merge: { enabled: true } }
            |    verify:
            |      checks:
            |        - { type: command, run: "$run" }
            |$extra
            """.trimMargin(),
    )

    @Test
    fun `a passing check in the workspace moves the issue to review and loop trace shows it`() {
        fixture.issue("t-1")
        // The agent wrote hello.txt in its workspace: the check passes only when it runs there.
        loopMode("test -f hello.txt && echo \$LOOP_ISSUE_IDENTIFIER > checked.txt")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals(
            "t-1",
            fixture
                .workspace("t-1")
                .resolve("checked.txt")
                .readText()
                .trim(),
        )
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        val lines = trace.stdout.lines()
        val started = lines.line("check_started", trace)
        assertContains(started, "command=\"test -f hello.txt && echo \$LOOP_ISSUE_IDENTIFIER > checked.txt\"")
        val finished = lines.line("check_finished", trace)
        assertContains(finished, "exit=0")
        assertTrue(Regex("""duration=\d""").containsMatchIn(finished), finished)
        assertContains(lines.line("result_submitted", trace), "to=review")
        assertContains(lines.line("issue_moved", trace), "by host after checks")
        val moved = lines.indexOfFirst { "issue_moved" in it }
        assertTrue(moved > lines.indexOf(finished), trace.stdout)
    }

    @Test
    fun `a failing check leaves the issue in todo while a queued comment still applies`() {
        val file = fixture.issue("t-1")
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |loop tool move review
            |loop tool comment "Ready for review"
            |
            """.trimMargin(),
        )
        loopMode("exit 3")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"), result.toString())
        assertContains(file.readText(), "Ready for review")
        // The failing check and its rerun (m2-08).
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'check_finished'")
        assertEquals(2, finished.size)
        finished.forEach { assertContains(it["data"].orEmpty(), "\"exit_code\":\"3\"") }
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind IN ('issue_moved', 'tool_request_failed')").size)
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        trace.stdout
            .lines()
            .filter { "check_finished" in it }
            .forEach { assertContains(it, "exit=3") }
    }

    /** Each turn of the agent saves its stdin, the turn input, to `inputs/turn-<n>.txt` outside the project. */
    private val inputs: Path by lazy { root.resolve("inputs").also { it.toFile().mkdirs() } }

    private fun turnInput(n: Int): String = inputs.resolve("turn-$n.txt").readText()

    /** An agent that saves each turn's input, writes `fixed.txt` from turn [fixOn] on, and submits on every turn. */
    private fun recordingAgent(fixOn: Int = Int.MAX_VALUE) {
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |cat > "$inputs/turn-${'$'}LOOP_TURN_NUMBER.txt"
            |if [ "${'$'}LOOP_TURN_NUMBER" -ge $fixOn ]; then touch fixed.txt; fi
            |loop tool move review
            |
            """.trimMargin(),
        )
    }

    /** A check that passes once `fixed.txt` exists, else prints 50 numbered lines and `boom`, then exits 7. */
    private val fixedCheck = "test -f fixed.txt || { seq 1 50; echo boom; exit 7; }"

    @Test
    fun `a refuted move on turn 1 is followed by turn 2 whose guidance names the check, its exit code and its last lines (m2-06)`() {
        fixture.issue("t-1")
        recordingAgent(fixOn = 2)
        loopMode(fixedCheck, maxTurns = 3)

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertContains(turnInput(1), "Work on t-1")
        val guidance = turnInput(2)
        assertTrue(guidance.startsWith("Continue working on t-1. This is turn 2 of 3. The issue is still in todo."), guidance)
        assertContains(guidance, "Your last result was refuted")
        assertContains(guidance, "Check: $fixedCheck\nExit code: 7\nLast 20 lines of its output:\n")
        assertTrue(guidance.trimEnd().endsWith((32..50).joinToString("\n") + "\nboom"), guidance)
        assertFalse("\n31\n" in guidance, "only the last lines of the output")
        assertTrue(guidance.length < 4_000, "the guidance is bounded, ${guidance.length} chars")
        val verdicts = fixture.query("SELECT data FROM events WHERE kind = 'verdict' ORDER BY seq").map { it["data"].orEmpty() }
        assertEquals(2, verdicts.size)
        assertContains(verdicts[0], "\"verdict\":\"refuted\"")
        assertContains(verdicts[1], "\"verdict\":\"verified\"")
    }

    @Test
    fun `refutations count against max_turns and max_attempts, so an agent that never passes parks (m2-06)`() {
        fixture.issue("t-1")
        recordingAgent()
        loopMode(fixedCheck, extra = "retries:\n  max_attempts: 1", maxTurns = 2)

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"))
        assertEquals(2, fixture.query("SELECT 1 FROM events WHERE kind = 'verdict'").size, "one refutation per turn")
        assertContains(turnInput(2), "Exit code: 7")
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").single()
        assertContains(finished["data"].orEmpty(), "\"reason\":\"refuted\"")
        val parked = fixture.query("SELECT data FROM events WHERE kind = 'issue_parked'").single()
        assertContains(parked["data"].orEmpty(), "\"reason\":\"max_attempts\"")
        assertContains(parked["data"].orEmpty(), "\"error\":\"refuted\"")
        assertContains(fixture.humanLog.readText(), "attempt failed, its checks refuted its last result")
    }

    @Test
    fun `an attempt that edits a protected path is refuted with the path named in the findings (m2-12)`() {
        fixture.issue("t-1")
        fixture.projectDir
            .resolve("guarded")
            .toFile()
            .mkdirs()
        fixture.projectDir
            .resolve("guarded/data.txt")
            .toFile()
            .writeText("keep me\n")
        // Turn 1 commits an edit of the protected file, turn 2 reverts it; every turn submits.
        val git = "git -c user.name=Agent -c user.email=agent@example.com"
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |cat > "$inputs/turn-${'$'}LOOP_TURN_NUMBER.txt"
            |if [ "${'$'}LOOP_TURN_NUMBER" -eq 1 ]; then echo changed >> guarded/data.txt; $git commit -qam edit; fi
            |if [ "${'$'}LOOP_TURN_NUMBER" -eq 2 ]; then $git revert --no-edit HEAD >/dev/null; fi
            |loop tool move review
            |
            """.trimMargin(),
        )
        loopMode("true", extra = "      guards:\n        protect: [guarded/, WORKFLOW.md]", maxTurns = 2)
        fixture.useGitWorktrees()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val verdicts = fixture.query("SELECT data FROM events WHERE kind = 'verdict' ORDER BY seq").map { it["data"].orEmpty() }
        assertEquals(2, verdicts.size, result.toString())
        assertContains(verdicts[0], "\"verdict\":\"refuted\"")
        assertContains(verdicts[0], "\"checks\":\"2\"")
        assertContains(verdicts[1], "\"verdict\":\"verified\"")
        // m2-08: the refuted diff_policy ran twice, its rerun failing too, before the verdict of turn 1.
        val firstVerification =
            fixture
                .query("SELECT data FROM events WHERE kind = 'check_finished' ORDER BY seq")
                .map { it["data"].orEmpty() }
                .take(3)
        assertContains(firstVerification[1], "\"exit_code\":\"1\"")
        assertContains(firstVerification[2], "\"exit_code\":\"1\"")
        assertContains(firstVerification[2], "\"rerun\":\"true\"")
        val guidance = turnInput(2)
        assertContains(guidance, "Your last result was refuted")
        assertContains(guidance, "Check: diff_policy base=main protect=guarded/,WORKFLOW.md\nExit code: 1\n")
        assertContains(guidance, "modified guarded/data.txt (protected by guarded/)")
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertContains(trace.stdout, "command=\"diff_policy base=main protect=guarded/,WORKFLOW.md\"")
    }

    /** Loop mode with the raw YAML [checks] value, such as `[]`, and one turn per attempt. */
    private fun loopModeChecks(checks: String) =
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks: $checks
                """.trimMargin(),
        )

    /** Runs Loop once on a submitted result that must give `unverified` with a reason starting with [reason], and checks the park. */
    private fun assertParkedUnverified(reason: String) {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"), "never delivered\n$result")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"unverified\"")
        assertContains(verdict, "\"reason\":\"$reason")
        assertEquals(
            listOf("unverified"),
            fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 'default:t-1'").map { it["reason"] },
        )
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").single()["data"].orEmpty()
        assertContains(finished, "\"reason\":\"unverified\"")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertContains(trace.stdout.lines().line("verdict ", trace), "unverified")
        assertContains(trace.stdout.lines().line("issue_parked", trace), "reason=unverified")
        assertContains(fixture.humanLog.readText(), "parked because its result could not be verified, $reason")

        val second = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, second.exitCode, second.toString())
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size, "a parked issue is not dispatched")
    }

    @Test
    fun `a kind with an empty check list gives unverified and parks the issue (m2-08)`() {
        loopModeChecks("[]")
        assertParkedUnverified("the kind has no check")
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size)
    }

    @Test
    fun `a check whose command is not found gives unverified and parks the issue (m2-08)`() {
        loopMode("no-such-command-m2-08")
        assertParkedUnverified("check 0 could not run: command not found, exit code 127")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size, "not rerun")
    }

    @Test
    fun `a check over its timeout gives unverified and parks the issue (m2-08)`() {
        loopModeChecks("[{ type: command, run: \"sleep 30\", timeout_ms: 300 }]")
        assertParkedUnverified("check 0 could not run: timed out after")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size, "not rerun")
    }

    @Test
    fun `a diff_policy that could not run gives unverified and parks the issue, never rerun (m2-12)`() {
        // Without the worktree hooks the workspace is a plain folder, not the top of a git work tree.
        loopModeChecks("[{ type: diff_policy }]\n      guards:\n        protect: [WORKFLOW.md]")
        assertParkedUnverified("check 0 could not run: the workspace is not the top of a git work tree")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'check_started'").size, "not rerun")
    }

    @Test
    fun `a check that fails then passes on its rerun gives verified and keeps both runs as evidence (m2-08)`() {
        fixture.issue("t-1")
        // Fails on its first run only: the first run leaves a marker outside the workspace.
        val marker = root.resolve("ran-once")
        loopMode("test -f $marker || { touch $marker; echo flaky; exit 1; }")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val verdict = fixture.query("SELECT data, attempt_id FROM events WHERE kind = 'verdict'").single()
        assertContains(verdict["data"].orEmpty(), "\"verdict\":\"verified\"")
        val attemptId = verdict["attempt_id"]!!
        assertContains(verdict["data"].orEmpty(), "\"evidence\":\"$attemptId.evidence-1.log,$attemptId.evidence-2.log\"")
        val attempts = fixture.loopHome.resolve("attempts/${fixture.projectId()}")
        assertContains(attempts.resolve("$attemptId.evidence-1.log").readText(), "exit_code: 1\n")
        assertContains(attempts.resolve("$attemptId.evidence-1.log").readText(), "flaky")
        assertContains(attempts.resolve("$attemptId.evidence-2.log").readText(), "exit_code: 0\n")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        val finished = trace.stdout.lines().filter { "check_finished" in it }
        assertEquals(2, finished.size, trace.toString())
        assertContains(finished[0], "exit=1")
        assertContains(finished[1], "exit=0")
        assertContains(finished[1], "rerun")
        assertContains(fixture.humanLog.readText(), "rerun of check")
    }

    @Test
    fun `a check that fails twice gives refuted (m2-08)`() {
        fixture.issue("t-1")
        loopMode("echo always; exit 5")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"))
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'check_finished' ORDER BY seq").map { it["data"].orEmpty() }
        assertEquals(2, finished.size)
        finished.forEach { assertContains(it, "\"exit_code\":\"5\"") }
        assertContains(finished[1], "\"rerun\":\"true\"")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"refuted\"")
        assertEquals(emptyList(), fixture.query("SELECT reason FROM parked_issues"), "a refutation is retried, not parked")
    }

    private fun List<String>.line(
        name: String,
        trace: LoopResult,
    ): String = singleOrNull { name in it } ?: kotlin.test.fail("no single $name line\n$trace")
}
