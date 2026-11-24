package loop.export.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.SessionId
import loop.api.TurnId
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The [SpanExporter] of these tests: it keeps every span it is given, in order, and fails while [failing] is set. */
class RecordingSpanExporter : SpanExporter {
    val spans = mutableListOf<SpanData>()
    var failing: Boolean = false
    var closed: Boolean = false

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (failing) return CompletableResultCode.ofFailure()
        this.spans += spans
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        closed = true
        return CompletableResultCode.ofSuccess()
    }

    fun named(name: String): List<SpanData> = spans.filter { it.name == name }
}

/** The events of one attempt as the orchestrator records them, built in order with a clock one second per event. */
class AttemptScript(
    val attempt: String = "attempt-1",
) {
    private val start = Instant.parse("2026-11-24T10:00:00Z")
    private var second = 0
    val events = mutableListOf<RecordedEvent>()

    fun at(index: Int): Instant = start + index.seconds

    fun event(
        kind: String,
        session: String? = null,
        turn: String? = null,
        phase: String? = null,
        data: Map<String, String> = emptyMap(),
    ): RecordedEvent =
        RecordedEvent(
            ts = at(second++),
            projectId = ProjectId("project-1"),
            kind = kind,
            runId = RunId("run-1"),
            issueId = "issue-1",
            issueIdentifier = "m4-42",
            attemptId = AttemptId(attempt),
            sessionId = session?.let(::SessionId),
            turnId = turn?.let(::TurnId),
            phase = phase,
            data = data,
        ).also { events += it }

    companion object {
        /** The ledger setup an `attempt_started` carries since m4-42. */
        val SETUP =
            mapOf(
                "loop_version" to "0.4.0",
                "config_version" to "c0ffee",
                "occupancy" to "true",
                "tracker" to "default",
                "kind" to "code",
                "runner" to "claude-code",
                "model" to "anthropic/claude-opus-5-5",
                "template_version" to "7e3a",
            )
    }
}

/** Records every event of [script] in order and returns the results. */
fun OtelEventExporter.recordAll(script: AttemptScript): List<Result<Unit>> = runBlocking { script.events.map { record(it) } }

/** The first [bytes] bytes of the SHA-256 of [key], as lowercase hex: how the tests derive the ids they expect. */
fun sha256Hex(
    key: String,
    bytes: Int,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .take(bytes)
        .joinToString("") { "%02x".format(it) }
