package loop.core.merge

import loop.core.verify.DiffEntry
import loop.core.verify.WorkspaceGit
import java.nio.file.Path

/**
 * The git calls the merge phases and the auto merge gate make in an issue's workspace (m2-26, m2-28), a seam so the
 * phases run against a fake in tests. [WorkspaceMergeGit] gives them to [WorkspaceGit], hooks off, minimal env, no
 * token and a timeout; each call blocks and throws on a failure, the caller runs it on its io context.
 */
internal interface MergeGit {
    /** True when [ancestor] is an ancestor of [commit] in [root]. */
    fun isAncestor(
        root: Path,
        ancestor: String,
        commit: String,
    ): Boolean

    /** Merges [sha] into the branch checked out in [root] with [message]. */
    fun merge(
        root: Path,
        sha: String,
        message: String,
    ): WorkspaceGit.MergeOutcome

    /** Aborts a merge a stop left in progress in [root] at [head]; true when there was one. */
    fun abortStrandedMerge(
        root: Path,
        head: String,
    ): Boolean

    /** The commit `HEAD` names in [root]. */
    fun head(root: Path): String

    /** The parents of [sha] in [root], in order. */
    fun parents(
        root: Path,
        sha: String,
    ): List<String>

    /** The files and lines of the diff from the merge base of [base] and [head] to [head]. */
    fun diffStat(
        root: Path,
        base: String,
        head: String,
    ): List<DiffEntry>

    /** The text of that diff, at most [maxChars] characters. */
    fun diffText(
        root: Path,
        base: String,
        head: String,
        maxChars: Int,
    ): String

    /** The files that differ from [from] to [to], or from their merge base to [to] with [sinceMergeBase], no renames. */
    fun changedFiles(
        root: Path,
        from: String,
        to: String,
        sinceMergeBase: Boolean,
    ): List<String>

    /** The files of [commit] under [dir]. */
    fun files(
        root: Path,
        commit: String,
        dir: String,
    ): List<String>
}

/** The [MergeGit] of a real workspace, every call through [git]. */
internal class WorkspaceMergeGit(
    private val git: WorkspaceGit,
) : MergeGit {
    override fun isAncestor(
        root: Path,
        ancestor: String,
        commit: String,
    ): Boolean = git.isAncestor(root, ancestor, commit)

    override fun merge(
        root: Path,
        sha: String,
        message: String,
    ): WorkspaceGit.MergeOutcome = git.merge(root, sha, message)

    override fun abortStrandedMerge(
        root: Path,
        head: String,
    ): Boolean = git.abortStrandedMerge(root, head)

    override fun head(root: Path): String = git.head(root)

    override fun parents(
        root: Path,
        sha: String,
    ): List<String> = git.parents(root, sha)

    override fun diffStat(
        root: Path,
        base: String,
        head: String,
    ): List<DiffEntry> = git.diffStat(root, base, head)

    override fun diffText(
        root: Path,
        base: String,
        head: String,
        maxChars: Int,
    ): String = git.diffText(root, base, head, maxChars)

    override fun changedFiles(
        root: Path,
        from: String,
        to: String,
        sinceMergeBase: Boolean,
    ): List<String> = git.changedFiles(root, from, to, sinceMergeBase)

    override fun files(
        root: Path,
        commit: String,
        dir: String,
    ): List<String> = git.files(root, commit, dir)
}
