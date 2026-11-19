package loop.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqliteEventStoreTest {
    @TempDir
    lateinit var tmp: Path

    private val projectA = ProjectId("aaaaaaaaaaaaaaaa")
    private val projectB = ProjectId("bbbbbbbbbbbbbbbb")

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun event(
        kind: String,
        issueId: String? = "m1-02",
        projectId: ProjectId = projectA,
        second: Int = 0,
    ) = RecordedEvent(
        ts = Instant.parse("2026-11-06T19:02:${second.toString().padStart(2, '0')}Z"),
        projectId = projectId,
        kind = kind,
        runId = RunId("run-1"),
        issueId = issueId,
        issueIdentifier = issueId,
        attemptId = AttemptId("attempt-1"),
        sessionId = SessionId("thread-1-turn-1"),
        turnId = TurnId("turn-1"),
        phase = if (kind == "phase") "PreparingWorkspace" else null,
        data = mapOf("workspace" to "/tmp/ws/m1-02", "note" to "with spaces, and \"quotes\""),
    )

    private fun rawConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}")

    @Test
    fun `the store file lives at loop home slash store db and nothing is written in the project`() =
        runTest {
            val root = projectRoot
            SqliteEventStore.open(loopHome, root, Redactor.NONE).getOrThrow().use { store ->
                store.record(event("attempt_started")).getOrThrow()
            }

            assertTrue(loopHome.resolve("store.db").exists())
            assertEquals(emptyList<Path>(), root.listDirectoryEntries())
        }

    @Test
    fun `the schema refuses a row without a project id`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                store.record(event("attempt_started")).getOrThrow()
            }

            rawConnection().use { conn ->
                assertFailsWith<SQLException> {
                    conn.createStatement().use {
                        it.executeUpdate("INSERT INTO events (ts, project_id, kind) VALUES ('2026-11-06T19:02:00Z', NULL, 'x')")
                    }
                }
                val missing =
                    conn.createStatement().use {
                        it.executeQuery("SELECT count(*) FROM events WHERE project_id IS NULL OR project_id = ''").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                assertEquals(0, missing)
            }
        }

    @Test
    fun `events of one run come back in seq order, run level events included`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                val started = event("attempt_started", second = 1)
                val stopped = event("run_stopped", issueId = null, second = 2).copy(attemptId = null, sessionId = null, turnId = null)
                store.record(started).getOrThrow()
                store.record(event("attempt_started").copy(runId = RunId("run-2"))).getOrThrow()
                store.record(event("attempt_started", projectId = projectB)).getOrThrow()
                store.record(stopped).getOrThrow()

                val stored = store.eventsForRun(projectA, RunId("run-1")).getOrThrow()

                assertEquals(listOf(started, stopped), stored.map { it.event })
            }
        }

    @Test
    fun `events of some kinds come back for one project in seq order, other kinds and projects left out`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                val started = event("attempt_started", second = 1)
                val retry = event("retry_scheduled", issueId = "m1-03", second = 2)
                store.record(started).getOrThrow()
                store.record(event("phase", second = 2)).getOrThrow()
                store.record(event("attempt_started", projectId = projectB)).getOrThrow()
                store.record(retry).getOrThrow()

                val stored = store.eventsOfKinds(projectA, setOf("attempt_started", "retry_scheduled")).getOrThrow()

                assertEquals(listOf(started, retry), stored.map { it.event })
                assertEquals(emptyList(), store.eventsOfKinds(projectA, emptySet()).getOrThrow())
            }
        }

    @Test
    fun `events of one issue come back in seq order with every field`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                val first = event("attempt_started", second = 1)
                val second = event("phase", second = 2)
                val third = event("attempt_finished", second = 3)
                store.record(first).getOrThrow()
                store.record(event("attempt_started", issueId = "m1-03")).getOrThrow()
                store.record(second).getOrThrow()
                store.record(event("attempt_started", projectId = projectB)).getOrThrow()
                store.record(third).getOrThrow()

                val stored = store.eventsForIssue(projectA, "m1-02").getOrThrow()

                assertEquals(listOf(first, second, third), stored.map { it.event })
                assertEquals(stored.map { it.seq }.sorted(), stored.map { it.seq })
                assertEquals(3, stored.map { it.seq }.distinct().size)
            }
        }

    @Test
    fun `events of one attempt come back in seq order`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                val first = event("attempt_started", second = 1)
                val second = event("issue_moved", second = 2)
                val third = event("attempt_finished", second = 3)
                store.record(first).getOrThrow()
                store.record(event("attempt_started").copy(attemptId = AttemptId("attempt-2"))).getOrThrow()
                store.record(second).getOrThrow()
                store.record(event("poll").copy(attemptId = null)).getOrThrow()
                store.record(third).getOrThrow()

                val stored = store.eventsForAttempt(AttemptId("attempt-1")).getOrThrow()

                assertEquals(listOf(first, second, third), stored.map { it.event })
                assertEquals(stored.map { it.seq }.sorted(), stored.map { it.seq })
            }
        }

    @Test
    fun `attempt ids starting with a prefix are listed once each, sorted, inside one project only`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                store.record(event("attempt_started").copy(attemptId = AttemptId("ab12-2"))).getOrThrow()
                store.record(event("attempt_finished").copy(attemptId = AttemptId("ab12-2"))).getOrThrow()
                store.record(event("attempt_started").copy(attemptId = AttemptId("ab12-1"))).getOrThrow()
                store.record(event("attempt_started").copy(attemptId = AttemptId("ab99"))).getOrThrow()
                store.record(event("attempt_started", projectId = projectB).copy(attemptId = AttemptId("ab12-3"))).getOrThrow()
                // LIKE wildcards in the prefix are plain characters.
                store.record(event("attempt_started").copy(attemptId = AttemptId("a_x"))).getOrThrow()

                assertEquals(listOf(AttemptId("ab12-1"), AttemptId("ab12-2")), store.attemptIdsStartingWith(projectA, "ab12").getOrThrow())
                assertEquals(listOf(AttemptId("ab99")), store.attemptIdsStartingWith(projectA, "ab99").getOrThrow())
                assertEquals(emptyList(), store.attemptIdsStartingWith(projectA, "a%").getOrThrow())
                assertEquals(emptyList(), store.attemptIdsStartingWith(projectA, "zz").getOrThrow())
            }
        }

    @Test
    fun `a row with corrupted data json returns a failure`() =
        runTest {
            SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().use { store ->
                store.record(event("attempt_started")).getOrThrow()
                rawConnection().use { conn ->
                    conn.createStatement().use { it.executeUpdate("UPDATE events SET data = '{not json'") }
                }

                assertIs<StoreError.Corrupt>(store.eventsForIssue(projectA, "m1-02").exceptionOrNull())
                assertIs<StoreError.Corrupt>(store.eventsForAttempt(AttemptId("attempt-1")).exceptionOrNull())
            }
        }

    @Test
    fun `a second store on the same file writes while the first is open`() =
        runTest {
            val root = projectRoot
            SqliteEventStore.open(loopHome, root, Redactor.NONE).getOrThrow().use { first ->
                first.record(event("attempt_started", second = 1)).getOrThrow()

                withContext(Dispatchers.IO) {
                    SqliteEventStore.open(loopHome, root, Redactor.NONE).getOrThrow().use { second ->
                        second.record(event("issue_moved", second = 2)).getOrThrow()
                    }
                }
                first.record(event("attempt_finished", second = 3)).getOrThrow()

                val kinds = first.eventsForIssue(projectA, "m1-02").getOrThrow().map { it.event.kind }
                assertEquals(listOf("attempt_started", "issue_moved", "attempt_finished"), kinds)
            }

            rawConnection().use { conn ->
                val mode =
                    conn.createStatement().use {
                        it.executeQuery("PRAGMA journal_mode").use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                assertEquals("wal", mode.lowercase())
            }
        }

    @Test
    fun `opening a new store while another process holds its write lock waits for the lock instead of failing (m3-14)`() {
        // Two `loop run` processes creating the shared store at once (MachineLimitsCommandTest): switching a new
        // file to WAL needs a lock SQLite does not wait for on busy_timeout, so the second open failed with SQLITE_BUSY.
        Files.createDirectories(loopHome)
        val root = projectRoot
        rawConnection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("CREATE TABLE holder (x INTEGER)") }
            val release =
                Thread {
                    Thread.sleep(500)
                    holder.commit()
                }.apply { start() }

            val opened = SqliteEventStore.open(loopHome, root, Redactor.NONE)

            release.join()
            assertTrue(opened.isSuccess, "${opened.exceptionOrNull()}")
            opened.getOrThrow().close()
        }
    }

    @Test
    fun `reopening the store keeps its events`() =
        runTest {
            val root = projectRoot
            SqliteEventStore.open(loopHome, root, Redactor.NONE).getOrThrow().use { it.record(event("attempt_started")).getOrThrow() }

            val kinds =
                SqliteEventStore.open(loopHome, root, Redactor.NONE).getOrThrow().use { store ->
                    store.eventsForIssue(projectA, "m1-02").getOrThrow().map { it.event.kind }
                }

            assertEquals(listOf("attempt_started"), kinds)
        }

    @Test
    fun `a loop home inside the project root is refused and nothing is created`() {
        val root = projectRoot
        val inside = root.resolve(".loop")

        val error = SqliteEventStore.open(inside, root, Redactor.NONE).exceptionOrNull()

        assertIs<StoreError.LoopHomeInsideProject>(error)
        assertFalse(inside.resolve(SqliteEventStore.FILE_NAME).exists())
        assertIs<StoreError.LoopHomeInsideProject>(SqliteEventStore.open(root, root, Redactor.NONE).exceptionOrNull())
    }

    @Test
    fun `the schema version is set in user_version`() {
        SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow().close()

        rawConnection().use { conn ->
            val version =
                conn.createStatement().use {
                    it.executeQuery("PRAGMA user_version").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertEquals(SqliteEventStore.SCHEMA_VERSION, version)
            assertTrue(version > 0)
        }
    }

    @Test
    fun `secrets are redacted before the insert, in data, identifier and phase`() =
        runTest {
            // A quote and a backslash, so the json encoding of data cannot split the value.
            val secret = "s3cr\"et\\value"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
            val leaky =
                event("phase").copy(
                    issueIdentifier = "id-$secret",
                    phase = "phase-$secret",
                    data = mapOf("line" to "echo $secret done"),
                )
            SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow().use { store ->
                store.record(leaky).getOrThrow()

                val stored =
                    store
                        .eventsForIssue(projectA, "m1-02")
                        .getOrThrow()
                        .single()
                        .event
                assertEquals("id-[redacted:LOOP_TRACKER_TOKEN]", stored.issueIdentifier)
                assertEquals("phase-[redacted:LOOP_TRACKER_TOKEN]", stored.phase)
                assertEquals(mapOf("line" to "echo [redacted:LOOP_TRACKER_TOKEN] done"), stored.data)
            }

            rawConnection().use { conn ->
                val row =
                    conn.createStatement().use {
                        it.executeQuery("SELECT issue_identifier || phase || data FROM events").use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                assertFalse(row.contains("s3cr"), row)
            }
        }
}
