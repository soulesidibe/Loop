package loop.export.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import loop.api.EventExporter
import loop.api.RecordedEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The OpenTelemetry exporter of Loop's events (m4-42). Each attempt is one trace, its id derived from the attempt id,
 * with a span for the attempt, each of its sessions, each turn and each verification, built from `attempt_started` and
 * `attempt_finished`, `session_rotated` and the session ids, `turn_started` and `turn_completed` or `turn_failed`, and
 * `check_started` or `result_submitted` to `verdict`. Every other event of an attempt becomes a span event of the
 * innermost span open for it; one that no span holds, such as a merge check after the attempt ended, becomes a span of
 * its own under the attempt span. Events outside any attempt are not exported.
 *
 * A span goes to [spans] as soon as its closing event is recorded, so a verification that comes after the attempt ended
 * joins the same trace. [record] returns a failed result when that export fails or takes longer than [timeout], and
 * never throws. [close] ends the spans still open at [clock]'s time, exports them and shuts [spans] down.
 *
 * Every span carries the ids of ADR 0001 and the attempt's ledger setup, which `attempt_started` holds. The exporter
 * keeps what it knows of the last [MAX_ATTEMPTS] attempts only.
 */
public class OtelEventExporter(
    private val spans: SpanExporter,
    private val timeout: Duration,
    private val clock: () -> Instant,
) : EventExporter {
    private val lock = Any()
    private val resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "loop"))
    private val traces =
        object : LinkedHashMap<String, AttemptTrace>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AttemptTrace>?): Boolean = size > MAX_ATTEMPTS
        }

    override suspend fun record(event: RecordedEvent): Result<Unit> {
        val ended =
            try {
                synchronized(lock) { place(event) }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        return export(ended)
    }

    override fun close() {
        val open =
            synchronized(lock) {
                val now = clock().epochNanos()
                traces.values.flatMap { it.endAll(now) }
            }
        try {
            if (open.isNotEmpty()) spans.export(open).join(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            spans.shutdown().join(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // close never throws: a span that cannot go out at the end of a run is lost, as any failed export.
        }
    }

    /** Puts [event] in its attempt's trace and returns the spans it ended. */
    private fun place(event: RecordedEvent): List<LoopSpan> {
        val attemptId = event.attemptId?.value ?: return emptyList()
        val trace = traces.getOrPut(attemptId) { AttemptTrace(attemptId) }
        return trace.place(event)
    }

    private suspend fun export(ended: List<LoopSpan>): Result<Unit> {
        if (ended.isEmpty()) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            try {
                val result = spans.export(ended).join(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                when {
                    !result.isDone -> Result.failure(TimeoutException("the span export took longer than $timeout"))
                    !result.isSuccess ->
                        Result.failure(IllegalStateException("the span export failed", result.failureThrowable))
                    else -> Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** A span still open: what it is, where it sits in the tree, and the events it got so far. */
    private class OpenSpan(
        val name: String,
        val id: String?,
        val spanId: String,
        val parentSpanId: String?,
        val startNanos: Long,
    ) {
        val attributes = linkedMapOf<String, String>()
        val events = mutableListOf<EventData>()
        var error = false
    }

    /** What the exporter knows of one attempt: its ids and setup, and its spans still open. */
    private inner class AttemptTrace(
        val attemptId: String,
    ) {
        val traceId = LoopIds.traceId(attemptId)
        val attemptSpanId = LoopIds.attemptSpanId(attemptId)

        /** The attributes of every span of the attempt: the ids of ADR 0001 and the ledger setup. */
        val common = linkedMapOf(ATTEMPT_ID to attemptId)
        var attempt: OpenSpan? = null
        var session: OpenSpan? = null
        var turn: OpenSpan? = null
        var verification: OpenSpan? = null

        fun place(event: RecordedEvent): List<LoopSpan> {
            learnIds(event)
            val at = event.ts.epochNanos()
            val ended = mutableListOf<LoopSpan>()
            when (event.kind) {
                "attempt_started" -> {
                    ended += endAll(at)
                    SETUP_KEYS.forEach { key -> event.data[key]?.let { common["loop.$key"] = it } }
                    attempt = OpenSpan(ATTEMPT_SPAN, attemptId, attemptSpanId, null, at).also { it.add(event) }
                }
                "attempt_finished" -> {
                    val span = attempt ?: innermost() ?: return listOf(alone(event))
                    span.add(event)
                    attempt?.let { root ->
                        listOf("outcome", "reason", "turns").forEach { key -> event.data[key]?.let { root.attributes["loop.$key"] = it } }
                        root.error = event.data["outcome"] != "succeeded"
                    }
                    ended += endAll(at)
                }
                "session_rotated" -> {
                    val sessionId = event.sessionId?.value
                    if (sessionId == null) {
                        ended += route(event, at)
                    } else {
                        ended += enterSession(sessionId, at)
                        checkNotNull(session).add(event)
                    }
                }
                "turn_started" -> {
                    event.sessionId?.value?.let { ended += enterSession(it, at) }
                    turn?.let { ended += end(it, at) }
                    turn = null
                    val turnId = event.turnId?.value
                    if (turnId == null) {
                        ended += route(event, at)
                    } else {
                        val parent = session?.spanId ?: attemptSpanId
                        turn =
                            OpenSpan(TURN_SPAN, turnId, LoopIds.turnSpanId(attemptId, turnId), parent, at).also {
                                session?.id?.let { id -> it.attributes[SESSION_ID] = id }
                                it.attributes[TURN_ID] = turnId
                                event.data["turn"]?.let { number -> it.attributes["loop.turn"] = number }
                                it.add(event)
                            }
                    }
                }
                "turn_completed", "turn_failed" -> {
                    val open = turn
                    if (open != null && (event.turnId == null || event.turnId?.value == open.id)) {
                        open.add(event)
                        open.error = event.kind == "turn_failed"
                        ended += end(open, at)
                        turn = null
                    } else {
                        ended += route(event, at)
                    }
                }
                "check_started", "result_submitted" -> {
                    if (verification == null && (event.kind == "check_started" || turn == null)) openVerification(at)
                    ended += route(event, at)
                }
                "verdict" -> {
                    val open = verification ?: openVerification(at)
                    open.add(event)
                    event.data["verdict"]?.let { open.attributes["loop.verdict"] = it }
                    open.error = event.data["verdict"] != "verified"
                    ended += end(open, at)
                    verification = null
                }
                else -> ended += route(event, at)
            }
            return ended
        }

        /** Ends every span still open at [at], innermost first. */
        fun endAll(at: Long): List<LoopSpan> {
            val open = listOfNotNull(turn, verification, session, attempt)
            turn = null
            verification = null
            session = null
            attempt = null
            return open.map { end(it, at) }
        }

        private fun learnIds(event: RecordedEvent) {
            common["loop.project_id"] = event.projectId.value
            event.runId?.let { common["loop.run_id"] = it.value }
            event.issueId?.let { common["loop.issue_id"] = it }
            event.issueIdentifier?.let { common["loop.issue_identifier"] = it }
        }

        /** Puts [event] on the innermost span open for it, opening its session when needed. */
        private fun route(
            event: RecordedEvent,
            at: Long,
        ): List<LoopSpan> {
            val ended = mutableListOf<LoopSpan>()
            val sessionId = event.sessionId?.value
            val turnId = event.turnId?.value
            val target =
                when {
                    verification != null -> verification
                    turn != null && turnId == turn?.id -> turn
                    // The ids of a turn already ended: an event after the agent's work, such as an issue move.
                    turnId != null -> attempt
                    sessionId != null -> {
                        ended += enterSession(sessionId, at)
                        turn ?: session
                    }
                    else -> innermost()
                }
            if (target == null) ended += alone(event) else target.add(event)
            return ended
        }

        private fun innermost(): OpenSpan? = turn ?: session ?: attempt

        /** Makes [sessionId] the open session, ending the one before it and its turn. */
        private fun enterSession(
            sessionId: String,
            at: Long,
        ): List<LoopSpan> {
            if (session?.id == sessionId) return emptyList()
            val ended = listOfNotNull(turn, session).map { end(it, at) }
            turn = null
            session =
                OpenSpan(SESSION_SPAN, sessionId, LoopIds.sessionSpanId(attemptId, sessionId), attemptSpanId, at).also {
                    it.attributes[SESSION_ID] = sessionId
                }
            return ended
        }

        private fun openVerification(at: Long): OpenSpan =
            OpenSpan(VERIFICATION_SPAN, null, LoopIds.verificationSpanId(attemptId, at), attemptSpanId, at).also {
                verification = it
            }

        /** A span of [event] alone, under the attempt span, for an event that no open span holds. */
        private fun alone(event: RecordedEvent): LoopSpan {
            val at = event.ts.epochNanos()
            val span = OpenSpan(EVENT_SPAN, null, LoopIds.eventSpanId(attemptId, event.kind, at), attemptSpanId, at)
            span.attributes["loop.event"] = event.kind
            span.add(event)
            return end(span, at)
        }

        private fun OpenSpan.add(event: RecordedEvent) {
            val attributes = Attributes.builder()
            event.data.forEach { (key, value) -> attributes.put("loop.$key", value) }
            event.phase?.let { attributes.put("loop.phase", it) }
            event.sessionId?.let { attributes.put(SESSION_ID, it.value) }
            event.turnId?.let { attributes.put(TURN_ID, it.value) }
            events += EventData.create(event.ts.epochNanos(), event.kind, attributes.build())
        }

        private fun end(
            span: OpenSpan,
            at: Long,
        ): LoopSpan {
            val attributes = Attributes.builder()
            common.forEach { (key, value) -> attributes.put(key, value) }
            span.attributes.forEach { (key, value) -> attributes.put(key, value) }
            return LoopSpan(
                name = span.name,
                context = LoopIds.context(traceId, span.spanId),
                parent = span.parentSpanId?.let { LoopIds.context(traceId, it) } ?: SpanContext.getInvalid(),
                status = if (span.error) StatusData.error() else StatusData.unset(),
                startNanos = span.startNanos,
                endNanos = maxOf(at, span.startNanos),
                attributes = attributes.build(),
                events = span.events.toList(),
                resource = resource,
            )
        }
    }

    public companion object {
        /** How many attempts the exporter keeps open spans and setup for; the oldest is dropped first. */
        public const val MAX_ATTEMPTS: Int = 256

        internal const val ATTEMPT_SPAN = "loop.attempt"
        internal const val SESSION_SPAN = "loop.session"
        internal const val TURN_SPAN = "loop.turn"
        internal const val VERIFICATION_SPAN = "loop.verification"
        internal const val EVENT_SPAN = "loop.event"
        private const val ATTEMPT_ID = "loop.attempt_id"
        private const val SESSION_ID = "loop.session_id"
        private const val TURN_ID = "loop.turn_id"

        /** The keys of `attempt_started` that every span of the attempt carries: the build, the config and the ledger setup. */
        private val SETUP_KEYS =
            listOf("tracker", "kind", "runner", "model", "template_version", "config_version", "loop_version")
    }
}
