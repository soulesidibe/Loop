package loop.core.trackers

import loop.api.ProfileProblem
import loop.api.TrackerConfigException
import loop.api.TrackerProvider
import loop.core.workflow.ConfigValue
import loop.core.workflow.DeferredSecret
import loop.core.workflow.TrackerCheck
import loop.core.workflow.WorkflowError
import loop.core.workflow.checkPluginProfile
import loop.core.workflow.secretCheckEnv
import java.nio.file.Path

/**
 * Reads the trackers of a `WORKFLOW.md` (m4-11, PLAN 6.1): the spec's `tracker:`, the primary tracker, then the extra
 * trackers of `trackers:`, from their front matter values as written (strings, lists, maps or null). Each one's `kind`
 * selects a tracker plugin through [trackerCheck], which checks and reads its own `provider` (bl-23, m3-05); a null
 * check accepts only `files`. Every failure is a [WorkflowError] naming the key, `trackers[<index>]` for an extra
 * tracker, so the preflight names the entry. Warnings go to [warnings] and deferred secrets (bl-70) to [deferred].
 */
internal class TrackerParser(
    private val env: Map<String, String>,
    private val trackerCheck: TrackerCheck?,
    private val deferSecrets: Boolean,
    private val warnings: MutableList<String>,
    private val deferred: MutableList<DeferredSecret>,
    /** The resolved folder of a `provider.dir` at the key path given, the value as written or null when unset. */
    private val resolveDir: (keyPath: String, value: String?) -> Path,
) {
    /** The primary tracker of the `tracker:` map [tracker], null when the front matter has none. */
    fun primary(tracker: Map<String, Any?>?): TrackerEntry {
        val kind = kindOf(PRIMARY, tracker)
        val id = tracker.string("$PRIMARY.id", "id") ?: TrackersConfig.DEFAULT_ID
        checkIdPattern(PRIMARY, id)
        return entry(PRIMARY, tracker, id, kind)
    }

    /** The extra trackers of the `trackers:` value [trackers], none when it is absent, each id unique beside [primaryId]. */
    fun extras(
        trackers: Any?,
        primaryId: String,
    ): List<TrackerEntry> {
        val list =
            when (trackers) {
                null -> return emptyList()
                is List<*> -> trackers
                else -> throw WorkflowError.InvalidConfig("trackers must be a list")
            }
        val owners = mutableMapOf(primaryId to "the primary tracker")
        return list.mapIndexed { index, item ->
            val name = "trackers[$index]"
            val map = item.asMap() ?: throw WorkflowError.InvalidConfig("$name must be a map")
            val id = map.string("$name.id", "id")?.takeIf { it.isNotBlank() } ?: throw WorkflowError.InvalidConfig("$name.id is required")
            checkIdPattern(name, id)
            owners[id]?.let { throw WorkflowError.InvalidConfig("$name.id \"$id\" is already the id of $it") }
            owners[id] = name
            warnings += map.keys.filter { it !in ENTRY_KEYS }.map { "$name.$it is not a key of a tracker entry, Loop ignores it" }
            entry(name, map, id, kindOf(name, map))
        }
    }

    private fun kindOf(
        name: String,
        map: Map<String, Any?>?,
    ): String = map.string("$name.kind", "kind")?.takeIf { it.isNotBlank() } ?: throw WorkflowError.InvalidConfig("$name.kind is required")

    private fun checkIdPattern(
        name: String,
        id: String,
    ) {
        if (!TrackersConfig.ID_PATTERN.matches(id)) {
            throw WorkflowError.InvalidConfig("$name.id \"$id\" must match ${TrackersConfig.ID_PATTERN.pattern}")
        }
    }

    /** The tracker [name] of [map], whose [kind] selects its plugin. */
    private fun entry(
        name: String,
        map: Map<String, Any?>?,
        id: String,
        kind: String,
    ): TrackerEntry {
        val plugin: TrackerProvider? =
            when {
                trackerCheck != null ->
                    trackerCheck.select(kind).getOrElse { error ->
                        val message = error.message ?: "tracker.kind is invalid"
                        throw WorkflowError.InvalidConfig(rename(name, message))
                    }
                kind == FILES_TRACKER -> null
                else -> throw WorkflowError.InvalidConfig(TrackerCheck.unsupported(kind, listOf(FILES_TRACKER), "$name.kind"))
            }
        val section = "$name.provider"
        val provider = map.child("provider", section)
        val settings = plugin?.let { read(name, section, it, provider.orEmpty()) }
        val dir = resolveDir("$section.dir", provider.string("$section.dir", "dir"))
        val activeStates = map.strings("$name.active_states", "active_states") ?: plugin?.defaultActiveStates ?: DEFAULT_ACTIVE_STATES
        val terminalStates =
            map.strings("$name.terminal_states", "terminal_states") ?: plugin?.defaultTerminalStates ?: DEFAULT_TERMINAL_STATES
        // The primary tracker's states are checked by the host's tracker check, as before m4-11.
        if (name != PRIMARY && plugin != null) {
            checkStates("$name.active_states", activeStates, plugin)
            checkStates("$name.terminal_states", terminalStates, plugin)
        }
        return TrackerEntry(
            id = id,
            kind = kind,
            dir = dir,
            settings = settings,
            activeStates = activeStates,
            terminalStates = terminalStates,
            requiredLabels = map.strings("$name.required_labels", "required_labels") ?: emptyList(),
            provider = provider.orEmpty().mapValues { it.value.toConfigValue() },
        )
    }

    /** What [plugin] reads from [provider], the section at [section] of the tracker [name], checked against its profile first. */
    private fun read(
        name: String,
        section: String,
        plugin: TrackerProvider,
        provider: Map<String, Any?>,
    ): Any? {
        val checkEnv = secretCheckEnv(env, deferSecrets, plugin.profile, section, provider, deferred)
        warnings +=
            checkPluginProfile(plugin.profile, section, provider, "the ${plugin.kind} tracker", checkEnv) { problem ->
                // bl-104: a secret out of its limits is there and resolved, so it is invalid, not missing.
                val missing = problem.secret && problem !is ProfileProblem.OutOfLimits
                WorkflowError.TrackerConfig(if (missing) MISSING_TRACKER_SECRET else INVALID_TRACKER_CONFIG, problem.message)
            }
        return try {
            // bl-70: the plugin checks the rest of its section with a stand in, whose settings are dropped.
            plugin.parse(provider, checkEnv).takeIf { checkEnv === env }
        } catch (e: TrackerConfigException) {
            throw WorkflowError.TrackerConfig(e.category, rename(name, e.message.orEmpty()))
        } catch (e: WorkflowError) {
            throw e
        } catch (e: Exception) {
            throw WorkflowError.InvalidConfig(rename(name, e.message ?: "tracker.provider is invalid"))
        }
    }

    private fun checkStates(
        key: String,
        states: List<String>,
        plugin: TrackerProvider,
    ) {
        val unknown = states.firstOrNull { it !in plugin.definedStates } ?: return
        val valid = plugin.definedStates.joinToString(", ")
        throw WorkflowError.InvalidConfig("$key has \"$unknown\", which is not a state of the ${plugin.kind} tracker. Valid states: $valid")
    }

    companion object {
        /**
         * Best effort, with no validation: the `provider` map of [tracker], the `tracker:` value, then of each entry of
         * [trackers], the `trackers:` value, as written, so their secrets can be learned before a load that may fail
         * (m1-11). A value that is not a map gives an empty map or is skipped.
         */
        fun providersOf(
            tracker: Any?,
            trackers: Any?,
        ): List<Map<String, ConfigValue>> {
            val primary = providerOf(tracker)
            val extras = (trackers as? List<*>).orEmpty().map(::providerOf).filter { it.isNotEmpty() }
            return listOf(primary) + extras
        }

        private fun providerOf(tracker: Any?): Map<String, ConfigValue> =
            tracker
                .asMap()
                ?.get("provider")
                .asMap()
                ?.mapValues { it.value.toConfigValue() }
                .orEmpty()

        private const val PRIMARY = "tracker"
        private const val FILES_TRACKER = "files"

        /** The spec 11.4 categories of a `provider` that does not follow its tracker's profile (m3-05). */
        private const val INVALID_TRACKER_CONFIG = "invalid_tracker_config"
        private const val MISSING_TRACKER_SECRET = "missing_tracker_secret"

        /** The keys of an entry of `trackers:` (PLAN 6.1); any other is warned about once and ignored. */
        private val ENTRY_KEYS = setOf("id", "kind", "provider", "active_states", "terminal_states", "required_labels")
        private val DEFAULT_ACTIVE_STATES = listOf("todo", "in_progress")
        private val DEFAULT_TERMINAL_STATES = listOf("done", "canceled")

        /**
         * A message a tracker plugin or the tracker check wrote about `tracker:`, here about the tracker [name]: an
         * extra tracker's message names `trackers[<index>]` in place of `tracker`, so the preflight names the entry.
         */
        private fun rename(
            name: String,
            message: String,
        ): String =
            when {
                name == PRIMARY -> message
                message.startsWith("$PRIMARY.") -> name + message.removePrefix(PRIMARY)
                else -> "$name: $message"
            }

        private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }

        private fun Any?.toConfigValue(): ConfigValue =
            when (this) {
                null -> ConfigValue.Null
                is List<*> -> ConfigValue.ListValue(map { it.toConfigValue() })
                is Map<*, *> -> ConfigValue.MapValue(entries.associate { (key, value) -> key.toString() to value.toConfigValue() })
                else -> ConfigValue.Str(toString())
            }

        private fun Map<String, Any?>?.child(
            key: String,
            name: String,
        ): Map<String, Any?>? =
            when (val value = this?.get(key)) {
                null -> null
                is Map<*, *> -> value.asMap()
                else -> throw WorkflowError.InvalidConfig("$name must be a map")
            }

        private fun Map<String, Any?>?.string(
            name: String,
            key: String,
        ): String? =
            when (val value = this?.get(key)) {
                null -> null
                is String -> value
                else -> throw WorkflowError.InvalidConfig("$name must be a string")
            }

        private fun Map<String, Any?>?.strings(
            name: String,
            key: String,
        ): List<String>? =
            when (val value = this?.get(key)) {
                null -> null
                is List<*> -> value.map { it as? String ?: throw WorkflowError.InvalidConfig("$name must be a list of strings") }
                else -> throw WorkflowError.InvalidConfig("$name must be a list of strings")
            }
    }
}
