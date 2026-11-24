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
) {
    /** True when [state] is one of [activeStates], compared trimmed and lowercase like every state (spec 4.2). */
    fun isActive(state: String): Boolean = activeStates.any { it.normalized() == state.normalized() }

    /** True when [state] is one of [terminalStates]. */
    fun isTerminal(state: String): Boolean = terminalStates.any { it.normalized() == state.normalized() }

    /** True when [labels] hold every one of [requiredLabels] (m4-13: this tracker's own, never the primary's). */
    fun hasRequiredLabels(labels: List<String>): Boolean {
        val held = labels.map { it.normalized() }.toSet()
        return requiredLabels.map { it.normalized() }.all { it.isNotEmpty() && it in held }
    }

    private fun String.normalized(): String = trim().lowercase()
}

/**
 * The trackers of a workflow (m4-11): the [primary] one of `tracker:` and the [extras] of `trackers:`, in the order
 * written. Their ids are unique and match [ID_PATTERN], which the loader checked. Each is polled on its own (m4-13).
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
     * The tracker of the core id [issueId] (m4-13), whose states and `required_labels` judge the issue: the tracker of
     * its namespace, a bare id being the primary's (m4-12), or null when no tracker of the workflow has it, as for an
     * issue whose tracker a reload removed. With no extra tracker every id is the primary's, whatever it holds.
     */
    fun trackerOf(issueId: String): TrackerEntry? {
        if (extras.isEmpty()) return primary
        return entry(NamespacedId.read(issueId, primary.id).tracker)
    }

    /** The tracker of [issueId] as [trackerOf] finds it, else the primary one, for a check that needs some states. */
    fun entryOf(issueId: String): TrackerEntry = trackerOf(issueId) ?: primary

    companion object {
        /** The id of a primary tracker whose `tracker:` sets no `id`. */
        const val DEFAULT_ID = "default"

        /** What a tracker id must match. */
        val ID_PATTERN = Regex("[a-z][a-z0-9-]*")
    }
}
