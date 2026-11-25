package loop.api

import java.nio.file.Path

/**
 * A tracker plugin (m3-05, plan 12): `tracker.kind` selects the provider whose [kind] matches. Providers are found
 * like runner providers, with Java's `ServiceLoader`, on the classpath and in each JAR of `<LOOP_HOME>/plugins/`, so
 * a module registers its tracker with a `META-INF/services/loop.api.TrackerProvider` resource naming its provider
 * class, which needs a public no argument constructor. Two providers of one kind fail startup.
 */
public interface TrackerProvider {
    /** The `tracker.kind` this provider answers to, for example `github`. */
    public val kind: String

    /** The keys of `tracker.provider`, checked on every load of `WORKFLOW.md` before [parse] reads them. */
    public val profile: PluginProfile

    /** The `tracker.active_states` a `WORKFLOW.md` gets when it sets none, the profile defaults of spec 5.3.1. */
    public val defaultActiveStates: List<String>

    /** The `tracker.terminal_states` a `WORKFLOW.md` gets when it sets none. */
    public val defaultTerminalStates: List<String>

    /** Every state the tracker defines, known without building it: the preflight checks the configured states against them. */
    public val definedStates: List<String>

    /**
     * Reads `tracker.provider` as written (string, list, map or null values, an empty map when absent), resolving
     * its own `$NAME` values from [env], into the settings [create] gets, null when the tracker needs none. Throws
     * [TrackerConfigException] with a spec 11.4 category for a setting it cannot use; Loop reports any other
     * failure as `invalid_config`. A message names keys and variables, never a secret value.
     */
    public fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String>,
    ): Any?

    /** Builds the tracker from the [settings] [parse] read and what the host gives every tracker. */
    public fun create(
        settings: Any?,
        context: TrackerContext,
    ): TrackerAdapter
}

/** A `tracker.provider` setting a tracker cannot use, with its spec 11.4 [category], such as `invalid_tracker_config`. */
public class TrackerConfigException(
    public val category: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * What the host gives every tracker it builds: [trackerDir], the resolved `tracker.provider.dir` (`.loop/issues`
 * beside `WORKFLOW.md` when unset), [lockDir], the machine level folder of locks, [onMalformed] and [onWarning] for
 * the operator log, and [requiredLabels], the effective workflow's `tracker.required_labels`, read at each move,
 * which a move to a handoff state removes (m2-23), and [activeStates], the tracker's `active_states` as the workflow
 * set them, whose first is the state of an issue the tracker creates (m4-33), empty when the host gives none.
 */
public class TrackerContext(
    public val trackerDir: Path,
    public val lockDir: Path,
    public val onMalformed: (TrackerError.Malformed) -> Unit = {},
    public val onWarning: (String) -> Unit = {},
    public val requiredLabels: () -> List<String> = { emptyList() },
    public val activeStates: List<String> = emptyList(),
)
