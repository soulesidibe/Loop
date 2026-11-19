package loop.conformance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Symphony spec 17.4, retry and backoff: a normal worker exit schedules a continuation retry,
 * attempt 1, after 1 s (spec 16.6), against the public [Orchestrator] with in-memory ports.
 */
class ContinuationRetryConformanceTest {
    private val root: Path = createTempDirectory("loop-continuation-conformance").toRealPath()
    private val start = Instant.parse("2026-11-08T10:00:00Z")
    private var elapsed: Duration = Duration.ZERO
    private val clock =
        object : Clock {
            override fun now(): Instant = start + elapsed
        }
    private val events = mutableListOf<RecordedEvent>()
    private val prompts = mutableListOf<String>()

    private val issue =
        Issue(
            id = "id-1",
            identifier = "m1-06",
            title = "Title",
            state = "todo",
            priority = 1,
            labels = listOf("ready-for-agent"),
            blockedBy = emptyList(),
            description = null,
            createdAt = null,
        )

    private val tracker =
        object : TrackerAdapter {
            override val definedStates = listOf("todo", "done")

            override suspend fun fetchIssuesByStates(states: List<String>) = Result.success(listOf(issue))

            override suspend fun fetchIssuesByIds(ids: List<String>) = Result.success(listOf(issue))

            override suspend fun moveIssue(
                issueId: String,
                toState: String,
            ) = Result.success(Unit)

            override suspend fun addComment(
                issueId: String,
                comment: IssueComment,
            ) = Result.success(Unit)
        }

    /** A runner whose every run is one turn that exits 0. */
    private val runner =
        object : AgentRunner {
            override val capabilities =
                RunnerCapabilities(
                    continuation = false,
                    loopTools = RunnerCapabilities.LoopTools.Files,
                    usage = false,
                    cost = false,
                    occupancy = false,
                    sandbox = false,
                )

            override fun run(
                ctx: AttemptContext,
                prompt: String,
            ): Flow<RunnerEvent> {
                prompts += prompt
                val session = SessionId("th1-tu1")
                return flowOf(
                    RunnerEvent.SessionStarted(session, threadId = "th1", turnId = TurnId("tu1")),
                    RunnerEvent.TurnCompleted(session, TurnId("tu1"), exitCode = 0),
                )
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

    private val workflow =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = RunnerSettings("command", mapOf("command" to mapOf("command" to "./agent.sh"))),
            pollingIntervalMs = 1000,
            promptTemplate = "{% if attempt %}attempt {{ attempt }}{% else %}first{% endif %}",
            config = WorkflowConfig(),
        )

    private fun orchestrator(): Orchestrator {
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
    fun `a normal exit schedules a continuation retry, attempt 1, after 1 s`() =
        runBlocking {
            val orchestrator = orchestrator()

            orchestrator.tick()

            val scheduled = events.single { it.kind == "retry_scheduled" }
            assertEquals("continuation", scheduled.data["kind"])
            assertEquals("1", scheduled.data["attempt"])
            assertEquals("1000", scheduled.data["delay_ms"])
            assertNull(scheduled.data["error"])

            elapsed += 999.milliseconds
            orchestrator.tick()
            assertEquals(listOf("first"), prompts, "dispatched before the continuation was due")

            elapsed += 1.milliseconds
            orchestrator.tick()
            assertEquals(listOf("first", "attempt 1"), prompts)
        }
}
