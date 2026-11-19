package loop.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

/** The token thresholds of an attempt (m1-38): each step is reported once, in order, and never stops anything. */
class TokenThresholdsTest {
    @Test
    fun `nothing is crossed below the first step`() {
        val thresholds = TokenThresholds(every = 10_000_000)

        assertEquals(emptyList(), thresholds.crossed(9_999_999))
    }

    @Test
    fun `exactly a step crosses it`() {
        val thresholds = TokenThresholds(every = 10_000_000)

        assertEquals(listOf(10_000_000L), thresholds.crossed(10_000_000))
    }

    @Test
    fun `a jump over several steps gives each step once, in order`() {
        val thresholds = TokenThresholds(every = 10_000_000)

        assertEquals(emptyList(), thresholds.crossed(9_000_000))
        assertEquals(listOf(10_000_000L, 20_000_000L, 30_000_000L), thresholds.crossed(31_000_000))
    }

    @Test
    fun `a step is never given twice, also when the total drops and climbs again`() {
        val thresholds = TokenThresholds(every = 10_000_000)

        assertEquals(listOf(10_000_000L), thresholds.crossed(10_200_000))
        assertEquals(emptyList(), thresholds.crossed(9_900_000))
        assertEquals(emptyList(), thresholds.crossed(10_100_000))
        assertEquals(listOf(20_000_000L), thresholds.crossed(20_000_001))
    }

    @Test
    fun `steps already recorded before a restart are not given again`() {
        val thresholds = TokenThresholds(every = 10_000_000, recorded = 20_000_000)

        assertEquals(listOf(30_000_000L), thresholds.crossed(31_000_000))
    }

    @Test
    fun `zero turns thresholds off`() {
        val thresholds = TokenThresholds(every = 0)

        assertEquals(emptyList(), thresholds.crossed(Long.MAX_VALUE))
    }

    @Test
    fun `one report gives at most a bounded number of steps, the rest come with the next report`() {
        val thresholds = TokenThresholds(every = 1)

        val first = thresholds.crossed(Long.MAX_VALUE)
        assertEquals((1L..TokenThresholds.MAX_STEPS_PER_REPORT).toList(), first)
        assertEquals(TokenThresholds.MAX_STEPS_PER_REPORT + 1, thresholds.crossed(Long.MAX_VALUE).first())
    }
}
