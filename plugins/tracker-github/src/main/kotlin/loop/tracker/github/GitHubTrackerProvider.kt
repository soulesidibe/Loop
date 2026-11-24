package loop.tracker.github

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerAdapter
import loop.api.TrackerConfigException
import loop.api.TrackerContext
import loop.api.TrackerProvider

/**
 * The `github` tracker plugin (m3-05), registered in `META-INF/services/loop.api.TrackerProvider`. Its section,
 * `tracker.provider`, is read by [GitHubTrackerSettings.parse], whose errors keep their spec 11.4 category; the
 * tracker uses the JDK transport. The host builds it with the run's [GitHubClients] (m4-48). See
 * `docs/plugins/tracker-github.md`.
 */
class GitHubTrackerProvider : TrackerProvider {
    override val kind: String = "github"

    override val profile: PluginProfile =
        PluginProfile(
            keys =
                listOf(
                    ProfileKey("repo", ProfileType.STRING, required = true),
                    ProfileKey("token", ProfileType.STRING, required = true),
                    ProfileKey("api_url", ProfileType.STRING, default = GitHubTrackerSettings.DEFAULT_API_URL.toString()),
                    ProfileKey("labels", ProfileType.LIST, default = "empty"),
                    ProfileKey(
                        "handoff_labels",
                        ProfileType.MAP,
                        default = GitHubTrackerSettings.DEFAULT_HANDOFF_LABELS.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                    ),
                    ProfileKey(
                        "trusted_associations",
                        ProfileType.LIST,
                        default = GitHubTrackerSettings.DEFAULT_TRUSTED_ASSOCIATIONS.joinToString(", "),
                    ),
                ),
            secrets = setOf("token"),
        )

    override val defaultActiveStates: List<String> = listOf("open")

    override val defaultTerminalStates: List<String> = listOf("closed")

    override val definedStates: List<String> = GitHubTracker.DEFINED_STATES

    override fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String>,
    ): Any? =
        GitHubTrackerSettings.parse(provider, env).getOrElse { error ->
            throw if (error is GitHubConfigError) TrackerConfigException(error.category, error.message.orEmpty()) else error
        }

    /** The tracker of [settings], over its own GitHub clients, so its rate limit hold is its own. */
    override fun create(
        settings: Any?,
        context: TrackerContext,
    ): TrackerAdapter = create(settings, context, GitHubClients())

    /**
     * The tracker of [settings], sending through the client of [clients] for its API URL and token (m4-48): `loop run`
     * passes the clients it shares with pull request delivery, so one rate limit hold covers both (bl-50).
     */
    fun create(
        settings: Any?,
        context: TrackerContext,
        clients: GitHubClients,
    ): TrackerAdapter {
        require(settings is GitHubTrackerSettings) { "the github tracker is built from the settings its parse read" }
        return GitHubTracker(
            settings,
            onMalformed = context.onMalformed,
            requiredLabels = context.requiredLabels,
            clients = clients,
            onWarning = context.onWarning,
        )
    }
}
