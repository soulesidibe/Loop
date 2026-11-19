package loop.store

import kotlinx.coroutines.runBlocking
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

/** m2-24: `loop approve` marks a `Waiting(approval)` row approved in the store, and only that kind of row. */
class SqliteApprovalTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val at = Instant.parse("2026-11-13T10:00:00Z")

    private fun open(): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow()

    private fun waiting(reason: WaitReason) =
        WaitingIssue(
            projectId = project,
            issueId = "1",
            identifier = "t-1",
            reason = reason,
            attemptId = AttemptId("attempt-1"),
            runId = RunId("run-1"),
            since = Instant.parse("2026-11-13T09:00:00Z"),
            data = mapOf("to" to "review"),
        )

    @Test
    fun `an approval wait is approved once, the approval survives reopening the store`() =
        runTest {
            val wait = waiting(WaitReason.Approval)
            open().use { store ->
                store.startWaiting(wait).getOrThrow()
                val approved = store.approve(project, "1", by = "soule", at = at).getOrThrow()
                val data = wait.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to at.toString())
                assertEquals(wait.copy(data = data), approved)
                // A second approval changes nothing and returns the row as it is.
                assertEquals(approved, store.approve(project, "1", by = "other", at = Instant.parse("2026-11-13T11:00:00Z")).getOrThrow())
            }
            open().use { store ->
                val kept = store.waitingIssues(project).getOrThrow().single()
                assertEquals("soule", kept.data[WaitingIssue.APPROVED_BY])
                assertEquals(at.toString(), kept.data[WaitingIssue.APPROVED_AT])
            }
        }

    @Test
    fun `a wait for another reason is returned unchanged and no wait at all is null`() =
        runTest {
            open().use { store ->
                assertNull(store.approve(project, "1", by = "soule", at = at).getOrThrow())
                val verify = waiting(WaitReason.Verify)
                store.startWaiting(verify).getOrThrow()
                assertEquals(verify, store.approve(project, "1", by = "soule", at = at).getOrThrow())
                assertEquals(listOf(verify), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `an approval while another connection writes never fails on a stale snapshot`() {
        val count = 200
        open().use { setup ->
            runBlocking { (1..count).forEach { setup.startWaiting(waiting(WaitReason.Approval).copy(issueId = "$it")).getOrThrow() } }
        }
        open().use { store ->
            val failures =
                whileAnotherStoreWrites(::open, project) {
                    (1..count).mapNotNull { runBlocking { store.approve(project, "$it", "soule", at) }.exceptionOrNull() }
                }
            assertEquals(emptyList(), failures.map { it.message })
        }
    }
}
