package loop.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AgentRunner
import loop.api.AttemptContext
import loop.api.EventSink
import loop.api.Issue
import loop.api.IssueComment
import loop.api.ParkedIssue
import loop.api.ParkedIssues
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TrackerAdapter
import loop.api.TrackerError
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.orchestrator.Orchestrator
import loop.core.workflow.AgentConfig
import loop.core.workflow.RunnerSettings
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Symphony spec 17.4, reconciliation (8.5 part B, 16.3) and startup terminal workspace cleanup
 * (8.6), against the public [Orchestrator] with in-memory ports and virtual time. Each running
 * issue is read again by id on every tick: terminal stops it and removes its workspace, not
 * active stops it and keeps the workspace, active and routable updates its running state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconciliationConformanceTest {
    private val root: Path = createTempDirectory("loop-reconciliation-conformance").toRealPath()
    private val start = Instant.parse("2026-11-11T10:00:00Z")
    private val events = mutableListOf<RecordedEvent>()
    private val logLines = mutableListOf<String>()

    private fun issue(
        identifier: String,
        state: String = "todo",
    ) = Issue(
        id = "id-$identifier",
        identifier = identifier,
        title = "Title of $identifier",
        state = state,
        priority = 2,
        labels = listOf("ready-for-agent"),
        blockedBy = emptyList(),
        description = null,
        createdAt = null,
    )

    /** Holds [issues] by id; a test changes an issue's state with [move], as a human would. */
    private class Tracker(
        issues: List<Issue>,
    ) : TrackerAdapter {
        val issues = issues.toMutableList()
        var byIdCalls = 0
        var failByIds = false
        var failByStates = false
        val statesAsked = mutableListOf<List<String>>()

        override val definedStates = listOf("todo", "in_progress", "review", "done", "canceled")

        fun move(
            identifier: String,
            state: String,
        ) {
            val index = issues.indexOfFirst { it.identifier == identifier }
            issues[index] = issues[index].copy(state = state)
        }

        fun remove(identifier: String) {
            issues.removeAll { it.identifier == identifier }
        }

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
            statesAsked += states
            if (failByStates) return Result.failure(TrackerError.Unavailable(IOException("tracker down")))
            return Result.success(issues.filter { it.state in states })
        }

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
            byIdCalls++
            if (failByIds) return Result.failure(TrackerError.Unavailable(IOException("tracker down")))
            return Result.success(issues.filter { it.id in ids })
        }

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ) = Result.success(Unit)

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ) = Result.success(Unit)
    }

    /** A runner whose turns wait until released; [cancelled] holds the issues whose turn was stopped. */
    private class Runner : AgentRunner {
        override val capabilities =
            RunnerCapabilities(
                continuation = false,
                loopTools = RunnerCapabilities.LoopTools.Files,
                usage = false,
                cost = false,
                occupancy = false,
                sandbox = false,
            )

        val calls = mutableListOf<String>()
        val inFlight = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override fun run(
            ctx: AttemptContext,
            prompt: String,
        ): Flow<RunnerEvent> {
            val identifier = ctx.issue.identifier
            calls += identifier
            val session = SessionId("th1-tu1")
            val turn = TurnId("tu1")
            return flow {
                emit(RunnerEvent.SessionStarted(session, threadId = "th1", turnId = turn))
                inFlight += identifier
                var completed = false
                try {
                    gates.getOrPut(identifier) { CompletableDeferred() }.await()
                    completed = true
                    emit(RunnerEvent.TurnCompleted(session, turn, exitCode = 0))
                } finally {
                    inFlight -= identifier
                    if (!completed) cancelled += identifier
                }
            }
        }
    }

    private val sink =
        object : EventSink {
            override suspend fun record(event: RecordedEvent): Result<Unit> {
                events += event
                return Result.success(Unit)
            }
        }

    private val parked =
        object : ParkedIssues {
            override suspend fun park(issue: ParkedIssue) = Result.success(Unit)

            override suspend fun list(projectId: ProjectId) = Result.success(emptyList<ParkedIssue>())

            override suspend fun unpark(
                projectId: ProjectId,
                issueId: String,
            ) = Result.success(null)
        }

    private fun workflow(agent: AgentConfig = AgentConfig()) =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = RunnerSettings("command", mapOf("command" to mapOf("command" to "./agent.sh"))),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = agent),
        )

    private fun workspace(identifier: String): Path = root.resolve("workspaces").resolve(identifier)

    private fun TestScope.orchestrator(
        tracker: Tracker,
        runner: Runner,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        val clock =
            object : Clock {
                override fun now(): Instant = start + testScheduler.currentTime.milliseconds
            }
        var next = 0
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, ShellHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    /** The next poll of [Orchestrator.runUntilCancelled], one polling interval later. */
    private fun TestScope.nextPoll() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun finished(identifier: String): RecordedEvent =
        events.single { it.kind == "attempt_finished" && it.issueIdentifier == identifier }

    @Test
    fun `a terminal state stops the running agent and removes its workspace`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-1")))
            val runner = Runner()
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()
            assertEquals(setOf("i-1"), runner.inFlight)
            assertTrue(workspace("i-1").exists())

            tracker.move("i-1", "done")
            nextPoll()

            assertEquals(setOf("i-1"), runner.cancelled)
            assertFalse(workspace("i-1").exists(), "the workspace of a terminal issue is removed")
            val end = finished("i-1")
            assertEquals("canceled", end.data["outcome"])
            assertEquals("terminal", end.data["reason"])
            val phase = events.single { it.kind == "phase" && it.phase == "CanceledByReconciliation" }
            assertEquals("done", phase.data["issue_state"])
            assertTrue(events.none { it.kind == "retry_scheduled" }, events.map { it.kind }.toString())
            job.cancelAndJoin()
        }

    @Test
    fun `a state neither active nor terminal stops the running agent and keeps its workspace`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-1")))
            val runner = Runner()
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()

            tracker.move("i-1", "review")
            nextPoll()

            assertEquals(setOf("i-1"), runner.cancelled)
            assertTrue(workspace("i-1").exists(), "the workspace of a handoff issue is kept")
            val end = finished("i-1")
            assertEquals("canceled", end.data["outcome"])
            assertEquals("not_active", end.data["reason"])
            assertTrue(events.none { it.kind == "retry_scheduled" }, events.map { it.kind }.toString())

            // Nothing dispatches it again while it stays out of the active states.
            nextPoll()
            assertEquals(listOf("i-1"), runner.calls)
            job.cancelAndJoin()
        }

    @Test
    fun `an issue gone from the tracker stops its running agent and keeps its workspace`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-1")))
            val runner = Runner()
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()

            tracker.remove("i-1")
            nextPoll()

            assertEquals(setOf("i-1"), runner.cancelled)
            assertTrue(workspace("i-1").exists())
            assertEquals("not_found", finished("i-1").data["reason"])
            job.cancelAndJoin()
        }

    @Test
    fun `an active state refresh updates the running entry state`() =
        runTest {
            // One slot for in_progress: i-1 starts in todo, then moves to in_progress while it runs.
            val agent = AgentConfig(maxConcurrentAgentsByState = mapOf("in_progress" to 1))
            val tracker = Tracker(listOf(issue("i-1")))
            val runner = Runner()
            val job = launch { orchestrator(tracker, runner, workflow(agent)).runUntilCancelled() }
            runCurrent()
            assertEquals(setOf("i-1"), runner.inFlight)

            tracker.move("i-1", "in_progress")
            tracker.issues += issue("i-2", state = "in_progress")
            nextPoll()

            assertEquals(setOf("i-1"), runner.inFlight, "i-1 keeps running and holds the in_progress slot")
            assertTrue(runner.cancelled.isEmpty())
            assertEquals(listOf("i-1"), runner.calls)
            job.cancelAndJoin()
        }

    @Test
    fun `reconciliation with no running issues is a no-op`() =
        runTest {
            val tracker = Tracker(emptyList())
            val orchestrator = orchestrator(tracker, Runner())

            orchestrator.tick()
            orchestrator.tick()

            assertEquals(0, tracker.byIdCalls)
        }

    @Test
    fun `a failed refresh keeps the running agents`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-1")))
            val runner = Runner()
            val job = launch { orchestrator(tracker, runner).runUntilCancelled() }
            runCurrent()

            tracker.move("i-1", "done")
            tracker.failByIds = true
            nextPoll()

            assertEquals(setOf("i-1"), runner.inFlight)
            assertTrue(workspace("i-1").exists())
            assertTrue(logLines.any { "event=reconcile" in it && "outcome=failed" in it }, logLines.joinToString("\n"))

            // The next poll that reads the tracker stops it.
            tracker.failByIds = false
            nextPoll()
            assertEquals(setOf("i-1"), runner.cancelled)
            assertFalse(workspace("i-1").exists())
            job.cancelAndJoin()
        }

    @Test
    fun `startup cleanup removes the workspaces of terminal issues only`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-done", "done"), issue("i-canceled", "canceled"), issue("i-review", "review")))
            listOf("i-done", "i-canceled", "i-review", "i-unknown").forEach { workspace(it).createDirectories() }

            orchestrator(tracker, Runner()).removeTerminalWorkspaces()

            assertEquals(listOf(listOf("done", "canceled")), tracker.statesAsked)
            assertFalse(workspace("i-done").exists())
            assertFalse(workspace("i-canceled").exists())
            assertTrue(workspace("i-review").exists())
            assertTrue(workspace("i-unknown").exists())
        }

    @Test
    fun `a failed terminal fetch at startup is logged and startup goes on`() =
        runTest {
            val tracker = Tracker(listOf(issue("i-done", "done"))).apply { failByStates = true }
            workspace("i-done").createDirectories()

            orchestrator(tracker, Runner()).removeTerminalWorkspaces()

            assertTrue(workspace("i-done").exists())
            assertTrue(logLines.any { "level=warn" in it && "event=startup_cleanup" in it }, logLines.joinToString("\n"))
        }
}
