package loop.core.merge

import loop.api.Issue
import loop.core.waiting.Wait
import loop.core.workflow.WorkflowFile
import java.nio.file.Path

/**
 * The rerun of the checks in a merge check (m2-26, bl-32), which the orchestrator runs: every check and holdout of
 * [WorkflowFile] in the workspace, outside any attempt, `diff_policy` based on the fetched base, each event marked
 * `merge_check`. The merge module checks the work tree after it.
 */
internal fun interface Recheck {
    /**
     * Reruns the checks of [workflow] for [wait] on [issue] in [workspace], `diff_policy` based on [base]; with
     * [diffPolicyOnly], only the `diff_policy` checks.
     */
    suspend fun run(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        workspace: Path,
        base: String,
        diffPolicyOnly: Boolean,
    ): RecheckOutcome
}

/** What a [Recheck] found. */
internal sealed interface RecheckOutcome {
    /** Only `diff_policy` was asked for and the kind has none: nothing ran. */
    data object Skipped : RecheckOutcome

    /** Every check passed. */
    data object Passed : RecheckOutcome

    /** A check failed: why the result is [unverified], when it is, and the [findings] for the next attempt, when any. */
    data class Failed(
        val unverified: String?,
        val findings: String?,
    ) : RecheckOutcome
}

/** What a call into [Merges] left: the issue still waits, for any reason, or its wait ended. */
internal enum class MergeWait { Continues, Ended }
