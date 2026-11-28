package loop.tracker.github.real

import loop.tracker.github.GitHubConfigError
import loop.tracker.github.GitHubTrackerSettings

/**
 * The `github` target of the real integration profile (m3-25, see [loop.api.RealIntegration]): the repository it runs
 * against and the token it reads.
 *
 * The repository is the playground (`docs/self-hosting.md`), named `owner/name` in [REPO_VARIABLE], with no default.
 * The credential is [TOKEN_VARIABLE], the playground's own fine grained token, never `GITHUB_TOKEN`, which the `gh` CLI
 * reads. The settings are built with [GitHubTrackerSettings.parse], the path a `WORKFLOW.md` takes, so the token is
 * trimmed and refused when it holds whitespace or a control character, and no error message ever shows its value.
 */
internal object GitHubRealProfile {
    const val TOKEN_VARIABLE = "LOOP_PLAYGROUND_GITHUB_TOKEN"

    const val REPO_VARIABLE = "LOOP_REAL_GITHUB_REPO"

    /** The playground repository [env] names in [REPO_VARIABLE], trimmed, or null when it is unset or blank. */
    fun repo(env: Map<String, String>): String? = env[REPO_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }

    /** The tracker settings of a run whose issues all carry [scopeLabel], or the config error naming what is missing. */
    fun settings(
        env: Map<String, String>,
        scopeLabel: String,
    ): Result<GitHubTrackerSettings> {
        val repo = repo(env) ?: return Result.failure(IllegalStateException("\$$REPO_VARIABLE is not set"))
        return parse(repo, env, scopeLabel)
    }

    /**
     * What the run lacks under [env], empty when it can start: the playground in [REPO_VARIABLE] and a usable token in
     * [TOKEN_VARIABLE]. Both are checked, so a run missing both names both.
     */
    fun missing(env: Map<String, String>): List<String> {
        val repo = repo(env)
        val absent = mutableListOf<String>()
        if (repo == null) absent += "the playground repository, as owner/name, in $REPO_VARIABLE"
        val error = parse(repo ?: TOKEN_PROBE_REPO, env, "loop-real-probe").exceptionOrNull() ?: return absent
        val reason = (error as? GitHubConfigError)?.message ?: error.toString()
        absent += "a fine grained token for ${repo ?: "the playground"} in $TOKEN_VARIABLE ($reason)"
        return absent
    }

    private fun parse(
        repo: String,
        env: Map<String, String>,
        scopeLabel: String,
    ): Result<GitHubTrackerSettings> =
        GitHubTrackerSettings.parse(
            mapOf("repo" to repo, "token" to "\$$TOKEN_VARIABLE", "labels" to listOf(scopeLabel)),
            env,
        )

    /** A well formed repository that only lets [missing] check the token when [REPO_VARIABLE] is unset. */
    private const val TOKEN_PROBE_REPO = "owner/loop-playground"
}
