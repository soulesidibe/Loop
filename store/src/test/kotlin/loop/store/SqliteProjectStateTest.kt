package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.Claim
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.Redactor
import loop.api.RetryState
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.SessionId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m2-02: claims and retry states in the machine level store, and the version 3 migration. */
class SqliteProjectStateTest {
    @TempDir
    lateinit var tmp: Path

    private val projectA = ProjectId("aaaaaaaaaaaaaaaa")
    private val projectB = ProjectId("bbbbbbbbbbbbbbbb")

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow()

    private fun rawConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}")

    private fun Connection.userVersion(): Int =
        createStatement().use {
            it.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun Connection.tables(): Set<String> =
        createStatement().use {
            it.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun claim(
        issueId: String = "1",
        projectId: ProjectId = projectA,
        sessionId: SessionId? = null,
        runtimeSessionId: String? = null,
    ) = Claim(
        projectId = projectId,
        issueId = issueId,
        identifier = "t-$issueId",
        attemptId = AttemptId("attempt-$issueId"),
        runId = RunId("run-1"),
        claimedAt = Instant.parse("2026-11-12T09:00:00Z"),
        sessionId = sessionId,
        runtimeSessionId = runtimeSessionId,
    )

    private fun retryState(
        issueId: String = "1",
        projectId: ProjectId = projectA,
        retry: QueuedRetry? = QueuedRetry(attempt = 2, dueAt = Instant.parse("2026-11-12T09:00:20Z"), error = "turn_failed"),
        failures: Int = 2,
        noProgress: Int = 0,
    ) = RetryState(projectId, issueId, "t-$issueId", retry, failures, noProgress)

    @Test
    fun `a claim round trips, is replaced by a later write and released`() =
        runTest {
            open().use { store ->
                val first = claim()
                store.claim(first).getOrThrow()
                assertEquals(listOf(first), store.claims(projectA).getOrThrow())

                val withSession = claim(sessionId = SessionId("thread-turn"), runtimeSessionId = "rt-1")
                store.claim(withSession).getOrThrow()
                assertEquals(listOf(withSession), store.claims(projectA).getOrThrow())

                store.release(projectA, "1").getOrThrow()
                assertEquals(emptyList(), store.claims(projectA).getOrThrow())
                // Releasing what is not there is not an error.
                store.release(projectA, "1").getOrThrow()
            }
        }

    @Test
    fun `claims and retry states are kept per project`() =
        runTest {
            open().use { store ->
                store.claim(claim(projectId = projectA)).getOrThrow()
                store.claim(claim(projectId = projectB)).getOrThrow()
                store.saveRetryState(retryState(projectId = projectB)).getOrThrow()

                store.release(projectA, "1").getOrThrow()

                assertEquals(listOf(claim(projectId = projectB)), store.claims(projectB).getOrThrow())
                assertEquals(emptyList(), store.retryStates(projectA).getOrThrow())
                assertEquals(listOf(retryState(projectId = projectB)), store.retryStates(projectB).getOrThrow())
            }
        }

    @Test
    fun `a retry state round trips with and without a queued retry, and is deleted`() =
        runTest {
            open().use { store ->
                val queued = retryState(issueId = "1")
                val continuation =
                    retryState(
                        issueId = "2",
                        retry = QueuedRetry(1, Instant.parse("2026-11-12T09:00:01Z"), null),
                        failures = 0,
                        noProgress = 3,
                    )
                val countersOnly = retryState(issueId = "3", retry = null, failures = 1)
                listOf(queued, continuation, countersOnly).forEach { store.saveRetryState(it).getOrThrow() }

                assertEquals(setOf(queued, continuation, countersOnly), store.retryStates(projectA).getOrThrow().toSet())

                val replaced = queued.copy(retry = QueuedRetry(3, Instant.parse("2026-11-12T09:00:40Z"), "stalled"), failures = 3)
                store.saveRetryState(replaced).getOrThrow()
                store.deleteRetryState(projectA, "2").getOrThrow()

                assertEquals(setOf(replaced, countersOnly), store.retryStates(projectA).getOrThrow().toSet())
            }
        }

    @Test
    fun `claims and retry states survive closing and reopening the store`() =
        runTest {
            open().use { store ->
                store.claim(claim()).getOrThrow()
                store.saveRetryState(retryState()).getOrThrow()
            }

            open().use { store ->
                assertEquals(listOf(claim()), store.claims(projectA).getOrThrow())
                assertEquals(listOf(retryState()), store.retryStates(projectA).getOrThrow())
            }
        }

    @Test
    fun `secrets are redacted before the insert, in identifiers and the retry error`() =
        runTest {
            val secret = "s3cr3t-value"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
            open(redactor).use { store ->
                store.claim(claim().copy(identifier = "t-$secret")).getOrThrow()
                store
                    .saveRetryState(
                        retryState().copy(
                            identifier = "t-$secret",
                            retry = QueuedRetry(1, Instant.parse("2026-11-12T09:00:00Z"), "bad $secret"),
                        ),
                    ).getOrThrow()

                assertEquals(
                    "t-[redacted:LOOP_TRACKER_TOKEN]",
                    store
                        .claims(projectA)
                        .getOrThrow()
                        .single()
                        .identifier,
                )
                val stored = store.retryStates(projectA).getOrThrow().single()
                assertEquals("t-[redacted:LOOP_TRACKER_TOKEN]", stored.identifier)
                assertEquals("bad [redacted:LOOP_TRACKER_TOKEN]", stored.retry?.error)
            }
        }

    @Test
    fun `a version two store is migrated to version three and keeps its events and parked issues`() =
        runTest {
            Files.createDirectories(loopHome)
            rawConnection().use { conn ->
                conn.createStatement().use { st ->
                    V2_SCHEMA.forEach { st.execute(it) }
                    st.executeUpdate(
                        "INSERT INTO events (ts, project_id, issue_id, kind) VALUES ('2026-11-06T19:02:00Z', '${projectA.value}', '1', 'x')",
                    )
                    st.executeUpdate(
                        "INSERT INTO parked_issues (project_id, issue_id, identifier, reason, state, labels, parked_at, run_id) " +
                            "VALUES ('${projectA.value}', '7', 't-7', 'max_attempts', 'todo', '[\"ready\"]', '2026-11-07T13:48:00Z', NULL)",
                    )
                    st.execute("PRAGMA user_version = 2")
                }
            }

            open().use { store ->
                assertEquals(listOf("x"), store.eventsForIssue(projectA, "1").getOrThrow().map { it.event.kind })
                val parked = store.list(projectA).getOrThrow().single()
                assertEquals(
                    ParkedIssue(
                        projectA,
                        "7",
                        "t-7",
                        ParkReason.MaxAttempts,
                        "todo",
                        setOf("ready"),
                        Instant.parse("2026-11-07T13:48:00Z"),
                    ),
                    parked,
                )
                store.claim(claim()).getOrThrow()
                store.saveRetryState(retryState()).getOrThrow()
                assertEquals(listOf(claim()), store.claims(projectA).getOrThrow())
                assertEquals(listOf(retryState()), store.retryStates(projectA).getOrThrow())
            }
            rawConnection().use { conn ->
                assertEquals(SqliteEventStore.SCHEMA_VERSION, conn.userVersion())
                assertTrue(conn.tables().containsAll(setOf("events", "parked_issues", "claims", "retry_states")), conn.tables().toString())
            }
        }

    @Test
    fun `a version one store is migrated straight to version three`() =
        runTest {
            Files.createDirectories(loopHome)
            rawConnection().use { conn ->
                conn.createStatement().use { st ->
                    V2_SCHEMA.take(V1_STATEMENTS).forEach { st.execute(it) }
                    st.execute("PRAGMA user_version = 1")
                }
            }

            open().use { store ->
                store.saveRetryState(retryState()).getOrThrow()
                assertEquals(listOf(retryState()), store.retryStates(projectA).getOrThrow())
            }
            rawConnection().use { conn -> assertEquals(SqliteEventStore.SCHEMA_VERSION, conn.userVersion()) }
        }

    private companion object {
        /** The first statements of [V2_SCHEMA] are the schema m1-02 shipped, user_version 1. */
        const val V1_STATEMENTS = 3

        /** The schema as m1-07 shipped it, user_version 2. */
        val V2_SCHEMA =
            listOf(
                """
                CREATE TABLE events (
                    seq INTEGER PRIMARY KEY,
                    ts TEXT NOT NULL,
                    run_id TEXT,
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT,
                    issue_identifier TEXT,
                    attempt_id TEXT,
                    session_id TEXT,
                    turn_id TEXT,
                    kind TEXT NOT NULL,
                    phase TEXT,
                    data TEXT
                )
                """.trimIndent(),
                "CREATE INDEX events_project_issue ON events (project_id, issue_id, seq)",
                "CREATE INDEX events_attempt ON events (attempt_id)",
                """
                CREATE TABLE parked_issues (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    state TEXT NOT NULL,
                    labels TEXT NOT NULL,
                    parked_at TEXT NOT NULL,
                    run_id TEXT,
                    PRIMARY KEY (project_id, issue_id)
                )
                """.trimIndent(),
            )
    }
}
