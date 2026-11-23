package loop.core.orchestrator

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.LedgerTurn
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

/**
 * m4-03, story 103 (bl-155): a wake turn, one that read the notification of a background task run an earlier turn
 * started, never counts against `agent.max_turns`. `agent.max_wake_turns` caps them instead. Its usage counts, its
 * ledger line says `wake` and the soft limit is checked at its end like any turn's. The wakes are scripted in the
 * fake runner.
 */
class OrchestratorWakeTurnsTest {
    private val root: Path = createTempDirectory("loop-orchestrator-wakes").toRealPath()
    private val sink = FakeSink()
    private val ledgerLines = mutableListOf<LedgerTurn>()

    private fun workflow(
        maxTurns: Int = 20,
        maxWakeTurns: Int = 30,
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
                    agent = AgentConfig(maxTurns = maxTurns, maxWakeTurns = maxWakeTurns),
                    budgets = budgets,
                    // Above the soft limit of 0.20, so the rotation case reaches the soft limit and not the hard one.
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
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            turnLedger =
                TurnLedger {
                    ledgerLines += it
                    Result.success(Unit)
                },
        )
    }

    /**
     * A runner declaring occupancy whose sessions are numbered from 1: turn `t` of session `s` is a wake turn when
     * [wakes] says so, reports the occupancy `occupancy(s, t)` when set and the absolute token total `tokens(s, t)`
     * when set, runs [onTurn], then completes.
     */
    private fun runner(
        wakes: (Int, Int) -> Boolean,
        tokens: (Int, Int) -> Long? = { _, _ -> null },
        occupancy: (Int, Int) -> Long? = { _, _ -> null },
        onTurn: suspend (Int, Int) -> Unit = { _, _ -> },
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.occupancy = true
            runner.wakes = wakes
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
                    tokens(session, turn)?.let { emit(RunnerEvent.Usage(sessionId, turnId, it, 0, 0, 0, it / 100.0)) }
                    onTurn(session, turn)
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `wake turns do not count against max_turns, the attempt ends once max_turns ordinary turns ran`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-03")))
            // Turns 2, 3 and 5 read a notification: 1, 4 and 6 are the three counted turns of max_turns 3.
            val runner = runner(wakes = { _, turn -> turn in setOf(2, 3, 5) })

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 3)).tick().attempts.single()

            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(6, summary.turns)
            assertEquals(listOf(1, 2, 3, 4, 5, 6), runner.turnInputs.map { it.first })
            // The guidance names the next counted turn: a wake turn leaves it where it was.
            val counted = runner.turnInputs.drop(1).map { it.second.substringAfter("This is turn ").substringBefore(".") }
            assertEquals(listOf("2 of 3", "2 of 3", "2 of 3", "3 of 3", "3 of 3"), counted)
            val completed = sink.ofKind("turn_completed")
            assertEquals(listOf(null, "true", "true", null, "true", null), completed.map { it.data["wake"] })
            assertEquals(listOf("1", "2", "3", "4", "5", "6"), completed.map { it.data["turn"] })
            assertEquals(listOf(false, true, true, false, true, false), ledgerLines.map { it.wake })
            assertEquals(listOf(1, 2, 3, 4, 5, 6), ledgerLines.map { it.turn })
        }

    @Test
    fun `the attempt ends with max_wake_turns once max_wake_turns wake turns ran`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-03")))
            val runner = runner(wakes = { _, turn -> turn > 1 })

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 20, maxWakeTurns = 2)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.MAX_WAKE_TURNS, summary.exitReason)
            assertEquals(3, summary.turns)
            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals(
                mapOf("exit_reason" to "max_wake_turns", "turns" to "3"),
                sink.ofKind("attempt_finished").single().data - "outcome",
            )
        }

    @Test
    fun `the six turns of attempt 10c2f759 use 2 of its 10 turns, the seventh says turn 3 of 10`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-03")))
            val runner =
                runner(
                    wakes = { _, turn -> turn in setOf(2, 3, 4, 6) },
                    onTurn = { _, turn -> if (turn == 7) tracker.moveIssue("id-m4-03", "review") },
                )

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 10)).tick().attempts.single()

            assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
            assertEquals(7, summary.turns)
            assertTrue(
                runner.turnInputs[6].second.startsWith("Continue working on m4-03. This is turn 3 of 10."),
                runner.turnInputs[6].second,
            )
        }

    @Test
    fun `a wake turn's usage counts in the attempt totals and its budget, its ledger line says wake`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-03")))
            // Absolute totals: turn 1 spends 400 tokens, the wake turn 2 brings the attempt to 1_200, past 1_000.
            val runner = runner(wakes = { _, turn -> turn == 2 }, tokens = { _, turn -> if (turn == 1) 400L else 1_200L })

            val summary =
                orchestrator(tracker, runner, workflow(budgets = BudgetsConfig(perAttemptTokens = 1_000))).tick().attempts.single()

            assertEquals("budget", summary.reason)
            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(
                mapOf("budget" to "per_attempt_tokens", "used" to "1200", "limit" to "1000"),
                sink.ofKind("budget_reached").single().data,
            )
            val wake = ledgerLines.last()
            assertEquals(true, wake.wake)
            assertEquals(800L, wake.tokens?.total)
            assertEquals(1_200L, wake.attemptTokens?.total)
            assertEquals(12.0, wake.attemptCostUsd)
        }

    @Test
    fun `the soft limit rotation fires at the end of a wake turn`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-03")))
            val runner =
                runner(
                    wakes = { session, turn -> session == 1 && turn == 2 },
                    occupancy = { session, turn -> if (session == 1 && turn == 2) 240_000L else 30_000L },
                    onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m4-03", "review") },
                )

            val summary = orchestrator(tracker, runner, workflow()).tick().attempts.single()

            assertEquals(3, summary.turns)
            assertEquals(2, runner.sessionsOpened)
            val kinds = sink.events.map { it.kind }.filter { it == "session_rotated" || it == "turn_completed" }
            assertEquals(listOf("turn_completed", "turn_completed", "session_rotated", "turn_completed"), kinds)
            assertEquals("soft_limit", sink.ofKind("session_rotated").single().data["reason"])
            assertEquals(listOf(false, true, false), ledgerLines.map { it.wake })
        }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
    }
}
