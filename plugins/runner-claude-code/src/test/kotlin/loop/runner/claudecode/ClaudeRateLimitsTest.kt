package loop.runner.claudecode

import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The rate limit snapshot of a Claude Code session, read from its `rate_limit_event` lines (bl-209). */
class ClaudeRateLimitsTest {
    private val session = SessionId("s")
    private val turn = TurnId("t")

    @Test
    fun `bl-209 the recorded rate limit line maps to a rate_limit event of the claude limit`() {
        val line = Fixtures.lines("real_two_turns_one_process").map(StreamJsonMapper::parse).filterIsInstance<StreamLine.RateLimit>().single()

        val event = ClaudeRateLimits().read(line, session, turn)

        assertEquals(
            RunnerEvent.RateLimit(
                sessionId = session,
                turnId = turn,
                limitId = "claude",
                limitName = null,
                primary = RunnerEvent.RateLimit.Window(74, 10080, Instant.fromEpochSeconds(1794906000)),
                secondary = null,
                reached = null,
            ),
            event,
        )
    }

    @Test
    fun `bl-209 a line that changes nothing gives no event`() {
        val limits = ClaudeRateLimits()
        val line = rateLimit("allowed", "seven_day" to window(0.07, 1795510800))

        limits.read(line, session, turn)

        assertNull(limits.read(line, session, turn))
        assertNull(limits.read(rateLimit("allowed"), session, turn), "a line with no window keeps the earlier ones")
    }

    @Test
    fun `bl-209 the shortest window is the primary, a later line keeps the windows it leaves out, and reached clears`() {
        val limits = ClaudeRateLimits()
        limits.read(rateLimit("allowed", "seven_day" to window(0.5, 1795510800)), session, turn)

        val both = limits.read(rateLimit("rejected", "five_hour" to window(1.0, 1794906000)), session, turn)

        assertEquals(RunnerEvent.RateLimit.Window(100, 300, Instant.fromEpochSeconds(1794906000)), both?.primary)
        assertEquals(RunnerEvent.RateLimit.Window(50, 10080, Instant.fromEpochSeconds(1795510800)), both?.secondary)
        assertEquals("rejected", both?.reached)
        val cleared = limits.read(rateLimit("allowed"), session, turn)
        assertNull(cleared?.reached)
        assertEquals(both?.primary, cleared?.primary)
    }

    @Test
    fun `bl-209 a window without a utilization or a readable length is reported as far as it can be`() {
        val limits = ClaudeRateLimits()

        val event =
            limits.read(
                rateLimit("allowed_warning", "seven_day_opus" to window(0.255, null), "seven_day" to window(null, 1795510800), "overage" to window(0.9, null)),
                session,
                turn,
            )

        assertEquals(RunnerEvent.RateLimit.Window(26, 10080, null), event?.primary)
        assertEquals(RunnerEvent.RateLimit.Window(90, null, null), event?.secondary)
        assertEquals("allowed_warning", event?.reached)
    }

    private fun rateLimit(
        status: String?,
        vararg windows: Pair<String, StreamLine.RateLimit.Window>,
    ) = StreamLine.RateLimit(status, mapOf(*windows))

    private fun window(
        utilization: Double?,
        resetsAt: Long?,
    ) = StreamLine.RateLimit.Window(utilization, resetsAt)
}
