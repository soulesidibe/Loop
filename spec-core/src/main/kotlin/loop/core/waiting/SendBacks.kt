package loop.core.waiting

/**
 * m4-32: what `loop send-back` writes for the reason a human sends a waiting delivery back, what it refuses, and what
 * the attempt after it is shown. The reason is kept in the approval wait ([Wait.Approval.sentBackReason]); a sent back
 * wait is never delivered and no longer holds its issue, which dispatches again in its kept workspace with no
 * `max_attempts` slot, and the attempt that renders the reason as its verify findings ends the wait.
 */
object SendBacks {
    /** What a refusal names, each with its own exit code in `loop send-back`. */
    enum class Kind {
        /** The issue waits for nothing a send back ends: no wait, a verify wait, or one already sent back. */
        NotWaiting,

        /** The issue waits for its merge, `Waiting(merge)`. */
        Merge,

        /** The issue waits for an answer, `Waiting(ask)`, which `loop answer` ends. */
        Ask,
    }

    /** Why a wait cannot be sent back: its [kind] and the [message] that follows the issue's name. */
    data class Refusal(
        val kind: Kind,
        val message: String,
    )

    /** The refusal of an issue that does not wait at all. */
    const val NOT_WAITING: String = "is not waiting for approval, loop status shows where it stands"

    /** Why [wait] cannot be sent back, null when it can: only an approval wait not sent back yet, approved or not. */
    fun refusal(wait: Wait): Refusal? =
        when {
            wait is Wait.Approval && wait.sentBack ->
                Refusal(Kind.NotWaiting, "is already sent back, Loop dispatches it with the first reason")
            wait is Wait.Approval -> null
            wait is Wait.Merge ->
                Refusal(
                    Kind.Merge,
                    "is waiting for its merge, not for approval, loop status shows its phase, " +
                        "and a refused merge waits for approval again, which loop send-back ends",
                )
            wait is Wait.Ask -> Refusal(Kind.Ask, "is waiting for an answer, not for approval, answer it with loop answer")
            else ->
                Refusal(
                    Kind.NotWaiting,
                    "is waiting for ${wait.reason.wire}, not for approval, loop status shows where it stands",
                )
        }

    /** The comment `loop send-back` posts on the issue: [reason], trimmed, given by [by]. Not redacted here. */
    fun comment(
        by: String,
        reason: String,
    ): String = "Sent back by $by: ${reason.trim()}"

    /** `loop.verify_findings` of the attempt after the send back: the reason as [comment] words it, null before one. */
    fun findings(wait: Wait.Approval): String? {
        val reason = wait.sentBackReason?.takeIf { wait.sentBack } ?: return null
        return comment(wait.sentBackBy.orEmpty().ifBlank { UNKNOWN_HUMAN }, reason)
    }

    /** Who sent it back when the wait does not say. */
    private const val UNKNOWN_HUMAN = "a human"
}
