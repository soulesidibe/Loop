package loop.core.orchestrator

import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import loop.core.workflow.BudgetsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The attempt's token total (m1-08): one latest absolute total, never a sum of reports. */
class TokenMeterTest {
    private fun usage(
        turn: Int,
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheCreation: Long = 0,
    ): RunnerEvent.Usage =
        RunnerEvent.Usage(SessionId("th1-tu$turn"), TurnId("tu$turn"), input, output, cacheRead, cacheCreation, totalCostUsd = null)

    @Test
    fun `repeated identical totals count once`() {
        val meter = TokenMeter()

        repeat(3) { meter.record(usage(turn = 1, input = 100, output = 50)) }

        assertEquals(150, meter.total)
    }

    @Test
    fun `a later turn's totals replace the earlier ones across session ids`() {
        val meter = TokenMeter()

        meter.record(usage(turn = 1, input = 100, output = 50))
        meter.record(usage(turn = 2, input = 220, output = 90))

        assertEquals(310, meter.total)
    }

    @Test
    fun `cache reads and cache creation count`() {
        val meter = TokenMeter()

        meter.record(usage(turn = 1, input = 1, output = 2, cacheRead = 30, cacheCreation = 400))

        assertEquals(433, meter.total)
    }

    @Test
    fun `a negative component is clamped to zero`() {
        val meter = TokenMeter()

        meter.record(usage(turn = 1, input = 60, output = -500, cacheRead = 10))

        assertEquals(70, meter.total)
    }

    @Test
    fun `an overflowing sum saturates and breaches the per attempt token budget`() {
        val meter = TokenMeter()

        meter.record(usage(turn = 1, input = Long.MAX_VALUE, output = 5, cacheRead = Long.MAX_VALUE))

        assertEquals(Long.MAX_VALUE, meter.total)
        val budgets = BudgetMeter(BudgetsConfig(perAttemptTokens = 100))
        assertEquals(Budget.PER_ATTEMPT_TOKENS, budgets.check(Spend(meter.total), Spend(), Spend()).breach?.budget)
    }
}
