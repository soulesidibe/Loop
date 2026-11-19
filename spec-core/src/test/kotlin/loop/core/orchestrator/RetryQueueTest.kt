package loop.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class RetryQueueTest {
    private val now = Instant.parse("2026-11-07T13:48:40Z")
    private val queue = RetryQueue()

    private fun fail(
        at: Instant = now,
        id: String = "1",
        error: String = "exit_code=1",
        maxAttempts: Int = 5,
        maxBackoffMs: Long = 300_000L,
    ): FailureDecision = queue.onFailure(id, "t-$id", error, at, maxAttempts, maxBackoffMs)

    private fun retry(decision: FailureDecision): FailureDecision.Retry = assertIs<FailureDecision.Retry>(decision)

    private fun normalExit(
        progressed: Boolean = false,
        id: String = "1",
        maxContinuations: Int = 3,
    ): ContinuationDecision = queue.onNormalExit(id, progressed, maxContinuations)

    @Test
    fun `backoff doubles from ten seconds`() {
        val delays = (1..6).map { RetryQueue.backoffMs(it, Long.MAX_VALUE) }

        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 320_000L), delays)
    }

    @Test
    fun `backoff is capped by the max`() {
        assertEquals(300_000L, RetryQueue.backoffMs(6, 300_000L))
        assertEquals(15_000L, RetryQueue.backoffMs(2, 15_000L))
        assertEquals(5_000L, RetryQueue.backoffMs(1, 5_000L))
    }

    @Test
    fun `backoff does not overflow for large attempts`() {
        assertEquals(10_000L shl 49, RetryQueue.backoffMs(50, Long.MAX_VALUE))
        assertEquals(300_000L, RetryQueue.backoffMs(40, 300_000L))
        for (attempt in listOf(51, 54, 63, 64, 100, 1_000, Int.MAX_VALUE)) {
            assertEquals(300_000L, RetryQueue.backoffMs(attempt, 300_000L), "attempt $attempt")
            assertEquals(Long.MAX_VALUE, RetryQueue.backoffMs(attempt, Long.MAX_VALUE), "attempt $attempt")
        }
    }

    @Test
    fun `reschedule moves a due entry to the next attempt with the error and keeps the failure count`() {
        retry(fail(error = "exit_code=1"))
        val at = now + 10_000.milliseconds

        val entry = queue.reschedule("1", at, maxBackoffMs = 300_000L, error = "no available orchestrator slots")

        assertEquals(
            RetryEntry("1", "t-1", attempt = 2, dueAt = at + 20_000.milliseconds, error = "no available orchestrator slots"),
            entry,
        )
        assertEquals(1, queue.failures("1"))
        assertEquals(entry, queue.due(at + 20_000.milliseconds).single())
        assertNull(queue.reschedule("9", at, maxBackoffMs = 300_000L, error = "x"))
    }

    @Test
    fun `requeue keeps the attempt and the first due time and moves the due time by the delay`() {
        val first = retry(fail(error = "exit_code=1")).entry
        val at = now + 15_000.milliseconds

        val entry = queue.requeue("1", at, delayMs = 1_000L, error = "no available orchestrator slots")

        assertEquals(
            RetryEntry(
                "1",
                "t-1",
                attempt = 1,
                dueAt = at + 1_000.milliseconds,
                error = "no available orchestrator slots",
                firstDueAt = first.dueAt,
            ),
            entry,
        )
        assertEquals(1, queue.failures("1"))
        val again = queue.requeue("1", at + 1_000.milliseconds, delayMs = 1_000L, error = "no available orchestrator slots")
        assertEquals(first.dueAt, again?.firstDueAt)
        assertEquals(1, again?.attempt)
        assertNull(queue.requeue("9", at, delayMs = 1_000L, error = "x"))
    }

    @Test
    fun `a new schedule starts a new first due time`() {
        val first = retry(fail(error = "exit_code=1")).entry
        queue.requeue("1", now + 10_000.milliseconds, delayMs = 1_000L, error = "no available orchestrator slots")

        val second = retry(fail(error = "exit_code=1", at = now + 20_000.milliseconds)).entry

        assertEquals(first.dueAt, first.firstDueAt)
        assertEquals(second.dueAt, second.firstDueAt)
    }

    @Test
    fun `a first failure schedules attempt one after ten seconds`() {
        val decision = retry(fail(error = "exit_code=1"))

        assertEquals(
            RetryEntry("1", "t-1", attempt = 1, dueAt = now + 10_000.milliseconds, error = "exit_code=1"),
            decision.entry,
        )
        assertEquals(10_000L, decision.delayMs)
        assertEquals(1, decision.failures)
        assertEquals(1, queue.failures("1"))
    }

    @Test
    fun `each failure increments the attempt and the failure counter`() {
        val decisions = (0 until 4).map { retry(fail(at = now + (it * 1_000).milliseconds)) }

        assertEquals(listOf(1, 2, 3, 4), decisions.map { it.entry.attempt })
        assertEquals(listOf(1, 2, 3, 4), decisions.map { it.failures })
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L), decisions.map { it.delayMs })
        assertEquals(now + 3_000.milliseconds + 80_000.milliseconds, decisions.last().entry.dueAt)
    }

    @Test
    fun `the delay is capped by the max backoff passed on the call`() {
        retry(fail(maxBackoffMs = 15_000L))
        val second = retry(fail(maxBackoffMs = 15_000L))
        val third = retry(fail(maxBackoffMs = 300_000L))

        assertEquals(15_000L, second.delayMs)
        assertEquals(40_000L, third.delayMs)
    }

    @Test
    fun `the failure that reaches max attempts parks the issue`() {
        repeat(4) { retry(fail()) }

        val decision = fail(error = "exit_code=2")

        assertEquals(FailureDecision.Park(failures = 5, error = "exit_code=2"), decision)
        assertEquals(0, queue.failures("1"))
        assertTrue(queue.due(now + 1_000_000.milliseconds).isEmpty())
        assertFalse(queue.isWaiting("1", now))
        assertNull(queue.nextDueAt())
    }

    @Test
    fun `max attempts is read on every call`() {
        retry(fail(maxAttempts = 5))
        retry(fail(maxAttempts = 5))

        assertIs<FailureDecision.Park>(fail(maxAttempts = 3))
    }

    @Test
    fun `max attempts of one parks on the first failure`() {
        assertEquals(FailureDecision.Park(failures = 1, error = "exit_code=1"), fail(maxAttempts = 1))
    }

    @Test
    fun `after a park the next failure starts again at attempt one`() {
        repeat(5) { fail() }

        val decision = retry(fail())

        assertEquals(1, decision.entry.attempt)
        assertEquals(1, decision.failures)
    }

    @Test
    fun `a normal exit resets the failure counter and the attempt`() {
        retry(fail())
        retry(fail())

        normalExit(progressed = true)

        assertEquals(0, queue.failures("1"))
        assertNull(queue.nextDueAt())
        val decision = retry(fail())
        assertEquals(1, decision.entry.attempt)
        assertEquals(1, decision.failures)
    }

    @Test
    fun `normal exits without progress continue then park at the limit`() {
        assertEquals(ContinuationDecision.Continue(noProgress = 1), normalExit())
        assertEquals(ContinuationDecision.Continue(noProgress = 2), normalExit())
        assertEquals(2, queue.noProgress("1"))

        assertEquals(ContinuationDecision.Park(noProgress = 3), normalExit())

        assertEquals(0, queue.noProgress("1"))
        assertNull(queue.nextDueAt())
        assertEquals(ContinuationDecision.Continue(noProgress = 1), normalExit())
    }

    @Test
    fun `max continuations of one parks on the first normal exit without progress`() {
        assertEquals(ContinuationDecision.Park(noProgress = 1), normalExit(maxContinuations = 1))
    }

    @Test
    fun `max continuations is read on every call`() {
        normalExit(maxContinuations = 5)
        normalExit(maxContinuations = 5)

        assertIs<ContinuationDecision.Park>(normalExit(maxContinuations = 2))
    }

    @Test
    fun `a normal exit with progress resets the no progress count to zero`() {
        normalExit()
        normalExit()

        assertEquals(ContinuationDecision.Continue(noProgress = 0), normalExit(progressed = true))

        assertEquals(0, queue.noProgress("1"))
        assertEquals(ContinuationDecision.Continue(noProgress = 1), normalExit())
    }

    @Test
    fun `a normal exit resets the failure counter but keeps the no progress count`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)
        retry(fail())

        assertEquals(ContinuationDecision.Continue(noProgress = 2), normalExit())

        assertEquals(0, queue.failures("1"))
        assertEquals(2, queue.noProgress("1"))
    }

    @Test
    fun `an uncounted normal exit resets the failure counter and keeps the no progress count`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)
        retry(fail())

        assertEquals(1, queue.onUncountedNormalExit("1"))

        assertEquals(0, queue.failures("1"))
        assertEquals(1, queue.noProgress("1"))
        assertNull(queue.entry("1"))
    }

    @Test
    fun `onFailure leaves the no progress count`() {
        normalExit()
        normalExit()

        retry(fail())
        retry(fail())

        assertEquals(2, queue.noProgress("1"))
        assertEquals(2, queue.failures("1"))
    }

    @Test
    fun `a failure park resets the no progress count`() {
        normalExit()
        normalExit()

        assertIs<FailureDecision.Park>(fail(maxAttempts = 1))

        assertEquals(0, queue.noProgress("1"))
    }

    @Test
    fun `no progress counts are kept per issue`() {
        normalExit(id = "1")
        normalExit(id = "1")
        normalExit(id = "2")

        assertEquals(2, queue.noProgress("1"))
        assertEquals(1, queue.noProgress("2"))
        assertEquals(0, queue.noProgress("unknown"))
    }

    @Test
    fun `drop with reset zeroes the no progress count`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)

        queue.drop("1", resetCounter = true)

        assertEquals(0, queue.noProgress("1"))
    }

    @Test
    fun `drop without reset keeps the no progress count`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)

        queue.drop("1", resetCounter = false)

        assertEquals(1, queue.noProgress("1"))
    }

    @Test
    fun `reschedule keeps the no progress count`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)

        queue.reschedule("1", now, maxBackoffMs = 300_000L, error = "no available orchestrator slots")

        assertEquals(1, queue.noProgress("1"))
    }

    @Test
    fun `forgetAbsent resets a no progress count on an issue with no failure count`() {
        normalExit(id = "1")
        normalExit(id = "2")

        queue.forgetAbsent(present = setOf("2"))

        assertEquals(0, queue.noProgress("1"))
        assertEquals(1, queue.noProgress("2"))
    }

    @Test
    fun `forgetAbsent keeps the no progress count of an absent issue with an entry`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)

        queue.forgetAbsent(present = emptySet())

        assertEquals(1, queue.noProgress("1"))
    }

    @Test
    fun `a max continuations park drops the entry and the failure counter`() {
        normalExit()
        queue.scheduleContinuation("1", "t-1", now)
        retry(fail())

        assertIs<ContinuationDecision.Park>(normalExit(maxContinuations = 2))

        assertEquals(0, queue.failures("1"))
        assertFalse(queue.isWaiting("1", now))
        assertNull(queue.nextDueAt())
        assertEquals(1, retry(fail()).entry.attempt)
    }

    @Test
    fun `counters are kept per issue`() {
        retry(fail(id = "1"))
        retry(fail(id = "1"))
        val other = retry(fail(id = "2"))

        assertEquals(1, other.entry.attempt)
        assertEquals(2, queue.failures("1"))
        assertEquals(1, queue.failures("2"))
    }

    @Test
    fun `due returns entries due at now earliest first and keeps the others waiting`() {
        retry(fail(id = "1", at = now))
        retry(fail(id = "2", at = now - 5_000.milliseconds))
        retry(fail(id = "3", at = now + 1_000.milliseconds))

        val at = now + 10_000.milliseconds

        assertEquals(listOf("2", "1"), queue.due(at).map { it.issueId })
        assertTrue(queue.isWaiting("3", at))
        assertFalse(queue.isWaiting("1", at))
        assertFalse(queue.isWaiting("unknown", at))
    }

    @Test
    fun `an entry is waiting before its due time`() {
        retry(fail())

        assertTrue(queue.isWaiting("1", now + 9_999.milliseconds))
        assertTrue(queue.due(now + 9_999.milliseconds).isEmpty())
        assertFalse(queue.isWaiting("1", now + 10_000.milliseconds))
    }

    @Test
    fun `a requeued entry stays due from its first due time and wakes at its moved due time`() {
        retry(fail(id = "1", at = now))
        val at = now + 10_000.milliseconds
        queue.requeue("1", at, delayMs = 30_000L, error = "no available orchestrator slots")
        // Falls due at 11 s, after the first due time of "1" (10 s) and before its moved due time (40 s).
        retry(fail(id = "2", at = now + 1_000.milliseconds))
        val early = now + 11_000.milliseconds

        assertEquals(listOf("1", "2"), queue.due(early).map { it.issueId })
        assertFalse(queue.isWaiting("1", early))
        assertTrue(queue.isWaiting("1", now + 9_999.milliseconds))
        assertEquals(now + 11_000.milliseconds, queue.nextDueAt())
        assertEquals(now + 40_000.milliseconds, queue.nextDueAt(skip = setOf("2")))
    }

    @Test
    fun `next due at is the earliest entry`() {
        assertNull(queue.nextDueAt())
        retry(fail(id = "1", at = now))
        retry(fail(id = "2", at = now - 5_000.milliseconds))

        assertEquals(now + 5_000.milliseconds, queue.nextDueAt())
    }

    @Test
    fun `a refresh failure reschedules attempt plus one without touching the failure counter`() {
        retry(fail())
        val at = now + 10_000.milliseconds

        val entry = queue.onRefreshFailed("1", at, maxBackoffMs = 300_000L)

        assertEquals(
            RetryEntry("1", "t-1", attempt = 2, dueAt = at + 20_000.milliseconds, error = "retry refresh failed"),
            entry,
        )
        assertEquals(1, queue.failures("1"))
        assertTrue(queue.isWaiting("1", at))
    }

    @Test
    fun `a refresh failure without an entry does nothing`() {
        assertNull(queue.onRefreshFailed("1", now, maxBackoffMs = 300_000L))
        assertNull(queue.nextDueAt())
    }

    @Test
    fun `drop with reset clears the entry and the failure counter`() {
        retry(fail())
        retry(fail())

        queue.drop("1", resetCounter = true)

        assertEquals(0, queue.failures("1"))
        assertNull(queue.nextDueAt())
        assertFalse(queue.isWaiting("1", now))
    }

    @Test
    fun `forget absent zeroes only the counters of absent issues without an entry`() {
        retry(fail(id = "1"))
        queue.drop("1", resetCounter = false)
        retry(fail(id = "2"))
        retry(fail(id = "3"))
        queue.drop("3", resetCounter = false)

        queue.forgetAbsent(present = setOf("3"))

        assertEquals(0, queue.failures("1"))
        assertEquals(1, queue.failures("2"))
        assertEquals(1, queue.failures("3"))
    }

    @Test
    fun `drop without reset keeps the failure counter and restarts the attempt`() {
        retry(fail())
        retry(fail())

        queue.drop("1", resetCounter = false)

        assertEquals(2, queue.failures("1"))
        assertNull(queue.nextDueAt())
        val decision = retry(fail())
        assertEquals(1, decision.entry.attempt)
        assertEquals(3, decision.failures)
    }

    // m2-02: what the queue keeps for one issue goes to the store and comes back after a restart.

    @Test
    fun `snapshot is null for an issue the queue keeps nothing for`() {
        assertNull(queue.snapshot("1"))
        retry(fail())
        queue.drop("1", resetCounter = true)

        assertNull(queue.snapshot("1"))
    }

    @Test
    fun `snapshot holds the entry and both counters`() {
        normalExit(progressed = false)
        val entry = retry(fail(error = "turn_failed")).entry

        assertEquals(RetrySnapshot("1", "t-1", entry, failures = 1, noProgress = 1), queue.snapshot("1"))
    }

    @Test
    fun `snapshot of a dropped entry keeps the counters and the identifier`() {
        retry(fail())
        queue.drop("1", resetCounter = false)

        assertEquals(RetrySnapshot("1", "t-1", entry = null, failures = 1, noProgress = 0), queue.snapshot("1"))
    }

    @Test
    fun `a restored queue fires at the stored due time and counts on from the stored failures`() {
        val entry = retry(fail(error = "turn_failed")).entry
        val restored = RetryQueue()
        restored.restore(checkNotNull(queue.snapshot("1")))

        assertTrue(restored.isWaiting("1", entry.dueAt - 1.milliseconds))
        assertEquals(entry.dueAt, restored.nextDueAt())
        assertEquals(listOf(entry), restored.due(entry.dueAt))
        assertEquals(1, restored.failures("1"))
        val next = assertIs<FailureDecision.Retry>(restored.onFailure("1", "t-1", "turn_failed", entry.dueAt, 5, 300_000L))
        assertEquals(2, next.entry.attempt)
        assertEquals(2, next.failures)
        assertIs<FailureDecision.Park>(restored.onFailure("1", "t-1", "turn_failed", entry.dueAt, maxAttempts = 3, maxBackoffMs = 300_000L))
    }

    @Test
    fun `a restored no progress count parks at max continuations`() {
        normalExit(progressed = false)
        val restored = RetryQueue()
        restored.restore(checkNotNull(queue.snapshot("1")))

        assertIs<ContinuationDecision.Park>(restored.onNormalExit("1", progressed = false, maxContinuations = 2))
    }

    @Test
    fun `forgetAbsent returns the issues it forgot`() {
        retry(fail(id = "1"))
        queue.drop("1", resetCounter = false)
        retry(fail(id = "2"))

        assertEquals(setOf("1"), queue.forgetAbsent(present = emptySet()))
        assertNull(queue.snapshot("1"))
    }
}
