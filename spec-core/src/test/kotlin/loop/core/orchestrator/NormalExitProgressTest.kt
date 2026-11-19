package loop.core.orchestrator

import loop.api.Issue
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The progress rule of the no progress counter (m1-27): a change of state or labels, or an issue that left the active states. */
class NormalExitProgressTest {
    private val root: Path = Path.of("/tmp/loop-normal-exit-progress")

    private fun workflow(
        activeStates: List<String> = listOf("todo", "in_progress"),
        terminalStates: List<String> = listOf("done", "canceled"),
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = activeStates,
            terminalStates = terminalStates,
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Prompt",
            config = WorkflowConfig(),
        )

    private fun progress(
        refreshed: Issue?,
        dispatched: Issue = issue("m1-02"),
        exitReason: String = ExitReason.NO_CONTINUATION,
        workflow: WorkflowFile = workflow(),
    ): NormalExitProgress =
        normalExitProgress(
            AttemptSummary(
                issueIdentifier = dispatched.identifier,
                dispatchedIssue = dispatched,
                outcome = AttemptOutcome.Succeeded,
                issueState = refreshed?.state,
                duration = Duration.ZERO,
                refreshedIssue = refreshed,
                exitReason = exitReason,
            ),
            workflow,
        )

    @Test
    fun `the same state and labels is no progress`() {
        assertEquals(NormalExitProgress.NoProgress, progress(issue("m1-02")))
    }

    @Test
    fun `state and labels are compared normalized`() {
        assertEquals(
            NormalExitProgress.NoProgress,
            progress(issue("m1-02", state = " Todo ", labels = listOf("ready-for-agent ", "ready-for-agent"))),
        )
    }

    @Test
    fun `a state change is progress`() {
        assertEquals(NormalExitProgress.Progressed, progress(issue("m1-02", state = "in_progress")))
    }

    @Test
    fun `a label change is progress`() {
        assertEquals(NormalExitProgress.Progressed, progress(issue("m1-02", labels = listOf("ready-for-agent", "backend"))))
    }

    @Test
    fun `a description change alone is no progress`() {
        assertEquals(NormalExitProgress.NoProgress, progress(issue("m1-02").copy(description = "new body")))
    }

    @Test
    fun `a refreshed issue now terminal with the same state is progress`() {
        val dispatched = issue("m1-02", state = "review")

        val result =
            progress(
                refreshed = dispatched,
                dispatched = dispatched,
                exitReason = ExitReason.TERMINAL,
                workflow = workflow(activeStates = listOf("todo", "review"), terminalStates = listOf("review")),
            )

        assertEquals(NormalExitProgress.Progressed, result)
    }

    @Test
    fun `a refreshed issue now not active with the same state is progress`() {
        val result = progress(issue("m1-02"), exitReason = ExitReason.NOT_ACTIVE, workflow = workflow(activeStates = listOf("in_progress")))

        assertEquals(NormalExitProgress.Progressed, result)
    }

    @Test
    fun `a blocked issue with the same state and labels is no progress`() {
        val blocked = issue("m1-02", blockedReason = "blocked by m1-01")

        assertEquals(NormalExitProgress.NoProgress, progress(blocked, exitReason = ExitReason.NOT_ELIGIBLE))
    }

    @Test
    fun `a not_found exit is not counted`() {
        assertEquals(NormalExitProgress.NotCounted, progress(refreshed = null, exitReason = ExitReason.NOT_FOUND))
    }

    @Test
    fun `an unread issue after another exit is no progress`() {
        assertEquals(NormalExitProgress.NoProgress, progress(refreshed = null))
    }
}
