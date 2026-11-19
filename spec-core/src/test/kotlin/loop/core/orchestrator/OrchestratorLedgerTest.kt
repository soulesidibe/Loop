package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.HumanTouch
import loop.api.LedgerAttempt
import loop.api.LedgerPeak
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.LedgerTurn
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.api.TurnLedger
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.RunnerSettings
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workflow.workflowHash
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m2-19: a ledger line per turn and a row per attempt, and the human touches the attempts count. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorLedgerTest {
    private val root: Path = createTempDirectory("loop-ledger").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val lines = mutableListOf<LedgerTurn>()
    private val turnLedger =
        TurnLedger {
            lines += it
            Result.success(Unit)
        }
    private val rows = FakeAttemptLedger()
    private val logLines = mutableListOf<String>()

    private val template = "Do {{ issue.identifier }}"

    private fun workflow(
        maxTurns: Int = 1,
        runner: RunnerSettings = commandRunner("./agent.sh"),
        checks: List<CheckConfig>? = null,
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = runner,
            pollingIntervalMs = 1000,
            promptTemplate = template,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns, maxRetryBackoffMs = 1000),
                    kinds = checks?.let { KindsConfig(KindConfig(it, delivery = DeliveryConfig(DeliveryPolicy.Auto))) },
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
        checks: CheckRunner = CheckRunner.NONE,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
            fingerprinter = Fingerprinter { Result.success("git:abc") },
            turnLedger = turnLedger,
            attemptLedger = rows,
        )
    }

    /** One turn of a session that reports each of [usages] in order, absolute for the session, then completes. */
    private fun usageTurn(
        turn: Int,
        vararg usages: Pair<Long, Double?>,
        wallMs: Long = 0,
    ) = flow {
        val turnId = TurnId("tu$turn")
        val sessionId = SessionId("th1-tu$turn")
        if (turn == 1) {
            emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
        } else {
            emit(RunnerEvent.TurnStarted(sessionId, threadId = "th1", turnId = turnId, turn = turn))
        }
        delay(wallMs)
        usages.forEach { (input, cost) ->
            emit(RunnerEvent.Usage(sessionId, turnId, input, input / 10, input * 2, input / 2, totalCostUsd = cost))
        }
        emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
    }

    private fun tokens(input: Long) = LedgerTokens(input, input / 10, input * 2, input / 2)

    private fun touches(): List<RecordedEvent> = sink.events.filter { it.kind == HumanTouch.EVENT }

    @Test
    fun `each completed turn appends one line with its share of the absolute totals, never counted twice`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                when (turn) {
                    // Two absolute reports in one turn: the later replaces the earlier.
                    1 -> usageTurn(1, 100L to 0.25, 150L to 0.5)
                    2 -> usageTurn(2, 400L to 1.0, wallMs = 1_500)
                    else -> usageTurn(turn)
                }
            }

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner, workflow(maxTurns = 3)).tick()

            assertEquals(listOf(1, 2, 3), lines.map { it.turn })
            assertEquals(listOf(tokens(150), tokens(250), null), lines.map { it.tokens })
            assertEquals(listOf(tokens(150), tokens(400), tokens(400)), lines.map { it.attemptTokens })
            assertEquals(listOf(0.5, 0.5, null), lines.map { it.costUsd })
            assertEquals(listOf(0.5, 1.0, 1.0), lines.map { it.attemptCostUsd })
            assertEquals(listOf(0L, 1_500L, 0L), lines.map { it.wallMs })
            assertEquals(listOf("completed", "completed", "completed"), lines.map { it.outcome })
            assertEquals(listOf("tu1", "tu2", "tu3"), lines.map { it.turnId?.value })
            assertTrue(lines.all { it.attemptId == AttemptId("a1") && it.identifier == "m2-19" && it.runId == RunId("r1") })
            assertEquals(
                LedgerSetup("files", null, "command", null, workflowHash(template), null),
                lines.first().setup,
            )
            // The turns add up to the attempt row, so no token is counted twice.
            val row = rows.recorded.single()
            assertEquals(
                row.tokens?.total,
                lines.mapNotNull { it.tokens?.total }.sum(),
            )
            assertEquals(tokens(400), row.tokens)
            assertEquals(1.0, row.costUsd)
        }

    @Test
    fun `a cost below the cost at the turn start warns once for the attempt and the turn's share stays 0 (bl-37)`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                when (turn) {
                    1 -> usageTurn(1, 100L to 1.0)
                    // Two reports below the 1.0 the turn started with, then a third turn below again.
                    2 -> usageTurn(2, 200L to 0.4, 250L to 0.3)
                    else -> usageTurn(turn, 300L to 0.2)
                }
            }

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner, workflow(maxTurns = 3)).tick()

            assertEquals(listOf(1.0, 0.0, 0.0), lines.map { it.costUsd })
            val warnings = logLines.filter { "event=ledger" in it }
            assertEquals(1, warnings.size, "$logLines")
            val warning = warnings.single()
            assertTrue("level=warn" in warning, warning)
            assertTrue("attempt_id=a1" in warning, warning)
            assertTrue("decreasing_cost" in warning, warning)
        }

    @Test
    fun `a cost that never goes down logs no ledger warning (bl-37)`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> usageTurn(turn, turn * 100L to turn * 0.5) }

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner, workflow(maxTurns = 2)).tick()

            assertEquals(listOf(0.5, 0.5), lines.map { it.costUsd })
            assertTrue(logLines.none { "event=ledger" in it }, "$logLines")
        }

    @Test
    fun `a failed turn appends its line too`() =
        runTest {
            val runner = FakeRunner { _, _ -> failingTurn() }

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner).tick()

            assertEquals(listOf("failed"), lines.map { it.outcome })
            assertEquals(1, rows.recorded.size)
        }

    @Test
    fun `a finished attempt has one row, and what the runner cannot report is null, never zero`() =
        runTest {
            // The default script reports no usage and the command runner has no model.
            orchestrator(FakeTracker(listOf(issue("m2-19"))), FakeRunner()).tick()

            val row = rows.recorded.single()
            assertEquals(
                LedgerAttempt(
                    projectId = PROJECT,
                    runId = RunId("r1"),
                    issueId = "id-m2-19",
                    identifier = "m2-19",
                    attemptId = AttemptId("a1"),
                    setup = LedgerSetup("files", null, "command", null, workflowHash(template), null),
                    startedAt = FIXED_NOW,
                    finishedAt = FIXED_NOW,
                    outcome = "succeeded",
                    reason = null,
                    turns = 1,
                    issueState = "todo",
                    tokens = null,
                    costUsd = null,
                    checks = null,
                    guards = null,
                    verdict = null,
                ),
                row,
            )
            assertNull(lines.single().tokens)
            assertNull(lines.single().attemptTokens)
            assertNull(lines.single().costUsd)
        }

    @Test
    fun `m3-21 a session that never reports a cost has its tokens in the ledger and its cost empty, not zero`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                when (turn) {
                    1 -> usageTurn(1, 100L to null, 150L to null)
                    else -> usageTurn(turn, 400L to null)
                }
            }

            orchestrator(FakeTracker(listOf(issue("m3-21"))), runner, workflow(maxTurns = 2)).tick()

            assertEquals(listOf(tokens(150), tokens(250)), lines.map { it.tokens })
            assertEquals(listOf(null, null), lines.map { it.costUsd })
            assertEquals(listOf(null, null), lines.map { it.attemptCostUsd })
            val row = rows.recorded.single()
            assertEquals(tokens(400), row.tokens)
            assertNull(row.costUsd)
            assertTrue(sink.events.filter { it.kind == "usage" }.none { "total_cost_usd" in it.data }, "${sink.events}")
        }

    @Test
    fun `the row names the model, the kind, the checks and the verdict when there are some`() =
        runTest {
            // m3-04: the model is the runner's, its settings belong to its plugin.
            val runner = FakeRunner().apply { model = "claude-opus-5-5" }
            var config: Path? = null
            runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
            runner.sessionScript = { turn, _ ->
                ToolRequestQueue(checkNotNull(config).parent, nowMillis = { 1_000L }).enqueue(ToolRequest.Move("review"))
                usageTurn(turn, 1_000L to 2.0)
            }
            val checks = CheckRunner { _, _, _ -> CheckResult(exitCode = 0, timedOut = false, durationMs = 10) }
            val workflow =
                workflow(
                    runner = RunnerSettings("claude-code"),
                    checks = listOf(CheckConfig.Command("./gradlew test"), CheckConfig.Command("./gradlew lint")),
                )

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner, workflow, checks).tick()

            val row = rows.recorded.single()
            assertEquals(LedgerSetup("files", "code", "claude-code", "claude-opus-5-5", workflowHash(template), null), row.setup)
            assertEquals(2, row.checks)
            assertEquals("verified", row.verdict)
            assertEquals("review", row.issueState)
            assertNull(row.guards)
            assertEquals(tokens(1_000), row.tokens)
        }

    @Test
    fun `a human move that stops a running attempt is a human touch of that attempt`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-19")))
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, "th1", TURN))
                        awaitCancellation()
                    }
                }
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()

            tracker.issues[0] = tracker.issues[0].copy(state = "review")
            advanceTimeBy(1_000)
            runCurrent()

            val touch = touches().single()
            assertEquals(mapOf(HumanTouch.TOUCH to HumanTouch.MOVE, "to_state" to "review"), touch.data)
            assertEquals(AttemptId("a1"), touch.attemptId)
            val row = rows.recorded.single()
            assertEquals("canceled", row.outcome)
            assertEquals("not_active", row.reason)
            job.cancelAndJoin()
        }

    @Test
    fun `a dispatch after an attempt that left the issue outside the active states is a human touch`() =
        runTest {
            rows.previous += row(issueState = "review")

            orchestrator(FakeTracker(listOf(issue("m2-19"))), FakeRunner()).tick()

            val touch = touches().single()
            assertEquals(mapOf(HumanTouch.TOUCH to HumanTouch.MOVE, "from_state" to "review", "to_state" to "todo"), touch.data)
            assertEquals(AttemptId("a1"), touch.attemptId)
        }

    @Test
    fun `a dispatch after an attempt that left the issue active, or after none, is not a human touch`() =
        runTest {
            orchestrator(FakeTracker(listOf(issue("m2-19"))), FakeRunner()).tick()
            for (state in listOf("In_Progress", null)) {
                rows.recorded.clear()
                rows.previous.clear()
                rows.previous += row(issueState = state)
                orchestrator(FakeTracker(listOf(issue("m2-19"))), FakeRunner()).tick()
            }

            assertEquals(emptyList(), touches())
            assertEquals(3, sink.events.count { it.kind == "attempt_finished" })
        }

    @Test
    fun `a tracker change that unparks an issue is a human touch`() =
        runTest {
            parked.records["p1" to "id-m2-19"] =
                ParkedIssue(PROJECT, "id-m2-19", "m2-19", ParkReason.MaxAttempts, "todo", setOf("ready-for-agent"), FIXED_NOW)

            orchestrator(FakeTracker(listOf(issue("m2-19", state = "in_progress"))), FakeRunner()).tick()

            val touch = touches().single()
            assertEquals(mapOf(HumanTouch.TOUCH to HumanTouch.MOVE, "from_state" to "todo", "to_state" to "in_progress"), touch.data)
            assertNull(touch.attemptId)
        }

    @Test
    fun `a human change that wins over a verified move is a human touch`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-19")))
            // m3-04: the model is the runner's, its settings belong to its plugin.
            val runner = FakeRunner().apply { model = "claude-opus-5-5" }
            var config: Path? = null
            runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
            runner.sessionScript = { turn, _ ->
                ToolRequestQueue(checkNotNull(config).parent, nowMillis = { 1_000L }).enqueue(ToolRequest.Move("review"))
                sessionTurn(turn)
            }
            val checks =
                CheckRunner { _, _, _ ->
                    tracker.issues[0] = tracker.issues[0].copy(state = "needs_info")
                    CheckResult(exitCode = 0, timedOut = false, durationMs = 10)
                }

            orchestrator(tracker, runner, workflow(checks = listOf(CheckConfig.Command("./gradlew test"))), checks).tick()

            assertEquals(emptyList(), tracker.moves)
            assertEquals(listOf(mapOf(HumanTouch.TOUCH to HumanTouch.MOVE, "to_state" to "needs_info")), touches().map { it.data })
        }

    @Test
    fun `an attempt whose own move ended it records no human touch`() =
        runTest {
            // m3-04: the model is the runner's, its settings belong to its plugin.
            val runner = FakeRunner().apply { model = "claude-opus-5-5" }
            var config: Path? = null
            runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
            runner.sessionScript = { turn, _ ->
                ToolRequestQueue(checkNotNull(config).parent, nowMillis = { 1_000L }).enqueue(ToolRequest.Move("review"))
                sessionTurn(turn)
            }

            orchestrator(FakeTracker(listOf(issue("m2-19"))), runner, workflow(maxTurns = 3)).tick()

            assertEquals(emptyList(), touches())
            assertEquals("review", rows.recorded.single().issueState)
        }

    private fun row(issueState: String?): LedgerRow =
        LedgerRow(
            LedgerAttempt(
                projectId = PROJECT,
                runId = RunId("r0"),
                issueId = "id-m2-19",
                identifier = "m2-19",
                attemptId = AttemptId("a0"),
                setup = LedgerSetup("files", null, "command", null, "t", null),
                startedAt = Instant.parse("2026-11-01T00:00:00Z"),
                finishedAt = Instant.parse("2026-11-01T00:01:00Z"),
                outcome = "succeeded",
                reason = null,
                turns = 1,
                issueState = issueState,
                tokens = null,
                costUsd = null,
                checks = null,
                guards = null,
                verdict = null,
            ),
            number = 1,
            humanTouches = 0,
        )

    /**
     * One turn on [thread] that reports each of [occupancy] in order, then completes. A turn on a new thread opens it
     * with `SessionStarted`, as a rotation (m3-11) will.
     */
    private fun occupancyTurn(
        turn: Int,
        thread: String,
        newSession: Boolean,
        vararg occupancy: Long,
    ) = flow {
        val turnId = TurnId("tu$turn")
        val sessionId = SessionId("$thread-tu$turn")
        if (newSession) {
            emit(RunnerEvent.SessionStarted(sessionId, threadId = thread, turnId = turnId))
        } else {
            emit(RunnerEvent.TurnStarted(sessionId, threadId = thread, turnId = turnId, turn = turn))
        }
        occupancy.forEach { emit(RunnerEvent.ContextUsage(sessionId, turnId, it, OPUS)) }
        emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
    }

    @Test
    fun `m3-09 each turn line carries its session's peak so far, the window and the fraction, a new session starting over`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                when (turn) {
                    1 -> occupancyTurn(1, "th1", newSession = true, 300_000L, 120_000L)
                    2 -> occupancyTurn(2, "th1", newSession = false, 200_000L)
                    else -> occupancyTurn(turn, "th2", newSession = true, 50_000L)
                }
            }

            orchestrator(FakeTracker(listOf(issue("m3-09"))), runner, workflow(maxTurns = 3)).tick()

            assertEquals(listOf("th1", "th1", "th2"), lines.map { it.threadId })
            assertEquals(
                listOf(LedgerPeak(300_000, 1_000_000), LedgerPeak(300_000, 1_000_000), LedgerPeak(50_000, 1_000_000)),
                lines.map { it.peak },
            )
            assertEquals(listOf(0.3, 0.3, 0.05), lines.map { it.peak?.fraction })
        }

    @Test
    fun `m3-09 a peak of a model with no window has its tokens and no window nor fraction`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { _, _ ->
                flow {
                    val sessionId = SessionId("th1-tu1")
                    val turnId = TurnId("tu1")
                    emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
                    emit(RunnerEvent.ContextUsage(sessionId, turnId, 40_000L, "acme/unknown"))
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }

            orchestrator(FakeTracker(listOf(issue("m3-09"))), runner).tick()

            val peak = lines.single().peak
            assertEquals(LedgerPeak(40_000, null), peak)
            assertNull(peak?.fraction)
        }

    @Test
    fun `m3-09 a session of a runner without occupancy has no peak, never a zero`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> usageTurn(turn, 100L to null) }

            orchestrator(FakeTracker(listOf(issue("m3-09"))), runner, workflow(maxTurns = 2)).tick()

            assertEquals(listOf("th1", "th1"), lines.map { it.threadId })
            assertEquals(listOf(null, null), lines.map { it.peak })
        }

    /** Keeps every row written; [previous] are rows a past process wrote, read before the new ones. */
    private class FakeAttemptLedger : AttemptLedger {
        val previous = mutableListOf<LedgerRow>()
        val recorded = mutableListOf<LedgerAttempt>()

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            recorded += attempt
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> =
            Result.success(
                previous.filter { it.attempt.issueId == issueId } +
                    recorded.filter { it.issueId == issueId }.map { LedgerRow(it, 0, 0) },
            )

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> = Result.success(emptyList())
    }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val OPUS = "anthropic/claude-opus-5-5"
    }
}
