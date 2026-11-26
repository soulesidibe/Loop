package loop.core.workspace

import loop.core.process.SessionProcess
import loop.core.process.ShellResult
import loop.core.process.ShellRun
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a hook with `sh -lc <script>` in the workspace, with the host environment
 * plus the given variables (spec 9.4, spec-decisions row 180, plan R9), through [ShellRun]:
 * its own process group, stopped whole when the shell exits, times out or is cancelled.
 */
class ShellHookRunner(
    grace: Duration = 2.seconds,
    drainTimeout: Duration = 500.milliseconds,
    start: (List<String>, Path, Map<String, String>) -> SessionProcess = SessionProcess::start,
) : HookRunner {
    private val shell = ShellRun(grace, drainTimeout, start)

    override suspend fun run(
        hook: HookName,
        script: String,
        cwd: Path,
        env: Map<String, String>,
        timeoutMs: Long,
        stopped: (HookResult) -> Unit,
    ): HookResult {
        val result = shell.run(script, cwd, env, timeoutMs) { stopped(hookResult(hook, timeoutMs, it).copy(stopped = true)) }
        return hookResult(hook, timeoutMs, result)
    }

    private fun hookResult(
        hook: HookName,
        timeoutMs: Long,
        result: ShellResult,
    ): HookResult =
        HookResult(
            hook = hook,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            timeoutMs = timeoutMs,
            durationMs = result.durationMs,
            output = result.startError?.let { "loop: Loop requires perl on PATH to run hooks: $it" } ?: result.output,
            stopFailed = result.stopFailed,
            outputTruncated = result.outputTruncated,
        )

    companion object {
        /** Bytes of output kept, the tail (plan R3). */
        const val OUTPUT_MAX = ShellRun.OUTPUT_MAX
    }
}
