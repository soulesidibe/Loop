package loop.api

import kotlin.time.Instant

/**
 * A channel through which Loop tells a human that a run needs them: the terminal in core (m4-30), later Telegram,
 * Slack or HTTP as plugins. See PLAN section 12, Plugins. [HumanChannelContract] is its contract. Like [EventSink],
 * implementations return a failed [Result] instead of throwing.
 */
public interface HumanChannel {
    /** Tells the human about [notice], as one message. */
    public fun notify(notice: HumanNotice): Result<Unit>

    public companion object {
        /** Tells nobody: every notice succeeds and goes nowhere. */
        public val NONE: HumanChannel =
            object : HumanChannel {
                override fun notify(notice: HumanNotice): Result<Unit> = Result.success(Unit)
            }
    }
}

/**
 * One notice for a human (m4-30): the issue, by its core [issueId] and its [identifier] as Loop shows it, which is the
 * form Loop's commands take, the [event], why ([reason]), the [url] of the pull request when there is one, and when it
 * happened ([at]).
 */
public data class HumanNotice(
    public val issueId: String,
    public val identifier: String,
    public val event: HumanEvent,
    public val reason: String,
    public val url: String? = null,
    public val at: Instant,
) {
    init {
        require(issueId.isNotBlank()) { "A human notice needs an issue id" }
        require(identifier.isNotBlank()) { "A human notice needs an identifier" }
    }

    /** The command the human types next, such as `loop approve m4-30`, null for an event this Loop does not know. */
    public val nextCommand: String? get() = event.command?.let { "$it $identifier" }
}

/** What a [HumanNotice] is about, as the wire string recorded and shown. */
@JvmInline
public value class HumanEvent(
    public val wire: String,
) {
    /** The command, without its issue, that answers this event, null for an event this Loop does not know. */
    public val command: String?
        get() =
            when (this) {
                Parked -> "loop resume"
                ApprovalWaiting, DeliveryFailed -> "loop approve"
                Question -> "loop answer"
                else -> null
            }

    public companion object {
        /** The issue was parked: nothing dispatches it until `loop resume` or a change in its tracker. */
        public val Parked: HumanEvent = HumanEvent("parked")

        /** A verified result waits for `loop approve`, fresh or after an auto merge fell back to the human policy. */
        public val ApprovalWaiting: HumanEvent = HumanEvent("approval_waiting")

        /** A delivery was refused for good (`delivery_failed`), and the result waits for a new `loop approve`. */
        public val DeliveryFailed: HumanEvent = HumanEvent("delivery_failed")

        /** An agent asked the human a question with `ask` (m4-31), answered with `loop answer` (m4-52). */
        public val Question: HumanEvent = HumanEvent("question")

        /** Every event this Loop knows, in this order. */
        public val KNOWN: List<HumanEvent> = listOf(Parked, ApprovalWaiting, DeliveryFailed, Question)
    }
}
