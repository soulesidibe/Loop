package loop.export.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.runBlocking
import loop.api.ProjectId
import loop.api.RecordedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** m4-42: the events of an attempt become one trace, a span per attempt, session, turn and verification. */
class OtelEventExporterTest {
    private val spans = RecordingSpanExporter()
    private val closedAt = Instant.parse("2026-11-24T11:00:00Z")
    private val exporter = OtelEventExporter(spans, timeout = 5.seconds, clock = { closedAt })

    /** Two sessions, three turns and one verification, with the ids the orchestrator gives each event. */
    private fun twoSessions(): AttemptScript =
        AttemptScript().apply {
            event("attempt_started", data = AttemptScript.SETUP)
            event("phase", phase = "PreparingWorkspace", data = mapOf("workspace" to "/w/m4-42"))
            event("phase", session = "s1", phase = "LaunchingAgentProcess")
            event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1", "max_turns" to "10"))
            event("runner_output", session = "s1", turn = "t1", data = mapOf("stream" to "stdout", "line" to "hello"))
            event("turn_completed", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
            event("turn_started", session = "s1", turn = "t2", data = mapOf("turn" to "2"))
            event("usage", session = "s1", turn = "t2", data = mapOf("attempt_tokens" to "1200"))
            event("turn_completed", session = "s1", turn = "t2", data = mapOf("turn" to "2"))
            event("session_rotated", session = "s2", turn = "t2", data = mapOf("reason" to "soft_limit", "rotation" to "1"))
            event("turn_started", session = "s2", turn = "t3", data = mapOf("turn" to "3"))
            event("turn_completed", session = "s2", turn = "t3", data = mapOf("turn" to "3"))
            // The verification keeps the last ids of the attempt, which must not reopen the closed turn.
            event("result_submitted", session = "s2", turn = "t3", data = mapOf("to" to "review"))
            event("check_started", session = "s2", turn = "t3", data = mapOf("check" to "0"))
            event("check_finished", session = "s2", turn = "t3", data = mapOf("check" to "0", "exit_code" to "0"))
            event("verdict", session = "s2", turn = "t3", data = mapOf("verdict" to "verified", "checks" to "1"))
            event("issue_moved", session = "s2", turn = "t3", data = mapOf("to" to "review"))
            event("attempt_finished", session = "s2", turn = "t3", data = mapOf("outcome" to "succeeded", "turns" to "3"))
        }

    private fun SpanData.attr(key: String): String? = attributes.get(AttributeKey.stringKey(key))

    private fun one(
        name: String,
        attr: String,
        value: String,
    ): SpanData = spans.named(name).single { it.attr(attr) == value }

    private fun nanos(at: Instant): Long = at.epochSeconds * 1_000_000_000 + at.nanosecondsOfSecond

    @Test
    fun `m4-42 an attempt with two sessions, three turns and one verification exports one trace with its span tree`() {
        val script = twoSessions()

        val results = exporter.recordAll(script)

        assertTrue(results.all { it.isSuccess }, results.toString())
        assertEquals(7, spans.spans.size, spans.spans.joinToString { it.name })
        val traceId = sha256Hex("loop:attempt:attempt-1", 16)
        assertEquals(setOf(traceId), spans.spans.map { it.traceId }.toSet())

        val attempt = spans.named("loop.attempt").single()
        assertEquals(sha256Hex("loop:attempt-span:attempt-1", 8), attempt.spanId)
        assertEquals(SpanContext.getInvalid(), attempt.parentSpanContext)
        val s1 = one("loop.session", "loop.session_id", "s1")
        val s2 = one("loop.session", "loop.session_id", "s2")
        assertEquals(sha256Hex("loop:session:attempt-1:s1", 8), s1.spanId)
        assertEquals(sha256Hex("loop:session:attempt-1:s2", 8), s2.spanId)
        listOf(s1, s2).forEach { assertEquals(attempt.spanId, it.parentSpanId) }
        val turns = listOf("t1", "t2", "t3").map { one("loop.turn", "loop.turn_id", it) }
        turns.forEach { assertEquals(sha256Hex("loop:turn:attempt-1:${it.attr("loop.turn_id")}", 8), it.spanId) }
        assertEquals(listOf(s1.spanId, s1.spanId, s2.spanId), turns.map { it.parentSpanId })
        assertEquals(listOf("1", "2", "3"), turns.map { it.attr("loop.turn") })
        val verification = spans.named("loop.verification").single()
        assertEquals(attempt.spanId, verification.parentSpanId)
        assertEquals("verified", verification.attr("loop.verdict"))
        assertEquals(7, spans.spans.map { it.spanId }.toSet().size)

        // Each span runs from the event that opened it to the event that ended it.
        assertEquals(nanos(script.at(0)) to nanos(script.at(17)), attempt.startEpochNanos to attempt.endEpochNanos)
        assertEquals(nanos(script.at(2)) to nanos(script.at(9)), s1.startEpochNanos to s1.endEpochNanos)
        assertEquals(nanos(script.at(9)) to nanos(script.at(17)), s2.startEpochNanos to s2.endEpochNanos)
        assertEquals(nanos(script.at(3)) to nanos(script.at(5)), turns[0].startEpochNanos to turns[0].endEpochNanos)
        assertEquals(nanos(script.at(12)) to nanos(script.at(15)), verification.startEpochNanos to verification.endEpochNanos)
        assertTrue(spans.spans.all { it.hasEnded() })
    }

    @Test
    fun `m4-42 the other events become span events of the innermost open span`() {
        exporter.recordAll(twoSessions())

        fun eventsOf(span: SpanData) = span.events.map { it.name }
        assertEquals(listOf("attempt_started", "phase", "issue_moved", "attempt_finished"), eventsOf(spans.named("loop.attempt").single()))
        assertEquals(listOf("phase"), eventsOf(one("loop.session", "loop.session_id", "s1")))
        assertEquals(listOf("session_rotated"), eventsOf(one("loop.session", "loop.session_id", "s2")))
        assertEquals(listOf("turn_started", "runner_output", "turn_completed"), eventsOf(one("loop.turn", "loop.turn_id", "t1")))
        assertEquals(listOf("turn_started", "usage", "turn_completed"), eventsOf(one("loop.turn", "loop.turn_id", "t2")))
        assertEquals(
            listOf("result_submitted", "check_started", "check_finished", "verdict"),
            eventsOf(spans.named("loop.verification").single()),
        )
        val output = one("loop.turn", "loop.turn_id", "t1").events.single { it.name == "runner_output" }
        assertEquals("hello", output.attributes.get(AttributeKey.stringKey("loop.line")))
        assertEquals("stdout", output.attributes.get(AttributeKey.stringKey("loop.stream")))
        val launch = one("loop.session", "loop.session_id", "s1").events.single()
        assertEquals("LaunchingAgentProcess", launch.attributes.get(AttributeKey.stringKey("loop.phase")))
    }

    @Test
    fun `m4-42 every span holds the ids of ADR 0001 and the ledger setup`() {
        exporter.recordAll(twoSessions())

        val common =
            mapOf(
                "loop.project_id" to "project-1",
                "loop.run_id" to "run-1",
                "loop.issue_id" to "issue-1",
                "loop.issue_identifier" to "m4-42",
                "loop.attempt_id" to "attempt-1",
                "loop.tracker" to "default",
                "loop.kind" to "code",
                "loop.runner" to "claude-code",
                "loop.model" to "anthropic/claude-opus-5-5",
                "loop.template_version" to "7e3a",
                "loop.config_version" to "c0ffee",
                "loop.loop_version" to "0.4.0",
            )
        for (span in spans.spans) {
            common.forEach { (key, value) -> assertEquals(value, span.attr(key), "${span.name} $key") }
        }
        spans.named("loop.session").forEach { assertTrue(it.attr("loop.session_id") != null) }
        val t3 = one("loop.turn", "loop.turn_id", "t3")
        assertEquals("s2", t3.attr("loop.session_id"))
        // Only the turn spans have a turn, only the turn and session spans a session.
        assertEquals(null, spans.named("loop.attempt").single().attr("loop.session_id"))
        assertEquals(null, spans.named("loop.verification").single().attr("loop.turn_id"))
        assertEquals("loop", spans.spans.first().resource.getAttribute(AttributeKey.stringKey("service.name")))
    }

    @Test
    fun `m4-42 a span is exported as soon as its closing event is recorded`() {
        val script = AttemptScript()
        script.event("attempt_started", data = AttemptScript.SETUP)
        script.event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        script.event("turn_completed", session = "s1", turn = "t1", data = mapOf("turn" to "1"))

        exporter.recordAll(script)

        assertEquals(listOf("loop.turn"), spans.spans.map { it.name })
    }

    @Test
    fun `m4-42 a failed attempt, a failed turn and a refuted verdict are error spans`() {
        val script = AttemptScript()
        script.event("attempt_started", data = AttemptScript.SETUP)
        script.event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        script.event("turn_failed", session = "s1", turn = "t1", data = mapOf("turn" to "1", "reason" to "exit_code=1"))
        script.event("check_started", session = "s1", turn = "t1", data = mapOf("check" to "0"))
        script.event("verdict", session = "s1", turn = "t1", data = mapOf("verdict" to "refuted"))
        script.event("attempt_finished", session = "s1", turn = "t1", data = mapOf("outcome" to "failed", "reason" to "checks"))

        exporter.recordAll(script)

        assertEquals(StatusCode.ERROR, spans.named("loop.turn").single().status.statusCode)
        assertEquals(StatusCode.ERROR, spans.named("loop.verification").single().status.statusCode)
        val attempt = spans.named("loop.attempt").single()
        assertEquals(StatusCode.ERROR, attempt.status.statusCode)
        assertEquals("failed", attempt.attr("loop.outcome"))
        assertEquals("checks", attempt.attr("loop.reason"))
        assertEquals(StatusCode.UNSET, spans.named("loop.session").single().status.statusCode)
    }

    @Test
    fun `m4-42 a verification after the attempt ended joins its trace under the attempt span`() {
        exporter.recordAll(twoSessions())
        val late = AttemptScript()
        late.event("merge_check_started", data = mapOf("round" to "1"))
        late.event("check_started", data = mapOf("check" to "0"))
        late.event("verdict", data = mapOf("verdict" to "verified", "merge_check" to "true"))

        val results = exporter.recordAll(late)

        assertTrue(results.all { it.isSuccess })
        val verifications = spans.named("loop.verification")
        assertEquals(2, verifications.size)
        val merge = verifications.last()
        assertEquals(sha256Hex("loop:attempt:attempt-1", 16), merge.traceId)
        assertEquals(sha256Hex("loop:attempt-span:attempt-1", 8), merge.parentSpanId)
        assertEquals(listOf("check_started", "verdict"), merge.events.map { it.name })
        // Known from the attempt's start, so a late span is found by the same setup filters.
        assertEquals("claude-code", merge.attr("loop.runner"))
        // An event of the attempt outside any span is a span of its own, so nothing recorded is lost.
        val alone = spans.named("loop.event").single()
        assertEquals("merge_check_started", alone.attr("loop.event"))
        assertEquals(merge.parentSpanId, alone.parentSpanId)
        assertEquals(alone.startEpochNanos, alone.endEpochNanos)
    }

    @Test
    fun `m4-42 an event outside any attempt is not exported`() {
        val event = RecordedEvent(ts = closedAt, projectId = ProjectId("project-1"), kind = "poll")

        val result = runBlocking { exporter.record(event) }

        assertTrue(result.isSuccess)
        exporter.close()
        assertEquals(emptyList(), spans.spans)
    }

    @Test
    fun `m4-42 a failed export is a failed result, and the next span still goes out`() {
        val script = AttemptScript()
        script.event("attempt_started", data = AttemptScript.SETUP)
        script.event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        script.event("turn_completed", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        spans.failing = true

        val results = exporter.recordAll(script)

        assertEquals(listOf(true, true, false), results.map { it.isSuccess })
        spans.failing = false
        val next = AttemptScript()
        next.event("turn_started", session = "s1", turn = "t2", data = mapOf("turn" to "2"))
        next.event("turn_completed", session = "s1", turn = "t2", data = mapOf("turn" to "2"))
        assertTrue(exporter.recordAll(next).all { it.isSuccess })
        assertEquals(listOf("t2"), spans.spans.map { it.attr("loop.turn_id") })
    }

    @Test
    fun `m4-42 close ends the spans still open at the close time, exports them and shuts the exporter down`() {
        val script = AttemptScript()
        script.event("attempt_started", data = AttemptScript.SETUP)
        script.event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        exporter.recordAll(script)

        exporter.close()

        assertEquals(setOf("loop.attempt", "loop.session", "loop.turn"), spans.spans.map { it.name }.toSet())
        assertTrue(spans.spans.all { it.endEpochNanos == nanos(closedAt) })
        assertTrue(spans.closed)
    }
}
