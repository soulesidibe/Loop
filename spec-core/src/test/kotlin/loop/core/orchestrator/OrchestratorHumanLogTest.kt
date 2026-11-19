package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
import loop.core.log.HumanLog
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The orchestrator feeds the human readable run log (m1-39): its events, its polls and its runner lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorHumanLogTest {
    private val root: Path = createTempDirectory("loop-orchestrator-human-log").toRealPath()
    private val sink = FakeSink()
    private val keyValueLines = mutableListOf<String>()
    private val humanLines = mutableListOf<String>()

    private fun workflow(
        maxAttempts: Int = 5,
        maxConcurrentAgents: Int = 10,
    ): WorkflowFile =
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
            promptTemplate = "Do {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = maxConcurrentAgents, maxTurns = 1, maxRetryBackoffMs = 1000),
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { keyValueLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId =
                run {
                    var next = 0
                    { "a${++next}" }
                },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            observer = HumanLog(out = { humanLines += it }, clock = clock, redactor = Redactor.NONE, zone = ZoneOffset.UTC, base = root),
        )
    }

    @Test
    fun `twenty idle polls write one nothing to do line, the key value log keeps one line per poll`() =
        runTest {
            val orchestrator = orchestrator(FakeTracker(emptyList()), FakeRunner())

            repeat(20) { orchestrator.tick() }

            assertEquals(listOf("19:02:11  nothing to do, waiting for eligible issues"), humanLines)
            assertEquals(20, keyValueLines.count { "event=poll outcome=idle" in it }, keyValueLines.joinToString("\n"))
        }

    @Test
    fun `an attempt is followed from the poll that found it to its retry, the retry coordinator's events included`() =
        runTest {
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-13"))), FakeRunner { _, _ -> failingTurn() })

            orchestrator.tick()

            assertEquals(
                listOf(
                    "19:02:11  1 issue eligible (m1-13)",
                    "19:02:11  m1-13  attempt started in workspaces/m1-13",
                    "19:02:11  m1-13  turn 1 of 1 started",
                    "19:02:11  m1-13  turn 1 failed, the agent exited with code 1",
                    "19:02:11  m1-13  attempt failed, the agent exited with code 1",
                    "19:02:11  m1-13  retry 1 in 1s",
                ),
                humanLines,
            )
        }

    @Test
    fun `a runner line longer than the stored cut still gives the command's result`() =
        runTest {
            val output = "x".repeat(3000)
            val call =
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}"""
            val result =
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"$output","is_error":false}]}}"""
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, call))
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, result))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-13"))), runner)

            orchestrator.tick()

            assertTrue("19:02:11  m1-13  ran ./gradlew build (exit 0)" in humanLines, humanLines.joinToString("\n"))
            val stored = sink.events.filter { it.kind == "runner_output" }.map { it.data.getValue("line") }
            assertTrue(stored.any { it.length == 2000 }, "the stored line is still cut")
        }

    @Test
    fun `a poll while every slot is busy says the eligible issues wait for a free slot`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        gate.await()
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val tracker = FakeTracker(listOf(issue("m1-13"), issue("m1-14")))
            val orchestrator = orchestrator(tracker, runner, workflow(maxConcurrentAgents = 1))

            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val polls = humanLines.filter { "eligible" in it }
            assertEquals(
                listOf(
                    "19:02:11  2 issues eligible (m1-13, m1-14)",
                    "19:02:12  1 issue eligible, waiting for a free slot (m1-14)",
                ),
                polls,
                humanLines.joinToString("\n"),
            )
            gate.complete(Unit)
            job.cancelAndJoin()
        }
}
