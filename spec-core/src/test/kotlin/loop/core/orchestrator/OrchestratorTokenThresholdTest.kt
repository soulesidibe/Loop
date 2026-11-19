package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
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

/**
 * Token thresholds of an attempt (m1-38): every `budgets.token_threshold_every` tokens Loop records a
 * `token_threshold` event, logs it and runs `hooks.on_token_threshold`, and the attempt goes on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTokenThresholdTest {
    private val root: Path = createTempDirectory("loop-orchestrator-token-threshold").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val hookRunner = FakeHookRunner()
    private val logLines = mutableListOf<String>()
    private val project = ProjectId("p1")

    private fun workflow(
        every: Long = 10_000_000L,
        cap: Long? = null,
        maxTurns: Int = 20,
        onTokenThreshold: String? = null,
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
                    budgets = BudgetsConfig(perAttemptTokens = cap, tokenThresholdEvery = every),
                    hooks = HooksConfig(onTokenThreshold = onTokenThreshold, timeoutMs = 4_000),
                ),
        )

    private fun TestScope.orchestrator(
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
        newId: () -> String =
            run {
                var next = 0
                { "a${++next}" }
            },
        recordedThreshold: suspend (AttemptId) -> Long = { 0L },
    ): Orchestrator {
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-38"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
            newId = newId,
            runId = RunId("r1"),
            projectId = project,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            recordedThreshold = recordedThreshold,
        )
    }

    /** One turn of a session: the [events] between its start and its normal end. */
    private fun turn(
        turn: Int,
        events: (SessionId, TurnId) -> List<RunnerEvent>,
    ): Flow<RunnerEvent> =
        flow {
            val turnId = TurnId("tu$turn")
            val sessionId = SessionId("th1-tu$turn")
            if (turn == 1) {
                emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
            } else {
                emit(RunnerEvent.TurnStarted(sessionId, threadId = "th1", turnId = turnId, turn = turn))
            }
            events(sessionId, turnId).forEach { emit(it) }
            emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
        }

    /** A session whose turn `n` reports the real absolute total [totals]`[n - 1]` with cost [costs]`[n - 1]`. */
    private fun usageRunner(
        totals: List<Long>,
        costs: List<Double?> = totals.map { null },
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.sessionScript = { n, _ ->
                val index = (n - 1).coerceAtMost(totals.lastIndex)
                turn(n) { session, turnId -> listOf(usage(session, turnId, totals[index], costs[index])) }
            }
        }

    private fun usage(
        session: SessionId,
        turnId: TurnId,
        total: Long,
        cost: Double? = null,
    ): RunnerEvent.Usage {
        // Mostly cache reads, like a real Claude Code turn.
        val cacheRead = total / 10 * 9
        return RunnerEvent.Usage(session, turnId, total - cacheRead, 0, cacheRead, 0, totalCostUsd = cost)
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `crossing 10M then 20M records two threshold events and the attempt goes on`() =
        runTest {
            val runner = usageRunner(listOf(6_000_000, 12_000_000, 21_000_000, 25_000_000), listOf(1.0, 2.5, 3.38, 4.0))
            val orchestrator = orchestrator(runner, workflow(maxTurns = 4))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(listOf(1, 2, 3, 4), runner.turnInputs.map { it.first })
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            val thresholds = sink.ofKind("token_threshold")
            assertEquals(
                listOf(
                    mapOf("tokens" to "12000000", "threshold" to "10000000", "cost_usd" to "2.5"),
                    mapOf("tokens" to "21000000", "threshold" to "20000000", "cost_usd" to "3.38"),
                ),
                thresholds.map { it.data },
            )
            thresholds.forEach { event ->
                assertEquals("id-m1-38", event.issueId)
                assertEquals("m1-38", event.issueIdentifier)
                assertEquals("a1", event.attemptId?.value)
            }
            assertTrue(sink.ofKind("issue_parked").isEmpty())
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            val logged = logLines.filter { "event=token_threshold" in it }
            assertEquals(2, logged.size, logLines.joinToString("\n"))
            assertTrue("level=info" in logged[0] && "outcome=reached" in logged[0], logged[0])
            assertTrue("tokens=12000000 threshold=10000000 cost_usd=2.5" in logged[0], logged[0])
        }

    @Test
    fun `one report that jumps from 9M to 31M records 10M, 20M and 30M in order`() =
        runTest {
            val runner = usageRunner(listOf(9_000_000, 31_000_000), listOf(1.0, 3.0))
            orchestrator(runner, workflow(maxTurns = 2)).tick()

            val thresholds = sink.ofKind("token_threshold")
            assertEquals(listOf("10000000", "20000000", "30000000"), thresholds.map { it.data["threshold"] })
            assertEquals(setOf("31000000"), thresholds.map { it.data["tokens"] }.toSet())
        }

    @Test
    fun `a restarted run never records a step the attempt already recorded`() =
        runTest {
            orchestrator(usageRunner(listOf(21_000_000)), workflow(maxTurns = 1), newId = { "a1" }).tick()
            assertEquals(listOf("10000000", "20000000"), sink.ofKind("token_threshold").map { it.data["threshold"] })

            // A new process with the same store and the same attempt id: what the store holds is not recorded again.
            val restarted =
                orchestrator(
                    usageRunner(listOf(21_000_000, 31_000_000)),
                    workflow(maxTurns = 2),
                    newId = { "a1" },
                    recordedThreshold = { attempt -> highestRecordedThreshold(sink.events.filter { it.attemptId == attempt }) },
                )
            restarted.tick()

            assertEquals(
                listOf("10000000", "20000000", "30000000"),
                sink.ofKind("token_threshold").map { it.data["threshold"] },
            )
        }

    @Test
    fun `an estimate crossing a step records it as estimated before the turn ends, and never parks`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { n, _ ->
                turn(n) { session, turnId ->
                    listOf(
                        RunnerEvent.UsageEstimate(session, turnId, 500_000, 1_000, 10_000_000, 0),
                        RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "still working"),
                        // The real total of the turn comes in below the estimate: 10M is not recorded again.
                        usage(session, turnId, 10_200_000, cost = 2.62),
                    )
                }
            }
            val orchestrator = orchestrator(runner, workflow(cap = 10_300_000, maxTurns = 1))

            val summary = orchestrator.tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertNull(summary.parkReason)
            val threshold = sink.ofKind("token_threshold").single()
            assertEquals(mapOf("tokens" to "10501000", "threshold" to "10000000", "estimated" to "true"), threshold.data)
            val kinds = sink.events.map { it.kind }
            assertTrue(kinds.indexOf("token_threshold") < kinds.indexOf("turn_completed"), kinds.toString())
            assertTrue(kinds.indexOf("token_threshold") < kinds.indexOf("usage"), kinds.toString())
            // The estimate is above the ceiling, the real total is not: only real usage parks.
            assertTrue(sink.ofKind("budget_reached").isEmpty())
            assertEquals(listOf("10200000"), sink.ofKind("usage").map { it.data["attempt_tokens"] })
        }

    @Test
    fun `an estimate carries the cost of the latest real usage report`() =
        runTest {
            val runner = FakeRunner()
            runner.sessionScript = { n, _ ->
                turn(n) { session, turnId ->
                    if (n == 1) {
                        listOf(usage(session, turnId, 6_000_000, cost = 1.75))
                    } else {
                        listOf(
                            RunnerEvent.UsageEstimate(session, turnId, 11_000_000, 0, 0, 0),
                            usage(session, turnId, 11_500_000, cost = 3.1),
                        )
                    }
                }
            }
            orchestrator(runner, workflow(maxTurns = 2)).tick()

            val threshold = sink.ofKind("token_threshold").single()
            assertEquals(
                mapOf("tokens" to "11000000", "threshold" to "10000000", "cost_usd" to "1.75", "estimated" to "true"),
                threshold.data,
            )
        }

    @Test
    fun `zero turns thresholds off`() =
        runTest {
            orchestrator(usageRunner(listOf(50_000_000)), workflow(every = 0, maxTurns = 1, onTokenThreshold = "notify")).tick()

            assertTrue(sink.ofKind("token_threshold").isEmpty())
            assertTrue(HookName.ON_TOKEN_THRESHOLD !in hookRunner.hooks())
        }

    @Test
    fun `the hook runs once per event with the five variables set`() =
        runTest {
            val runner = usageRunner(listOf(31_000_000), listOf(3.38))
            orchestrator(runner, workflow(maxTurns = 1, onTokenThreshold = "notify \"\$LOOP_THRESHOLD\"")).tick()

            val calls = hookRunner.calls.filter { it.hook == HookName.ON_TOKEN_THRESHOLD }
            assertEquals(3, calls.size)
            assertEquals(listOf("10000000", "20000000", "30000000"), calls.map { it.env["LOOP_THRESHOLD"] })
            calls.forEach { call ->
                assertEquals("m1-38", call.env["LOOP_ISSUE"])
                assertEquals("a1", call.env["LOOP_ATTEMPT"])
                assertEquals("31000000", call.env["LOOP_TOKENS"])
                assertEquals("3.38", call.env["LOOP_COST_USD"])
                assertEquals("notify \"\$LOOP_THRESHOLD\"", call.script)
                assertEquals(4_000, call.timeoutMs)
                assertEquals(root.resolve("workspaces/m1-38"), call.cwd)
            }
            val finished = sink.ofKind("hook_finished").filter { it.data["hook"] == "on_token_threshold" }
            assertEquals(3, finished.size)
            // Each hook run follows its own event.
            val kinds =
                sink.events.map { it.kind to it.data["hook"] }.filter {
                    it.first == "token_threshold" ||
                        it.second == "on_token_threshold"
                }
            assertEquals(List(3) { listOf("token_threshold" to null, "hook_finished" to "on_token_threshold") }.flatten(), kinds)
        }

    @Test
    fun `the cost variable is empty while no cost is known`() =
        runTest {
            orchestrator(usageRunner(listOf(10_000_000)), workflow(maxTurns = 1, onTokenThreshold = "notify")).tick()

            assertEquals("", hookRunner.calls.single { it.hook == HookName.ON_TOKEN_THRESHOLD }.env["LOOP_COST_USD"])
            assertNull(sink.ofKind("token_threshold").single().data["cost_usd"])
        }

    @Test
    fun `a failing hook and a timed out hook are recorded and the attempt goes on`() =
        runTest {
            var runs = 0
            hookRunner.onRun = { hook ->
                if (hook == HookName.ON_TOKEN_THRESHOLD) {
                    runs++
                    if (runs == 1) hookRunner.exitCodes[hook] = 1
                    if (runs == 2) {
                        hookRunner.exitCodes.remove(hook)
                        hookRunner.timedOut += hook
                    }
                }
            }
            val runner = usageRunner(listOf(11_000_000, 21_000_000, 22_000_000))
            val summary = orchestrator(runner, workflow(maxTurns = 3, onTokenThreshold = "notify")).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            val finished = sink.ofKind("hook_finished").filter { it.data["hook"] == "on_token_threshold" }
            assertEquals(listOf("1", null), finished.map { it.data["exit_code"] })
            assertEquals(listOf("false", "true"), finished.map { it.data["timed_out"] })
            assertTrue(sink.ofKind("turn_failed").isEmpty())
            assertTrue(
                logLines.any { "event=hook" in it && "level=warn" in it && "hook=on_token_threshold" in it },
                logLines.joinToString("\n"),
            )
        }
}
