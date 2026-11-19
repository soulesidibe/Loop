package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A runner that cannot reach its model holds the whole run instead of using up attempts (bl-39). */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRunnerHoldTest {
    private val root: Path = createTempDirectory("loop-orchestrator-hold").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxAttempts: Int = 5,
        maxContinuations: Int = 3,
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
            promptTemplate = PROMPT,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = 2),
                    retries = RetriesConfig(maxAttempts = maxAttempts, maxContinuations = maxContinuations),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        parked: FakeParkedIssues = FakeParkedIssues(),
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
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    private fun FakeRunner.callsOf(identifier: String): Int = calls.count { it.first.issue.identifier == identifier }

    @Test
    fun `a runner_unavailable failure takes no max_attempts slot and requeues its issue at the probe`() =
        runTest {
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, workflow(maxAttempts = 1), parked)

            orchestrator.tick()

            assertEquals(emptyList(), sink.ofKind("issue_parked"), "max_attempts 1 would park an ordinary failure at once")
            assertTrue(parked.list(PROJECT).getOrThrow().isEmpty())
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("error", retry.data["kind"])
            assertEquals("0", retry.data["failures"])
            assertEquals("60000", retry.data["delay_ms"])
            assertEquals((FIXED_NOW + 1.minutes).toString(), retry.data["due_at"])
            assertEquals(REASON, retry.data["error"])
            val failed = sink.ofKind("turn_failed").single()
            assertEquals("runner_unavailable", failed.data["category"])
        }

    @Test
    fun `the hold starts at 1 minute, doubles to 15, dispatches one probe at a time and waits indefinitely`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val tracker = FakeTracker(listOf(issue("m1-02"), issue("m1-03")))
            val orchestrator = orchestrator(tracker, runner, sink, workflow(maxAttempts = 1))

            orchestrator.tick()

            assertEquals(2, runner.calls.size, "both issues were running when the runner failed")
            assertEquals(listOf("60000"), sink.ofKind("runner_hold_started").map { it.data["delay_ms"] })
            val delays = listOf(1, 2, 4, 8, 15, 15, 15, 15, 15, 15).map { it.minutes }
            delays.zipWithNext().forEachIndexed { index, (current, next) ->
                val calls = runner.calls.size
                advanceTimeBy(current - 1.milliseconds)
                orchestrator.tick()
                assertEquals(calls, runner.calls.size, "dispatched before the probe of hold ${index + 1}")
                advanceTimeBy(1.milliseconds)
                orchestrator.tick()
                assertEquals(calls + 1, runner.calls.size, "hold ${index + 1} probes with exactly one attempt")
                assertEquals(next.inWholeMilliseconds.toString(), sink.ofKind("runner_hold_started").last().data["delay_ms"])
            }
            assertEquals(delays.size, sink.ofKind("runner_hold_started").size, "one hold event per hold")
            assertEquals((1..delays.size).map { it.toString() }, sink.ofKind("runner_hold_started").map { it.data["hold"] })
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
            assertTrue(runner.callsOf("m1-02") > 1 && runner.callsOf("m1-03") > 1, "the probe is the next due retry, by turns")
        }

    @Test
    fun `a retry-after longer than the backoff sets the hold`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn(retryAfter = 5.minutes) }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            advanceTimeBy(5.minutes - 1.milliseconds)
            orchestrator.tick()

            val started = sink.ofKind("runner_hold_started").single()
            assertEquals("300000", started.data["delay_ms"])
            assertEquals("300000", started.data["retry_after_ms"])
            assertEquals(1, runner.calls.size)
            advanceTimeBy(1.milliseconds)
            orchestrator.tick()
            assertEquals(2, runner.calls.size)
        }

    @Test
    fun `each hold records one event with the issue and attempt, and one operator line naming the reason and the probe`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()

            val started = sink.ofKind("runner_hold_started").single()
            assertEquals(
                mapOf("reason" to REASON, "hold" to "1", "delay_ms" to "60000", "probe_at" to (FIXED_NOW + 1.minutes).toString()),
                started.data,
            )
            assertEquals("m1-02", started.issueIdentifier)
            assertEquals("a1", started.attemptId?.value)
            val line = logLines.single { "event=runner_hold" in it }
            assertTrue("outcome=started" in line && "hold=1" in line && "probe_at=${FIXED_NOW + 1.minutes}" in line, line)
            assertTrue("API Error: 403" in line, line)
        }

    @Test
    fun `a successful turn lifts the hold and resets its backoff`() =
        runTest {
            val sink = FakeSink()
            var call = 0
            // Unavailable, unavailable, then a successful probe, then unavailable again.
            val runner = FakeRunner { _, _ -> if (++call == 3) succeedingTurn() else unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            advanceTimeBy(1.minutes)
            orchestrator.tick()
            advanceTimeBy(2.minutes)
            orchestrator.tick()

            val lifted = sink.ofKind("runner_hold_lifted").single()
            assertEquals(mapOf("holds" to "2", "held_ms" to "180000"), lifted.data)
            assertEquals("a3", lifted.attemptId?.value)
            assertTrue(logLines.any { "event=runner_hold" in it && "outcome=released" in it })
            // The continuation of the normal exit runs at once and fails: a new hold, back at 1 minute.
            advanceTimeBy(1.seconds)
            orchestrator.tick()
            assertEquals(4, runner.calls.size)
            val again = sink.ofKind("runner_hold_started").last()
            assertEquals("1", again.data["hold"])
            assertEquals("60000", again.data["delay_ms"])
        }

    @Test
    fun `an issue that hits it 3 times in a row while another issue succeeds counts an ordinary failure`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { ctx, _ ->
                    if (ctx.issue.identifier == "m1-02") unavailableTurn() else succeedingTurn(after = 100.milliseconds)
                }
            val tracker = FakeTracker(listOf(issue("m1-02"), issue("m1-03")))
            val orchestrator = orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, maxContinuations = 1000))

            var waited = Duration.ZERO
            while (sink.ofKind("issue_parked").isEmpty() && waited < 10.minutes) {
                orchestrator.tick()
                advanceTimeBy(1.seconds)
                waited += 1.seconds
            }

            assertEquals(3, runner.callsOf("m1-02"))
            val park = sink.ofKind("issue_parked").single()
            assertEquals("m1-02", park.issueIdentifier)
            assertEquals("max_attempts", park.data["reason"])
            assertEquals("1", park.data["failures"])
            assertEquals(2, sink.ofKind("runner_hold_started").size, "the third hit starts no hold")
            assertEquals(2, sink.ofKind("runner_hold_lifted").size)
        }

    private fun unavailableTurn(retryAfter: Duration? = null): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            emit(
                RunnerEvent.TurnFailed(
                    SESSION,
                    TURN,
                    exitCode = 1,
                    reason = REASON,
                    category = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
                    retryAfter = retryAfter,
                ),
            )
        }

    private fun succeedingTurn(after: Duration): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            delay(after)
            emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val PROMPT = "{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
        const val REASON = "turn_failed: Failed to authenticate. API Error: 403 Attention Required! | Cloudflare"
    }
}
