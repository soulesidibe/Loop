package loop.core.reactions

/**
 * The `reactions:` key of `WORKFLOW.md` (m4-34, PLAN 6.7): what Loop does when something happens to a pull request it
 * opened while the issue waits. Each key is null when it is absent, and absent means no reaction. [mergeConflict],
 * since m4-35 [ciFailed] and since m4-36 [changesRequested] act; [approvedGreen] parses and does nothing until m4-37.
 */
data class ReactionsConfig(
    val ciFailed: Reaction? = null,
    val changesRequested: Reaction? = null,
    val mergeConflict: Reaction? = null,
    val approvedGreen: Reaction? = null,
) {
    /** True when no reaction is configured, as with no `reactions:` key. */
    val none: Boolean get() = ciFailed == null && changesRequested == null && mergeConflict == null && approvedGreen == null

    companion object {
        const val CI_FAILED: String = "ci_failed"
        const val CHANGES_REQUESTED: String = "changes_requested"
        const val MERGE_CONFLICT: String = "merge_conflict"
        const val APPROVED_GREEN: String = "approved_green"

        /** Every reaction Loop knows, in the order of PLAN 6.7. */
        val KEYS: List<String> = listOf(CI_FAILED, CHANGES_REQUESTED, MERGE_CONFLICT, APPROVED_GREEN)

        /**
         * The author associations whose reviews `changes_requested` reads when `trusted_associations` is not written
         * (m4-36), the default of the github tracker's `trusted_associations` (bl-157).
         */
        val DEFAULT_TRUSTED_ASSOCIATIONS: List<String> = listOf("OWNER", "MEMBER", "COLLABORATOR")

        /** Every `author_association` GitHub gives a review or a comment. */
        val AUTHOR_ASSOCIATIONS: List<String> =
            listOf("OWNER", "MEMBER", "COLLABORATOR", "CONTRIBUTOR", "FIRST_TIME_CONTRIBUTOR", "FIRST_TIMER", "MANNEQUIN", "NONE")
    }
}

/** One reaction: its [action], and the options its key takes, null when not written. */
data class Reaction(
    val action: ReactionAction,
    /** How many times `send_to_agent` may send the issue back for this reaction (`ci_failed`, `changes_requested`). */
    val retries: Int? = null,
    /** How long the reaction waits before it escalates to a human, in milliseconds (`ci_failed`, `changes_requested`). */
    val escalateAfterMs: Long? = null,
    /**
     * The author associations whose reviews and review comments count (`changes_requested`, m4-36), upper case, each
     * once; null when not written, which means [ReactionsConfig.DEFAULT_TRUSTED_ASSOCIATIONS].
     */
    val trustedAssociations: List<String>? = null,
)

/** What a reaction does. */
enum class ReactionAction(
    val wire: String,
) {
    /** The wait ends and the issue's workspace resumes with a continuation turn, then the result is verified again. */
    SendToAgent("send_to_agent"),

    /** A human is told. In M4 nothing is sent for a reaction: Loop records `reaction_skipped`. */
    NotifyHuman("notify_human"),
}
