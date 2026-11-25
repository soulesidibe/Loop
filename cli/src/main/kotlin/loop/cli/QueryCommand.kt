package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.ProjectId
import loop.store.QueryOutcome
import loop.store.QueryValue
import loop.store.StoreQuery
import loop.store.StoreTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * `loop query "<sql>" [--workflow WORKFLOW.md] [--format table|json] [--limit N]` and `loop query --tables` (m4-39,
 * PLAN 10.4): one SQL statement over the machine store, every project of the machine, since a query may compare them.
 * The store is opened read only by SQLite itself through [StoreQuery], never created or migrated, so a write, an
 * `ATTACH` or `load_extension` fails with SQLite's own error and nothing changes. The rows are printed as stored: the
 * store holds redacted text only. Exit 0 printed, 1 startup failure or no store yet, 2 usage or a statement SQLite
 * cannot run, with its message, 3 store failure, 4 refused, 5 timed out.
 */
class QueryCommand(
    private val host: Host,
) : GuardedCommand("query", host) {
    private val sql by argument(name = "sql", help = "One SQL statement, only the first one runs").optional()
    private val tables by option("--tables", help = "List the tables of the store and their columns instead").flag()
    private val format by option("--format", help = "How the rows are printed, table by default")
        .choice(TABLE, JSON)
        .default(TABLE)
    private val limit by option("--limit", metavar = "N", help = "At most N rows are printed, 1000 by default")
        .int()
        .restrictTo(min = 1)
        .default(DEFAULT_LIMIT)
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Run one read only SQL statement over the machine store of every project"

    override fun body(scope: RedactorScope) {
        val statement = sql
        if (statement == null && !tables) throw UsageError("give a SQL statement, or --tables to list the tables")
        if (statement != null && tables) throw UsageError("give a SQL statement or --tables, not both")
        val project = host.openProject(workflowPath, Access.READ, scope)
        val storeFile = project.storeFile
        // A missing store would be created by any other open; the read only one only fails, less clearly.
        if (!Files.exists(storeFile)) throw StartupFailure("no store at $storeFile yet, loop run creates it")
        val store = StoreQuery.open(storeFile).getOrElse { fail("cannot open the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
        val header = Header(storeFile, project.projectId)
        store.use {
            if (statement == null) {
                val found = store.tables().getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                echo(if (format == JSON) tablesJson(header, found) else tablesText(header, found))
                return
            }
            when (val outcome = store.run(statement, limit, TIMEOUT)) {
                is QueryOutcome.Rows -> echo(if (format == JSON) rowsJson(header, outcome) else rowsText(header, outcome))
                is QueryOutcome.Invalid -> fail(outcome.message, EXIT_USAGE)
                is QueryOutcome.Refused -> fail("${outcome.message}. The store is opened read only, nothing changed", EXIT_REFUSED)
                is QueryOutcome.TimedOut -> fail("the statement ran past ${outcome.timeout} and was stopped, nothing changed", EXIT_TIMEOUT)
                is QueryOutcome.Failed -> fail("cannot read the store at $storeFile: ${outcome.message}", EXIT_STORE)
            }
        }
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop query: $message."), err = true)
        throw ProgramResult(code)
    }

    /** What the first line says: the store read and its scope, with the project of the workflow to filter on. */
    private class Header(
        val storeFile: Path,
        val projectId: ProjectId,
    ) {
        val line: String get() = "Store $storeFile: $SCOPE, this project is ${projectId.value}."

        fun json(vararg rest: Pair<String, JsonElement>): String =
            JsonObject(
                mapOf(
                    "store" to JsonPrimitive(storeFile.toString()),
                    "scope" to JsonPrimitive(SCOPE),
                    "project_id" to JsonPrimitive(projectId.value),
                ) + rest,
            ).toString()
    }

    private fun rowsText(
        header: Header,
        rows: QueryOutcome.Rows,
    ): String {
        val cells = rows.rows.map { row -> row.map { text(it) } }
        val widths = rows.columns.indices.map { i -> maxOf(rows.columns[i].length, cells.maxOfOrNull { it[i].length } ?: 0) }
        val line = { values: List<String> -> values.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString("  ").trimEnd() }
        val count = rows.rows.size
        val footer =
            when {
                rows.more -> "$count rows printed, more were left out. --limit prints more, $DEFAULT_LIMIT by default."
                count == 1 -> "1 row."
                else -> "$count rows."
            }
        val table =
            if (rows.columns.isEmpty()) {
                emptyList()
            } else {
                listOf(line(rows.columns), line(widths.map { "-".repeat(it) })) + cells.map(line)
            }
        return (listOf(header.line) + table + footer).joinToString("\n")
    }

    private fun rowsJson(
        header: Header,
        rows: QueryOutcome.Rows,
    ): String =
        header.json(
            "columns" to JsonArray(rows.columns.map(::JsonPrimitive)),
            "rows" to JsonArray(rows.rows.map { row -> JsonArray(row.map(::json)) }),
            "rows_left_out" to JsonPrimitive(rows.more),
        )

    private fun tablesText(
        header: Header,
        found: List<StoreTable>,
    ): String = (listOf(header.line) + found.map { "${it.name}: ${it.columns.joinToString(", ")}" }).joinToString("\n")

    private fun tablesJson(
        header: Header,
        found: List<StoreTable>,
    ): String =
        header.json(
            "tables" to
                JsonArray(
                    found.map {
                        JsonObject(mapOf("name" to JsonPrimitive(it.name), "columns" to JsonArray(it.columns.map(::JsonPrimitive))))
                    },
                ),
        )

    private companion object {
        const val TABLE = "table"
        const val JSON = "json"
        const val DEFAULT_LIMIT = 1000
        const val SCOPE = "every project on this machine"
        const val EXIT_STORE = 3
        const val EXIT_REFUSED = 4
        const val EXIT_TIMEOUT = 5

        /** How long a statement may run before it is stopped, so it cannot hold the store against a running `loop run`. */
        val TIMEOUT = 30.seconds
    }
}

/** A value as a table cell: `NULL`, a blob as `x'<hex>'`, a line break or a tab written `\n`, `\r` or `\t` so a row stays on one line. */
private fun text(value: QueryValue): String =
    when (value) {
        QueryValue.Null -> "NULL"
        is QueryValue.Integer -> value.value.toString()
        is QueryValue.Real -> value.value.toString()
        is QueryValue.Text ->
            value.value
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        is QueryValue.Blob -> hex(value.bytes)
    }

/** A value as JSON, by its storage class; a blob as the `x'<hex>'` text, an infinite real, which JSON has not, as text. */
private fun json(value: QueryValue): JsonElement =
    when (value) {
        QueryValue.Null -> JsonNull
        is QueryValue.Integer -> JsonPrimitive(value.value)
        is QueryValue.Real -> if (value.value.isFinite()) JsonPrimitive(value.value) else JsonPrimitive(value.value.toString())
        is QueryValue.Text -> JsonPrimitive(value.value)
        is QueryValue.Blob -> JsonPrimitive(hex(value.bytes))
    }

private fun hex(bytes: ByteArray): String = bytes.joinToString("", prefix = "x'", postfix = "'") { "%02x".format(it) }
