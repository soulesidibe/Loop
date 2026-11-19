package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m2-23: on the GitHub tracker a move to a handoff state is a label change and the issue stays `open`, so a state
 * only comparison would miss it. A move the drain applied to a handoff state counts as a handoff, as with the files
 * tracker: the worker stops, the workspace is kept, the counters reset and no continuation turn runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabelHandoffTest {
    private val root: Path = createTempDirectory("loop-label-handoff").toRealPath()
    private val sink = FakeSink()
    private val hooks = FakeHookRunner()

    private val workflow: WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "github",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("open"),
            terminalStates = listOf("closed"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 5), hooks = HooksConfig(beforeRemove = "sh before_remove.sh")),
        )

    /** A tracker with GitHub's states whose move to a handoff state only swaps labels: the issue stays `open`. */
    private fun labelTracker(applyLabels: Boolean = true): FakeTracker =
        FakeTracker(listOf(issue("example-app-26", state = "open"))).apply {
            onMove = { issue, to ->
                when {
                    to == "open" || to == "closed" -> issue.copy(state = to)
                    !applyLabels -> issue
                    else -> issue.copy(labels = issue.labels - "ready-for-agent" + HANDOFF_LABELS.getValue(to))
                }
            }
        }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hooks),
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

    /**
     * A runner with a session. Each attempt plays the next of [attempts]: on its turn 1 it queues the given requests
     * the way `loop tool` does and ends with the given exit code. Later turns succeed and queue nothing.
     */
    private fun runner(vararg attempts: Pair<List<ToolRequest>, Int>): FakeRunner {
        val runner = FakeRunner()
        var config: Path? = null
        runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
        var now = 1_000L
        runner.sessionScript = { turn, _ ->
            val (requests, exitCode) = attempts.getOrElse(runner.sessionsOpened - 1) { emptyList<ToolRequest>() to 0 }
            if (turn == 1) {
                val queue = ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ })
                requests.forEach { queue.enqueue(it) }
            }
            sessionTurn(turn, exitCode = if (turn == 1) exitCode else 0)
        }
        return runner
    }

    private fun ofKind(kind: String): List<RecordedEvent> = sink.events.filter { it.kind == kind }

    private val workspace: Path get() = root.resolve("workspaces/example-app-26")

    @Test
    fun `a label move to review stops the worker, keeps the workspace and runs no continuation turn`() =
        runTest { assertLabelHandoff("review") }

    @Test
    fun `a label move to needs_info stops the worker, keeps the workspace and runs no continuation turn`() =
        runTest { assertLabelHandoff("needs_info") }

    private suspend fun TestScope.assertLabelHandoff(to: String) {
        val tracker = labelTracker()
        val runner = runner(listOf(ToolRequest.Move(to)) to 0)
        val orchestrator = orchestrator(tracker, runner)

        val summary = orchestrator.tick().attempts.single()

        assertEquals(listOf("id-example-app-26" to to), tracker.moves)
        assertEquals("open", summary.refreshedIssue?.state, "the issue stays open after a label move")
        assertEquals(1, runner.turnInputs.size, "no continuation turn after the handoff")
        assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
        assertEquals(emptyList(), ofKind("human_touch"), "the attempt's own move is not a human's")
        assertEquals(emptyList(), ofKind("retry_scheduled"), "a handoff needs no continuation retry")
        assertTrue(workspace.exists(), "the workspace is kept")
        assertTrue(hooks.calls.none { it.hook == HookName.BEFORE_REMOVE })

        // The next ticks see the issue out of the active set: it is never dispatched again.
        advanceTimeBy(10_000)
        orchestrator.tick()
        assertEquals(1, runner.sessionsOpened)
        assertTrue(workspace.exists())
    }

    @Test
    fun `a label move resets the failure counter, so the next failure after a human's return counts one`() =
        runTest {
            val tracker = labelTracker()
            val runner =
                runner(
                    emptyList<ToolRequest>() to 1,
                    listOf(ToolRequest.Move("review")) to 0,
                    emptyList<ToolRequest>() to 1,
                )
            val orchestrator = orchestrator(tracker, runner)

            orchestrator.tick()
            assertEquals("1", ofKind("retry_scheduled").single().data["failures"])
            advanceTimeBy(20_000)
            orchestrator.tick()
            assertEquals(2, runner.sessionsOpened, "the retry ran and handed off")
            assertEquals(1, ofKind("retry_scheduled").size, "the handoff scheduled nothing")

            // A human sends it back by restoring the routing label.
            tracker.issues[0] = issue("example-app-26", state = "open")
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(3, runner.sessionsOpened)
            assertEquals("1", ofKind("retry_scheduled").last().data["failures"], "the counter was reset by the handoff")
        }

    @Test
    fun `a label move the refresh does not show yet still counts as a handoff`() =
        runTest {
            val tracker = labelTracker(applyLabels = false)
            val runner = runner(listOf(ToolRequest.Move("review")) to 0)

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(listOf("id-example-app-26" to "review"), tracker.moves)
            assertEquals(listOf("ready-for-agent"), summary.refreshedIssue?.labels, "the refresh still shows the routing label")
            assertEquals(1, runner.turnInputs.size)
            assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
            assertEquals(emptyList(), ofKind("retry_scheduled"))
        }

    @Test
    fun `reconciliation that stops the attempt right after its label move sees its handoff, not a human's change`() =
        runTest {
            val tracker = labelTracker()
            // The attempt's refresh after its move hangs, so the next tick's reconciliation stops it first.
            var hung = false
            tracker.onFetchByIds = {
                if (tracker.moves.isNotEmpty() && !hung) {
                    hung = true
                    awaitCancellation()
                }
            }
            val runner = runner(listOf(ToolRequest.Move("review")) to 0)
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()
            assertTrue(hung, "the attempt is in its refresh after the move")

            advanceTimeBy(1_000)
            runCurrent()

            val finished = ofKind("attempt_finished").single().data
            assertEquals("canceled", finished["outcome"])
            assertEquals(ExitReason.NOT_ACTIVE, finished["reason"])
            assertEquals(emptyList(), ofKind("human_touch"), "the attempt's own move is not a human's")
            assertTrue(workspace.exists())
            assertTrue(hooks.calls.none { it.hook == HookName.BEFORE_REMOVE })
            job.cancelAndJoin()
        }

    @Test
    fun `a move to an active state is not a handoff, the next turn runs`() =
        runTest {
            val tracker = labelTracker()
            val runner = runner(listOf(ToolRequest.Move("open")) to 0)

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(5, runner.turnInputs.size)
            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
        }

    @Test
    fun `a move to closed ends the attempt as terminal and removes the workspace`() =
        runTest {
            val tracker = labelTracker()
            val runner = runner(listOf(ToolRequest.Move("closed")) to 0)

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(ExitReason.TERMINAL, summary.exitReason)
        }

    private companion object {
        val HANDOFF_LABELS = mapOf("review" to "needs-review", "needs_info" to "needs-info")
    }
}
