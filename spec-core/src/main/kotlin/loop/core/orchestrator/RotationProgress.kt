package loop.core.orchestrator

/**
 * The progress of one attempt between its rotations (m3-12, plan 8.4): a new commit on the issue's branch since the
 * previous rotation, the workspace's HEAD at the attempt's first prompt for the first one, a verification with fewer
 * failing checks than the previous verification of the attempt, or (bl-136) a working tree that changed since the
 * previous rotation, the one at the attempt's first prompt for the first. [rotation] counts the rotations in a row
 * without it; two park the issue with `split_issue`.
 *
 * The working tree is a fingerprint of the tracked and untracked files git does not ignore, so an edit under a
 * gitignored folder such as `.scratch/` is no progress. A HEAD or a working tree Loop could not read, null, in a
 * workspace that is not a git work tree or after a failed read, counts as progress: Loop never parks on a fact it did
 * not read.
 */
internal class RotationProgress(
    startHead: String?,
    startTree: String?,
) {
    private var head: String? = startHead
    private var tree: String? = startTree

    /** The failing checks of the attempt's latest verification, null before one. */
    private var lastFailing: Int? = null

    /** True once a verification since the previous rotation failed fewer checks than the one before it. */
    private var improved = false

    private var withoutProgress = 0

    /** A verification of the attempt ended with [failing] checks that did not pass. */
    fun verified(failing: Int) {
        lastFailing?.let { if (failing < it) improved = true }
        lastFailing = failing
    }

    /**
     * A rotation is asked for while the workspace is at [head] with the working tree fingerprint [tree]: gives the
     * rotations in a row without progress, this one included, 0 when it follows progress. The next rotation compares
     * with [head], [tree] and the verifications after this.
     */
    fun rotation(
        head: String?,
        tree: String?,
    ): Int {
        val sameCommit = same(this.head, head)
        val sameTree = same(this.tree, tree)
        withoutProgress = if (sameCommit && sameTree && !improved) withoutProgress + 1 else 0
        this.head = head
        this.tree = tree
        improved = false
        return withoutProgress
    }

    /** True only when both facts were read and are equal. */
    private fun same(
        before: String?,
        now: String?,
    ): Boolean = before != null && now != null && before == now

    companion object {
        /** The rotations in a row without progress that park the issue with `split_issue`. */
        const val SPLIT_AFTER = 2
    }
}
