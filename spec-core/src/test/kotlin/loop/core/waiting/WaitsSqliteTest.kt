package loop.core.waiting

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** bl-79: a wait of each reason goes through the machine level store and comes back the same typed wait. */
class WaitsSqliteTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val since = Instant.parse("2026-11-15T09:00:00Z")

    private fun open(): SqliteEventStore =
        SqliteEventStore.open(tmp.resolve("loop-home"), Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow()

    @Test
    fun `a wait of each reason round trips through the sqlite store`() =
        runTest {
            val verify = Wait.verify(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review")
            val approval =
                Wait.approval(
                    project,
                    "id-2",
                    "t-2",
                    AttemptId("a2"),
                    RunId("r1"),
                    since.plus(kotlin.time.Duration.parse("1s")),
                    "review",
                    "git:abc:e:e",
                    checks = "- check 0, ./gradlew test: passed",
                    judgmentCalls = 1,
                )
            val merge =
                Wait
                    .approval(
                        project,
                        "id-3",
                        "t-3",
                        AttemptId("a3"),
                        RunId("r1"),
                        since,
                        "review",
                        "git:def:e:e",
                        approvedBy = "auto",
                        approvedAt = since,
                    ).merging(7, "https://github.com/acme/widgets/pull/7", since.plus(kotlin.time.Duration.parse("2s")))
                    .numbered(7)
                    .intending("base1")
                    .checked("base1", "head1")

            open().use { store ->
                val waits = Waits(store, project)
                for (wait in listOf(verify, approval, merge)) assertTrue(waits.save(wait).isSuccess)
            }
            open().use { store ->
                val read = Waits(store, project).read().getOrThrow()
                assertEquals(listOf(verify, approval, merge), read)
                assertEquals("review", assertIs<Wait.Verify>(read[0]).to)
                assertEquals(1, assertIs<Wait.Approval>(read[1]).judgmentCalls)
                val merged = assertIs<Wait.Merge>(read[2])
                assertEquals(MergePhase.Merge, merged.phase)
                assertEquals("head1", merged.mergedHead)
                assertEquals("auto", merged.approvedBy)

                val approved = Waits(store, project).approve("id-2", "soule", since).getOrThrow()
                assertEquals("soule", assertIs<Wait.Approval>(approved).approvedBy)
            }
        }
}
