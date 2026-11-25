package loop.cli

import loop.core.process.SessionProcess
import loop.core.process.ShellRun
import loop.core.resources.CommandOutcome
import loop.core.resources.ResourceCommands
import java.nio.file.Path

/**
 * Runs the `read_only_check` and `restore` commands of a resource (m4-26) with `sh -lc` in the folder [cwd] gives,
 * with exactly the env [base] gives plus the `LOOP_RESOURCE_<KEY>` env of the resource, nothing inherited, and a
 * [timeoutMs] past which the command fails. `loop tool mcp` runs the check of a release in the agent's own env and
 * folder; `loop run` runs the check and the restore at the end of an attempt with the agent env policy.
 */
internal class ShellResourceCommands(
    private val cwd: () -> Path,
    private val base: () -> Map<String, String>,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val shell: ShellRun = ShellRun(start = { command, dir, env -> SessionProcess.start(command, dir, env, inheritEnv = false) }),
) : ResourceCommands {
    override suspend fun run(
        command: String,
        env: Map<String, String>,
    ): CommandOutcome {
        val result = shell.run(command, cwd(), base() + env, timeoutMs, keepAll = true)
        val output = result.fullOutput ?: result.output
        return when {
            result.startError != null -> CommandOutcome(null, "cannot start the command: ${result.startError}")
            result.timedOut -> CommandOutcome(null, "timed out after ${timeoutMs / 1000} s. $output".trim())
            else -> CommandOutcome(result.exitCode, output)
        }
    }

    companion object {
        /** How long a resource command may run before it fails: 5 minutes. */
        const val TIMEOUT_MS: Long = 300_000
    }
}
