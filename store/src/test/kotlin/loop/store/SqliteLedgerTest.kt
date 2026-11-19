package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.FrozenCommands
import loop.api.HumanTouch
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m2-19: the attempt rows of the ledger and the human touches each one counts. */
class SqliteLedgerTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow()

    private val setup =
        LedgerSetup(
            tracker = "files",
            kind = "code",
            runner = "claude-code",
            model = "claude-opus-5-5",
            templateVersion = "t".repeat(64),
            configVersion = "c".repeat(64),
        )

    private fun attempt(
        id: String,
        issueId: String = "1",
        tokens: LedgerTokens? = LedgerTokens(100, 20, 3_000, 400),
        costUsd: Double? = 0.25,
        checks: Int? = 2,
        verdict: String? = "verified",
        identifier: String = "t-1",
        finishedAt: Instant = Instant.parse("2026-11-12T10:02:03.500Z"),
        projectId: ProjectId = project,
    ): LedgerAttempt =
        LedgerAttempt(
            projectId = projectId,
            runId = RunId("r1"),
            issueId = issueId,
            identifier = identifier,
            attemptId = AttemptId(id),
            setup = setup,
            startedAt = Instant.parse("2026-11-12T10:00:00Z"),
            finishedAt = finishedAt,
            outcome = "succeeded",
            reason = null,
            turns = 3,
            issueState = "review",
            tokens = tokens,
            costUsd = costUsd,
            checks = checks,
            guards = null,
            verdict = verdict,
        )

    private suspend fun SqliteEventStore.touch(
        touch: String,
        issueId: String = "1",
    ) = record(
        RecordedEvent(
            ts = Instant.parse("2026-11-12T09:00:00Z"),
            projectId = project,
            kind = HumanTouch.EVENT,
            issueId = issueId,
            issueIdentifier = "t-$issueId",
            data = mapOf(HumanTouch.TOUCH to touch),
        ),
    ).getOrThrow()

    @Test
    fun `an attempt row keeps every field, with its wall time and its number`() =
        runTest {
            open().use { store ->
                store.recordAttempt(attempt("a1")).getOrThrow()

                val row = store.attemptRows(project, "1").getOrThrow().single()
                assertEquals(attempt("a1"), row.attempt)
                assertEquals(1, row.number)
                assertEquals(0, row.humanTouches)
                assertEquals(123_500, row.attempt.wallMs)
            }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT wall_ms, tokens, verdict, guards FROM attempts").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(123_500, rs.getLong("wall_ms"))
                        assertEquals(3_520, rs.getLong("tokens"))
                        assertEquals("verified", rs.getString("verdict"))
                        rs.getLong("guards")
                        assertTrue(rs.wasNull(), "guards is null, never zero")
                    }
                }
            }
        }

    @Test
    fun `what the runner could not report is read back as null, never zero`() =
        runTest {
            open().use { store ->
                val bare = attempt("a1", tokens = null, costUsd = null, checks = null, verdict = null)
                store.recordAttempt(bare.copy(setup = setup.copy(kind = null, model = null, configVersion = null))).getOrThrow()

                val row =
                    store
                        .attemptRows(project, "1")
                        .getOrThrow()
                        .single()
                        .attempt
                assertEquals(null, row.tokens)
                assertEquals(null, row.costUsd)
                assertEquals(null, row.checks)
                assertEquals(null, row.verdict)
                assertEquals(null, row.guards)
                assertEquals(null, row.setup.model)
                assertEquals(null, row.setup.kind)
                assertEquals(null, row.setup.configVersion)
            }
        }

    @Test
    fun `resume, approve and a human move each count once, in the next row of their issue`() =
        runTest {
            open().use { store ->
                store.touch(HumanTouch.RESUME)
                store.touch(HumanTouch.APPROVE)
                store.touch(HumanTouch.MOVE)
                store.touch(HumanTouch.MOVE, issueId = "2")
                store.recordAttempt(attempt("a1")).getOrThrow()
                store.recordAttempt(attempt("a2")).getOrThrow()
                store.touch(HumanTouch.RESUME)
                store.recordAttempt(attempt("a3")).getOrThrow()

                val rows = store.attemptRows(project, "1").getOrThrow()
                assertEquals(listOf("a1", "a2", "a3"), rows.map { it.attempt.attemptId.value })
                assertEquals(listOf(1, 2, 3), rows.map { it.number })
                assertEquals(listOf(3, 0, 1), rows.map { it.humanTouches })
                store.recordAttempt(attempt("b1", issueId = "2")).getOrThrow()
                assertEquals(
                    1,
                    store
                        .attemptRows(project, "2")
                        .getOrThrow()
                        .single()
                        .humanTouches,
                )
            }
        }

    @Test
    fun `a version five store keeps its frozen command lists and is migrated to version six with the attempts table`() =
        runTest {
            val frozen = FrozenCommands(project, "1", "t-1", "sha256:first", 2, Instant.parse("2026-11-12T09:00:00Z"))
            open().use { store -> store.freeze(frozen).getOrThrow() }
            // A store left at version 5 by the m2-09 build: acceptance_commands, no attempts.
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE attempts")
                    st.execute("PRAGMA user_version = 5")
                }
            }

            open().use { store ->
                store.recordAttempt(attempt("a1")).getOrThrow()
                assertEquals(1, store.attemptRows(project, "1").getOrThrow().size)
                assertEquals(frozen, store.frozen(project, "1").getOrThrow())
            }
            assertEquals(SqliteEventStore.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `a version four store is migrated to version six with both tables`() =
        runTest {
            open().use { }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE attempts")
                    st.execute("DROP TABLE acceptance_commands")
                    st.execute("PRAGMA user_version = 4")
                }
            }

            open().use { store ->
                store.recordAttempt(attempt("a1")).getOrThrow()
                assertEquals(1, store.attemptRows(project, "1").getOrThrow().size)
                val frozen = FrozenCommands(project, "1", "t-1", "sha256:first", 2, Instant.parse("2026-11-12T09:00:00Z"))
                assertTrue(store.freeze(frozen).getOrThrow().written)
            }
            assertEquals(SqliteEventStore.SCHEMA_VERSION, userVersion())
        }

    private fun userVersion(): Int =
        DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `a second row for the same attempt is ignored`() =
        runTest {
            open().use { store ->
                store.recordAttempt(attempt("a1")).getOrThrow()
                store.recordAttempt(attempt("a1", verdict = "refuted")).getOrThrow()

                assertEquals(
                    "verified",
                    store
                        .attemptRows(project, "1")
                        .getOrThrow()
                        .single()
                        .attempt.verdict,
                )
            }
        }

    @Test
    fun `the identifier, reason, state and model are redacted before the insert`() =
        runTest {
            val redactor = SecretRedactor().also { it.learn(mapOf("TOKEN" to "s3cr3t-value")) }
            open(redactor).use { store ->
                val leaky =
                    attempt("a1", identifier = "t-s3cr3t-value").copy(
                        reason = "failed with s3cr3t-value",
                        issueState = "s3cr3t-value",
                        setup = setup.copy(model = "s3cr3t-value"),
                    )
                store.recordAttempt(leaky).getOrThrow()

                val row =
                    store
                        .attemptRows(project, "1")
                        .getOrThrow()
                        .single()
                        .attempt
                listOf(row.identifier, row.reason, row.issueState, row.setup.model).forEach {
                    assertTrue("s3cr3t-value" !in it.orEmpty(), it)
                }
            }
        }

    @Test
    fun `the rows since an instant are those of the project that finished after it, across a reopen (m2-20)`() =
        runTest {
            val now = Instant.parse("2026-11-13T12:00:00Z")
            open().use { store ->
                store.recordAttempt(attempt("old", finishedAt = Instant.parse("2026-11-12T11:59:59.999Z"))).getOrThrow()
                store.recordAttempt(attempt("edge", issueId = "2", finishedAt = Instant.parse("2026-11-12T12:00:00.5Z"))).getOrThrow()
                store.recordAttempt(attempt("new", finishedAt = Instant.parse("2026-11-13T11:00:00Z"))).getOrThrow()
                store.recordAttempt(attempt("other", projectId = ProjectId("bbbbbbbbbbbbbbbb"), finishedAt = now)).getOrThrow()
            }

            // A new process opens the same store: the rows are read from it, not from memory.
            open().use { store ->
                val since = store.attemptRowsSince(project, Instant.parse("2026-11-12T12:00:00Z")).getOrThrow()

                assertEquals(listOf("edge", "new"), since.map { it.attempt.attemptId.value })
                assertEquals(listOf("2", "1"), since.map { it.attempt.issueId })
            }
        }
}
