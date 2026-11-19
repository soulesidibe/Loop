package loop.core.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.CodexConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stall detection (m1-09, spec 8.5): a runner silent past `codex.stall_timeout_ms` fails its attempt. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorStallTest {
    private val root: Path = createTempDirectory("loop-stall").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(stallTimeoutMs: Long): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(codex = CodexConfig(stallTimeoutMs = stallTimeoutMs)),
        )

    private fun TestScope.orchestrator(
        runner: FakeRunner,
        sink: FakeSink,
        stallTimeoutMs: Long,
    ): Orchestrator {
        val workflow = workflow(stallTimeoutMs)
        val clock = SchedulerClock(testScheduler)
        var next = 0
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-01"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
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
        )
    }

    /** Starts a session, writes one line, then says nothing until it is cancelled. */
    private class SilentTurn {
        var cancelled = false

        fun flow(): Flow<RunnerEvent> =
            flow {
                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "thinking"))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
    }

    @Test
    fun `an agent silent longer than the stall timeout is stopped and handled as a failure`() =
        runTest {
            val silent = SilentTurn()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeRunner { _, _ -> silent.flow() }, sink, stallTimeoutMs = 5_000)

            val tick = async { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(5_001)
            val attempt = tick.await().attempts.single()

            assertEquals(AttemptOutcome.Failed, attempt.outcome)
            assertEquals("stalled", attempt.reason)
            assertTrue(silent.cancelled, "the runner flow is cancelled, which stops the agent process")
            val stalled = sink.events.single { it.kind == "attempt_stalled" }
            assertEquals(mapOf("silent_ms" to "5000", "stall_timeout_ms" to "5000"), stalled.data)
            val phases = sink.events.filter { it.kind == "phase" }
            assertEquals(AttemptPhase.Stalled.name, phases.last().phase)
            val finished = sink.events.single { it.kind == "attempt_finished" }
            assertEquals("failed", finished.data["outcome"])
            assertEquals("stalled", finished.data["reason"])
            assertEquals("stalled", sink.events.single { it.kind == "retry_scheduled" }.data["error"])
            assertTrue(
                logLines.any { line ->
                    listOf(
                        "level=warn event=stall outcome=failed",
                        "silent_ms=5000",
                        "stall_timeout_ms=5000",
                        "reason=\"no runner event for 5 s\"",
                    ).all { it in line }
                },
                logLines.joinToString("\n"),
            )
        }

    /**
     * Production shape: `runBlocking` on one thread, runner events sent from IO threads like the
     * process launcher's readers, so every receive races a send. A spin fails on the JUnit timeout.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `turns fed from other threads always finish with the stall timer on`() {
        val ioTurn =
            channelFlow {
                send(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                val lines = Channel<RunnerEvent>(Channel.UNLIMITED)
                val reader =
                    CoroutineScope(Dispatchers.IO + Job()).launch {
                        repeat(20) { lines.trySend(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "line $it")) }
                    }
                val forwarder = launch { for (line in lines) send(line) }
                withContext(Dispatchers.IO) { Thread.sleep(1) }
                reader.join()
                lines.close()
                forwarder.join()
                send(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
            }
        // A process that exits at once: both events are queued, and the channel closed, before the second receive.
        val instantFailure =
            channelFlow {
                send(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                withContext(Dispatchers.IO) { }
                send(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
            }
        repeat(300) { round ->
            val failing = round % 2 == 1
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> if (failing) instantFailure else ioTurn }
            val orchestrator = runBlockingOrchestrator(runner, sink, stallTimeoutMs = 300_000)

            val attempt = runBlocking { orchestrator.tick() }.attempts.single()

            val expected = if (failing) AttemptOutcome.Failed else AttemptOutcome.Succeeded
            assertEquals(expected, attempt.outcome, "round $round")
        }
    }

    private fun runBlockingOrchestrator(
        runner: FakeRunner,
        sink: FakeSink,
        stallTimeoutMs: Long,
    ): Orchestrator {
        val workflow = workflow(stallTimeoutMs)
        val clock = FixedClock()
        var next = 0
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-01"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
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
        )
    }

    @Test
    fun `a stall timeout of zero disables detection`() =
        runTest {
            val silent = SilentTurn()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeRunner { _, _ -> silent.flow() }, sink, stallTimeoutMs = 0)

            val tick = async { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(3_600_000)
            runCurrent()

            assertTrue(tick.isActive)
            assertEquals(emptyList(), sink.events.filter { it.kind == "attempt_stalled" })
            tick.cancel()
        }

    @Test
    fun `an agent that keeps emitting events is not stalled`() =
        runTest {
            val sink = FakeSink()
            // Ten lines four seconds apart: forty seconds in all, never five seconds of silence.
            val chatty =
                flow {
                    emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                    repeat(10) {
                        delay(4_000)
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "line $it"))
                    }
                    emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                }
            val orchestrator = orchestrator(FakeRunner { _, _ -> chatty }, sink, stallTimeoutMs = 5_000)

            val attempt = orchestrator.tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, attempt.outcome)
            assertEquals(emptyList(), sink.events.filter { it.kind == "attempt_stalled" })
        }
}
