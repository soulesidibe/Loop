package loop.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The run level hold of a runner that cannot reach its model (bl-39), on a fake clock. */
class RunnerHoldTest {
    private val clock = MutableClock(FIXED_NOW)
    private val hold = RunnerHold(clock)

    private fun unavailable(
        issueId: String = "a",
        retryAfter: Duration? = null,
    ) = hold.onUnavailable(issueId, REASON, retryAfter)

    /** The issue fails as the probe of the period just due, then the attempt ends. */
    private fun probeFails(issueId: String = "a"): RunnerHold.Outcome {
        clock.now = assertNotNull(hold.probeAt)
        assertEquals(RunnerHold.Gate.Probe, hold.gate())
        hold.probeStarted(issueId)
        val outcome = unavailable(issueId)
        hold.onAttemptEnded(issueId, unavailable = true)
        return outcome
    }

    @Test
    fun `without a hold every dispatch is allowed`() {
        assertEquals(RunnerHold.Gate.Open, hold.gate())
        assertNull(hold.probeAt)
    }

    @Test
    fun `the first failure holds for 1 minute and dispatches nothing until the probe is due`() {
        val outcome = assertIs<RunnerHold.Outcome.Held>(unavailable())

        val period = assertNotNull(outcome.started)
        assertEquals(1, period.number)
        assertEquals(1.minutes, period.delay)
        assertEquals(FIXED_NOW + 1.minutes, period.probeAt)
        assertEquals(REASON, period.reason)
        assertEquals(FIXED_NOW + 1.minutes, outcome.dueAt)
        clock.now = FIXED_NOW + 59_999.milliseconds
        assertEquals(RunnerHold.Gate.Closed, hold.gate())
        clock.now = FIXED_NOW + 1.minutes
        assertEquals(RunnerHold.Gate.Probe, hold.gate())
    }

    @Test
    fun `each failed probe doubles the hold up to 15 minutes, and it waits indefinitely`() {
        unavailable()
        val delays = (1..10).map { assertNotNull(assertIs<RunnerHold.Outcome.Held>(probeFails()).started).delay }

        assertEquals(listOf(2, 4, 8, 15, 15, 15, 15, 15, 15, 15).map { it.minutes }, delays)
        assertEquals(11, hold.holds)
    }

    @Test
    fun `nothing else dispatches while the probe runs`() {
        unavailable()
        clock.now = FIXED_NOW + 1.minutes

        hold.probeStarted("b")

        assertEquals(RunnerHold.Gate.Closed, hold.gate())
    }

    @Test
    fun `a retry-after longer than the backoff sets the hold, a shorter one does not`() {
        val long = assertNotNull(assertIs<RunnerHold.Outcome.Held>(unavailable(retryAfter = 5.minutes)).started)
        assertEquals(5.minutes, long.delay)
        assertEquals(5.minutes, long.retryAfter)

        clock.now = long.probeAt
        hold.probeStarted("a")
        val short = assertNotNull(assertIs<RunnerHold.Outcome.Held>(unavailable(retryAfter = 30.seconds)).started)
        assertEquals(2.minutes, short.delay)
    }

    @Test
    fun `a running attempt that fails before the probe is due falls into the same hold`() {
        unavailable("a")
        clock.now = FIXED_NOW + 20.seconds

        val outcome = assertIs<RunnerHold.Outcome.Held>(unavailable("b"))

        assertNull(outcome.started)
        assertEquals(FIXED_NOW + 1.minutes, outcome.dueAt)
        assertEquals(1, hold.holds)
    }

    @Test
    fun `a failure after the probe time passed starts the next hold, even when it is not the probe`() {
        unavailable("a")
        clock.now = FIXED_NOW + 90.seconds

        val period = assertNotNull(assertIs<RunnerHold.Outcome.Held>(unavailable("b")).started)

        assertEquals(2, period.number)
        assertEquals(2.minutes, period.delay)
    }

    @Test
    fun `a successful turn lifts the hold and resets its backoff`() {
        unavailable()
        probeFails()
        clock.now = FIXED_NOW + 5.minutes

        val lifted = assertNotNull(hold.onTurnSucceeded("b"))

        assertEquals(2, lifted.holds)
        assertEquals(5.minutes, lifted.held)
        assertEquals(RunnerHold.Gate.Open, hold.gate())
        assertNull(hold.onTurnSucceeded("b"), "a second success lifts nothing")
        val next = assertNotNull(assertIs<RunnerHold.Outcome.Held>(unavailable("c")).started)
        assertEquals(1, next.number)
        assertEquals(1.minutes, next.delay)
    }

    @Test
    fun `a probe that ends without a verdict makes the next probe due at once, without doubling`() {
        unavailable("a")
        clock.now = FIXED_NOW + 1.minutes
        hold.probeStarted("b")
        clock.now = FIXED_NOW + 2.minutes

        hold.onAttemptEnded("b", unavailable = false)

        assertEquals(RunnerHold.Gate.Probe, hold.gate())
        assertEquals(FIXED_NOW + 2.minutes, hold.probeAt)
        assertEquals(1, hold.holds)
    }

    @Test
    fun `the third hit in a row while another issue succeeds is an ordinary failure, and so are later ones`() {
        unavailable("a")
        hold.onAttemptEnded("a", unavailable = true)
        hold.onTurnSucceeded("b")
        assertIs<RunnerHold.Outcome.Held>(unavailable("a"))
        hold.onAttemptEnded("a", unavailable = true)
        hold.onTurnSucceeded("b")

        assertEquals(RunnerHold.Outcome.Ordinary, unavailable("a"))
        hold.onAttemptEnded("a", unavailable = true)
        assertEquals(RunnerHold.Outcome.Ordinary, unavailable("a"))
    }

    @Test
    fun `three hits in a row with no other success keep holding`() {
        repeat(5) { assertIs<RunnerHold.Outcome.Held>(probeFailsOrFirst("a")) }
    }

    @Test
    fun `another end of the issue breaks the row`() {
        unavailable("a")
        hold.onAttemptEnded("a", unavailable = true)
        hold.onTurnSucceeded("b")
        unavailable("a")
        hold.onAttemptEnded("a", unavailable = true)
        hold.onTurnSucceeded("b")
        hold.onAttemptEnded("a", unavailable = false)

        assertIs<RunnerHold.Outcome.Held>(unavailable("a"))
    }

    @Test
    fun `the issue's own successful turn breaks the row`() {
        unavailable("a")
        hold.onTurnSucceeded("b")
        unavailable("a")
        hold.onTurnSucceeded("a")

        assertIs<RunnerHold.Outcome.Held>(unavailable("a"))
    }

    private fun probeFailsOrFirst(issueId: String): RunnerHold.Outcome =
        if (hold.probeAt ==
            null
        ) {
            unavailable(issueId)
        } else {
            probeFails(issueId)
        }

    private class MutableClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private companion object {
        const val REASON = "turn_failed: Failed to authenticate. API Error: 403"
    }
}
