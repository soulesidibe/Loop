package loop.tracker.files

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerAdapter
import loop.api.TrackerConfigException
import loop.api.TrackerContext
import loop.api.TrackerProvider

/**
 * The `files` tracker plugin (m3-05), registered in `META-INF/services/loop.api.TrackerProvider`. Its key
 * `tracker.provider.dir` is a path the loader resolves against the folder of `WORKFLOW.md`, a whole `$NAME` read
 * from the env first, and hands the tracker as [TrackerContext.trackerDir]. Its key `prefix` (m4-33) names the
 * issues the tracker creates, `<prefix>-<nn>`, [FilesTracker.DEFAULT_PREFIX] when absent; it is the one setting [parse]
 * reads, so a workflow without it has no settings. See `docs/plugins/tracker-files.md`.
 */
class FilesTrackerProvider : TrackerProvider {
    override val kind: String = "files"

    override val profile: PluginProfile =
        PluginProfile(
            listOf(
                ProfileKey("dir", ProfileType.STRING, default = ".loop/issues"),
                ProfileKey(PREFIX, ProfileType.STRING, default = FilesTracker.DEFAULT_PREFIX),
            ),
        )

    override val defaultActiveStates: List<String> = listOf("todo", "in_progress")

    override val defaultTerminalStates: List<String> = listOf("done", "canceled")

    override val definedStates: List<String> = FilesTracker.DEFINED_STATES

    /** Null when `prefix` is absent; a prefix must be a lowercase letter, then lowercase letters and digits. */
    override fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String>,
    ): Any? {
        val prefix = provider[PREFIX] ?: return null
        if (prefix !is String || !FilesTracker.PREFIX_PATTERN.matches(prefix)) {
            throw TrackerConfigException(
                "invalid_tracker_config",
                "tracker.provider.prefix must be a lowercase letter, then lowercase letters and digits, such as bl",
            )
        }
        return FilesTrackerSettings(prefix)
    }

    override fun create(
        settings: Any?,
        context: TrackerContext,
    ): TrackerAdapter =
        FilesTracker(
            context.trackerDir,
            context.lockDir,
            context.onMalformed,
            context.onWarning,
            prefix = (settings as? FilesTrackerSettings)?.prefix ?: FilesTracker.DEFAULT_PREFIX,
            firstActiveState = context.activeStates.firstOrNull() ?: FilesTracker.DEFAULT_ACTIVE_STATE,
        )

    private companion object {
        const val PREFIX = "prefix"
    }
}

/** What the `files` tracker reads from `tracker.provider` (m4-33): the [prefix] of the issues it creates. */
data class FilesTrackerSettings(
    val prefix: String,
)
