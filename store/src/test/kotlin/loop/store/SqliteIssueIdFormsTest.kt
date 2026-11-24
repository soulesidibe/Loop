package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.Claim
import loop.api.FrozenCommands
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RetryState
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * m4-12: a store written before namespaced ids, its rows holding bare ids, read through [IssueIdForms]. A bare id is
 * the primary tracker's, `default` here; the rule of the test stands in for `loop.core.trackers.NamespacedId`.
 */
class SqliteIssueIdFormsTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))
    private val at = Instant.parse("2026-11-18T10:00:00Z")

    private val forms =
        IssueIdForms(
            read = { if (':' in it) it else "$PRIMARY:$it" },
            legacy = { id -> id.removePrefix("$PRIMARY:").takeIf { id.startsWith("$PRIMARY:") } },
        )

    private fun open(ids: IssueIdForms): SqliteEventStore = SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE, ids).getOrThrow()

    private fun event(
        issueId: String,
        kind: String = "attempt_started",
    ) = RecordedEvent(ts = at, projectId = project, kind = kind, issueId = issueId, issueIdentifier = "t-1")

    private fun attempt(
        id: String,
        issueId: String,
    ) = LedgerAttempt(
        projectId = project,
        runId = RunId("r1"),
        issueId = issueId,
        identifier = "t-1",
        attemptId = AttemptId(id),
        setup = LedgerSetup("files", "code", "claude-code", null, "t".repeat(64), null),
        startedAt = at,
        finishedAt = at,
        outcome = "succeeded",
        reason = null,
        turns = 1,
        issueState = "review",
        tokens = null,
        costUsd = null,
        checks = null,
        guards = null,
        verdict = null,
    )

    private fun parked(issueId: String) = ParkedIssue(project, issueId, "t-1", ParkReason("stalled"), "todo", setOf("a"), at)

    private fun claim(issueId: String) = Claim(project, issueId, "t-1", AttemptId("a-1"), RunId("r1"), at)

    private fun retry(issueId: String) = RetryState(project, issueId, "t-1", QueuedRetry(2, at, "boom"), 1, 0)

    private fun waiting(issueId: String) = WaitingIssue(project, issueId, "t-1", WaitReason.Approval, AttemptId("a-1"), RunId("r1"), at)

    private fun frozen(
        issueId: String,
        digest: String = "d1",
    ) = FrozenCommands(project, issueId, "t-1", digest, 2, at)

    /** The store as a Loop before m4-12 left it: every row of `t-1` under its bare id. */
    private suspend fun writtenBefore() {
        open(IssueIdForms.AS_STORED).use { store ->
            store.record(event("t-1")).getOrThrow()
            store.recordAttempt(attempt("a-0", "t-1")).getOrThrow()
            store.park(parked("t-1")).getOrThrow()
            store.claim(claim("t-1")).getOrThrow()
            store.saveRetryState(retry("t-1")).getOrThrow()
            store.startWaiting(waiting("t-1")).getOrThrow()
            store.freeze(frozen("t-1")).getOrThrow()
        }
    }

    private fun issueIds(table: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT issue_id FROM $table ORDER BY rowid").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `m4-12 rows written before are read with their bare ids as the primary tracker's`() =
        runTest {
            writtenBefore()
            open(forms).use { store ->
                assertEquals(listOf("default:t-1"), store.eventsForIssue(project, "default:t-1").getOrThrow().map { it.event.issueId })
                assertEquals(listOf("default:t-1"), store.attemptRows(project, "default:t-1").getOrThrow().map { it.attempt.issueId })
                assertEquals(listOf("default:t-1"), store.list(project).getOrThrow().map { it.issueId })
                assertEquals(listOf("default:t-1"), store.claims(project).getOrThrow().map { it.issueId })
                assertEquals(listOf("default:t-1"), store.retryStates(project).getOrThrow().map { it.issueId })
                assertEquals(listOf("default:t-1"), store.waitingIssues(project).getOrThrow().map { it.issueId })
                assertEquals("default:t-1", store.frozen(project, "default:t-1").getOrThrow()?.issueId)
                // A bare id given, as a command before m4-47 may, reads the same rows.
                assertEquals(1, store.eventsForIssue(project, "t-1").getOrThrow().size)
            }
            for (table in listOf("events", "attempts", "parked_issues", "claims", "retry_states", "waiting_issues", "acceptance_commands")) {
                assertEquals(listOf("t-1"), issueIds(table), "$table is never rewritten")
            }
        }

    @Test
    fun `m4-12 an extra tracker's issue of the same native id never reads the primary's rows`() =
        runTest {
            writtenBefore()
            open(forms).use { store ->
                assertEquals(emptyList(), store.eventsForIssue(project, "local:t-1").getOrThrow())
                assertEquals(emptyList(), store.attemptRows(project, "local:t-1").getOrThrow())
                assertNull(store.frozen(project, "local:t-1").getOrThrow())
                assertNull(store.unpark(project, "local:t-1").getOrThrow())
                assertNull(store.approve(project, "local:t-1", "soule", at).getOrThrow())
            }
        }

    @Test
    fun `m4-12 a new ledger row and event carry the namespaced id, and the attempt number counts the rows before`() =
        runTest {
            writtenBefore()
            open(forms).use { store ->
                store.record(event("default:t-1", kind = "attempt_finished")).getOrThrow()
                store.recordAttempt(attempt("a-1", "default:t-1")).getOrThrow()
                assertEquals(listOf(1, 2), store.attemptRows(project, "default:t-1").getOrThrow().map { it.number })
                assertEquals(2, store.eventsForIssue(project, "default:t-1").getOrThrow().size)
            }
            assertEquals(listOf("t-1", "default:t-1"), issueIds("events"))
            assertEquals(listOf("t-1", "default:t-1"), issueIds("attempts"))
        }

    @Test
    fun `m4-12 a write to a row held under its bare id updates that row, and a delete removes it`() =
        runTest {
            writtenBefore()
            open(forms).use { store ->
                store.claim(claim("default:t-1").copy(attemptId = AttemptId("a-2"))).getOrThrow()
                store.saveRetryState(retry("default:t-1").copy(failures = 3)).getOrThrow()
                store.park(parked("default:t-1").copy(state = "review")).getOrThrow()
                store.startWaiting(waiting("default:t-1").copy(reason = WaitReason.Verify)).getOrThrow()
                val freeze = store.freeze(frozen("default:t-1", digest = "d2")).getOrThrow()
                assertFalse(freeze.written)
                assertEquals("d1", freeze.stored.digest)
                assertEquals(listOf("t-1"), issueIds("claims"))
                assertEquals(listOf("t-1"), issueIds("retry_states"))
                assertEquals(listOf("t-1"), issueIds("parked_issues"))
                assertEquals(listOf("t-1"), issueIds("waiting_issues"))
                assertEquals(listOf("t-1"), issueIds("acceptance_commands"))
                assertEquals(AttemptId("a-2"), store.claims(project).getOrThrow().single().attemptId)
                assertEquals(3, store.retryStates(project).getOrThrow().single().failures)
                assertEquals("review", store.list(project).getOrThrow().single().state)
                assertEquals(WaitReason.Verify, store.waitingIssues(project).getOrThrow().single().reason)

                store.markChanged(project, "default:t-1", "d3", 4).getOrThrow()
                assertEquals("d3", store.acceptChanged(project, "default:t-1", "d3").getOrThrow()?.digest)
                store.clearChanged(project, "default:t-1").getOrThrow()

                assertNotNull(store.unpark(project, "default:t-1").getOrThrow())
                store.release(project, "default:t-1").getOrThrow()
                store.deleteRetryState(project, "default:t-1").getOrThrow()
                store.endWaiting(project, "default:t-1").getOrThrow()
            }
            for (table in listOf("parked_issues", "claims", "retry_states", "waiting_issues")) {
                assertEquals(emptyList(), issueIds(table), table)
            }
        }

    @Test
    fun `m4-12 an approval wait held under its bare id is approved by its namespaced id`() =
        runTest {
            writtenBefore()
            open(forms).use { store ->
                val approved = assertNotNull(store.approve(project, "default:t-1", "soule", at).getOrThrow())
                assertEquals("default:t-1", approved.issueId)
                assertEquals(true, store.waitingIssues(project).getOrThrow().single().approved)
            }
        }

    @Test
    fun `m4-12 a new issue's rows are written under the namespaced id`() =
        runTest {
            open(forms).use { store ->
                store.claim(claim("local:r-3")).getOrThrow()
                store.freeze(frozen("local:r-3")).getOrThrow()
            }
            assertEquals(listOf("local:r-3"), issueIds("claims"))
            assertEquals(listOf("local:r-3"), issueIds("acceptance_commands"))
        }

    private companion object {
        const val PRIMARY = "default"
    }
}
