package loop.core.workspace

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import loop.core.workflow.HooksConfig
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The workspace manager of spec 9: per issue directories under the root
 * ([Workspaces]) and the four hooks of [hooks], run through [runner]. Every hook
 * run is reported to the caller's [HookListener], which records and logs it.
 */
class WorkspaceManager(
    private val workspaces: Workspaces,
    private val hooks: HooksConfig,
    private val runner: HookRunner,
) {
    /**
     * Creates or reuses the workspace of [key]. `after_create` runs only when the
     * directory was created by this call; its failure or timeout fails the call
     * with [WorkspaceError.Hook]. [created] is called once the directory exists,
     * before `after_create`, so a caller can record that before the hook (bl-04).
     */
    suspend fun prepare(
        key: WorkspaceKey,
        env: Map<String, String>,
        listener: HookListener = HookListener.NONE,
        created: suspend (Workspace) -> Unit = {},
    ): Result<Workspace> {
        val workspace = workspaces.prepare(key).getOrElse { return Result.failure(it) }
        created(workspace)
        if (!workspace.createdNow) return Result.success(workspace)
        val result =
            try {
                run(HookName.AFTER_CREATE, hooks.afterCreate, workspace.path, env, hooks.timeoutMs, listener)
            } catch (e: Throwable) {
                // Cancelled or broken half way: same partial cleanup as a failure.
                withContext(NonCancellable) { workspaces.delete(key) }
                    .exceptionOrNull()
                    ?.let { e.addSuppressed(it) }
                throw e
            }
        if (result == null || result.ok) return Result.success(workspace)
        // Partial cleanup (spec-decisions row 183): the next attempt runs after_create again
        // instead of reusing a half populated folder. A reused workspace is never removed here.
        val message = "cannot prepare workspace ${key.value}: ${result.reason}"
        val cleanup = workspaces.delete(key).exceptionOrNull()?.let { "; the partial workspace was not removed: ${it.message}" } ?: ""
        return Result.failure(WorkspaceError.Hook(message + cleanup, result))
    }

    /**
     * This manager without `after_create`, `before_run`, `after_run` and `before_remove` (m4-20), for a kind declared
     * `populate: empty`: its workspace is the folder [Workspaces] creates, and the other hooks still run.
     */
    fun withoutWorkspaceHooks(): WorkspaceManager =
        WorkspaceManager(workspaces, hooks.copy(afterCreate = null, beforeRun = null, afterRun = null, beforeRemove = null), runner)

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
        val path = workspaces.locate(key).getOrElse { return Result.failure(it) } ?: return Result.success(false)
        run(HookName.BEFORE_REMOVE, hooks.beforeRemove, path, env, hooks.timeoutMs, listener)
        return workspaces.delete(key).map { true }
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
        val result = runner.runStoppable(hook, script, cwd, env, timeoutMs, listener)
        listener.finished(script, result)
        return result
    }
}

/**
 * Runs [script] with this runner. A cancel of the caller tells [listener] about the stopped run, with
 * [shown] as its script, then is thrown on: the runner's partial result, or one with the time measured
 * here and no output when the runner kept none (bl-04).
 */
internal suspend fun HookRunner.runStoppable(
    hook: HookName,
    script: String,
    cwd: Path,
    env: Map<String, String>,
    timeoutMs: Long,
    listener: HookListener,
    shown: String = script,
): HookResult {
    val started = TimeSource.Monotonic.markNow()
    var partial: HookResult? = null
    try {
        return run(hook, script, cwd, env, timeoutMs) { partial = it }
    } catch (e: CancellationException) {
        val result =
            partial ?: HookResult(
                hook = hook,
                exitCode = null,
                timedOut = false,
                timeoutMs = timeoutMs,
                durationMs = started.elapsedNow().inWholeMilliseconds,
                output = "",
                stopped = true,
            )
        withContext(NonCancellable) { listener.stopped(shown, result) }
        throw e
    }
}

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

    /** The hook was stopped by a cancel of the caller, before it ended; [result] is the run so far (bl-04). */
    suspend fun stopped(
        script: String,
        result: HookResult,
    ) {}

    companion object {
        val NONE: HookListener = object : HookListener {}
    }
}
