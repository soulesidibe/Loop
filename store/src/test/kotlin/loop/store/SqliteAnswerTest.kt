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

/** m4-52: `loop answer` marks a `Waiting(ask)` row answered in the store, and only that kind of row. */
class SqliteAnswerTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val at = Instant.parse("2026-11-25T10:00:00Z")

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), redactor).getOrThrow()

    private fun waiting(reason: WaitReason) =
        WaitingIssue(
            projectId = project,
            issueId = "1",
            identifier = "t-1",
            reason = reason,
            attemptId = AttemptId("attempt-1"),
            runId = RunId("run-1"),
            since = Instant.parse("2026-11-25T09:00:00Z"),
            data = mapOf("state" to "todo", "question_kind" to "product"),
        )

    @Test
    fun `an ask wait is answered once, the answer survives reopening the store`() =
        runTest {
            val wait = waiting(WaitReason.Ask)
            open().use { store ->
                store.startWaiting(wait).getOrThrow()
                val answered = store.answer(project, "1", by = "soule", answer = "Leave them out.", at = at).getOrThrow()
                val data =
                    wait.data +
                        mapOf(
                            WaitingIssue.ANSWER to "Leave them out.",
                            WaitingIssue.ANSWERED_BY to "soule",
                            WaitingIssue.ANSWERED_AT to at.toString(),
                        )
                assertEquals(wait.copy(data = data), answered)
                // A second answer changes nothing and returns the row as it is.
                assertEquals(answered, store.answer(project, "1", by = "other", answer = "Include them.", at = at).getOrThrow())
            }
            open().use { store ->
                val kept = store.waitingIssues(project).getOrThrow().single()
                assertEquals("Leave them out.", kept.data[WaitingIssue.ANSWER])
                assertEquals("soule", kept.data[WaitingIssue.ANSWERED_BY])
                assertEquals(at.toString(), kept.data[WaitingIssue.ANSWERED_AT])
            }
        }

    @Test
    fun `a wait for another reason is returned unchanged and no wait at all is null`() =
        runTest {
            open().use { store ->
                assertNull(store.answer(project, "1", by = "soule", answer = "Yes.", at = at).getOrThrow())
                val approval = waiting(WaitReason.Approval)
                store.startWaiting(approval).getOrThrow()
                assertEquals(approval, store.answer(project, "1", by = "soule", answer = "Yes.", at = at).getOrThrow())
                assertEquals(listOf(approval), store.waitingIssues(project).getOrThrow())
            }
        }

    @Test
    fun `the answer and who answered are redacted before they are written`() =
        runTest {
            val redactor = Redactor { it.replace("hunter2", "[redacted]") }
            open(redactor).use { store ->
                store.startWaiting(waiting(WaitReason.Ask)).getOrThrow()
                store.answer(project, "1", by = "soule", answer = "The password is hunter2.", at = at).getOrThrow()
                val kept = store.waitingIssues(project).getOrThrow().single()
                assertEquals(false, "hunter2" in kept.data.getValue(WaitingIssue.ANSWER), kept.toString())
            }
        }
}
