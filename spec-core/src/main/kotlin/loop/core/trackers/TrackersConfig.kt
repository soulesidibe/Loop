package loop.core.trackers

import loop.core.workflow.ConfigValue
import java.nio.file.Path

/**
 * One tracker of a project (m4-11, PLAN 6.1): the spec's `tracker:`, the primary tracker, or an entry of `trackers:`,
 * as its tracker plugin read it.
 */
data class TrackerEntry(
    /** The tracker id: `tracker.id` or [TrackersConfig.DEFAULT_ID] for the primary tracker, `id` for an extra one. */
    val id: String,
    /** The `kind` that selected its tracker plugin, for example `files` or `github`. */
    val kind: String,
    /** The resolved `provider.dir`, `.loop/issues` beside `WORKFLOW.md` when unset; only the `files` tracker reads it. */
    val dir: Path,
    /** What the tracker plugin read from `provider`, null when it needs none or when its secret was deferred (bl-70). */
    val settings: Any?,
    val activeStates: List<String>,
    val terminalStates: List<String>,
    /** Its own `required_labels`, empty when unset: an extra tracker never takes the primary's. */
    val requiredLabels: List<String>,
    /** The `provider` map as written, whose `$NAME` values Loop redacts as secrets. */
    val provider: Map<String, ConfigValue> = emptyMap(),
)

/**
 * The trackers of a workflow (m4-11): the [primary] one of `tracker:` and the [extras] of `trackers:`, in the order
 * written. Their ids are unique and match [ID_PATTERN], which the loader checked. Nothing polls the extras before m4-13.
 */
data class TrackersConfig(
    val primary: TrackerEntry,
    val extras: List<TrackerEntry> = emptyList(),
) {
    /** The primary tracker, then the extras. */
    val all: List<TrackerEntry> get() = listOf(primary) + extras

    val ids: List<String> get() = all.map { it.id }

    /** The tracker of [id], or null when no tracker has it. */
    fun entry(id: String): TrackerEntry? = all.firstOrNull { it.id == id }

    /**
     * The one line `loop run` logs at startup when `trackers:` lists extra trackers, without a final period, so a
     * workflow that sets them before m4-13 is not silently half read; null when it lists none.
     */
    fun notPolledNotice(): String? {
        if (extras.isEmpty()) return null
        val ids = extras.map { it.id }
        val named = if (ids.size == 1) ids.single() else ids.dropLast(1).joinToString(", ") + " and " + ids.last()
        return "trackers lists $named, which Loop does not poll yet (m4-13), it polls only the primary tracker ${primary.id}"
    }

    companion object {
        /** The id of a primary tracker whose `tracker:` sets no `id`. */
        const val DEFAULT_ID = "default"

        /** What a tracker id must match. */
        val ID_PATTERN = Regex("[a-z][a-z0-9-]*")
    }
}
