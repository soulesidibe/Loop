package loop.core.kinds

import loop.api.Issue
import loop.core.workflow.KindConfig
import loop.core.workflow.KindMatch
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m4-18: a wait holds the name of the kind its issue was dispatched under, so its delivery and a restored check
 * follow that kind, not the one the issue matches now. A wait stored without a name falls back to the live lookup,
 * and a name the workflow no longer holds is reported as gone.
 */
class KindLookupTest {
    private val code = KindConfig(checks = emptyList())
    private val quick = KindConfig(checks = emptyList(), match = KindMatch(state = "in_progress"))

    private val workflow =
        WorkflowFile(
            config = WorkflowConfig(kinds = KindsConfig(linkedMapOf("code" to code, "quick" to quick))),
            path = Path.of("/project/WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = Path.of("/project/.loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done"),
            requiredLabels = emptyList(),
            workspaceRoot = Path.of("/project/workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work",
        )

    private fun issue(state: String) =
        Issue(
            id = "id-1",
            identifier = "i-1",
            title = "Issue",
            state = state,
            priority = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = null,
            createdAt = null,
        )

    @Test
    fun `a pinned name wins over the kind the issue matches now`() {
        assertEquals(PinnedKind.Found(IssueKind("code", code)), KindLookup.pinned(workflow, "code", issue("in_progress")))
        assertEquals(PinnedKind.Found(IssueKind("quick", quick)), KindLookup.pinned(workflow, "quick", issue("todo")))
    }

    @Test
    fun `a wait stored without a name falls back to the live lookup, or to code without an issue`() {
        assertEquals(PinnedKind.Found(IssueKind("quick", quick)), KindLookup.pinned(workflow, null, issue("in_progress")))
        assertEquals(PinnedKind.Found(IssueKind("code", code)), KindLookup.pinned(workflow, null, issue("todo")))
        assertEquals(PinnedKind.Found(IssueKind("code", code)), KindLookup.pinned(workflow, null, null))
    }

    @Test
    fun `a name the workflow no longer holds is gone`() {
        assertEquals(PinnedKind.Gone("content"), KindLookup.pinned(workflow, "content", issue("todo")))
    }

    @Test
    fun `in Symphony mode there is no kind`() {
        val symphony = workflow.copy(config = WorkflowConfig())

        assertEquals(PinnedKind.Found(null), KindLookup.pinned(symphony, "code", issue("todo")))
        assertEquals(PinnedKind.Found(null), KindLookup.pinned(symphony, null, issue("todo")))
    }

    @Test
    fun `a move changes the kind only when the target state matches another kind`() {
        assertEquals("quick", KindLookup.afterMove(workflow, issue("todo"), "In_Progress ")?.name)
        assertEquals("code", KindLookup.afterMove(workflow, issue("in_progress"), "todo")?.name)
    }
}
