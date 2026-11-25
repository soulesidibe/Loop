package loop.core.reactions

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowLoader
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** m4-34: preflight reads `reactions:` with the four keys of PLAN 6.7 and refuses what it does not know. */
class ReactionsPreflightTest {
    private val folder: Path = createTempDirectory("loop-reactions").toRealPath()

    private fun check(extra: String): PreflightResult {
        val path =
            folder.resolve("WORKFLOW.md").also {
                it.writeText(
                    """
                    |---
                    |tracker:
                    |  kind: files
                    |runner:
                    |  kind: command
                    |runners:
                    |  command:
                    |    command: ./agent.sh
                    |$extra
                    |---
                    |Work on {{ issue.identifier }}
                    """.trimMargin(),
                )
            }
        return runBlocking { Preflight(WorkflowLoader(env = emptyMap()), path, TemplateEngine()) { null }.check() }
    }

    private fun refused(extra: String): String {
        val result = check(extra)
        assertIs<PreflightResult.Failed>(result, result.toString())
        assertEquals("invalid_config", result.code)
        return result.message
    }

    @Test
    fun `reactions parses the four keys of PLAN 6_7 with their options`() {
        val result =
            check(
                """
                |reactions:
                |  ci_failed:          { action: send_to_agent, retries: 2 }
                |  changes_requested:  { action: send_to_agent, escalate_after: 4h }
                |  merge_conflict:     { action: send_to_agent }
                |  approved_green:     { action: notify_human }
                """.trimMargin(),
            )

        assertIs<PreflightResult.Ok>(result, result.toString())
        assertEquals(
            ReactionsConfig(
                ciFailed = Reaction(ReactionAction.SendToAgent, retries = 2),
                changesRequested = Reaction(ReactionAction.SendToAgent, escalateAfterMs = 4 * 3_600_000L),
                mergeConflict = Reaction(ReactionAction.SendToAgent),
                approvedGreen = Reaction(ReactionAction.NotifyHuman),
            ),
            result.workflow.config.reactions,
        )
    }

    @Test
    fun `with no reactions key there is no reaction`() {
        val result = check("")

        assertIs<PreflightResult.Ok>(result, result.toString())
        assertEquals(ReactionsConfig(), result.workflow.config.reactions)
        assertTrue(result.workflow.config.reactions.none)
    }

    @Test
    fun `preflight refuses an unknown reaction`() {
        assertEquals(
            "reactions.pr_closed is not a reaction Loop knows, use ci_failed, changes_requested, merge_conflict, approved_green",
            refused("reactions:\n  pr_closed: { action: notify_human }"),
        )
    }

    @Test
    fun `preflight refuses an unknown action`() {
        assertEquals(
            "reactions.merge_conflict.action is \"rebase\", which is not an action Loop knows, use send_to_agent, notify_human",
            refused("reactions:\n  merge_conflict: { action: rebase }"),
        )
        assertEquals("reactions.merge_conflict.action is required", refused("reactions:\n  merge_conflict: { }"))
    }

    @Test
    fun `preflight refuses a bad option`() {
        assertEquals(
            "reactions.ci_failed.retries must be an integer of 0 or more",
            refused("reactions:\n  ci_failed: { action: send_to_agent, retries: -1 }"),
        )
        assertEquals(
            "reactions.changes_requested.escalate_after must be a duration such as 30m, 4h or 2d",
            refused("reactions:\n  changes_requested: { action: send_to_agent, escalate_after: soon }"),
        )
        assertEquals(
            "reactions.merge_conflict.retries is not an option of merge_conflict, it takes action",
            refused("reactions:\n  merge_conflict: { action: send_to_agent, retries: 2 }"),
        )
        assertEquals("reactions must be a map", refused("reactions: [merge_conflict]"))
        assertEquals("reactions.merge_conflict must be a map", refused("reactions:\n  merge_conflict: send_to_agent"))
    }
}
