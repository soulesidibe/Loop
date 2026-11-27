package loop.core.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.core.workspace.WorkspaceClones
import loop.core.workspace.WorkspaceKey
import java.io.IOException
import java.nio.file.Path

/**
 * bl-28: the delivery of a workflow with `workspace.git: clone`. A clone's commits live in its own object folder, so
 * before every push of a workspace commit, the pull request push (first try and retry) and the merge check's push of
 * the merged head, the commit is imported into the project by its id ([WorkspaceClones.import]) and
 * `refs/heads/loop/<key>` written there. The orchestrator hands this executor to the pull request delivery and to the
 * merge check alike, so an import that failed on the verified verdict is made again here.
 * The push of [delegate] then runs unchanged and checks that branch. Every other step is [delegate]'s.
 *
 * A branch that is not `loop/<key>` and a workspace that is gone are pushed as they are: the push checks the project's
 * branch as without clones. A workspace that is no longer a clone of the project, its `.git` missing, replaced or in a
 * layout Loop refuses, fails the push with the reason and nothing is pushed (bl-253).
 */
class CloneDelivery(
    private val delegate: DeliveryExecutor,
    private val clones: WorkspaceClones,
    private val locate: (WorkspaceKey) -> Result<Path?>,
) : DeliveryExecutor by delegate {
    override suspend fun push(
        sha: String,
        branch: String,
    ): Result<Unit> {
        import(sha, branch)?.let { return Result.failure(it) }
        return delegate.push(sha, branch)
    }

    /** Imports [sha] for [branch] from its clone workspace; the error, null when it was imported or had nothing to import. */
    private suspend fun import(
        sha: String,
        branch: String,
    ): DeliveryError? {
        if (!branch.startsWith(BRANCH_PREFIX)) return null
        val key = WorkspaceKey(branch.removePrefix(BRANCH_PREFIX))
        val workspace =
            locate(key).getOrElse { error ->
                return DeliveryError.Unavailable("Cannot locate the workspace of $branch: ${error.message}. Nothing was pushed", error)
            } ?: return null
        return try {
            withContext(Dispatchers.IO) {
                // bl-253: in clone mode a workspace whose `.git` is no longer a clone is refused, never skipped.
                clones.import(workspace, sha, branch)
            }
            null
        } catch (e: IOException) {
            DeliveryError.Rejected("Cannot import $sha from the workspace $workspace: ${e.message}. Nothing was pushed", e)
        }
    }

    private companion object {
        const val BRANCH_PREFIX = "loop/"
    }
}
