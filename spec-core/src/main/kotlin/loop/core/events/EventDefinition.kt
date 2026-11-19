package loop.core.events

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.SessionId
import loop.api.TurnId
import kotlin.time.Instant

/**
 * One event kind, defined once (bl-80): its name, its keys, the builder its emitters use, its `loop trace` details, its
 * human log sentence and how it reads the shapes older runs recorded. The event itself stays a [RecordedEvent], a string
 * kind and a string map, so the store never branches on a kind. Every definition is listed in [EventDefinitions].
 */
abstract class EventDefinition(
    /** The kind as recorded. */
    val name: String,
) {
    /** Where `loop trace` shows the kind, or [TraceShow.SILENT]. */
    open val trace: TraceShow = TraceShow.SILENT

    /** Where the human log writes the kind's sentence, or [HumanShow.SILENT]. */
    open val human: HumanShow = HumanShow.SILENT

    /** Data of a typical event of the kind, which renders in the trace and in the human log unless the kind is silent there. */
    abstract val sample: Map<String, String>

    /** The phase of the sample event, set only for `phase`. */
    open val samplePhase: String? = null

    /** The event of this kind with [data] and the ids it belongs to. */
    fun event(
        ts: Instant,
        projectId: ProjectId,
        data: Map<String, String> = emptyMap(),
        runId: RunId? = null,
        issueId: String? = null,
        issueIdentifier: String? = null,
        attemptId: AttemptId? = null,
        sessionId: SessionId? = null,
        turnId: TurnId? = null,
        phase: String? = null,
    ): RecordedEvent =
        RecordedEvent(
            ts = ts,
            projectId = projectId,
            kind = name,
            runId = runId,
            issueId = issueId,
            issueIdentifier = issueIdentifier,
            attemptId = attemptId,
            sessionId = sessionId,
            turnId = turnId,
            phase = phase,
            data = data,
        )

    /** The name column of the trace line: the kind, or the phase name of a `phase` event. */
    open fun traceName(event: RecordedEvent): String = name

    /** True when [event] shows inside its attempt block of `loop trace`; an old shape that would print wrong is left out. */
    open fun showsInTimeline(event: RecordedEvent): Boolean = trace.inTimeline

    /** The details of the trace line after the name column, each `key=value` or a word; by default every field in order. */
    open fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ): List<String> = traceDetails(context) { event.data.forEach { (key, value) -> add(key, value) } }

    /** Lines printed under the trace line, without indent; none by default. */
    open fun traceOutput(event: RecordedEvent): List<String> = emptyList()

    /** The human log sentence of an event with [data], or null when this event gets no line. */
    open fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? = null

    override fun toString(): String = name
}

/** Where `loop trace` shows a kind: inside an attempt block, as its own line when recorded outside any attempt, both or neither. */
enum class TraceShow(
    val inTimeline: Boolean,
    val issueLevel: Boolean,
) {
    SILENT(false, false),
    TIMELINE(true, false),
    ISSUE_LEVEL(false, true),
    TIMELINE_AND_ISSUE_LEVEL(true, true),
}

/** Where the human log writes a kind's sentence: on a line of its issue, on a run level line, or nowhere. */
enum class HumanShow { SILENT, ISSUE, RUN }

/** What a trace line needs beyond the event: whether ids print in full, and the attempt's thread for the launch line. */
class TraceContext(
    val fullIds: Boolean,
    val thread: String? = null,
) {
    /** [value] in full with `--full-ids`, else its first 8 characters. */
    fun id(value: String): String = if (fullIds) value else value.take(SHORT_ID)

    private companion object {
        const val SHORT_ID = 8
    }
}

/** What the human log knows beyond the event when it writes the event's sentence. */
interface HumanFacts {
    /** `attempt` on a first dispatch, `retry 2` or `continuation 1` after a `retry_scheduled`. */
    val attemptName: String

    /** The issue state the attempt's Succeeded phase recorded, null when unknown. */
    val issueState: String?

    /** The attempt's tokens and cost so far, `, 1.2M tokens, $3.38`, empty when nothing is known. */
    val spend: String

    /** True while the workflow check fails, so only the first failure and the recovery get a line. */
    val preflightFailing: Boolean

    /** The name of the issue's next attempt, from its last `retry_scheduled`, null when none is pending. */
    val nextAttempt: String?

    companion object {
        /** No attempt known, no failing preflight, nothing pending. */
        val NONE: HumanFacts =
            object : HumanFacts {
                override val attemptName = "attempt"
                override val issueState: String? = null
                override val spend = ""
                override val preflightFailing = false
                override val nextAttempt: String? = null
            }
    }
}

/** The details of one trace line, built in order. */
class TraceDetails internal constructor(
    val context: TraceContext,
) {
    internal val parts = mutableListOf<String>()

    /** `key=value`, the value quoted when needed; nothing when [value] is null. */
    fun add(
        key: String,
        value: String?,
    ) {
        if (value != null) parts += "$key=${TraceText.quote(value)}"
    }

    /** [text] as it is; nothing when null. */
    fun word(text: String?) {
        if (text != null) parts += text
    }
}

internal fun traceDetails(
    context: TraceContext,
    build: TraceDetails.() -> Unit,
): List<String> = TraceDetails(context).apply(build).parts
