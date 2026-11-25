package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindContext
import loop.core.workflow.KindMatch
import loop.core.workflow.KindTemplate
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-19: the orchestrator renders the template of the attempt's kind with its context sources, checked against the
 * workspace at render time. A missing source is listed as missing and recorded, and the attempt goes on.
 */
class ContextSourcesOrchestratorTest {
    private val root: Path = createTempDirectory("loop-context-sources-orchestrator").toRealPath()

    private val body = "Code {{ issue.identifier }}.{% if loop.context_sources %}\nSources:\n{{ loop.context_sources }}{% endif %}"

    private fun workflow(kinds: KindsConfig): WorkflowFile =
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
            promptTemplate = body,
            config = WorkflowConfig(kinds = kinds),
        )

    private fun orchestrator(
        workflow: WorkflowFile,
        issues: List<loop.api.Issue>,
        runner: FakeRunner,
        sink: FakeSink,
    ): Orchestrator {
        val clock = FixedClock()
        var ids = 0
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(issues),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++ids}" },
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

    private fun List<RecordedEvent>.buildingPrompt(): RecordedEvent =
        single { it.kind == "phase" && it.phase == AttemptPhase.BuildingPrompt.name }

    @Test
    fun `the kind's sources render in the prompt, a missing one is listed and recorded, and the attempt goes on`() =
        runTest {
            val code = KindConfig(checks = emptyList(), context = KindContext(listOf("present.md", "gone.md")))
            val workflow = workflow(KindsConfig(code))
            workflow.workspaceRoot
                .resolve("m4-19")
                .createDirectories()
                .resolve("present.md")
                .writeText("here")
            val runner = FakeRunner()
            val sink = FakeSink()

            val summary = orchestrator(workflow, listOf(issue("m4-19")), runner, sink).tick().attempts.single()

            val prompt = runner.calls.single().second
            assertEquals("Code m4-19.\nSources:\n- present.md\n- gone.md (missing from the workspace)", prompt)
            val missing = sink.events.single { it.kind == "context_source_missing" }
            assertEquals(mapOf("path" to "gone.md", "kind" to "code"), missing.data)
            assertEquals("id-m4-19", missing.issueId)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            val building = sink.events.buildingPrompt()
            assertEquals(
                "\nSources:\n- present.md\n- gone.md (missing from the workspace)".length.toString(),
                building.data["slot_context_sources_chars"],
            )
            assertTrue(building.data.getValue("slot_context_sources_tokens").toInt() > 0)
        }

    @Test
    fun `an issue of a kind with its own template gets that template, and a code issue gets the body without sources`() =
        runTest {
            val content =
                KindConfig(
                    checks = emptyList(),
                    match = KindMatch(label = "content"),
                    template = KindTemplate(".loop/templates/content.md", "Content {{ issue.identifier }}"),
                )
            val workflow = workflow(KindsConfig(linkedMapOf("code" to KindConfig(checks = emptyList()), "content" to content)))
            val runner = FakeRunner()
            val sink = FakeSink()
            val issues = listOf(issue("c-1", labels = listOf("ready-for-agent", "content")), issue("k-1"))

            orchestrator(workflow, issues, runner, sink).tick()

            val prompts = runner.calls.map { it.second }.toSet()
            assertEquals(setOf("Content c-1", "Code k-1."), prompts)
            assertEquals(emptyList(), sink.events.filter { it.kind == "context_source_missing" })
        }
}
