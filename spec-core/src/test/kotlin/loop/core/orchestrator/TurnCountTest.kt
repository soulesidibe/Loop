package loop.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** m4-03: wake turns never count against `agent.max_turns`, and `agent.max_wake_turns` caps them. */
class TurnCountTest {
    @Test
    fun `the six turns of attempt 10c2f759 leave 2 of 10 turns used after turn 6`() {
        // Turns 2, 3, 4 and 6 read the notification of a background run started in an earlier turn (the replay in
        // WakeTurnTest); turns 1 and 5 are ordinary.
        val count = TurnCount(maxTurns = 10, maxWakeTurns = 30)
        val ends = listOf(false, true, true, true, false, true).map { wake -> count.end(wake) }

        assertEquals(2, count.counted)
        assertEquals(4, count.wakes)
        assertEquals(List(6) { null }, ends)
        assertEquals(3, count.next, "the next ordinary turn is turn 3 of 10")
    }

    @Test
    fun `ordinary turns reach max_turns, wake turns in between do not`() {
        val count = TurnCount(maxTurns = 2, maxWakeTurns = 30)

        assertNull(count.end(wake = false))
        assertNull(count.end(wake = true))
        assertNull(count.end(wake = true))
        assertEquals(ExitReason.MAX_TURNS, count.end(wake = false))
    }

    @Test
    fun `wake turns reach max_wake_turns, ordinary turns do not count toward it`() {
        val count = TurnCount(maxTurns = 10, maxWakeTurns = 2)

        assertNull(count.end(wake = true))
        assertNull(count.end(wake = false))
        assertEquals(ExitReason.MAX_WAKE_TURNS, count.end(wake = true))
    }

    @Test
    fun `both limits are exit reasons a refutation replaces, nothing else is`() {
        assertEquals(true, TurnCount.isLimit(ExitReason.MAX_TURNS))
        assertEquals(true, TurnCount.isLimit(ExitReason.MAX_WAKE_TURNS))
        assertEquals(false, TurnCount.isLimit(ExitReason.NOT_ACTIVE))
        assertEquals(false, TurnCount.isLimit(null))
    }

    @Test
    fun `limits below 1 are refused`() {
        assertFailsWith<IllegalArgumentException> { TurnCount(maxTurns = 0, maxWakeTurns = 30) }
        assertFailsWith<IllegalArgumentException> { TurnCount(maxTurns = 10, maxWakeTurns = 0) }
    }
}
