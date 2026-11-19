package loop.api

import java.nio.file.Path

/**
 * A runner plugin (m3-04, plan 12): `runner.kind` selects the provider whose [kind] matches. Providers are found
 * with Java's `ServiceLoader`, on the classpath and in each JAR of `<LOOP_HOME>/plugins/`, so a module registers
 * its runner with a `META-INF/services/loop.api.RunnerProvider` resource naming its provider class, which needs a
 * public no argument constructor. Two providers of one kind fail startup.
 */
public interface RunnerProvider {
    /** The `runner.kind` this provider answers to, for example `claude-code`. */
    public val kind: String

    /** The key of the provider's own section under `runners`: [kind] unless it says otherwise, `codex` for `codex-app-server`. */
    public val section: String get() = kind

    /**
     * The keys of the section `runners.<section>` (m3-05), checked on every load of `WORKFLOW.md` before [validate]
     * reads them: a missing required key, a wrong type, a value out of its limits or an unresolved secret fails
     * preflight, an unknown key gives a startup warning.
     */
    public val profile: PluginProfile

    /**
     * Reads [config], the provider's section `runners.<section>` as written (string, list, map or null values, an
     * empty map when the section is absent), and throws [RunnerConfigException] when it is invalid. Loop calls it on
     * every load of `WORKFLOW.md`, so a bad section fails preflight with `invalid_config` before any attempt.
     */
    public fun validate(config: Map<String, Any?>)

    /**
     * Builds the runner from [config], read like [validate], and the agent env policy of the host. [holdoutDir] is
     * the holdout folder an agent must never read (m2-14), null when the host has none.
     */
    public fun create(
        config: Map<String, Any?>,
        env: AgentEnvPolicy,
        holdoutDir: Path?,
    ): AgentRunner
}

/** An invalid runner section; the message names the key, `runners.<section>.<key>`, never a secret value. */
public class RunnerConfigException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The agent env policy the host builds (m1-23, bl-01): the agent env is [hostEnv] minus [removedEnvNames], plus
 * [addedEnv] (`agent.env`, `{workspace}` still in its values) and Loop's own variables.
 */
public data class AgentEnvPolicy(
    public val hostEnv: Map<String, String>,
    public val removedEnvNames: Set<String>,
    public val addedEnv: Map<String, String>,
)
