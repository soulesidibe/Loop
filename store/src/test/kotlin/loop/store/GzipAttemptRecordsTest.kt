package loop.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m1-13: the per attempt gzip JSONL file, `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.jsonl.gz`. */
class GzipAttemptRecordsTest {
    @TempDir
    lateinit var root: Path

    private val project = ProjectId("p1")
    private val attempt = AttemptId("a1")
    private val ts = Instant.parse("2026-11-11T10:00:00Z")

    private fun fileOf(
        projectId: ProjectId,
        attemptId: AttemptId,
    ): Path = root.resolve("attempts").resolve(projectId.value).resolve("${attemptId.value}.jsonl.gz")

    private fun records(redactor: Redactor = Redactor.NONE) = GzipAttemptRecords(redactor, ::fileOf)

    /** The decompressed lines of the file, every gzip member. */
    private fun rawLines(file: Path): List<String> =
        GZIPInputStream(Files.newInputStream(file)).use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotEmpty() }

    @Test
    fun `a prompt record is written as one json line with type, seq and ts and read back`() {
        val records = records()

        val written = records.append(project, attempt, AttemptRecord.Prompt(ts, turn = 1, text = "Work on t-1:\n\"quoted\""))

        assertTrue(written.isSuccess, written.toString())
        val line = Json.parseToJsonElement(rawLines(fileOf(project, attempt)).single())
        assertEquals(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("prompt"),
                    "seq" to JsonPrimitive(1),
                    "ts" to JsonPrimitive("2026-11-11T10:00:00Z"),
                    "turn" to JsonPrimitive(1),
                    "text" to JsonPrimitive("Work on t-1:\n\"quoted\""),
                ),
            ),
            line,
        )
        assertEquals(
            listOf(AttemptRecordLine(1, AttemptRecord.Prompt(ts, 1, "Work on t-1:\n\"quoted\""))),
            records.read(project, attempt).getOrThrow().records,
        )
    }

    @Test
    fun `each append is its own gzip member and seq goes on, also for a new writer on the same file`() {
        records().append(project, attempt, AttemptRecord.Prompt(ts, 1, "one")).getOrThrow()
        val afterOne = fileOf(project, attempt).readBytes()
        records().append(project, attempt, AttemptRecord.Prompt(ts, 2, "two")).getOrThrow()
        val shared = records()
        shared.append(project, attempt, AttemptRecord.Prompt(ts, 3, "three")).getOrThrow()

        val bytes = fileOf(project, attempt).readBytes()
        // The first member is left as it was: appends never rewrite the file.
        assertTrue(bytes.size > afterOne.size && bytes.copyOf(afterOne.size).contentEquals(afterOne))
        assertEquals(
            listOf(1L, 2L, 3L),
            shared
                .read(project, attempt)
                .getOrThrow()
                .records
                .map { it.seq },
        )
        assertEquals(listOf("one", "two", "three"), texts(shared))
    }

    @Test
    fun `the text is redacted before it is written`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) }

        records(redactor).append(project, attempt, AttemptRecord.Prompt(ts, 1, "token \"$secret\" here")).getOrThrow()

        val raw = rawLines(fileOf(project, attempt)).single()
        assertFalse(secret in raw, raw)
        assertEquals(
            "token \"[redacted:LOOP_TEST_SECRET]\" here",
            (
                records()
                    .read(project, attempt)
                    .getOrThrow()
                    .records
                    .single()
                    .record as AttemptRecord.Prompt
            ).text,
        )
    }

    @Test
    fun `the file is owner only and attempts of two projects do not mix`() {
        records().append(project, attempt, AttemptRecord.Prompt(ts, 1, "mine")).getOrThrow()
        records().append(ProjectId("p2"), attempt, AttemptRecord.Prompt(ts, 1, "theirs")).getOrThrow()

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fileOf(project, attempt))))
        assertEquals(listOf("mine"), texts(records()))
    }

    @Test
    fun `a runner output line longer than 2000 characters is written in full and read back with its seq (m2-18)`() {
        val records = records()
        val long = "{\"type\":\"user\",\"text\":\"" + "x".repeat(4995) + "\"}"

        val prompt = records.append(project, attempt, AttemptRecord.Prompt(ts, 1, "p")).getOrThrow()
        val first = records.append(project, attempt, AttemptRecord.RunnerOutput(ts, RunnerEvent.Output.Stream.Stdout, long)).getOrThrow()
        val second = records.append(project, attempt, AttemptRecord.RunnerOutput(ts, RunnerEvent.Output.Stream.Stderr, "warn")).getOrThrow()

        assertEquals(listOf(1L, 2L, 3L), listOf(prompt, first, second))
        val raw = Json.parseToJsonElement(rawLines(fileOf(project, attempt))[1])
        assertEquals(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("runner_output"),
                    "seq" to JsonPrimitive(2),
                    "ts" to JsonPrimitive("2026-11-11T10:00:00Z"),
                    "stream" to JsonPrimitive("stdout"),
                    "line" to JsonPrimitive(long),
                ),
            ),
            raw,
        )
        assertEquals(
            listOf(
                AttemptRecordLine(2, AttemptRecord.RunnerOutput(ts, RunnerEvent.Output.Stream.Stdout, long)),
                AttemptRecordLine(3, AttemptRecord.RunnerOutput(ts, RunnerEvent.Output.Stream.Stderr, "warn")),
            ),
            records()
                .read(project, attempt)
                .getOrThrow()
                .records
                .drop(1),
        )
        assertTrue(long.length > 2000)
    }

    @Test
    fun `a runner output line is redacted before it is written`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) }

        records(redactor)
            .append(project, attempt, AttemptRecord.RunnerOutput(ts, RunnerEvent.Output.Stream.Stdout, "x".repeat(3000) + secret))
            .getOrThrow()

        val raw = rawLines(fileOf(project, attempt)).single()
        assertFalse(secret in raw, raw.takeLast(40))
        val read =
            records()
                .read(project, attempt)
                .getOrThrow()
                .records
                .single()
                .record as AttemptRecord.RunnerOutput
        assertEquals("x".repeat(3000) + "[redacted:LOOP_TEST_SECRET]", read.line)
    }

    @Test
    fun `a tool output record keeps the full text of a trimmed reply, redacted, and reads back with its tool (m3-14)`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) }
        val text = "y".repeat(9000) + secret

        val seq = records(redactor).append(project, attempt, AttemptRecord.ToolOutput(ts, "move", text)).getOrThrow()

        val raw = rawLines(fileOf(project, attempt)).single()
        assertTrue(raw.startsWith("""{"type":"tool_output","seq":1,"ts":"2026-11-11T10:00:00Z","tool":"move","text":"yyy"""), raw.take(80))
        assertFalse(secret in raw, raw.takeLast(40))
        val read =
            records()
                .read(project, attempt)
                .getOrThrow()
                .records
                .single()
        assertEquals(seq, read.seq)
        assertEquals(AttemptRecord.ToolOutput(ts, "move", "y".repeat(9000) + "[redacted:LOOP_TEST_SECRET]"), read.record)
    }

    @Test
    fun `no file reads as no records`() {
        assertEquals(emptyList(), records().read(project, attempt).getOrThrow().records)
    }

    @Test
    fun `a record of an unknown type is skipped on read`() {
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        val lines =
            """{"type":"session_brief","seq":1,"ts":"2026-11-11T10:00:00Z","text":"x"}""" + "\n" +
                """{"type":"prompt","seq":2,"ts":"2026-11-11T10:00:00Z","turn":1,"text":"hello"}""" + "\n"
        file.writeBytes(gzip(lines))

        assertEquals(
            listOf(AttemptRecordLine(2, AttemptRecord.Prompt(ts, 1, "hello"))),
            records().read(project, attempt).getOrThrow().records,
        )
    }

    @Test
    fun `a failed write is a failed result, never a throw`() {
        // The project folder is a file, so the attempt file cannot be created.
        root
            .resolve("attempts")
            .createDirectories()
            .resolve(project.value)
            .writeText("not a folder")

        val written = records().append(project, attempt, AttemptRecord.Prompt(ts, 1, "lost"))

        assertTrue(written.isFailure, written.toString())
    }

    @Test
    fun `a symlink in place of the file is not followed`() {
        val target = root.resolve("elsewhere.txt").also { it.writeText("") }
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        Files.createSymbolicLink(file, target)

        val written = records().append(project, attempt, AttemptRecord.Prompt(ts, 1, "lost"))

        assertTrue(written.isFailure, written.toString())
        assertEquals(0, target.readBytes().size)
    }

    @Test
    fun `a file that is not gzip is a failed read`() {
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        file.writeText("plain text")

        assertTrue(records().read(project, attempt).isFailure)
    }

    @Test
    fun `a cut last record leaves the records before it readable (m2-18)`() {
        val records = records()
        records.append(project, attempt, AttemptRecord.Prompt(ts, 1, "one")).getOrThrow()
        records.append(project, attempt, AttemptRecord.Prompt(ts, 2, "two")).getOrThrow()
        val file = fileOf(project, attempt)
        val beforeLast = file.readBytes()
        records.append(project, attempt, AttemptRecord.Prompt(ts, 3, "three " + "z".repeat(500))).getOrThrow()
        val all = file.readBytes()
        // A kill or a full disk in the middle of the last append: half of its gzip member is on disk.
        file.writeBytes(all.copyOf(beforeLast.size + (all.size - beforeLast.size) / 2))

        val read = records().read(project, attempt).getOrThrow()

        assertEquals(listOf(1L, 2L), read.records.map { it.seq })
        assertEquals(1, read.damaged)
    }

    @Test
    fun `a cut record followed by good ones skips the cut one only, and appends go on after it (m2-18)`() {
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        val two = gzip(promptLine(2, "two " + "z".repeat(500)))
        file.writeBytes(gzip(promptLine(1, "one")) + two.copyOf(two.size / 2) + gzip(promptLine(3, "three")))

        // A new writer reads the file to find the next seq, so the cut member does not stop it.
        val appended = records().append(project, attempt, AttemptRecord.Prompt(ts, 4, "four"))

        assertEquals(4L, appended.getOrThrow())
        val read = records().read(project, attempt).getOrThrow()
        assertEquals(listOf(1L, 3L, 4L), read.records.map { it.seq })
        assertEquals(listOf("one", "three", "four"), read.records.map { (it.record as AttemptRecord.Prompt).text })
        assertEquals(1, read.damaged)
    }

    @Test
    fun `a member whose bytes changed or whose text is not a record is skipped (m2-18)`() {
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        val two = gzip(promptLine(2, "two"))
        // One flipped byte in the compressed data: the member still ends, its checksum does not match.
        two[two.size - 10] = (two[two.size - 10].toInt() xor 0x01).toByte()
        file.writeBytes(gzip(promptLine(1, "one")) + two + gzip("not json\n") + gzip(promptLine(4, "four")))

        val read = records().read(project, attempt).getOrThrow()

        assertEquals(listOf(1L, 4L), read.records.map { it.seq })
        assertEquals(2, read.damaged)
    }

    @Test
    fun `an empty file reads as no records`() {
        val file = fileOf(project, attempt)
        file.parent.createDirectories()
        file.writeBytes(ByteArray(0))

        assertEquals(AttemptFileRecords(emptyList(), 0), records().read(project, attempt).getOrThrow())
    }

    /** A prompt record as [GzipAttemptRecords] writes it, with its newline. */
    private fun promptLine(
        seq: Int,
        text: String,
    ): String = """{"type":"prompt","seq":$seq,"ts":"2026-11-11T10:00:00Z","turn":1,"text":"$text"}""" + "\n"

    /** The texts of the prompt records of [attempt] in [project]. */
    private fun texts(records: GzipAttemptRecords): List<String> =
        records
            .read(project, attempt)
            .getOrThrow()
            .records
            .map { (it.record as AttemptRecord.Prompt).text }

    private fun gzip(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }
}
