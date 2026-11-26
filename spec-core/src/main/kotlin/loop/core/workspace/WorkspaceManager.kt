package loop.core.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import loop.core.verify.WorkspaceGit
import loop.core.workflow.HooksConfig
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException

/**
 * The workspace manager of spec 9: per issue directories under the root
 * ([Workspaces]) and the four hooks of [hooks], run through [runner]. Every hook
 * run is reported to the caller's [HookListener], which records and logs it.
 *
 * bl-28: with [clones] (`workspace.git: clone`), each workspace is a clone of the project, made by Loop before
 * `after_create`, and removal deletes its keep ref.
 */
class WorkspaceManager(
    private val workspaces: Workspaces,
    private val hooks: HooksConfig,
    private val runner: HookRunner,
    /** bl-28: the clone steps of `workspace.git: clone`, null when the hooks own the folder. */
    val clones: WorkspaceClones? = null,
) {
    /**
     * Creates or reuses the workspace of [key]. `after_create` runs only when the
     * directory was created by this call; its failure or timeout fails the call
     * with [WorkspaceError.Hook].
     *
     * bl-28: with [clones], before any hook, a folder without `.git` becomes a clone of the project starting at
     * [clone]'s base (and counts as created now, so `after_create` runs on it), a clone of the project is checked
     * ([WorkspaceClones.reuse]) and reused as it is, and any other `.git` fails the call with [WorkspaceError.Clone].
     * Then [CloneStart.baseRef], when given, is written in the clone. A clone made by this call that fails or is
     * cancelled, in the clone step or in `after_create`, has its folder and its keep refs deleted.
     */
    suspend fun prepare(
        key: WorkspaceKey,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
        clone: CloneStart = CloneStart(),
    ): Result<Workspace> {
        val prepared = workspaces.prepare(key).getOrElse { return Result.failure(it) }
        var workspace = prepared
        clones?.let { clones ->
            val made =
                try {
                    cloneStep(clones, prepared, clone)
                } catch (e: Throwable) {
                    // Cancelled or broken half way: the folder this call created and the keep refs go.
                    val cleanup = withContext(NonCancellable) { dropClone(key, prepared.createdNow) }
                    cleanup.forEach { e.addSuppressed(it) }
                    if (e is CancellationException || e !is IOException) throw e
                    val left = cleanup.joinToString("") { "; the partial clone was not removed: ${it.message}" }
                    return Result.failure(WorkspaceError.Clone("cannot prepare workspace ${key.value}: ${e.message ?: e}$left"))
                }
            if (made) workspace = prepared.copy(createdNow = true)
        }
        if (!workspace.createdNow) return Result.success(workspace)
        val result =
            try {
                run(HookName.AFTER_CREATE, hooks.afterCreate, workspace.path, env, hooks.timeoutMs, listener)
            } catch (e: Throwable) {
                // Cancelled or broken half way: same partial cleanup as a failure.
                withContext(NonCancellable) { dropClone(key, folder = true) }.forEach { e.addSuppressed(it) }
                throw e
            }
        if (result == null || result.ok) return Result.success(workspace)
        // Partial cleanup (spec-decisions row 183): the next attempt runs after_create again
        // instead of reusing a half populated folder. A reused workspace is never removed here.
        val message = "cannot prepare workspace ${key.value}: ${result.reason}"
        val cleanup = dropClone(key, folder = true).joinToString("") { "; the partial workspace was not removed: ${it.message}" }
        return Result.failure(WorkspaceError.Hook(message + cleanup, result))
    }

    /**
     * Deletes the folder of [key] when [folder], and its keep refs when this manager clones; what failed. A keep ref
     * of a workspace whose folder stays is kept.
     */
    private suspend fun dropClone(
        key: WorkspaceKey,
        folder: Boolean,
    ): List<Throwable> {
        if (!folder) return emptyList()
        val deleted = workspaces.delete(key).exceptionOrNull()
        val keep = dropKeep(key).exceptionOrNull()
        return listOfNotNull(deleted, keep)
    }

    /**
     * This manager without `after_create`, `before_run`, `after_run` and `before_remove` (m4-20), for a kind declared
     * `populate: empty`: its workspace is the folder [Workspaces] creates, and the other hooks still run.
     */
    fun withoutWorkspaceHooks(): WorkspaceManager =
        WorkspaceManager(workspaces, hooks.copy(afterCreate = null, beforeRun = null, afterRun = null, beforeRemove = null), runner)

    /**
     * bl-28: makes [workspace] a clone or checks it is one, then writes the base ref; true when it made the clone. The
     * git steps run on a thread of their own, and a cancel of the caller stops the running git through a
     * [WorkspaceGit.Cancellation], then waits for the steps to end before it is rethrown, so the cleanup never races
     * them. Fails with an [IOException] naming the reason.
     */
    private suspend fun cloneStep(
        clones: WorkspaceClones,
        workspace: Workspace,
        clone: CloneStart,
    ): Boolean {
        val cancellation = WorkspaceGit.Cancellation()
        val steps =
            CompletableFuture.supplyAsync({
                val made =
                    when (val state = clones.state(workspace.path)) {
                        CloneState.Absent -> {
                            clones.create(workspace.path, workspace.key, clone.base, cancellation)
                            true
                        }
                        CloneState.Clone -> {
                            clones.reuse(workspace.path, workspace.key, cancellation)
                            false
                        }
                        is CloneState.Foreign ->
                            throw IOException("the workspace ${workspace.path} is not a clone of the project, ${state.reason}")
                    }
                clone.baseRef?.let { clones.writeBase(workspace.path, workspace.key, it.name, it.sha, cancellation) }
                made
            }, CLONE_STEPS)
        try {
            // A stage of its own, since a cancelled await cancels the future it waits on, and the join below waits on steps.
            return steps.thenApply { it }.await()
        } catch (e: CancellationException) {
            cancellation.cancel()
            withContext(NonCancellable + Dispatchers.IO) { runCatching { steps.join() } }
            throw e
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    /** The existing workspace of [key], null when there is none; nothing is created and no hook runs (m2-04). */
    fun locate(key: WorkspaceKey): Result<Path?> = workspaces.locate(key)

    /**
     * Runs `before_run` in [workspace]; null when it is not configured. A result that
     * is not ok is fatal to the attempt, the caller fails it (spec 9.4).
     */
    suspend fun beforeRun(
        workspace: Workspace,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
    ): HookResult? = run(HookName.BEFORE_RUN, hooks.beforeRun, workspace.path, env, hooks.timeoutMs, listener)

    /**
     * Runs `after_run` in [workspace]; null when it is not configured. A failure is
     * only reported, never fatal (spec 9.4). The timeout is `hooks.timeout_ms`, cut
     * to [maxTimeoutMs] when given (plan R5: a cancelled attempt's after_run).
     */
    suspend fun afterRun(
        workspace: Workspace,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
        maxTimeoutMs: Long = Long.MAX_VALUE,
    ): HookResult? = run(HookName.AFTER_RUN, hooks.afterRun, workspace.path, env, minOf(hooks.timeoutMs, maxTimeoutMs), listener)

    /**
     * Runs `on_token_threshold` in [workspace] (m1-38); null when it is not configured. A failure is
     * only reported, never fatal: a threshold never fails or stops the attempt.
     */
    suspend fun onTokenThreshold(
        workspace: Workspace,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
    ): HookResult? = run(HookName.ON_TOKEN_THRESHOLD, hooks.onTokenThreshold, workspace.path, env, hooks.timeoutMs, listener)

    /**
     * Runs `on_budget_warning` in [workspace] (m2-20); null when it is not configured. A failure is only
     * reported, never fatal: a warning never fails or stops the attempt.
     */
    suspend fun onBudgetWarning(
        workspace: Workspace,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
    ): HookResult? = run(HookName.ON_BUDGET_WARNING, hooks.onBudgetWarning, workspace.path, env, hooks.timeoutMs, listener)

    /**
     * Removes the workspace of [key]: runs `before_remove` in it (failure or timeout
     * only reported, spec 9.4), then deletes the directory if it is still there.
     * Returns true when a workspace existed. Callers: terminal retry cleanup (m1-07)
     * and reconciliation (m1-16).
     */
    suspend fun remove(
        key: WorkspaceKey,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
    ): Result<Boolean> {
        val path = workspaces.locate(key).getOrElse { return Result.failure(it) } ?: return dropKeep(key).map { false }
        run(HookName.BEFORE_REMOVE, hooks.beforeRemove, path, env, hooks.timeoutMs, listener)
        return workspaces.delete(key).mapCatching {
            dropKeep(key).getOrThrow()
            true
        }
    }

    /** bl-28: deletes the keep ref of [key] in the project when this manager clones, so a gc may drop what it kept. */
    private suspend fun dropKeep(key: WorkspaceKey): Result<Unit> {
        val clones = clones ?: return Result.success(Unit)
        return try {
            withContext(Dispatchers.IO) { clones.dropKeep(key) }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(WorkspaceError.Io("cannot remove the keep ref of workspace ${key.value}: ${e.message}", e))
        }
    }

    private suspend fun run(
        hook: HookName,
        script: String?,
        cwd: Path,
        env: Map<String, String>,
        timeoutMs: Long,
        listener: HookListener,
    ): HookResult? {
        if (script.isNullOrBlank()) return null
        listener.started(hook, script)
        val result =
            try {
                runner.run(hook, script, cwd, env, timeoutMs)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { listener.stopped(hook) }
                throw e
            }
        listener.finished(script, result)
        return result
    }

    private companion object {
        /** A new daemon thread for each clone step, so a step left behind holds no shared thread. */
        val CLONE_STEPS = Executor { task -> Thread(task, "loop-workspace-clone").apply { isDaemon = true }.start() }
    }
}

/**
 * bl-28: what [WorkspaceManager.prepare] needs to make a clone: the [base] a new branch starts from when the project has
 * no `loop/<key>` branch, and the [baseRef] to write in the clone, both resolved by Loop in the project.
 */
data class CloneStart(
    val base: String? = null,
    val baseRef: CloneBase? = null,
)

/** bl-28: `refs/loop/base/[name]` at [sha], written in a clone like in the project. */
data class CloneBase(
    val name: String,
    val sha: String,
)

/** Told about each hook run, to record and log it (plan R2, R3). All default to nothing. */
interface HookListener {
    suspend fun started(
        hook: HookName,
        script: String,
    ) {}

    suspend fun finished(
        script: String,
        result: HookResult,
    ) {}

    /** The hook was stopped by a cancel of the caller, before it ended. */
    suspend fun stopped(hook: HookName) {}

    companion object {
        val NONE: HookListener = object : HookListener {}
    }
}
