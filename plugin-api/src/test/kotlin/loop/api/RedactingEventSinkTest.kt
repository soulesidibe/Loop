package loop.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

/** m4-42: an exporter sees an event redacted like the store keeps it. */
class RedactingEventSinkTest {
    private class Captured : EventSink {
        val events = mutableListOf<RecordedEvent>()

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            events += event
            return Result.success(Unit)
        }
    }

    private val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TOKEN" to "s3cr3t-value")) }

    @Test
    fun `m4-42 the identifier, the phase and every data value are redacted, the ids and keys are kept`() {
        val captured = Captured()
        val event =
            RecordedEvent(
                ts = Instant.parse("2026-11-24T10:00:00Z"),
                projectId = ProjectId("p-1"),
                kind = "runner_output",
                runId = RunId("r-1"),
                issueId = "i-1",
                issueIdentifier = "id s3cr3t-value",
                attemptId = AttemptId("a-1"),
                sessionId = SessionId("s-1"),
                turnId = TurnId("t-1"),
                phase = "phase s3cr3t-value",
                data = mapOf("line" to "token s3cr3t-value and ghp_abcdefghijklmnopqrstuvwxyz0123456789", "stream" to "stdout"),
            )

        val result = runBlocking { RedactingEventSink(redactor, captured).record(event) }

        assertEquals(Result.success(Unit), result)
        val seen = captured.events.single()
        assertEquals("id [redacted:LOOP_TOKEN]", seen.issueIdentifier)
        assertEquals("phase [redacted:LOOP_TOKEN]", seen.phase)
        assertEquals("token [redacted:LOOP_TOKEN] and [redacted:github-token]", seen.data["line"])
        assertEquals("stdout", seen.data["stream"])
        assertEquals(event.copy(issueIdentifier = seen.issueIdentifier, phase = seen.phase, data = seen.data), seen)
    }

    @Test
    fun `m4-42 the result of the wrapped sink is returned as it is`() {
        val down = IllegalStateException("down")
        val failing =
            object : EventSink {
                override suspend fun record(event: RecordedEvent): Result<Unit> = Result.failure(down)
            }
        val event = RecordedEvent(ts = Instant.parse("2026-11-24T10:00:00Z"), projectId = ProjectId("p-1"), kind = "poll")

        assertSame(down, runBlocking { RedactingEventSink(redactor, failing).record(event) }.exceptionOrNull())
    }
}
