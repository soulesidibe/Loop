package loop.runner.codex

import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.PluginProfile
import loop.api.RunnerProvider
import java.nio.file.Path

/**
 * The `codex-app-server` runner plugin (m3-17), registered in `META-INF/services/loop.api.RunnerProvider`, so plugin
 * loading finds it with no change to the CLI host. Its section is `runners.codex`, read by [CodexAppServerSettings.of],
 * its profile is [CodexAppServerSettings.PROFILE]. See `docs/plugins/runner-codex-app-server.md`.
 */
class CodexAppServerRunnerProvider : RunnerProvider {
    override val kind: String = CodexAppServerSettings.KIND

    override val section: String = CodexAppServerSettings.SECTION

    override val profile: PluginProfile = CodexAppServerSettings.PROFILE

    override fun validate(config: Map<String, Any?>) {
        CodexAppServerSettings.of(config)
    }

    /**
     * Builds the runner with [holdoutDir], the host's holdout folder (`loop run` always gives one), whose reads and
     * edits the runner's approval rules refuse, beside `git push`, `gh` and the history rewrites (m3-20). Until m3-20
     * it refused to build a runner for Loop's runs, since nothing guarded them.
     */
    override fun create(
        config: Map<String, Any?>,
        env: AgentEnvPolicy,
        holdoutDir: Path?,
    ): AgentRunner =
        CodexAppServerRunner(
            CodexAppServerSettings.of(config, holdoutDir),
            hostEnv = env.hostEnv,
            removedEnvNames = env.removedEnvNames,
            addedEnv = env.addedEnv,
        )
}
