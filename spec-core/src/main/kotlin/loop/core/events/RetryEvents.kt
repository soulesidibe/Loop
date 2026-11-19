package loop.core.events

import loop.api.RecordedEvent
import loop.core.events.HumanText.count
import loop.core.events.HumanText.words

/** A retry or continuation scheduled for the issue (spec 16.6, m1-07). */
object RetryScheduled : EventDefinition("retry_scheduled") {
    const val KIND = "kind"

    /** The spec retry count, not the trace's attempt number: 1 is the first after a dispatch. */
    const val ATTEMPT = "attempt"
    const val DELAY_MS = "delay_ms"
    const val DUE_AT = "due_at"
    const val ERROR = "error"
    const val FAILURES = "failures"

    /** The [KIND] of a continuation; any other kind is a retry. */
    const val CONTINUATION = "continuation"

    /** Set on a retry scheduled again from the store after a restart. */
    const val RESTORED = "restored"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample =
        mapOf(
            KIND to "failure",
            ATTEMPT to "2",
            DELAY_MS to "20000",
            DUE_AT to "2026-11-15T10:20:50Z",
            ERROR to "exit_code=1",
            FAILURES to "1",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(KIND, data[KIND])
        add("retry", data[ATTEMPT])
        add("delay", TraceText.millis(data[DELAY_MS]))
        add("due", data[DUE_AT]?.let(TraceText::clock))
        word(Spend.traceNoProgress(data))
        add(ERROR, data[ERROR])
    }

    /** The name of the attempt this schedules, `retry 2` or `continuation 1`, or null without a number. */
    fun attemptName(data: Map<String, String>): String? {
        val number = data[ATTEMPT] ?: return null
        return if (data[KIND] == CONTINUATION) "continuation $number" else "retry $number"
    }

    /**
     * Every normal exit schedules a continuation (spec 16.6), mostly dropped at the next poll because the issue moved on:
     * it gets a line only when it starts.
     */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? {
        val name = attemptName(data) ?: return null
        if (data[KIND] == CONTINUATION) return null
        val delay = data[DELAY_MS]?.toLongOrNull()?.let { " in ${HumanText.duration(it)}" }.orEmpty()
        return "$name$delay"
    }
}

/** A scheduled retry dropped, for example because the issue moved on. */
object RetryDropped : EventDefinition("retry_dropped") {
    const val REASON = "reason"

    /** Set when a blocker made the issue not eligible. */
    const val BLOCKED_REASON = "blocked_reason"

    override val trace = TraceShow.ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(REASON to "not_active")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(REASON, event.data[REASON]) }

    /** A dropped continuation gets no line, as its scheduling got none. */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? =
        if (facts.nextAttempt?.startsWith(RetryScheduled.CONTINUATION) != true) "retry dropped, ${words(data[REASON] ?: "")}" else null
}

/** The issue parked, so Loop will not dispatch it until a human resumes it or its tracker state changes. */
object IssueParked : EventDefinition("issue_parked") {
    const val REASON = "reason"
    const val FAILURES = "failures"
    const val ERROR = "error"
    const val STORE_ERROR = "store_error"

    const val MAX_ATTEMPTS = "max_attempts"
    const val UNVERIFIED = "unverified"
    const val TOKEN_CAP = "token_cap"

    /** m3-11: a rotation past `context.max_rotations`; the park carries [ROTATIONS] and [MAX_ROTATIONS]. */
    const val MAX_ROTATIONS = "max_rotations"

    /** The rotations the attempt made before the one refused. */
    const val ROTATIONS = "rotations"

    /** m3-12: two rotations in a row without progress; the park carries [WITHOUT_PROGRESS], [ROTATIONS] and [LIMIT]. */
    const val SPLIT_ISSUE = "split_issue"

    /** The rotations in a row without progress, the refused one included. */
    const val WITHOUT_PROGRESS = "without_progress"

    /** The limit that asked for the refused rotation, `soft_limit` or `hard_limit`. */
    const val LIMIT = "limit"

    /** The finding a `split_issue` park gives a human, in its comment and its log line. */
    const val SPLIT_FINDING = "split this issue"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(REASON to MAX_ATTEMPTS, FAILURES to "3", ERROR to "exit_code=1")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(REASON, data[REASON])
        when (data[REASON]) {
            Spend.BUDGET -> word(Spend.traceBudget(data))
            TOKEN_CAP -> word(Spend.traceTokens(data))
            Spend.MAX_CONTINUATIONS -> word(Spend.traceNoProgress(data))
            MAX_ROTATIONS -> {
                word("$ROTATIONS=${data[ROTATIONS] ?: "?"}/${data[MAX_ROTATIONS] ?: "?"}")
                add(SessionRotated.FRACTION, data[SessionRotated.FRACTION])
            }
            SPLIT_ISSUE -> listOf(WITHOUT_PROGRESS, ROTATIONS, LIMIT, SessionRotated.FRACTION).forEach { add(it, data[it]) }
            else -> {
                add(FAILURES, data[FAILURES])
                add(ERROR, data[ERROR])
            }
        }
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val why =
            when (data[REASON]) {
                MAX_ATTEMPTS -> "after ${count(data[FAILURES]?.toIntOrNull() ?: 0, "failed attempt")}"
                Spend.BUDGET -> "after passing its ${data[Spend.BUDGET]} budget, ${Spend.humanBudgetUse(data)}"
                Spend.MAX_CONTINUATIONS -> "after ${count(data[Spend.NO_PROGRESS]?.toIntOrNull() ?: 0, "continuation")} without progress"
                UNVERIFIED -> "because its result could not be verified" + data[ERROR]?.let { ", $it" }.orEmpty()
                MAX_ROTATIONS -> "after ${count(data[ROTATIONS]?.toIntOrNull() ?: 0, "rotation")}, its session was full again"
                SPLIT_ISSUE ->
                    "with the finding $SPLIT_FINDING, after " +
                        "${count(data[WITHOUT_PROGRESS]?.toIntOrNull() ?: 0, "session rotation")} in a row without progress"
                else -> "for ${data[REASON]}"
            }
        return "parked $why, until loop resume or a tracker change"
    }
}

/** A parked issue unparked by `loop resume` or a change of its tracker state. */
object IssueUnparked : EventDefinition("issue_unparked") {
    const val REASON = "reason"
    const val FROM_STATE = "from_state"
    const val TO_STATE = "to_state"

    /** The [REASON] of `loop resume`. */
    const val RESUME = "resume"

    /** The [REASON] of a change of the issue's tracker state. */
    const val TRACKER_CHANGED = "tracker_changed"

    override val trace = TraceShow.ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(REASON to TRACKER_CHANGED, FROM_STATE to "todo", TO_STATE to "in_progress")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(REASON, event.data[REASON])
        add("from", event.data[FROM_STATE])
        add("to", event.data[TO_STATE])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "unparked, its tracker state changed from ${data[FROM_STATE]} to ${data[TO_STATE]}"
}

/** m1-09: a candidate not dispatched, and why, recorded when the reason changes. */
object CandidateSkipped : EventDefinition("candidate_skipped") {
    const val REASON = "reason"

    override val trace = TraceShow.ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(REASON to "blocked_by bl-2")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "skipped, ${words(data[REASON] ?: "")}"
}
