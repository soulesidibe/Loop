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
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.trackers.NamespacedId
import loop.core.trackers.TrackerEntry
import loop.core.trackers.TrackerPoll
import loop.core.trackers.TrackerRegistry
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m4-13, story 113: the orchestrator polls a `files` like primary tracker `local` and an extra tracker `gh` each on its
 * own, with its own states and `required_labels`, merges their candidates in the spec's order, and a tracker that fails
 * is logged and skipped while the other's issues still dispatch and reconcile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiTrackerOrchestratorTest {
    private val root: Path = createTempDirectory("loop-multi-tracker").toRealPath()
    private val logLines = mutableListOf<String>()
    private val sink = FakeSink()

    private val local = FakeTracker(emptyList()).apply { filterByStates = true }
    private val github = FakeTracker(emptyList()).apply { filterByStates = true }

    private val ghEntry =
        TrackerEntry(
            id = "gh",
            kind = "github",
            dir = root,
            settings = null,
            activeStates = listOf("open"),
            terminalStates = listOf("closed"),
            requiredLabels = listOf("gh-ready"),
        )

    private val workflow =
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
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxConcurrentAgents = 5)),
            trackerId = "local",
            extraTrackers = listOf(ghEntry),
        )

    private val trackers =
        TrackerPoll.of(TrackerRegistry.build(workflow.trackers) { entry -> if (entry.id == "local") local else github })

    /** A runner whose turns wait until released; [cancelled] holds the identifiers whose turn was stopped. */
    private val calls = mutableListOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val cancelled = mutableSetOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val runner =
        FakeRunner { ctx, _ ->
            val identifier = ctx.issue.identifier
            calls += identifier
            flow {
                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                inFlight += identifier
                var completed = false
                try {
                    gates.getOrPut(identifier) { CompletableDeferred() }.await()
                    completed = true
                    emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                } finally {
                    inFlight -= identifier
                    if (!completed) cancelled += identifier
                }
            }
        }

    private fun TestScope.orchestrator(): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = trackers,
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

    private fun TestScope.nextPoll() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun workspace(
        trackerId: String,
        identifier: String,
    ): Path = root.resolve("workspaces").resolve(NamespacedId.workspaceKey("$trackerId:x", identifier, "local").value)

    private fun finished(identifier: String) = sink.events.single { it.kind == "attempt_finished" && it.issueIdentifier == identifier }

    @Test
    fun `each tracker is polled with its own states, its candidates filtered by its own labels and merged in the spec's order`() =
        runTest {
            local.issues += issue("l-1", priority = 3)
            local.issues += issue("l-2", labels = listOf("gh-ready"), priority = 1)
            github.issues += issue("26", state = "open", labels = listOf("gh-ready"), priority = 2)
            github.issues += issue("27", state = "open", labels = listOf("ready-for-agent"), priority = 1)
            github.issues += issue("28", state = "todo", labels = listOf("gh-ready"), priority = 1)
            gates.getOrPut("l-1") { CompletableDeferred() }.complete(Unit)
            gates.getOrPut("26") { CompletableDeferred() }.complete(Unit)

            val result = orchestrator().tick()

            assertEquals(listOf(listOf("todo")), local.requestedStates)
            assertEquals(listOf(listOf("open")), github.requestedStates)
            // gh:26 (priority 2) before local:l-1 (priority 3). l-2 lacks local's label and 27 lacks gh's, though each
            // holds the other tracker's; 28 is in local's active state, not gh's.
            assertEquals(listOf("26", "l-1"), calls)
            assertEquals(listOf("gh:id-26", "local:id-l-1"), result.attempts.map { it.dispatchedIssue.id })
        }

    @Test
    fun `a tracker that fails its poll is logged by its id and the other tracker's candidates still dispatch and reconcile`() =
        runTest {
            local.issues += issue("l-1")
            github.issues += issue("26", state = "open", labels = listOf("gh-ready"))
            github.fetchFailure = TrackerError.Status(503, "unavailable")
            val job = launch { orchestrator().runUntilCancelled() }
            runCurrent()

            assertEquals(listOf("l-1"), calls)
            val failed = logLines.filter { "event=poll" in it && "outcome=failed" in it }
            assertTrue(failed.isNotEmpty() && failed.all { "tracker=gh" in it }, logLines.joinToString("\n"))

            // The github tracker comes back: its issue dispatches. Then it fails its by id read while the primary's issue
            // reaches a terminal state: the primary's attempt stops, the github one keeps running.
            github.fetchFailure = null
            nextPoll()
            assertEquals(setOf("l-1", "26"), inFlight)
            github.fetchByIdsFailure = TrackerError.Status(503, "unavailable")
            local.issues[0] = local.issues[0].copy(state = "done")
            nextPoll()

            assertEquals(setOf("l-1"), cancelled)
            assertEquals(setOf("26"), inFlight)
            assertEquals("terminal", finished("l-1").data["reason"])
            assertTrue(
                logLines.any { "event=reconcile" in it && "outcome=failed" in it && "tracker=gh" in it },
                logLines.joinToString("\n"),
            )
            job.cancelAndJoin()
        }

    @Test
    fun `each tracker is reconciled with its own terminal states`() =
        runTest {
            local.issues += issue("l-1")
            github.issues += issue("26", state = "open", labels = listOf("gh-ready"))
            val job = launch { orchestrator().runUntilCancelled() }
            runCurrent()
            assertEquals(setOf("l-1", "26"), inFlight)
            assertTrue(workspace("local", "l-1").exists() && workspace("gh", "26").exists())

            // closed is terminal for gh only: for the primary tracker it is neither active nor terminal.
            local.issues[0] = local.issues[0].copy(state = "closed")
            github.issues[0] = github.issues[0].copy(state = "closed")
            nextPoll()

            assertEquals(setOf("l-1", "26"), cancelled)
            assertEquals("not_active", finished("l-1").data["reason"])
            assertEquals("terminal", finished("26").data["reason"])
            assertTrue(workspace("local", "l-1").exists(), "the primary's issue left its active states, its workspace stays")
            assertFalse(workspace("gh", "26").exists(), "the github issue is terminal, its workspace goes")
            job.cancelAndJoin()
        }

    @Test
    fun `startup cleanup reads each tracker with its own terminal states and a failing one does not stop the other`() =
        runTest {
            local.issues += issue("l-1", state = "done")
            local.issues += issue("l-2", state = "closed")
            github.issues += issue("26", state = "closed")
            listOf(workspace("local", "l-1"), workspace("local", "l-2"), workspace("gh", "26")).forEach { it.createDirectories() }

            orchestrator().removeTerminalWorkspaces()

            assertEquals(listOf(listOf("done")), local.requestedStates)
            assertEquals(listOf(listOf("closed")), github.requestedStates)
            assertFalse(workspace("local", "l-1").exists())
            assertTrue(workspace("local", "l-2").exists())
            assertFalse(workspace("gh", "26").exists())

            local.fetchFailure = TrackerError.Status(503, "unavailable")
            github.issues += issue("27", state = "closed")
            workspace("gh", "27").createDirectories()
            orchestrator().removeTerminalWorkspaces()

            assertFalse(workspace("gh", "27").exists())
            assertTrue(
                logLines.any { "event=startup_cleanup" in it && "outcome=failed" in it && "tracker=local" in it },
                logLines.joinToString("\n"),
            )
        }

    private companion object {
        val SESSION = loop.api.SessionId("th1-tu1")
        val TURN = loop.api.TurnId("tu1")
    }
}
