package loop.core.kinds

import loop.api.Issue
import loop.core.workflow.KindConfig
import loop.core.workflow.WorkflowFile

/** The kind an issue got (m4-18): its [name], as `kinds:` writes it, and its settings. */
data class IssueKind(
    val name: String,
    val config: KindConfig,
)

/**
 * The one lookup of an issue's kind (m4-18): [KindMatcher] on the kinds of a workflow, with the id of the issue's own
 * tracker. The orchestrator reads an issue's kind settings through it, never `kinds.code`, except on the paths that
 * are code only so far.
 */
object KindLookup {
    /** The kind of [issue] under [workflow], null in Symphony mode. */
    fun of(
        workflow: WorkflowFile,
        issue: Issue,
    ): IssueKind? {
        val kinds = workflow.config.kinds ?: return null
        val name = KindMatcher.kindOf(kinds, issue, workflow.trackers.entryOf(issue.id).id)
        return IssueKind(name, kinds.named.getValue(name))
    }
}
