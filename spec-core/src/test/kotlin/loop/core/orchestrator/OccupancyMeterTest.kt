package loop.core.orchestrator

import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** m3-11: the soft limit is crossed when the session's latest occupancy reaches the limit times its model's window. */
class OccupancyMeterTest {
    private val windows = mapOf(MODEL to 1_000_000L)

    private fun meter(
        vararg tokens: Long,
        model: String? = MODEL,
    ): OccupancyMeter =
        OccupancyMeter().apply {
            tokens.forEach { record(RunnerEvent.ContextUsage(SessionId("s"), TurnId("t"), it, model)) }
        }

    @Test
    fun `m3-11 the latest report at or above the limit crosses it, below does not`() {
        assertEquals(OccupancyMeter.Crossing(200_000, 1_000_000), meter(200_000).crossing(windows, 0.20))
        assertEquals(OccupancyMeter.Crossing(250_000, 1_000_000), meter(250_000).crossing(windows, 0.20))
        assertNull(meter(199_999).crossing(windows, 0.20))
        assertEquals(0.25, OccupancyMeter.Crossing(250_000, 1_000_000).fraction)
    }

    @Test
    fun `m3-11 the latest report decides, not the peak`() {
        assertNull(meter(300_000, 100_000).crossing(windows, 0.20))
    }

    @Test
    fun `m3-11 no report, no model or a model without a window never crosses`() {
        assertNull(OccupancyMeter().crossing(windows, 0.20))
        assertNull(meter(900_000, model = null).crossing(windows, 0.20))
        assertNull(meter(900_000, model = "anthropic/unknown").crossing(windows, 0.20))
    }

    private companion object {
        const val MODEL = "anthropic/claude-opus-5-5"
    }
}
