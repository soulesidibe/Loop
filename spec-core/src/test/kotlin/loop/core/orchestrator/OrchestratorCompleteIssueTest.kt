package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * bl-06: the orchestrator asks the tracker to complete the issue once per dispatch, just before the prompt is
 * rendered, so an answer the tracker keeps outside the body (a GitHub comment) reaches the agent. A failed
 * completion never renders a prompt without the answers: the agent does not start and the attempt is retried
 * with the normal backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorCompleteIssueTest {
    private val root: Path = createTempDirectory("loop-orchestrator-complete").toRealPath()

    private val workflow: WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = PROMPT,
            config = WorkflowConfig(agent = AgentConfig(maxRetryBackoffMs = 3_600_000L)),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink,
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
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            preflight = PreflightCheck { PreflightResult.Ok(workflow) },
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    private fun answered(): FakeTracker =
        FakeTracker(listOf(issue("gh-7", description = "Export items."))).apply {
            completer = { it.copy(description = it.description + "\n\n## Comments\n\n2026-11-08 16:00Z soule: Yes.") }
        }

    @Test
    fun `the completed issue is what the prompt renders and what the agent gets`() =
        runTest {
            val tracker = answered()
            val runner = FakeRunner()

            orchestrator(tracker, runner, FakeSink()).tick()

            val (ctx, prompt) = runner.calls.single()
            assertEquals("gh-7: Export items.\n\n## Comments\n\n2026-11-08 16:00Z soule: Yes.", prompt)
            assertEquals(prompt.substringAfter("gh-7: "), ctx.issue.description)
            assertEquals(listOf("id-gh-7"), tracker.completions.map { it.id })
            assertEquals("Export items.", tracker.completions.single().description)
        }

    @Test
    fun `the issue is completed once per dispatch, not once per turn`() =
        runTest {
            val tracker = answered()
            val runner = FakeRunner().apply { sessionScript = { turn, _ -> sessionTurn(turn) } }
            val sink = FakeSink()

            orchestrator(tracker, runner, sink).tick()

            assertEquals(true, runner.turnInputs.size > 1, runner.turnInputs.toString())
            assertEquals(1, tracker.completions.size)
        }

    @Test
    fun `a failed completion starts no agent and the attempt is retried with the normal backoff`() =
        runTest {
            val tracker = answered().apply { completeFailure = TrackerError.Unavailable(IOException("GitHub did not answer within 30s")) }
            val runner = FakeRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)

            orchestrator.tick()

            assertEquals(emptyList(), runner.calls, "the agent started without the answers")
            assertEquals(0, runner.sessionsOpened)
            assertEquals(emptyList(), sink.ofKind("prompt_record_failed") + sink.events.filter { it.data.containsKey("prompt_chars") })
            val finished = sink.ofKind("attempt_finished").single()
            assertEquals("failed", finished.data["outcome"])
            assertEquals("tracker error: GitHub did not answer within 30s", finished.data["reason"])
            val scheduled = sink.ofKind("retry_scheduled").single()
            assertEquals("error", scheduled.data["kind"])
            assertEquals("10000", scheduled.data["delay_ms"])
            assertEquals("tracker error: GitHub did not answer within 30s", scheduled.data["error"])

            tracker.completeFailure = null
            advanceTimeBy(9_999)
            orchestrator.tick()
            assertEquals(emptyList(), runner.calls, "dispatched before the retry was due")
            advanceTimeBy(1)
            orchestrator.tick()

            assertEquals(2, tracker.completions.size)
            assertEquals("gh-7: Export items.\n\n## Comments\n\n2026-11-08 16:00Z soule: Yes.", runner.calls.single().second)
        }

    private companion object {
        const val PROMPT = "{{ issue.identifier }}: {{ issue.description }}"
    }
}
