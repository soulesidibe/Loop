package loop.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.AttemptRecordWriter
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunnerEvent
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream
import kotlin.time.Instant

/**
 * The Attempt files (m1-13): one gzip compressed JSONL file per Attempt at [fileOf], which the cli
 * builds as `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.jsonl.gz`, beside the attempt directory.
 * One json object per line, `{"type":..., "seq":..., "ts":..., ...}`, the shape m1-22 settled: `prompt`
 * records (m1-13), `runner_output` records, each runner line in full (m2-18), and `tool_output` records, the full text
 * of a tool reply trimmed for the agent (m3-14).
 *
 * Every append writes one complete gzip member, so the file is a series of concatenated members and
 * each record is whole on disk once [append] returns. Reads decode member by member ([GzipMembers]), so
 * one cut member costs its own record only. The text is redacted before the json encoding,
 * as [SqliteEventStore] does; reads return what was written. The file is created owner only and is
 * never opened through a symlink. Thread safe: appends are serialized on this instance.
 */
class GzipAttemptRecords(
    private val redactor: Redactor,
    private val fileOf: (ProjectId, AttemptId) -> Path,
) : AttemptRecordWriter {
    /** The next sequence number of each file this instance wrote to, guarded by `this`. */
    private val nextSeq = mutableMapOf<Path, Long>()

    override fun append(
        projectId: ProjectId,
        attemptId: AttemptId,
        record: AttemptRecord,
    ): Result<Long> =
        attempt {
            synchronized(this) {
                val file = fileOf(projectId, attemptId)
                // A file this instance has not seen yet goes on after the highest seq already in it, so a
                // cut or unreadable record left out on read never makes a seq repeat.
                val seq = nextSeq[file] ?: ((readFile(file).lines.mapNotNull(::seqOf).maxOrNull() ?: 0L) + 1)
                val line = encode(record, seq) + "\n"
                Files.createDirectories(file.parent)
                Files.newByteChannel(file, APPEND_OPTIONS, OWNER_ONLY).use { channel ->
                    GZIPOutputStream(Channels.newOutputStream(channel)).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                }
                nextSeq[file] = seq + 1
                seq
            }
        }

    /**
     * The records of [attemptId] in [projectId] in file order, empty when the file does not exist.
     * A record of a type this build does not know is skipped, so a file a later build extends still reads.
     * A cut or unreadable record (a kill or a full disk in the middle of an append, or a live attempt) is
     * left out and counted in [AttemptFileRecords.damaged], and the records around it still read (m2-18).
     * A file that is not gzip at all is a failed read.
     */
    fun read(
        projectId: ProjectId,
        attemptId: AttemptId,
    ): Result<AttemptFileRecords> =
        attempt {
            val file = readFile(fileOf(projectId, attemptId))
            var damaged = file.damaged
            val records =
                file.lines.mapNotNull { line ->
                    try {
                        decode(line)
                    } catch (e: Exception) {
                        // A whole gzip member whose text is not a record of a known shape.
                        damaged++
                        null
                    }
                }
            AttemptFileRecords(records, damaged)
        }

    /** The text lines of the good gzip members of [file], and how many stretches were cut or unreadable. */
    private fun readFile(file: Path): FileLines {
        val bytes =
            try {
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readBytes() }
            } catch (e: NoSuchFileException) {
                return FileLines(emptyList(), 0)
            }
        val members = GzipMembers.decode(bytes)
        val lines = members.members.flatMap { member -> member.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() } }
        return FileLines(lines, members.damaged)
    }

    /** The seq of a record line, or null when the line is not a json object with one. */
    private fun seqOf(line: String): Long? =
        try {
            Json
                .parseToJsonElement(line)
                .jsonObject[SEQ]
                ?.jsonPrimitive
                ?.long
        } catch (e: Exception) {
            null
        }

    private class FileLines(
        val lines: List<String>,
        val damaged: Int,
    )

    private fun encode(
        record: AttemptRecord,
        seq: Long,
    ): String {
        val fields =
            when (record) {
                is AttemptRecord.Prompt ->
                    mapOf(
                        TYPE to JsonPrimitive(PROMPT),
                        SEQ to JsonPrimitive(seq),
                        TS to JsonPrimitive(record.ts.toString()),
                        TURN to JsonPrimitive(record.turn),
                        // Redacted before the encoding, so an escaped quote cannot split a secret.
                        TEXT to JsonPrimitive(redactor.redact(record.text)),
                    )
                is AttemptRecord.RunnerOutput ->
                    mapOf(
                        TYPE to JsonPrimitive(RUNNER_OUTPUT),
                        SEQ to JsonPrimitive(seq),
                        TS to JsonPrimitive(record.ts.toString()),
                        STREAM to JsonPrimitive(record.stream.name.lowercase()),
                        // In full: the `runner_output` event keeps a preview, this record keeps the line.
                        LINE to JsonPrimitive(redactor.redact(record.line)),
                    )
                is AttemptRecord.ToolOutput ->
                    mapOf(
                        TYPE to JsonPrimitive(TOOL_OUTPUT),
                        SEQ to JsonPrimitive(seq),
                        TS to JsonPrimitive(record.ts.toString()),
                        TOOL to JsonPrimitive(record.tool),
                        TEXT to JsonPrimitive(redactor.redact(record.text)),
                    )
            }
        return JsonObject(fields).toString()
    }

    private fun decode(line: String): AttemptRecordLine? {
        val fields = Json.parseToJsonElement(line).jsonObject
        val seq = fields.getValue(SEQ).jsonPrimitive.long
        val ts = Instant.parse(fields.getValue(TS).jsonPrimitive.content)
        return when (fields.getValue(TYPE).jsonPrimitive.content) {
            PROMPT ->
                AttemptRecordLine(
                    seq,
                    AttemptRecord.Prompt(ts, fields.getValue(TURN).jsonPrimitive.int, fields.getValue(TEXT).jsonPrimitive.content),
                )
            RUNNER_OUTPUT -> {
                val name = fields.getValue(STREAM).jsonPrimitive.content
                // A stream this build does not know skips the record, like an unknown type.
                val stream =
                    RunnerEvent.Output.Stream.entries
                        .firstOrNull { it.name.lowercase() == name } ?: return null
                AttemptRecordLine(seq, AttemptRecord.RunnerOutput(ts, stream, fields.getValue(LINE).jsonPrimitive.content))
            }
            TOOL_OUTPUT ->
                AttemptRecordLine(
                    seq,
                    AttemptRecord.ToolOutput(ts, fields.getValue(TOOL).jsonPrimitive.content, fields.getValue(TEXT).jsonPrimitive.content),
                )
            else -> null
        }
    }

    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val TYPE = "type"
        const val SEQ = "seq"
        const val TS = "ts"
        const val TURN = "turn"
        const val TEXT = "text"
        const val PROMPT = "prompt"
        const val RUNNER_OUTPUT = "runner_output"
        const val STREAM = "stream"
        const val LINE = "line"
        const val TOOL_OUTPUT = "tool_output"
        const val TOOL = "tool"

        val APPEND_OPTIONS =
            setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}

/** The records read back from an Attempt file, and how many cut or unreadable parts were left out. */
data class AttemptFileRecords(
    val records: List<AttemptRecordLine>,
    val damaged: Int,
)

/** One record read back from an Attempt file, with its sequence number in that file. */
data class AttemptRecordLine(
    val seq: Long,
    val record: AttemptRecord,
)
