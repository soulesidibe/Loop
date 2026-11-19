package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * bl-19: `loop.workspace_reused` is true when the workspace of the issue already existed when the attempt
 * prepared it, false when the attempt created it. It is a fact about the folder: `attempt` keeps its value.
 */
class WorkspaceReusedPromptTest {
    private val root: Path = createTempDirectory("loop-workspace-reused").toRealPath()

    private val prompt =
        "Work on {{ issue.identifier }}. attempt=[{{ attempt }}] reused=[{{ loop.workspace_reused }}]" +
            "{% if attempt or loop.workspace_reused %} Read the earlier work first.{% endif %}"

    private val workflow: WorkflowFile =
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
            promptTemplate = prompt,
        )

    private fun orchestrator(
        runner: FakeRunner,
        sink: FakeSink,
    ): Orchestrator {
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("bl-19"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a1" },
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

    private fun RecordedEvent.slotTotal(): Int =
        data.filterKeys { it.startsWith("slot_") && it.endsWith("_chars") }.values.sumOf { it.toInt() }

    @Test
    fun `a first dispatch into a workspace that already exists renders workspace_reused true with attempt null`() =
        runTest {
            // What a restart leaves behind: the folder of the attempt that died, and no queued retry.
            workflow.workspaceRoot.resolve("bl-19").createDirectories()
            val runner = FakeRunner()
            val sink = FakeSink()

            orchestrator(runner, sink).tick()

            val rendered = runner.calls.single().second
            assertEquals("Work on bl-19. attempt=[] reused=[true] Read the earlier work first.", rendered)
            val building = sink.events.buildingPrompt()
            assertEquals(rendered.length.toString(), building.data["prompt_chars"])
            assertEquals(rendered.length, building.slotTotal())
        }

    @Test
    fun `a dispatch that creates the workspace renders workspace_reused false`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()

            orchestrator(runner, sink).tick()

            val rendered = runner.calls.single().second
            assertEquals("Work on bl-19. attempt=[] reused=[false]", rendered)
            assertEquals(rendered.length, sink.events.buildingPrompt().slotTotal())
        }
}
