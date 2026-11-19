package loop.runner.process

import java.nio.file.Path

/**
 * Names never passed to an agent child: the agent runs on the user's own login, never on API
 * billing (no opt in in M1).
 */
val ALWAYS_REMOVED_ENV_NAMES: Set<String> = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

/** The placeholder an `agent.env` value can hold, replaced by the attempt's workspace path (bl-01). */
const val WORKSPACE_PLACEHOLDER: String = "{workspace}"

/**
 * The agent environment shared by every runner: the [host] environment minus the [removed]
 * names, then the [added] names of `agent.env` (bl-01, already resolved with [resolveAddedEnv]),
 * then Loop's own [overrides] (LOOP_* and PATH with the launcher first), which survive removal
 * and win over [added], then minus [ALWAYS_REMOVED_ENV_NAMES], which wins over everything.
 */
fun agentEnvironment(
    overrides: Map<String, String> = emptyMap(),
    removed: Set<String> = emptySet(),
    host: Map<String, String> = System.getenv(),
    added: Map<String, String> = emptyMap(),
): Map<String, String> = ((host - removed) + added + overrides) - ALWAYS_REMOVED_ENV_NAMES

/** The `agent.env` values [added] with every [WORKSPACE_PLACEHOLDER] replaced by [workspace]; nothing else is expanded. */
fun resolveAddedEnv(
    added: Map<String, String>,
    workspace: Path,
): Map<String, String> = added.mapValues { (_, value) -> value.replace(WORKSPACE_PLACEHOLDER, workspace.toString()) }
