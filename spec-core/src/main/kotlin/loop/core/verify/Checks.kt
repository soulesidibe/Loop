package loop.core.verify

import loop.core.process.SessionProcess
import loop.core.process.ShellRun
import loop.core.workflow.CheckConfig
import java.nio.file.Path
import java.util.Locale

/**
 * What one check run did (m2-03). [exitCode] is null when the check timed out, could not start
 * ([startError]) or was not run. It passes only on exit 0 within its timeout. [output] is its whole
 * stdout and stderr, merged as written and not yet redacted, for its evidence (m2-05).
 */
data class CheckResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val startError: String? = null,
    val output: String = "",
    /** True when [output] went past [ShellRun.FULL_OUTPUT_MAX] and its head was dropped. */
    val outputTruncated: Boolean = false,
) {
    val passed: Boolean get() = !timedOut && exitCode == 0

    /**
     * Why the check could not run, null when it ran (m2-08): it could not start, it timed out, it ended without
     * an exit code, or `sh` could not find (127) or execute (126) its command. A check that could not run proves
     * nothing either way, so it gives `unverified` and is never rerun.
     */
    val couldNotRun: String?
        get() =
            when {
                startError != null -> startError
                timedOut -> "timed out after ${seconds(durationMs)}"
                exitCode == null -> "it ended without an exit code"
                exitCode == COMMAND_NOT_FOUND -> "command not found, exit code $exitCode"
                exitCode == COMMAND_NOT_EXECUTABLE -> "command cannot be executed, exit code $exitCode"
                else -> null
            }

    /** A check that ran and failed: the only result that is rerun, and the only one that refutes (m2-08). */
    val refutes: Boolean get() = !passed && couldNotRun == null

    /**
     * All a holdout's records say of its run (m2-14): `passed`, `failed` or `could_not_run`. Its exit code,
     * duration and reason could each carry what the hidden check found, so none of them is recorded.
     */
    val hiddenOutcome: String
        get() =
            when {
                passed -> "passed"
                couldNotRun != null -> "could_not_run"
                else -> "failed"
            }

    companion object {
        /** The exit code POSIX `sh` gives when it cannot find a command. */
        const val COMMAND_NOT_FOUND = 127

        /** The exit code POSIX `sh` gives when it finds a command but cannot execute it. */
        const val COMMAND_NOT_EXECUTABLE = 126
    }
}

/** `42 s` for whole seconds, else `4.2 s`. */
internal fun seconds(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

/**
 * Runs one check of a kind's verify contract with exactly [env], nothing inherited; the seam
 * between the orchestrator, which records the check events, and processes. Like [loop.core.workspace.HookRunner].
 */
fun interface CheckRunner {
    suspend fun run(
        check: CheckConfig,
        cwd: Path,
        env: Map<String, String>,
    ): CheckResult

    companion object {
        /** Refuses every check, so an orchestrator without a check runner never lets an unchecked move through. */
        val NONE: CheckRunner = CheckRunner { _, _, _ -> CheckResult(null, false, 0, startError = "no check runner") }
    }
}

/**
 * Runs every core check type (m2-03, m2-12): a `command` with `sh -lc <run>` in the workspace through [ShellRun],
 * with only the env it is given, and `file_exists`, `file_matches`, `diff_policy` and `changed_line_coverage` (bl-117)
 * in process, see [CoreChecks].
 */
class CoreCheckRunner(
    private val shell: ShellRun = ShellRun(start = { command, cwd, env -> SessionProcess.start(command, cwd, env, inheritEnv = false) }),
) : CheckRunner {
    override suspend fun run(
        check: CheckConfig,
        cwd: Path,
        env: Map<String, String>,
    ): CheckResult =
        when (check) {
            is CheckConfig.Command -> {
                val result = shell.run(check.run, cwd, env, check.timeoutMs, keepAll = true)
                CheckResult(
                    exitCode = result.exitCode,
                    timedOut = result.timedOut,
                    durationMs = result.durationMs,
                    startError = result.startError?.let { "requires perl on PATH: $it" },
                    output = result.fullOutput.orEmpty(),
                    outputTruncated = result.fullOutputTruncated,
                )
            }
            is CheckConfig.FileExists -> CoreChecks.fileExists(check, cwd)
            is CheckConfig.FileMatches -> CoreChecks.fileMatches(check, cwd)
            is CheckConfig.DiffPolicy -> CoreChecks.diffPolicy(check, cwd, env)
            is CheckConfig.ChangedLineCoverage -> CoreChecks.changedLineCoverage(check, cwd, env)
            is CheckConfig.AcceptanceCommands -> CheckResult(null, false, 0, startError = "acceptance commands run as command checks")
        }
}
