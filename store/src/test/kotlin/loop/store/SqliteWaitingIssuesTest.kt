package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** m2-04: the waiting issues of a Project in the machine level store, and the version 4 migration. */
class SqliteWaitingIssuesTest {
    @TempDir
    lateinit var tmp: Path

    private val projectA = ProjectId("aaaaaaaaaaaaaaaa")
    private val projectB = ProjectId("bbbbbbbbbbbbbbbb")

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow()

    private fun waiting(
        issueId: String = "1",
        projectId: ProjectId = projectA,
        data: Map<String, String> = mapOf("to" to "review"),
    ) = WaitingIssue(
        projectId = projectId,
        issueId = issueId,
        identifier = "t-$issueId",
        reason = WaitReason.Verify,
        attemptId = AttemptId("attempt-$issueId"),
        runId = RunId("run-1"),
        since = Instant.parse("2026-11-12T09:00:00Z"),
        data = data,
    )

    @Test
    fun `a waiting issue round trips, is replaced by a later write and ended`() =
        runTest {
            open().use { store ->
                val first = waiting()
                store.startWaiting(first).getOrThrow()
                assertEquals(listOf(first), store.waitingIssues(projectA).getOrThrow())

                val later = first.copy(attemptId = AttemptId("attempt-2"), since = Instant.parse("2026-11-12T09:05:00Z"))
                store.startWaiting(later).getOrThrow()
                assertEquals(listOf(later), store.waitingIssues(projectA).getOrThrow())

                store.endWaiting(projectA, "1").getOrThrow()
                assertEquals(emptyList(), store.waitingIssues(projectA).getOrThrow())
                // Ending what is not there is not an error.
                store.endWaiting(projectA, "1").getOrThrow()
            }
        }

    @Test
    fun `a waiting issue without attempt, run or data round trips`() =
        runTest {
            open().use { store ->
                val bare = waiting().copy(attemptId = null, runId = null, data = emptyMap())
                store.startWaiting(bare).getOrThrow()
                assertEquals(listOf(bare), store.waitingIssues(projectA).getOrThrow())
            }
        }

    @Test
    fun `waiting issues are kept per project and survive closing and reopening the store`() =
        runTest {
            open().use { store ->
                store.startWaiting(waiting(projectId = projectA)).getOrThrow()
                store.startWaiting(waiting(projectId = projectB)).getOrThrow()
                store.endWaiting(projectA, "1").getOrThrow()
            }

            open().use { store ->
                assertEquals(emptyList(), store.waitingIssues(projectA).getOrThrow())
                assertEquals(listOf(waiting(projectId = projectB)), store.waitingIssues(projectB).getOrThrow())
            }
        }

    @Test
    fun `secrets are redacted before the insert, in the identifier and the data`() =
        runTest {
            val secret = "s3cr3t-value"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
            open(redactor).use { store ->
                store.startWaiting(waiting(data = mapOf("to" to "review $secret")).copy(identifier = "t-$secret")).getOrThrow()

                val stored = store.waitingIssues(projectA).getOrThrow().single()
                assertEquals("t-[redacted:LOOP_TRACKER_TOKEN]", stored.identifier)
                assertEquals(mapOf("to" to "review [redacted:LOOP_TRACKER_TOKEN]"), stored.data)
            }
        }

    @Test
    fun `a version three store is migrated to the current version`() =
        runTest {
            open().use { }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE waiting_issues")
                    st.execute("PRAGMA user_version = 3")
                }
            }

            open().use { store ->
                store.startWaiting(waiting()).getOrThrow()
                assertEquals(listOf(waiting()), store.waitingIssues(projectA).getOrThrow())
            }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA user_version").use { rs ->
                        rs.next()
                        assertEquals(SqliteEventStore.SCHEMA_VERSION, rs.getInt(1))
                    }
                }
            }
        }
}
