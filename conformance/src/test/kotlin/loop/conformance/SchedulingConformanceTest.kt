package loop.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.orchestrator.Orchestrator
import loop.core.workflow.AgentConfig
import loop.core.workflow.CodexConfig
import loop.core.workflow.RunnerSettings
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Symphony spec 17.4, dispatch and scheduling: candidate sort order, eligibility (dispatchable,
 * required labels), global and per state concurrency, claims, stall detection and slot exhaustion,
 * against the public [Orchestrator] with in-memory ports and virtual time. Loop deviates from 8.2 and
 * 16.6 for due retries (bl-34): they get free slots before new candidates, and a slot requeue keeps
 * the attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingConformanceTest {
    private val root: Path = createTempDirectory("loop-scheduling-conformance").toRealPath()
    private val start = Instant.parse("2026-11-10T10:00:00Z")
    private val events = mutableListOf<RecordedEvent>()

    private fun issue(
        identifier: String,
        state: String = "todo",
        priority: Int? = 2,
        createdAt: Instant? = null,
        labels: List<String> = listOf("ready-for-agent"),
        blockedReason: String? = null,
    ) = Issue(
        id = "id-$identifier",
        identifier = identifier,
        title = "Title of $identifier",
        state = state,
        priority = priority,
        labels = labels,
        blockedBy = emptyList(),
        description = null,
        createdAt = createdAt,
        blockedReason = blockedReason,
    )

    /** Holds [issues]; returns all of them for any states, so the orchestrator's own filter is what is tested. */
    private class Tracker(
        val issues: MutableList<Issue>,
    ) : TrackerAdapter {
        override val definedStates = listOf("todo", "in_progress", "done", "canceled")

        override suspend fun fetchIssuesByStates(states: List<String>) = Result.success(issues.toList())

        override suspend fun fetchIssuesByIds(ids: List<String>) = Result.success(issues.filter { it.id in ids })

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ) = Result.success(Unit)

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ) = Result.success(Unit)
    }

    /**
     * A scripted runner: an issue in [failing] fails its turn at once, one in [silent] goes quiet after
     * starting, one in [instant] completes at once, any other waits for its gate before completing.
     */
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
        val failing = mutableSetOf<String>()
        val silent = mutableSetOf<String>()
        val instant = mutableSetOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var cancelledSilent = 0

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
                when (identifier) {
                    in failing -> emit(RunnerEvent.TurnFailed(session, turn, exitCode = 1, reason = "exit_code=1"))
                    in silent ->
                        try {
                            awaitCancellation()
                        } finally {
                            cancelledSilent++
                        }
                    in instant -> emit(RunnerEvent.TurnCompleted(session, turn, exitCode = 0))
                    else -> {
                        inFlight += identifier
                        try {
                            gates.getOrPut(identifier) { CompletableDeferred() }.await()
                            emit(RunnerEvent.TurnCompleted(session, turn, exitCode = 0))
                        } finally {
                            inFlight -= identifier
                        }
                    }
                }
            }
        }

        fun releaseAll() {
            inFlight.toList().forEach { gates.getOrPut(it) { CompletableDeferred() }.complete(Unit) }
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

    private fun workflow(
        agent: AgentConfig = AgentConfig(),
        codex: CodexConfig = CodexConfig(),
        requiredLabels: List<String> = listOf("ready-for-agent"),
    ) = WorkflowFile(
        path = root.resolve("WORKFLOW.md"),
        trackerKind = "files",
        trackerDir = root.resolve(".loop/issues"),
        activeStates = listOf("todo", "in_progress"),
        terminalStates = listOf("done", "canceled"),
        requiredLabels = requiredLabels,
        workspaceRoot = root.resolve("workspaces"),
        runner = RunnerSettings("command", mapOf("command" to mapOf("command" to "./agent.sh"))),
        pollingIntervalMs = 1000,
        promptTemplate = "Work on {{ issue.identifier }}",
        config = WorkflowConfig(agent = agent, codex = codex),
    )

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
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
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

    @Test
    fun `candidates are dispatched by priority, then oldest creation, then identifier`() =
        runTest {
            val issues =
                mutableListOf(
                    issue("i-d", priority = null, createdAt = start - 9_000.milliseconds),
                    issue("i-e", priority = 2, createdAt = null),
                    issue("i-f", priority = 2, createdAt = start - 5_000.milliseconds),
                    issue("i-a", priority = 2, createdAt = start - 5_000.milliseconds),
                    issue("i-b", priority = 1, createdAt = start - 1_000.milliseconds),
                    issue("i-c", priority = 1, createdAt = start - 2_000.milliseconds),
                )
            val runner = Runner().apply { instant += issues.map { it.identifier } }
            // One slot: each tick dispatches the first candidate; its continuation retry keeps it waiting.
            // Deviation from spec 8.2 (bl-34, spec decisions "Due retries before new candidates"): the spec order
            // holds among new candidates, which all are here, while an issue with a due retry would come first.
            val orchestrator = orchestrator(Tracker(issues), runner, workflow(AgentConfig(maxConcurrentAgents = 1)))

            repeat(issues.size) { orchestrator.tick() }

            assertEquals(listOf("i-c", "i-b", "i-a", "i-f", "i-e", "i-d"), runner.calls)
        }

    @Test
    fun `an issue that is not dispatchable is not eligible`() =
        runTest {
            val runner = Runner().apply { instant += listOf("i-1", "i-2") }
            val tracker = Tracker(mutableListOf(issue("i-1", blockedReason = "blocked_by i-0 (todo)"), issue("i-2")))

            orchestrator(tracker, runner).tick()

            assertEquals(listOf("i-2"), runner.calls)
            val skipped = events.single { it.kind == "candidate_skipped" }
            assertEquals("i-1", skipped.issueIdentifier)
            assertEquals("blocked_by i-0 (todo)", skipped.data["reason"])
        }

    @Test
    fun `required labels match case insensitively`() =
        runTest {
            val runner = Runner().apply { instant += listOf("i-1", "i-2") }
            val tracker = Tracker(mutableListOf(issue("i-1"), issue("i-2", labels = listOf("other"))))

            orchestrator(tracker, runner, workflow(requiredLabels = listOf(" Ready-For-Agent "))).tick()

            assertEquals(listOf("i-1"), runner.calls)
        }

    @Test
    fun `the global concurrency limit holds`() =
        runTest {
            val runner = Runner()
            val tracker = Tracker((1..5).map { issue("i-$it") }.toMutableList())
            val orchestrator = orchestrator(tracker, runner, workflow(AgentConfig(maxConcurrentAgents = 2)))

            val tick = async { orchestrator.tick() }
            runCurrent()

            assertEquals(setOf("i-1", "i-2"), runner.inFlight)
            runner.releaseAll()
            assertEquals(2, tick.await().attempts.size)
        }

    @Test
    fun `the per state concurrency limit holds`() =
        runTest {
            val runner = Runner()
            val issues = mutableListOf(issue("i-1", state = "in_progress"), issue("i-2", state = "in_progress"), issue("i-3"))
            val agent = AgentConfig(maxConcurrentAgentsByState = mapOf("in_progress" to 1))
            val orchestrator = orchestrator(Tracker(issues), runner, workflow(agent))

            val tick = async { orchestrator.tick() }
            runCurrent()

            assertEquals(setOf("i-1", "i-3"), runner.inFlight)
            runner.releaseAll()
            tick.await()
        }

    @Test
    fun `a claimed issue is not dispatched again while it runs`() =
        runTest {
            val runner = Runner()
            val orchestrator = orchestrator(Tracker(mutableListOf(issue("i-1"))), runner)
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(listOf("i-1"), runner.calls)
            job.cancelAndJoin()
        }

    @Test
    fun `a stalled agent is stopped and a retry is scheduled`() =
        runTest {
            val runner = Runner().apply { silent += "i-1" }
            val orchestrator =
                orchestrator(Tracker(mutableListOf(issue("i-1"))), runner, workflow(codex = CodexConfig(stallTimeoutMs = 2_000)))

            val tick = async { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(2_001)
            val attempt = tick.await().attempts.single()

            assertEquals("stalled", attempt.reason)
            assertEquals(1, runner.cancelledSilent)
            val retry = events.single { it.kind == "retry_scheduled" }
            assertEquals("error", retry.data["kind"])
            assertEquals("stalled", retry.data["error"])
        }

    @Test
    fun `a due retry with no free slot is requeued with a reason`() =
        runTest {
            val runner = Runner().apply { failing += "i-1" }
            val tracker = Tracker(mutableListOf(issue("i-1"), issue("i-2")))
            val orchestrator = orchestrator(tracker, runner, workflow(AgentConfig(maxConcurrentAgents = 1)))
            val job = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(setOf("i-2"), runner.inFlight)

            advanceTimeBy(9_000)
            runCurrent()

            val requeued = events.filter { it.kind == "retry_scheduled" && it.issueIdentifier == "i-1" }.last()
            assertEquals("no available orchestrator slots", requeued.data["error"])
            assertEquals("1", requeued.data["failures"])
            // Deviation from spec 16.6 (bl-34, spec decisions "Due retries before new candidates"): the requeue
            // keeps the attempt and stays due from its first due time, instead of attempt + 1 and its backoff. Its due
            // time moves one poll interval ahead, to wake the daemon.
            assertEquals("1", requeued.data["attempt"])
            assertEquals("1000", requeued.data["delay_ms"])
            assertTrue(runner.calls.count { it == "i-1" } == 1)
            job.cancelAndJoin()
        }
}
