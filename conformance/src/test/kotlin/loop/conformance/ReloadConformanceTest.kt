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
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.workflowHash
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Symphony spec 17.1 items 2 and 3, dynamic reload (6.2): a changed `WORKFLOW.md` is re-read and re-applied
 * without restart, and an invalid one keeps the last known good config with an operator visible error. Real
 * files, the real loader and preflight, and the public [Orchestrator] with in-memory ports.
 */
class ReloadConformanceTest {
    private val root: Path = createTempDirectory("loop-reload-conformance").toRealPath()
    private val home: Path = root.resolve("home").createDirectories()
    private val file: Path = root.resolve("WORKFLOW.md")
    private val events = mutableListOf<RecordedEvent>()
    private val logLines = mutableListOf<String>()
    private val prompts = mutableListOf<String>()

    /** The issue the tracker returns; each test changes it between ticks, so the continuation retry never fires. */
    private var current = issue("m1-01")

    private fun issue(identifier: String) =
        Issue(
            id = "id-$identifier",
            identifier = identifier,
            title = "Title",
            state = "todo",
            priority = 1,
            labels = listOf("ready-for-agent"),
            blockedBy = emptyList(),
            description = null,
            createdAt = null,
        )

    private fun write(
        maxTurns: String,
        prompt: String = "Work on {{ issue.identifier }}",
    ) = file.writeText(
        """
        |---
        |tracker:
        |  kind: files
        |  active_states: [todo]
        |  terminal_states: [done]
        |  required_labels: [ready-for-agent]
        |workspace:
        |  root: ./workspaces
        |runner:
        |  kind: command
        |runners:
        |  command:
        |    command: ./agent.sh
        |agent:
        |  max_turns: $maxTurns
        |---
        |$prompt
        |
        """.trimMargin(),
    )

    private val tracker =
        object : TrackerAdapter {
            override val definedStates = listOf("todo", "done")

            override suspend fun fetchIssuesByStates(states: List<String>) = Result.success(listOf(current))

            override suspend fun fetchIssuesByIds(ids: List<String>) = Result.success(listOf(current).filter { it.id in ids })

            override suspend fun moveIssue(
                issueId: String,
                toState: String,
            ) = Result.success(Unit)

            override suspend fun addComment(
                issueId: String,
                comment: IssueComment,
            ) = Result.success(Unit)
        }

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

    /** The orchestrator `loop run` builds: the startup preflight's workflow, the same preflight on every tick. */
    private fun orchestrator(): Orchestrator {
        val preflight = Preflight(WorkflowLoader(env = emptyMap(), home = home), file, TemplateEngine()) { null }
        val workflow = assertIs<PreflightResult.Ok>(runBlocking { preflight.check() }).workflow
        val clock = Clock.System
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
            loopHome = root.resolve("loop-home"),
            hostPath = "/usr/bin",
            preflight = preflight,
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            workspacesFor = { WorkspaceManager(Workspaces(it.workspaceRoot), it.config.hooks, ShellHookRunner()) },
        )
    }

    private fun turnLimits(): List<String?> = events.filter { it.kind == "turn_completed" }.map { it.data["max_turns"] }

    private fun versions(): List<String?> = events.filter { it.kind == "attempt_started" }.map { it.data["config_version"] }

    @Test
    fun `17_1 item 2 workflow file changes are detected and re-read and re-applied without restart`() =
        runBlocking {
            write(maxTurns = "3")
            val first = workflowHash(file.readText())
            val orchestrator = orchestrator()

            orchestrator.tick()
            write(maxTurns = "4", prompt = "Changed prompt for {{ issue.identifier }}")
            val second = workflowHash(file.readText())
            current = issue("m1-02")
            orchestrator.tick()

            assertEquals(listOf("3", "4"), turnLimits())
            assertEquals(listOf("Work on m1-01", "Changed prompt for m1-02"), prompts)
            assertEquals(listOf(first, second), versions())
            val reloaded = events.single { it.kind == "config_reloaded" }
            assertEquals(second, reloaded.data["config_version"])
            assertEquals(first, reloaded.data["previous_version"])
            assertTrue("-  max_turns: 3\n+  max_turns: 4\n" in reloaded.data.getValue("diff"))
        }

    @Test
    fun `17_1 item 3 an invalid workflow reload keeps the last known good config and emits an operator visible error`() =
        runBlocking {
            write(maxTurns = "3")
            val good = workflowHash(file.readText())
            val orchestrator = orchestrator()

            write(maxTurns = "0")
            orchestrator.tick()

            assertEquals(emptyList(), prompts, "no attempt starts from an invalid file")
            val rejected = events.single { it.kind == "config_reload_failed" }
            assertEquals("invalid_config", rejected.data["error"])
            assertEquals("agent.max_turns must be a positive integer, got 0", rejected.data["message"])
            assertEquals(good, rejected.data["active_version"])
            assertEquals(workflowHash(file.readText()), rejected.data["config_version"])
            assertTrue(
                logLines.any {
                    "level=error event=workflow_reload outcome=failed" in it &&
                        "reason=\"agent.max_turns must be a positive integer, got 0\"" in it
                },
                logLines.joinToString("\n"),
            )

            // The service kept running on the last good config: back to its text, the next attempt runs with it.
            write(maxTurns = "3")
            orchestrator.tick()

            assertEquals(listOf("3"), turnLimits())
            assertEquals(listOf(good), versions())
            assertTrue(events.none { it.kind == "config_reloaded" })
        }
}
