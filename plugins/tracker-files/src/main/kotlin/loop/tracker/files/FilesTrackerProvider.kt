package loop.tracker.files

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerAdapter
import loop.api.TrackerContext
import loop.api.TrackerProvider

/**
 * The `files` tracker plugin (m3-05), registered in `META-INF/services/loop.api.TrackerProvider`. Its one key,
 * `tracker.provider.dir`, is a path the loader resolves against the folder of `WORKFLOW.md`, a whole `$NAME` read
 * from the env first, and hands the tracker as [TrackerContext.trackerDir]; it needs no settings of its own.
 * See `docs/plugins/tracker-files.md`.
 */
class FilesTrackerProvider : TrackerProvider {
    override val kind: String = "files"

    override val profile: PluginProfile = PluginProfile(listOf(ProfileKey("dir", ProfileType.STRING, default = ".loop/issues")))

    override val defaultActiveStates: List<String> = listOf("todo", "in_progress")

    override val defaultTerminalStates: List<String> = listOf("done", "canceled")

    override val definedStates: List<String> = FilesTracker.DEFINED_STATES

    override fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String>,
    ): Any? = null

    override fun create(
        settings: Any?,
        context: TrackerContext,
    ): TrackerAdapter = FilesTracker(context.trackerDir, context.lockDir, context.onMalformed, context.onWarning)
}
