package loop.tracker.github

import java.net.URI
import java.net.URISyntaxException

/**
 * The `tracker.provider` settings of the `github` tracker, see `docs/plugins/tracker-github.md`.
 * [token] is the resolved secret; [tokenEnvName] is the variable it came from, null for a literal.
 * [toString] never shows the token.
 */
data class GitHubTrackerSettings(
    val owner: String,
    val repo: String,
    val token: String,
    val apiUrl: URI = DEFAULT_API_URL,
    /** Labels every issue in scope carries, as written; GitHub compares them case insensitively. */
    val scopeLabels: List<String> = emptyList(),
    val tokenEnvName: String? = null,
    /** The label of each handoff state, `review` and `needs_info` (m2-23). */
    val handoffLabels: Map<String, String> = DEFAULT_HANDOFF_LABELS,
) {
    override fun toString(): String =
        "GitHubTrackerSettings(repo=$owner/$repo, apiUrl=$apiUrl, scopeLabels=$scopeLabels, handoffLabels=$handoffLabels, " +
            "token=${tokenEnvName?.let { "\$$it" } ?: "literal"})"

    companion object {
        val DEFAULT_API_URL: URI = URI("https://api.github.com")

        /**
         * True when the host of [uri] is a loopback host (m2-25): `localhost`, an address of `127.0.0.0/8` or `::1`.
         * Only a fake server of a test listens there, so only there may Loop talk plain http.
         */
        fun isLoopback(uri: URI): Boolean = LOOPBACK.matches(uri.host.orEmpty().lowercase())

        private val LOOPBACK = Regex("localhost|127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}|\\[::1]")

        /** The labels m2-11 and the playground use for the two handoff states. */
        val DEFAULT_HANDOFF_LABELS: Map<String, String> = mapOf("review" to "needs-review", "needs_info" to "needs-info")

        /**
         * Reads the `tracker.provider` map, whose values are strings, lists, maps or null, as the
         * front matter holds them. Only `token` reads a whole `$NAME` value from [env] (spec 5.3.1);
         * unknown keys are ignored. Fails with a [GitHubConfigError] that names keys and variables,
         * never a secret value.
         */
        fun parse(
            provider: Map<String, Any?>,
            env: Map<String, String>,
        ): Result<GitHubTrackerSettings> =
            try {
                Result.success(read(provider, env))
            } catch (e: GitHubConfigError) {
                Result.failure(e)
            }

        private fun read(
            provider: Map<String, Any?>,
            env: Map<String, String>,
        ): GitHubTrackerSettings {
            val repoValue = provider["repo"] as? String ?: throw GitHubConfigError.Invalid("tracker.provider.repo is required")
            val repoMatch =
                REPO.matchEntire(repoValue.trim())
                    ?: throw GitHubConfigError.Invalid("tracker.provider.repo must be owner/name, got \"$repoValue\"")
            val (token, tokenEnvName) = token(provider["token"], env)
            return GitHubTrackerSettings(
                owner = repoMatch.groupValues[1],
                repo = repoMatch.groupValues[2],
                token = token,
                apiUrl = apiUrl(provider["api_url"]),
                scopeLabels = labels(provider["labels"]),
                tokenEnvName = tokenEnvName,
                handoffLabels = handoffLabels(provider["handoff_labels"]),
            )
        }

        private fun token(
            value: Any?,
            env: Map<String, String>,
        ): Pair<String, String?> {
            val written =
                (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw GitHubConfigError.MissingSecret("tracker.provider.token is required")
            val name = ENV_REF.matchEntire(written)?.groupValues?.get(1)
            if (name == null) {
                if (!usable(written)) {
                    throw GitHubConfigError.Invalid("tracker.provider.token must not hold whitespace or a control character")
                }
                return written to null
            }
            val resolved = env[name] ?: throw GitHubConfigError.MissingSecret("tracker.provider.token uses \$$name, which is not set")
            if (resolved.isBlank()) throw GitHubConfigError.MissingSecret("tracker.provider.token uses \$$name, which is empty")
            val token = resolved.trim()
            if (!usable(token)) {
                throw GitHubConfigError.Invalid("tracker.provider.token uses \$$name, whose value holds whitespace or a control character")
            }
            return token to name
        }

        /**
         * A trimmed token with no whitespace or control character inside (bl-22): such a token can
         * never be valid, and an HTTP client that refuses the header could quote it in its error.
         */
        private fun usable(token: String): Boolean = token.none { it.isWhitespace() || it.isISOControl() }

        private fun apiUrl(value: Any?): URI {
            if (value == null) return DEFAULT_API_URL
            val invalid = GitHubConfigError.Invalid("tracker.provider.api_url must be an http or https address, got \"$value\"")
            val text = (value as? String)?.trim()?.trimEnd('/') ?: throw invalid
            val uri =
                try {
                    URI(text)
                } catch (e: URISyntaxException) {
                    throw invalid
                }
            if (uri.scheme !in setOf("http", "https") ||
                uri.host.isNullOrEmpty() ||
                uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                throw invalid
            }
            // m2-25: the token goes to this address, so http only for a fake server on this machine.
            if (uri.scheme == "http" && !isLoopback(uri)) {
                throw GitHubConfigError.Invalid(
                    "tracker.provider.api_url must be an https address, http is allowed only for a loopback host, got \"$value\"",
                )
            }
            return uri
        }

        private fun labels(value: Any?): List<String> {
            if (value == null) return emptyList()
            val items = value as? List<*> ?: throw GitHubConfigError.Invalid("tracker.provider.labels must be a list of labels")
            return items
                .map { it as? String ?: throw GitHubConfigError.Invalid("tracker.provider.labels must be a list of labels") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /**
         * `handoff_labels` (m2-23): a map of `review` and `needs_info` to the label a move to that state adds. A
         * state left out keeps its label from [DEFAULT_HANDOFF_LABELS]; any other key is refused, as a typo would
         * otherwise leave the default in place unseen.
         */
        private fun handoffLabels(value: Any?): Map<String, String> {
            if (value == null) return DEFAULT_HANDOFF_LABELS
            val map =
                value as? Map<*, *>
                    ?: throw GitHubConfigError.Invalid("tracker.provider.handoff_labels must map review and needs_info to a label")
            val written =
                map.entries.associate { (key, label) ->
                    val state = key as? String
                    if (state !in DEFAULT_HANDOFF_LABELS) {
                        throw GitHubConfigError.Invalid(
                            "tracker.provider.handoff_labels has \"$key\", only review and needs_info have a handoff label",
                        )
                    }
                    val name =
                        (label as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: throw GitHubConfigError.Invalid("tracker.provider.handoff_labels.$state must be a label")
                    checkNotNull(state) to name
                }
            return DEFAULT_HANDOFF_LABELS + written
        }

        /** GitHub owner and repository names: letters, digits, `-`, `_` and `.`. */
        private val REPO = Regex("([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")

        /** A whole value `$NAME`, the same rule as the core `$VAR` paths (plan D3). */
        private val ENV_REF = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)")
    }
}

/** A `tracker.provider` setting the `github` tracker cannot use, with its spec 11.4 [category]. */
sealed class GitHubConfigError(
    val category: String,
    message: String,
) : Exception(message) {
    /** `invalid_tracker_config`: a key is missing or has a value the tracker cannot use, a token with whitespace inside included. */
    class Invalid(
        message: String,
    ) : GitHubConfigError("invalid_tracker_config", message)

    /** `missing_tracker_secret`: the token is absent, or its `$NAME` is unset or empty. */
    class MissingSecret(
        message: String,
    ) : GitHubConfigError("missing_tracker_secret", message)
}
