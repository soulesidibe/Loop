package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.RecordedEvent
import loop.api.SessionId
import loop.api.TurnId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m1-13: `loop show <attempt>` end to end through the installed launcher. */
class ShowCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `--events prints every recorded event of a finished attempt in recording order`() {
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!

        val result = fixture.loop("show", attemptId, "--events")

        assertEquals(0, result.exitCode, result.toString())
        val stored =
            fixture.query(
                "SELECT seq, ts, run_id, issue_id, issue_identifier, attempt_id, session_id, turn_id, kind, phase, data " +
                    "FROM events WHERE attempt_id = '$attemptId' ORDER BY seq",
            )
        assertTrue(stored.any { it["kind"] == "attempt_finished" }, "the attempt finished: $stored")
        val printed =
            result.stdout
                .lines()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(stored.size, printed.size, result.stdout)
        stored.zip(printed).forEach { (row, line) ->
            assertEquals(row["seq"], line.text("seq"), "$line")
            COLUMNS.forEach { column -> assertEquals(row[column], line.text(column), "$column of $line") }
            val data = row["data"]?.let { Json.parseToJsonElement(it) } ?: JsonObject(emptyMap())
            assertEquals(data, line["data"] ?: JsonObject(emptyMap()), "data of $line")
        }
    }

    @Test
    fun `--events prints a runner line longer than 2000 characters in full, from the attempt file (m2-18)`() {
        useLongLineAgent()
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!
        val stored =
            fixture
                .query("SELECT data FROM events WHERE attempt_id = '$attemptId' AND kind = 'runner_output' ORDER BY seq")
                .map { Json.parseToJsonElement(it["data"]!!).jsonObject }
        val storedLong = stored.single { it.text("chars") == "5000" }
        assertEquals(2000, storedLong.text("line")!!.length, "the store keeps a bounded preview")
        assertEquals("true", storedLong.text("truncated"))

        val result = fixture.inProcess("show", attemptId, "--events")

        assertEquals(0, result.exitCode, result.toString())
        val printed =
            result.stdout
                .lines()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.text("kind") == "runner_output" }
                .map { it["data"]!!.jsonObject }
        val long = printed.single { it.text("chars") == "5000" }
        assertEquals(LONG_LINE, long.text("line"))
        assertEquals(null, long.text("truncated"), "the printed line is not cut")
        assertEquals(storedLong.text("record_seq"), long.text("record_seq"))
        assertEquals("after the long line", printed.single { it.text("chars") == "19" }.text("line"))
    }

    @Test
    fun `--events prints the stored preview of a line whose record is gone`() {
        useLongLineAgent()
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!
        Files.delete(
            fixture.loopHome
                .resolve("attempts")
                .resolve(fixture.projectId())
                .resolve("$attemptId.jsonl.gz"),
        )

        val result = fixture.inProcess("show", attemptId, "--events")

        assertEquals(0, result.exitCode, result.toString())
        val long =
            result.stdout
                .lines()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject["data"]?.jsonObject ?: JsonObject(emptyMap()) }
                .single { it.text("chars") == "5000" }
        assertEquals(LONG_LINE.take(2000), long.text("line"))
        assertEquals("true", long.text("truncated"))
    }

    @Test
    fun `--events keeps the readable records of an attempt file whose record of the long line is cut, with a note (m2-18)`() {
        val (attemptId, file) = runLongLineAttempt()
        // The long line's record is cut in half, as by a kill or a full disk, and the records after it stay whole.
        rewriteWithLongLineCut(file, keepAfter = true)

        val result = fixture.inProcess("show", attemptId, "--events")

        assertEquals(0, result.exitCode, result.toString())
        val printed = runnerLines(result.stdout)
        val long = printed.single { it.text("chars") == "5000" }
        assertEquals(LONG_LINE.take(2000), long.text("line"), "the stored preview stands in for the cut record")
        assertEquals("true", long.text("truncated"))
        assertEquals("after the long line", printed.single { it.text("chars") == "19" }.text("line"))
        assertEquals(
            "loop show: the attempt file at $file has 1 cut or unreadable part, " +
                "a cut runner line whose record is missing prints its stored preview.\n",
            result.stderr,
        )
    }

    @Test
    fun `--events reads an attempt file with a cut tail, and --prompt still prints the prompt before it (m2-18)`() {
        val (attemptId, file) = runLongLineAttempt()
        // The file ends in the middle of the long line's record, as for a live attempt or a killed one.
        rewriteWithLongLineCut(file, keepAfter = false)

        val events = fixture.inProcess("show", attemptId, "--events")
        val prompt = fixture.inProcess("show", attemptId, "--prompt")

        assertEquals(0, events.exitCode, events.toString())
        assertEquals(LONG_LINE.take(2000), runnerLines(events.stdout).single { it.text("chars") == "5000" }.text("line"))
        assertContains(events.stderr, "loop show: the attempt file at $file has 1 cut or unreadable part")
        assertEquals(0, prompt.exitCode, prompt.toString())
        assertEquals("Work on t-1: Issue t-1\n", prompt.stdout)
        assertEquals("loop show: the attempt file at $file has 1 cut or unreadable part, left out.\n", prompt.stderr)
    }

    /** One finished attempt of the long line agent: its id and its attempt file. */
    private fun runLongLineAttempt(): Pair<String, Path> {
        useLongLineAgent()
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!
        val file =
            fixture.loopHome
                .resolve("attempts")
                .resolve(fixture.projectId())
                .resolve("$attemptId.jsonl.gz")
        return attemptId to file
    }

    /**
     * Writes [file] again one gzip member per record, as the orchestrator does, with the long line's member cut
     * in half, and the members after it kept when [keepAfter].
     */
    private fun rewriteWithLongLineCut(
        file: Path,
        keepAfter: Boolean,
    ) {
        val lines = gunzip(file).lines().filter { it.isNotEmpty() }
        val long = lines.indexOfFirst { LONG_LINE in it }
        assertTrue(long > 0, lines.toString())
        val cut = gzip(lines[long] + "\n").let { it.copyOf(it.size / 2) }
        val before = lines.take(long).map { gzip(it + "\n") }
        val after = if (keepAfter) lines.drop(long + 1).map { gzip(it + "\n") } else emptyList()
        file.writeBytes((before + listOf(cut) + after).reduce { acc, bytes -> acc + bytes })
    }

    /** The `data` of each `runner_output` event `--events` printed. */
    private fun runnerLines(stdout: String): List<JsonObject> =
        stdout
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.text("kind") == "runner_output" }
            .map { it["data"]!!.jsonObject }

    /** An agent that prints one 5000 character line and a short one, then hands off. */
    private fun useLongLineAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "$LONG_LINE"
            |echo "after the long line"
            |loop tool move review
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `--prompt prints the turn 1 rendered prompt of a finished attempt, as recorded beside its attempt directory`() {
        fixture.issue("t-1", title = "Say hello")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!

        val result = fixture.inProcess("show", attemptId, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Work on t-1: Say hello\n", result.stdout)
        val folder = fixture.loopHome.resolve("attempts").resolve(fixture.projectId())
        assertFalse(folder.resolve(attemptId).exists(), "the attempt directory goes when the attempt ends")
        // The prompt is the first record; the runner lines follow it (m2-18).
        val record = Json.parseToJsonElement(gunzip(folder.resolve("$attemptId.jsonl.gz")).lines().first()).jsonObject
        assertEquals("prompt", record.text("type"))
        assertEquals("1", record.text("seq"))
        assertEquals("1", record.text("turn"))
        assertEquals(result.stdout.removeSuffix("\n"), record.text("text"))
    }

    @Test
    fun `--prompt shows the prompt redacted with the workflow secrets, as it was written`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(text.replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"))
        fixture.issue("t-1", title = "Use $secret")
        val env = mapOf("LOOP_TEST_SECRET" to secret)
        val run = fixture.loop("run", fixture.workflow.toString(), "--once", env = env)
        assertEquals(0, run.exitCode, run.toString())
        val attemptId = fixture.query("SELECT DISTINCT attempt_id FROM events WHERE attempt_id IS NOT NULL").single()["attempt_id"]!!

        val result = fixture.inProcess("show", attemptId, "--prompt", env = env)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Work on t-1: Use [redacted:LOOP_TEST_SECRET]\n", result.stdout)
        val written =
            gunzip(
                fixture.loopHome
                    .resolve("attempts")
                    .resolve(fixture.projectId())
                    .resolve("$attemptId.jsonl.gz"),
            )
        assertFalse(secret in written, written)
    }

    @Test
    fun `--prompt says when the attempt has no recorded prompt`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.inProcess("show", ATTEMPT, "--prompt")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("No prompt recorded for this attempt.\n", result.stdout)
    }

    @Test
    fun `an unreadable attempt file is an error with exit 3`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val file =
            fixture.loopHome
                .resolve("attempts")
                .resolve(fixture.projectId())
                .createDirectories()
                .resolve("$ATTEMPT.jsonl.gz")
        file.writeText("not gzip")

        val result = fixture.inProcess("show", ATTEMPT, "--prompt")

        assertEquals(3, result.exitCode, result.toString())
        assertContains(result.stderr, "loop show: cannot read the attempt file at $file")
        assertEquals("", result.stdout)
    }

    @Test
    fun `--cost prints the latest absolute totals and the spend of the attempt`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted(ATTEMPT, "t-1")
        fixture.seed(
            started,
            usage(
                started,
                attemptTokens = "812004",
                input = "800000",
                output = "4",
                cacheRead = "12000",
                cacheCreation = "0",
                cost = "0.11",
            ),
            usage(
                started,
                attemptTokens = "1204331",
                input = "1000000",
                output = "4331",
                cacheRead = "190000",
                cacheCreation = "10000",
                cost = "0.25",
            ),
            started.copy(kind = "attempt_finished", data = mapOf("outcome" to "succeeded", "turns" to "2")),
        )

        val result = fixture.inProcess("show", ATTEMPT, "--cost")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Attempt $ATTEMPT  issue t-1",
                "Tokens          1204331 of a 2000000 token cap",
                "Input           1000000",
                "Output          4331",
                "Cache read      190000",
                "Cache creation  10000",
                "Spend           0.25 USD",
            ),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `--cost says when no usage was recorded or the runner reported no spend`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted(ATTEMPT, "t-1")
        val other = fixture.attemptStarted(OTHER, "t-1")
        fixture.seed(
            started,
            other,
            usage(other, attemptTokens = "10", input = "4", output = "6", cacheRead = "0", cacheCreation = "0", cost = null),
        )

        val none = fixture.inProcess("show", ATTEMPT, "--cost")
        val noSpend = fixture.inProcess("show", OTHER, "--cost")

        assertEquals(0, none.exitCode, none.toString())
        assertEquals(listOf("Attempt $ATTEMPT  issue t-1", "No usage recorded for this attempt."), none.stdout.trimEnd().lines())
        assertEquals(0, noSpend.exitCode, noSpend.toString())
        assertContains(noSpend.stdout, "Spend           not reported")
    }

    @Test
    fun `m3-09 --cost prints the peak occupancy of each session of the attempt, and none for a session without occupancy`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted(ATTEMPT, "t-1")
        fixture.seed(
            started,
            turnStarted(started, "th1", "tu1"),
            contextUsage(started, "th1-tu1", latest = "300000", peak = "300000", window = "1000000"),
            turnStarted(started, "th1", "tu2"),
            contextUsage(started, "th1-tu2", latest = "120000", peak = "300000", window = "1000000"),
            // A second session, as a rotation starts one, whose model has no window.
            turnStarted(started, "th2", "tu3"),
            contextUsage(started, "th2-tu3", latest = "40000", peak = "50000", window = null),
            // A third session with no report: no peak, never a zero.
            turnStarted(started, "th3", "tu4"),
            usage(started, attemptTokens = "10", input = "4", output = "6", cacheRead = "0", cacheCreation = "0", cost = null),
        )

        val result = fixture.inProcess("show", ATTEMPT, "--cost")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "",
                "Peak occupancy per session",
                "Session 1       300000 tokens, 0.3000 of a 1000000 token window, thread th1",
                "Session 2       50000 tokens, window unknown, thread th2",
                "Session 3       not reported, thread th3",
                "Rotations       0",
            ),
            result.stdout
                .trimEnd()
                .lines()
                .takeLast(6),
            result.toString(),
        )
    }

    @Test
    fun `m3-09 --cost of a runner without usage nor occupancy says each session reports no peak`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted(ATTEMPT, "t-1")
        fixture.seed(started, turnStarted(started, "th1", "tu1"))

        val result = fixture.inProcess("show", ATTEMPT, "--cost")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Attempt $ATTEMPT  issue t-1",
                "No usage recorded for this attempt.",
                "",
                "Peak occupancy per session",
                "Session 1       not reported, thread th1",
                "Rotations       0",
            ),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `m3-11 --cost prints the attempt's rotations after its sessions, with the cap`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted(ATTEMPT, "t-1")
        fixture.seed(
            started,
            turnStarted(started, "th1", "tu1"),
            contextUsage(started, "th1-tu1", latest = "250000", peak = "250000", window = "1000000"),
            rotated(started, rotation = "1", old = "th1", new = "th2"),
            turnStarted(started, "th2", "tu2"),
            contextUsage(started, "th2-tu2", latest = "210000", peak = "210000", window = "1000000"),
            rotated(started, rotation = "2", old = "th2", new = "th3"),
            turnStarted(started, "th3", "tu3"),
            contextUsage(started, "th3-tu3", latest = "30000", peak = "30000", window = "1000000"),
        )

        val result = fixture.inProcess("show", ATTEMPT, "--cost")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Peak occupancy per session",
                "Session 1       250000 tokens, 0.2500 of a 1000000 token window, thread th1",
                "Session 2       210000 tokens, 0.2100 of a 1000000 token window, thread th2",
                "Session 3       30000 tokens, 0.0300 of a 1000000 token window, thread th3",
                "Rotations       2 of at most 5",
            ),
            result.stdout
                .trimEnd()
                .lines()
                .takeLast(5),
            result.toString(),
        )
    }

    @Test
    fun `a unique prefix of the attempt id selects the attempt`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptStarted(OTHER, "t-1"))

        val result = fixture.inProcess("show", ATTEMPT.take(8), "--cost")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Attempt $ATTEMPT  issue t-1", result.stdout.lines().first())
    }

    @Test
    fun `an ambiguous prefix is an error with exit 2`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptStarted(OTHER, "t-1"))

        val result = fixture.inProcess("show", "3d9a", "--cost")

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("loop show: \"3d9a\" matches 2 attempts in project ${fixture.projectId()}, give more of the id.\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `an unknown attempt id is a clear error with exit 2`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.inProcess("show", "no-such-attempt", "--events")

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("loop show: no attempt \"no-such-attempt\" in project ${fixture.projectId()}.\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `an attempt of another project is unknown here`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1").copy(projectId = loop.api.ProjectId("0123456789abcdef")))

        val result = fixture.inProcess("show", ATTEMPT, "--cost")

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "loop show: no attempt \"$ATTEMPT\" in project ${fixture.projectId()}.")
    }

    @Test
    fun `no view is a usage error`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val result = fixture.inProcess("show", ATTEMPT)

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "pick a view: --prompt, --events, --cost or --evidence")
    }

    private fun usage(
        started: RecordedEvent,
        attemptTokens: String,
        input: String,
        output: String,
        cacheRead: String,
        cacheCreation: String,
        cost: String?,
    ): RecordedEvent =
        started.copy(
            kind = "usage",
            data =
                buildMap {
                    put("attempt_tokens", attemptTokens)
                    put("max_tokens", "2000000")
                    put("input_tokens", input)
                    put("output_tokens", output)
                    put("cache_read_input_tokens", cacheRead)
                    put("cache_creation_input_tokens", cacheCreation)
                    cost?.let { put("total_cost_usd", it) }
                },
        )

    private fun turnStarted(
        started: RecordedEvent,
        thread: String,
        turn: String,
    ): RecordedEvent =
        started.copy(
            kind = "turn_started",
            sessionId = SessionId("$thread-$turn"),
            turnId = TurnId(turn),
            data = mapOf("thread_id" to thread, "turn_id" to turn),
        )

    private fun rotated(
        started: RecordedEvent,
        rotation: String,
        old: String,
        new: String,
    ): RecordedEvent =
        started.copy(
            kind = "session_rotated",
            data =
                mapOf(
                    "reason" to "soft_limit",
                    "rotation" to rotation,
                    "max_rotations" to "5",
                    "old_thread_id" to old,
                    "new_thread_id" to new,
                ),
        )

    private fun contextUsage(
        started: RecordedEvent,
        session: String,
        latest: String,
        peak: String,
        window: String?,
    ): RecordedEvent =
        started.copy(
            kind = "context_usage",
            sessionId = SessionId(session),
            data =
                buildMap {
                    put("latest_tokens", latest)
                    put("peak_tokens", peak)
                    window?.let {
                        put("window", it)
                        put("fraction", String.format(java.util.Locale.ROOT, "%.4f", latest.toDouble() / it.toDouble()))
                    }
                },
        )

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun gunzip(file: Path): String = GZIPInputStream(Files.newInputStream(file)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun gzip(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private companion object {
        const val ATTEMPT = "3d9a0c1e-0000-4000-8000-000000000001"
        const val OTHER = "3d9a77aa-0000-4000-8000-000000000002"

        /** 5000 characters, with a marker at each end so a cut shows. */
        val LONG_LINE = "start-" + "y".repeat(4990) + "-end"

        /** The stored columns `--events` prints as is, besides `seq` and `data`. */
        val COLUMNS = listOf("ts", "run_id", "issue_id", "issue_identifier", "attempt_id", "session_id", "turn_id", "kind", "phase")
    }
}
