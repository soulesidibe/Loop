package loop.store

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SqliteParkedIssuesTest {
    @TempDir
    lateinit var tmp: Path

    private val projectA = ProjectId("aaaaaaaaaaaaaaaa")
    private val projectB = ProjectId("bbbbbbbbbbbbbbbb")

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun parked(
        issueId: String = "1",
        projectId: ProjectId = projectA,
        state: String = "todo",
        labels: Set<String> = setOf("backend", "ready"),
        runId: RunId? = RunId("run-1"),
        second: Int = 0,
        reason: ParkReason = ParkReason.MaxAttempts,
    ) = ParkedIssue(
        projectId = projectId,
        issueId = issueId,
        identifier = "t-$issueId",
        reason = reason,
        state = state,
        labels = labels,
        parkedAt = Instant.parse("2026-11-07T13:48:${second.toString().padStart(2, '0')}Z"),
        runId = runId,
    )

    private fun open(): SqliteEventStore = SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow()

    private fun rawConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}")

    private fun Connection.userVersion(): Int =
        createStatement().use {
            it.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Test
    fun `a parked issue is listed with every field`() =
        runTest {
            open().use { store ->
                val issue = parked()
                store.park(issue).getOrThrow()

                assertEquals(listOf(issue), store.list(projectA).getOrThrow())
            }
        }

    @Test
    fun `a budget park round trips with its wire reason`() =
        runTest {
            open().use { store ->
                val issue = parked(reason = ParkReason.Budget)
                store.park(issue).getOrThrow()

                val listed = store.list(projectA).getOrThrow().single()

                assertEquals(issue, listed)
                assertEquals("budget", listed.reason.wire)
                assertEquals(listed, store.unpark(projectA, "1").getOrThrow())
            }
        }

    @Test
    fun `a token cap park stored by an older Loop still loads and unparks (m2-20)`() =
        runTest {
            open().use { store ->
                store.park(parked(reason = ParkReason("token_cap"))).getOrThrow()

                assertEquals(
                    "token_cap",
                    store
                        .list(projectA)
                        .getOrThrow()
                        .single()
                        .reason.wire,
                )
                assertEquals(
                    "token_cap",
                    store
                        .unpark(projectA, "1")
                        .getOrThrow()
                        ?.reason
                        ?.wire,
                )
            }
        }

    @Test
    fun `list is scoped by project`() =
        runTest {
            open().use { store ->
                val a1 = parked(issueId = "1")
                val a2 = parked(issueId = "2")
                val b1 = parked(issueId = "1", projectId = projectB)
                store.park(a1).getOrThrow()
                store.park(b1).getOrThrow()
                store.park(a2).getOrThrow()

                assertEquals(setOf(a1, a2), store.list(projectA).getOrThrow().toSet())
                assertEquals(listOf(b1), store.list(projectB).getOrThrow())
                assertEquals(emptyList<ParkedIssue>(), store.list(ProjectId("cccccccccccccccc")).getOrThrow())
            }
        }

    @Test
    fun `parking the same issue again replaces the record`() =
        runTest {
            open().use { store ->
                store.park(parked(state = "todo", second = 1)).getOrThrow()
                val again = parked(state = "in_progress", labels = setOf("other"), runId = RunId("run-2"), second = 2)
                store.park(again).getOrThrow()

                assertEquals(listOf(again), store.list(projectA).getOrThrow())
            }
        }

    @Test
    fun `unpark returns the record once then null`() =
        runTest {
            open().use { store ->
                val issue = parked()
                store.park(issue).getOrThrow()
                store.park(parked(issueId = "1", projectId = projectB)).getOrThrow()

                assertEquals(issue, store.unpark(projectA, "1").getOrThrow())
                assertNull(store.unpark(projectA, "1").getOrThrow())
                assertEquals(emptyList<ParkedIssue>(), store.list(projectA).getOrThrow())
                assertEquals(1, store.list(projectB).getOrThrow().size)
            }
        }

    @Test
    fun `unpark of an issue never parked returns null`() =
        runTest {
            open().use { store ->
                assertNull(store.unpark(projectA, "missing").getOrThrow())
            }
        }

    @Test
    fun `a record without a run id round trips`() =
        runTest {
            open().use { store ->
                val issue = parked(runId = null, labels = emptySet())
                store.park(issue).getOrThrow()

                assertEquals(listOf(issue), store.list(projectA).getOrThrow())
            }
        }

    @Test
    fun `parked issues survive closing and reopening the store`() =
        runTest {
            val withRun = parked(issueId = "1")
            val withoutRun = parked(issueId = "2", runId = null)
            open().use { store ->
                store.park(withRun).getOrThrow()
                store.park(withoutRun).getOrThrow()
            }

            val listed = open().use { it.list(projectA).getOrThrow() }

            assertEquals(setOf(withRun, withoutRun), listed.toSet())
        }

    @Test
    fun `a version one store is upgraded and keeps its events`() =
        runTest {
            Files.createDirectories(loopHome)
            rawConnection().use { conn ->
                conn.createStatement().use { st ->
                    V1_SCHEMA.forEach { st.execute(it) }
                    st.executeUpdate(
                        "INSERT INTO events (ts, project_id, issue_id, kind) " +
                            "VALUES ('2026-11-06T19:02:00Z', '${projectA.value}', '1', 'x')",
                    )
                    st.execute("PRAGMA user_version = 1")
                }
            }

            open().use { store ->
                assertEquals(listOf("x"), store.eventsForIssue(projectA, "1").getOrThrow().map { it.event.kind })
                val issue = parked()
                store.park(issue).getOrThrow()
                assertEquals(listOf(issue), store.list(projectA).getOrThrow())
            }
            rawConnection().use { conn -> assertEquals(SqliteEventStore.SCHEMA_VERSION, conn.userVersion()) }
        }

    @Test
    fun `an unpark while another connection writes never fails on a stale snapshot`() {
        val count = 200
        open().use { setup -> runBlocking { (1..count).forEach { setup.park(parked(issueId = "$it")).getOrThrow() } } }
        open().use { store ->
            val failures =
                whileAnotherStoreWrites(::open, projectA) {
                    (1..count).mapNotNull { runBlocking { store.unpark(projectA, "$it") }.exceptionOrNull() }
                }
            assertEquals(emptyList(), failures.map { it.message })
            assertEquals(emptyList(), runBlocking { store.list(projectA) }.getOrThrow())
        }
    }

    @Test
    fun `two stores opened at once on a version one store both succeed`() {
        val root = projectRoot
        repeat(ROUNDS) { round ->
            val home = Files.createDirectories(tmp.resolve("home-$round"))
            DriverManager.getConnection("jdbc:sqlite:${home.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    // A store an older Loop wrote is in WAL mode already.
                    st.execute("PRAGMA journal_mode = WAL")
                    V1_SCHEMA.forEach { st.execute(it) }
                    st.execute("PRAGMA user_version = 1")
                }
            }
            val barrier = CyclicBarrier(2)
            val results = arrayOfNulls<Result<Unit>>(2)
            List(2) { i ->
                thread {
                    barrier.await()
                    results[i] = SqliteEventStore.open(home, root, Redactor.NONE).map { it.close() }
                }
            }.forEach { it.join() }
            assertEquals(listOf(null, null), results.map { it?.exceptionOrNull()?.message }, "round $round")
            DriverManager.getConnection("jdbc:sqlite:${home.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                assertEquals(SqliteEventStore.SCHEMA_VERSION, conn.userVersion())
            }
        }
    }

    private companion object {
        /** The schema as m1-02 shipped it, user_version 1. */
        val V1_SCHEMA =
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
            )

        /** How many fresh version one stores are opened twice at once, as a race needs a few tries to show. */
        const val ROUNDS = 50
    }

    @Test
    fun `secrets are redacted before the insert, in identifier, state and labels`() =
        runTest {
            val secret = "s3cr3t-value"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
            SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow().use { store ->
                store.park(parked().copy(identifier = "t-$secret", state = "todo-$secret", labels = setOf("l-$secret"))).getOrThrow()

                val stored = store.list(projectA).getOrThrow().single()
                assertEquals("t-[redacted:LOOP_TRACKER_TOKEN]", stored.identifier)
                assertEquals("todo-[redacted:LOOP_TRACKER_TOKEN]", stored.state)
                assertEquals(setOf("l-[redacted:LOOP_TRACKER_TOKEN]"), stored.labels)
            }
        }
}
