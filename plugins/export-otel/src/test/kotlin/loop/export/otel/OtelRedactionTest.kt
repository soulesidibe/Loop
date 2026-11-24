package loop.export.otel

import kotlinx.coroutines.runBlocking
import loop.api.RedactingEventSink
import loop.api.SecretRedactor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** m4-42: the host wraps the exporter in the run's redactor, so a token in an event never reaches a span. */
class OtelRedactionTest {
    @Test
    fun `m4-42 a token in runner output never reaches the exported span`() {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val learned = "s3cr3t-from-the-env"
        val redactor = SecretRedactor().apply { learn(mapOf("OTEL_TOKEN" to learned)) }
        val spans = RecordingSpanExporter()
        val exporter =
            OtelEventExporter(spans, timeout = 5.seconds, clock = {
                kotlin.time.Clock.System
                    .now()
            })
        val sink = RedactingEventSink(redactor, exporter)
        val script = AttemptScript()
        script.event("attempt_started", data = AttemptScript.SETUP)
        script.event("turn_started", session = "s1", turn = "t1", data = mapOf("turn" to "1"))
        script.event("runner_output", session = "s1", turn = "t1", data = mapOf("stream" to "stdout", "line" to "push with $token"))
        script.event("runner_output", session = "s1", turn = "t1", data = mapOf("stream" to "stderr", "line" to "header $learned"))
        script.event("turn_completed", session = "s1", turn = "t1", data = mapOf("turn" to "1"))

        runBlocking { script.events.forEach { sink.record(it) } }
        exporter.close()

        val exported =
            spans.spans.joinToString("\n") { span ->
                "${span.name} ${span.attributes} " + span.events.joinToString(" ") { "${it.name} ${it.attributes}" }
            }
        assertTrue("runner_output" in exported, exported)
        assertTrue("[redacted:github-token]" in exported, exported)
        assertTrue("[redacted:OTEL_TOKEN]" in exported, exported)
        assertFalse(token in exported, exported)
        assertFalse(learned in exported, exported)
    }
}
