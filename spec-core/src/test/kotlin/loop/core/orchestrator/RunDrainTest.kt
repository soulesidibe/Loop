package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.waiting.Wait
import loop.core.workflow.AgentConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m4-10, bl-110: the drain of a run. Once `loop drain` asks for it, no candidate is dispatched, a retry of an issue whose
 * attempt ran when the drain began still runs, a retry of any other issue does not, a wait that needs a human does not
 * hold it, and the run stops by itself once nothing runs, with `run_draining` and `run_drained` recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunDrainTest {
    private val root: Path = createTempDirectory("loop-run-drain").toRealPath()

    /** Set by a test to ask for the drain, as the drain marker of `loop drain` does. */
    private var drainAsked = false

    private fun workflow(maxAttempts: Int = 5): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = POLL_MS,
            promptTemplate = "{{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxRetryBackoffMs = 3_600_000L),
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink = FakeSink(),
        waiting: FakeWaitingIssues = FakeWaitingIssues(),
        workflow: WorkflowFile = workflow(),
        runId: RunId = RunId("r1"),
        drainRequested: () -> Boolean = { drainAsked },
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "${runId.value}-a${++next}" },
            runId = runId,
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            waiting = waiting,
            drainRequested = drainRequested,
        )
    }

    /** A turn that starts, waits for [gate], then ends as [fails] says. */
    private fun gatedTurn(
        gate: CompletableDeferred<Unit>,
        fails: Boolean = false,
    ): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            gate.await()
            if (fails) {
                emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
            } else {
                emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
            }
        }

    private fun identifiers(runner: FakeRunner): List<String> = runner.calls.map { it.second }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `a drain dispatches no new candidate, lets the running attempt finish, then the run stops by itself`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            // The running issue leaves the active states as it ends, so nothing follows it.
            lateinit var tracker: FakeTracker
            val runner =
                FakeRunner { ctx, _ ->
                    if (ctx.issue.identifier == "m1-02") {
                        flow {
                            gatedTurn(gate).collect { event ->
                                if (event is RunnerEvent.TurnCompleted) tracker.moveIssue(ctx.issue.id, "review")
                                emit(event)
                            }
                        }
                    } else {
                        succeedingTurn()
                    }
                }
            tracker = FakeTracker(listOf(issue("m1-02")))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)
            val daemon = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(listOf("m1-02"), identifiers(runner))

            drainAsked = true
            tracker.issues += issue("m1-03")
            advanceTimeBy(POLL_MS * 3)
            runCurrent()

            assertEquals(listOf("m1-02"), identifiers(runner), "a candidate was dispatched during the drain")
            val draining = sink.ofKind("run_draining").single()
            assertEquals("m1-02", draining.data["attempts"])
            assertEquals(null, draining.issueId)
            assertEquals(RunId("r1"), draining.runId)
            assertFalse(daemon.isCompleted, "the run stopped while an attempt ran")
            assertTrue(sink.ofKind("run_drained").isEmpty())

            gate.complete(Unit)
            advanceTimeBy(POLL_MS * 5)
            runCurrent()

            assertTrue(daemon.isCompleted, "the run did not stop once nothing ran")
            assertEquals(listOf("m1-02"), identifiers(runner))
            val drained = sink.ofKind("run_drained").single()
            assertTrue(drained.data.getValue("drained_ms").toLong() > 0, drained.data.toString())
            assertEquals(1, sink.ofKind("attempt_finished").size)
        }

    @Test
    fun `a drain with nothing running stops the run at the next poll`() =
        runTest {
            val sink = FakeSink()
            drainAsked = true
            val runner = FakeRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            val daemon = launch { orchestrator.runUntilCancelled() }
            runCurrent()

            assertTrue(daemon.isCompleted)
            assertTrue(runner.calls.isEmpty())
            assertEquals(1, sink.ofKind("run_draining").size)
            assertEquals(null, sink.ofKind("run_draining").single().data["attempts"])
            assertEquals(1, sink.ofKind("run_drained").size)
        }

    @Test
    fun `a retry of an issue running when the drain began still runs, a due retry of another issue does not`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val started = mutableMapOf<String, Int>()
            val runner =
                FakeRunner { ctx, _ ->
                    val run = started.merge(ctx.issue.identifier, 1, Int::plus)!!
                    when {
                        // m1-02 fails at once and its retry falls due during the drain.
                        ctx.issue.identifier == "m1-02" -> failingTurn()
                        // m1-03 runs when the drain begins, then fails, and its retry fails again, which parks it.
                        run == 1 -> gatedTurn(gate, fails = true)
                        else -> failingTurn()
                    }
                }
            val tracker = FakeTracker(listOf(issue("m1-02", priority = 1)))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink, workflow = workflow(maxAttempts = 2))
            // m1-02 fails at 0 s, its retry is due at 10 s.
            orchestrator.tick()
            tracker.issues += issue("m1-03", priority = 2)
            val daemon = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(listOf("m1-02", "m1-03"), identifiers(runner))

            drainAsked = true
            advanceTimeBy(POLL_MS)
            runCurrent()
            val polls = tracker.requestedStates.size
            // m1-02's retry is due at 10 s and held back; the sleep does not spin on it either.
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf("m1-02", "m1-03"), identifiers(runner), "the retry of an issue outside the drain ran")
            assertTrue(tracker.requestedStates.size - polls <= 16, "the run polled ${tracker.requestedStates.size - polls} times in 15 s")

            gate.complete(Unit)
            runCurrent()
            // m1-03 failed at 16 s, its retry is due 10 s later.
            advanceTimeBy(10_000)
            runCurrent()
            advanceTimeBy(POLL_MS * 2)
            runCurrent()

            assertEquals(listOf("m1-02", "m1-03", "m1-03"), identifiers(runner))
            assertTrue(daemon.isCompleted, "the run did not stop once the retry of the drained issue ended")
            assertEquals("m1-03", sink.ofKind("run_draining").single().data["attempts"])
            assertEquals(1, sink.ofKind("run_drained").size)
            // m1-02's retry stays queued for the next run.
            val m102 = sink.ofKind("retry_scheduled").filter { it.issueIdentifier == "m1-02" }
            assertTrue(sink.ofKind("retry_dropped").none { it.issueIdentifier == "m1-02" })
            assertEquals(1, m102.size, m102.toString())
        }

    @Test
    fun `an approval wait does not hold the drain and still waits at the next run`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            lateinit var tracker: FakeTracker
            val runner =
                FakeRunner { ctx, _ ->
                    flow {
                        gatedTurn(gate).collect { event ->
                            if (event is RunnerEvent.TurnCompleted) tracker.moveIssue(ctx.issue.id, "review")
                            emit(event)
                        }
                    }
                }
            tracker = FakeTracker(listOf(issue("m1-02"), issue("m1-04")))
            val waiting = FakeWaitingIssues(approvalWait("m1-04"))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink, waiting)
            val daemon = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(listOf("m1-02"), identifiers(runner))

            drainAsked = true
            advanceTimeBy(POLL_MS)
            runCurrent()
            gate.complete(Unit)
            advanceTimeBy(POLL_MS * 3)
            runCurrent()

            assertTrue(daemon.isCompleted, "the approval wait held the drain")
            assertEquals(WaitReason.Approval, waiting.records.getValue(PROJECT.value to "id-m1-04").reason)

            // The next loop run, with no drain: the issue still waits for its approval and is not dispatched.
            val next = orchestrator(tracker, runner, FakeSink(), waiting, runId = RunId("r2"), drainRequested = { false })
            next.tick()
            assertEquals(listOf("m1-02"), identifiers(runner))
            assertEquals(WaitReason.Approval, waiting.records.getValue(PROJECT.value to "id-m1-04").reason)
        }

    @Test
    fun `what still runs counts attempts, checks, merges, approved approvals and the retries of the drained issues`() =
        runTest {
            val sink = FakeSink()
            drainAsked = true
            val drain = RunDrain({ drainAsked }, sink, "fake", quietLog(), FixedClock(), RunId("r1"), PROJECT)
            drain.notice(DrainFacts(running = listOf(issue("m1-02")), waits = emptyList(), queued = emptyMap()))

            val facts =
                DrainFacts(
                    running = listOf(issue("m1-02")),
                    waits =
                        listOf(
                            Wait.of(record("m1-05", WaitReason.Verify)),
                            Wait.of(record("m1-06", WaitReason.Merge)),
                            Wait.of(approvalWait("m1-07")),
                            Wait.of(approvalWait("m1-08", approved = true)),
                        ),
                    queued = mapOf("id-m1-02" to "m1-02", "id-m1-09" to "m1-09"),
                )
            val work = drain.remaining(facts)

            assertEquals(listOf("m1-02"), work.attempts)
            assertEquals(listOf("m1-05", "m1-06", "m1-08"), work.waits)
            assertEquals(listOf("m1-02"), work.retries)
            assertEquals(setOf("id-m1-09"), drain.heldBack(facts.queued.keys))
            assertEquals(emptyList(), drain.candidates(listOf(issue("m1-10"))))
        }

    @Test
    fun `a merge check that waits behind a paused delivery queue does not hold the drain, a running merge job does`() =
        runTest {
            val drain = RunDrain({ true }, FakeSink(), "fake", quietLog(), FixedClock(), RunId("r1"), PROJECT)
            drain.notice(DrainFacts(running = emptyList(), waits = emptyList(), queued = emptyMap()))
            val merge = Wait.of(record("m1-06", WaitReason.Merge))

            assertEquals(emptyList(), drain.remaining(DrainFacts(emptyList(), listOf(merge), emptyMap(), queuePaused = true)).waits)
            assertEquals(
                listOf("m1-06"),
                drain
                    .remaining(
                        DrainFacts(emptyList(), listOf(merge), emptyMap(), queuePaused = true, mergeJobs = setOf("id-m1-06")),
                    ).waits,
            )
        }

    @Test
    fun `no drain asked changes nothing`() =
        runTest {
            val sink = FakeSink()
            val drain = RunDrain({ false }, sink, "fake", quietLog(), FixedClock(), RunId("r1"), PROJECT)
            val facts = DrainFacts(running = emptyList(), waits = emptyList(), queued = mapOf("id-m1-09" to "m1-09"))

            drain.notice(facts)

            assertFalse(drain.draining)
            assertFalse(drain.drained(facts))
            assertEquals(emptySet(), drain.heldBack(facts.queued.keys))
            assertEquals(listOf(issue("m1-10")), drain.candidates(listOf(issue("m1-10"))))
            assertTrue(sink.events.isEmpty())
        }

    private fun quietLog(): KeyValueLog = KeyValueLog(out = {}, clock = FixedClock(), redactor = Redactor.NONE)

    private fun record(
        identifier: String,
        reason: WaitReason,
        data: Map<String, String> = emptyMap(),
    ) = WaitingIssue(
        projectId = PROJECT,
        issueId = "id-$identifier",
        identifier = identifier,
        reason = reason,
        attemptId = AttemptId("old1"),
        runId = RunId("r0"),
        since = FIXED_NOW,
        data = data,
    )

    private fun approvalWait(
        identifier: String,
        approved: Boolean = false,
    ) = record(
        identifier,
        WaitReason.Approval,
        mapOf("to" to "review") +
            if (approved) mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()) else emptyMap(),
    )

    private companion object {
        const val POLL_MS = 1_000L
        val PROJECT = ProjectId("p1")
    }
}
