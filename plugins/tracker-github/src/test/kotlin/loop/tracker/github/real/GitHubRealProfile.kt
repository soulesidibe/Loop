package loop.tracker.github.real

import loop.tracker.github.GitHubConfigError
import loop.tracker.github.GitHubTrackerSettings

/**
 * The `github` target of the real integration profile (m3-25, see [loop.api.RealIntegration]): the repository it runs
 * against and the token it reads.
 *
 * The repository is the playground, `Soules-Studio-Ltd/loop-playground` (`docs/self-hosting.md`). The credential is
 * [TOKEN_VARIABLE], the playground's own fine grained token, never `GITHUB_TOKEN`, which the `gh` CLI reads. The
 * settings are built with [GitHubTrackerSettings.parse], the path a `WORKFLOW.md` takes, so the token is trimmed and
 * refused when it holds whitespace or a control character, and no error message ever shows its value.
 */
internal object GitHubRealProfile {
    const val TOKEN_VARIABLE = "LOOP_PLAYGROUND_GITHUB_TOKEN"

    const val REPO = "Soules-Studio-Ltd/loop-playground"

    /** The tracker settings of a run whose issues all carry [scopeLabel], or the config error naming the token. */
    fun settings(
        env: Map<String, String>,
        scopeLabel: String,
    ): Result<GitHubTrackerSettings> =
        GitHubTrackerSettings.parse(
            mapOf("repo" to REPO, "token" to "\$$TOKEN_VARIABLE", "labels" to listOf(scopeLabel)),
            env,
        )

    /** What the run lacks under [env], empty when it can start: a usable token in [TOKEN_VARIABLE]. */
    fun missing(env: Map<String, String>): List<String> {
        val error = settings(env, "loop-real-probe").exceptionOrNull() ?: return emptyList()
        val reason = (error as? GitHubConfigError)?.message ?: error.toString()
        return listOf("a fine grained token for $REPO in $TOKEN_VARIABLE ($reason)")
    }
}
