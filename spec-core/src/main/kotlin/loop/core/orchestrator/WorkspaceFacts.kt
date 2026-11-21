package loop.core.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import loop.core.verify.WorkspaceGit
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The commit HEAD of a workspace is at and the fingerprint of its working tree (bl-136), for the progress between
 * rotations (m3-12), when the workspace is the top level of a git work tree. bl-149: the read is bounded and
 * cancellable. It runs on its own daemon thread; the caller waits at most [budget], then gets no facts and a failure,
 * and a cancel of the caller stops the wait at once. Either way the read's git is stopped and its hashing ends at its
 * next chunk; a thread stuck where nothing can stop it, such as the open of a FIFO swapped in for a file, is left
 * behind, never awaited.
 */
internal class WorkspaceFacts(
    private val git: WorkspaceGit,
    private val budget: Duration = BUDGET,
    private val read: (Path, WorkspaceGit.Cancellation) -> Facts = { workspace, cancellation -> readFacts(git, workspace, cancellation) },
) {
    /**
     * What was read: [head] and [tree], each null when the workspace is not a git work tree or its read failed, and the
     * [failures], the reason of each failed read by fact ([WORKSPACE], [HEAD], [WORKING_TREE]), empty for a workspace
     * that is simply not a git work tree.
     */
    data class Facts(
        val head: String?,
        val tree: String?,
        val failures: Map<String, String>,
    )

    /** The facts of [workspace], within [budget]. */
    suspend fun of(workspace: Path): Facts {
        val cancellation = WorkspaceGit.Cancellation()
        val future =
            CompletableFuture
                .supplyAsync({ read(workspace, cancellation) }, DAEMON)
                .orTimeout(budget.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        var done = false
        try {
            return future.await().also { done = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason =
                if (e.causes().any { it is TimeoutException }) {
                    "the git reads did not finish within $budget"
                } else {
                    "the git reads failed: ${e.message ?: e.javaClass.simpleName}"
                }
            return Facts(null, null, mapOf(WORKSPACE to reason))
        } finally {
            if (!done) cancellation.cancel()
        }
    }

    companion object {
        /** The fact of the whole read: the top level check, or the read past its budget. */
        const val WORKSPACE = "workspace"
        const val HEAD = "head"
        const val WORKING_TREE = "working_tree"

        /** The longest the first prompt or a rotation waits for the facts. */
        val BUDGET: Duration = 30.seconds

        /** A new daemon thread for each read, so one left behind holds no shared thread. */
        private val DAEMON = Executor { task -> Thread(task, "loop-workspace-facts").apply { isDaemon = true }.start() }

        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

        /** Reads the facts of [workspace] with [git], each fact on its own, a failure kept as its reason. */
        fun readFacts(
            git: WorkspaceGit,
            workspace: Path,
            cancellation: WorkspaceGit.Cancellation,
        ): Facts {
            val topLevel =
                try {
                    val top = git.run(workspace, "rev-parse", "--show-toplevel", cancellation = cancellation)
                    top.code == 0 &&
                        runCatching { Path.of(top.stdout.trim()).toRealPath() == workspace.toRealPath() }.getOrDefault(false)
                } catch (e: Exception) {
                    return Facts(null, null, mapOf(WORKSPACE to "cannot run git: ${e.message ?: e.javaClass.simpleName}"))
                }
            if (!topLevel) return Facts(null, null, emptyMap())
            val failures = mutableMapOf<String, String>()
            val head =
                try {
                    git.head(workspace, cancellation)
                } catch (e: Exception) {
                    failures[HEAD] = "cannot read HEAD: ${e.message ?: e.javaClass.simpleName}"
                    null
                }
            val tree =
                try {
                    git.workTree(workspace, cancellation)
                } catch (e: Exception) {
                    failures[WORKING_TREE] = "cannot read the working tree: ${e.message ?: e.javaClass.simpleName}"
                    null
                }
            return Facts(head, tree, failures)
        }
    }
}
