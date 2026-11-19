package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ParkReason
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.BudgetsConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The spend budgets (m2-20): per attempt, per issue and per rolling day, in tokens and dollars, read from the
 * ledger. At 80 percent an attempt warns once per budget; past 100 percent it stops and the issue parks with
 * `budget` and the budget's name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorBudgetTest {
    private val root: Path = createTempDirectory("loop-orchestrator-budget").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val hookRunner = FakeHookRunner()
    private val project = ProjectId("p1")
    private val logLines = mutableListOf<String>()

    /** The ledger rows in the store: they outlive every orchestrator, like the machine level store. */
    private val ledger = StoredLedger()

    private fun workflow(
        budgets: BudgetsConfig,
        maxTurns: Int = 1,
        hooks: HooksConfig = HooksConfig(),
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Do {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns, maxRetryBackoffMs = 1000),
                    retries = RetriesConfig(maxAttempts = 5),
                    budgets = budgets,
                    hooks = hooks,
                ),
        )

    /** A new orchestrator, as after a restart: it shares only the store ([ledger], [parked], [sink]) with earlier ones. */
    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile,
        idPrefix: String = "a",
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
            newId = { "$idPrefix${++next}" },
            runId = RunId("r-$idPrefix"),
            projectId = project,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            attemptLedger = ledger,
        )
    }

    /** A session whose turn `n` reports `spend[n - 1]` (absolute for the session) and completes, or fails with [exitCode]. */
    private fun spendRunner(
        vararg spend: Spend,
        exitCode: Int = 0,
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.sessionScript = { turn, _ -> spendTurn(turn, spend[(turn - 1).coerceAtMost(spend.lastIndex)], exitCode) }
        }

    private fun spendTurn(
        turn: Int,
        spend: Spend,
        exitCode: Int = 0,
        pause: Duration = Duration.ZERO,
    ): Flow<RunnerEvent> =
        flow {
            val turnId = TurnId("tu$turn")
            val sessionId = SessionId("th1-tu$turn")
            if (turn == 1) {
                emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
            } else {
                emit(RunnerEvent.TurnStarted(sessionId, threadId = "th1", turnId = turnId, turn = turn))
            }
            emit(RunnerEvent.Usage(sessionId, turnId, spend.tokens, 0, 0, 0, totalCostUsd = spend.usd))
            if (pause > Duration.ZERO) delay(pause)
            if (exitCode == 0) {
                emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
            } else {
                emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = exitCode, reason = "exit_code=$exitCode"))
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `crossing 80 percent of a dollar budget warns once, logs it and runs the warning hook, and the attempt goes on`() =
        runTest {
            val runner = spendRunner(Spend(100, 5.0), Spend(200, 8.5), Spend(300, 9.25))
            val workflow = workflow(BudgetsConfig(perAttemptUsd = 10.0), maxTurns = 3, hooks = HooksConfig(onBudgetWarning = "notify"))
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow)

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            val warning = sink.ofKind("budget_warning").single()
            assertEquals(mapOf("budget" to "per_attempt_usd", "used" to "8.5", "limit" to "10"), warning.data)
            assertEquals("a1", warning.attemptId?.value)
            assertEquals("id-m2-20", warning.issueId)
            val logged = logLines.single { "event=budget_warning" in it }
            assertTrue("level=warn" in logged && "budget=per_attempt_usd used=8.5 limit=10" in logged, logged)
            val hook = hookRunner.calls.single { it.hook == HookName.ON_BUDGET_WARNING }
            assertEquals("notify", hook.script)
            assertEquals("m2-20", hook.env["LOOP_ISSUE"])
            assertEquals("a1", hook.env["LOOP_ATTEMPT"])
            assertEquals("per_attempt_usd", hook.env["LOOP_BUDGET"])
            assertEquals("8.5", hook.env["LOOP_BUDGET_USED"])
            assertEquals("10", hook.env["LOOP_BUDGET_LIMIT"])
            assertTrue(sink.ofKind("hook_finished").any { it.data["hook"] == "on_budget_warning" })
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            assertTrue(sink.ofKind("issue_parked").isEmpty())
        }

    @Test
    fun `every attempt warns once per budget, and two budgets warn apart`() =
        runTest {
            ledger.add(row("old", "id-m2-20", Spend(800, 8.0)))
            val runner = spendRunner(Spend(10, 0.25), Spend(20, 0.5), exitCode = 1)
            val workflow = workflow(BudgetsConfig(perIssueUsd = 10.0, perIssueTokens = 1_000), maxTurns = 2)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            val warnings = sink.ofKind("budget_warning")
            assertEquals(
                listOf("a1" to "per_issue_tokens", "a1" to "per_issue_usd", "a2" to "per_issue_tokens", "a2" to "per_issue_usd"),
                warnings.map { it.attemptId?.value to it.data["budget"] },
            )
            // The second attempt counts the first one's row: 800 + 10 before its own report.
            assertEquals(listOf("800", "8", "810", "8.25"), warnings.map { it.data["used"] })
        }

    @Test
    fun `past 100 percent of a dollar budget the attempt stops and the issue parks with budget, never as a failure`() =
        runTest {
            val runner = spendRunner(Spend(100, 4.0), Spend(200, 10.5), Spend(300, 12.0))
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow(BudgetsConfig(perAttemptUsd = 10.0), maxTurns = 5))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals("budget", summary.reason)
            val event = sink.ofKind("issue_parked").single()
            assertEquals(mapOf("reason" to "budget", "budget" to "per_attempt_usd", "used" to "10.5", "limit" to "10"), event.data)
            assertEquals(
                mapOf("budget" to "per_attempt_usd", "used" to "10.5", "limit" to "10"),
                sink.ofKind("budget_reached").single().data,
            )
            assertEquals(
                "budget",
                parked.records.values
                    .single()
                    .reason.wire,
            )
            assertTrue(sink.ofKind("retry_scheduled").isEmpty())
            assertEquals(
                "budget",
                ledger.rows
                    .single()
                    .attempt.reason,
            )
        }

    @Test
    fun `the issue budget counts the earlier attempts of the issue from the ledger, not those of other issues`() =
        runTest {
            ledger.add(row("old-1", "id-m2-20", Spend(0, 12.0)))
            ledger.add(row("old-2", "id-m2-20", Spend(0, 8.0)))
            ledger.add(row("other", "id-other", Spend(0, 100.0)))
            val runner = spendRunner(Spend(0, 6.0))
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow(BudgetsConfig(perIssueUsd = 25.0)))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(ParkReason.Budget, summary.parkReason)
            val event = sink.ofKind("issue_parked").single()
            assertEquals(mapOf("reason" to "budget", "budget" to "per_issue_usd", "used" to "26", "limit" to "25"), event.data)
        }

    @Test
    fun `an issue already past its budget parks before its first turn`() =
        runTest {
            ledger.add(row("old", "id-m2-20", Spend(3_000, 1.0)))
            val runner = spendRunner(Spend(10, 0.1))
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow(BudgetsConfig(perIssueTokens = 2_000)))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(0, runner.sessionsOpened)
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals(
                mapOf("budget" to "per_issue_tokens", "used" to "3000", "limit" to "2000"),
                sink.ofKind("budget_reached").single().data,
            )
            assertEquals("per_issue_tokens", sink.ofKind("issue_parked").single().data["budget"])
        }

    @Test
    fun `the day budget counts every attempt of the project in the last 24 hours, across restarts`() =
        runTest {
            val start = SchedulerClock(testScheduler).now()
            ledger.add(row("yesterday", "id-x", Spend(5_000, 0.0), finishedAt = start - 25.hours))
            ledger.add(row("today", "id-y", Spend(600, 0.0), finishedAt = start - 23.hours))
            val workflow = workflow(BudgetsConfig(perDayTokens = 1_000))

            // First process: 600 from the store and 300 of its own, 90 percent: a warning, no park.
            val first = orchestrator(FakeTracker(listOf(issue("i-1"))), spendRunner(Spend(300, 0.0)), workflow, idPrefix = "a")
            assertNull(
                first
                    .tick()
                    .attempts
                    .single()
                    .parkReason,
            )
            assertEquals(listOf("900"), sink.ofKind("budget_warning").map { it.data["used"] })

            // Restart: a new process reads the first one's row from the store. 600 + 300 + 200 is over 1,000.
            val second = orchestrator(FakeTracker(listOf(issue("i-2"))), spendRunner(Spend(200, 0.0)), workflow, idPrefix = "b")
            assertEquals(
                ParkReason.Budget,
                second
                    .tick()
                    .attempts
                    .single()
                    .parkReason,
            )
            assertEquals(
                mapOf("reason" to "budget", "budget" to "per_day_tokens", "used" to "1100", "limit" to "1000"),
                sink.ofKind("issue_parked").single().data,
            )

            // Two hours later the 600 of 23 hours ago left the window: 300 + 200 + 200 stays under 80 percent.
            advanceTimeBy(2.hours)
            val third = orchestrator(FakeTracker(listOf(issue("i-3"))), spendRunner(Spend(200, 0.0)), workflow, idPrefix = "c")
            assertNull(
                third
                    .tick()
                    .attempts
                    .single()
                    .parkReason,
            )
            assertEquals(1, sink.ofKind("issue_parked").size)
            assertTrue(sink.ofKind("budget_warning").none { it.attemptId?.value == "c1" })
        }

    @Test
    fun `the day budget counts the attempts still running beside it`() =
        runTest {
            val runner = FakeRunner()
            // The prompt names the issue: i-1 runs ten minutes, i-2 one, both report 600 tokens at once.
            runner.sessionScript = { turn, input ->
                spendTurn(turn, Spend(600, 0.0), pause = if ("i-1" in input) 10.minutes else 1.minutes)
            }
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("i-1"), issue("i-2"))), runner, workflow(BudgetsConfig(perDayTokens = 1_000)))

            val summaries = orchestrator.tick().attempts

            val parks = summaries.filter { it.parkReason == ParkReason.Budget }
            assertEquals(1, parks.size, summaries.toString())
            assertEquals("1200", sink.ofKind("budget_reached").single().data["used"])
        }

    @Test
    fun `a ledger read that fails keeps the spend read last and logs a warning, it never fails the attempt`() =
        runTest {
            ledger.add(row("old", "id-m2-20", Spend(0, 8.0)))
            val runner = FakeRunner()
            // The read before the first turn sees the $8 row; the read on the usage report fails.
            runner.sessionScript = { turn, _ ->
                flow {
                    ledger.readFailure = IllegalStateException("database is locked")
                    emitAll(spendTurn(turn, Spend(0, 2.5)))
                }
            }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-20"))), runner, workflow(BudgetsConfig(perIssueUsd = 10.0)))

            val summary = orchestrator.tick().attempts.single()

            // The $8 read last plus the attempt's $2.50 passes the $10.
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals(
                mapOf("budget" to "per_issue_usd", "used" to "10.5", "limit" to "10"),
                sink.ofKind("budget_reached").single().data,
            )
            val failed = logLines.single { "event=budget outcome=failed" in it }
            assertTrue(
                failed.endsWith(
                    " level=warn event=budget outcome=failed run_id=r-a project_id=p1 issue_id=id-m2-20 issue_identifier=m2-20 " +
                        "attempt_id=a1 session_id=th1-tu1 turn_id=tu1 reason=\"database is locked\"",
                ),
                failed,
            )
        }

    private val setup = LedgerSetup("files", null, "command", null, "t".repeat(64), null)

    private fun row(
        id: String,
        issueId: String,
        spend: Spend,
        finishedAt: Instant = FIXED_NOW - 1.hours,
    ): LedgerAttempt =
        LedgerAttempt(
            projectId = project,
            runId = RunId("r0"),
            issueId = issueId,
            identifier = issueId.removePrefix("id-"),
            attemptId = AttemptId(id),
            setup = setup,
            startedAt = finishedAt - 10.minutes,
            finishedAt = finishedAt,
            outcome = "succeeded",
            reason = null,
            turns = 1,
            issueState = "review",
            tokens = LedgerTokens(spend.tokens, 0, 0, 0),
            costUsd = spend.usd,
            checks = null,
            guards = null,
            verdict = null,
        )

    /** Ledger rows kept for the whole test, like the store keeps them across restarts. */
    private class StoredLedger : AttemptLedger {
        val rows = mutableListOf<LedgerRow>()

        /** When set, every read of the rows fails with it; writes still succeed. */
        var readFailure: Throwable? = null

        fun add(attempt: LedgerAttempt) {
            rows += LedgerRow(attempt, rows.count { it.attempt.issueId == attempt.issueId } + 1, 0)
        }

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            add(attempt)
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> =
            readFailure?.let { Result.failure(it) }
                ?: Result.success(rows.filter { it.attempt.projectId == projectId && it.attempt.issueId == issueId })

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> =
            readFailure?.let { Result.failure(it) }
                ?: Result.success(rows.filter { it.attempt.projectId == projectId && it.attempt.finishedAt > since })
    }
}
