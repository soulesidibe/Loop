package loop.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The behavior every [EventExporter] must show (m4-42): a failure to export is a failed [Result], never an exception,
 * and the exporter goes on taking events after it, so a broken exporter never stops the run. An exporter's test extends
 * this class and supplies exporters that fail in each way it can.
 */
public abstract class EventExporterContract {
    /** An exporter whose transport throws on every export. */
    protected abstract fun throwingExporter(): EventExporter

    /** An exporter whose endpoint cannot be reached, with a short timeout. */
    protected abstract fun unreachableExporter(): EventExporter

    /** One attempt, from start to end, with a session, a turn and a verification: every exporter has spans to send. */
    protected open fun attemptEvents(): List<RecordedEvent> {
        val start = Instant.parse("2026-11-24T10:00:00Z")
        val project = ProjectId("contract-project")
        val attempt = AttemptId("contract-attempt")
        val session = SessionId("contract-session")
        val turn = TurnId("contract-turn")

        fun event(
            seconds: Int,
            kind: String,
            withSession: Boolean = false,
            withTurn: Boolean = false,
            data: Map<String, String> = emptyMap(),
        ) = RecordedEvent(
            ts = start + seconds.seconds,
            projectId = project,
            kind = kind,
            runId = RunId("contract-run"),
            issueId = "contract-issue",
            issueIdentifier = "c-1",
            attemptId = attempt,
            sessionId = if (withSession || withTurn) session else null,
            turnId = if (withTurn) turn else null,
            data = data,
        )
        return listOf(
            event(0, "attempt_started"),
            event(1, "turn_started", withTurn = true, data = mapOf("turn" to "1")),
            event(2, "runner_output", withTurn = true, data = mapOf("line" to "hello")),
            event(3, "turn_completed", withTurn = true, data = mapOf("turn" to "1")),
            event(4, "check_started", data = mapOf("check" to "0")),
            event(5, "verdict", data = mapOf("verdict" to "verified")),
            event(6, "attempt_finished", data = mapOf("outcome" to "succeeded")),
        )
    }

    @Test
    public fun `an exporter whose transport throws returns a failed result and never throws`() {
        assertFailsWithoutThrowing(throwingExporter())
    }

    @Test
    public fun `an exporter that cannot reach its endpoint returns a failed result and never throws`() {
        assertFailsWithoutThrowing(unreachableExporter())
    }

    @Test
    public fun `close never throws, after failures included`() {
        val exporter = throwingExporter()
        runBlocking { attemptEvents().forEach { exporter.record(it) } }
        try {
            exporter.close()
        } catch (e: Exception) {
            fail("close threw ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Every event of an attempt is recorded without an exception, some fail, and the exporter takes the next attempt too. */
    private fun assertFailsWithoutThrowing(exporter: EventExporter) {
        exporter.use {
            val results =
                runBlocking {
                    (attemptEvents() + attemptEvents()).map { event ->
                        try {
                            it.record(event)
                        } catch (e: Exception) {
                            fail("record threw ${e.javaClass.name} on ${event.kind}: ${e.message}")
                        }
                    }
                }
            assertTrue(results.any { it.isFailure }, "a failed export must be a failed result")
            val half = results.size / 2
            assertTrue(results.drop(half).any { it.isFailure }, "the exporter must go on taking events after a failure")
        }
    }
}
