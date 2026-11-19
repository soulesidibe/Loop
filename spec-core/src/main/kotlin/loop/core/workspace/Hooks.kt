package loop.core.workspace

import java.nio.file.Path

/**
 * The four workspace hooks of spec 9.4 and Loop's own `on_token_threshold` (m1-38) and `on_budget_warning` (m2-20),
 * with their front matter names.
 */
enum class HookName(
    val id: String,
) {
    AFTER_CREATE("after_create"),
    BEFORE_RUN("before_run"),
    AFTER_RUN("after_run"),
    BEFORE_REMOVE("before_remove"),
    ON_TOKEN_THRESHOLD("on_token_threshold"),
    ON_BUDGET_WARNING("on_budget_warning"),
}

/**
 * What one hook run did. [exitCode] is null when the hook timed out or could not
 * start. [output] is the tail of stdout and stderr merged.
 */
data class HookResult(
    val hook: HookName,
    val exitCode: Int?,
    val timedOut: Boolean,
    val timeoutMs: Long,
    val durationMs: Long,
    val output: String,
    /** True when the hook's process group could not be stopped (the final KILL failed). */
    val stopFailed: Boolean = false,
    /** True when [output] is a cut tail: its start, up to the first whole line, was dropped. */
    val outputTruncated: Boolean = false,
) {
    val ok: Boolean get() = !timedOut && exitCode == 0

    /** Null when [ok], else why the hook failed, for attempt reasons and logs. */
    val reason: String?
        get() =
            when {
                ok -> null
                timedOut -> "hook ${hook.id} timed out after $timeoutMs ms"
                exitCode != null -> "hook ${hook.id} failed with exit code $exitCode"
                else -> "hook ${hook.id} could not start: ${output.trim().lineSequence().lastOrNull().orEmpty()}"
            }
}

/** Runs one hook script; the seam between the workspace manager and processes. */
fun interface HookRunner {
    suspend fun run(
        hook: HookName,
        script: String,
        cwd: Path,
        env: Map<String, String>,
        timeoutMs: Long,
    ): HookResult
}
