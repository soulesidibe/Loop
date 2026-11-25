package loop.core.events

import loop.api.HumanTouch
import loop.api.RecordedEvent

/** The fields the tracker change events share. */
object Tracker {
    const val FROM = "from"
    const val TO = "to"
    const val BY = "by"
    const val CHARS = "chars"
    const val REASON = "reason"

    /** m2-03: Loop applied a submitted result once its checks passed. */
    const val VERIFIED = "verified"

    /** m2-24: and once a human approved it. */
    const val APPROVED = "approved"
    const val APPROVED_BY = "approved_by"

    /** m2-26: and once its pull request merged. */
    const val MERGED = "merged"

    /** The [BY] of a change Loop applied host side for a queued request (m1-23). */
    const val BY_HOST = "host"

    /** The `source` of a result submitted with the `submit_result` MCP tool (m2-16). */
    const val MCP = "mcp"

    /** Who wrote a tracker change: `by host` when Loop applied a queued request (m1-23), else `by turn=<id>`. */
    internal fun traceBy(
        event: RecordedEvent,
        context: TraceContext,
    ): String? = if (event.data[BY] == BY_HOST) "by host" else event.turnId?.let { "by turn=${context.id(it.value)}" }

    /** The words after a move: who or what made it. */
    internal fun humanBy(data: Map<String, String>): String =
        when {
            // m2-24: a verified result delivered once a human approved it.
            data[APPROVED] == "true" ->
                data[APPROVED_BY]?.let { " after its checks passed, approved by $it" }
                    ?: " after its checks passed and its approval"
            data[VERIFIED] == "true" -> " after its checks passed"
            data[BY] == BY_HOST -> " by the agent's request"
            else -> ""
        }
}

/** The details of a move line, applied or requested. */
private fun TraceDetails.move(event: RecordedEvent) {
    word("${Tracker.TO}=${TraceText.quote(event.data[Tracker.TO] ?: "")}")
    word(Tracker.traceBy(event, context))
    if (event.data[Tracker.VERIFIED] == "true") word("after checks")
    if (event.data[Tracker.APPROVED] == "true") word("after approval")
}

/** The details of a comment line, applied or requested. */
private fun TraceDetails.comment(event: RecordedEvent) {
    word("${Tracker.CHARS}=${event.data[Tracker.CHARS] ?: "?"}")
    word(Tracker.traceBy(event, context))
    // m2-11: a comment Loop wrote itself, outside any attempt, says why.
    add(Tracker.REASON, event.data[Tracker.REASON])
}

/** A move of the issue in its tracker. */
object IssueMoved : EventDefinition("issue_moved") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Tracker.FROM to "todo", Tracker.TO to "review", Tracker.BY to Tracker.BY_HOST, Tracker.VERIFIED to "true")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { move(event) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "moved to ${data[Tracker.TO]}${Tracker.humanBy(data)}"
}

/** A comment on the issue in its tracker, by the agent's request or by Loop itself (m2-11). */
object IssueCommented : EventDefinition("issue_commented") {
    const val NOT_READY = "not_ready"
    const val EVIDENCE_SUMMARY = "evidence_summary"
    const val PULL_REQUEST = "pull_request"
    const val MERGE_CHECK = "merge_check"
    const val AUTO_MERGE_FALLBACK = "auto_merge_fallback"
    const val CI = "ci"

    /** m4-22: the comment of the attach delivery, the deliverable files of a verified result. */
    const val DELIVERABLE = "deliverable"

    /** m3-12: the comment of a `split_issue` park. */
    const val SPLIT_ISSUE = IssueParked.SPLIT_ISSUE

    /** m4-31: the comment of an agent's `ask`, its question for a human. */
    const val QUESTION = "question"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Tracker.CHARS to "42", Tracker.BY to Tracker.BY_HOST)

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { comment(event) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = when (data[Tracker.REASON]) {
        NOT_READY -> "commented that it is not ready"
        EVIDENCE_SUMMARY -> "commented the evidence summary of its verified result"
        PULL_REQUEST -> "commented the URL of its pull request"
        MERGE_CHECK -> "commented the outcome of its merge check"
        AUTO_MERGE_FALLBACK -> "commented why it did not merge on its own"
        CI -> "commented why CI did not let it merge"
        DELIVERABLE -> "commented the deliverable files of its verified result"
        SPLIT_ISSUE -> "commented that it should be split"
        QUESTION -> "commented the agent's question for a human"
        else -> "commented${Tracker.humanBy(data)}"
    }
}

