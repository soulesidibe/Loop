package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.Claim
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RetryState
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.verify.CheckRunner
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * m2-02: the retry queue, its counters and the claims go to the store, so a new orchestrator (a restart)
 * picks up where the last one stopped. Each test builds a second orchestrator on the same fake stores.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRestartTest {
    private val root: Path = createTempDirectory("loop-orchestrator-restart").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxAttempts: Int = 5,
        maxContinuations: Int = 3,
        pollingIntervalMs: Long = 60_000,
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
                    agent = AgentConfig(maxRetryBackoffMs = 3_600_000L),
                    retries = RetriesConfig(maxAttempts = maxAttempts, maxContinuations = maxContinuations),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        retryStates: FakeRetryStates,
        claims: FakeClaims = FakeClaims(),
        sink: FakeSink = FakeSink(),
        workflow: WorkflowFile = workflow(),
        runId: RunId = RunId("r1"),
        idPrefix: String = "a",
        parked: FakeParkedIssues = FakeParkedIssues(),
        checks: CheckRunner = CheckRunner.NONE,
        waiting: FakeWaitingIssues = FakeWaitingIssues(),
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "$idPrefix${++next}" },
            runId = runId,
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            retryStates = retryStates,
            claims = claims,
            checkRunnerFor = { checks },
            waiting = waiting,
        )
    }

    private fun failingRunner(): FakeRunner = FakeRunner { _, _ -> failingTurn() }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `three restored verify waits with max_concurrent_agents 1 rerun their builds one at a time in stored order (bl-188)`() =
        runTest {
            val identifiers = listOf("bl-3", "bl-1", "bl-2")
            val tracker = FakeTracker(identifiers.map { issue(it) })
            val stored =
                identifiers.mapIndexed { n, identifier ->
                    Files.createDirectories(root.resolve("workspaces/$identifier"))
                    WaitingIssue(
                        projectId = PROJECT,
                        issueId = "id-$identifier",
                        identifier = identifier,
                        reason = WaitReason.Verify,
                        attemptId = AttemptId("dead$n"),
                        runId = RunId("r0"),
                        since = FIXED_NOW,
                        data = mapOf("to" to "review"),
                    )
                }
            val waiting = FakeWaitingIssues(*stored.toTypedArray())
            val checks = HeldBuilds()
            val base = workflow()
            val workflow =
                base.copy(
                    config =
                        base.config.copy(
                            agent = base.config.agent.copy(maxConcurrentAgents = 1),
                            kinds =
                                KindsConfig(
                                    KindConfig(
                                        listOf(CheckConfig.Command("./gradlew build")),
                                        delivery = DeliveryConfig(DeliveryPolicy.Auto),
                                    ),
                                ),
                        ),
                )
            val job =
                launch {
                    orchestrator(tracker, failingRunner(), FakeRetryStates(), workflow = workflow, checks = checks, waiting = waiting)
                        .runUntilCancelled()
                }
            runCurrent()

            for (count in 1..3) {
                assertEquals(count, checks.started.size, "build $count runs alone")
                assertEquals(1, checks.running)
                checks.releaseNext()
                runCurrent()
            }

            assertEquals(identifiers, checks.started.map { it.fileName.toString() })
            assertEquals(1, checks.most)
            assertEquals(identifiers.map { "id-$it" }, tracker.moves.map { it.first }, "each rerun verified and applied its move")
            assertEquals(emptyMap(), waiting.records)
            job.cancelAndJoin()
        }

    @Test
    fun `a failure writes the queued retry and the failure count to the store`() =
        runTest {
            val states = FakeRetryStates()
            orchestrator(FakeTracker(listOf(issue("m1-02"))), failingRunner(), states).tick()

            assertEquals(
                RetryState(PROJECT, ISSUE_ID, "m1-02", QueuedRetry(1, FIXED_NOW + 10_000.milliseconds, "exit_code=1"), 1, 0),
                states.records.values.single(),
            )
        }

    @Test
    fun `a retry queued before a restart fires at its stored due time, not at once`() =
        runTest {
            val states = FakeRetryStates()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            orchestrator(tracker, failingRunner(), states).tick()
            val dueAt = FIXED_NOW + 10_000.milliseconds

            advanceTimeBy(4_000)
            val runner = failingRunner()
            val restarted = orchestrator(tracker, runner, states, runId = RunId("r2"), idPrefix = "b")
            val job = launch { restarted.runUntilCancelled() }
            runCurrent()
            assertEquals(0, runner.calls.size, "a restart must not dispatch a waiting retry at once")
            advanceTimeBy(5_999)
            runCurrent()
            assertEquals(0, runner.calls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, runner.calls.size)
            assertEquals(dueAt, SchedulerClock(testScheduler).now())
            assertEquals("m1-02 retry 1", runner.calls.single().second)

            job.cancelAndJoin()
        }

    @Test
    fun `a restored retry is recorded in the new run as restored`() =
        runTest {
            val states = FakeRetryStates()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            orchestrator(tracker, failingRunner(), states).tick()

            advanceTimeBy(4_000)
            val sink = FakeSink()
            orchestrator(tracker, failingRunner(), states, sink = sink, runId = RunId("r2")).tick()

            val restored = sink.ofKind("retry_scheduled").single()
            assertEquals(RunId("r2"), restored.runId)
            assertEquals("true", restored.data["restored"])
            assertEquals("1", restored.data["attempt"])
            assertEquals((FIXED_NOW + 10_000.milliseconds).toString(), restored.data["due_at"])
            assertEquals("6000", restored.data["delay_ms"])
            assertEquals("1", restored.data["failures"])
        }

    @Test
    fun `the failure counter survives a restart and max_attempts parks on the total`() =
        runTest {
            val states = FakeRetryStates()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            orchestrator(tracker, failingRunner(), states, workflow = workflow(maxAttempts = 2)).tick()

            advanceTimeBy(10_000)
            val sink = FakeSink()
            val runner = failingRunner()
            orchestrator(tracker, runner, states, sink = sink, workflow = workflow(maxAttempts = 2), runId = RunId("r2")).tick()

            assertEquals(1, runner.calls.size)
            val parked = sink.ofKind("issue_parked").single()
            assertEquals("max_attempts", parked.data["reason"])
            assertEquals("2", parked.data["failures"])
            assertTrue(states.records.isEmpty(), "a park clears the stored retry state")
        }

    @Test
    fun `the no progress count survives a restart`() =
        runTest {
            val states = FakeRetryStates()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            orchestrator(tracker, FakeRunner(), states, workflow = workflow(maxContinuations = 2)).tick()
            assertEquals(
                1,
                states.records.values
                    .single()
                    .noProgress,
            )

            advanceTimeBy(1_000)
            val sink = FakeSink()
            orchestrator(tracker, FakeRunner(), states, sink = sink, workflow = workflow(maxContinuations = 2), runId = RunId("r2")).tick()

            assertEquals("max_continuations", sink.ofKind("issue_parked").single().data["reason"])
        }

    @Test
    fun `a normal exit after a failure leaves only the continuation in the store`() =
        runTest {
            val states = FakeRetryStates()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner { _, prompt -> if (prompt.endsWith("first")) failingTurn() else succeedingTurn() }
            val orchestrator = orchestrator(tracker, runner, states)
            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            val state = states.records.values.single()
            assertEquals(0, state.failures)
            assertEquals(QueuedRetry(1, FIXED_NOW + 11_000.milliseconds, null), state.retry)
        }

    @Test
    fun `an issue that left the active states has its stored counters deleted`() =
        runTest {
            val states =
                FakeRetryStates(RetryState(PROJECT, ISSUE_ID, "m1-02", retry = null, failures = 2, noProgress = 0))
            orchestrator(FakeTracker(emptyList()), failingRunner(), states).tick()

            assertTrue(states.records.isEmpty())
        }

    @Test
    fun `a stored retry of a parked issue is dropped, never dispatched`() =
        runTest {
            // A park deletes the row; this one survived a failed delete.
            val states =
                FakeRetryStates(RetryState(PROJECT, ISSUE_ID, "m1-02", QueuedRetry(1, FIXED_NOW, "exit_code=1"), 4, 0))
            val parked =
                FakeParkedIssues(
                    ParkedIssue(PROJECT, ISSUE_ID, "m1-02", ParkReason.MaxAttempts, "todo", setOf("ready-for-agent"), FIXED_NOW),
                )
            val runner = failingRunner()
            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, states, parked = parked).tick()

            assertEquals(0, runner.calls.size)
            assertTrue(states.records.isEmpty())
        }

    @Test
    fun `a store that cannot be read on the first tick dispatches nothing until it can`() =
        runTest {
            val states =
                FakeRetryStates(
                    RetryState(PROJECT, ISSUE_ID, "m1-02", QueuedRetry(3, FIXED_NOW + 30_000.milliseconds, "exit_code=1"), 3, 0),
                )
            states.readFailure = IOException("disk gone")
            val runner = failingRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, states)

            orchestrator.tick()
            assertEquals(0, runner.calls.size)
            assertTrue(logLines.any { "event=retry_state outcome=failed" in it && "disk gone" in it }, logLines.joinToString("\n"))

            states.readFailure = null
            orchestrator.tick()
            assertEquals(0, runner.calls.size, "the restored retry is not due yet")
            advanceTimeBy(30_000)
            orchestrator.tick()
            assertEquals("m1-02 retry 3", runner.calls.single().second)
        }

    @Test
    fun `a failed write is logged and written again on the next tick`() =
        runTest {
            val states = FakeRetryStates()
            states.writeFailure = IOException("disk full")
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val orchestrator = orchestrator(tracker, failingRunner(), states)

            orchestrator.tick()
            assertTrue(states.records.isEmpty())
            assertTrue(logLines.any { "event=retry_state outcome=failed" in it && "disk full" in it }, logLines.joinToString("\n"))

            states.writeFailure = null
            orchestrator.tick()
            assertEquals(
                1,
                states.records.values
                    .single()
                    .failures,
            )
        }

    @Test
    fun `the claim holds the attempt and its session while the attempt runs, and is released at its end`() =
        runTest {
            val claims = FakeClaims()
            val seenAtStart = mutableListOf<Claim>()
            val runner =
                FakeRunner { _, _ ->
                    seenAtStart += claims.records.values
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN, runtimeSessionId = "rt-1"))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, FakeRetryStates(), claims = claims).tick()

            val expected = Claim(PROJECT, ISSUE_ID, "m1-02", AttemptId("a1"), RunId("r1"), FIXED_NOW)
            assertEquals(listOf(expected), seenAtStart, "the claim is stored before the agent starts")
            assertEquals(listOf(expected, expected.copy(sessionId = SESSION, runtimeSessionId = "rt-1")), claims.history)
            assertTrue(claims.records.isEmpty(), "the claim is released when the attempt ends")
        }

    @Test
    fun `a later turn updates the claimed session`() =
        runTest {
            val claims = FakeClaims()
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> sessionTurn(turn) }
            val tracker = FakeTracker(listOf(issue("m1-02")))
            // The issue stays active, so the session runs up to max_turns (2 here).
            val workflow = workflow().let { it.copy(config = it.config.copy(agent = it.config.agent.copy(maxTurns = 2))) }
            orchestrator(tracker, runner, FakeRetryStates(), claims = claims, workflow = workflow).tick()

            assertEquals(listOf(null, "th1-tu1", "th1-tu2"), claims.history.map { it.sessionId?.value })
        }

    @Test
    fun `a canceled attempt releases its claim`() =
        runTest {
            val claims = FakeClaims()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        awaitCancellation()
                    }
                }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, FakeRetryStates(), claims = claims)
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(1, claims.records.size)

            job.cancelAndJoin()
            assertTrue(claims.records.isEmpty())
        }

    @Test
    fun `a claim that cannot be written is logged and the attempt goes on`() =
        runTest {
            val claims = FakeClaims().apply { failure = IOException("locked") }
            val runner = FakeRunner()
            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, FakeRetryStates(), claims = claims).tick()

            assertEquals(1, runner.calls.size)
            assertContains(logLines.joinToString("\n"), "event=claim outcome=failed")
            assertNull(claims.records.values.firstOrNull())
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val ISSUE_ID = "id-m1-02"
        const val PROMPT = "{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
    }
}
