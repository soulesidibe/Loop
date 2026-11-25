package loop.runner.acp.real

import loop.api.RunnerConfigException
import loop.runner.acp.AcpSettings
import loop.runner.acp.AgyPreflight
import loop.runner.acp.CommandWords
import java.nio.file.Path

/**
 * The `agy` target of the real integration profile (see [loop.api.RealIntegration] and
 * `.loop/specs/gemini-support.md`).
 *
 * [COMMAND_VARIABLE] overrides the command (`agy acp` by default); [MODEL_VARIABLE] overrides the
 * Gemini model (`gemini-4-neon-high` by default). Credentials come from the operator's Google login
 * in the OS keyring, checked with `agy models`.
 */
internal object AgyRealProfile {
    const val COMMAND_VARIABLE: String = "LOOP_REAL_AGY_COMMAND"
    const val MODEL_VARIABLE: String = "LOOP_REAL_AGY_MODEL"

    const val DEFAULT_COMMAND: String = "agy acp"
    const val DEFAULT_MODEL: String = "gemini-4-neon-high"

    fun command(env: Map<String, String>): String = env[COMMAND_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_COMMAND

    fun model(env: Map<String, String>): String = env[MODEL_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * What the real `agy` run lacks under [env], empty when ready to start: valid `preset: agy`
     * settings, reachable `agy` binary on pinned major version `1`, and a working `agy models`
     * probe listing the selected model.
     */
    fun missing(
        env: Map<String, String>,
        resolveBinary: (String, Map<String, String>) -> String? = AgyPreflight::defaultResolveBinary,
        runProbe: ((List<String>, Map<String, String>, Long) -> AgyPreflight.ProbeOutput?)? = null,
    ): List<String> {
        val command = command(env)
        val model = model(env)
        try {
            AcpSettings.of(
                mapOf(
                    "command" to command,
                    "preset" to AcpSettings.PRESET_AGY,
                    "model" to model,
                ),
            )
            CommandWords.of(command, COMMAND_VARIABLE)
        } catch (e: RunnerConfigException) {
            return listOf("valid agy real profile settings (${e.message})")
        }

        val preflight =
            if (runProbe != null) {
                AgyPreflight(command, model, env, resolveBinary = resolveBinary, runProbe = runProbe)
            } else {
                AgyPreflight(command, model, env, resolveBinary = resolveBinary)
            }
        val checked = preflight.check()
        checked.error?.let { return listOf(it) }
        if (checked.warnings.isNotEmpty()) return checked.warnings
        return emptyList()
    }

    fun executable(
        program: String,
        env: Map<String, String>,
    ): Path? = AgyPreflight.defaultResolveBinary(program, env)?.let(Path::of)
}
