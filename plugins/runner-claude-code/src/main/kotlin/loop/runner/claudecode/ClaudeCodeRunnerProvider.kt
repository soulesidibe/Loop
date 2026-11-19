package loop.runner.claudecode

import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.PluginProfile
import loop.api.RunnerProvider
import java.nio.file.Path

/**
 * The `claude-code` runner plugin (m3-04), registered in `META-INF/services/loop.api.RunnerProvider`. Its section,
 * `runners.claude-code`, is read by [ClaudeCodeSettings.of], its profile is [ClaudeCodeSettings.PROFILE]. See
 * `docs/plugins/runner-claude-code.md`.
 */
class ClaudeCodeRunnerProvider : RunnerProvider {
    override val kind: String = ClaudeCodeSettings.KIND

    override val profile: PluginProfile = ClaudeCodeSettings.PROFILE

    override fun validate(config: Map<String, Any?>) {
        ClaudeCodeSettings.of(config)
    }

    override fun create(
        config: Map<String, Any?>,
        env: AgentEnvPolicy,
        holdoutDir: Path?,
    ): AgentRunner =
        ClaudeCodeRunner(
            ClaudeCodeSettings.of(config, holdoutDir),
            hostEnv = env.hostEnv,
            removedEnvNames = env.removedEnvNames,
            addedEnv = env.addedEnv,
        )
}
