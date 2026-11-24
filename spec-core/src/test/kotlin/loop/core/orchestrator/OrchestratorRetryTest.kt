package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.Issue
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Retries, the attempt cap and parking (m1-07, spec 8.4 and 17.4, spec decisions answer 5). */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRetryTest {
    private val root: Path = createTempDirectory("loop-orchestrator-retry").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxAttempts: Int = 5,
        maxBackoffMs: Long = 3_600_000L,
        pollingIntervalMs: Long = 1000,
        hooks: HooksConfig = HooksConfig(),
        maxConcurrentAgents: Int = AgentConfig().maxConcurrentAgents,
        maxConcurrentAgentsByState: Map<String, Int> = emptyMap(),
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
            pollingIntervalMs = pollingIntervalMs,
            promptTemplate = PROMPT,
            config =
                WorkflowConfig(
                    agent =
                        AgentConfig(
                            maxConcurrentAgents = maxConcurrentAgents,
                            maxRetryBackoffMs = maxBackoffMs,
                            maxConcurrentAgentsByState = maxConcurrentAgentsByState,
                        ),
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                    hooks = hooks,
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner = failingRunner(),
        sink: FakeSink = FakeSink(),
        parked: FakeParkedIssues = FakeParkedIssues(),
        workflow: WorkflowFile = workflow(),
        runId: RunId = RunId("r1"),
        preflight: PreflightCheck = PreflightCheck { PreflightResult.Ok(workflow) },
        hookRunner: FakeHookRunner = FakeHookRunner(),
        loopVersion: String = "unknown",
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
            newId = { "a${++next}" },
            runId = runId,
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            preflight = preflight,
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            loopVersion = loopVersion,
        )
    }

    private fun failingRunner(): FakeRunner = FakeRunner { _, _ -> failingTurn() }

    private fun prompts(runner: FakeRunner): List<String> = runner.calls.map { it.second }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    private fun parkedRecord(
        issueId: String = ISSUE_ID,
        identifier: String = "m1-02",
        state: String = "todo",
        labels: Set<String> = setOf("ready-for-agent"),
        runId: RunId? = RunId("r0"),
    ): ParkedIssue = ParkedIssue(PROJECT, issueId, identifier, ParkReason.MaxAttempts, state, labels, FIXED_NOW, runId)

    private fun unavailable(): TrackerError = TrackerError.Unavailable(IOException("tracker down"))

    // Continuation retry after a normal exit, spec 16.6 and 17.4.

    private fun succeedingRunner(): FakeRunner = FakeRunner { _, _ -> succeedingTurn() }

    @Test
    fun `normal exit schedules continuation retry after 1 s`() =
        runTest {
            val runner = succeedingRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()

            val scheduled = sink.ofKind("retry_scheduled").single()
            assertEquals(
                mapOf(
                    "kind" to "continuation",
                    "attempt" to "1",
                    "delay_ms" to "1000",
                    "due_at" to (FIXED_NOW + 1_000.milliseconds).toString(),
                    "failures" to "0",
                    "no_progress" to "1",
                    "max_continuations" to "3",
                ),
                scheduled.data,
            )
            assertEquals("a1", scheduled.attemptId?.value)
            val retryLine = logLines.single { "event=retry" in it && "kind=continuation" in it }
            assertTrue("level=info" in retryLine, retryLine)
            assertFalse("level=warn" in retryLine, retryLine)
            advanceTimeBy(999)
            orchestrator.tick()
            assertEquals(1, runner.calls.size, "dispatched before the continuation was due")
            advanceTimeBy(1)
            orchestrator.tick()
            assertEquals(2, runner.calls.size)
        }

    @Test
    fun `continued attempt renders attempt 1`() =
        runTest {
            val runner = succeedingRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(listOf("m1-02 first", "m1-02 retry 1"), prompts(runner))
        }

    @Test
    fun `first dispatch renders attempt null`() =
        runTest {
            val runner = succeedingRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            assertEquals(listOf("m1-02 first"), prompts(runner))
        }

    // The Loop version on every attempt, m1-12.

    @Test
    fun `first and retry attempts record the loop version`() =
        runTest {
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink, loopVersion = "3f2a9c1d7e4b-dirty")

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            val started = sink.ofKind("attempt_started")
            assertEquals(listOf("a1", "a2"), started.map { it.attemptId?.value })
            assertEquals(listOf("3f2a9c1d7e4b-dirty", "3f2a9c1d7e4b-dirty"), started.map { it.data["loop_version"] })
        }

    @Test
    fun `m4-42 attempt_started carries the ledger setup of the attempt`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val data = sink.ofKind("attempt_started").single().data
            val setup = LedgerMeter.setupOf(workflow(), issueId = "m1-02")
            assertEquals(setup.tracker, data["tracker"])
            assertEquals(setup.runner, data["runner"])
            assertEquals(setup.templateVersion, data["template_version"])
            assertEquals(setup.kind, data["kind"])
            assertEquals(null, data["model"])
        }

    @Test
    fun `failure after continuation gets attempt 2 after 20 s`() =
        runTest {
            var turns = 0
            val runner = FakeRunner { _, _ -> if (++turns == 1) succeedingTurn() else failingTurn() }
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            val scheduled = sink.ofKind("retry_scheduled")
            assertEquals(listOf("continuation", "error"), scheduled.map { it.data["kind"] })
            assertEquals(listOf("1", "2"), scheduled.map { it.data["attempt"] })
            assertEquals("20000", scheduled.last().data["delay_ms"])
            assertEquals("exit_code=1", scheduled.last().data["error"])
        }

    // Backoff, spec 8.4 and 17.4.

    @Test
    fun `a failing issue retries with exponential backoff and is not dispatched before due`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            for ((index, delay) in listOf(10_000L, 20_000L, 40_000L).withIndex()) {
                orchestrator.tick()
                advanceTimeBy(delay - 1)
                orchestrator.tick()
                assertEquals(index + 1, runner.calls.size, "dispatched before the retry was due")
                advanceTimeBy(1)
                orchestrator.tick()
                assertEquals(index + 2, runner.calls.size, "not dispatched when the retry was due")
            }

            val scheduled = sink.ofKind("retry_scheduled")
            assertEquals(listOf("10000", "20000", "40000", "80000"), scheduled.map { it.data["delay_ms"] })
            assertEquals(listOf("1", "2", "3", "4"), scheduled.map { it.data["attempt"] })
            assertEquals(listOf("1", "2", "3", "4"), scheduled.map { it.data["failures"] })
            assertEquals(listOf("a1", "a2", "a3", "a4"), scheduled.map { it.attemptId?.value })
        }

    @Test
    fun `retry scheduled carries the attempt the delay the due time the error and the failures`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val scheduled = sink.ofKind("retry_scheduled").single()
            assertEquals(
                mapOf(
                    "kind" to "error",
                    "attempt" to "1",
                    "delay_ms" to "10000",
                    "due_at" to (FIXED_NOW + 10_000.milliseconds).toString(),
                    "error" to "exit_code=1",
                    "failures" to "1",
                ),
                scheduled.data,
            )
            assertEquals(RunId("r1"), scheduled.runId)
            assertEquals(ISSUE_ID, scheduled.issueId)
            assertEquals("m1-02", scheduled.issueIdentifier)
            assertEquals("a1", scheduled.attemptId?.value)
        }

    @Test
    fun `backoff is capped by max retry backoff ms`() =
        runTest {
            val sink = FakeSink()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink, workflow = workflow(maxBackoffMs = 15_000L))

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()
            advanceTimeBy(15_000)
            orchestrator.tick()

            assertEquals(listOf("10000", "15000", "15000"), sink.ofKind("retry_scheduled").map { it.data["delay_ms"] })
        }

    @Test
    fun `a retried dispatch renders the attempt variable`() =
        runTest {
            val runner = failingRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner)

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf("m1-02 first", "m1-02 retry 1"), prompts(runner))
        }

    // Attempt cap and parking, spec decisions answer 5.

    @Test
    fun `the fifth consecutive failure parks the issue`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked, workflow(maxBackoffMs = 1000))

            orchestrator.tick()
            repeat(4) {
                advanceTimeBy(1000)
                orchestrator.tick()
            }

            val event = sink.ofKind("issue_parked").single()
            assertEquals(mapOf("reason" to "max_attempts", "failures" to "5", "error" to "exit_code=1"), event.data)
            assertEquals("a5", event.attemptId?.value)
            assertEquals(ISSUE_ID, event.issueId)
            assertEquals(4, sink.ofKind("retry_scheduled").size)
            assertTrue(sink.ofKind("retry_scheduled").none { it.attemptId?.value == "a5" })
            assertEquals(
                ParkedIssue(
                    projectId = PROJECT,
                    issueId = ISSUE_ID,
                    identifier = "m1-02",
                    reason = ParkReason.MaxAttempts,
                    state = "todo",
                    labels = setOf("ready-for-agent"),
                    parkedAt = FIXED_NOW + 4_000.milliseconds,
                    runId = RunId("r1"),
                ),
                parked.records.values.single(),
            )
        }

    @Test
    fun `a parked issue is not dispatched by further ticks`() =
        runTest {
            val runner = failingRunner()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, workflow = workflow(maxBackoffMs = 1000))

            orchestrator.tick()
            repeat(4) {
                advanceTimeBy(1000)
                orchestrator.tick()
            }
            advanceTimeBy(3_600_000)
            orchestrator.tick()
            orchestrator.tick()

            assertEquals(5, runner.calls.size)
        }

    @Test
    fun `a normal exit resets the failure counter`() =
        runTest {
            val outcomes = ArrayDeque(listOf(false, false, true, false, false, false, false))
            val runner = FakeRunner { _, _ -> if (outcomes.removeFirst()) succeedingTurn() else failingTurn() }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked, workflow(maxBackoffMs = 1000))

            orchestrator.tick()
            repeat(6) {
                advanceTimeBy(1000)
                orchestrator.tick()
            }

            assertEquals(7, runner.calls.size)
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
            assertTrue(parked.records.isEmpty())
            assertEquals("4", sink.ofKind("retry_scheduled").last().data["failures"])
        }

    @Test
    fun `a failed preflight launches no attempt and does not count`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val workflow = workflow(maxAttempts = 2)
            var checks = 0
            val preflight =
                PreflightCheck {
                    if (checks++ < 3) PreflightResult.Failed("invalid_config", "broken") else PreflightResult.Ok(workflow)
                }
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, workflow = workflow, preflight = preflight)

            repeat(3) { orchestrator.tick() }
            assertEquals(0, runner.calls.size)
            orchestrator.tick()

            assertEquals(1, runner.calls.size)
            assertEquals("1", sink.ofKind("retry_scheduled").single().data["failures"])
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
        }

    @Test
    fun `a cancel during an attempt is not a failure and schedules no retry`() =
        runTest {
            var calls = 0
            val runner =
                FakeRunner { _, _ ->
                    if (calls++ == 0) {
                        flow {
                            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                            awaitCancellation()
                        }
                    } else {
                        succeedingTurn()
                    }
                }
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            val job = launch { orchestrator.tick() }
            runCurrent()
            job.cancelAndJoin()

            assertEquals(emptyList(), sink.ofKind("retry_scheduled"))
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
            orchestrator.tick()
            assertEquals(2, runner.calls.size)
        }

    // Unparking, plan review item 8.

    @Test
    fun `a state change of a parked candidate unparks and dispatches it`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues(parkedRecord(state = "todo"))

            orchestrator(FakeTracker(listOf(issue("m1-02", state = "in_progress"))), runner, sink, parked).tick()

            val event = sink.ofKind("issue_unparked").single()
            assertEquals(mapOf("reason" to "tracker_changed", "from_state" to "todo", "to_state" to "in_progress"), event.data)
            assertEquals(ISSUE_ID, event.issueId)
            assertNull(event.attemptId)
            assertTrue(parked.records.isEmpty())
            assertEquals(1, runner.calls.size)
        }

    @Test
    fun `a label change of a parked candidate unparks and dispatches it`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues(parkedRecord(labels = setOf("ready-for-agent")))

            orchestrator(FakeTracker(listOf(issue("m1-02", labels = listOf("ready-for-agent", "backend")))), runner, sink, parked)
                .tick()

            assertEquals("tracker_changed", sink.ofKind("issue_unparked").single().data["reason"])
            assertTrue(parked.records.isEmpty())
            assertEquals(1, runner.calls.size)
        }

    @Test
    fun `a parked candidate with the same state and labels stays parked`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues(parkedRecord())

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked).tick()

            assertEquals(0, runner.calls.size)
            assertEquals(1, parked.records.size)
            assertEquals(emptyList(), sink.ofKind("issue_unparked"))
        }

    @Test
    fun `a parked issue outside the candidates whose state changed is unparked by a by id fetch`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues(parkedRecord(state = "todo"))
            val tracker = FakeTracker(listOf(issue("m1-02", state = "review"))).apply { filterByStates = true }
            val orchestrator = orchestrator(tracker, runner, sink, parked)

            orchestrator.tick()

            assertTrue(listOf(ISSUE_ID) in tracker.requestedIds, tracker.requestedIds.toString())
            assertEquals(
                mapOf("reason" to "tracker_changed", "from_state" to "todo", "to_state" to "review"),
                sink.ofKind("issue_unparked").single().data,
            )
            assertTrue(parked.records.isEmpty())
            assertEquals(0, runner.calls.size)

            tracker.issues[0] = issue("m1-02", state = "todo")
            orchestrator.tick()
            assertEquals(1, runner.calls.size)
        }

    @Test
    fun `a parked issue not found by id has its record dropped`() =
        runTest {
            val parked = FakeParkedIssues(parkedRecord(issueId = "id-gone", identifier = "gone"))

            orchestrator(FakeTracker(emptyList()), parked = parked).tick()

            assertTrue(parked.records.isEmpty())
        }

    @Test
    fun `a failed by id fetch keeps the issue parked`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues(parkedRecord(state = "todo"))
            val tracker =
                FakeTracker(listOf(issue("m1-02", state = "review"))).apply {
                    filterByStates = true
                    fetchByIdsFailure = unavailable()
                }
            val orchestrator = orchestrator(tracker, runner, sink, parked)

            orchestrator.tick()

            assertEquals(1, parked.records.size)
            assertEquals(emptyList(), sink.ofKind("issue_unparked"))

            tracker.fetchByIdsFailure = null
            orchestrator.tick()
            assertTrue(parked.records.isEmpty())
        }

    @Test
    fun `unparking as loop resume does makes the issue dispatchable with a fresh counter`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked, workflow(maxAttempts = 2, maxBackoffMs = 1000))

            orchestrator.tick()
            advanceTimeBy(1000)
            orchestrator.tick()
            advanceTimeBy(1000)
            orchestrator.tick()
            assertEquals(2, runner.calls.size)

            assertNotNull(parked.unpark(PROJECT, ISSUE_ID).getOrThrow())
            orchestrator.tick()

            assertEquals(3, runner.calls.size)
            assertEquals("m1-02 first", prompts(runner).last())
            val scheduled = sink.ofKind("retry_scheduled").last()
            assertEquals("1", scheduled.data["failures"])
            assertEquals("1", scheduled.data["attempt"])
            assertTrue(parked.records.isEmpty())
        }

    // Review round 1: a park whose store write fails stays parked in memory.

    private suspend fun TestScope.assertParkSurvivesStoreFailure(
        fail: (FakeParkedIssues) -> Unit,
        clear: (FakeParkedIssues) -> Unit,
        storeError: String,
    ) {
        val runner = failingRunner()
        val sink = FakeSink()
        val parked = FakeParkedIssues().also(fail)
        val orchestrator =
            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked, workflow(maxAttempts = 1, maxBackoffMs = 1000))

        orchestrator.tick()

        val event = sink.ofKind("issue_parked").single()
        assertEquals(
            mapOf("reason" to "max_attempts", "failures" to "1", "error" to "exit_code=1", "store_error" to storeError),
            event.data,
        )
        assertTrue(parked.records.isEmpty())
        advanceTimeBy(3_600_000)
        orchestrator.tick()
        orchestrator.tick()
        assertEquals(1, runner.calls.size, "dispatched although parked")

        clear(parked)
        orchestrator.tick()

        assertEquals(1, parked.records.size)
        assertEquals(
            "todo",
            parked.records.values
                .single()
                .state,
        )
        orchestrator.tick()
        assertEquals(1, runner.calls.size)
        assertEquals(1, sink.ofKind("issue_parked").size)
    }

    @Test
    fun `a park whose store write fails keeps the issue parked and retries the write`() =
        runTest {
            assertParkSurvivesStoreFailure(
                fail = { it.parkFailure = IOException("disk full") },
                clear = { it.parkFailure = null },
                storeError = "disk full",
            )
        }

    @Test
    fun `a park whose store write throws keeps the issue parked and retries the write`() =
        runTest {
            assertParkSurvivesStoreFailure(
                fail = { it.parkThrows = IllegalStateException("store broke") },
                clear = { it.parkThrows = null },
                storeError = "store broke",
            )
        }

    // Review round 1: the park snapshot comes from the refreshed issue.

    @Test
    fun `labels changed by the agent during the parking attempt are in the snapshot`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        tracker.issues[0] = issue("m1-02", labels = listOf("ready-for-agent", "backend"))
                        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
                    }
                }
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(tracker, runner, parked = parked, workflow = workflow(maxAttempts = 1))

            orchestrator.tick()
            assertEquals(
                setOf("ready-for-agent", "backend"),
                parked.records.values
                    .single()
                    .labels,
            )
            orchestrator.tick()

            assertEquals(1, runner.calls.size)
            assertEquals(1, parked.records.size)
        }

    // Review round 1: a kept counter resets once the issue leaves the active states.

    /**
     * Fails once, drops the retry as not eligible (counter kept), then runs [between]
     * before restoring the issue; returns the failures counted by its next failure.
     */
    private suspend fun TestScope.failuresAfterNotEligibleDrop(between: suspend (FakeTracker, Orchestrator) -> Unit): String {
        val runner = failingRunner()
        val sink = FakeSink()
        val tracker = FakeTracker(listOf(issue("m1-02"))).apply { filterByStates = true }
        val orchestrator = orchestrator(tracker, runner, sink)

        orchestrator.tick()
        tracker.issues[0] = issue("m1-02", labels = listOf("other"))
        advanceTimeBy(10_000)
        orchestrator.tick()
        assertEquals("not_eligible", sink.ofKind("retry_dropped").single().data["reason"])

        between(tracker, orchestrator)
        tracker.issues[0] = issue("m1-02")
        orchestrator.tick()

        assertEquals(2, runner.calls.size)
        return sink.ofKind("retry_scheduled").last().data["failures"]!!
    }

    @Test
    fun `a kept counter resets when the issue leaves the active candidates`() =
        runTest {
            val failures =
                failuresAfterNotEligibleDrop { tracker, orchestrator ->
                    tracker.issues[0] = issue("m1-02", state = "review", labels = listOf("other"))
                    orchestrator.tick()
                }

            assertEquals("1", failures)
        }

    @Test
    fun `a kept counter stays while the issue is a candidate that is not eligible`() =
        runTest {
            val failures = failuresAfterNotEligibleDrop { _, orchestrator -> orchestrator.tick() }

            assertEquals("2", failures)
        }

    // Restart, acceptance criterion 5.

    @Test
    fun `a parked issue is not dispatched after a restart with another run id`() =
        runTest {
            val runner = failingRunner()
            val parked = FakeParkedIssues()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val workflow = workflow(maxAttempts = 1)

            orchestrator(tracker, runner, parked = parked, workflow = workflow).tick()
            assertEquals(1, parked.records.size)
            orchestrator(tracker, runner, parked = parked, workflow = workflow, runId = RunId("r2")).tick()

            assertEquals(1, runner.calls.size)
        }

    @Test
    fun `a parked record without a run id keeps the issue parked`() =
        runTest {
            val runner = FakeRunner()
            val parked = FakeParkedIssues(parkedRecord(runId = null))

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, parked = parked).tick()

            assertEquals(0, runner.calls.size)
            assertEquals(1, parked.records.size)
        }

    // Input required, acceptance criterion 4.

    @Test
    fun `input required fails the attempt with its reason and schedules a retry`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnInputRequired(SESSION, TURN, reason = "permission prompt for Bash"))
                    }
                }

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            val required = sink.ofKind("turn_input_required").single()
            assertEquals("a1", required.attemptId?.value)
            assertEquals("permission prompt for Bash", required.data["reason"])
            val finished = sink.ofKind("attempt_finished").single()
            assertEquals("failed", finished.data["outcome"])
            assertEquals("turn_input_required: permission prompt for Bash", finished.data["reason"])
            val scheduled = sink.ofKind("retry_scheduled").single()
            assertEquals("turn_input_required: permission prompt for Bash", scheduled.data["error"])
            val order = sink.events.map { it.kind }
            assertTrue(order.indexOf("turn_input_required") < order.indexOf("attempt_finished"), order.toString())
            assertTrue(order.indexOf("attempt_finished") < order.indexOf("retry_scheduled"), order.toString())
        }

    // Retry fire is a by id refresh, plan review item 1.

    @Test
    fun `a failed retry refresh reschedules the next attempt without counting a failure`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val orchestrator = orchestrator(tracker, runner, sink)

            orchestrator.tick()
            tracker.fetchByIdsFailure = unavailable()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(1, runner.calls.size)
            val rescheduled = sink.ofKind("retry_scheduled").last()
            assertEquals("2", rescheduled.data["attempt"])
            assertEquals("20000", rescheduled.data["delay_ms"])
            assertEquals("retry refresh failed", rescheduled.data["error"])
            assertEquals("1", rescheduled.data["failures"])

            tracker.fetchByIdsFailure = null
            advanceTimeBy(19_999)
            orchestrator.tick()
            assertEquals(1, runner.calls.size)
            advanceTimeBy(1)
            orchestrator.tick()
            assertEquals(listOf("m1-02 first", "m1-02 retry 2"), prompts(runner))
        }

    @Test
    fun `a retry whose issue is gone is dropped`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val orchestrator = orchestrator(tracker, runner, sink)

            orchestrator.tick()
            tracker.issues.clear()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals("not_found", sink.ofKind("retry_dropped").single().data["reason"])
            assertEquals(1, runner.calls.size)
            tracker.issues += issue("m1-02")
            orchestrator.tick()
            assertEquals(listOf("m1-02 first", "m1-02 first"), prompts(runner))
        }

    @Test
    fun `a retry whose issue became terminal is dropped and the counter reset`() =
        runTest {
            assertRetryDropped(movedTo = issue("m1-02", state = "done"), reason = "terminal", failuresAfter = "1")
        }

    @Test
    fun `a retry dropped for a terminal issue runs before_remove and removes the workspace`() =
        runTest {
            val hookRunner = FakeHookRunner()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val orchestrator =
                orchestrator(
                    tracker,
                    workflow = workflow(hooks = HooksConfig(beforeRemove = "sh before_remove.sh")),
                    hookRunner = hookRunner,
                )
            orchestrator.tick()
            val workspace = root.resolve("workspaces/m1-02")
            assertTrue(workspace.exists())

            tracker.issues[0] = issue("m1-02", state = "done")
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(workspace, hookRunner.calls.single { it.hook == HookName.BEFORE_REMOVE }.cwd)
            assertFalse(workspace.exists())
        }

    @Test
    fun `handoff state needs_info keeps the workspace when the continuation retry fires`() =
        runTest { assertHandoffKeepsWorkspace("needs_info") }

    @Test
    fun `handoff state review keeps the workspace when the continuation retry fires`() = runTest { assertHandoffKeepsWorkspace("review") }

    /** Characterization: unlike a terminal state, a handoff state never cleans the workspace. */
    private suspend fun TestScope.assertHandoffKeepsWorkspace(handoffState: String) {
        val hookRunner = FakeHookRunner()
        val sink = FakeSink()
        val tracker = FakeTracker(listOf(issue("m1-02")))
        val orchestrator =
            orchestrator(
                tracker,
                sink = sink,
                workflow = workflow(hooks = HooksConfig(beforeRemove = "sh before_remove.sh")),
                hookRunner = hookRunner,
            )
        orchestrator.tick()
        val workspace = root.resolve("workspaces/m1-02")
        assertTrue(workspace.exists())

        tracker.issues[0] = issue("m1-02", state = handoffState)
        advanceTimeBy(10_000)
        orchestrator.tick()

        assertEquals("not_active", sink.ofKind("retry_dropped").single().data["reason"])
        assertTrue(workspace.exists())
        assertTrue(hookRunner.calls.none { it.hook == HookName.BEFORE_REMOVE })
    }

    @Test
    fun `a retry whose issue left the active states is dropped and the counter reset`() =
        runTest {
            assertRetryDropped(movedTo = issue("m1-02", state = "review"), reason = "not_active", failuresAfter = "1")
        }

    @Test
    fun `a retry whose issue is active but not eligible is dropped and the counter kept`() =
        runTest {
            assertRetryDropped(movedTo = issue("m1-02", labels = listOf("other")), reason = "not_eligible", failuresAfter = "2")
        }

    /**
     * Fails once, changes the issue to [movedTo] before the retry fires, checks the
     * retry is dropped with [reason], then restores the issue: it is dispatched as a
     * first run and its next failure counts [failuresAfter].
     */
    private suspend fun TestScope.assertRetryDropped(
        movedTo: Issue,
        reason: String,
        failuresAfter: String,
    ) {
        val runner = failingRunner()
        val sink = FakeSink()
        val tracker = FakeTracker(listOf(issue("m1-02")))
        val orchestrator = orchestrator(tracker, runner, sink)

        orchestrator.tick()
        tracker.issues[0] = movedTo
        advanceTimeBy(10_000)
        orchestrator.tick()

        assertEquals(reason, sink.ofKind("retry_dropped").single().data["reason"])
        assertEquals(1, runner.calls.size)

        tracker.issues[0] = issue("m1-02")
        orchestrator.tick()
        assertEquals(listOf("m1-02 first", "m1-02 first"), prompts(runner))
        assertEquals(failuresAfter, sink.ofKind("retry_scheduled").last().data["failures"])
    }

    // Due retries before new candidates (bl-34, deviation from spec 8.2 and 16.6).

    @Test
    fun `with one slot a due retry of priority 3 is dispatched before a new candidate of priority 1`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02", priority = 3)))
            val orchestrator = orchestrator(tracker, runner, sink, workflow = workflow(maxConcurrentAgents = 1))

            orchestrator.tick()
            tracker.issues += issue("m1-05", priority = 1)
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf("m1-02 first", "m1-02 retry 1"), prompts(runner))
            val skipped = sink.ofKind("candidate_skipped").single { it.issueIdentifier == "m1-05" }
            assertEquals("no_available_slots", skipped.data["reason"])
        }

    @Test
    fun `with two due retries and one slot the retry due first is dispatched`() =
        runTest {
            val runner = failingRunner()
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02", priority = 3)))
            val orchestrator = orchestrator(tracker, runner, sink, workflow = workflow(maxConcurrentAgents = 1))

            // m1-02 is due at 10 s, m1-03 at 15 s, so both are due at 15 s.
            orchestrator.tick()
            advanceTimeBy(5_000)
            tracker.issues += issue("m1-03", priority = 1)
            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf("m1-02 first", "m1-03 first", "m1-02 retry 1"), prompts(runner))
            val requeue = sink.ofKind("retry_scheduled").last { it.issueIdentifier == "m1-03" }
            assertEquals("no available orchestrator slots", requeue.data["error"])
        }

    @Test
    fun `a slot requeue keeps the attempt and is due at the next tick`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { ctx, _ ->
                    if (ctx.issue.identifier == "m1-02") {
                        failingTurn()
                    } else {
                        flow {
                            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                            gate.await()
                            emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                        }
                    }
                }
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val orchestrator = orchestrator(tracker, runner, sink, workflow = workflow(maxConcurrentAgents = 1))

            // m1-02 fails and is due at 10 s; m1-03 takes the only slot and holds it.
            orchestrator.tick()
            tracker.issues += issue("m1-03")
            val holder = launch { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(10_000)
            orchestrator.tick()

            val requeue = sink.ofKind("retry_scheduled").last { it.issueIdentifier == "m1-02" }
            assertEquals("no available orchestrator slots", requeue.data["error"])
            assertEquals("1", requeue.data["attempt"])
            // The poll interval of the test workflow: the daemon wakes for it at the next poll.
            assertEquals("1000", requeue.data["delay_ms"])
            assertEquals((FIXED_NOW + 11_000.milliseconds).toString(), requeue.data["due_at"])
            assertEquals("1", requeue.data["failures"])

            gate.complete(Unit)
            holder.join()
            advanceTimeBy(1_000)
            orchestrator.tick()

            // m1-03's continuation is due at the same time, m1-02 fell due first and takes the slot.
            assertEquals(listOf("m1-02 first", "m1-03 first", "m1-02 retry 1"), prompts(runner))
        }

    @Test
    fun `a due retry blocked by its state's cap is requeued and another state's candidate takes the free slot (bl-77 finding 2)`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { ctx, _ ->
                    when (ctx.issue.identifier) {
                        "m1-02" -> failingTurn()
                        else ->
                            flow {
                                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                                gate.await()
                                emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                            }
                    }
                }
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02", state = "in_progress")))
            val agents = workflow(maxConcurrentAgents = 3, maxConcurrentAgentsByState = mapOf("in_progress" to 1))
            val orchestrator = orchestrator(tracker, runner, sink, workflow = agents)

            // m1-02 fails and is due at 10 s; m1-03 takes the one in_progress slot and holds it.
            orchestrator.tick()
            tracker.issues += issue("m1-03", state = "in_progress")
            val holder = launch { orchestrator.tick() }
            runCurrent()
            tracker.issues += issue("m1-04")
            advanceTimeBy(10_000)
            val other = launch { orchestrator.tick() }
            runCurrent()

            // Two of the three slots are free, but in_progress is at its cap: m1-04, in todo, gets one.
            assertEquals(listOf("m1-02 first", "m1-03 first", "m1-04 first"), prompts(runner))
            val requeue = sink.ofKind("retry_scheduled").last { it.issueIdentifier == "m1-02" }
            assertEquals("no available orchestrator slots", requeue.data["error"])
            assertEquals("1", requeue.data["attempt"])
            gate.complete(Unit)
            holder.join()
            other.join()
        }

    @Test
    fun `the daemon gives the freed slot to the waiting retry before the holder's continuation and a new candidate`() =
        runTest { assertWaitingRetryTakesFreedSlot(holderFails = false) }

    @Test
    fun `the daemon gives the freed slot to the waiting retry before the holder's failure retry and a new candidate`() =
        runTest { assertWaitingRetryTakesFreedSlot(holderFails = true) }

    /**
     * One slot and the poll interval of `WORKFLOW.md`, 30 s. m1-02 fails and falls due at 10 s while m1-03
     * holds the slot, so it is requeued. m1-03 then ends at 15 s, normally (continuation due at 16 s) or failing
     * (retry due at 25 s), and a new candidate of priority 1 appears. That early tick, before the requeued due
     * time of 40 s, gives the slot to m1-02, which fell due first (bl-34 send back 1).
     */
    private suspend fun TestScope.assertWaitingRetryTakesFreedSlot(holderFails: Boolean) {
        val gate = CompletableDeferred<Unit>()
        val started = mutableMapOf<String, Int>()
        val runner =
            FakeRunner { ctx, _ ->
                val identifier = ctx.issue.identifier
                val run = started.merge(identifier, 1, Int::plus)!!
                when {
                    // Every later attempt holds the slot, so the dispatch order stays readable.
                    run > 1 -> flow { awaitCancellation() }
                    identifier == "m1-02" -> failingTurn()
                    else ->
                        flow {
                            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                            gate.await()
                            if (holderFails) {
                                emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
                            } else {
                                emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                            }
                        }
                }
            }
        val sink = FakeSink()
        val tracker = FakeTracker(listOf(issue("m1-02", priority = 3)))
        val orchestrator =
            orchestrator(tracker, runner, sink, workflow = workflow(pollingIntervalMs = 30_000, maxConcurrentAgents = 1))

        // m1-02 fails at 0 s and is due at 10 s; m1-03 takes the slot in between and holds it.
        launch { orchestrator.tick() }
        runCurrent()
        tracker.issues += issue("m1-03", priority = 3)
        val holder = launch { orchestrator.tick() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        val daemon = launch { orchestrator.runUntilCancelled() }
        runCurrent()
        val requeue = sink.ofKind("retry_scheduled").last { it.issueIdentifier == "m1-02" }
        assertEquals("no available orchestrator slots", requeue.data["error"])
        assertEquals((FIXED_NOW + 40_000.milliseconds).toString(), requeue.data["due_at"])

        tracker.issues += issue("m1-04", priority = 1)
        advanceTimeBy(5_000)
        gate.complete(Unit)
        holder.join()
        // The holder's retry falls due at 16 s or 25 s, before the next poll at 40 s.
        advanceTimeBy(if (holderFails) 10_000 else 1_000)
        runCurrent()

        assertEquals(listOf("m1-02 first", "m1-03 first", "m1-02 retry 1"), prompts(runner))
        daemon.cancel()
    }

    // Daemon sleep, plan review item 9.

    @Test
    fun `run until cancelled fires a due retry before the next poll`() =
        runTest {
            val runner = failingRunner()
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, workflow = workflow(pollingIntervalMs = 60_000))
            val job = launch { orchestrator.runUntilCancelled() }

            runCurrent()
            assertEquals(1, runner.calls.size)
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(1, runner.calls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, runner.calls.size)

            job.cancelAndJoin()
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val ISSUE_ID = "id-m1-02"
        const val PROMPT = "{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
    }
}
