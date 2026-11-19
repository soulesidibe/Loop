package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m3-11: `loop.retry_kind` is null on a first dispatch and the kind of the retry that dispatched the attempt after it:
 * `continuation` after a normal exit, `error` after a failure and `refuted` after a failure whose reason is a refutation.
 * `rotation` is set inside an attempt, see [OrchestratorRotationTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryKindPromptTest {
    private val root: Path = createTempDirectory("loop-retry-kind-prompt").toRealPath()

    private val workflow: WorkflowFile =
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
            promptTemplate = "Work on {{ issue.identifier }}. kind=[{{ loop.retry_kind }}]",
        )

    private fun TestScope.orchestrator(runner: FakeRunner): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m3-11"))),
            runner = runner,
            sink = FakeSink(),
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

    private fun failing(reason: String): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = reason))
        }

    @Test
    fun `m3-11 a continuation retry renders retry_kind continuation, the first dispatch none`() =
        runTest {
            val runner = FakeRunner()
            val orchestrator = orchestrator(runner)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(listOf("Work on m3-11. kind=[]", "Work on m3-11. kind=[continuation]"), runner.calls.map { it.second })
        }

    @Test
    fun `m3-11 an error retry renders retry_kind error`() =
        runTest {
            val runner = FakeRunner { _, _ -> failing("exit_code=1") }
            val orchestrator = orchestrator(runner)

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf("Work on m3-11. kind=[]", "Work on m3-11. kind=[error]"), runner.calls.map { it.second })
        }

    @Test
    fun `m3-11 a retry after a failure whose reason is refuted renders retry_kind refuted`() =
        runTest {
            val runner = FakeRunner { _, _ -> failing("refuted") }
            val orchestrator = orchestrator(runner)

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf("Work on m3-11. kind=[]", "Work on m3-11. kind=[refuted]"), runner.calls.map { it.second })
        }
}
