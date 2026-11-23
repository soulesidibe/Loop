package loop.core.orchestrator

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.AttemptLedger
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerTurn
import loop.api.ParkReason
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
import loop.core.workflow.AgentConfig
import loop.core.workflow.BudgetsConfig
import loop.core.workflow.ContextConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-04, story 104 (bl-152): the usage of a wake read at or after the stop counts. Every stop, the attempt's end
 * included, passes the usage callback, a stop grace that ends with a wake running records `stop_grace_expired` after
 * the usage its lines reported, a budget that usage goes past ends the attempt on the budget, even when the stop then
 * throws, and the ledger's turn lines sum to the attempt total. The wakes are scripted in the fake runner.
 */
class OrchestratorStopUsageTest {
    private val root: Path = createTempDirectory("loop-orchestrator-stop-usage").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val ledgerLines = mutableListOf<LedgerTurn>()
    private val rows = RecordingAttemptLedger()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxTurns: Int = 20,
        budgets: BudgetsConfig = BudgetsConfig(),
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
                    agent = AgentConfig(maxTurns = maxTurns),
                    budgets = budgets,
                    // Above the soft limit of 0.20, so the rotation cases reach the soft limit and not the hard one.
                    context = ContextConfig(hardLimit = 0.25),
                ),
        )

    private fun orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile,
    ): Orchestrator {
        var next = 0
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            turnLedger =
                TurnLedger {
                    ledgerLines += it
                    Result.success(Unit)
                },
            attemptLedger = rows,
        )
    }

    /**
     * A runner declaring occupancy whose sessions are numbered from 1: turn `t` of session `s` reports the absolute
     * token total `tokens(s, t)` with a cost of a hundredth of it, the occupancy `occupancy(s, t)` when set, runs
     * [onTurn], then completes.
     */
    private fun runner(
        tokens: (Int, Int) -> Long,
        occupancy: (Int, Int) -> Long? = { _, _ -> null },
        onTurn: suspend (Int, Int) -> Unit = { _, _ -> },
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.occupancy = true
            runner.sessionScript = { turn, _ ->
                val session = runner.sessionsOpened
                flow {
                    val thread = "th$session"
                    val turnId = TurnId("s${session}t$turn")
                    val sessionId = SessionId("$thread-${turnId.value}")
                    if (turn == 1) {
                        emit(RunnerEvent.SessionStarted(sessionId, thread, turnId, runtimeSessionId = "runtime-$session"))
                    } else {
                        emit(RunnerEvent.TurnStarted(sessionId, thread, turnId, turn))
                    }
                    occupancy(session, turn)?.let { emit(RunnerEvent.ContextUsage(sessionId, turnId, it, OPUS)) }
                    val total = tokens(session, turn)
                    emit(RunnerEvent.Usage(sessionId, turnId, total, 0, 0, 0, total / 100.0))
                    onTurn(session, turn)
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }
        }

    /** What a stop reads of a wake: its usage, the session's absolute [tokens], and the grace expiry when [expired]. */
    private fun wake(
        session: Int,
        turn: Int,
        tokens: Long,
        expired: Boolean = false,
    ): List<RunnerEvent> {
        val turnId = TurnId("s${session}t$turn")
        val sessionId = SessionId("th$session-${turnId.value}")
        val usage = RunnerEvent.Usage(sessionId, turnId, tokens, 0, 0, 0, null)
        val grace = RunnerEvent.StopGraceExpired(sessionId, turnId, graceMs = 5000, wake = true, wakeMessages = 1)
        return if (expired) listOf(usage, grace) else listOf(usage)
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    /** The turn lines of the attempt sum to its row's total, tokens and cost. */
    private fun assertLinesSumToRow() {
        val row = rows.recorded.single()
        assertEquals(row.tokens?.total, ledgerLines.sumOf { it.tokens?.total ?: 0 }, ledgerLines.toString())
        assertEquals(row.costUsd, ledgerLines.sumOf { it.costUsd ?: 0.0 }, ledgerLines.toString())
    }

    @Test
    fun `a wake still running when the stop grace ends at the attempt's end records stop_grace_expired after its usage`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner = runner(tokens = { _, _ -> 1_000L }, onTurn = { _, _ -> tracker.moveIssue("id-m4-04", "review") })
            runner.stopUsage = { wake(it, 1, tokens = 1_500L, expired = true) }

            val summary = orchestrator(tracker, runner, workflow()).tick().attempts.single()

            assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
            assertEquals(listOf(1), runner.reportedStops)
            assertEquals(listOf("1000", "1500"), sink.ofKind("usage").map { it.data["attempt_tokens"] })
            assertEquals(
                mapOf(
                    "grace_ms" to "5000",
                    "wake" to "true",
                    "wake_messages" to "1",
                    "attempt_tokens" to "1500",
                    "total_cost_usd" to "10.0",
                ),
                sink.ofKind("stop_grace_expired").single().data,
            )
            val kinds = sink.events.map { it.kind }.filter { it in setOf("usage", "stop_grace_expired", "attempt_finished") }
            assertEquals(listOf("usage", "usage", "stop_grace_expired", "attempt_finished"), kinds)
            assertTrue(logLines.any { "grace_ms=5000" in it && "outcome=failed" in it }, logLines.joinToString("\n"))
            assertEquals(
                1_500L,
                rows.recorded
                    .single()
                    .tokens
                    ?.total,
            )
        }

    @Test
    fun `a wake that ended while idle after a review end reaches the ledger in a stop line, the lines sum to the total`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner =
                runner(tokens = { _, turn -> 1_000L * turn }, onTurn = { _, turn ->
                    if (turn ==
                        2
                    ) {
                        tracker.moveIssue("id-m4-04", "review")
                    }
                })
            runner.stopUsage = { wake(it, 2, tokens = 2_600L) }

            val summary = orchestrator(tracker, runner, workflow()).tick().attempts.single()

            assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
            assertEquals(listOf(1), runner.reportedStops)
            assertEquals(listOf("completed", "completed", StopUsage.STOP), ledgerLines.map { it.outcome })
            assertEquals(listOf(1_000L, 1_000L, 600L), ledgerLines.map { it.tokens?.total })
            val stop = ledgerLines.last()
            assertEquals(2, stop.turn)
            assertEquals(TurnId("s1t2"), stop.turnId)
            assertEquals(2_600L, stop.attemptTokens?.total)
            assertEquals(
                2_600L,
                rows.recorded
                    .single()
                    .tokens
                    ?.total,
            )
            assertLinesSumToRow()
        }

    @Test
    fun `a wake that ended while idle after a max turns end reaches the budgets and the ledger`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner = runner(tokens = { _, turn -> 1_000L * turn })
            runner.stopUsage = { wake(it, 2, tokens = 2_600L) }

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 2)).tick().attempts.single()

            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(listOf(1), runner.reportedStops)
            assertEquals(listOf("1000", "2000", "2600"), sink.ofKind("usage").map { it.data["attempt_tokens"] })
            assertEquals(listOf(1_000L, 1_000L, 600L), ledgerLines.map { it.tokens?.total })
            assertLinesSumToRow()
        }

    @Test
    fun `a budget passed by usage read at the attempt's last stop ends the attempt on the budget`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner = runner(tokens = { _, _ -> 1_000L })
            runner.stopUsage = { wake(it, 1, tokens = 1_500L) }

            val summary =
                orchestrator(tracker, runner, workflow(maxTurns = 1, budgets = BudgetsConfig(perAttemptTokens = 1_200)))
                    .tick()
                    .attempts
                    .single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("budget", summary.reason)
            assertEquals(
                mapOf("budget" to "per_attempt_tokens", "used" to "1500", "limit" to "1200"),
                sink.ofKind("budget_reached").single().data,
            )
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )
            assertLinesSumToRow()
        }

    @Test
    fun `a stop that throws at the attempt's end after usage past a budget ends on the budget, session_stop_failed recorded`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner = runner(tokens = { _, _ -> 1_000L })
            runner.stopUsage = { wake(it, 1, tokens = 1_500L) }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary =
                orchestrator(tracker, runner, workflow(maxTurns = 1, budgets = BudgetsConfig(perAttemptTokens = 1_200)))
                    .tick()
                    .attempts
                    .single()

            assertEquals("budget", summary.reason)
            assertEquals(
                "could not stop the claude-code process group 4242",
                sink.ofKind("session_stop_failed").single().data["reason"],
            )
            assertEquals("per_attempt_tokens", sink.ofKind("budget_reached").single().data["budget"])
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )
            assertEquals(listOf("completed", StopUsage.STOP), ledgerLines.map { it.outcome })
            assertLinesSumToRow()
        }

    @Test
    fun `a stop that throws at a rotation after usage past a budget ends on the budget, session_stop_failed recorded`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            // Turn 1 crosses the soft limit, so the session rotates; its stop reads a wake past the budget, then fails.
            val runner = runner(tokens = { _, _ -> 1_000L }, occupancy = { _, _ -> 240_000L })
            runner.stopUsage = { wake(it, 1, tokens = 1_500L) }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary =
                orchestrator(tracker, runner, workflow(budgets = BudgetsConfig(perAttemptTokens = 1_200)))
                    .tick()
                    .attempts
                    .single()

            assertEquals("budget", summary.reason)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(
                "could not stop the claude-code process group 4242",
                sink.ofKind("session_stop_failed").single().data["reason"],
            )
            assertEquals("per_attempt_tokens", sink.ofKind("budget_reached").single().data["budget"])
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )
            // The wake's usage is in the attempt's total and in a stop line, since no turn follows the failed stop.
            assertEquals(listOf("completed", StopUsage.STOP), ledgerLines.map { it.outcome })
            assertLinesSumToRow()
        }

    @Test
    fun `a stop that throws at a rotation with no budget passed still ends as session_stop_failed`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner = runner(tokens = { _, _ -> 1_000L }, occupancy = { _, _ -> 240_000L })
            runner.stopUsage = { wake(it, 1, tokens = 1_100L) }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary =
                orchestrator(tracker, runner, workflow(budgets = BudgetsConfig(perAttemptTokens = 1_200)))
                    .tick()
                    .attempts
                    .single()

            assertEquals("session_stop_failed", summary.reason)
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            assertLinesSumToRow()
        }

    @Test
    fun `a wake still running when a rotation's stop grace ends records stop_grace_expired, its usage in the next turn line`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-04")))
            val runner =
                runner(
                    tokens = { session, _ -> if (session == 1) 1_000L else 200L },
                    occupancy = { session, _ -> if (session == 1) 240_000L else 10_000L },
                    onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m4-04", "review") },
                )
            runner.stopUsage = { session -> if (session == 1) wake(1, 1, tokens = 1_500L, expired = true) else emptyList() }

            orchestrator(tracker, runner, workflow()).tick()

            assertEquals(listOf(1, 2), runner.reportedStops)
            assertEquals("1500", sink.ofKind("stop_grace_expired").single().data["attempt_tokens"])
            val kinds = sink.events.map { it.kind }.filter { it in setOf("usage", "stop_grace_expired", "session_rotated") }
            assertEquals(listOf("usage", "usage", "stop_grace_expired", "session_rotated", "usage"), kinds)
            assertEquals(listOf(1_000L, 700L), ledgerLines.map { it.tokens?.total })
            assertLinesSumToRow()
        }

    /** Keeps the attempt rows; the budgets of an issue read them back. */
    private class RecordingAttemptLedger : AttemptLedger {
        val recorded = mutableListOf<LedgerAttempt>()

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            recorded += attempt
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> = Result.success(recorded.filter { it.issueId == issueId }.map { LedgerRow(it, 0, 0) })

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> = Result.success(emptyList())
    }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
    }
}
