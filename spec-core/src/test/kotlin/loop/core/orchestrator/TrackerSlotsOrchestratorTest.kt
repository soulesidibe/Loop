package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m4-15, story 115: `agent.max_concurrent_agents_by_tracker` with a `files` like primary tracker `local` and an extra
 * tracker `gh`. A tracker at its limit waits while the other tracker's issues take the free slots, a due retry included.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerSlotsOrchestratorTest {
    private val root: Path = createTempDirectory("loop-tracker-slots").toRealPath()
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

    /** Three slots in all, one for the `gh` tracker. */
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
            config = WorkflowConfig(agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByTracker = mapOf("gh" to 1))),
            trackerId = "local",
            extraTrackers = listOf(ghEntry),
        )

    private val trackers =
        TrackerPoll.of(TrackerRegistry.build(workflow.trackers) { entry -> if (entry.id == "local") local else github })

    /** Identifiers whose first attempt fails; every other attempt holds its slot until the test ends. */
    private val failFirst = mutableSetOf<String>()
    private val calls = mutableListOf<String>()
    private val hold = CompletableDeferred<Unit>()

    private val runner =
        FakeRunner { ctx, _ ->
            val identifier = ctx.issue.identifier
            val first = identifier !in calls
            calls += identifier
            if (first && identifier in failFirst) {
                failingTurn()
            } else {
                flow {
                    emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                    hold.await()
                    emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
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

    private fun ghIssue(
        identifier: String,
        priority: Int = 2,
    ) = issue(identifier, state = "open", labels = listOf("gh-ready"), priority = priority)

    @Test
    fun `with a limit of 1 on gh and two of its issues eligible, one runs and the local issue takes the next free slot`() =
        runTest {
            github.issues += ghIssue("26", priority = 1)
            github.issues += ghIssue("27", priority = 1)
            local.issues += issue("l-1", priority = 3)

            val tick = launch { orchestrator().tick() }
            runCurrent()

            // 27 comes before l-1 in the spec's order, but gh is at its limit: l-1 takes the slot, one slot stays free.
            assertEquals(listOf("26", "l-1"), calls)
            val skipped = sink.events.single { it.kind == "candidate_skipped" }
            assertEquals("27", skipped.issueIdentifier)
            assertEquals("no_available_slots", skipped.data["reason"])
            hold.complete(Unit)
            tick.join()
        }

    @Test
    fun `a due retry blocked by its tracker's limit waits while the other tracker's issue takes the free slot (bl-77 finding 2)`() =
        runTest {
            failFirst += "26"
            github.issues += ghIssue("26")
            val orchestrator = orchestrator()

            // 26 fails and is due at 10 s; 27 then takes the one gh slot and holds it.
            orchestrator.tick()
            github.issues += ghIssue("27")
            val holder = launch { orchestrator.tick() }
            runCurrent()
            local.issues += issue("l-1")
            advanceTimeBy(10_000)
            val other = launch { orchestrator.tick() }
            runCurrent()

            // Two of the three slots are free, but gh is at its limit: l-1 gets one and the retry of 26 waits.
            assertEquals(listOf("26", "27", "l-1"), calls)
            val requeue = sink.events.last { it.kind == "retry_scheduled" && it.issueIdentifier == "26" }
            assertEquals("no available orchestrator slots", requeue.data["error"])
            assertEquals("1", requeue.data["attempt"])
            hold.complete(Unit)
            holder.join()
            other.join()
        }

    private companion object {
        val SESSION = loop.api.SessionId("th1-tu1")
        val TURN = loop.api.TurnId("tu1")
    }
}
