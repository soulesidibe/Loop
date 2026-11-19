package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.machine.MachineConfigError
import loop.core.machine.MachineLimits
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.RetriesConfig
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The machine limits (m3-16): a machine slot claimed before each launch and each merge check, released at its end,
 * and the machine day budget that skips new dispatches of every project. Both read from the machine, which the cli
 * backs with `<LOOP_HOME>/config.yaml` and the shared store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineLimitsOrchestratorTest {
    private val root: Path = createTempDirectory("loop-machine-limits").toRealPath()
    private val machine = FakeMachine()
    private val parked = FakeParkedIssues()

    private fun workflow(
        project: String = "p1",
        maxConcurrentAgents: Int = 10,
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("$project/WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve("$project/.loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("$project/workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = maxConcurrentAgents),
                    retries = RetriesConfig(maxAttempts = 5),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner = FakeRunner(),
        sink: FakeSink = FakeSink(),
        project: String = "p1",
        maxConcurrentAgents: Int = 10,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        val workflow = workflow(project, maxConcurrentAgents)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "$project-a${++next}" },
            runId = RunId("r-$project"),
            projectId = ProjectId(project),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            machine = machine,
        )
    }

    private fun FakeSink.skipReasons(): List<String?> = events.filter { it.kind == "candidate_skipped" }.map { it.data["reason"] }

    /** A finished attempt of another project in the store, [ago] before the test clock's start. */
    private fun otherProjectRow(
        costUsd: Double?,
        tokens: Long = 1_000,
        finishedAt: Instant = FIXED_NOW - 1.hours,
    ) = LedgerRow(
        LedgerAttempt(
            projectId = ProjectId("other"),
            runId = RunId("r-other"),
            issueId = "o-1",
            identifier = "o-1",
            attemptId = AttemptId("other-attempt"),
            setup = LedgerSetup("files", null, "command", null, "t".repeat(64), null),
            startedAt = finishedAt - 1.hours,
            finishedAt = finishedAt,
            outcome = "succeeded",
            reason = null,
            turns = 1,
            issueState = "review",
            tokens = LedgerTokens(tokens, 0, 0, 0),
            costUsd = costUsd,
            checks = null,
            guards = null,
            verdict = null,
        ),
        number = 1,
        humanTouches = 0,
    )

    @Test
    fun `no machine config dispatches as today and writes no slot`() =
        runTest {
            val result = orchestrator(FakeTracker(listOf(issue("t-1"), issue("t-2")))).tick()

            assertEquals(listOf("t-1", "t-2"), result.attempts.map { it.issueIdentifier })
            assertEquals(emptyList(), machine.log)
        }

    @Test
    fun `a config without a cap writes no slot`() =
        runTest {
            machine.limits = MachineLimits(perDayUsd = 100.0)

            val result = orchestrator(FakeTracker(listOf(issue("t-1")))).tick()

            assertEquals(listOf("t-1"), result.attempts.map { it.issueIdentifier })
            assertEquals(emptyList(), machine.log)
        }

    @Test
    fun `the slot is claimed before the launch and released at the attempt end`() =
        runTest {
            machine.limits = MachineLimits(maxConcurrentAgents = 2)
            val heldAtLaunch = mutableListOf<List<String>>()
            val runner = FakeRunner().apply { onCall = { heldAtLaunch += machine.slots.toList() } }

            val result = orchestrator(FakeTracker(listOf(issue("t-1"))), runner).tick()

            assertEquals(listOf("t-1"), result.attempts.map { it.issueIdentifier })
            assertEquals(1, heldAtLaunch.single().size, "the slot is held when the agent starts")
            val holder = heldAtLaunch.single().single()
            assertEquals(listOf("claimed $holder", "released $holder"), machine.log)
            assertEquals(emptyList(), machine.slots)
        }

    @Test
    fun `a dispatch that cannot claim a machine slot is skipped, then dispatched once a slot is free`() =
        runTest {
            machine.limits = MachineLimits(maxConcurrentAgents = 1)
            // The last slot is held by another Loop process.
            machine.slots += "another-process"
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("t-1"))), runner, sink)

            assertEquals(emptyList(), orchestrator.tick().attempts)
            assertEquals(0, runner.calls.size)
            assertEquals(listOf<String?>("no_machine_slot"), sink.skipReasons())

            machine.slots -= "another-process"
            assertEquals(listOf("t-1"), orchestrator.tick().attempts.map { it.issueIdentifier })
        }

    @Test
    fun `a failed slot claim is a skip, never a launch`() =
        runTest {
            machine.limits = MachineLimits(maxConcurrentAgents = 4)
            machine.claimFailure = IllegalStateException("database is locked")
            val sink = FakeSink()
            val runner = FakeRunner()

            assertEquals(emptyList(), orchestrator(FakeTracker(listOf(issue("t-1"))), runner, sink).tick().attempts)
            assertEquals(0, runner.calls.size)
            assertEquals(listOf<String?>("no_machine_slot"), sink.skipReasons())
        }

    @Test
    fun `two projects on one machine never run more agents together than the machine cap`() =
        runTest {
            machine.limits = MachineLimits(maxConcurrentAgents = 1)
            val gate = CompletableDeferred<Unit>()
            val inFlight = mutableSetOf<String>()
            var peak = 0
            val gated = {
                FakeRunner { ctx, _ ->
                    flow {
                        inFlight += ctx.issue.identifier
                        peak = maxOf(peak, inFlight.size)
                        try {
                            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                            gate.await()
                            emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                        } finally {
                            inFlight -= ctx.issue.identifier
                        }
                    }
                }
            }
            val firstSink = FakeSink()
            val secondSink = FakeSink()
            val first = orchestrator(FakeTracker(listOf(issue("a-1"))), gated(), firstSink, project = "p1")
            val second = orchestrator(FakeTracker(listOf(issue("b-1"))), gated(), secondSink, project = "p2")

            val firstTick = async { first.tick() }
            runCurrent()
            val secondTick = async { second.tick() }
            runCurrent()

            assertEquals(setOf("a-1"), inFlight)
            assertEquals(emptyList(), secondTick.await().attempts)
            assertEquals(listOf<String?>("no_machine_slot"), secondSink.skipReasons())
            gate.complete(Unit)
            assertEquals(listOf("a-1"), firstTick.await().attempts.map { it.issueIdentifier })
            assertEquals(listOf("b-1"), second.tick().attempts.map { it.issueIdentifier })
            assertEquals(1, peak)
        }

    @Test
    fun `a crossed machine day budget skips the dispatches of every project, parks nothing, and lifts by itself`() =
        runTest {
            machine.limits = MachineLimits(perDayUsd = 5.0)
            machine.rows += otherProjectRow(costUsd = 6.0)
            val firstSink = FakeSink()
            val secondSink = FakeSink()
            val firstRunner = FakeRunner()
            val secondRunner = FakeRunner()
            val first = orchestrator(FakeTracker(listOf(issue("a-1"))), firstRunner, firstSink, project = "p1")
            val second = orchestrator(FakeTracker(listOf(issue("b-1"))), secondRunner, secondSink, project = "p2")

            assertEquals(emptyList(), first.tick().attempts)
            assertEquals(emptyList(), second.tick().attempts)
            assertEquals(0, firstRunner.calls.size + secondRunner.calls.size)
            assertEquals(listOf<String?>("machine_budget"), firstSink.skipReasons())
            assertEquals(listOf<String?>("machine_budget"), secondSink.skipReasons())
            assertEquals(emptyMap(), parked.records, "the machine budget parks nothing")

            // The other project's attempt leaves the 24 hour window: the budget lifts with no human step.
            advanceTimeBy(24.hours)
            assertEquals(listOf("a-1"), first.tick().attempts.map { it.issueIdentifier })
            assertEquals(listOf("b-1"), second.tick().attempts.map { it.issueIdentifier })
        }

    @Test
    fun `the machine day budget in tokens counts every project`() =
        runTest {
            machine.limits = MachineLimits(perDayTokens = 5_000)
            machine.rows += otherProjectRow(costUsd = null, tokens = 4_000)
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("a-1")))
            val orchestrator = orchestrator(tracker, sink = sink)

            // 4000 of 5000: room left, the dispatch goes.
            assertEquals(listOf("a-1"), orchestrator.tick().attempts.map { it.issueIdentifier })

            // a-1 now waits for its continuation; a new issue comes once the machine spent 6000 tokens.
            machine.rows += otherProjectRow(costUsd = null, tokens = 2_000)
            tracker.issues += issue("a-2")
            assertEquals(emptyList(), orchestrator.tick().attempts)
            assertEquals(listOf<String?>("machine_budget"), sink.skipReasons())
        }

    @Test
    fun `a running attempt is not cut when the machine day budget is crossed`() =
        runTest {
            machine.limits = MachineLimits(perDayUsd = 5.0)
            val gate = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        gate.await()
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("a-1"))), runner, sink)

            val tick = async { orchestrator.tick() }
            runCurrent()
            // Another project ends an attempt that crosses the budget while a-1 runs.
            machine.rows += otherProjectRow(costUsd = 9.0, finishedAt = FIXED_NOW)
            gate.complete(Unit)

            val attempt = tick.await().attempts.single()
            assertEquals(AttemptOutcome.Succeeded, attempt.outcome)
            assertTrue(sink.events.none { it.kind == "budget_reached" })
            assertEquals(emptyMap(), parked.records)
        }

    @Test
    fun `a malformed machine config fails the preflight naming the key and dispatches nothing`() =
        runTest {
            machine.limitsFailure =
                MachineConfigError(
                    "machine.max_concurrent_agents",
                    "/home/.loop/config.yaml: machine.max_concurrent_agents must be a positive integer, got 0",
                )
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("t-1"))), runner, sink)

            assertEquals(emptyList(), orchestrator.tick().attempts)
            assertEquals(0, runner.calls.size)
            val preflight = sink.events.single { it.kind == "preflight" }.data
            assertEquals("failed", preflight["result"])
            assertEquals("invalid_config", preflight["error"])
            assertTrue("machine.max_concurrent_agents" in preflight["message"].orEmpty(), preflight.toString())
            assertTrue(sink.events.none { it.kind == "config_reload_failed" }, "WORKFLOW.md itself is fine")

            // Read every tick: once the file is fixed, the next tick dispatches.
            machine.limitsFailure = null
            assertEquals(listOf("t-1"), orchestrator.tick().attempts.map { it.issueIdentifier })
        }

    @Test
    fun `a due retry with no machine slot is requeued, not failed`() =
        runTest {
            machine.limits = MachineLimits(maxConcurrentAgents = 1)
            val runner = FakeRunner { _, _ -> failingTurn() }
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("t-1"))), runner, sink)

            orchestrator.tick()
            assertEquals(1, runner.calls.size)
            machine.slots += "another-process"
            advanceTimeBy(RETRY_WAIT_MS)
            orchestrator.tick()

            assertEquals(1, runner.calls.size, "the retry found no machine slot")
            val scheduled = sink.events.filter { it.kind == "retry_scheduled" }.map { it.data["error"] }
            assertEquals("no available machine slots", scheduled.last(), scheduled.toString())
        }

    private companion object {
        /** Past the first retry's backoff (10 seconds). */
        const val RETRY_WAIT_MS = 11_000L
    }
}
