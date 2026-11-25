package loop.core.kinds

import loop.api.Issue
import loop.core.workflow.KindMatch
import loop.core.workflow.KindsConfig

/**
 * The kind of an issue (m4-18, plan 6.2): the first kind of [KindsConfig.named], in the order written, whose match
 * holds, else the default kind. A pure function of the config, the issue and its tracker id.
 */
object KindMatcher {
    /** The name of the kind of [issue], read from the tracker of id [trackerId]. */
    fun kindOf(
        kinds: KindsConfig,
        issue: Issue,
        trackerId: String,
    ): String =
        kinds.named.entries
            .firstOrNull { (_, kind) -> !kind.match.default && holds(kind.match, issue, trackerId) }
            ?.key
            ?: kinds.named.entries
                .firstOrNull { it.value.match.default }
                ?.key
            ?: KindsConfig.CODE

    /** True when every key [match] sets holds for [issue]; a match that sets none never holds. */
    private fun holds(
        match: KindMatch,
        issue: Issue,
        trackerId: String,
    ): Boolean {
        val keys = listOfNotNull(match.label, match.tracker, match.state)
        if (keys.isEmpty()) return false
        val label = match.label?.let { wanted -> issue.labels.any { it.trim() == wanted.trim().lowercase() } } ?: true
        val tracker = match.tracker?.let { it == trackerId } ?: true
        val state = match.state?.let { it.trim().lowercase() == issue.state.trim().lowercase() } ?: true
        return label && tracker && state
    }
}
