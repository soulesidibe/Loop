package loop.core.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import loop.api.AgentSession
import loop.api.AttemptId
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.LedgerTurn
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-04, story 104 (bl-152): what an agent session's stop reads counts. [StopUsage] stops the session, routes its usage
 * and its stop grace expiry, keeps what a failed stop reported, decides when a budget passed at the stop wins over the
 * attempt's end, and writes the `stop` ledger line that keeps the turn lines summing to the attempt total.
 */
class StopUsageTest {
    private val session = SessionId("th1-t1")
    private val turn = TurnId("t1")

    private fun usage(tokens: Long) = RunnerEvent.Usage(session, turn, tokens, 0, 0, 0, tokens / 100.0)

    /** A session whose stop reports [reports], then throws [failure] when set. */
    private fun session(
        reports: List<RunnerEvent>,
        failure: Exception? = null,
    ): AgentSession =
        object : AgentSession {
            override fun runTurn(
                input: String,
                turn: Int,
            ): Flow<RunnerEvent> = emptyFlow()

            override suspend fun stop() {
                failure?.let { throw it }
            }

            override suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
                reports.forEach { report(it) }
                stop()
            }
        }

    private class Seen {
        val usages = mutableListOf<RunnerEvent.Usage>()
        val expired = mutableListOf<RunnerEvent.StopGraceExpired>()
        val failures = mutableListOf<String>()
        val order = mutableListOf<String>()

        fun stopUsage() =
            StopUsage(
                usage = {
                    usages += it
                    order += "usage"
                },
                graceExpired = {
                    expired += it
                    order += "stop_grace_expired"
                },
                failed = {
                    failures += it.message.orEmpty()
                    order += "failed"
                },
            )
    }

    @Test
    fun `a stop gives its usage and its grace expiry in the order the session read them, and no failure`() =
        runTest {
            val seen = Seen()
            val expired = RunnerEvent.StopGraceExpired(session, turn, graceMs = 5000, wake = true, wakeMessages = 2)

            val failure = seen.stopUsage().stop(session(listOf(usage(1_500), expired)))

            assertNull(failure)
            assertEquals(listOf(usage(1_500)), seen.usages)
            assertEquals(listOf(expired), seen.expired)
            assertEquals(listOf("usage", "stop_grace_expired"), seen.order)
        }

    @Test
    fun `a stop that throws after reporting usage keeps that usage and gives the failure`() =
        runTest {
            val seen = Seen()

            val failure = seen.stopUsage().stop(session(listOf(usage(1_500)), IllegalStateException("could not stop group 4242")))

            assertEquals("could not stop group 4242", failure)
            assertEquals(listOf("usage", "failed"), seen.order)
            assertEquals(listOf("could not stop group 4242"), seen.failures)
        }

    @Test
    fun `a session that reads nothing at its stop reports nothing`() =
        runTest {
            val seen = Seen()

            assertNull(seen.stopUsage().stop(session(emptyList())))
            assertEquals(emptyList(), seen.order)
        }

    @Test
    fun `at a rotation a budget passed at the stop wins over a failed stop, which wins over a new session`() {
        assertEquals(StopUsage.End.Budget, StopUsage.rotationEnd(breached = true, stopFailure = "could not stop"))
        assertEquals(StopUsage.End.Budget, StopUsage.rotationEnd(breached = true, stopFailure = null))
        assertEquals(StopUsage.End.StopFailed, StopUsage.rotationEnd(breached = false, stopFailure = "could not stop"))
        assertEquals(StopUsage.End.Next, StopUsage.rotationEnd(breached = false, stopFailure = null))
    }

    @Test
    fun `at the attempt's end a budget passed at the stop wins, but an end that parks or waits for approval keeps its end`() {
        assertTrue(StopUsage.budgetWins(breached = true, parks = false, waitsApproval = false))
        assertFalse(StopUsage.budgetWins(breached = false, parks = false, waitsApproval = false))
        assertFalse(StopUsage.budgetWins(breached = true, parks = true, waitsApproval = false))
        assertFalse(StopUsage.budgetWins(breached = true, parks = false, waitsApproval = true))
    }

    @Test
    fun `stop_grace_expired carries the grace, the wake and the attempt's usage known after the stop`() {
        val expired = RunnerEvent.StopGraceExpired(session, turn, graceMs = 5000, wake = true, wakeMessages = 2)

        assertEquals(
            mapOf(
                "grace_ms" to "5000",
                "wake" to "true",
                "wake_messages" to "2",
                "attempt_tokens" to "9000",
                "total_cost_usd" to "0.09",
            ),
            StopUsage.graceData(expired, attemptTokens = 9_000, costUsd = 0.09),
        )
        // A usage nobody reported is left out.
        val quiet = RunnerEvent.StopGraceExpired(session, turn, graceMs = 5000, wake = false, wakeMessages = 0)
        assertEquals(
            mapOf("grace_ms" to "5000", "wake" to "false", "wake_messages" to "0"),
            StopUsage.graceData(quiet, attemptTokens = null, costUsd = null),
        )
    }

    @Test
    fun `usage read at a stop no turn follows gets a stop line, so the turn lines sum to the attempt total`() {
        val meter = LedgerMeter()
        meter.startTurn()
        meter.usage(usage(1_000))
        val last = turnLine(meter, ts = Instant.parse("2026-11-23T10:00:00Z"))
        // The stop read a wake that brought the session to 1_500.
        meter.usageBetweenTurns(usage(1_500))

        val line = StopUsage.line(last, meter, Instant.parse("2026-11-23T10:00:07Z"))

        checkNotNull(line)
        assertEquals(StopUsage.STOP, line.outcome)
        assertEquals(500L, line.tokens?.total)
        assertEquals(5.0, line.costUsd)
        assertEquals(1_500L, line.attemptTokens?.total)
        assertEquals(15.0, line.attemptCostUsd)
        assertEquals(7_000L, line.wallMs)
        assertEquals(last.turn, line.turn)
        assertEquals(last.turnId, line.turnId)
        assertEquals(last.threadId, line.threadId)
        assertEquals(false, line.wake)
        assertNull(line.interrupted)
        assertEquals(meter.tokens?.total, listOf(last, line).sumOf { it.tokens?.total ?: 0 })
        // Written once: a second call finds nothing held.
        assertNull(StopUsage.line(line, meter, Instant.parse("2026-11-23T10:00:08Z")))
    }

    @Test
    fun `no stop line without usage read at the stop, or before any turn line`() {
        val meter = LedgerMeter()
        meter.startTurn()
        meter.usage(usage(1_000))
        val last = turnLine(meter, ts = Instant.parse("2026-11-23T10:00:00Z"))

        assertNull(StopUsage.line(last, meter, Instant.parse("2026-11-23T10:00:07Z")))
        meter.usageBetweenTurns(usage(1_500))
        assertNull(StopUsage.line(null, meter, Instant.parse("2026-11-23T10:00:07Z")))
    }

    private fun turnLine(
        meter: LedgerMeter,
        ts: Instant,
    ): LedgerTurn =
        LedgerTurn(
            ts = ts,
            projectId = ProjectId("p1"),
            runId = RunId("r1"),
            issueId = "id-m4-04",
            identifier = "m4-04",
            attemptId = AttemptId("a1"),
            sessionId = session,
            turnId = turn,
            turn = 3,
            outcome = "completed",
            setup =
                LedgerSetup(
                    tracker = "files",
                    kind = null,
                    runner = "claude-code",
                    model = null,
                    templateVersion = "t",
                    configVersion = null,
                ),
            tokens = meter.turnTokens(),
            attemptTokens = meter.tokens,
            costUsd = meter.turnCostUsd(),
            attemptCostUsd = meter.costUsd,
            wallMs = 1_000,
            threadId = "th1",
            interrupted = "hard_limit",
            wake = true,
        ).also { check(it.tokens == LedgerTokens(1_000, 0, 0, 0)) }
}
