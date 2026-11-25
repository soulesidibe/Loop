package loop.store

import org.sqlite.ProgressHandler
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import org.sqlite.SQLiteLimits
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.time.Duration

/**
 * A read only connection to the machine store (ADR 0001), for `loop query` (m4-39): it runs one SQL statement over
 * every project of the machine. It never goes through [SqliteEventStore.open], which creates and migrates the store.
 *
 * Read only for real, by SQLite itself: the file is opened with `SQLITE_OPEN_READONLY`, `query_only` is on, so a
 * temporary table cannot be written either, extension loading is off, and the attached database limit is 0, so
 * `ATTACH` and `VACUUM INTO`, which can open another file for writing, fail. The statement is prepared, never handed
 * to the driver's own `backup to` and `restore from` commands. A progress handler stops it past its timeout, so a bad
 * join cannot hold the store against a running `loop run`. In WAL mode a reader never blocks a writer.
 */
class StoreQuery private constructor(
    private val connection: Connection,
) : AutoCloseable {
    /** The tables of the store, `sqlite_` ones left out, each with its columns in their order, by name. */
    fun tables(): Result<List<StoreTable>> =
        runCatching {
            val names =
                connection.prepareStatement(SELECT_TABLES).use { st ->
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            names.map { name ->
                val columns =
                    connection.prepareStatement(SELECT_COLUMNS).use { st ->
                        st.setString(1, name)
                        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                StoreTable(name, columns)
            }
        }.recoverCatching { throw StoreError.Unavailable(it) }

    /**
     * Runs the first statement of [sql] and reads at most [limit] rows, [QueryOutcome.Rows.more] set when there were
     * more. Past [timeout] the statement is stopped. A failure comes back as an outcome by what SQLite said.
     */
    fun run(
        sql: String,
        limit: Int,
        timeout: Duration,
    ): QueryOutcome {
        require(limit >= 1) { "limit must be at least 1, got $limit" }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        ProgressHandler.setHandler(
            connection,
            PROGRESS_STEPS,
            object : ProgressHandler() {
                override fun progress(): Int = if (System.nanoTime() - deadline > 0) 1 else 0
            },
        )
        return try {
            connection.prepareStatement(sql).use { st ->
                if (!st.execute()) return QueryOutcome.Rows(emptyList(), emptyList(), more = false)
                st.resultSet.use { rs -> rows(rs, limit) }
            }
        } catch (e: SQLException) {
            outcome(e, timeout)
        } finally {
            ProgressHandler.clearHandler(connection)
        }
    }

    private fun rows(
        rs: ResultSet,
        limit: Int,
    ): QueryOutcome.Rows {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<List<QueryValue>>()
        var more = false
        while (rs.next()) {
            if (rows.size == limit) {
                more = true
                break
            }
            rows += columns.indices.map { value(rs.getObject(it + 1)) }
        }
        return QueryOutcome.Rows(columns, rows, more)
    }

    private fun value(cell: Any?): QueryValue =
        when (cell) {
            null -> QueryValue.Null
            is Int -> QueryValue.Integer(cell.toLong())
            is Long -> QueryValue.Integer(cell)
            is Float -> QueryValue.Real(cell.toDouble())
            is Double -> QueryValue.Real(cell)
            is ByteArray -> QueryValue.Blob(cell)
            else -> QueryValue.Text(cell.toString())
        }

    /** What [error], thrown by SQLite while it prepared or ran the statement, means for the command. */
    private fun outcome(
        error: SQLException,
        timeout: Duration,
    ): QueryOutcome {
        val code = (error as? SQLiteException)?.resultCode
        val message = sqliteMessage(error, code)
        return when (code?.code?.and(PRIMARY_CODE)) {
            SQLITE_READONLY, SQLITE_AUTH -> QueryOutcome.Refused(message)
            SQLITE_INTERRUPT -> QueryOutcome.TimedOut(timeout)
            SQLITE_ERROR ->
                if (REFUSALS.any { message.startsWith(it) }) QueryOutcome.Refused(message) else QueryOutcome.Invalid(message)
            SQLITE_MISMATCH, SQLITE_RANGE, SQLITE_TOOBIG -> QueryOutcome.Invalid(message)
            else -> QueryOutcome.Failed(message)
        }
    }

    override fun close() {
        connection.close()
    }

    companion object {
        /**
         * Opens [storeFile] read only. Fails with [StoreError.Unavailable] when it cannot, a missing file included,
         * which it never creates.
         */
        fun open(storeFile: Path): Result<StoreQuery> =
            try {
                if (!Files.isRegularFile(storeFile)) throw NoSuchFileException(storeFile.toString(), null, "no store file")
                val config =
                    SQLiteConfig().apply {
                        setReadOnly(true)
                        enableLoadExtension(false)
                        setBusyTimeout(BUSY_TIMEOUT_MS)
                    }
                val connection = DriverManager.getConnection("jdbc:sqlite:$storeFile", config.toProperties())
                try {
                    (connection as SQLiteConnection).setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0)
                    connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
                    Result.success(StoreQuery(connection))
                } catch (e: Exception) {
                    connection.close()
                    throw e
                }
            } catch (e: Exception) {
                Result.failure(StoreError.Unavailable(e))
            }

        /** SQLite's own message in [error], without the driver's `[SQLITE_ERROR] ...` prefix when it has one. */
        private fun sqliteMessage(
            error: SQLException,
            code: SQLiteErrorCode?,
        ): String {
            val text = error.message.orEmpty()
            val prefix = code?.let { "[${it.name}] ${it.message} (" }
            return if (prefix != null && text.startsWith(prefix) && text.endsWith(")")) {
                text.removePrefix(prefix).removeSuffix(")")
            } else {
                text
            }
        }

        private const val SELECT_TABLES =
            "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name"
        private const val SELECT_COLUMNS = "SELECT name FROM pragma_table_info(?) ORDER BY cid"

        /** How many virtual machine steps run between two looks at the deadline. */
        private const val PROGRESS_STEPS = 1000
        private const val BUSY_TIMEOUT_MS = 5000
        private const val PRIMARY_CODE = 0xff
        private const val SQLITE_ERROR = 1
        private const val SQLITE_READONLY = 8
        private const val SQLITE_INTERRUPT = 9
        private const val SQLITE_TOOBIG = 18
        private const val SQLITE_MISMATCH = 20
        private const val SQLITE_AUTH = 23
        private const val SQLITE_RANGE = 25

        /**
         * The messages of an `SQLITE_ERROR` that is a refusal: the attached database limit of 0, for `ATTACH` and
         * `VACUUM INTO`, and `load_extension` with extension loading off.
         */
        private val REFUSALS = listOf("too many attached databases", "not authorized")
    }
}

/** A table of the store and its columns, as `loop query --tables` lists them. */
data class StoreTable(
    val name: String,
    val columns: List<String>,
)

/** What a statement of [StoreQuery.run] gave. */
sealed interface QueryOutcome {
    /** The [columns] and the first [rows], [more] when rows past the limit were left out. */
    data class Rows(
        val columns: List<String>,
        val rows: List<List<QueryValue>>,
        val more: Boolean,
    ) : QueryOutcome

    /** SQLite refused a write, an `ATTACH` or an extension, with its own [message]. Nothing changed. */
    data class Refused(
        val message: String,
    ) : QueryOutcome

    /** SQLite could not prepare or run the statement, a syntax error or an unknown table, with its own [message]. */
    data class Invalid(
        val message: String,
    ) : QueryOutcome

    /** The statement ran past its [timeout] and was stopped. */
    data class TimedOut(
        val timeout: Duration,
    ) : QueryOutcome

    /** The store could not be read, busy, damaged or gone, with SQLite's [message]. */
    data class Failed(
        val message: String,
    ) : QueryOutcome
}

/** One value of a row, by its SQLite storage class. */
sealed interface QueryValue {
    data object Null : QueryValue

    data class Integer(
        val value: Long,
    ) : QueryValue

    data class Real(
        val value: Double,
    ) : QueryValue

    data class Text(
        val value: String,
    ) : QueryValue

    class Blob(
        val bytes: ByteArray,
    ) : QueryValue
}
