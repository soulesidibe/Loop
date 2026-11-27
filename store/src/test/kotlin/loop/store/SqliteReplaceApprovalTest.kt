package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * bl-250: Loop replaces or removes an approval wait row only while the store still holds the wait it read, same reason,
 * since, attempt id and decision, in one write transaction, so a `loop send-back` or a `loop approve` committed since the
 * read is never overwritten. The call returns the row as the store holds it after it.
 */
class SqliteReplaceApprovalTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val at = Instant.parse("2026-11-26T10:00:00Z")

    private fun open(): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow()

    private fun waiting(
        issueId: String = "1",
        reason: WaitReason = WaitReason.Approval,
        data: Map<String, String> = mapOf("to" to "review"),
    ) = WaitingIssue(
        projectId = project,
        issueId = issueId,
        identifier = "t-$issueId",
        reason = reason,
        attemptId = AttemptId("attempt-$issueId"),
        runId = RunId("run-1"),
        since = Instant.parse("2026-11-26T09:00:00Z"),
        data = data,
    )

    /** `t-1` approved, as Loop reads it on a tick, beside an unrelated wait of `t-2`. */
    private suspend fun SqliteEventStore.approvedRead(): WaitingIssue {
        startWaiting(waiting()).getOrThrow()
        startWaiting(waiting("2")).getOrThrow()
        return checkNotNull(approve(project, "1", by = "soule", at = at).getOrThrow())
    }

    private suspend fun SqliteEventStore.row(issueId: String): WaitingIssue? =
        waitingIssues(project).getOrThrow().singleOrNull { it.issueId == issueId }

    @Test
    fun `an unchanged row is replaced and the replacement returned`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()
                val commented = read.copy(data = read.data + ("pull_request" to "https://github.com/acme/widgets/pull/7"))

                assertEquals(commented, store.replaceApproval(read, commented).getOrThrow())
                assertEquals(commented, store.row("1"))
                assertEquals(waiting("2"), store.row("2"))
            }
        }

    @Test
    fun `an unchanged row is replaced by a wait for another reason`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()
                val merge = read.copy(reason = WaitReason.Merge, since = at, data = read.data + ("phase" to "check"))

                assertEquals(merge, store.replaceApproval(read, merge).getOrThrow())
                assertEquals(merge, store.row("1"))
            }
        }

    @Test
    fun `an unchanged row is removed and null returned`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()

                assertNull(store.replaceApproval(read, null).getOrThrow())
                assertNull(store.row("1"))
                assertEquals(waiting("2"), store.row("2"))
            }
        }

    @Test
    fun `a row sent back since the read is kept as it is and returned, replaced or removed`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()
                val sent = checkNotNull(store.sendBack(project, "1", by = "soule", reason = "Use Save.", at = at).getOrThrow())
                val unapproved = read.copy(data = read.data - WaitingIssue.APPROVED_BY - WaitingIssue.APPROVED_AT)

                assertEquals(sent, store.replaceApproval(read, unapproved).getOrThrow())
                assertEquals(sent, store.row("1"))
                assertEquals(sent, store.replaceApproval(read, null).getOrThrow())
                assertEquals(sent, store.row("1"))
                assertEquals(waiting("2"), store.row("2"))
            }
        }

    @Test
    fun `a row approved since the read is kept as it is and returned`() =
        runTest {
            open().use { store ->
                store.startWaiting(waiting()).getOrThrow()
                val read = checkNotNull(store.row("1"))
                val approved = checkNotNull(store.approve(project, "1", by = "soule", at = at).getOrThrow())

                assertEquals(approved, store.replaceApproval(read, null).getOrThrow())
                assertEquals(approved, store.row("1"))
                assertEquals(approved, store.replaceApproval(read, read.copy(data = read.data + ("x" to "y"))).getOrThrow())
                assertEquals(approved, store.row("1"))
            }
        }

    @Test
    fun `a row started again since the read, another since or another reason, is kept`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()
                val again = read.copy(since = at)
                store.startWaiting(again).getOrThrow()
                assertEquals(again, store.replaceApproval(read, null).getOrThrow())
                assertEquals(again, store.row("1"))

                val merge = read.copy(reason = WaitReason.Merge)
                store.startWaiting(merge).getOrThrow()
                assertEquals(merge, store.replaceApproval(read, null).getOrThrow())
                assertEquals(merge, store.row("1"))
            }
        }

    @Test
    fun `a missing row writes nothing and returns null`() =
        runTest {
            open().use { store ->
                val read = store.approvedRead()
                store.endWaiting(project, "1").getOrThrow()

                assertNull(store.replaceApproval(read, read).getOrThrow())
                assertNull(store.row("1"))
                assertNull(store.replaceApproval(read, null).getOrThrow())
                assertEquals(listOf(waiting("2")), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `the decision is kept across a reopen of the store`() =
        runTest {
            val read = open().use { it.approvedRead() }
            open().use { it.sendBack(project, "1", by = "soule", reason = "Use Save.", at = at).getOrThrow() }
            open().use { store ->
                val kept = checkNotNull(store.replaceApproval(read, null).getOrThrow())
                assertEquals("Use Save.", kept.data[WaitingIssue.SENT_BACK_REASON])
                assertEquals(kept, store.row("1"))
            }
        }
}
