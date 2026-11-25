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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m4-32: `loop send-back` marks a `Waiting(approval)` row sent back in the store, approved or not, and only that kind of row. */
class SqliteSendBackTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val at = Instant.parse("2026-11-25T10:00:00Z")

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), redactor).getOrThrow()

    private fun waiting(
        reason: WaitReason,
        data: Map<String, String> = mapOf("to" to "review"),
    ) = WaitingIssue(
        projectId = project,
        issueId = "1",
        identifier = "t-1",
        reason = reason,
        attemptId = AttemptId("attempt-1"),
        runId = RunId("run-1"),
        since = Instant.parse("2026-11-25T09:00:00Z"),
        data = data,
    )

    @Test
    fun `an approved wait is sent back once, its approval dropped, and the send back survives reopening the store`() =
        runTest {
            val wait = waiting(WaitReason.Approval, mapOf("to" to "review", WaitingIssue.APPROVED_BY to "a", WaitingIssue.APPROVED_AT to "x"))
            open().use { store ->
                store.startWaiting(wait).getOrThrow()
                val sent = store.sendBack(project, "1", by = "soule", reason = "Use Save.", at = at).getOrThrow()
                assertEquals(wait.copy(data = mapOf("to" to "review") + WaitingIssue.sendBackData("soule", "Use Save.", at)), sent)
                // A second send back and an approve change nothing and return the row as it is.
                assertEquals(sent, store.sendBack(project, "1", by = "other", reason = "Again.", at = at).getOrThrow())
                assertEquals(sent, store.approve(project, "1", by = "other", at = at).getOrThrow())
            }
            open().use { store ->
                val kept = store.waitingIssues(project).getOrThrow().single()
                assertTrue(kept.sentBack)
                assertFalse(kept.approved)
                assertEquals("Use Save.", kept.data[WaitingIssue.SENT_BACK_REASON])
                assertEquals("soule", kept.data[WaitingIssue.SENT_BACK_BY])
            }
        }

    @Test
    fun `a wait for another reason is returned unchanged and no wait at all is null`() =
        runTest {
            open().use { store ->
                assertNull(store.sendBack(project, "1", by = "soule", reason = "No.", at = at).getOrThrow())
                val ask = waiting(WaitReason.Ask, mapOf("state" to "todo"))
                store.startWaiting(ask).getOrThrow()
                assertEquals(ask, store.sendBack(project, "1", by = "soule", reason = "No.", at = at).getOrThrow())
                assertEquals(listOf(ask), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `the reason and who sent it back are redacted before they are written`() =
        runTest {
            val redactor = Redactor { it.replace("hunter2", "[redacted]") }
            open(redactor).use { store ->
                store.startWaiting(waiting(WaitReason.Approval)).getOrThrow()
                store.sendBack(project, "1", by = "soule", reason = "The password is hunter2.", at = at).getOrThrow()
                val kept = store.waitingIssues(project).getOrThrow().single()
                assertFalse("hunter2" in kept.data.getValue(WaitingIssue.SENT_BACK_REASON), kept.toString())
            }
        }
}
