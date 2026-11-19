package loop.runner.command

import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.RunnerConfigException
import loop.api.RunnerProvider
import java.nio.file.Path

/**
 * The `command` runner plugin (m3-04), registered in `META-INF/services/loop.api.RunnerProvider`. Its section,
 * `runners.command`, has one key, `command`, the shell command [CommandRunner] runs with `sh -c`. See
 * `docs/plugins/runner-command.md`.
 */
class CommandRunnerProvider : RunnerProvider {
    override val kind: String = KIND

    override val profile: PluginProfile = PluginProfile(listOf(ProfileKey("command", ProfileType.STRING, required = true)))

    override fun validate(config: Map<String, Any?>) {
        command(config)
    }

    override fun create(
        config: Map<String, Any?>,
        env: AgentEnvPolicy,
        holdoutDir: Path?,
    ): AgentRunner =
        CommandRunner(
            command(config),
            hostEnv = env.hostEnv,
            removedEnvNames = env.removedEnvNames,
            addedEnv = env.addedEnv,
        )

    /** `runners.command.command`, kept verbatim, `$` and `~` included. */
    private fun command(config: Map<String, Any?>): String =
        when (val value = config["command"]) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            else -> throw RunnerConfigException("runners.$KIND.command must be a string")
        } ?: throw RunnerConfigException("runners.$KIND.command is required")

    private companion object {
        const val KIND = "command"
    }
}
