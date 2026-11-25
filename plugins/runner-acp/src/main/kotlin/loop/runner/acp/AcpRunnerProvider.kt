package loop.runner.acp

import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.PluginProfile
import loop.api.RunnerProvider
import java.nio.file.Path

/**
 * The `acp` runner plugin, registered in `META-INF/services/loop.api.RunnerProvider`. Its section
 * is `runners.acp`, read by [AcpSettings.of], and its profile is [AcpSettings.PROFILE].
 * See `docs/plugins/runner-acp.md`.
 */
class AcpRunnerProvider : RunnerProvider {
    override val kind: String = AcpSettings.KIND

    override val section: String = AcpSettings.SECTION

    override val profile: PluginProfile = AcpSettings.PROFILE

    override fun validate(config: Map<String, Any?>) {
        AcpSettings.of(config)
    }

    override fun create(
        config: Map<String, Any?>,
        env: AgentEnvPolicy,
        holdoutDir: Path?,
    ): AgentRunner =
        AcpRunner(
            settings = AcpSettings.of(config, holdoutDir),
            hostEnv = env.hostEnv,
            removedEnvNames = env.removedEnvNames,
            addedEnv = env.addedEnv,
        )
}
