package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.Issue
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m1-17: recording the prompt slot sizes leaves the rendered prompt as it was. The golden file was
 * rendered by the Orchestrator at fbf637a, before slot sizes existed, from a copy of Loop's own
 * template at that commit. A mismatch writes the prompt rendered now to `build/prompt-golden-actual.md`.
 *
 * The copy `own-template.md` follows the root `WORKFLOW.md`, which `OwnWorkflowTest` checks. m1-42 added
 * the sync with `main` to its later attempt section only, which turn 1 of a first attempt never renders.
 * bl-02 moved that sync into "Your workspace", so it renders on every dispatch, and the golden file gained
 * that one bullet, rendered by this test. On 2026-11-18 the root template gained the rotation and session brief
 * blocks, which a first turn renders as blank lines, and the rule to commit after each passing step; the golden
 * file gained those, rendered by this test. On 2026-11-25 the root template gained the `loop.answer` block, which
 * a first turn renders as one more blank line; the golden file gained it, rendered by this test. The rest of the
 * golden file is still the prompt of fbf637a.
 */
class PromptRenderUnchangedTest {
    private val root: Path = createTempDirectory("loop-prompt-golden").toRealPath()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/prompt/$name")) { "missing test resource prompt/$name" }.readText()

    private fun workflow(template: String): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = template,
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 1), hooks = HooksConfig()),
        )

    private val goldenIssue: Issue =
        Issue(
            id = "id-m1-99",
            identifier = "m1-99",
            title = "A golden issue, with {{ braces }} and <tags>",
            state = "todo",
            priority = 2,
            labels = listOf("ready-for-agent", "area:prompt"),
            blockedBy = emptyList(),
            description =
                """
                ## Parent

                [Loop, M1 to M3](../specs/loop-m1-to-m3.md)

                ## What

                A description long enough to matter, with a list:

                - one item, with `code`;
                - another item, with {% raw %} liquid looking text.

                ## Acceptance criteria

                - [ ] `./gradlew build` passes.
                """.trimIndent(),
            createdAt = null,
        )

    @Test
    fun `the turn 1 prompt of Loop's own template is byte for byte the golden one`() =
        runTest {
            val workflow = workflow(resource("own-template.md"))
            val runner = FakeRunner()
            val clock = FixedClock()
            val orchestrator =
                Orchestrator(
                    workflow = workflow,
                    tracker = FakeTracker(listOf(goldenIssue)),
                    runner = runner,
                    sink = FakeSink(),
                    log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                    clock = clock,
                    workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
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

            orchestrator.tick()

            val actual = runner.calls.single().second
            val expected = resource("own-template.golden.md")
            if (actual != expected) {
                Path("build").createDirectories()
                Path("build/prompt-golden-actual.md").writeText(actual)
            }
            assertEquals(expected, actual)
        }
}
