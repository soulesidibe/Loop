package loop.core.kinds

import loop.api.Issue
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowFile

/** The kind an issue got (m4-18): its [name], as `kinds:` writes it, and its settings. */
data class IssueKind(
    val name: String,
    val config: KindConfig,
)

/** The kind of a wait (m4-18), see [KindLookup.pinned]. */
sealed interface PinnedKind {
    /** The [kind] the wait follows, null in Symphony mode. */
    data class Found(
        val kind: IssueKind?,
    ) : PinnedKind

    /** The wait names the kind [name], which the workflow no longer holds: it keeps waiting until the kind is back. */
    data class Gone(
        val name: String,
    ) : PinnedKind
}

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

    /**
     * The kind of a wait whose issue was dispatched under the kind [name]: that kind, whatever [issue] matches now, so a
     * move or a label edit during the wait never changes how it is verified or delivered. A wait stored without a name,
     * before m4-18, falls back to the kind [issue] matches now, or to `code` when there is no issue to read. A name the
     * workflow no longer holds is [PinnedKind.Gone].
     */
    fun pinned(
        workflow: WorkflowFile,
        name: String?,
        issue: Issue?,
    ): PinnedKind {
        val kinds = workflow.config.kinds ?: return PinnedKind.Found(null)
        if (name == null) {
            return PinnedKind.Found(issue?.let { of(workflow, it) } ?: IssueKind(KindsConfig.CODE, kinds.code))
        }
        val config = kinds.named[name] ?: return PinnedKind.Gone(name)
        return PinnedKind.Found(IssueKind(name, config))
    }

    /** The kind [issue] would have once moved to the state [to], null in Symphony mode. */
    fun afterMove(
        workflow: WorkflowFile,
        issue: Issue,
        to: String,
    ): IssueKind? = of(workflow, issue.copy(state = to))
}
