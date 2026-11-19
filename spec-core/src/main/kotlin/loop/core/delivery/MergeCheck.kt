package loop.core.delivery

import loop.core.verify.WorkspaceFingerprint

/**
 * The names and texts of the merge check and the merge of an approved pull request (m2-26, story 79). The merge module
 * (`loop.core.merge`, bl-85) runs the steps and the merge wait holds their data (`loop.core.waiting.Wait.Merge`); this
 * holds what they write, so every text is built from facts Loop holds.
 */
object MergeCheck {
    const val UP_TO_DATE = "up_to_date"
    const val MERGED = "merged"
    const val CONFLICT = "conflict"
    const val REFUTED = "refuted"

    /** The data key that marks the events of a merge check's rerun of the checks, so its `verdict` is not a new verification. */
    const val MARK = "merge_check"

    /** A merge check is redone at most this many times when the base moves before the merge. */
    const val MAX_ROUNDS = 3

    /** The `step` of a `delivery_failed` of the base fetch. */
    const val STEP_FETCH = "fetch"

    /** The `step` of a `delivery_failed` of the merge check in the workspace. */
    const val STEP_CHECK = "merge_check"

    /** The `step` of a `delivery_failed` of the merge itself, the base read included. */
    const val STEP_MERGE = "merge"

    /** The `reason` of the `issue_commented` events of the merge check. */
    const val COMMENT_REASON = "merge_check"

    /** The `touch` of the human touch of a pull request a human merged at the stored head. */
    const val HUMAN_MERGE = "merge"

    /** The ref the base is written to in the project repository, the one the next attempt merges after a conflict. */
    fun baseRef(base: String): String = "refs/loop/base/$base"

    /** The fingerprint of a clean work tree at [sha]. */
    fun cleanFingerprint(sha: String): String = "git:$sha:${WorkspaceFingerprint.EMPTY_HASH}:${WorkspaceFingerprint.EMPTY_HASH}"

    /** The message of the merge commit Loop makes in the workspace. */
    fun message(
        identifier: String,
        base: String,
        sha: String,
    ): String = "$identifier: merge $base at ${short(sha)} for the merge check"

    /** The comment on the issue after a conflict with the base. */
    fun conflictComment(
        number: String,
        base: String,
        sha: String,
        branch: String,
        detail: String,
    ): String =
        "Loop could not merge pull request #$number: merging $base at $sha into $branch conflicts ($detail). " +
            "The merge was aborted and the branch is unchanged. The next attempt merges ${baseRef(base)} into its branch, " +
            "resolves the conflict and submits the result again."

    /** The comment on the issue after the checks failed on the merged result. */
    fun refutedComment(
        number: String,
        base: String,
        sha: String,
        branch: String,
        reason: String,
    ): String =
        "Loop did not merge pull request #$number: $base at $sha was merged into $branch, but the checks failed on the " +
            "result ($reason). The merge commit stays on the branch. The next attempt fixes the result and submits it again."

    /** The comment on the issue after `diff_policy` failed against a base already in the branch (bl-32). */
    fun guardComment(
        number: String,
        base: String,
        sha: String,
        branch: String,
        reason: String,
    ): String =
        "Loop did not merge pull request #$number: $base at $sha is already in $branch, but diff_policy failed against " +
            "it ($reason). The branch is unchanged. The next attempt fixes the result and submits it again."

    /** The comment on the issue after a merge step was refused for good. */
    fun failedComment(
        number: String,
        step: String,
        reason: String,
    ): String =
        "Loop could not merge pull request #$number, $step: $reason. The pull request stays open and the result waits for " +
            "a new approval."

    /** The findings the next attempt gets after a conflict with the base. */
    fun conflictFindings(
        base: String,
        sha: String,
        detail: String,
    ): String =
        "Your last result was verified and approved, but merging $base at $sha into your branch conflicts ($detail), " +
            "so the pull request was not merged. Run `git merge ${baseRef(base)}`, resolve the conflict keeping the intent " +
            "of both sides, commit the merge, check the work again, then move the issue again."

    /** The findings the next attempt gets after the checks failed on the merged result: the check's own [findings] after a lead. */
    fun refutedFindings(
        base: String,
        sha: String,
        findings: String,
    ): String =
        "Your last result was verified and approved, then Loop merged $base at $sha into your branch and the checks failed " +
            "on the merged result, so the pull request was not merged. The merge commit is on your branch.\n\n$findings"

    /**
     * The findings the next attempt gets after `diff_policy` failed against a base already in the branch (bl-32): the
     * check's own [findings] after a lead.
     */
    fun guardFindings(
        base: String,
        sha: String,
        findings: String,
    ): String =
        "Your last result was verified and approved, then Loop judged your branch against $base at $sha, already in " +
            "your branch, and diff_policy failed, so the pull request was not merged.\n\n$findings"

    /** The short form of a commit id in texts. */
    fun short(sha: String): String = sha.take(SHORT_SHA)

    private const val SHORT_SHA = 12
}
