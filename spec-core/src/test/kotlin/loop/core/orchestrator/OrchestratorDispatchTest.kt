package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Concurrency, blockers and skip reasons of dispatch (m1-09, spec 8.2 and 8.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorDispatchTest {
    private val root: Path = createTempDirectory("loop-dispatch").toRealPath()
    private val logLines = mutableListOf<String>()

    /** Runs on every log line before it is kept; may throw to break an attempt from outside its own catches. */
    private var onLogLine: (String) -> Unit = {}

    private fun workflow(agent: AgentConfig = AgentConfig()): WorkflowFile =
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
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = agent),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink = FakeSink(),
        workflow: WorkflowFile = workflow(),
        cancelReason: () -> String = { "canceled" },
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log =
                KeyValueLog(
                    out = {
                        onLogLine(it)
                        logLines += it
                    },
                    clock = clock,
                    redactor = Redactor.NONE,
                ),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            cancelReason = cancelReason,
        )
    }

    /** Counts the turns in flight; each waits for [gate] of its issue identifier before it completes. */
    private class GatedRunner {
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val inFlight = mutableSetOf<String>()
        var peak = 0

        fun gate(identifier: String): CompletableDeferred<Unit> = gates.getOrPut(identifier) { CompletableDeferred() }

        fun turn(identifier: String): Flow<RunnerEvent> =
            flow {
                inFlight += identifier
                peak = maxOf(peak, inFlight.size)
                try {
                    emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                    gate(identifier).await()
                    emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                } finally {
                    inFlight -= identifier
                }
            }

        fun runner(): FakeRunner = FakeRunner { ctx, _ -> turn(ctx.issue.identifier) }

        fun releaseAll() = gates.values.forEach { it.complete(Unit) }
    }

    @Test
    fun `with max concurrent agents 2 and five ready issues never more than two run at once`() =
        runTest {
            val issues = (1..5).map { issue("m1-0$it") }
            val gated = GatedRunner()
            val orchestrator =
                orchestrator(FakeTracker(issues), gated.runner(), workflow = workflow(AgentConfig(maxConcurrentAgents = 2)))

            val tick = async { orchestrator.tick() }
            runCurrent()

            assertEquals(setOf("m1-01", "m1-02"), gated.inFlight)
            listOf("m1-01", "m1-02").forEach { gated.gate(it) }
            gated.releaseAll()
            val result = tick.await()

            assertEquals(listOf("m1-01", "m1-02"), result.attempts.map { it.issueIdentifier })
            assertEquals(2, gated.peak)
        }

    @Test
    fun `per state limit caps running issues in that state`() =
        runTest {
            val issues =
                listOf(
                    issue("m1-01", state = "in_progress"),
                    issue("m1-02", state = "in_progress"),
                    issue("m1-03"),
                    issue("m1-04", state = "In_Progress"),
                    issue("m1-05"),
                )
            val gated = GatedRunner()
            val agent = AgentConfig(maxConcurrentAgentsByState = mapOf("in_progress" to 1))
            val orchestrator = orchestrator(FakeTracker(issues), gated.runner(), workflow = workflow(agent))

            val tick = async { orchestrator.tick() }
            runCurrent()

            // A capped state skips its issue and the next candidates still get slots.
            assertEquals(setOf("m1-01", "m1-03", "m1-05"), gated.inFlight)
            gated.releaseAll()
            tick.await()
        }

    @Test
    fun `a candidate left out for lack of a global or per state slot is recorded as skipped`() =
        runTest {
            val gated = GatedRunner()
            val sink = FakeSink()
            val issues =
                listOf(
                    issue("m1-01", state = "in_progress"),
                    issue("m1-02", state = "in_progress"),
                    issue("m1-03"),
                    issue("m1-04"),
                    issue("m1-05"),
                )
            val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByState = mapOf("in_progress" to 1))
            val orchestrator = orchestrator(FakeTracker(issues), gated.runner(), sink, workflow(agent))

            val tick = async { orchestrator.tick() }
            runCurrent()

            assertEquals(setOf("m1-01", "m1-03", "m1-04"), gated.inFlight)
            gated.releaseAll()
            tick.await()
            val skipped = sink.events.filter { it.kind == "candidate_skipped" }
            assertEquals(listOf("m1-02", "m1-05"), skipped.map { it.issueIdentifier })
            assertEquals(listOf("no_available_slots", "no_available_slots"), skipped.map { it.data["reason"] })
        }

    @Test
    fun `a freed slot is refilled by the next tick while others run`() =
        runTest {
            val gated = GatedRunner()
            val tracker = FakeTracker((1..3).map { issue("m1-0$it") })
            val orchestrator = orchestrator(tracker, gated.runner(), workflow = workflow(AgentConfig(maxConcurrentAgents = 2)))
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(setOf("m1-01", "m1-02"), gated.inFlight)

            tracker.issues[0] = issue("m1-01", state = "done")
            gated.gate("m1-01").complete(Unit)
            runCurrent()
            assertEquals(setOf("m1-02"), gated.inFlight)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(setOf("m1-02", "m1-03"), gated.inFlight)
            job.cancelAndJoin()
        }

    // Stop: the caller names why running attempts were canceled.

    @Test
    fun `canceled attempts carry the reason the caller gives and are counted`() =
        runTest {
            val gated = GatedRunner()
            val sink = FakeSink()
            var reason = "signal"
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-01"), issue("m1-02"))), gated.runner(), sink, cancelReason = { reason })
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(setOf("m1-01", "m1-02"), gated.inFlight)

            reason = "stop_requested"
            job.cancelAndJoin()

            val finished = sink.events.filter { it.kind == "attempt_finished" }
            assertEquals(
                listOf("m1-01", "m1-02").map { it to mapOf("outcome" to "canceled", "reason" to "stop_requested") },
                finished.map { it.issueIdentifier to it.data }.sortedBy { it.first },
            )
            assertEquals(2, orchestrator.attemptsCanceled)
        }

    // Retries under concurrency.

    @Test
    fun `a due retry for a running issue is not fired`() =
        runTest {
            val gated = GatedRunner()
            var calls = 0
            val runner = FakeRunner { ctx, _ -> if (++calls == 1) failingTurn() else gated.turn(ctx.issue.identifier) }
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            // The retry runs; its entry stays queued until replaced.
            assertEquals(setOf("m1-01"), gated.inFlight)

            // The agent moves its issue to a terminal state while the attempt still runs.
            tracker.issues[0] = issue("m1-01", state = "done")
            advanceTimeBy(1_500)
            runCurrent()

            // The entry is not fired: reconciliation (m1-16) stops the attempt first, and only then removes the
            // workspace, once the attempt closed. The stop drops the entry with no retry_dropped of its own.
            assertEquals(emptyList(), sink.events.filter { it.kind == "retry_dropped" })
            val finished = sink.events.last { it.kind == "attempt_finished" }
            assertEquals("canceled", finished.data["outcome"])
            assertEquals("terminal", finished.data["reason"])
            assertFalse(root.resolve("workspaces/m1-01").exists())
            job.cancelAndJoin()
        }

    @Test
    fun `a due retry with no free slot is requeued with no available orchestrator slots`() =
        runTest {
            val gated = GatedRunner()
            val runner = FakeRunner { ctx, _ -> if (ctx.issue.identifier == "m1-01") failingTurn() else gated.turn(ctx.issue.identifier) }
            val sink = FakeSink()
            val orchestrator =
                orchestrator(
                    FakeTracker(listOf(issue("m1-01"), issue("m1-02"))),
                    runner,
                    sink,
                    workflow(AgentConfig(maxConcurrentAgents = 1)),
                )
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(setOf("m1-02"), gated.inFlight)

            advanceTimeBy(9_000)
            runCurrent()

            val scheduled = sink.events.filter { it.kind == "retry_scheduled" && it.issueIdentifier == "m1-01" }
            assertEquals(2, scheduled.size)
            val requeued = scheduled.last().data
            assertEquals("no available orchestrator slots", requeued["error"])
            // bl-34: a slot requeue keeps the attempt, and its due time, which wakes the daemon, moves one poll interval.
            assertEquals("1", requeued["attempt"])
            assertEquals("1000", requeued["delay_ms"])
            // Slot exhaustion is not a failure: the count stays at the one real failure.
            assertEquals("1", requeued["failures"])
            assertEquals(1, runner.calls.count { it.first.issue.identifier == "m1-01" })
            job.cancelAndJoin()
        }

    @Test
    fun `a due retry whose issue became blocked is dropped with the blocked reason`() =
        runTest {
            val runner = FakeRunner { _, _ -> failingTurn() }
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)
            orchestrator.tick()

            tracker.issues[0] = issue("m1-01", blockedReason = "blocked_by m1-00 (todo)")
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(1, runner.calls.size)
            val dropped = sink.events.single { it.kind == "retry_dropped" }
            assertEquals(mapOf("reason" to "not_eligible", "blocked_reason" to "blocked_by m1-00 (todo)"), dropped.data)
        }

    @Test
    fun `one attempt failing unexpectedly does not cancel its siblings`() =
        runTest {
            val gated = GatedRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-01"), issue("m1-02"))), gated.runner(), sink)
            onLogLine = { line ->
                if ("event=attempt outcome=started" in line && "issue_identifier=m1-01" in line) error("log broke")
            }

            val tick = async { orchestrator.tick() }
            runCurrent()
            gated.releaseAll()
            val result = tick.await()

            assertEquals(listOf("m1-02"), result.attempts.map { it.issueIdentifier })
            val finished = sink.events.filter { it.kind == "attempt_finished" }
            assertEquals(listOf("m1-02" to "succeeded"), finished.map { it.issueIdentifier to it.data["outcome"] })
            assertTrue(
                logLines.any { "level=error event=attempt outcome=failed" in it && "issue_identifier=m1-01" in it && "log broke" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a broken attempt is counted in the tick result`() =
        runTest {
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-01"), issue("m1-02"))), FakeRunner())
            onLogLine = { line ->
                if ("event=attempt outcome=started" in line && "issue_identifier=m1-01" in line) error("log broke")
            }

            val result = orchestrator.tick()

            assertEquals(listOf("m1-02"), result.attempts.map { it.issueIdentifier })
            assertEquals(1, result.brokenAttempts)
        }

    @Test
    fun `a non dispatchable issue is not dispatched and its skip reason is recorded once`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-01", blockedReason = "blocked_by m1-00 (todo)"), issue("m1-02")))
            val runner = FakeRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)

            orchestrator.tick()
            orchestrator.tick()

            assertEquals(listOf("m1-02"), runner.calls.map { it.first.issue.identifier })
            val skipped = sink.events.filter { it.kind == "candidate_skipped" }
            assertEquals(listOf("id-m1-01"), skipped.map { it.issueId })
            assertEquals(listOf("m1-01"), skipped.map { it.issueIdentifier })
            assertEquals(listOf(mapOf("reason" to "blocked_by m1-00 (todo)")), skipped.map { it.data })

            tracker.issues[0] = issue("m1-01", blockedReason = "blocked_by m1-00 (review)")
            orchestrator.tick()

            assertEquals(
                listOf("blocked_by m1-00 (todo)", "blocked_by m1-00 (review)"),
                sink.events.filter { it.kind == "candidate_skipped" }.map { it.data["reason"] },
            )
        }
}
