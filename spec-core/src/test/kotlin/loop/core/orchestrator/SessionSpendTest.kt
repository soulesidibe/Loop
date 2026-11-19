package loop.core.orchestrator

import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * m3-11: a runner reports usage absolute for its session, so a rotation's new session starts at 0. [SessionSpend]
 * adds the last totals of the closed sessions, so the attempt's totals never drop at a rotation.
 */
class SessionSpendTest {
    private val session = SessionId("th-tu")
    private val turn = TurnId("tu")

    private fun usage(
        input: Long,
        output: Long,
        cacheRead: Long,
        cacheCreation: Long,
        cost: Double?,
    ) = RunnerEvent.Usage(session, turn, input, output, cacheRead, cacheCreation, cost)

    @Test
    fun `m3-11 within one session a report passes as it is`() {
        val spend = SessionSpend()

        assertEquals(usage(1, 2, 3, 4, 0.5), spend.usage(usage(1, 2, 3, 4, 0.5)))
        assertEquals(usage(10, 20, 30, 40, 0.75), spend.usage(usage(10, 20, 30, 40, 0.75)))
    }

    @Test
    fun `m3-11 after a rotation the closed session's last totals are added to every report`() {
        val spend = SessionSpend()
        spend.usage(usage(1, 2, 3, 4, 0.5))
        spend.usage(usage(10, 20, 30, 40, 0.75))

        spend.closeSession()

        assertEquals(usage(11, 22, 33, 44, 1.0), spend.usage(usage(1, 2, 3, 4, 0.25)))
        assertEquals(usage(15, 25, 35, 45, 1.25), spend.usage(usage(5, 5, 5, 5, 0.5)))
        spend.closeSession()
        assertEquals(usage(16, 26, 36, 46, 1.35), spend.usage(usage(1, 1, 1, 1, 0.1)))
    }

    @Test
    fun `m3-11 a report without a cost stays without one, a closed session that reported none adds no cost`() {
        val spend = SessionSpend()
        spend.usage(usage(1, 1, 1, 1, null))
        spend.closeSession()

        assertNull(spend.usage(usage(1, 1, 1, 1, null)).totalCostUsd)
        assertEquals(0.5, spend.usage(usage(1, 1, 1, 1, 0.5)).totalCostUsd)
    }

    @Test
    fun `m3-11 a session closed before any report carries nothing, and estimates get the same carry`() {
        val spend = SessionSpend()
        spend.closeSession()
        spend.usage(usage(100, 0, 0, 0, null))
        spend.closeSession()

        val estimate = spend.estimate(RunnerEvent.UsageEstimate(session, turn, 1, 2, 3, 4))

        assertEquals(RunnerEvent.UsageEstimate(session, turn, 101, 2, 3, 4), estimate)
    }
}
