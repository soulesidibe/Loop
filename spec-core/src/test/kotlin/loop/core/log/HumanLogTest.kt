package loop.core.log

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.trackers.IssueIdDisplay
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The human readable run log (m1-39): plain sentences, one line each, from what the run records. */
class HumanLogTest {
    private val lines = mutableListOf<String>()
    private val now = Instant.parse("2026-11-11T17:02:11Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun humanLog(redactor: Redactor = Redactor.NONE): HumanLog =
        HumanLog(out = { lines += it }, clock = clock, redactor = redactor, zone = ZoneOffset.UTC, base = Path.of("/project"))

    private fun event(
        kind: String,
        data: Map<String, String> = emptyMap(),
        phase: String? = null,
        attempt: String? = "a1",
        identifier: String = "m1-13",
    ): RecordedEvent =
        RecordedEvent(
            ts = now,
            projectId = ProjectId("p1"),
            kind = kind,
            runId = RunId("r1"),
            issueId = identifier,
            issueIdentifier = identifier,
            attemptId = attempt?.let(::AttemptId),
            phase = phase,
            data = data,
        )

    /** Feeds the stream fixture [name] as runner lines of attempt `a1`, whose workspace is `/work`. */
    private fun HumanLog.stream(name: String) {
        val text = checkNotNull(HumanLogTest::class.java.getResource("/streams/$name.jsonl")) { "no fixture $name" }.readText()
        text.lines().filter { it.isNotBlank() }.forEach { runnerLine(event("runner_output"), it) }
    }

    @Test
    fun `a submitted result, its checks and the verified move read as sentences`() {
        val log = humanLog()

        log.recorded(event("attempt_started", mapOf("loop_version" to "abc")))
        log.recorded(event("result_submitted", mapOf("to" to "review")))
        log.recorded(
            event(
                "check_finished",
                mapOf(
                    "check" to "0",
                    "command" to "./gradlew test",
                    "exit_code" to "0",
                    "duration_ms" to "61200",
                ),
            ),
        )
        log.recorded(event("check_finished", mapOf("check" to "1", "command" to "./lint.sh", "exit_code" to "2", "duration_ms" to "800")))
        log.recorded(
            event("check_finished", mapOf("check" to "2", "command" to "./slow.sh", "timed_out" to "true", "duration_ms" to "5000")),
        )
        log.recorded(event("check_finished", mapOf("check" to "3", "command" to "./x.sh", "reason" to "requires perl on PATH: no perl")))
        log.recorded(event("check_finished", mapOf("check" to "4", "command" to "./y.sh", "canceled" to "true")))
        log.recorded(event("issue_moved", mapOf("from" to "todo", "to" to "review", "by" to "host", "verified" to "true")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  attempt started",
                "17:02:11  m1-13  submitted its result, a move to review",
                "17:02:11  m1-13  check ./gradlew test passed in 1m 2s",
                "17:02:11  m1-13  check ./lint.sh failed with exit code 2 in 1s",
                "17:02:11  m1-13  check ./slow.sh timed out after 5s",
                "17:02:11  m1-13  check ./x.sh could not run: requires perl on PATH: no perl",
                "17:02:11  m1-13  check ./y.sh was stopped",
                "17:02:11  m1-13  moved to review after its checks passed",
            ),
            lines,
        )
    }

    @Test
    fun `a holdout check reads as a hidden check that passed, failed or could not run, with no exit code or duration (m2-14)`() {
        val log = humanLog()
        val hidden = mapOf("check" to "1", "command" to "\$LOOP_HOLDOUT/acceptance.sh", "holdout" to "true")

        log.recorded(event("check_finished", hidden + ("result" to "failed")))
        log.recorded(event("check_finished", hidden + mapOf("result" to "passed", "rerun" to "true")))
        log.recorded(event("check_finished", hidden + ("result" to "could_not_run")))
        log.recorded(event("check_finished", hidden + ("canceled" to "true")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  hidden check \$LOOP_HOLDOUT/acceptance.sh failed",
                "17:02:11  m1-13  rerun of hidden check \$LOOP_HOLDOUT/acceptance.sh passed",
                "17:02:11  m1-13  hidden check \$LOOP_HOLDOUT/acceptance.sh could not run",
                "17:02:11  m1-13  hidden check \$LOOP_HOLDOUT/acceptance.sh was stopped",
            ),
            lines,
        )
    }

    @Test
    fun `a result file reads by its status, an invalid one by its validation error (m2-15)`() {
        val log = humanLog()
        val file = mapOf("source" to "file")

        log.recorded(event("result_submitted", file + mapOf("to" to "review", "status" to "done")))
        log.recorded(event("result_submitted", file + mapOf("status" to "needs_info")))
        log.recorded(event("result_submitted", file + mapOf("status" to "blocked", "reason" to "No device")))
        log.recorded(event("result_invalid", mapOf("reason" to "status is required")))
        log.recorded(event("tool_request_failed", mapOf("request" to "result", "to" to "review", "reason" to "no review state")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  submitted its result in .loop/result.json, a move to review",
                "17:02:11  m1-13  submitted a needs_info result in .loop/result.json, it asks a question",
                "17:02:11  m1-13  submitted a blocked result in .loop/result.json: No device",
                "17:02:11  m1-13  its result file is invalid: status is required",
                "17:02:11  m1-13  could not apply the agent's result file, a move to review: no review state",
            ),
            lines,
        )
    }

    @Test
    fun `a result submitted with submit_result reads like the result file, named by its tool (m2-16)`() {
        val log = humanLog()
        val mcp = mapOf("source" to "mcp")

        log.recorded(event("result_submitted", mcp + mapOf("to" to "review", "status" to "done")))
        log.recorded(event("result_submitted", mcp + mapOf("status" to "needs_info")))
        log.recorded(event("result_submitted", mcp + mapOf("status" to "failed", "reason" to "No device")))
        log.recorded(event("result_invalid", mcp + mapOf("reason" to "status is required")))
        log.recorded(event("tool_request_failed", mcp + mapOf("request" to "result", "to" to "review", "reason" to "no review state")))
        log.recorded(event("tool_request_failed", mapOf("request" to "result", "reason" to "submit_result needs Loop mode")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  submitted its result with submit_result, a move to review",
                "17:02:11  m1-13  submitted a needs_info result with submit_result, it asks a question",
                "17:02:11  m1-13  submitted a failed result with submit_result: No device",
                "17:02:11  m1-13  its submit_result call is invalid: status is required",
                "17:02:11  m1-13  could not apply the agent's submit_result, a move to review: no review state",
                "17:02:11  m1-13  could not apply the agent's submit_result: submit_result needs Loop mode",
            ),
            lines,
        )
    }

    @Test
    fun `a result whose checks run again after a restart says so (m2-04)`() {
        val log = humanLog()

        log.recorded(event("result_submitted", mapOf("to" to "review", "restored" to "true")))

        assertEquals(listOf("17:02:11  m1-13  checks its submitted result again after a restart, a move to review"), lines)
    }

    @Test
    fun `each verdict reads as a sentence with its evidence count (m2-05)`() {
        val log = humanLog()

        log.recorded(event("verdict", mapOf("verdict" to "verified", "checks" to "2", "evidence" to "a.evidence-1.log,a.evidence-2.log")))
        log.recorded(event("verdict", mapOf("verdict" to "refuted", "checks" to "1", "evidence" to "a.evidence-3.log")))
        log.recorded(event("verdict", mapOf("verdict" to "unverified", "checks" to "1")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  verdict verified, evidence of 2 checks saved",
                "17:02:11  m1-13  verdict refuted, evidence of 1 check saved",
                "17:02:11  m1-13  verdict unverified, no evidence saved",
            ),
            lines,
        )
    }

    @Test
    fun `a rerun, an unverified verdict and its park read as sentences (m2-08)`() {
        val log = humanLog()

        val check = mapOf("check" to "0", "command" to "./gradlew test", "duration_ms" to "42000")
        log.recorded(event("check_finished", check + ("exit_code" to "1")))
        log.recorded(event("check_finished", check + mapOf("exit_code" to "0", "rerun" to "true")))
        log.recorded(event("verdict", mapOf("verdict" to "unverified", "checks" to "0", "reason" to "the kind has no check")))
        log.recorded(event("attempt_finished", mapOf("outcome" to "failed", "reason" to "unverified", "turns" to "1")))
        val why = "check 0 could not run: timed out after 30 s"
        log.recorded(event("issue_parked", mapOf("reason" to "unverified", "error" to why)))

        assertEquals(
            listOf(
                "17:02:11  m1-13  check ./gradlew test failed with exit code 1 in 42s",
                "17:02:11  m1-13  rerun of check ./gradlew test passed in 42s",
                "17:02:11  m1-13  verdict unverified (the kind has no check), no evidence saved",
                "17:02:11  m1-13  attempt failed, its checks could not verify its last result",
                "17:02:11  m1-13  parked because its result could not be verified, $why, until loop resume or a tracker change",
            ),
            lines,
        )
    }

    @Test
    fun `a budget warning, a budget passed and the budget park read as sentences (m2-20)`() {
        val log = humanLog()

        log.recorded(event("budget_warning", mapOf("budget" to "per_attempt_tokens", "used" to "8100000", "limit" to "10000000")))
        log.recorded(event("budget_warning", mapOf("budget" to "per_day_usd", "used" to "80.5", "limit" to "100")))
        log.recorded(event("budget_reached", mapOf("budget" to "per_day_usd", "used" to "101.25", "limit" to "100")))
        log.recorded(event("attempt_finished", mapOf("outcome" to "failed", "reason" to "budget", "turns" to "2")))
        val park = mapOf("reason" to "budget", "budget" to "per_day_usd", "used" to "101.25", "limit" to "100")
        log.recorded(event("issue_parked", park))

        assertEquals(
            listOf(
                "17:02:11  m1-13  used 8.1M of 10M tokens, 80 percent of its per_attempt_tokens budget",
                "17:02:11  m1-13  used \$80.50 of \$100.00, 80 percent of its per_day_usd budget",
                "17:02:11  m1-13  passed its per_day_usd budget, \$101.25 of \$100.00, the turn stops",
                "17:02:11  m1-13  attempt failed, the attempt passed a spend budget",
                "17:02:11  m1-13  parked after passing its per_day_usd budget, \$101.25 of \$100.00, until loop resume or a tracker change",
            ),
            lines,
        )
    }

    @Test
    fun `a turn interrupted at the hard limit, its rotation and the split_issue park read as sentences (m3-12)`() {
        val log = humanLog()

        log.recorded(event("turn_completed", mapOf("turn" to "1", "max_turns" to "20", "interrupted" to "hard_limit")))
        val rotated = mapOf("reason" to "hard_limit", "fraction" to "0.2600", "rotation" to "1", "max_rotations" to "5")
        log.recorded(event("session_rotated", rotated))
        log.recorded(event("attempt_finished", mapOf("outcome" to "failed", "reason" to "split_issue", "turns" to "2")))
        log.recorded(event("issue_parked", mapOf("reason" to "split_issue", "without_progress" to "2", "rotations" to "1")))
        log.recorded(event("issue_commented", mapOf("chars" to "300", "by" to "host", "reason" to "split_issue")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  turn 1 was interrupted at the hard limit",
                "17:02:11  m1-13  interrupted its turn at 26 percent of its window and started a new session, rotation 1 of at most 5",
                "17:02:11  m1-13  attempt failed, its sessions filled up without progress",
                "17:02:11  m1-13  parked with the finding split this issue, after 2 session rotations in a row without progress, " +
                    "until loop resume or a tracker change",
                "17:02:11  m1-13  commented that it should be split",
            ),
            lines,
        )
    }

    @Test
    fun `a rotation whose prompt was cut says what each slot lost (m3-14)`() {
        val log = humanLog()
        val rotated = mapOf("reason" to "soft_limit", "fraction" to "0.2400", "rotation" to "1", "max_rotations" to "5")
        val cut =
            mapOf(
                "prompt_tokens" to "98",
                "prompt_limit_tokens" to "100",
                "over_limit" to "false",
                "cut_template_tokens" to "0",
                "cut_issue_tokens" to "0",
                "cut_verify_findings_tokens" to "0",
                "cut_session_brief_tokens" to "60",
                "cut_lessons_tokens" to "0",
                "cut_context_sources_tokens" to "0",
            )

        log.recorded(event("session_rotated", rotated + cut))
        log.recorded(event("session_rotated", rotated + cut + ("over_limit" to "true") + ("prompt_tokens" to "120")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  started a new session at 24 percent of its window, rotation 1 of at most 5, " +
                    "its prompt cut by 60 tokens to fit its limit of 100 tokens: session_brief 60",
                "17:02:11  m1-13  started a new session at 24 percent of its window, rotation 1 of at most 5, " +
                    "its prompt cut by 60 tokens to fit its limit of 100 tokens: session_brief 60. It is still 120 tokens, over its limit",
            ),
            lines,
        )
    }

    @Test
    fun `judgment calls and the verdict that names them read as sentences (m2-09)`() {
        val log = humanLog()

        log.recorded(event("judgment_calls", mapOf("count" to "2", "evidence" to "a.evidence-1.log")))
        log.recorded(event("judgment_calls", mapOf("count" to "1")))
        val verdict = mapOf("verdict" to "verified", "checks" to "1", "evidence" to "a.evidence-2.log")
        log.recorded(event("verdict", verdict + ("judgment_calls" to "2")))
        log.recorded(event("verdict", verdict + ("judgment_calls" to "1")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  2 acceptance criteria are judgment calls, listed in the evidence for a human",
                "17:02:11  m1-13  1 acceptance criterion is a judgment call, not saved as evidence",
                "17:02:11  m1-13  verdict verified, evidence of 1 check saved, 2 judgment calls left to a human",
                "17:02:11  m1-13  verdict verified, evidence of 1 check saved, 1 judgment call left to a human",
            ),
            lines,
        )
    }

    @Test
    fun `the frozen acceptance commands, a change, an empty list and an accept read as sentences (m2-09)`() {
        val log = humanLog()

        log.recorded(event("acceptance_commands_frozen", mapOf("count" to "2")))
        log.recorded(event("acceptance_commands_changed", mapOf("frozen" to "2", "count" to "1")))
        log.recorded(event("acceptance_commands_empty", mapOf("judgment_calls" to "1")))
        log.recorded(event("acceptance_commands_accepted", mapOf("count" to "1")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  acceptance commands frozen, 2 commands",
                "17:02:11  m1-13  acceptance criteria hold 1 command where 2 commands were frozen at the first dispatch, " +
                    "none runs until loop resume accepts them",
                "17:02:11  m1-13  acceptance criteria hold no command, 1 judgment call instead",
                "17:02:11  m1-13  loop resume accepted the changed acceptance commands, 1 command",
            ),
            lines,
        )
    }

    @Test
    fun `run start, an attempt with one turn and a move, then run end, in plain sentences`() {
        val log = humanLog()

        log.runStarted(Path.of("/project/WORKFLOW.md"))
        log.polled(listOf("m1-13"), running = 0, maxRunning = 10)
        log.recorded(event("attempt_started", mapOf("loop_version" to "abc")))
        log.recorded(event("phase", mapOf("workspace" to "/project/workspaces/m1-13"), phase = "PreparingWorkspace"))
        log.recorded(event("turn_started", mapOf("turn" to "1", "max_turns" to "10")))
        log.recorded(event("usage", mapOf("attempt_tokens" to "7213000", "total_cost_usd" to "3.3812")))
        log.recorded(event("turn_completed", mapOf("turn" to "1", "max_turns" to "10")))
        log.recorded(event("issue_moved", mapOf("from" to "todo", "to" to "review", "by" to "host")))
        log.recorded(event("phase", mapOf("issue_state" to "review", "exit_reason" to "not_active"), phase = "Succeeded"))
        log.recorded(event("attempt_finished", mapOf("outcome" to "succeeded", "exit_reason" to "not_active", "turns" to "1")))
        log.runEnded(reason = null, attemptsCanceled = 0)

        assertEquals(
            listOf(
                "17:02:11  run started for /project/WORKFLOW.md",
                "17:02:11  1 issue eligible (m1-13)",
                "17:02:11  m1-13  attempt started in workspaces/m1-13",
                "17:02:11  m1-13  turn 1 of 10 started",
                "17:02:11  m1-13  turn 1 finished, 7.2M tokens, \$3.38",
                "17:02:11  m1-13  moved to review by the agent's request",
                "17:02:11  m1-13  attempt succeeded, the issue is in review",
                "17:02:11  run ended",
            ),
            lines,
        )
    }

    @Test
    fun `a reload and a rejected edit of the workflow get a line each with their short versions`() {
        val log = humanLog()
        val good = "1a2b3c4d" + "0".repeat(56)
        val next = "5e6f7a8b" + "0".repeat(56)

        log.recorded(
            event("config_reloaded", mapOf("config_version" to next, "previous_version" to good, "diff" to "x"), attempt = null),
        )
        log.recorded(
            event(
                "config_reload_failed",
                mapOf(
                    "active_version" to next,
                    "error" to "invalid_config",
                    "message" to "agent.max_turns must be a positive integer, got 0",
                ),
                attempt = null,
            ),
        )

        assertEquals(
            listOf(
                "17:02:11  WORKFLOW.md reloaded, config 5e6f7a8b replaces 1a2b3c4d",
                "17:02:11  WORKFLOW.md edit rejected, config 5e6f7a8b stays active: agent.max_turns must be a positive integer, got 0",
            ),
            lines,
        )
    }

    @Test
    fun `a retry is named by its number, a failure and a park say why in words`() {
        val log = humanLog()

        log.recorded(event("attempt_started"))
        log.recorded(event("phase", mapOf("workspace_key" to "m1-13"), phase = "PreparingWorkspace"))
        log.recorded(event("turn_failed", mapOf("turn" to "1", "max_turns" to "10", "reason" to "exit_code=1")))
        log.recorded(event("phase", mapOf("reason" to "exit_code=1"), phase = "Failed"))
        log.recorded(event("attempt_finished", mapOf("outcome" to "failed", "reason" to "exit_code=1", "turns" to "1")))
        log.recorded(
            event("retry_scheduled", mapOf("kind" to "error", "attempt" to "2", "delay_ms" to "10000", "error" to "exit_code=1")),
        )
        log.recorded(event("attempt_started", attempt = "a2"))
        log.recorded(event("phase", mapOf("workspace" to "/elsewhere/m1-13"), phase = "PreparingWorkspace", attempt = "a2"))
        log.recorded(event("attempt_finished", mapOf("outcome" to "failed", "reason" to "stalled"), attempt = "a2"))
        log.recorded(event("issue_parked", mapOf("reason" to "max_attempts", "failures" to "2", "error" to "stalled"), attempt = "a2"))

        assertEquals(
            listOf(
                "17:02:11  m1-13  attempt started",
                "17:02:11  m1-13  turn 1 failed, the agent exited with code 1",
                "17:02:11  m1-13  attempt failed, the agent exited with code 1",
                "17:02:11  m1-13  retry 2 in 10s",
                "17:02:11  m1-13  retry 2 started in /elsewhere/m1-13",
                "17:02:11  m1-13  retry 2 failed, the agent was silent for too long",
                "17:02:11  m1-13  parked after 2 failed attempts, until loop resume or a tracker change",
            ),
            lines,
        )
    }

    @Test
    fun `bl-39 each runner hold gets one run level line naming its reason and next probe, and the lift gets one`() {
        val log = humanLog()
        val reason = "turn_failed: Failed to authenticate. API Error: 403"

        log.recorded(
            event(
                "runner_hold_started",
                mapOf(
                    "reason" to reason,
                    "hold" to "1",
                    "delay_ms" to "60000",
                    "probe_at" to "2026-11-11T17:03:11Z",
                ),
            ),
        )
        log.recorded(
            event(
                "runner_hold_started",
                mapOf(
                    "reason" to reason,
                    "hold" to "2",
                    "delay_ms" to "120000",
                    "probe_at" to "2026-11-11T17:05:11Z",
                ),
            ),
        )
        log.recorded(event("runner_hold_lifted", mapOf("holds" to "2", "held_ms" to "180000")))

        assertEquals(
            listOf(
                "17:02:11  runner unavailable, hold 1, no dispatch until the next probe in 1m at 17:03:11 UTC: $reason",
                "17:02:11  runner unavailable, hold 2, no dispatch until the next probe in 2m at 17:05:11 UTC: $reason",
                "17:02:11  runner available again after 3m, dispatch resumes",
            ),
            lines,
        )
    }

    @Test
    fun `bl-175 the pause of the delivery queue and its resumes are run level lines`() {
        val log = humanLog()

        log.recorded(event("delivery_queue_paused", mapOf("issue" to "m1-13", "reason" to "ci_timeout")))
        log.recorded(event("delivery_queue_resumed", mapOf("by" to "approve")))
        log.recorded(event("delivery_queue_resumed", mapOf("by" to "restart"), attempt = null))

        assertEquals(
            listOf(
                "17:02:11  delivery queue paused, the CI of m1-13 timed out, no merge check starts until loop approve or a restart",
                "17:02:11  delivery queue resumed by loop approve",
                "17:02:11  delivery queue resumed by a restart",
            ),
            lines,
        )
    }

    @Test
    fun `bl-182 a failed early conflict check reads as a sentence of its issue`() {
        val log = humanLog()

        val data = mapOf("issue" to "m1-13", "head" to "fedcba9876543210", "base" to "89abcdef0123456789", "reason" to "timed out")
        log.recorded(event("early_check_failed", data, attempt = null))

        assertEquals(
            listOf(
                "17:02:11  m1-13  the early conflict check of fedcba987654 against the base at 89abcdef0123 failed, " +
                    "it is tried again at the next tick: timed out",
            ),
            lines,
        )
    }

    @Test
    fun `bl-178 an early conflict of a queued merge wait reads as a sentence of its issue`() {
        val log = humanLog()

        val data = mapOf("issue" to "m1-13", "base" to "89abcdef0123456789", "paths" to "a.txt,b.txt")
        log.recorded(event("merge_conflict_early", data, attempt = null))

        assertEquals(
            listOf(
                "17:02:11  m1-13  merging the base at 89abcdef0123 conflicts in a.txt,b.txt, found while queued, " +
                    "the issue goes back to its agent before its turn",
            ),
            lines,
        )
    }

    @Test
    fun `a continuation is silent until it starts, a dropped one stays silent, a dropped retry is told`() {
        val log = humanLog()

        log.recorded(event("retry_scheduled", mapOf("kind" to "continuation", "attempt" to "1", "delay_ms" to "1000"), attempt = null))
        log.recorded(event("retry_dropped", mapOf("reason" to "not_active"), attempt = null))
        log.recorded(event("retry_scheduled", mapOf("kind" to "continuation", "attempt" to "1", "delay_ms" to "1000"), attempt = null))
        log.recorded(event("attempt_started", attempt = "a3"))
        log.recorded(event("phase", mapOf("workspace" to "/project/workspaces/m1-13"), phase = "PreparingWorkspace", attempt = "a3"))
        log.recorded(event("retry_scheduled", mapOf("kind" to "error", "attempt" to "2", "delay_ms" to "20000"), attempt = null))
        log.recorded(event("retry_dropped", mapOf("reason" to "terminal"), attempt = null))

        assertEquals(
            listOf(
                "17:02:11  m1-13  continuation 1 started in workspaces/m1-13",
                "17:02:11  m1-13  retry 2 in 20s",
                "17:02:11  m1-13  retry dropped, the issue reached a terminal state",
            ),
            lines,
        )
    }

    @Test
    fun `m2-25 the steps of a pull request delivery read as sentences`() {
        val log = humanLog()
        val url = "https://github.com/acme/widgets/pull/7"

        log.recorded(event("branch_pushed", mapOf("sha" to "0123456789abcdef0123456789abcdef01234567", "branch" to "loop/m1-13")))
        log.recorded(event("pull_request_opened", mapOf("number" to "7", "url" to url)))
        log.recorded(event("issue_commented", mapOf("chars" to "80", "by" to "host", "reason" to "pull_request")))
        log.recorded(event("pull_request_updated", mapOf("number" to "7", "url" to url)))
        log.recorded(event("delivery_failed", mapOf("step" to "push", "reason" to "the push was rejected")))
        log.recorded(event("pull_request_found", mapOf("number" to "7", "url" to url, "state" to "merged")))
        log.recorded(event("verdict", mapOf("verdict" to "refuted", "checks" to "1", "evidence" to "a.log", "uncommitted" to "2")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  pushed 0123456789ab to loop/m1-13",
                "17:02:11  m1-13  opened pull request #7, $url",
                "17:02:11  m1-13  commented the URL of its pull request",
                "17:02:11  m1-13  updated pull request #7, $url",
                "17:02:11  m1-13  could not be delivered, push: the push was rejected. It waits for a new approval with loop approve",
                "17:02:11  m1-13  found pull request #7 already merged, $url",
                "17:02:11  m1-13  verdict refuted (2 files are not committed), evidence of 1 check saved",
            ),
            lines,
        )
    }

    @Test
    fun `bl-181 a failed early push of the merge commit reads as a sentence`() {
        val log = humanLog()
        val head = "0123456789abcdef0123456789abcdef01234567"

        log.recorded(event("branch_push_failed", mapOf("issue" to "m1-13", "head" to head, "early" to "true", "reason" to "rejected")))

        assertEquals(
            listOf("17:02:11  m1-13  could not push the merge commit 0123456789ab before the checks, it is pushed after them: rejected"),
            lines,
        )
    }

    @Test
    fun `m2-26 the merge check and the merge read as sentences`() {
        val log = humanLog()
        val base = "89abcdef0123456789abcdef0123456789abcdef"
        val head = "0123456789abcdef0123456789abcdef01234567"

        log.recorded(event("base_fetched", mapOf("sha" to base, "base" to "main")))
        log.recorded(event("merge_check_started", mapOf("base_sha" to base, "round" to "1")))
        log.recorded(event("verdict", mapOf("verdict" to "verified", "checks" to "1", "evidence" to "a.log", "merge_check" to "true")))
        log.recorded(event("merge_check_finished", mapOf("outcome" to "merged", "base_sha" to base, "round" to "1", "head" to head)))
        log.recorded(event("merge_check_finished", mapOf("outcome" to "up_to_date", "base_sha" to base, "round" to "2", "head" to head)))
        log.recorded(event("merge_check_finished", mapOf("outcome" to "conflict", "base_sha" to base, "round" to "1")))
        log.recorded(event("merge_check_finished", mapOf("outcome" to "refuted", "base_sha" to base, "round" to "1", "head" to head)))
        log.recorded(event("pull_request_merged", mapOf("number" to "7", "sha" to head, "method" to "squash", "merge_sha" to base)))
        log.recorded(event("issue_commented", mapOf("chars" to "80", "by" to "host", "reason" to "merge_check")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  fetched main at 89abcdef0123",
                "17:02:11  m1-13  merge check started against 89abcdef0123, round 1",
                "17:02:11  m1-13  verdict verified of the merge check, evidence of 1 check saved",
                "17:02:11  m1-13  merge check passed, merged the base into 0123456789ab and every check passed again",
                "17:02:11  m1-13  merge check passed, the base 89abcdef0123 is already in the branch",
                "17:02:11  m1-13  merge check failed, merging 89abcdef0123 conflicts, the issue goes back to its agent",
                "17:02:11  m1-13  merge check failed, the checks failed on the merged result, the issue goes back to its agent",
                "17:02:11  m1-13  merged pull request #7 at 0123456789ab with squash",
                "17:02:11  m1-13  commented the outcome of its merge check",
            ),
            lines,
        )
    }

    @Test
    fun `m3-15 the CI wait reads as sentences`() {
        val log = humanLog()
        val head = "0123456789abcdef0123456789abcdef01234567"

        log.recorded(event("ci_waiting", mapOf("sha" to head)))
        for (outcome in listOf("green", "red", "no_run_pass", "no_run_fail", "timeout", "not_started")) {
            log.recorded(event("ci_finished", mapOf("outcome" to outcome, "runs" to "1,2")))
        }
        log.recorded(event("issue_commented", mapOf("chars" to "80", "by" to "host", "reason" to "ci")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  waits for the Actions runs of 0123456789ab before the merge",
                "17:02:11  m1-13  CI passed, every Actions run of the merged head is green",
                "17:02:11  m1-13  CI failed, an Actions run of the merged head did not pass, the pull request stays open",
                "17:02:11  m1-13  CI passed, the merged head has no Actions run and ci_no_run is pass",
                "17:02:11  m1-13  CI failed, the merged head has no Actions run and ci_no_run is fail",
                "17:02:11  m1-13  CI timed out, Actions runs of the merged head are still pending",
                "17:02:11  m1-13  CI not started, GitHub did not start the Actions runs of the merged head, the code was not judged",
                "17:02:11  m1-13  commented why CI did not let it merge",
            ),
            lines,
        )
    }

    @Test
    fun `m2-28 the merge risk assessment and an auto merge fallback read as sentences`() {
        val log = humanLog()

        log.recorded(
            event(
                "merge_risk_assessed",
                mapOf(
                    "alignment" to "aligned",
                    "impact" to "medium",
                    "verification" to "strong",
                    "rollback_cost" to "two_way",
                    "error_detection" to "n/a",
                    "floors" to "impact:medium",
                    "score" to "1.27",
                    "band" to "low",
                    "reviewer" to "claude-sonnet-5-5",
                ),
            ),
        )
        log.recorded(
            event("auto_merge_fallback", mapOf("conditions" to "ci,diff_size", "reason" to "ci: pull request #7 is not mergeable")),
        )
        log.recorded(
            event(
                "auto_merge_fallback",
                mapOf(
                    "band" to "medium",
                    "reason" to "the merge risk band is medium, an auto merge needs low",
                ),
            ),
        )
        log.recorded(event("issue_commented", mapOf("chars" to "80", "by" to "host", "reason" to "auto_merge_fallback")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  merge risk band low, score 1.27: alignment aligned, impact medium, verification strong, " +
                    "rollback_cost two_way, error_detection n/a, floors impact:medium",
                "17:02:11  m1-13  did not merge on its own, ci: pull request #7 is not mergeable. It waits for approval with loop approve",
                "17:02:11  m1-13  did not merge on its own, the merge risk band is medium, an auto merge needs low. " +
                    "It waits for approval with loop approve",
                "17:02:11  m1-13  commented why it did not merge on its own",
            ),
            lines,
        )
    }

    @Test
    fun `bl-137 a merge risk assessment of a cut diff says how many files the cut left out, a whole diff says nothing`() {
        val log = humanLog()
        val data =
            mapOf(
                "alignment" to "aligned",
                "impact" to "low",
                "verification" to "strong",
                "rollback_cost" to "two_way",
                "error_detection" to "loud",
                "floors" to "",
                "score" to "1.00",
                "reviewer" to "claude-sonnet-5-5",
            )

        log.recorded(event("merge_risk_assessed", data + mapOf("band" to "medium", "diff_cut" to "true", "files_left_out" to "2")))
        log.recorded(event("merge_risk_assessed", data + mapOf("band" to "low", "diff_cut" to "false", "files_left_out" to "0")))

        val verdicts = "alignment aligned, impact low, verification strong, rollback_cost two_way, error_detection loud"
        assertEquals(
            listOf(
                "17:02:11  m1-13  merge risk band medium, score 1.00: $verdicts, the reviewer's diff cut with 2 files left out",
                "17:02:11  m1-13  merge risk band low, score 1.00: $verdicts",
            ),
            lines,
        )
    }

    @Test
    fun `bl-32 an unpinned diff base and a diff_policy refusal on a base already in the branch read as sentences`() {
        val log = humanLog()
        val base = "89abcdef0123456789abcdef0123456789abcdef"

        log.recorded(event("diff_base_unpinned", mapOf("reason" to "cannot fetch main: network down")))
        log.recorded(event("merge_check_finished", mapOf("outcome" to "refuted", "base_sha" to base, "round" to "1")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  could not fetch the base, diff_policy uses guards.base: cannot fetch main: network down",
                "17:02:11  m1-13  merge check failed, diff_policy failed against the base 89abcdef0123 already in the branch, " +
                    "the issue goes back to its agent",
            ),
            lines,
        )
    }

    @Test
    fun `a readiness bounce reads as a skip, a comment and a label`() {
        val log = humanLog()

        log.recorded(event("candidate_skipped", mapOf("reason" to "not_ready (missing Acceptance criteria)"), attempt = null))
        log.recorded(event("issue_commented", mapOf("chars" to "120", "reason" to "not_ready"), attempt = null))
        log.recorded(event("issue_labeled", mapOf("add" to "needs-info", "reason" to "not_ready"), attempt = null))

        assertEquals(
            listOf(
                "17:02:11  m1-13  skipped, not ready, missing Acceptance criteria",
                "17:02:11  m1-13  commented that it is not ready",
                "17:02:11  m1-13  labeled needs-info",
            ),
            lines,
        )
    }

    @Test
    fun `skips, hook failures and token thresholds get a line, a successful hook does not`() {
        val log = humanLog()

        log.recorded(event("candidate_skipped", mapOf("reason" to "blocked_by m1-12 (in_progress)"), attempt = null))
        log.recorded(event("hook_finished", mapOf("hook" to "after_create", "exit_code" to "0", "timed_out" to "false")))
        log.recorded(
            event(
                "hook_finished",
                mapOf("hook" to "before_run", "exit_code" to "1", "timed_out" to "false", "output" to "fetching\nfatal: no remote\n"),
            ),
        )
        log.recorded(event("hook_finished", mapOf("hook" to "after_run", "timed_out" to "true", "duration_ms" to "60000")))
        log.recorded(event("token_threshold", mapOf("tokens" to "10200210", "threshold" to "10000000", "cost_usd" to "2.62")))
        log.recorded(event("token_threshold", mapOf("tokens" to "20000001", "threshold" to "20000000", "estimated" to "true")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  skipped, blocked by m1-12 (in_progress)",
                "17:02:11  m1-13  hook before_run failed with exit code 1: fatal: no remote",
                "17:02:11  m1-13  hook after_run timed out after 1m",
                "17:02:11  m1-13  passed 10M tokens, \$2.62 so far",
                "17:02:11  m1-13  passed about 20M tokens",
            ),
            lines,
        )
    }

    @Test
    fun `bl-04 a hook stopped by a cancel says it was stopped, not that it failed`() {
        val log = humanLog()

        log.recorded(
            event(
                "hook_finished",
                mapOf("hook" to "before_run", "timed_out" to "false", "stopped" to "true", "duration_ms" to "830", "output" to "fetching\n"),
            ),
        )

        assertEquals(listOf("17:02:11  m1-13  hook before_run was stopped"), lines)
    }

    @Test
    fun `twenty idle polls give one nothing to do line, work found and idle again give one more each`() {
        val log = humanLog()

        repeat(20) { log.polled(emptyList(), running = 0, maxRunning = 10) }
        log.polled(listOf("m1-13", "m1-14"), running = 0, maxRunning = 10)
        log.polled(listOf("m1-13", "m1-14"), running = 2, maxRunning = 10)
        log.polled(emptyList(), running = 1, maxRunning = 10)
        repeat(20) { log.polled(emptyList(), running = 0, maxRunning = 10) }

        assertEquals(
            listOf(
                "17:02:11  nothing to do, waiting for eligible issues",
                "17:02:11  2 issues eligible (m1-13, m1-14)",
                "17:02:11  nothing to do, waiting for eligible issues",
            ),
            lines,
        )
    }

    @Test
    fun `eligible issues while every slot is busy wait for a free slot, with a free slot the line stays as it was`() {
        val log = humanLog()

        log.polled(listOf("m1-13", "m1-14"), running = 2, maxRunning = 2)
        log.polled(listOf("m1-13", "m1-14"), running = 2, maxRunning = 2)
        log.polled(listOf("m1-14"), running = 3, maxRunning = 2)
        log.polled(listOf("m1-14"), running = 1, maxRunning = 2)
        log.polled(listOf("m1-15"), running = 1, maxRunning = 2)

        assertEquals(
            listOf(
                "17:02:11  2 issues eligible, waiting for a free slot (m1-13, m1-14)",
                "17:02:11  1 issue eligible, waiting for a free slot (m1-14)",
                "17:02:11  1 issue eligible (m1-14)",
                "17:02:11  1 issue eligible (m1-15)",
            ),
            lines,
        )
    }

    /** One `Bash` call with [command] in attempt `a1`, then its result with [isError] (no `is_error` field when null). */
    private fun HumanLog.bash(
        id: String,
        command: String,
        isError: Boolean?,
    ) {
        val input = buildJsonObject { put("command", JsonPrimitive(command)) }
        val call = """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"Bash","input":$input}]}}"""
        val error = isError?.let { ""","is_error":$it""" }.orEmpty()
        val result = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id"$error}]}}"""
        runnerLine(event("runner_output"), call)
        runnerLine(event("runner_output"), result)
    }

    private fun attemptIn(
        log: HumanLog,
        workspace: String,
    ) {
        log.recorded(event("attempt_started"))
        log.recorded(event("phase", mapOf("workspace" to workspace), phase = "PreparingWorkspace"))
        lines.clear()
    }

    @Test
    fun `a command result states the exit, never that the command passed`() {
        val log = humanLog()
        attemptIn(log, "/work")

        log.bash("t1", "./gradlew test 2>&1 | tee evidence.txt", isError = false)
        log.bash("t2", "./gradlew build", isError = true)
        log.bash("t3", "ls", isError = null)

        assertEquals(
            listOf(
                "17:02:11  m1-13  ran ./gradlew test 2>&1 | tee evidence.txt (exit 0)",
                "17:02:11  m1-13  ran ./gradlew build (error)",
                "17:02:11  m1-13  ran ls",
            ),
            lines,
        )
        assertTrue(lines.none { "passed" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `a command of several lines shows its first line and how many lines follow`() {
        val log = humanLog()
        attemptIn(log, "/work")

        log.bash("t1", "python3 - <<'EOF'\nimport json\nprint(json.dumps({}))\nEOF\n", isError = false)
        log.bash("t2", "git add -A &&\n  git commit -m 'm1-40: x'", isError = false)

        assertEquals(
            listOf(
                "17:02:11  m1-13  ran python3 - <<'EOF' (+3 lines) (exit 0)",
                "17:02:11  m1-13  ran git add -A && (+1 line) (exit 0)",
            ),
            lines,
        )
    }

    @Test
    fun `a leading cd to the workspace is dropped and the workspace shows as a dot before the cut`() {
        val workspace = "/Users/me/PROJECTS/Loop/workspaces/m1-40"
        val log = humanLog()
        attemptIn(log, workspace)
        val long = "x".repeat(110)

        log.bash("t1", "cd $workspace && ./gradlew :cli:test 2>&1 | tee $workspace/.scratch/m1-40/evidence/cli-test.txt", isError = false)
        log.bash("t2", "cd \"$workspace\" && git status", isError = false)
        log.bash("t3", "cd $workspace/cli && ls $workspace", isError = false)
        log.bash("t4", "cd /elsewhere && ls $workspace-old $workspace", isError = false)
        log.bash("t5", "cd $workspace && echo $long $workspace/end", isError = false)

        assertEquals(
            listOf(
                "17:02:11  m1-13  ran ./gradlew :cli:test 2>&1 | tee ./.scratch/m1-40/evidence/cli-test.txt (exit 0)",
                "17:02:11  m1-13  ran git status (exit 0)",
                "17:02:11  m1-13  ran cd ./cli && ls . (exit 0)",
                "17:02:11  m1-13  ran cd /elsewhere && ls $workspace-old . (exit 0)",
                "17:02:11  m1-13  ran ${"echo $long ./end".take(120)}... (exit 0)",
            ),
            lines,
        )
    }

    @Test
    fun `stream lines with tool calls give read, edit and command lines, reads in a row grouped`() {
        val log = humanLog()
        log.recorded(event("attempt_started"))
        log.recorded(event("phase", mapOf("workspace" to "/work"), phase = "PreparingWorkspace"))
        lines.clear()

        log.stream("activity")
        log.recorded(event("turn_completed", mapOf("turn" to "1", "max_turns" to "10")))

        assertEquals(
            listOf(
                "17:02:11  m1-13  read CONTEXT.md, docs/spec-decisions.md and 2 other files",
                "17:02:11  m1-13  edited cli/src/main/kotlin/loop/cli/ShowCommand.kt",
                "17:02:11  m1-13  wrote cli/src/test/kotlin/loop/cli/ShowCommandTest.kt",
                "17:02:11  m1-13  ran ./gradlew build (exit 0)",
                "17:02:11  m1-13  ran ./gradlew :cli:test (+1 line) (error)",
                "17:02:11  m1-13  read /elsewhere/README.md",
                "17:02:11  m1-13  ran loop tool move review",
                "17:02:11  m1-13  turn 1 finished",
            ),
            lines,
        )
    }

    @Test
    fun `a learned secret in a command never reaches the human log`() {
        val secret = "tok-5f2b9c1e-human-log"
        val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_TOKEN" to secret)) }
        val log = humanLog(redactor)
        log.recorded(event("attempt_started"))
        val call =
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Bash",""" +
                """"input":{"command":"curl -H 'Authorization: Bearer $secret' https://example.invalid"}}]}}"""
        val result = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":false}]}}"""

        log.runnerLine(event("runner_output"), call)
        log.runnerLine(event("runner_output"), result)

        assertTrue(lines.none { secret in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "ran curl -H 'Authorization: Bearer [redacted:LOOP_TEST_TOKEN]'" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `a line that is not stream json and a failing sink never throw`() {
        val log = HumanLog(out = { error("disk full") }, clock = clock, redactor = Redactor.NONE, zone = ZoneOffset.UTC)

        log.runnerLine(event("runner_output"), "plain output of a command runner")
        log.recorded(event("attempt_started"))
        log.runStarted(Path.of("/project/WORKFLOW.md"))
    }

    @Test
    fun `run end says who stopped it and how many attempts were canceled`() {
        val log = humanLog()

        log.runEnded(reason = "stop_requested", attemptsCanceled = 1)
        log.runEnded(reason = "signal", attemptsCanceled = 2)
        log.runEnded(reason = "internal_error", attemptsCanceled = 0)
        log.runEnded(reason = "drained", attemptsCanceled = 0)

        assertEquals(
            listOf(
                "17:02:11  run stopped by loop stop, 1 attempt canceled",
                "17:02:11  run stopped by a signal, 2 attempts canceled",
                "17:02:11  run stopped by an internal error",
                "17:02:11  run ended after loop drain",
            ),
            lines,
        )
    }

    @Test
    fun `sizes and money read like 7_2M and 3_38 dollars`() {
        assertEquals("567", HumanLog.size(567))
        assertEquals("1.2K", HumanLog.size(1234))
        assertEquals("7.2M", HumanLog.size(7_213_000))
        assertEquals("10M", HumanLog.size(10_000_000))
        assertEquals("1.5B", HumanLog.size(1_500_000_000))
        assertEquals("\$3.38", HumanLog.money(3.3812))
        assertEquals("\$0.01", HumanLog.money(0.01))
    }

    @Test
    fun `m4-47 an issue line names the issue by the display rule given`() {
        val several = IssueIdDisplay("gh", listOf("gh", "local"), namespaced = true)
        val log =
            HumanLog(
                out = { lines += it },
                clock = clock,
                redactor = Redactor.NONE,
                zone = ZoneOffset.UTC,
                base = Path.of("/project"),
                names = several::name,
            )

        log.recorded(event("attempt_started", mapOf("loop_version" to "abc")).copy(issueId = "local:m1-13"))
        log.recorded(event("issue_moved", mapOf("from" to "todo", "to" to "review", "by" to "host")).copy(issueId = "local:m1-13"))

        assertTrue(lines.isNotEmpty(), lines.toString())
        assertTrue(lines.all { it.startsWith("17:02:11  local:m1-13  ") }, lines.toString())
    }
}
