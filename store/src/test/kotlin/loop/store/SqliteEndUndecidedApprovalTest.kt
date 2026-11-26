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
 * bl-224: a reaction ends a `Waiting(approval)` row only when it is neither approved nor sent back, in one transaction,
 * so a `loop approve` or a `loop send-back` committed while the reaction read GitHub is kept.
 */
class SqliteEndUndecidedApprovalTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val at = Instant.parse("2026-11-26T10:00:00Z")

    private fun open(): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow()

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
        since = Instant.parse("2026-11-26T09:00:00Z"),
        data = data,
    )

    @Test
    fun `an undecided approval wait is removed and returned as it was`() =
        runTest {
            open().use { store ->
                val wait = waiting(WaitReason.Approval)
                store.startWaiting(wait).getOrThrow()
                assertEquals(wait, store.endUndecidedApproval(project, "1").getOrThrow())
                assertEquals(emptyList(), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `an approved wait, a sent back wait and a wait for another reason are kept`() =
        runTest {
            open().use { store ->
                store.startWaiting(waiting(WaitReason.Approval)).getOrThrow()
                val approved = store.approve(project, "1", by = "soule", at = at).getOrThrow()
                assertEquals(approved, store.endUndecidedApproval(project, "1").getOrThrow())
                assertEquals(listOf(approved), store.waitingIssues(project).getOrThrow())

                val sent = store.sendBack(project, "1", by = "soule", reason = "Use Save.", at = at).getOrThrow()
                assertEquals(sent, store.endUndecidedApproval(project, "1").getOrThrow())
                assertEquals(listOf(sent), store.waitingIssues(project).getOrThrow())

                val merge = waiting(WaitReason.Merge, mapOf("phase" to "check"))
                store.startWaiting(merge).getOrThrow()
                assertEquals(merge, store.endUndecidedApproval(project, "1").getOrThrow())
                assertEquals(listOf(merge), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `no wait at all is null`() =
        runTest {
            open().use { store -> assertNull(store.endUndecidedApproval(project, "1").getOrThrow()) }
        }
}
