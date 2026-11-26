package loop.core.events

import loop.core.events.HumanText.SHORT_SHA
import loop.core.reactions.ReactionsConfig

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

    /** m4-35: this reaction's count for the issue, from 1 (`ci_failed`); on a skip, the count already used. */
    const val RETRY = "retry"

    /** m4-35: the `retries` of the reaction, absent when not written. */
    const val RETRIES = "retries"

    /**
     * m4-36: the ids of the reviews a `changes_requested` reaction sent, comma separated, oldest first; on a skip, the
     * newest review of the round, which names it.
     */
    const val REVIEW = "review"

    /** m4-36: the commit the pull request's head was at when the `changes_requested` reaction read its reviews. */
    const val HEAD = "head"

    /** m4-36: what the provider said when the reviews could not be read, redacted and cut. */
    const val DETAIL = "detail"

    /**
     * m4-37: which green the `approved_green` reaction found, [GREEN_ACTIONS], the Actions runs of the head under
     * `ci: actions`, or [GREEN_MERGE_CHECK], the merge check passed on the head under `ci: none`.
     */
    const val GREEN = "green"

    /** m4-37: green by the Actions runs of the head. */
    const val GREEN_ACTIONS = "actions"

    /** m4-37: green by the merge check passed on the head. */
    const val GREEN_MERGE_CHECK = "merge_check"

    /**
     * bl-224: the findings a reaction sent its issue back with, redacted, only when its retry was scheduled. A new process
     * reads them back for the retry, and they mark the issue as sent back by a reaction. Never shown by `loop trace`.
     */
    const val FINDINGS = "findings"
}

/**
 * m4-34: a reaction of `reactions:` acted on a waiting pull request. For `merge_conflict` with `send_to_agent` the wait
 * ended and the issue goes back to its agent with the conflict findings, on a `max_attempts` slot. Since m4-35
 * `ci_failed` does the same after a red verdict of the CI wait, with [ReactionKeys.RETRY] and [ReactionKeys.RETRIES].
 * Since m4-36 `changes_requested` does it for the change requests on the pull request's head, with [ReactionKeys.REVIEW]
 * and [ReactionKeys.HEAD]. Since m4-37 `approved_green` records it with `notify_human` when it told the human that the
 * pull request is approved and green on [ReactionKeys.HEAD], with [ReactionKeys.GREEN]; the wait goes on.
 */
object ReactionTriggered : KeyedEvent(
    "reaction_triggered",
    ReactionKeys.REACTION,
    ReactionKeys.ACTION,
    ReactionKeys.ISSUE,
    ReactionKeys.PULL_REQUEST,
    ReactionKeys.RETRY,
    ReactionKeys.RETRIES,
    ReactionKeys.REVIEW,
    ReactionKeys.HEAD,
    ReactionKeys.GREEN,
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
    ) = if (data[ReactionKeys.REACTION] == ReactionsConfig.APPROVED_GREEN) {
        "pull request #${data[ReactionKeys.PULL_REQUEST]} is approved and green (${data[ReactionKeys.GREEN]}) on " +
            "${data[ReactionKeys.HEAD]?.take(SHORT_SHA)}, Loop told the human"
    } else {
        "pull request #${data[ReactionKeys.PULL_REQUEST]} triggered the ${data[ReactionKeys.REACTION]} reaction, " +
            "${data[ReactionKeys.ACTION]}, the issue goes back to its agent"
    }
}

/**
 * m4-34: a reaction found what it watches for but did nothing, with [ReactionKeys.REASON]: `notify_human`, since M4 sends
 * no notice for a reaction, recorded once per wait. Since m4-35 also `retries_spent`, a `ci_failed` reaction past its
 * `retries`. Since m4-36 also `escalated`, a `changes_requested` round still standing past `escalate_after`, told to the
 * human, and `reviews_unread`, the reviews read refused for good, with [ReactionKeys.DETAIL]. Since bl-224 also
 * `human_decided`, a `merge_conflict` or `changes_requested` reaction that found its wait approved or sent back.
 */
object ReactionSkipped : KeyedEvent(
    "reaction_skipped",
    ReactionKeys.REACTION,
    ReactionKeys.ACTION,
    ReactionKeys.ISSUE,
    ReactionKeys.PULL_REQUEST,
    ReactionKeys.RETRY,
    ReactionKeys.RETRIES,
    ReactionKeys.REVIEW,
    ReactionKeys.HEAD,
    ReactionKeys.REASON,
    ReactionKeys.DETAIL,
) {
    /** The action is `notify_human`, which sends nothing for a reaction in M4. */
    const val NOTIFY_HUMAN = "notify_human"

    /** m4-35: the issue was sent back `retries` times for this reaction already. */
    const val RETRIES_SPENT = "retries_spent"

    /** m4-36: changes are still requested `escalate_after` after the round's reaction, so Loop told the human. */
    const val ESCALATED = "escalated"

    /** m4-36: the reviews read was refused for good, a 403 or a 404 naming "Pull requests: read" among them. */
    const val REVIEWS_UNREAD = "reviews_unread"

    /**
     * bl-224: a `loop approve` or a `loop send-back` decided the wait while the reaction read GitHub, so the reaction
     * left the wait to the human's decision and sent nothing.
     */
    const val HUMAN_DECIDED = "human_decided"

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
