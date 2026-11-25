package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** m4-39: `loop query "<sql>"`, one read only statement over the machine store. */
class QueryCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }
    private val project by lazy { ProjectId(fixture.projectId()) }
    private val at = Instant.parse("2026-11-25T10:00:00Z")

    private val scopeLine by lazy {
        "Store ${fixture.storeFile}: every project on this machine, this project is ${project.value}."
    }

    @Test
    fun `select count of attempts prints the count on a seeded store`() {
        seedAttempts(3)

        val result = fixture.inProcess("query", "select count(*) from attempts")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(scopeLine, "count(*)", "--------", "3", "1 row."),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `rows of several columns are aligned, a null shows as NULL`() {
        seedAttempts(2)

        val result = fixture.inProcess("query", "select identifier, attempt_number as n, guards from attempts order by seq")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                scopeLine,
                "identifier  n  guards",
                "----------  -  ------",
                "t-1         1  NULL",
                "t-2         1  NULL",
                "2 rows.",
            ),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `a line break in a text value is written escaped so a row stays on one line`() {
        seedAttempts(1)

        val result = fixture.inProcess("query", "select 'a' || char(10) || 'b' as t, x'0aff' as b")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf("t     b", "----  -------", "a\\nb  x'0aff'", "1 row."),
            result.stdout
                .trimEnd()
                .lines()
                .drop(1),
        )
    }

    @Test
    fun `an insert fails and the store file is unchanged`() =
        assertRefused(
            "insert into events (ts, project_id, kind, data) values ('t', 'p', 'k', '{}')",
            "attempt to write a readonly database",
        )

    @Test
    fun `an update fails and the store file is unchanged`() =
        assertRefused("update attempts set cost_usd = 0", "attempt to write a readonly database")

    @Test
    fun `a delete fails and the store file is unchanged`() = assertRefused("delete from attempts", "attempt to write a readonly database")

    @Test
    fun `a drop fails and the store file is unchanged`() = assertRefused("drop table attempts", "attempt to write a readonly database")

    @Test
    fun `a pragma that writes fails and the store file is unchanged`() =
        assertRefused("pragma user_version = 99", "attempt to write a readonly database")

    @Test
    fun `an ATTACH fails, creates no file and the store file is unchanged`() {
        val other = root.resolve("other.db")

        assertRefused("attach database 'file:$other?mode=rwc' as other", "too many attached databases - max 0")

        assertFalse(other.exists(), "ATTACH created $other")
    }

    @Test
    fun `a load_extension call fails and the store file is unchanged`() =
        assertRefused("select load_extension('/no/such/extension')", "not authorized")

    @Test
    fun `the query runs while another connection holds the store open for writing, as loop run does`() {
        seedAttempts(2)
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { running ->
            runBlocking { running.record(RecordedEvent(at, project, "poll")).getOrThrow() }
            DriverManager.getConnection("jdbc:sqlite:${fixture.storeFile}").use { writer ->
                writer.createStatement().use { st ->
                    st.execute("begin immediate")
                    st.execute("insert into events (ts, project_id, kind, data) values ('t', 'p', 'uncommitted', '{}')")
                }

                val result = fixture.inProcess("query", "select kind, count(*) as n from events group by kind order by kind")

                assertEquals(0, result.exitCode, result.toString())
                assertEquals(
                    listOf("kind  n", "----  -", "poll  1", "1 row."),
                    result.stdout
                        .trimEnd()
                        .lines()
                        .drop(1),
                )
                writer.createStatement().use { it.execute("rollback") }
            }
        }
    }

    @Test
    fun `format json prints one line with the scope, the columns and typed rows`() {
        seedAttempts(2)

        val result =
            fixture.inProcess(
                "query",
                "select identifier, attempt_number, cost_usd, guards from attempts order by seq",
                "--format",
                "json",
            )

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            1,
            result.stdout
                .trimEnd()
                .lines()
                .size,
            result.stdout,
        )
        val json = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals(listOf("store", "scope", "project_id", "columns", "rows", "rows_left_out"), json.keys.toList())
        assertEquals(fixture.storeFile.toString(), json.getValue("store").jsonPrimitive.content)
        assertEquals("every project on this machine", json.getValue("scope").jsonPrimitive.content)
        assertEquals(project.value, json.getValue("project_id").jsonPrimitive.content)
        assertEquals(
            listOf("identifier", "attempt_number", "cost_usd", "guards"),
            json.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            JsonArray(
                listOf(
                    JsonArray(listOf(JsonPrimitive("t-1"), JsonPrimitive(1), JsonPrimitive(0.25), JsonNull)),
                    JsonArray(listOf(JsonPrimitive("t-2"), JsonPrimitive(1), JsonPrimitive(0.25), JsonNull)),
                ),
            ),
            json.getValue("rows"),
        )
        assertEquals(JsonPrimitive(false), json.getValue("rows_left_out"))
    }

    @Test
    fun `limit cuts the rows and a note says rows were left out`() {
        seedAttempts(5)

        val result = fixture.inProcess("query", "select identifier from attempts order by seq", "--limit", "2")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                scopeLine,
                "identifier",
                "----------",
                "t-1",
                "t-2",
                "2 rows printed, more were left out. --limit prints more, 1000 by default.",
            ),
            result.stdout.trimEnd().lines(),
        )
        val json = fixture.inProcess("query", "select identifier from attempts order by seq", "--limit", "2", "--format", "json")
        val parsed = Json.parseToJsonElement(json.stdout).jsonObject
        assertEquals(2, parsed.getValue("rows").jsonArray.size)
        assertEquals(JsonPrimitive(true), parsed.getValue("rows_left_out"))
    }

    @Test
    fun `at most 1000 rows are printed by default`() {
        seedAttempts(1)

        val result =
            fixture.inProcess(
                "query",
                "with recursive n(i) as (select 1 union all select i + 1 from n limit 1500) select i from n",
            )

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.trimEnd().lines()
        assertEquals("1000", lines[lines.size - 2])
        assertEquals("1000 rows printed, more were left out. --limit prints more, 1000 by default.", lines.last())
    }

    @Test
    fun `a limit below 1 is a usage error`() {
        seedAttempts(1)

        val result = fixture.inProcess("query", "select 1", "--limit", "0")

        assertEquals(2, result.exitCode, result.toString())
    }

    @Test
    fun `tables lists every table of the store with its columns`() {
        seedAttempts(1)

        val result = fixture.inProcess("query", "--tables")

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.trimEnd().lines()
        assertEquals(scopeLine, lines.first())
        val tables = lines.drop(1).map { it.substringBefore(":") }
        assertEquals(tables.sorted(), tables)
        assertTrue("attempts" in tables && "events" in tables, result.stdout)
        assertTrue(lines.any { it.startsWith("events: seq, ts, run_id, project_id, ") }, result.stdout)
        val json = Json.parseToJsonElement(fixture.inProcess("query", "--tables", "--format", "json").stdout).jsonObject
        assertEquals(
            tables,
            json.getValue("tables").jsonArray.map {
                it.jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun `a statement and tables together, or neither, is a usage error`() {
        seedAttempts(1)

        assertEquals(2, fixture.inProcess("query").exitCode)
        assertEquals(2, fixture.inProcess("query", "select 1", "--tables").exitCode)
    }

    @Test
    fun `a syntax error exits 2 with SQLite's message`() {
        seedAttempts(1)

        val result = fixture.inProcess("query", "selec count(*) from attempts")

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("loop query: near \"selec\": syntax error.", result.stderr.trim())
        assertEquals("", result.stdout)
    }

    @Test
    fun `a missing store says so, exits 1 and creates nothing`() {
        val result = fixture.inProcess("query", "select 1")

        assertEquals(1, result.exitCode, result.toString())
        assertEquals("loop query: no store at ${fixture.storeFile} yet, loop run creates it.", result.stderr.trim())
        assertFalse(fixture.storeFile.exists(), "the query created the store")
    }

    /**
     * [sql] exits 4 with SQLite's [message], the store file is unchanged and no other file appears beside it but the
     * `-wal` and `-shm` files a WAL reader opens when no writer holds the store, the `-wal` one empty: every write
     * goes to it first, so it holds no frame.
     */
    private fun assertRefused(
        sql: String,
        message: String,
    ) {
        seedAttempts(2)
        val bytesBefore = fixture.storeFile.readBytes()
        val filesBefore = loopHomeFiles()

        val result = fixture.inProcess("query", sql)

        assertEquals(4, result.exitCode, result.toString())
        assertEquals("loop query: $message. The store is opened read only, nothing changed.", result.stderr.trim())
        assertContentEquals(bytesBefore, fixture.storeFile.readBytes())
        assertEquals(emptySet(), loopHomeFiles().toSet() - filesBefore.toSet() - READER_FILES, "files beside the store")
        val wal = fixture.loopHome.resolve("store.db-wal")
        assertEquals(0L, if (wal.exists()) wal.fileSize() else 0L, "frames in the WAL")
        assertContains(
            fixture
                .query("select count(*) as n from attempts")
                .single()
                .getValue("n")
                .orEmpty(),
            "2",
        )
    }

    private fun loopHomeFiles(): List<String> =
        fixture.loopHome
            .listDirectoryEntries()
            .map { it.fileName.toString() }
            .sorted()

    /** [count] ledger rows, `t-1` to `t-<count>`, each its issue's first attempt, then the store closed. */
    private fun seedAttempts(count: Int) {
        Files.createDirectories(fixture.loopHome)
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking {
                (1..count).forEach { n ->
                    store
                        .recordAttempt(
                            LedgerAttempt(
                                projectId = project,
                                runId = RunId("r-query"),
                                issueId = "t-$n",
                                identifier = "t-$n",
                                attemptId = AttemptId("a-$n"),
                                setup = LedgerSetup("files", "code", "claude-code", "claude-opus-5-5", "a".repeat(64), null),
                                startedAt = at - 1.hours,
                                finishedAt = at,
                                outcome = "succeeded",
                                reason = null,
                                turns = 1,
                                issueState = "todo",
                                tokens = LedgerTokens(1000, 100, 0, 0),
                                costUsd = 0.25,
                                checks = 1,
                                guards = null,
                                verdict = "verified",
                            ),
                        ).getOrThrow()
                }
            }
        }
    }

    private companion object {
        val READER_FILES = setOf("store.db-wal", "store.db-shm")
    }
}
