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
import loop.api.RetryState
import loop.api.RetryStates
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.machine.MachineLimits
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
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

/**
 * bl-77 findings 1 and 4 (m4-15): while slots stay full, a waiting retry records `retry_scheduled` and its WARN line
 * once per change of reason, writes its retry state only then, and is refreshed by id at most once per poll interval,
 * however many ticks come in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryRequeueVolumeTest {
    private val root: Path = createTempDirectory("loop-requeue-volume").toRealPath()
    private val logLines = mutableListOf<String>()
    private val sink = FakeSink()
    private val machine = FakeMachine(MachineLimits(maxConcurrentAgents = 1))

    /** The identifier of every retry state written to the store, in order. */
    private val saves = mutableListOf<String>()
    private val stored = FakeRetryStates()
    private val retryStates =
        object : RetryStates by stored {
            override suspend fun saveRetryState(state: RetryState): Result<Unit> {
                saves += state.identifier
                return stored.saveRetryState(state)
            }
        }

    /** One slot and a 30 s poll, as `WORKFLOW.md` gives them. */
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
            pollingIntervalMs = POLL_MS,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxConcurrentAgents = 1), retries = RetriesConfig(maxAttempts = 5)),
        )

    private fun TestScope.orchestrator(runner: FakeRunner): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
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
            retryStates = retryStates,
            machine = machine,
        )
    }

    private val tracker = FakeTracker(listOf(issue("m1-02")))

    /** The by id reads that asked for m1-02. */
    private fun refreshes(): Int = tracker.requestedIds.count { "id-m1-02" in it }

    /** The errors of the slot requeues of m1-02, one per `retry_scheduled` recorded. */
    private fun requeues(): List<String?> =
        sink.events
            .filter { it.kind == "retry_scheduled" && it.issueIdentifier == "m1-02" }
            .map { it.data["error"] }
            .filter { it != "exit_code=1" }

    /** The WARN lines of the slot requeues of m1-02. */
    private fun warnLines(): List<String> =
        logLines.filter {
            "level=warn" in it && "event=retry" in it && "outcome=scheduled" in it && "m1-02" in it && "exit_code=1" !in it
        }

    private suspend fun TestScope.ticksEvery5s(
        orchestrator: Orchestrator,
        count: Int,
    ) = repeat(count) {
        advanceTimeBy(5_000)
        orchestrator.tick()
    }

    @Test
    fun `a waiting retry records its requeue once per reason and is refreshed by id at most once per poll interval`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val runner =
                FakeRunner { ctx, _ ->
                    val identifier = ctx.issue.identifier
                    val first = identifier !in started
                    started += identifier
                    when {
                        identifier == "m1-02" && first -> failingTurn()
                        identifier == "m1-03" && first ->
                            flow {
                                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                                gate.await()
                                emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                            }
                        else -> succeedingTurn()
                    }
                }
            val orchestrator = orchestrator(runner)

            // m1-02 fails at 0 s and is due at 10 s; m1-03 takes the only slot and holds it.
            orchestrator.tick()
            tracker.issues += issue("m1-03")
            val holder = launch { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(5_000)
            val savesBefore = saves.count { it == "m1-02" }
            // The read of m1-02 after its failed attempt is no retry refresh.
            val readsBefore = refreshes()

            // Eight ticks, 10 s to 45 s, with every slot full: one requeue recorded, one write, refreshed at 10 s and 40 s.
            ticksEvery5s(orchestrator, 8)

            assertEquals(listOf("m1-02", "m1-03"), started)
            assertEquals(listOf<String?>(NO_SLOTS), requeues())
            assertEquals(1, warnLines().size, warnLines().joinToString("\n"))
            assertEquals(1, saves.count { it == "m1-02" } - savesBefore, "store writes")
            assertEquals(2, refreshes() - readsBefore, "by id refreshes")

            // m1-03 ends and frees the orchestrator slot, but another process takes the only machine slot: the reason
            // changes once, then six more ticks, 50 s to 75 s, record nothing new and refresh once more, at 70 s.
            gate.complete(Unit)
            holder.join()
            machine.slots += "another-process"
            ticksEvery5s(orchestrator, 6)

            assertEquals(listOf<String?>(NO_SLOTS, NO_MACHINE_SLOTS), requeues())
            assertEquals(2, warnLines().size, warnLines().joinToString("\n"))
            assertEquals(2, saves.count { it == "m1-02" } - savesBefore, "store writes")
            assertEquals(3, refreshes() - readsBefore, "by id refreshes")

            // The machine slot frees: the waiting retry is dispatched with its spec attempt.
            machine.slots -= "another-process"
            advanceTimeBy(5_000)
            orchestrator.tick()
            assertEquals(listOf("m1-02", "m1-03", "m1-02"), started)
        }

    private companion object {
        const val POLL_MS = 30_000L
        const val NO_SLOTS = "no available orchestrator slots"
        const val NO_MACHINE_SLOTS = "no available machine slots"
        val SESSION = loop.api.SessionId("th1-tu1")
        val TURN = loop.api.TurnId("tu1")
    }
}
