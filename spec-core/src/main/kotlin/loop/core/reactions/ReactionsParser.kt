package loop.core.reactions

import loop.core.workflow.WorkflowError

/**
 * Reads `reactions:` of a `WORKFLOW.md` (m4-34, PLAN 6.7) from its front matter value as written (strings, lists, maps
 * or null): a map of the four reactions of [ReactionsConfig.KEYS], each a map with an `action` and the options its key
 * takes. `ci_failed` and `changes_requested` take `retries` (an integer of 0 or more) and `escalate_after` (a duration
 * such as `4h`), and `changes_requested` takes `trusted_associations` (m4-36), a non empty list of GitHub author
 * associations; `merge_conflict` and `approved_green` take `action` only. An unknown reaction, an unknown action and a
 * bad option are each a [WorkflowError.InvalidConfig] naming the key, so the preflight refuses the workflow.
 */
internal object ReactionsParser {
    private const val KEY = "reactions"
    private const val ACTION = "action"
    private const val RETRIES = "retries"
    private const val ESCALATE_AFTER = "escalate_after"
    private const val TRUSTED_ASSOCIATIONS = "trusted_associations"

    /** The options each reaction takes beside `action`. */
    private val OPTIONS: Map<String, List<String>> =
        mapOf(
            ReactionsConfig.CI_FAILED to listOf(RETRIES, ESCALATE_AFTER),
            ReactionsConfig.CHANGES_REQUESTED to listOf(RETRIES, ESCALATE_AFTER, TRUSTED_ASSOCIATIONS),
            ReactionsConfig.MERGE_CONFLICT to emptyList(),
            ReactionsConfig.APPROVED_GREEN to emptyList(),
        )

    private val DURATION = Regex("""(\d+)([smhd])""")
    private val UNIT_MS = mapOf("s" to 1_000L, "m" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L)

    /** The reactions of the `reactions:` value [reactions], none when it is absent. */
    fun parse(reactions: Any?): ReactionsConfig {
        val map =
            when (reactions) {
                null -> return ReactionsConfig()
                else -> reactions.asMap() ?: throw WorkflowError.InvalidConfig("$KEY must be a map")
            }
        map.keys.firstOrNull { it !in ReactionsConfig.KEYS }?.let { unknown ->
            throw WorkflowError.InvalidConfig(
                "$KEY.$unknown is not a reaction Loop knows, use ${ReactionsConfig.KEYS.joinToString(", ")}",
            )
        }
        val parsed = map.mapValues { (key, value) -> reaction(key, value) }
        return ReactionsConfig(
            ciFailed = parsed[ReactionsConfig.CI_FAILED],
            changesRequested = parsed[ReactionsConfig.CHANGES_REQUESTED],
            mergeConflict = parsed[ReactionsConfig.MERGE_CONFLICT],
            approvedGreen = parsed[ReactionsConfig.APPROVED_GREEN],
        )
    }

    private fun reaction(
        key: String,
        value: Any?,
    ): Reaction {
        val name = "$KEY.$key"
        val map = value.asMap() ?: throw WorkflowError.InvalidConfig("$name must be a map")
        val options = OPTIONS.getValue(key)
        map.keys.firstOrNull { it != ACTION && it !in options }?.let { unknown ->
            throw WorkflowError.InvalidConfig(
                "$name.$unknown is not an option of $key, it takes ${(listOf(ACTION) + options).joinToString(", ")}",
            )
        }
        val written = (map[ACTION] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (written == null) {
            if (map[ACTION] != null) throw WorkflowError.InvalidConfig("$name.$ACTION must be a string")
            throw WorkflowError.InvalidConfig("$name.$ACTION is required")
        }
        val action =
            ReactionAction.entries.firstOrNull { it.wire == written }
                ?: throw WorkflowError.InvalidConfig(
                    "$name.$ACTION is \"$written\", which is not an action Loop knows, use " +
                        ReactionAction.entries.joinToString(", ") { it.wire },
                )
        return Reaction(
            action = action,
            retries = map[RETRIES]?.let { retries(name, it) },
            escalateAfterMs = map[ESCALATE_AFTER]?.let { duration(name, it) },
            trustedAssociations = map[TRUSTED_ASSOCIATIONS]?.let { associations(name, it) },
        )
    }

    /**
     * `trusted_associations` (m4-36), validated like the github tracker's (bl-157): a non empty list of GitHub author
     * associations, trimmed, compared case insensitively and kept upper case, each once. An unknown name is refused, as
     * a typo would otherwise trust nobody it was meant to.
     */
    private fun associations(
        name: String,
        value: Any,
    ): List<String> {
        val key = "$name.$TRUSTED_ASSOCIATIONS"
        val items = value as? List<*>
        if (items == null || items.any { it !is String }) {
            throw WorkflowError.InvalidConfig("$key must be a list of author associations")
        }
        if (items.isEmpty()) throw WorkflowError.InvalidConfig("$key must not be empty, list the author associations to trust")
        val known = ReactionsConfig.AUTHOR_ASSOCIATIONS
        return items
            .map { item ->
                val association = (item as String).trim().uppercase()
                if (association !in known) {
                    throw WorkflowError.InvalidConfig(
                        "$key has \"$item\", which is not a GitHub author association, use " +
                            known.dropLast(1).joinToString(", ") + " or " + known.last(),
                    )
                }
                association
            }.distinct()
    }

    private fun retries(
        name: String,
        value: Any,
    ): Int =
        (value as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw WorkflowError.InvalidConfig("$name.$RETRIES must be an integer of 0 or more")

    private fun duration(
        name: String,
        value: Any,
    ): Long {
        val match =
            (value as? String)?.trim()?.let { DURATION.matchEntire(it) }
                ?: throw WorkflowError.InvalidConfig("$name.$ESCALATE_AFTER must be a duration such as 30m, 4h or 2d")
        val (amount, unit) = match.destructured
        return amount
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { runCatching { Math.multiplyExact(it, UNIT_MS.getValue(unit)) }.getOrNull() }
            ?: throw WorkflowError.InvalidConfig("$name.$ESCALATE_AFTER must be a duration such as 30m, 4h or 2d")
    }

    private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
}
