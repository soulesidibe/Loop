package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * bl-84: the one reading of what was spent against the issue and day budgets, for the attempt and the auto merge
 * gate alike: the ledger rows and the attempts still running in this process, each scope apart.
 */
class SpendReadingTest {
    private val project = ProjectId("p1")
    private val now = FIXED_NOW
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val live = LinkedHashMap<AttemptId, LiveSpend>()
    private val ledger = Ledger()
    private val reading = SpendReading(ledger, project, clock) { live.toMap() }

    @Test
    fun `an attempt whose row is written while it is still in the live map is counted once`() =
        runTest {
            ledger.rows += row("a1", "id-i-1", Spend(500, 0.5))
            live[AttemptId("a1")] = LiveSpend("id-i-1", Spend(500, 0.5))

            val read = reading.read("id-i-1", day = true)

            assertEquals(Spend(), read.issue?.ledger)
            assertEquals(Spend(500, 0.5), read.issue?.live)
            assertEquals(Spend(), read.day?.ledger)
            assertEquals(Spend(500, 0.5), read.day?.live)
        }

    @Test
    fun `the live map is read before the ledger, so an attempt that ends during the read is counted once`() =
        runTest {
            live[AttemptId("a1")] = LiveSpend("id-i-1", Spend(500, 0.5))
            // The attempt ends between the two reads: its row is written, then it leaves the live map.
            ledger.onRead = {
                ledger.rows += row("a1", "id-i-1", Spend(500, 0.5))
                live.remove(AttemptId("a1"))
            }

            val read = reading.read(null, day = true)

            assertEquals(Spend(), read.day?.ledger)
            assertEquals(Spend(500, 0.5), read.day?.live)
        }

    @Test
    fun `the ledger part counts the finished attempts, the live part the running ones, the issue only its own`() =
        runTest {
            ledger.rows += row("old", "id-i-1", Spend(100, 1.0))
            ledger.rows += row("other", "id-i-2", Spend(200, 2.0))
            ledger.rows += row("yesterday", "id-i-1", Spend(400, 4.0), finishedAt = now - 25.hours)
            live[AttemptId("a2")] = LiveSpend("id-i-2", Spend(20, 0.25))

            val read = reading.read("id-i-1", day = true, own = AttemptId("a1") to LiveSpend("id-i-1", Spend(10, 0.5)))

            assertEquals(Spend(500, 5.0), read.issue?.ledger)
            assertEquals(Spend(10, 0.5), read.issue?.live)
            assertEquals(Spend(300, 3.0), read.day?.ledger)
            assertEquals(Spend(30, 0.75), read.day?.live)
            assertEquals(Spend(330, 3.75), read.day?.total())
        }

    @Test
    fun `the attempt's own spend replaces what the live map holds of it`() =
        runTest {
            live[AttemptId("a1")] = LiveSpend("id-i-1", Spend(10, 0.1))

            val read = reading.read(null, day = true, own = AttemptId("a1") to LiveSpend("id-i-1", Spend(50, 0.5)))

            assertEquals(Spend(50, 0.5), read.day?.live)
        }

    @Test
    fun `only the scopes asked are read`() =
        runTest {
            assertEquals(SpendRead(null, null), reading.read(null, day = false))
            assertEquals(0, ledger.reads)

            val issueOnly = reading.read("id-i-1", day = false)
            assertNull(issueOnly.day)
            assertEquals(listOf("issue"), ledger.scopes)

            val dayOnly = reading.read(null, day = true)
            assertNull(dayOnly.issue)
            assertEquals(listOf("issue", "day"), ledger.scopes)
        }

    @Test
    fun `a failed read leaves that scope's ledger part out with the failure, the other scope and the live part stay`() =
        runTest {
            ledger.rows += row("old", "id-i-1", Spend(100, 1.0))
            live[AttemptId("a1")] = LiveSpend("id-i-1", Spend(10, 0.5))
            val failure = IllegalStateException("database is locked")
            ledger.issueFailure = failure

            val read = reading.read("id-i-1", day = true)

            assertNull(read.issue?.ledger)
            assertSame(failure, read.issue?.failure)
            assertEquals(Spend(10, 0.5), read.issue?.live)
            assertEquals(Spend(100, 1.0), read.day?.ledger)
            assertNull(read.day?.failure)
        }

    @Test
    fun `a read that throws is a failed read`() =
        runTest {
            ledger.throwing = IllegalStateException("disk gone")

            val read = reading.read("id-i-1", day = true)

            assertNull(read.issue?.ledger)
            assertEquals("disk gone", read.issue?.failure?.message)
            assertNull(read.day?.ledger)
            assertEquals("disk gone", read.day?.failure?.message)
        }

    private val setup = LedgerSetup("files", null, "command", null, "t".repeat(64), null)

    private fun row(
        id: String,
        issueId: String,
        spend: Spend,
        finishedAt: Instant = now - 1.hours,
    ): LedgerRow =
        LedgerRow(
            LedgerAttempt(
                projectId = project,
                runId = RunId("r0"),
                issueId = issueId,
                identifier = issueId.removePrefix("id-"),
                attemptId = AttemptId(id),
                setup = setup,
                startedAt = finishedAt - 10.minutes,
                finishedAt = finishedAt,
                outcome = "succeeded",
                reason = null,
                turns = 1,
                issueState = "review",
                tokens = LedgerTokens(spend.tokens, 0, 0, 0),
                costUsd = spend.usd,
                checks = null,
                guards = null,
                verdict = null,
            ),
            1,
            0,
        )

    private class Ledger : AttemptLedger {
        val rows = mutableListOf<LedgerRow>()
        val scopes = mutableListOf<String>()
        val reads: Int get() = scopes.size
        var onRead: () -> Unit = {}
        var issueFailure: Throwable? = null
        var throwing: Throwable? = null

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> = Result.success(Unit)

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> {
            scopes += "issue"
            onRead()
            throwing?.let { throw it }
            issueFailure?.let { return Result.failure(it) }
            return Result.success(rows.filter { it.attempt.issueId == issueId })
        }

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> {
            scopes += "day"
            onRead()
            throwing?.let { throw it }
            return Result.success(rows.filter { it.attempt.finishedAt > since })
        }
    }
}
