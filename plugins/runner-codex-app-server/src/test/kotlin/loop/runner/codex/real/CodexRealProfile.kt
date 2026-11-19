package loop.runner.codex.real

import loop.api.RunnerConfigException
import loop.runner.codex.CodexAppServerSettings
import loop.runner.codex.CommandWords
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The `codex` target of the real integration profile (m3-24, see [loop.api.RealIntegration]): the command it runs and
 * what it needs before a real session can start.
 *
 * [COMMAND_VARIABLE] overrides the command, `codex app-server` by default. It is how the run picks a model the login
 * accepts, `codex app-server -c model=gpt-6.1-sol`, since the runner sends no model (bl-141). The credential is the
 * codex login itself, checked with `codex login status` under the test's env, so `CODEX_HOME` and a keyring store
 * count as codex counts them.
 */
internal object CodexRealProfile {
    const val COMMAND_VARIABLE = "LOOP_REAL_CODEX_COMMAND"

    private const val LOGIN_STATUS_TIMEOUT_SECONDS = 30L

    /** The command of the run: [COMMAND_VARIABLE] when set and not blank, else the runner's default. */
    fun command(env: Map<String, String>): String =
        env[COMMAND_VARIABLE]?.takeIf { it.isNotBlank() } ?: CodexAppServerSettings.DEFAULT_COMMAND

    /**
     * What the run lacks under [env], empty when it can start: a command [CommandWords] can split, its program on
     * `PATH` (or at its path), and a codex login, which that program's `login status` confirms with exit 0.
     */
    fun missing(env: Map<String, String>): List<String> {
        val command = command(env)
        val program =
            try {
                CommandWords.of(command, COMMAND_VARIABLE).first()
            } catch (e: RunnerConfigException) {
                return listOf("a command the runner can launch in $COMMAND_VARIABLE (${e.message})")
            }
        val executable =
            executable(program, env["PATH"])
                ?: return listOf("the program $program of the command `$command` on PATH")
        val exit = loginStatus(executable, env)
        if (exit == 0) return emptyList()
        val home = env["CODEX_HOME"]?.let { "CODEX_HOME=$it" } ?: "CODEX_HOME unset, so ~/.codex"
        val result = exit?.let { "exited $it" } ?: "did not answer in $LOGIN_STATUS_TIMEOUT_SECONDS seconds"
        return listOf("a codex login: `$program login status` $result ($home); run `codex login`")
    }

    /** [program] as a file to run: itself when it holds a slash, else the first executable match in [path]. */
    fun executable(
        program: String,
        path: String?,
    ): Path? {
        if ('/' in program) return Path.of(program).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        return path
            .orEmpty()
            .split(':')
            .filter { it.isNotEmpty() }
            .map { Path.of(it, program) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    /** The exit code of `<executable> login status` run with exactly [env], null when it did not end in time. */
    private fun loginStatus(
        executable: Path,
        env: Map<String, String>,
    ): Int? {
        val process =
            ProcessBuilder(executable.toString(), "login", "status")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    environment().clear()
                    environment().putAll(env)
                }.start()
        process.outputStream.close()
        if (!process.waitFor(LOGIN_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return process.exitValue()
    }
}
