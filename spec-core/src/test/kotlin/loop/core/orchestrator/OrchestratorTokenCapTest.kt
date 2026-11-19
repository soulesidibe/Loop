package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per attempt token budget, `budgets.per_attempt_tokens`, the m1-08 token cap folded into the spend budgets
 * (m2-20): it warns at 80 percent, and past it the attempt stops and the issue is parked with `budget`, not retried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTokenCapTest {
    private val root: Path = createTempDirectory("loop-orchestrator-token-cap").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val hookRunner = FakeHookRunner()
    private val project = ProjectId("p1")

    private fun workflow(
        cap: Long? = 1_000L,
        maxTurns: Int = 20,
        maxAttempts: Int = 5,
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
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                    budgets = BudgetsConfig(perAttemptTokens = cap),
                    hooks = HooksConfig(afterRun = "echo after"),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = project,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    /** A session whose turn `n` reports [totals]`[n - 1]` tokens (absolute for the thread) and completes. */
    private fun usageRunner(vararg totals: Long): FakeRunner =
        FakeRunner().also { runner ->
            runner.sessionScript =
                { turn, _ -> flow { emitAll(sessionTurn(turn, tokens = totals[(turn - 1).coerceAtMost(totals.lastIndex)])) } }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `growing usage past the cap stops the attempt and parks the issue`() =
        runTest {
            val runner = usageRunner(400, 800, 1_200, 1_600)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner)

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals(1, runner.stops)
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("budget", summary.reason)
            val event = sink.ofKind("issue_parked").single()
            assertEquals(mapOf("reason" to "budget", "budget" to "per_attempt_tokens", "used" to "1200", "limit" to "1000"), event.data)
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )
            assertTrue(sink.ofKind("retry_scheduled").isEmpty())
            assertEquals("budget", sink.ofKind("attempt_finished").single().data["reason"])
            assertTrue(sink.ofKind("attempt_finished").single().data["outcome"] != "canceled")
            val reached = sink.ofKind("budget_reached").single()
            assertEquals(mapOf("budget" to "per_attempt_tokens", "used" to "1200", "limit" to "1000"), reached.data)
            // 800 of 1,000 is 80 percent: one warning, never repeated by the later reports.
            val warning = sink.ofKind("budget_warning").single()
            assertEquals(mapOf("budget" to "per_attempt_tokens", "used" to "800", "limit" to "1000"), warning.data)
            // The fold replaced the event of the token cap.
            assertTrue(sink.ofKind("token_cap_reached").isEmpty())
            assertTrue(HookName.AFTER_RUN in hookRunner.hooks())
        }

    @Test
    fun `repeated cumulative totals under the cap are not summed into a breach`() =
        runTest {
            // 400 + 400 + 400 is over the cap of 500, the latest absolute total of 400 is not.
            val runner = usageRunner(400, 400, 400)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 500, maxTurns = 3))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            assertTrue(sink.ofKind("issue_parked").isEmpty())
        }

    @Test
    fun `exactly the cap passes and one token more stops the attempt`() =
        runTest {
            val runner = usageRunner(1_000, 1_001)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 1_000, maxTurns = 5))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals("1001", sink.ofKind("budget_reached").single().data["used"])
        }

    @Test
    fun `usage events carry the attempt tokens and the cap`() =
        runTest {
            val runner = usageRunner(300, 700)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 1_000, maxTurns = 2))

            orchestrator.tick()

            val usage = sink.ofKind("usage")
            assertEquals(listOf("300", "700"), usage.map { it.data["attempt_tokens"] })
            assertEquals(setOf("1000"), usage.map { it.data["max_tokens"] }.toSet())
        }

    @Test
    fun `cache reads count toward the cap`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> sessionTurn(turn, tokens = 100, cacheRead = 950) }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 1_000))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals(listOf(1), runner.turnInputs.map { it.first })
        }

    @Test
    fun `a token cap is not a failure and leaves the failure counter alone`() =
        runTest {
            val runner = FakeRunner()
            // Attempts 1 and 2 fail, attempt 3 hits the cap, attempt 4 (after a resume) fails again.
            runner.sessionScript = { turn, _ ->
                when (runner.sessionsOpened) {
                    3 -> sessionTurn(turn, tokens = 5_000)
                    else -> sessionTurn(turn, exitCode = 1)
                }
            }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 1_000))

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            val event = sink.ofKind("issue_parked").single()
            assertEquals("budget", event.data["reason"])
            assertEquals("per_attempt_tokens", event.data["budget"])
            assertNull(event.data["failures"])
            assertNull(event.data["error"])
            assertEquals(2, sink.ofKind("retry_scheduled").size)
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )

            advanceTimeBy(3_600_000)
            orchestrator.tick()
            assertEquals(3, runner.sessionsOpened)

            assertNotNull(parked.unpark(project, "id-m1-08").getOrThrow())
            orchestrator.tick()

            assertEquals(4, runner.sessionsOpened)
            val scheduled = sink.ofKind("retry_scheduled").last()
            assertEquals("1", scheduled.data["failures"])
            assertEquals("1", scheduled.data["attempt"])
        }

    @Test
    fun `a breaching usage cuts a turn that never ends and closes the upstream`() =
        runTest {
            var upstreamClosed = false
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                flow {
                    val turnId = TurnId("tu$turn")
                    val sessionId = SessionId("th1-tu$turn")
                    emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
                    try {
                        emit(RunnerEvent.Usage(sessionId, turnId, 5_000, 0, 0, 0, totalCostUsd = null))
                        awaitCancellation()
                    } finally {
                        upstreamClosed = true
                    }
                }
            }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner)

            val summary = orchestrator.tick().attempts.single()

            assertTrue(upstreamClosed)
            assertEquals(ParkReason.Budget, summary.parkReason)
            assertEquals("budget", summary.reason)
            assertEquals("budget", sink.ofKind("attempt_finished").single().data["reason"])
            assertEquals(
                ParkReason.Budget,
                parked.records.values
                    .single()
                    .reason,
            )
        }

    @Test
    fun `a capped turn keeps its real terminal event and marks the turn as capped`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> sessionTurn(turn, tokens = 5_000, exitCode = 1) }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner)

            val summary = orchestrator.tick().attempts.single()

            assertEquals(ParkReason.Budget, summary.parkReason)
            assertTrue(sink.ofKind("turn_completed").isEmpty())
            val failed = sink.ofKind("turn_failed").single()
            assertEquals("exit_code=1", failed.data["reason"])
            assertEquals("true", failed.data["capped"])
            assertEquals("budget", sink.ofKind("issue_parked").single().data["reason"])
        }

    @Test
    fun `without a ceiling no total parks the attempt and usage carries no cap`() =
        runTest {
            val runner = usageRunner(40_000_000, 90_000_000, 150_000_000)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = null, maxTurns = 3))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            assertTrue(sink.ofKind("issue_parked").isEmpty())
            val usage = sink.ofKind("usage")
            assertEquals(listOf("40000000", "90000000", "150000000"), usage.map { it.data["attempt_tokens"] })
            assertTrue(usage.none { "max_tokens" in it.data })
        }

    @Test
    fun `an estimate above the ceiling never parks, only real usage does`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ ->
                flow {
                    val turnId = TurnId("tu$turn")
                    val sessionId = SessionId("th1-tu$turn")
                    emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
                    emit(RunnerEvent.UsageEstimate(sessionId, turnId, 5_000, 0, 0, 0))
                    emit(RunnerEvent.Usage(sessionId, turnId, 900, 0, 0, 0, totalCostUsd = null))
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-08"))), runner, workflow(cap = 1_000, maxTurns = 1))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            assertTrue(sink.ofKind("budget_reached").isEmpty())
        }
}
