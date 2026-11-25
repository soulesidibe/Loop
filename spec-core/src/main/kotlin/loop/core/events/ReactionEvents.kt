package loop.core.events

/** The keys the reaction events share (m4-34). */
object ReactionKeys {
    /** The reaction, a key of `reactions:` such as `merge_conflict`. */
    const val REACTION = "reaction"

    /** Its action, `send_to_agent` or `notify_human`. */
    const val ACTION = "action"

    /** The identifier of the waiting issue. */
    const val ISSUE = "issue"

    /** The number of the waiting pull request. */
    const val PULL_REQUEST = "pull_request"

    /** Why the reaction did nothing. */
    const val REASON = "reason"
}

/**
 * m4-34: a reaction of `reactions:` acted on a waiting pull request. For `merge_conflict` with `send_to_agent` the wait
 * ended and the issue goes back to its agent with the conflict findings, on a `max_attempts` slot.
 */
object ReactionTriggered : KeyedEvent(
    "reaction_triggered",
    ReactionKeys.REACTION,
    ReactionKeys.ACTION,
    ReactionKeys.ISSUE,
    ReactionKeys.PULL_REQUEST,
) {
    override val sample =
        mapOf(
            ReactionKeys.REACTION to "merge_conflict",
            ReactionKeys.ACTION to "send_to_agent",
            ReactionKeys.ISSUE to "m4-34",
            ReactionKeys.PULL_REQUEST to "7",
        )

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "pull request #${data[ReactionKeys.PULL_REQUEST]} triggered the ${data[ReactionKeys.REACTION]} reaction, " +
        "${data[ReactionKeys.ACTION]}, the issue goes back to its agent"
}

/**
 * m4-34: a reaction found what it watches for but did nothing, with [ReactionKeys.REASON]: `notify_human`, since M4 sends
 * no notice for a reaction. Recorded once per wait.
 */
object ReactionSkipped : KeyedEvent(
    "reaction_skipped",
    ReactionKeys.REACTION,
    ReactionKeys.ACTION,
    ReactionKeys.ISSUE,
    ReactionKeys.PULL_REQUEST,
    ReactionKeys.REASON,
) {
    /** The action is `notify_human`, which sends nothing for a reaction in M4. */
    const val NOTIFY_HUMAN = "notify_human"

    override val sample =
        mapOf(
            ReactionKeys.REACTION to "merge_conflict",
            ReactionKeys.ACTION to "notify_human",
            ReactionKeys.ISSUE to "m4-34",
            ReactionKeys.PULL_REQUEST to "7",
            ReactionKeys.REASON to NOTIFY_HUMAN,
        )

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "pull request #${data[ReactionKeys.PULL_REQUEST]} triggered the ${data[ReactionKeys.REACTION]} reaction, " +
        "Loop did nothing (${data[ReactionKeys.REASON]})"
}
