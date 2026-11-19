package loop.core.events

import loop.api.RecordedEvent

/** The fields the result events share. */
object Results {
    const val TO = "to"
    const val STATUS = "status"
    const val SOURCE = "source"
    const val REASON = "reason"

    /** The number of acceptance criteria the result reports on. */
    const val ACCEPTANCE = "acceptance"

    /** The saved result file, beside the evidence. */
    const val RESULT = "result"

    /** m2-04: the checks of a result run again after a restart. */
    const val RESTORED = "restored"

    /** The [SOURCE] of a result file (m2-15). */
    const val FILE = "file"

    /** The [SOURCE] of a result submitted with the `submit_result` MCP tool (m2-16). */
    const val MCP = Tracker.MCP
}

/** A result the agent submitted: a move, or from m2-15 a result file, and from m2-16 the `submit_result` MCP tool. */
object ResultSubmitted : EventDefinition("result_submitted") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Results.TO to "review", Results.STATUS to "done", Results.SOURCE to Results.MCP)

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        // m2-15: a needs_info, blocked or failed result file moves nowhere.
        if (data[Results.TO] != null || data[Results.STATUS] == null) word("${Results.TO}=${TraceText.quote(data[Results.TO] ?: "")}")
        if (data[Results.RESTORED] == "true") word(Results.RESTORED)
        add(Results.STATUS, data[Results.STATUS])
        // Where the result came from; null for a move.
        when (data[Results.SOURCE]) {
            Results.FILE -> word("from .loop/result.json")
            Results.MCP -> word("from submit_result")
        }
        add(Results.REASON, data[Results.REASON])
        add(Results.RESULT, data[Results.RESULT])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        // m2-04: the checks a dead process left running start again.
        if (data[Results.RESTORED] == "true") return "checks its submitted result again after a restart, a move to ${data[Results.TO]}"
        val file =
            when (data[Results.SOURCE]) {
                Results.FILE -> " in .loop/result.json"
                Results.MCP -> " with submit_result"
                else -> ""
            }
        return when (val status = data[Results.STATUS]) {
            null, "done" -> "submitted its result$file, a move to ${data[Results.TO]}"
            "needs_info" -> "submitted a needs_info result$file, it asks a question"
            else -> "submitted a $status result$file" + data[Results.REASON]?.let { ": $it" }.orEmpty()
        }
    }
}

/** m2-16: an invalid `submit_result` call or result file; a result file's line names no source, as in m2-15. */
object ResultInvalid : EventDefinition("result_invalid") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Results.REASON to "status is missing", Results.SOURCE to Results.MCP)

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        if (event.data[Results.SOURCE] == Results.MCP) word("from submit_result")
        add(Results.REASON, event.data[Results.REASON])
        add(Results.RESULT, event.data[Results.RESULT])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val what = if (data[Results.SOURCE] == Results.MCP) "its submit_result call" else "its result file"
        return "$what is invalid: ${data[Results.REASON]}"
    }
}

/** m2-15: a result file a failed turn left, removed at the next attempt start. */
object ResultDiscarded : EventDefinition("result_discarded") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Results.REASON to "left by an earlier turn")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(Results.REASON, event.data[Results.REASON]) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "removed a result file left by an earlier turn, never submitted"
}

/** m2-16: a result the `submit_result` MCP tool queued. */
object ResultRequested : EventDefinition("result_requested") {
    override val sample = mapOf(Results.STATUS to "done")
}

/** A check's evidence or a result file could not be saved. */
object EvidenceRecordFailed : EventDefinition("evidence_record_failed") {
    const val CHECK = "check"

    override val sample = mapOf(CHECK to "0", Results.REASON to "disk full")
}
