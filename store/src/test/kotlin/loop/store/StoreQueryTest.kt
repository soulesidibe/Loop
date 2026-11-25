package loop.store

import kotlinx.coroutines.runBlocking
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** m4-39: the read only connection `loop query` runs its statement on. */
class StoreQueryTest {
    @TempDir
    lateinit var tmp: Path

    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val storeFile: Path get() = loopHome.resolve(SqliteEventStore.FILE_NAME)
    private val at = Instant.parse("2026-11-25T10:00:00Z")

    /** A store with [count] events, closed again, so no other connection holds it. */
    private fun seed(count: Int = 3) {
        SqliteEventStore.open(loopHome, Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow().use { store ->
            runBlocking { repeat(count) { store.record(RecordedEvent(at, project, "poll", data = mapOf("n" to "$it"))).getOrThrow() } }
        }
    }

    private fun run(
        sql: String,
        limit: Int = 1000,
    ): QueryOutcome = StoreQuery.open(storeFile).getOrThrow().use { it.run(sql, limit, 5.seconds) }

    @Test
    fun `a select comes back with its columns and typed values, on a store no other connection holds`() {
        seed()

        val outcome = run("select count(*) as n, 1.5 as r, 'a' as t, null as z, x'0102' as b from events")

        val rows = assertIs<QueryOutcome.Rows>(outcome)
        assertEquals(listOf("n", "r", "t", "z", "b"), rows.columns)
        val row = rows.rows.single()
        assertEquals(QueryValue.Integer(3), row[0])
        assertEquals(QueryValue.Real(1.5), row[1])
        assertEquals(QueryValue.Text("a"), row[2])
        assertEquals(QueryValue.Null, row[3])
        assertContentEquals(byteArrayOf(1, 2), assertIs<QueryValue.Blob>(row[4]).bytes)
        assertFalse(rows.more)
    }

    @Test
    fun `past the limit the rows are cut and more says so`() {
        seed(5)

        val cut = assertIs<QueryOutcome.Rows>(run("select seq from events order by seq", limit = 2))
        val whole = assertIs<QueryOutcome.Rows>(run("select seq from events order by seq", limit = 5))

        assertEquals(listOf(listOf(QueryValue.Integer(1)), listOf(QueryValue.Integer(2))), cut.rows)
        assertTrue(cut.more)
        assertEquals(5, whole.rows.size)
        assertFalse(whole.more)
    }

    @Test
    fun `writes, ATTACH, VACUUM INTO and load_extension are refused and the store file is unchanged`() {
        seed()
        val before = storeFile.readBytes()
        val other = tmp.resolve("other.db")

        val statements =
            listOf(
                "insert into events (ts, project_id, kind, data) values ('t', 'p', 'k', '{}')",
                "update events set kind = 'x'",
                "delete from events",
                "drop table events",
                "pragma user_version = 99",
                "attach database '$other' as other",
                "attach database 'file:$other?mode=rwc' as other",
                "vacuum into '$other'",
                "select load_extension('/no/such/extension')",
            )
        statements.forEach { sql -> assertIs<QueryOutcome.Refused>(run(sql), sql) }

        assertContentEquals(before, storeFile.readBytes())
        assertFalse(other.exists(), "a refused statement created $other")
    }

    @Test
    fun `a refusal carries SQLite's own message`() {
        seed()

        assertEquals(QueryOutcome.Refused("attempt to write a readonly database"), run("delete from events"))
    }

    @Test
    fun `a syntax error or an unknown table is invalid with SQLite's message`() {
        seed()

        assertEquals(QueryOutcome.Invalid("near \"selec\": syntax error"), run("selec 1"))
        assertEquals(QueryOutcome.Invalid("no such table: nope"), run("select * from nope"))
    }

    @Test
    fun `an empty text or a comment alone is invalid`() {
        seed()

        assertEquals(QueryOutcome.Invalid("the text holds no SQL statement"), run(""))
        assertEquals(QueryOutcome.Invalid("the text holds no SQL statement"), run("-- only a comment"))
        assertEquals(QueryOutcome.Invalid("the text holds no SQL statement"), run(" ; /* a comment */\n-- another\n;"))
        assertIs<QueryOutcome.Rows>(run("-- a comment\nselect 1 /* unclosed"))
    }

    @Test
    fun `the backup and restore commands of the driver are not run`() {
        seed()
        val before = storeFile.readBytes()
        val copy = tmp.resolve("copy.db")

        assertIs<QueryOutcome.Invalid>(run("backup to $copy"))
        assertIs<QueryOutcome.Invalid>(run("restore from $copy"))

        assertFalse(copy.exists())
        assertContentEquals(before, storeFile.readBytes())
    }

    @Test
    fun `a statement past its timeout is stopped`() {
        seed()

        val outcome =
            StoreQuery.open(storeFile).getOrThrow().use {
                it.run("with recursive n(i) as (select 1 union all select i + 1 from n) select count(*) from n", 10, 200.milliseconds)
            }

        assertEquals(QueryOutcome.TimedOut(200.milliseconds), outcome)
    }

    @Test
    fun `it reads while another connection holds a write transaction open`() {
        seed()
        DriverManager.getConnection("jdbc:sqlite:$storeFile").use { writer ->
            writer.createStatement().use { st ->
                st.execute("begin immediate")
                st.execute("insert into events (ts, project_id, kind, data) values ('t', 'p', 'k', '{}')")
            }

            val rows = assertIs<QueryOutcome.Rows>(run("select count(*) from events"))

            assertEquals(QueryValue.Integer(3), rows.rows.single().single())
            writer.createStatement().use { it.execute("rollback") }
        }
    }

    @Test
    fun `tables lists every table of the store with its columns`() {
        seed()

        val tables = StoreQuery.open(storeFile).getOrThrow().use { it.tables().getOrThrow() }

        val names = tables.map { it.name }
        assertEquals(names.sorted(), names)
        assertTrue("events" in names && "attempts" in names, names.toString())
        assertFalse(names.any { it.startsWith("sqlite_") }, names.toString())
        assertEquals(
            listOf("seq", "ts", "run_id", "project_id"),
            tables
                .single { it.name == "events" }
                .columns
                .take(4),
        )
    }

    @Test
    fun `opening a missing file fails and creates nothing`() {
        Files.createDirectories(loopHome)

        assertTrue(StoreQuery.open(storeFile).isFailure)

        assertFalse(storeFile.exists())
    }
}
