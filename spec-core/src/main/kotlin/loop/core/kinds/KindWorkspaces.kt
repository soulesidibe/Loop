package loop.core.kinds

import loop.core.workflow.CheckConfig
import loop.core.workflow.GuardsConfig
import loop.core.workflow.KindPopulate
import loop.core.workflow.KindWorkspace
import loop.core.workspace.WorkspaceManager

/**
 * A kind's `workspace` and `populate` (m4-20). The declaration decides, never a look at the folder: a `plain` kind is
 * never read with git, even when the workflow's hooks gave it a git work tree, and a `git` kind outside a work tree stays
 * `unverified` as before.
 */
object KindWorkspaces {
    /** True when [kind] declares `workspace: plain`; false for a `git` kind and in Symphony mode. */
    fun isPlain(kind: IssueKind?): Boolean = kind?.config?.workspace == KindWorkspace.Plain

    /**
     * [manager] for the workspaces of [kind]: with `populate: empty`, one that runs none of `after_create`, `before_run`,
     * `after_run` and `before_remove`, since the workflow's hooks assume a git work tree; [manager] itself otherwise.
     */
    fun manager(
        manager: WorkspaceManager,
        kind: IssueKind?,
    ): WorkspaceManager = if (kind?.config?.populate == KindPopulate.Empty) manager.withoutWorkspaceHooks() else manager

    /**
     * Why the kind at [kindKey] cannot run, as a sentence for preflight, or null when it can: `populate: empty` on a
     * `git` kind, or, on a `plain` kind, a setting that reads git. Those are the deny guards, a `diff_policy` or
     * `changed_line_coverage` check in [checks] as written or in [holdout], any `verify.guards`, which add a
     * `diff_policy` check, and a pull request delivery ([pullRequest]).
     */
    fun refusal(
        kindKey: String,
        workspace: KindWorkspace,
        populate: KindPopulate,
        checks: List<CheckConfig>,
        holdout: List<CheckConfig>,
        guards: GuardsConfig?,
        pullRequest: Boolean,
    ): String? {
        if (workspace == KindWorkspace.Git) {
            if (populate != KindPopulate.Empty) return null
            return "$kindKey.populate is empty, which needs workspace: plain, a git kind is populated by the workspace hooks"
        }
        val plain = "and $kindKey is workspace: plain"
        if (guards != null && guards.deny.isNotEmpty()) return "$kindKey.verify.guards.deny reads a git diff, $plain"
        val written =
            checks.mapIndexed { index, check -> "$kindKey.verify.checks[$index]" to check } +
                holdout.mapIndexed { index, check -> "$kindKey.verify.holdout[$index]" to check }
        written.firstOrNull { (_, check) -> check is CheckConfig.DiffPolicy }?.let { (key, _) ->
            return "$key is diff_policy, which reads a git diff, $plain"
        }
        written.firstOrNull { (_, check) -> check is CheckConfig.ChangedLineCoverage }?.let { (key, _) ->
            return "$key is changed_line_coverage, which reads a git diff, $plain"
        }
        if (guards != null) return "$kindKey.verify.guards adds a diff_policy check, which reads a git diff, $plain"
        if (pullRequest) return "$kindKey.delivery.pull_request pushes a git commit, $plain"
        return null
    }
}