/** A label Loop added to the issue, so far `needs-info` for an issue that is not ready. */
object IssueLabeled : EventDefinition("issue_labeled") {
    const val ADD = "add"

    override val trace = TraceShow.ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(ADD to "needs-info", Tracker.REASON to IssueCommented.NOT_READY)

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "labeled ${data[ADD]}"
}

/** A move the agent queued with the tracker tools, applied when its turn ends. */
object IssueMoveRequested : EventDefinition("issue_move_requested") {
    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(Tracker.TO to "review")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { move(event) }
}

/** A comment the agent queued with the tracker tools, applied when its turn ends. */
object IssueCommentRequested : EventDefinition("issue_comment_requested") {
    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(Tracker.CHARS to "42")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { comment(event) }
}

/**
 * m4-31: Loop posted an agent's `ask` on its issue, a question of [QUESTION_KIND] with [OPTIONS] options in a comment of
 * [Tracker.CHARS] characters, and the issue waits as `Waiting(ask)`. Never the text: a question can hold a secret.
 */
object QuestionAsked : KeyedEvent("question_asked", QuestionAsked.QUESTION_KIND, QuestionAsked.OPTIONS, Tracker.CHARS) {
    const val QUESTION_KIND = "question_kind"
    const val OPTIONS = "options"

    override val sample = mapOf(QUESTION_KIND to "product", OPTIONS to "2", Tracker.CHARS to "120")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "asked a human a question (${data[QUESTION_KIND]}) on the issue and waits for the answer"
}

/** The fields of a queued agent request Loop could not apply or dropped. */
abstract class ToolRequestEvent(
    name: String,
) : EventDefinition(name) {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(REQUEST, event.data[REQUEST])
        add(Tracker.TO, event.data[Tracker.TO])
        add(Tracker.CHARS, event.data[Tracker.CHARS])
        add(Tracker.REASON, event.data[Tracker.REASON])
    }

    /** The request as the human log names it. */
    protected fun request(data: Map<String, String>): String =
        when (data[REQUEST]) {
            MOVE -> "the agent's request to move to ${data[Tracker.TO]}"
            COMMENT -> "the agent's comment"
            // m2-16: a submitted result that never got to a move has no target.
            RESULT if data[SOURCE] == Tracker.MCP || data[Tracker.TO] == null ->
                "the agent's submit_result" + data[Tracker.TO]?.let { ", a move to $it" }.orEmpty()
            RESULT -> "the agent's result file, a move to ${data[Tracker.TO]}"
            else -> "an agent request"
        }

    companion object {
        const val REQUEST = "request"
        const val SOURCE = "source"
        const val MOVE = "move"
        const val COMMENT = "comment"
        const val RESULT = "result"

        /** The request of a queued file that could not be read. */
        const val UNKNOWN = "unknown"
    }
}

/** A queued agent request Loop could not apply. */
object ToolRequestFailed : ToolRequestEvent("tool_request_failed") {
    override val sample = mapOf(REQUEST to MOVE, Tracker.TO to "done", Tracker.REASON to "not a handoff state")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "could not apply ${request(data)}: ${data[Tracker.REASON]}"
}

/** A queued agent request dropped because its turn did not complete. */
object ToolRequestDropped : ToolRequestEvent("tool_request_dropped") {
    override val sample = mapOf(REQUEST to COMMENT, Tracker.CHARS to "42")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "dropped ${request(data)}, its turn did not complete"
}

/** A moment a human had to act on an issue (m2-19); the store counts it by [HumanTouch.EVENT], which this points to. */
object HumanTouched : EventDefinition(HumanTouch.EVENT) {
    const val TOUCH = HumanTouch.TOUCH
    const val FROM_STATE = "from_state"
    const val TO_STATE = "to_state"

    override val sample = mapOf(TOUCH to HumanTouch.MOVE, TO_STATE to "todo")
}
