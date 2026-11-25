package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.HumanTouch
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
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** m4-38: `loop report --since`, read from the ledger rows and the events of the store. */
class ReportCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }
    private val now = Clock.System.now()
    private val project by lazy { ProjectId(fixture.projectId()) }

    private val opus = Setup("claude-code", "claude-opus-5-5", "a".repeat(64))
    private val codex = Setup("codex", null, "b".repeat(64))

    private data class Setup(
        val runner: String,
        val model: String?,
        val template: String,
    )

    @Test
    fun `report --since 7d prints delivered issues, zero touch deliveries, cost, refutation reasons and the comparison`() {
        seedWeek()

        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines()
        assertTrue(lines.first().startsWith("Report of project ${project.value} since "), result.stdout)
        assertTrue(lines.first().endsWith(" (7d)"), result.stdout)
        val body = lines.drop(1).joinToString("\n").trim()
        assertEquals(
            """
            Delivered issues: 3
              code, files: 2
              content, files: 1
            Delivered with zero human touches: 2 of 3 (not reliable, see bl-64)
            Cost per delivered issue: 2.00 usd on average over 2 issues and 4 attempts, 1 issue left out, its cost not reported
              t-1, code, files: 1.50 usd over 2 attempts
              t-2, content, files: not reported, 1 attempt
              t-3, code, files: 2.50 usd over 2 attempts

            Refutation reasons: 5 refuted verdicts
              check ./gradlew test failed: 2
              2 files not committed: 1
              a hidden check failed: 1
              check ./gradlew test failed in a merge check: 1

            Comparison by runner, model and template, attempts finished since the start of the window:
              claude-code, claude-opus-5-5, template aaaaaaaa: 4 attempts, 3 verified, 1 refuted, 2 delivered, 0.60 usd per attempt on average over 4 attempts
              codex, model not recorded, template bbbbbbbb: 2 attempts, 1 verified, 0 refuted, 1 delivered, 0.30 usd per attempt on average over 1 attempt, 1 not reported
            """.trimIndent(),
            body,
        )
    }

    @Test
    fun `an issue counts as delivered only through a verified host move in the window`() {
        seedWeek()

        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        val delivered = result.stdout.lines().filter { it.startsWith("  t-") }
        assertEquals(listOf("t-1", "t-2", "t-3"), delivered.map { it.trim().substringBefore(",") }, result.stdout)
        // t-4 has a verified attempt, a host move that was not verified and a verified move not by host: not delivered.
        // t-5 was delivered nine days ago, before the window.
        assertFalse(result.stdout.contains("t-4"), result.stdout)
        assertFalse(result.stdout.contains("t-5"), result.stdout)
    }

    @Test
    fun `a narrower window leaves out what was delivered before it`() {
        seedWeek()

        val result = fixture.inProcess("report", "--since", "36h")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Delivered issues: 2\n")
        assertContains(result.stdout, "Delivered with zero human touches: 2 of 2 (not reliable, see bl-64)")
        assertFalse(result.stdout.contains("  t-1,"), result.stdout)
    }

    @Test
    fun `every touch figure carries the not reliable mark`() {
        seedWeek()

        val result = fixture.inProcess("report")

        assertEquals(0, result.exitCode, result.toString())
        val touchLines = result.stdout.lines().filter { it.contains("touch") }
        assertTrue(touchLines.isNotEmpty(), result.stdout)
        touchLines.forEach { assertContains(it, "(not reliable, see bl-64)") }
        assertTrue(
            result.stdout
                .lines()
                .first()
                .endsWith(" (7d)"),
            "7 days by default: ${result.stdout}",
        )
    }

    @Test
    fun `a runner that reports no cost shows not reported, never zero, and is left out of the averages`() {
        attempt("t-7", "g1", codex, finished = now - 1.days, cost = null, verdict = "verified")
        moved("t-7", at = now - 1.days)

        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Cost per delivered issue: not reported, no delivered issue has a reported cost")
        assertContains(result.stdout, "  t-7, code, files: not reported, 1 attempt")
        val comparison = "codex, model not recorded, template bbbbbbbb: 1 attempt, 1 verified, 0 refuted, 1 delivered, cost not reported"
        assertContains(result.stdout, comparison)
        assertFalse(Regex("""\b0\.00 usd""").containsMatchIn(result.stdout), result.stdout)
    }

    @Test
    fun `an issue with one attempt not reporting its cost shows the partial sum and leaves the average`() {
        attempt("t-8", "h1", opus, finished = now - 2.days, cost = 0.8, verdict = "refuted")
        attempt("t-8", "h2", codex, finished = now - 1.days, cost = null, verdict = "verified")
        attempt("t-9", "i1", opus, finished = now - 1.days, cost = 1.25, verdict = "verified")
        moved("t-8", at = now - 1.days)
        moved("t-9", at = now - 1.days)

        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(
            result.stdout,
            "Cost per delivered issue: 1.25 usd on average over 1 issue and 1 attempt, 1 issue left out, its cost not reported",
        )
        assertContains(result.stdout, "  t-8, code, files: 0.80 usd over 1 of 2 attempts, 1 not reported")
    }

    @Test
    fun `a bad --since exits 2 with a usage line`() {
        listOf("7x", "0d", "-1d", "d", "7").forEach { value ->
            val result = fixture.inProcess("report", "--since", value)

            assertEquals(2, result.exitCode, "$value: $result")
            // The in process terminal colors the help, so the color codes are dropped before the check.
            val stderr = result.stderr.replace(Regex("\u001B\\[[0-9;]*m"), "")
            assertContains(stderr, "Usage: loop report", message = "$value: $result")
            assertContains(stderr, "--since", message = "$value: $result")
            assertEquals("", result.stdout, "$value: $result")
        }
    }

    @Test
    fun `an empty window prints a nothing delivered line and exits 0`() {
        attempt("t-5", "e1", opus, finished = now - 9.days, cost = 3.0, verdict = "verified")
        moved("t-5", at = now - 9.days)

        val result = fixture.inProcess("report", "--since", "24h")

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.trim().lines()
        assertTrue(lines[2].startsWith("Nothing delivered since "), result.stdout)
        assertTrue(lines[2].endsWith(" (24h)."), result.stdout)
        assertContains(result.stdout, "Refutation reasons: none")
        assertContains(result.stdout, "Comparison by runner, model and template: no attempt finished since the start of the window")
    }

    @Test
    fun `with no store yet it prints the nothing delivered line and creates no store`() {
        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Nothing delivered since ")
        assertFalse(fixture.storeFile.exists(), "the report created the store")
    }

    @Test
    fun `the report writes nothing to the store`() {
        seedWeek()
        val tables = listOf("events", "attempts", "parked_issues", "claims", "retry_states", "waiting_issues")
        val dump = { tables.associateWith { fixture.query("SELECT * FROM $it") } }
        val dumpBefore = dump()
        val bytesBefore = fixture.storeFile.readBytes()
        val filesBefore =
            fixture.loopHome
                .listDirectoryEntries()
                .map { it.fileName.toString() }
                .sorted()

        val result = fixture.inProcess("report", "--since", "7d")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(dumpBefore, dump())
        assertContentEquals(bytesBefore, fixture.storeFile.readBytes())
        assertEquals(
            filesBefore,
            fixture.loopHome
                .listDirectoryEntries()
                .map { it.fileName.toString() }
                .sorted(),
        )
    }

    /**
     * A week of work: t-1, t-2 and t-3 delivered in the window, t-4 verified but never delivered, t-5 delivered nine
     * days ago, and five refuted verdicts in the window plus one before it.
     */
    private fun seedWeek() {
        // t-1: refuted, then verified after one human touch, delivered two days ago.
        checks("a1", "t-1", now - 3.days - 1.hours, "./gradlew ktlintCheck" to "0", "./gradlew test" to "1")
        verdict("a1", "t-1", now - 3.days - 1.hours, "refuted")
        attempt("t-1", "a1", opus, finished = now - 3.days, cost = 1.0, verdict = "refuted")
        touch("t-1", now - 2.days - 2.hours)
        attempt("t-1", "a2", opus, finished = now - 2.days - 1.hours, cost = 0.5, verdict = "verified")
        moved("t-1", at = now - 2.days)
        // t-2: a runner with no cost, delivered yesterday.
        attempt("t-2", "b1", codex, finished = now - 1.days - 1.hours, cost = null, verdict = "verified", kind = "content")
        moved("t-2", at = now - 1.days)
        // t-3: its first attempt is before the window, still part of its cost; its merge check was refuted once.
        checks("c1", "t-3", now - 10.days, "./gradlew test" to "1")
        verdict("c1", "t-3", now - 10.days, "refuted")
        attempt("t-3", "c1", opus, finished = now - 10.days, cost = 2.0, verdict = "refuted")
        attempt("t-3", "c2", opus, finished = now - 1.days, cost = 0.5, verdict = "verified")
        checks("c2", "t-3", now - 20.hours, "./gradlew test" to "1")
        verdict("c2", "t-3", now - 20.hours, "refuted", mapOf("merge_check" to "true"))
        moved("t-3", at = now - 1.hours)
        // t-4: verified, moved by host without verification and by a turn with it, never delivered.
        checks("d0", "t-4", now - 2.days, "./gradlew test" to "0")
        seedEvents(event("check_finished", "d0", "t-4", now - 2.days, mapOf("check" to "1", "holdout" to "true", "result" to "failed")))
        verdict("d0", "t-4", now - 2.days, "refuted")
        checks("d1", "t-4", now - 1.days, "./gradlew test" to "1", "./gradlew test" to "0")
        verdict("d1", "t-4", now - 1.days, "refuted", mapOf("uncommitted" to "2"))
        verdict("d1", "t-4", now - 1.days, "verified")
        attempt("t-4", "d1", opus, finished = now - 1.days, cost = 0.4, verdict = "verified")
        seedEvents(
            event("issue_moved", null, "t-4", now - 1.days, mapOf("from" to "todo", "to" to "review", "by" to "host")),
            event("issue_moved", null, "t-4", now - 1.days, mapOf("from" to "todo", "to" to "review", "verified" to "true")),
        )
        // f1: a failed codex attempt with a cost.
        attempt("t-6", "f1", codex, finished = now - 1.days, cost = 0.3, verdict = null)
        // j1: one more refuted verdict on a plain check failure.
        checks("j1", "t-6", now - 12.hours, "./gradlew test" to "1")
        verdict("j1", "t-6", now - 12.hours, "refuted")
        // t-5: delivered nine days ago.
        attempt("t-5", "e1", opus, finished = now - 9.days, cost = 3.0, verdict = "verified")
        moved("t-5", at = now - 9.days)
    }

    private fun attempt(
        issue: String,
        attemptId: String,
        setup: Setup,
        finished: Instant,
        cost: Double?,
        verdict: String?,
        kind: String = "code",
    ) = withStore { store ->
        store
            .recordAttempt(
                LedgerAttempt(
                    projectId = project,
                    runId = RunId(RUN),
                    issueId = issue,
                    identifier = issue,
                    attemptId = AttemptId(attemptId),
                    setup = LedgerSetup("files", kind, setup.runner, setup.model, setup.template, null),
                    startedAt = finished - 1.hours,
                    finishedAt = finished,
                    outcome = if (verdict == null) "failed" else "succeeded",
                    reason = null,
                    turns = 1,
                    issueState = "todo",
                    tokens = LedgerTokens(1000, 100, 0, 0),
                    costUsd = cost,
                    checks = 1,
                    guards = null,
                    verdict = verdict,
                ),
            ).getOrThrow()
    }

    private fun moved(
        issue: String,
        at: Instant,
    ) = seedEvents(event("issue_moved", null, issue, at, mapOf("from" to "todo", "to" to "review", "by" to "host", "verified" to "true")))

    private fun touch(
        issue: String,
        at: Instant,
    ) = seedEvents(event(HumanTouch.EVENT, null, issue, at, mapOf(HumanTouch.TOUCH to HumanTouch.RESUME)))

    private fun checks(
        attemptId: String,
        issue: String,
        at: Instant,
        vararg commands: Pair<String, String>,
    ) = seedEvents(
        *commands
            .mapIndexed { index, (command, exit) ->
                event("check_finished", attemptId, issue, at, mapOf("check" to index.toString(), "command" to command, "exit_code" to exit))
            }.toTypedArray(),
    )

    private fun verdict(
        attemptId: String,
        issue: String,
        at: Instant,
        verdict: String,
        extra: Map<String, String> = emptyMap(),
    ) = seedEvents(event("verdict", attemptId, issue, at, mapOf("verdict" to verdict, "checks" to "1") + extra))

    private fun event(
        kind: String,
        attemptId: String?,
        issue: String,
        at: Instant,
        data: Map<String, String>,
    ): RecordedEvent =
        RecordedEvent(
            ts = at,
            projectId = project,
            kind = kind,
            runId = RunId(RUN),
            issueId = issue,
            issueIdentifier = issue,
            attemptId = attemptId?.let(::AttemptId),
            data = data,
        )

    private fun seedEvents(vararg events: RecordedEvent) = withStore { store -> events.forEach { store.record(it).getOrThrow() } }

    private fun withStore(block: suspend (SqliteEventStore) -> Unit) {
        Files.createDirectories(fixture.loopHome)
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { runBlocking { block(it) } }
    }

    private companion object {
        const val RUN = "r-report"
    }
}
