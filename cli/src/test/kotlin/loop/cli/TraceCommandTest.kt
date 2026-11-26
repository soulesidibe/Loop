package loop.cli

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Plan seam 8 and U1, end to end through the installed launcher; acceptance 3. */
class TraceCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `prints phases in order with their ids`() {
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val result = fixture.loop("trace", "t-1", "--full-ids", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        val out = result.stdout
        val storedPhases =
            fixture
                .query("SELECT phase FROM events WHERE issue_id = 'default:t-1' AND kind = 'phase' ORDER BY seq")
                .map { it["phase"]!! }
        assertEquals(EXPECTED_PHASES, storedPhases)
        val lines = out.lines()
        val printedPhases = lines.mapNotNull { line -> line.split(Regex("\\s+")).firstOrNull { it in PHASE_NAMES } }
        assertEquals(EXPECTED_PHASES, printedPhases, out)

        assertTrue(lines.first().startsWith("Issue t-1 "), out)
        assertTrue(lines.any { it.startsWith("Project ${fixture.projectId()}") }, out)
        val attemptLine = lines.single { it.startsWith("Attempt 1 ") }
        listOf("run_id", "attempt_id").forEach { column ->
            val ids = distinct(column)
            assertEquals(1, ids.size, "$column values: $ids")
            assertContains(attemptLine, ids.single())
        }
        listOf("session_id", "turn_id").forEach { column ->
            val ids = distinct(column)
            assertEquals(1, ids.size, "$column values: $ids")
            assertContains(out, ids.single())
        }
        assertTrue(lines.any { "issue_moved" in it && "to=review" in it }, out)

        // U1: the session id shows from the launch line, the turn id from the streaming line.
        val sessionPrefix = distinct("session_id").single().take(8)
        val turnPrefix = distinct("turn_id").single().take(8)
        assertContains(phaseLine(lines, "LaunchingAgentProcess"), "session=$sessionPrefix")
        assertContains(phaseLine(lines, "StreamingTurn"), "turn=$turnPrefix")
    }

    @Test
    fun `an issue without attempts prints that no attempt was recorded`() {
        fixture.issue("t-1")
        fixture.issue("t-2", labels = listOf("needs-triage"))
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val result = fixture.inProcess("trace", "t-2", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "No attempt recorded for this issue in project ${fixture.projectId()}.")
    }

    @Test
    fun `an unknown issue is reported on stderr with exit 2`() {
        fixture.issue("t-1")

        val result = fixture.inProcess("trace", "t-99", cwd = fixture.projectDir)

        assertEquals(2, result.exitCode, result.toString())
        assertContains(result.stderr, "loop trace: no issue \"t-99\" in the tracker at ")
        assertEquals("", result.stdout.trim())
    }

    // m4-47: a bare id is the primary tracker's, a namespaced id names its tracker.

    /** Seeds one finished attempt of [issueId], the core id, whose identifier is `t-1`. */
    private fun seedAttemptOf(issueId: String) {
        val started = fixture.attemptStarted("a-1", issueId).copy(issueIdentifier = "t-1")
        fixture.seed(started, started.copy(kind = "attempt_finished", data = mapOf("outcome" to "succeeded", "turns" to "1")))
    }

    /** Adds the extra `files` tracker `local` to the workflow, so ids are shown namespaced. */
    private fun withExtraTracker() {
        fixture.projectDir
            .resolve("other")
            .toFile()
            .mkdirs()
        fixture.writeWorkflow(extra = "trackers:\n  - id: local\n    kind: files\n    provider:\n      dir: ./other\n")
    }

    @Test
    fun `m4-47 a bare and a namespaced id of the primary tracker trace the same issue, shown bare with no trackers`() {
        fixture.issue("t-1")
        seedAttemptOf("default:t-1")

        val bare = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)
        val namespaced = fixture.inProcess("trace", "default:t-1", cwd = fixture.projectDir)

        listOf(bare, namespaced).forEach { result ->
            assertEquals(0, result.exitCode, result.toString())
            assertTrue(
                result.stdout
                    .lines()
                    .first()
                    .startsWith("Issue t-1 \"Issue t-1\""),
                result.toString(),
            )
            assertTrue(result.stdout.lines().any { it.startsWith("Attempt 1 ") }, result.toString())
        }
    }

    @Test
    fun `m4-47 with trackers, both forms trace the primary's issue and the heading shows the namespaced id`() {
        withExtraTracker()
        fixture.issue("t-1")
        seedAttemptOf("default:t-1")

        val bare = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)
        val namespaced = fixture.inProcess("trace", "default:t-1", cwd = fixture.projectDir)

        listOf(bare, namespaced).forEach { result ->
            assertEquals(0, result.exitCode, result.toString())
            assertTrue(
                result.stdout
                    .lines()
                    .first()
                    .startsWith("Issue default:t-1 \"Issue t-1\""),
                result.toString(),
            )
        }
    }

    @Test
    fun `m4-47 a namespaced id of an extra tracker traces its issue from the store, not the primary's`() {
        withExtraTracker()
        fixture.issue("t-1")
        seedAttemptOf("local:t-1")

        val result = fixture.inProcess("trace", "local:t-1", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Issue local:t-1", result.stdout.lines().first(), result.toString())
        assertTrue(result.stdout.lines().any { it.startsWith("Attempt 1 ") }, result.toString())
        // The bare id is the primary's t-1, which has no attempt.
        val primary = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)
        assertContains(primary.stdout, "No attempt recorded for this issue")
    }

    @Test
    fun `m4-47 a namespaced id whose tracker is not in the registry is refused with exit 2, naming the tracker`() {
        fixture.issue("t-1")
        seedAttemptOf("default:t-1")

        val result = fixture.inProcess("trace", "gh:t-1", cwd = fixture.projectDir)

        assertEquals(2, result.exitCode, result.toString())
        assertEquals("loop trace: no tracker \"gh\" in this project, its tracker is default.", result.stderr.trim())
        assertEquals("", result.stdout.trim())
    }

    // The Loop version in the attempt header, m1-12.

    /** Seeds one finished attempt of t-1 whose `attempt_started` carries [startedData], and returns its trace. */
    private fun traceOfAttempt(
        startedData: Map<String, String>,
        vararg args: String,
    ): LoopResult {
        fixture.issue("t-1")
        val started = fixture.attemptStarted("a-1", "t-1").copy(data = startedData)
        fixture.seed(started, started.copy(kind = "attempt_finished", data = mapOf("outcome" to "succeeded", "turns" to "3")))
        val result = fixture.inProcess("trace", "t-1", *args, cwd = fixture.projectDir)
        assertEquals(0, result.exitCode, result.toString())
        return result
    }

    private fun attemptLine(result: LoopResult): String = result.stdout.lines().single { it.startsWith("Attempt 1 ") }

    @Test
    fun `the attempt header shows the loop version between the start time and the status`() {
        val version = "3f2a9c1d7e4b-dirty"

        val result = traceOfAttempt(mapOf("loop_version" to version))

        val header = attemptLine(result)
        val startedAt = """\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ"""
        val expected = Regex("""Attempt 1  a-1  run 5b2f0c3d  $startedAt  loop 3f2a9c1d7e4b-dirty  succeeded in 0s, 3 turns""")
        assertTrue(expected.matches(header), header)
        assertEquals(1, Regex("3f2a9c1d7e4b").findAll(result.stdout).count(), "the version is printed once\n${result.stdout}")
        // The run redactor knows no pattern that matches a commit, so the store keeps it as is.
        assertEquals("loop_version=$version", SecretRedactor().redact("loop_version=$version"))
    }

    @Test
    fun `--full-ids leaves the loop version alone`() {
        val header = attemptLine(traceOfAttempt(mapOf("loop_version" to "3f2a9c1d7e4b"), "--full-ids"))

        assertContains(header, "run ${LoopFixture.RUN_ID}  ")
        assertContains(header, "Z  loop 3f2a9c1d7e4b  succeeded in 0s")
    }

    @Test
    fun `an attempt recorded before m1-12 has no loop column`() {
        val header = attemptLine(traceOfAttempt(emptyMap()))

        assertTrue(Regex("""Z  succeeded in 0s, 3 turns$""").containsMatchIn(header), header)
        assertFalse("loop" in header, header)
    }

    @Test
    fun `an unknown version prints as loop unknown`() {
        val header = attemptLine(traceOfAttempt(mapOf("loop_version" to "unknown")))

        assertContains(header, "Z  loop unknown  succeeded in 0s")
    }

    // The config version in the attempt header, m1-15.

    @Test
    fun `the attempt header shows the config version after the loop version`() {
        val hash = "9c1d7e4b3f2a" + "0".repeat(52)

        val header = attemptLine(traceOfAttempt(mapOf("loop_version" to "3f2a9c1d7e4b", "config_version" to hash)))

        assertContains(header, "Z  loop 3f2a9c1d7e4b  config 9c1d7e4b  succeeded in 0s, 3 turns")
    }

    @Test
    fun `--full-ids prints the config version in full`() {
        val hash = "9c1d7e4b3f2a" + "0".repeat(52)

        val header = attemptLine(traceOfAttempt(mapOf("config_version" to hash), "--full-ids"))

        assertContains(header, "Z  config $hash  succeeded in 0s")
    }

    @Test
    fun `an attempt recorded before m1-15 has no config column`() {
        val header = attemptLine(traceOfAttempt(mapOf("loop_version" to "3f2a9c1d7e4b")))

        assertFalse("config" in header, header)
    }

    @Test
    fun `trace shows hook events, with the output of failed ones`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted("a-1", "t-1")

        fun hook(data: Map<String, String>) = started.copy(kind = "hook_finished", data = data)
        fixture.seed(
            started,
            hook(
                mapOf(
                    "hook" to "after_create",
                    "command" to "sh after.sh",
                    "exit_code" to "0",
                    "timed_out" to "false",
                    "duration_ms" to "40",
                    "output" to "Preparing worktree\n",
                ),
            ),
            hook(
                mapOf(
                    "hook" to "before_run",
                    "command" to "sh before.sh",
                    "exit_code" to "3",
                    "timed_out" to "false",
                    "duration_ms" to "12",
                    "output" to "fetching\nfetch failed\n",
                ),
            ),
            hook(
                mapOf(
                    "hook" to "after_run",
                    "command" to "sleep 90",
                    "timed_out" to "true",
                    "duration_ms" to "60000",
                    "output" to "still waiting\n",
                ),
            ),
            fixture.attemptFinished("a-1", "t-1"),
        )

        val result = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines()
        val afterCreate = lines.single { "hook_finished" in it && "hook=after_create" in it }
        assertContains(afterCreate, "hook=after_create exit_code=0 timed_out=false duration_ms=40")
        assertFalse("Preparing worktree" in result.stdout, result.stdout)
        val beforeRun = lines.indexOfFirst { "hook=before_run exit_code=3 timed_out=false duration_ms=12" in it }
        assertTrue(beforeRun >= 0, result.stdout)
        assertEquals(listOf("fetching", "fetch failed"), lines.subList(beforeRun + 1, beforeRun + 3).map { it.trim() })
        val afterRun = lines.indexOfFirst { "hook=after_run timed_out=true duration_ms=60000" in it }
        assertTrue(afterRun >= 0, result.stdout)
        assertEquals("still waiting", lines[afterRun + 1].trim())
    }

    @Test
    fun `bl-04 a hook stopped by a cancel shows stopped=true after timed_out, with the output kept so far`() {
        fixture.issue("t-1")
        val started = fixture.attemptStarted("a-1", "t-1")
        fixture.seed(
            started,
            started.copy(
                kind = "hook_finished",
                data =
                    mapOf(
                        "hook" to "before_run",
                        "command" to "sh before.sh",
                        "timed_out" to "false",
                        "stopped" to "true",
                        "duration_ms" to "830",
                        "output" to "fetching\n",
                    ),
            ),
            fixture.attemptFinished("a-1", "t-1"),
        )

        val lines = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout.lines()

        val hook = lines.indexOfFirst { "hook_finished" in it }
        assertTrue(hook >= 0, lines.joinToString("\n"))
        assertTrue(lines[hook].endsWith("hook=before_run timed_out=false stopped=true duration_ms=830"), lines[hook])
        assertEquals("fetching", lines[hook + 1].trim())
    }

    @Test
    fun `bl-04 a hook run outside an attempt, such as before_remove, is an issue level block with its output when it failed`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event(
                    "hook_finished",
                    "13:48:50",
                    data =
                        mapOf(
                            "hook" to "before_remove",
                            "command" to "git worktree remove .",
                            "exit_code" to "128",
                            "timed_out" to "false",
                            "duration_ms" to "15",
                            "output" to "removing\nfatal: not a working tree\n",
                        ),
                ),
                *failedAttempt("a2", "13:49:01", "13:49:02").toTypedArray(),
            )

        val expected = "13:48:50  ${"hook_finished".padEnd(NAME_WIDTH)}hook=before_remove exit_code=128 timed_out=false duration_ms=15"
        val hook = lines.indexOf(expected)
        val first = lines.indexOfFirst { it.startsWith("Attempt 1 ") }
        val second = lines.indexOfFirst { it.startsWith("Attempt 2 ") }
        assertTrue(hook >= 0, lines.joinToString("\n"))
        assertTrue(first in 0 until hook && hook < second, lines.joinToString("\n"))
        val output = " ".repeat(10 + NAME_WIDTH)
        assertEquals(listOf("${output}removing", "${output}fatal: not a working tree"), lines.subList(hook + 1, hook + 3))
    }

    @Test
    fun `bl-04 a passing hook run outside an attempt prints its line alone`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event(
                    "hook_finished",
                    "13:48:50",
                    data =
                        mapOf(
                            "hook" to "before_remove",
                            "command" to "rm -rf build",
                            "exit_code" to "0",
                            "timed_out" to "false",
                            "duration_ms" to "4",
                            "output" to "removed\n",
                        ),
                ),
            )

        val hook = "13:48:50  ${"hook_finished".padEnd(NAME_WIDTH)}hook=before_remove exit_code=0 timed_out=false duration_ms=4"
        assertEquals(hook, lines.last { it.isNotBlank() }, lines.joinToString("\n"))
        assertFalse(lines.any { "removed" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `bl-04 the PreparingWorkspace phase line comes before the after_create and before_run hook lines`() {
        fixture.writeWorkflow(extra = "hooks:\n  after_create: echo created\n  before_run: echo before")
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val lines = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout.lines()

        val phase = lines.indexOf(phaseLine(lines, "PreparingWorkspace"))
        val afterCreate = lines.indexOfFirst { "hook_finished" in it && "hook=after_create" in it }
        val beforeRun = lines.indexOfFirst { "hook_finished" in it && "hook=before_run" in it }
        assertTrue(phase in 0 until afterCreate && afterCreate < beforeRun, lines.joinToString("\n"))
    }

    // Retries, parking and input required (m1-07, plan review item 10).

    private fun event(
        kind: String,
        time: String,
        attempt: String? = null,
        phase: String? = null,
        data: Map<String, String> = emptyMap(),
    ): RecordedEvent =
        RecordedEvent(
            ts = Instant.parse("2026-11-07T${time}Z"),
            projectId = ProjectId(fixture.projectId()),
            kind = kind,
            runId = attempt?.let { RunId(LoopFixture.RUN_ID) },
            issueId = "t-1",
            issueIdentifier = "t-1",
            attemptId = attempt?.let(::AttemptId),
            phase = phase,
            data = data,
        )

    /** A failed attempt: started at [start], failed with [reason] and finished at [end]. */
    private fun failedAttempt(
        attempt: String,
        start: String,
        end: String,
        reason: String = "exit_code=1",
    ): List<RecordedEvent> =
        listOf(
            event("attempt_started", start, attempt),
            event("phase", end, attempt, phase = "Failed", data = mapOf("reason" to reason)),
            event("attempt_finished", end, attempt, data = mapOf("outcome" to "failed", "reason" to reason)),
        )

    private fun trace(vararg events: RecordedEvent): List<String> {
        fixture.issue("t-1")
        fixture.seed(*events)
        val result = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir)
        assertEquals(0, result.exitCode, result.toString())
        return result.stdout.lines()
    }

    /** A timeline line inside an attempt block, in the existing column layout. */
    private fun line(
        time: String,
        name: String,
        details: String,
    ): String = "  $time  ${name.padEnd(NAME_WIDTH)}$details"

    @Test
    fun `m2-26 the merge check and the merge show in the attempt's timeline`() {
        val base = "89abcdef0123456789abcdef0123456789abcdef"
        val head = "0123456789abcdef0123456789abcdef01234567"
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event("base_fetched", "13:50:00", "a1", data = mapOf("sha" to base, "base" to "main")),
                event("merge_check_started", "13:50:01", "a1", data = mapOf("base_sha" to base, "round" to "1")),
                event(
                    "verdict",
                    "13:50:02",
                    "a1",
                    data = mapOf("verdict" to "verified", "checks" to "1", "fingerprint" to "git:x", "merge_check" to "true"),
                ),
                event(
                    "merge_check_finished",
                    "13:50:03",
                    "a1",
                    data = mapOf("outcome" to "merged", "base_sha" to base, "round" to "1", "head" to head),
                ),
                event("pull_request_merged", "13:50:04", "a1", data = mapOf("number" to "7", "sha" to head, "method" to "merge")),
            )

        assertContains(lines, line("13:50:00", "base_fetched", "base=main sha=$base"))
        assertContains(lines, line("13:50:01", "merge_check_started", "base_sha=$base round=1"))
        assertContains(lines, line("13:50:02", "verdict", "verified merge_check checks=1 fingerprint=git:x evidence=0"))
        assertContains(lines, line("13:50:03", "merge_check_finished", "outcome=merged round=1 head=$head"))
        assertContains(lines, line("13:50:04", "pull_request_merged", "number=7 sha=$head method=merge"))
    }

    @Test
    fun `m3-15 the CI wait shows in the attempt's timeline`() {
        val head = "0123456789abcdef0123456789abcdef01234567"
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event("ci_waiting", "13:50:05", "a1", data = mapOf("sha" to head)),
                event("ci_finished", "13:58:05", "a1", data = mapOf("outcome" to "red", "runs" to "30433642,30433650")),
                event(
                    "delivery_failed",
                    "13:58:06",
                    "a1",
                    data =
                        mapOf(
                            "step" to "ci",
                            "reason" to "Actions runs did not pass: lint (failure)",
                        ),
                ),
            )

        assertContains(lines, line("13:50:05", "ci_waiting", "sha=$head"))
        assertContains(lines, line("13:58:05", "ci_finished", "outcome=red runs=30433642,30433650"))
        assertContains(lines, line("13:58:06", "delivery_failed", "step=ci reason=\"Actions runs did not pass: lint (failure)\""))
    }

    @Test
    fun `m4-07 a CI run GitHub did not start shows as not_started in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event("ci_finished", "13:58:05", "a1", data = mapOf("outcome" to "not_started", "runs" to "43779352010")),
            )

        assertContains(lines, line("13:58:05", "ci_finished", "outcome=not_started runs=43779352010"))
    }

    @Test
    fun `bl-175 the pause of the delivery queue and its resume show in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event("ci_finished", "14:35:05", "a1", data = mapOf("outcome" to "timeout", "runs" to "30433642")),
                event("delivery_queue_paused", "14:35:06", "a1", data = mapOf("issue" to "t-1", "reason" to "ci_timeout")),
                event("delivery_queue_resumed", "15:02:00", "a1", data = mapOf("by" to "approve")),
            )

        assertContains(lines, line("14:35:06", "delivery_queue_paused", "issue=t-1 reason=ci_timeout"))
        assertContains(lines, line("15:02:00", "delivery_queue_resumed", "by=approve"))
    }

    @Test
    fun `bl-182 a failed early conflict check shows in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event(
                    "early_check_failed",
                    "14:35:06",
                    "a1",
                    data = mapOf("issue" to "t-2", "head" to "fedcba9876543210", "base" to "0123456789abcdef", "reason" to "timed out"),
                ),
            )

        assertContains(
            lines,
            line("14:35:06", "early_check_failed", "issue=t-2 head=fedcba9876543210 base=0123456789abcdef reason=\"timed out\""),
        )
    }

    @Test
    fun `bl-178 an early conflict of a queued merge wait shows in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event(
                    "merge_conflict_early",
                    "14:35:06",
                    "a1",
                    data = mapOf("issue" to "t-2", "base" to "0123456789abcdef", "paths" to "a.txt,b.txt"),
                ),
            )

        assertContains(lines, line("14:35:06", "merge_conflict_early", "issue=t-2 base=0123456789abcdef paths=a.txt,b.txt"))
    }

    @Test
    fun `m4-34 reaction_triggered and reaction_skipped show in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event(
                    "reaction_skipped",
                    "14:35:06",
                    "a1",
                    data =
                        mapOf(
                            "reaction" to "merge_conflict",
                            "action" to "notify_human",
                            "issue" to "t-1",
                            "pull_request" to "7",
                            "reason" to "notify_human",
                        ),
                ),
                event(
                    "reaction_triggered",
                    "14:36:06",
                    "a1",
                    data = mapOf("reaction" to "merge_conflict", "action" to "send_to_agent", "issue" to "t-1", "pull_request" to "7"),
                ),
            )

        assertContains(
            lines,
            line(
                "14:35:06",
                "reaction_skipped",
                "reaction=merge_conflict action=notify_human issue=t-1 pull_request=7 reason=notify_human",
            ),
        )
        assertContains(
            lines,
            line("14:36:06", "reaction_triggered", "reaction=merge_conflict action=send_to_agent issue=t-1 pull_request=7"),
        )
    }

    @Test
    fun `m2-28 the merge risk assessment and an auto merge fallback show in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded", "reason" to "waiting_approval")),
                event(
                    "merge_risk_assessed",
                    "13:50:05",
                    "a1",
                    data =
                        mapOf(
                            "alignment" to "aligned",
                            "impact" to "medium",
                            "verification" to "partial",
                            "rollback_cost" to "two_way",
                            "error_detection" to "n/a",
                            "floors" to "impact:medium,verification:partial",
                            "score" to "1.53",
                            "band" to "medium",
                            "reviewer" to "claude-sonnet-5-5",
                        ),
                ),
                event(
                    "auto_merge_fallback",
                    "13:50:06",
                    "a1",
                    data = mapOf("band" to "medium", "reason" to "the merge risk band is medium, an auto merge needs low"),
                ),
            )

        assertContains(
            lines,
            line(
                "13:50:05",
                "merge_risk_assessed",
                "band=medium score=1.53 alignment=aligned impact=medium verification=partial rollback_cost=two_way " +
                    "error_detection=n/a floors=impact:medium,verification:partial reviewer=claude-sonnet-5-5",
            ),
        )
        assertContains(
            lines,
            line("13:50:06", "auto_merge_fallback", "band=medium reason=\"the merge risk band is medium, an auto merge needs low\""),
        )
    }

    @Test
    fun `bl-137 merge_risk_assessed shows whether the reviewer's diff was cut and how many files the cut left out`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "merge_risk_assessed",
                    "13:50:05",
                    "a1",
                    data =
                        mapOf(
                            "alignment" to "aligned",
                            "impact" to "low",
                            "verification" to "strong",
                            "rollback_cost" to "two_way",
                            "error_detection" to "loud",
                            "floors" to "",
                            "score" to "1.00",
                            "band" to "medium",
                            "reviewer" to "claude-sonnet-5-5",
                            "prompt_tokens" to "2987",
                            "diff_cut" to "true",
                            "files_left_out" to "2",
                        ),
                ),
            )

        assertContains(
            lines,
            line(
                "13:50:05",
                "merge_risk_assessed",
                "band=medium score=1.00 alignment=aligned impact=low verification=strong rollback_cost=two_way " +
                    "error_detection=loud reviewer=claude-sonnet-5-5 prompt_tokens=2987 diff_cut=true files_left_out=2",
            ),
        )
    }

    @Test
    fun `bl-32 an unpinned diff base shows its reason in the attempt's timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("diff_base_unpinned", "13:48:41", "a1", data = mapOf("reason" to "cannot fetch main: network down")),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded")),
            )

        assertContains(lines, line("13:48:41", "diff_base_unpinned", "reason=\"cannot fetch main: network down\""))
    }

    @Test
    fun `turn input required shows its reason and the failed line carries it`() {
        val reason = "permission prompt for Bash"
        val failure = "turn_input_required: $reason"
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("turn_input_required", "13:48:42", "a1", data = mapOf("reason" to reason)),
                event("phase", "13:48:42", "a1", phase = "Failed", data = mapOf("reason" to failure)),
                event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "failed")),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:42", "turn_input_required", "reason=\"$reason\"") in lines, out)
        assertTrue(line("13:48:42", "Failed", "reason=\"$failure\"") in lines, out)
    }

    @Test
    fun `retry scheduled shows the retry number the delay the due time and the error`() {
        val retry =
            mapOf(
                "attempt" to "1",
                "delay_ms" to "10000",
                "due_at" to "2026-11-07T13:48:51Z",
                "error" to "exit_code=1",
                "failures" to "1",
            )
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event("retry_scheduled", "13:48:41", "a1", data = retry),
            )

        assertTrue(
            line("13:48:41", "retry_scheduled", "retry=1 delay=10s due=13:48:51 error=\"exit_code=1\"") in lines,
            lines.joinToString("\n"),
        )
    }

    @Test
    fun `bl-39 a runner_unavailable turn, the hold it started and the turn that lifted it show in the timelines`() {
        val reason = "turn_failed: Failed to authenticate. API Error: 403"
        val turn = mapOf("turn" to "1", "max_turns" to "10", "turn_id" to "turn-1")
        val hold =
            mapOf(
                "reason" to reason,
                "hold" to "1",
                "delay_ms" to "60000",
                "probe_at" to "2026-11-07T13:49:41Z",
                "retry_after_ms" to "30000",
            )
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("turn_failed", "13:48:41", "a1", data = turn + mapOf("reason" to reason, "category" to "runner_unavailable")),
                event("attempt_finished", "13:48:41", "a1", data = mapOf("outcome" to "failed")),
                event("runner_hold_started", "13:48:41", "a1", data = hold),
                event("attempt_started", "13:49:41", "a2"),
                event("turn_completed", "13:49:50", "a2", data = turn),
                event("runner_hold_lifted", "13:49:50", "a2", data = mapOf("holds" to "1", "held_ms" to "69000")),
                event("attempt_finished", "13:49:51", "a2", data = mapOf("outcome" to "succeeded")),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:41", "turn_failed", "number=1/10 turn=turn-1 category=runner_unavailable reason=\"$reason\"") in lines, out)
        assertTrue(
            line("13:48:41", "runner_hold_started", "hold=1 delay=1m probe=13:49:41 retry_after=30s reason=\"$reason\"") in lines,
            out,
        )
        assertTrue(line("13:49:50", "runner_hold_lifted", "holds=1 held=\"1m 9s\"") in lines, out)
    }

    @Test
    fun `issue parked shows the park reason the failures and the error`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event(
                    "issue_parked",
                    "13:48:41",
                    "a1",
                    data = mapOf("reason" to "max_attempts", "failures" to "5", "error" to "exit_code=1"),
                ),
            )

        assertTrue(
            line("13:48:41", "issue_parked", "reason=max_attempts failures=5 error=\"exit_code=1\"") in lines,
            lines.joinToString("\n"),
        )
    }

    @Test
    fun `issue unparked by resume is an issue level line between the attempt blocks`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event("issue_parked", "13:48:41", "a1", data = mapOf("reason" to "max_attempts", "failures" to "1")),
                event("issue_unparked", "13:49:00", data = mapOf("reason" to "resume")),
                *failedAttempt("a2", "13:49:01", "13:49:02").toTypedArray(),
            )

        val unparked = lines.indexOfFirst { it.trim() == "13:49:00  ${"issue_unparked".padEnd(NAME_WIDTH)}reason=resume" }
        val first = lines.indexOfFirst { it.startsWith("Attempt 1 ") }
        val second = lines.indexOfFirst { it.startsWith("Attempt 2 ") }
        assertTrue(unparked >= 0, lines.joinToString("\n"))
        assertTrue(first in 0 until unparked && unparked < second, lines.joinToString("\n"))
    }

    @Test
    fun `issue unparked by a tracker change shows the states`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event(
                    "issue_unparked",
                    "13:49:00",
                    data = mapOf("reason" to "tracker_changed", "from_state" to "todo", "to_state" to "in_progress"),
                ),
                *failedAttempt("a2", "13:49:01", "13:49:02").toTypedArray(),
            )

        val expected = "13:49:00  ${"issue_unparked".padEnd(NAME_WIDTH)}reason=tracker_changed from=todo to=in_progress"
        val unparked = lines.indexOfFirst { it.trim() == expected }
        assertTrue(unparked > lines.indexOfFirst { it.startsWith("Attempt 1 ") }, lines.joinToString("\n"))
        assertTrue(unparked < lines.indexOfFirst { it.startsWith("Attempt 2 ") }, lines.joinToString("\n"))
    }

    @Test
    fun `m4-10 the drain of a run the issue took part in prints as its own lines, the drain of another run does not`() {
        val project = ProjectId(fixture.projectId())
        val runLevel = { kind: String, time: String, run: String, data: Map<String, String> ->
            RecordedEvent(ts = Instant.parse("2026-11-07T${time}Z"), projectId = project, kind = kind, runId = RunId(run), data = data)
        }
        val other = "0be00000-0000-4000-8000-000000000000"
        val lines =
            trace(
                runLevel("run_draining", "13:40:00", other, mapOf("attempts" to "t-9")),
                event("attempt_started", "13:48:40", "a1"),
                runLevel("run_draining", "13:48:45", LoopFixture.RUN_ID, mapOf("attempts" to "t-1")),
                event("attempt_finished", "13:48:50", "a1", data = mapOf("outcome" to "succeeded")),
                runLevel("run_drained", "13:52:57", LoopFixture.RUN_ID, mapOf("drained_ms" to "252000")),
            )

        val draining = lines.indexOfFirst { it.trim() == "13:48:45  ${"run_draining".padEnd(NAME_WIDTH)}attempts=t-1" }
        val drained = lines.indexOfFirst { it.trim().startsWith("13:52:57  ${"run_drained".padEnd(NAME_WIDTH)}drained=") }
        assertTrue(draining > 0 && drained > draining, lines.joinToString("\n"))
        // Skip the Project header line, whose @TempDir path on Linux (/tmp/junit-9...) can contain "t-9".
        assertTrue(lines.filterNot { it.startsWith("Project ") }.none { "t-9" in it || "13:40:00" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `retry dropped shows its reason`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event("retry_dropped", "13:48:51", data = mapOf("reason" to "not_found")),
            )

        val dropped = lines.indexOfFirst { it.trim() == "13:48:51  ${"retry_dropped".padEnd(NAME_WIDTH)}reason=not_found" }
        assertTrue(dropped > lines.indexOfFirst { it.startsWith("Attempt 1 ") }, lines.joinToString("\n"))
    }

    @Test
    fun `a result whose checks run again after a restart says restored (m2-04)`() {
        val lines =
            trace(
                *failedAttempt("a1", "13:48:40", "13:48:41").toTypedArray(),
                event("result_submitted", "13:48:51", "a1", data = mapOf("to" to "review", "restored" to "true")),
            )

        assertTrue(
            lines.any { it.trim() == "13:48:51  ${"result_submitted".padEnd(NAME_WIDTH)}to=review restored" },
            lines.joinToString("\n"),
        )
    }

    /** A session of [turns] turns on thread `thr-1`, as the orchestrator records them (m1-06). */
    private fun turnSession(
        turns: Int,
        threadId: String = "thr-1",
    ): List<RecordedEvent> =
        buildList {
            add(event("attempt_started", "13:48:40", "a1"))
            add(
                event("phase", "13:48:40", "a1", phase = "LaunchingAgentProcess")
                    .copy(sessionId = loop.api.SessionId("thr-1-1")),
            )
            (1..turns).forEach { n ->
                val data = mapOf("turn" to "$n", "max_turns" to "10", "thread_id" to threadId, "turn_id" to "turn-$n")
                add(event("turn_started", "13:48:4$n", "a1", data = data))
                add(event("turn_completed", "13:48:4$n", "a1", data = data))
            }
            add(
                event(
                    "attempt_finished",
                    "13:50:14",
                    "a1",
                    data = mapOf("outcome" to "succeeded", "exit_reason" to "not_active", "turns" to "$turns"),
                ),
            )
        }

    @Test
    fun `each turn appears under its session`() {
        fixture.issue("t-1")
        fixture.seed(*turnSession(3).toTypedArray())

        val result = fixture.inProcess("trace", "t-1", "--full-ids", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines()
        val launches = lines.indices.filter { lines[it].split(Regex("\\s+")).getOrNull(2) == "LaunchingAgentProcess" }
        assertEquals(1, launches.size, result.stdout)
        assertContains(lines[launches.single()], "thread=thr-1")
        val turnLines = lines.filter { it.contains("turn_started") || it.contains("turn_completed") }
        assertEquals(6, turnLines.size, result.stdout)
        turnLines.forEach { assertContains(it, "turn=turn-") }
        assertTrue(turnLines.all { it.indexOf("turn=") > 0 && lines.indexOf(it) > launches.single() }, result.stdout)
        val started = lines.filter { "turn_started" in it }
        (1..3).forEach { n ->
            val l = started[n - 1]
            assertContains(l, "number=$n/10")
            assertContains(l, "turn=turn-$n")
            assertContains(l, "thread=thr-1")
        }
        assertContains(lines.single { "turn_completed" in it && "turn=turn-2" in it }, "number=2/10")
    }

    @Test
    fun `thread ids are shortened without full ids`() {
        val threadId = "thread-0123456789"
        val lines = trace(*turnSession(2, threadId).toTypedArray())

        val threadLines = lines.filter { "thread=" in it }
        // The launch line and one turn_started line per turn.
        assertEquals(3, threadLines.size, lines.joinToString("\n"))
        threadLines.forEach {
            assertContains(it, "thread=${threadId.take(8)}")
            assertFalse(threadId in it, it)
        }
    }

    @Test
    fun `the building prompt line shows the size of every prompt slot (m1-17)`() {
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val out = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout

        val line = phaseLine(out.lines(), "BuildingPrompt")
        val sizes =
            Regex(
                """BuildingPrompt +prompt_chars=(\d+) template=(\d+) issue=(\d+) """ +
                    """verify_findings=0 session_brief=0 lessons=0 context_sources=0 """,
            ).find(line)
                ?.groupValues
                ?.drop(1)
                ?.map { it.toInt() }
        assertTrue(sizes != null, line)
        val (total, template, issue) = sizes
        assertEquals(total, template + issue, line)
        assertTrue(issue > 0, line)
    }

    @Test
    fun `m3-13 the building prompt line shows the tokens of the prompt and of every slot, the limit and the counter`() {
        fixture.issue("t-1")
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val line = phaseLine(fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout.lines(), "BuildingPrompt")

        val tokens =
            Regex(
                """ prompt_tokens=(\d+) template_tokens=(\d+) issue_tokens=(\d+) verify_findings_tokens=0 session_brief_tokens=0 """ +
                    """lessons_tokens=0 context_sources_tokens=0 prompt_limit_tokens=23750 over_limit=false token_counter=estimate$""",
            ).find(line)
                ?.groupValues
                ?.drop(1)
                ?.map { it.toInt() }
        assertTrue(tokens != null, line)
        val (total, template, issue) = tokens
        assertEquals(total, template + issue, line)
        val chars = Regex("""prompt_chars=(\d+)""").find(line)!!.groupValues[1].toInt()
        assertEquals((chars + 2) / 3, total, "one token per 3 characters, rounded up: $line")
    }

    @Test
    fun `m3-13 with the command runner the limit is the smallest window, and a prompt over it is recorded and logged, not cut`() {
        fixture.writeWorkflow(
            extra =
                """
                |context:
                |  windows:
                |    test/small-model: 4000
                """.trimMargin(),
        )
        // The title alone is 900 characters, so the prompt is over the 200 tokens of 0.05 of the 4000 token window.
        fixture.issue("t-1", title = "t".repeat(900))
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val line = phaseLine(fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout.lines(), "BuildingPrompt")

        assertContains(line, " prompt_limit_tokens=200 over_limit=true token_counter=estimate")
        val tokens = Regex("""prompt_tokens=(\d+)""").find(line)!!.groupValues[1].toInt()
        assertTrue(tokens > 200, line)
        assertEquals("review", fixture.stateOf("t-1"), "the attempt goes on")
        // m3-14: the template does not show the description, and the title is never cut, so the fill cuts nothing.
        assertContains(line, " cut_template_tokens=0 cut_issue_tokens=0 cut_verify_findings_tokens=0")
        assertContains(
            fixture.humanLog.readText(),
            "t-1  the prompt is $tokens tokens, over its limit of 200 tokens, and no slot Loop may cut holds any of it. It is sent whole",
        )
    }

    @Test
    fun `m3-14 a prompt over its limit is cut to fit, and the building prompt line and the human log say what each slot lost`() {
        fixture.writeWorkflow(
            extra =
                """
                |context:
                |  windows:
                |    test/small-model: 4000
                """.trimMargin(),
            prompt = "Work on {{ issue.identifier }}: {{ issue.title }}\n{{ issue.description }}\n",
        )
        fixture.issue("t-1", criteria = (1..100).joinToString("\n") { "- [ ] Criterion $it holds." })
        val run = fixture.loop("run", fixture.workflow.toString(), "--once")
        assertEquals(0, run.exitCode, run.toString())

        val line = phaseLine(fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout.lines(), "BuildingPrompt")

        val cut =
            Regex(
                """ prompt_limit_tokens=200 over_limit=false token_counter=estimate cut_template_tokens=0 cut_issue_tokens=(\d+) """ +
                    """cut_verify_findings_tokens=0 cut_session_brief_tokens=0 cut_lessons_tokens=0 cut_context_sources_tokens=0$""",
            ).find(line)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertTrue(cut != null && cut > 0, line)
        val tokens = Regex("""prompt_tokens=(\d+)""").find(line)!!.groupValues[1].toInt()
        assertTrue(tokens <= 200, line)
        assertEquals("review", fixture.stateOf("t-1"))
        assertContains(
            fixture.humanLog.readText(),
            "t-1  the prompt was ${tokens + cut} tokens, over its limit of 200 tokens, so Loop cut $cut tokens: issue $cut",
        )
    }

    @Test
    fun `a building prompt recorded before slot sizes shows its length alone`() {
        fixture.issue("t-1")
        fixture.seed(
            event("attempt_started", "13:48:40", "a1"),
            event("phase", "13:48:41", "a1", phase = "BuildingPrompt", data = mapOf("prompt_chars" to "1234")),
            event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "succeeded")),
        )

        val out = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout

        assertTrue(phaseLine(out.lines(), "BuildingPrompt").endsWith("BuildingPrompt         prompt_chars=1234"), out)
    }

    @Test
    fun `a failed turn shows its reason and input required is not repeated`() {
        fixture.issue("t-1")
        val data = mapOf("turn" to "2", "max_turns" to "10", "thread_id" to "thr-1", "turn_id" to "turn-2")
        val reason = "turn_input_required: permission prompt"
        fixture.seed(
            event("attempt_started", "13:48:40", "a1"),
            event("turn_started", "13:48:41", "a1", data = data),
            event("turn_input_required", "13:48:42", "a1", data = mapOf("reason" to "permission prompt")),
            event("turn_failed", "13:48:42", "a1", data = data + ("reason" to reason)),
            event("attempt_finished", "13:48:42", "a1", data = mapOf("outcome" to "failed")),
        )

        val out = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout

        assertEquals(1, out.lines().count { "turn_input_required" in it }, out)
        assertFalse("turn_failed" in out, out)

        val plain = data + ("reason" to "exit_code=1")
        fixture.seed(
            event("attempt_started", "13:49:40", "a2"),
            event("turn_failed", "13:49:42", "a2", data = plain),
            event("attempt_finished", "13:49:42", "a2", data = mapOf("outcome" to "failed")),
        )
        fixture.seed(
            event("attempt_started", "13:50:40", "a3"),
            event("phase", "13:50:42", "a3", phase = "Succeeded", data = mapOf("exit_reason" to "not_active", "turns" to "3")),
            event("attempt_finished", "13:50:42", "a3", data = mapOf("outcome" to "succeeded")),
        )
        val again = fixture.inProcess("trace", "t-1", cwd = fixture.projectDir).stdout
        assertContains(again, "turn_failed".padEnd(NAME_WIDTH) + "number=2/10 turn=turn-2 reason=\"exit_code=1\"")
        val succeeded = again.lines().single { it.split(Regex("\\s+")).getOrNull(2) == "Succeeded" }
        assertContains(succeeded, "exit_reason=not_active")
        assertContains(succeeded, "turns=3")
    }

    @Test
    fun `continuation retry prints kind and 1s`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:41", "a1", data = mapOf("outcome" to "succeeded")),
                event(
                    "retry_scheduled",
                    "13:48:41",
                    "a1",
                    data =
                        mapOf(
                            "kind" to "continuation",
                            "attempt" to "1",
                            "delay_ms" to "1000",
                            "due_at" to "2026-11-07T13:48:42Z",
                            "failures" to "0",
                        ),
                ),
            )

        assertTrue(
            line("13:48:41", "retry_scheduled", "kind=continuation retry=1 delay=1s due=13:48:42") in lines,
            lines.joinToString("\n"),
        )
    }

    // No progress counter (m1-27).

    @Test
    fun `continuation retry prints no progress count 1 of 3`() {
        val counts = mapOf("no_progress" to "1", "max_continuations" to "3")
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:41", "a1", data = mapOf("outcome" to "succeeded")),
                event(
                    "retry_scheduled",
                    "13:48:41",
                    "a1",
                    data =
                        mapOf(
                            "kind" to "continuation",
                            "attempt" to "1",
                            "delay_ms" to "1000",
                            "due_at" to "2026-11-07T13:48:42Z",
                            "failures" to "0",
                        ) + counts,
                ),
                // The continuation found no free slot: requeued with an error, still carrying the count.
                event(
                    "retry_scheduled",
                    "13:48:42",
                    data =
                        mapOf(
                            "kind" to "error",
                            "attempt" to "2",
                            "delay_ms" to "20000",
                            "due_at" to "2026-11-07T13:49:02Z",
                            "error" to "no available orchestrator slots",
                            "failures" to "0",
                        ) + counts,
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:41", "retry_scheduled", "kind=continuation retry=1 delay=1s due=13:48:42 no_progress=1/3") in lines, out)
        val requeue = "kind=error retry=2 delay=20s due=13:49:02 no_progress=1/3 error=\"no available orchestrator slots\""
        assertTrue(lines.any { it.trim() == "13:48:42  ${"retry_scheduled".padEnd(NAME_WIDTH)}$requeue" }, out)
    }

    @Test
    fun `issue parked with max_continuations prints no progress 3 of 3`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_finished", "13:48:41", "a1", data = mapOf("outcome" to "succeeded")),
                event(
                    "issue_parked",
                    "13:48:41",
                    "a1",
                    data = mapOf("reason" to "max_continuations", "no_progress" to "3", "max_continuations" to "3"),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:41", "issue_parked", "reason=max_continuations no_progress=3/3") in lines, out)
        val parked = lines.single { "issue_parked" in it }
        assertFalse("failures=" in parked, parked)
        assertFalse("error=" in parked, parked)
    }

    @Test
    fun `attempt header shows turns and exit reason`() {
        val lines = trace(*turnSession(3).toTypedArray())

        val header = lines.single { it.startsWith("Attempt 1 ") }
        assertTrue(header.endsWith("succeeded in 94s, 3 turns, not_active"), header)

        val single = trace(*turnSession(1).toTypedArray())

        assertTrue(single.single { it.startsWith("Attempt 1 ") }.endsWith("1 turn, not_active"), single.joinToString("\n"))
    }

    /** An attempt whose usage grew past a cap of 2000000, as the orchestrator recorded it before the fold into budgets (m1-08). */
    private fun cappedAttempt(): List<RecordedEvent> {
        fun usage(
            time: String,
            tokens: String,
        ) = event(
            "usage",
            time,
            "a1",
            data = mapOf("input_tokens" to "1", "attempt_tokens" to tokens, "max_tokens" to "2000000", "total_cost_usd" to "0.25"),
        )
        return listOf(
            event("attempt_started", "13:48:40", "a1"),
            usage("13:48:50", "812004"),
            usage("13:49:10", "2104331"),
            event("token_cap_reached", "13:49:10", "a1", data = mapOf("attempt_tokens" to "2104331", "max_tokens" to "2000000")),
            event("phase", "13:49:11", "a1", phase = "Failed", data = mapOf("reason" to "token_cap")),
            event(
                "attempt_finished",
                "13:49:11",
                "a1",
                data = mapOf("outcome" to "failed", "reason" to "token_cap", "turns" to "3"),
            ),
            event(
                "issue_parked",
                "13:49:11",
                "a1",
                data = mapOf("reason" to "token_cap", "attempt_tokens" to "2104331", "max_tokens" to "2000000"),
            ),
        )
    }

    @Test
    fun `usage and the token cap show the spend against the cap`() {
        val lines = trace(*cappedAttempt().toTypedArray())

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:50", "usage", "tokens=812004/2000000") in lines, out)
        assertTrue(line("13:49:10", "usage", "tokens=2104331/2000000") in lines, out)
        assertTrue(line("13:49:10", "token_cap_reached", "tokens=2104331/2000000") in lines, out)
        assertTrue(line("13:49:11", "issue_parked", "reason=token_cap tokens=2104331/2000000") in lines, out)
    }

    /** An attempt past its per attempt dollar budget, as the orchestrator records it (m2-20). */
    private fun budgetAttempt(): List<RecordedEvent> {
        val level = mapOf("budget" to "per_attempt_usd", "limit" to "10")
        return listOf(
            event("attempt_started", "13:48:40", "a1"),
            event("usage", "13:48:50", "a1", data = mapOf("attempt_tokens" to "812004", "total_cost_usd" to "8.5")),
            event("budget_warning", "13:48:50", "a1", data = level + ("used" to "8.5")),
            event("usage", "13:49:10", "a1", data = mapOf("attempt_tokens" to "1104331", "total_cost_usd" to "10.25")),
            event("budget_reached", "13:49:10", "a1", data = level + ("used" to "10.25")),
            event("phase", "13:49:11", "a1", phase = "Failed", data = mapOf("reason" to "budget")),
            event("attempt_finished", "13:49:11", "a1", data = mapOf("outcome" to "failed", "reason" to "budget", "turns" to "2")),
            event("issue_parked", "13:49:11", "a1", data = level + mapOf("reason" to "budget", "used" to "10.25")),
        )
    }

    @Test
    fun `a budget warning, a budget passed and the budget park name the budget and its spend (m2-20)`() {
        val lines = trace(*budgetAttempt().toTypedArray())

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:50", "budget_warning", "budget=per_attempt_usd used=8.5/10") in lines, out)
        assertTrue(line("13:49:10", "budget_reached", "budget=per_attempt_usd used=10.25/10") in lines, out)
        assertTrue(line("13:49:11", "issue_parked", "reason=budget budget=per_attempt_usd used=10.25/10") in lines, out)
        val header = lines.single { it.startsWith("Attempt 1 ") }
        assertTrue(header.endsWith("failed in 31s, 2 turns, 1104331 tokens, budget"), header)
    }

    @Test
    fun `attempt header shows the spend after the turns`() {
        val capped = trace(*cappedAttempt().toTypedArray())

        val header = capped.single { it.startsWith("Attempt 1 ") }
        assertTrue(header.endsWith("failed in 31s, 3 turns, 2104331 tokens, token_cap"), header)
    }

    @Test
    fun `usage events from before the token cap are not printed and the header has no tokens`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("usage", "13:48:50", "a1", data = mapOf("input_tokens" to "120")),
                event("attempt_finished", "13:48:51", "a1", data = mapOf("outcome" to "succeeded", "turns" to "1")),
            )

        assertTrue(lines.none { "usage" in it }, lines.joinToString("\n"))
        assertTrue(lines.single { it.startsWith("Attempt 1 ") }.endsWith("succeeded in 11s, 1 turn"), lines.joinToString("\n"))
    }

    @Test
    fun `a usage line without max_tokens shows the tokens alone`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("usage", "13:48:50", "a1", data = mapOf("attempt_tokens" to "812004")),
            )

        assertTrue(line("13:48:50", "usage", "tokens=812004") in lines, lines.joinToString("\n"))
    }

    @Test
    fun `token thresholds show the step, the tokens, whether it was an estimate and the cost when known (m1-41)`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "token_threshold",
                    "13:48:50",
                    "a1",
                    data = mapOf("tokens" to "10059033", "threshold" to "10000000", "estimated" to "true"),
                ),
                event(
                    "token_threshold",
                    "13:49:10",
                    "a1",
                    data = mapOf("tokens" to "20412977", "threshold" to "20000000", "cost_usd" to "4.12"),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:50", "token_threshold", "threshold=10000000 tokens=10059033 estimated=true") in lines, out)
        assertTrue(line("13:49:10", "token_threshold", "threshold=20000000 tokens=20412977 estimated=false cost_usd=4.12") in lines, out)
    }

    @Test
    fun `m3-08 context usage shows the latest and peak occupancy, the window and the fraction, or the model alone`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "context_usage",
                    "13:48:50",
                    "a1",
                    data =
                        mapOf(
                            "latest_tokens" to "120000",
                            "peak_tokens" to "300000",
                            "model" to "anthropic/claude-opus-5-5",
                            "window" to "1000000",
                            "fraction" to "0.1200",
                        ),
                ),
                event(
                    "context_usage",
                    "13:49:10",
                    "a1",
                    data = mapOf("latest_tokens" to "43455", "peak_tokens" to "43455", "model" to "anthropic/claude-haiku-5-5"),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(
            line(
                "13:48:50",
                "context_usage",
                "latest_tokens=120000 peak_tokens=300000 window=1000000 fraction=0.1200 model=anthropic/claude-opus-5-5",
            ) in lines,
            out,
        )
        val noWindow = line("13:49:10", "context_usage", "latest_tokens=43455 peak_tokens=43455 model=anthropic/claude-haiku-5-5")
        assertTrue(noWindow in lines, out)
    }

    @Test
    fun `m3-21 a rate limit shows its limit, its windows and their reset, and whether it is reached`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "rate_limit",
                    "13:48:50",
                    "a1",
                    data =
                        mapOf(
                            "limit_id" to "codex",
                            "limit_name" to "Codex",
                            "primary_used_percent" to "42",
                            "primary_window_minutes" to "300",
                            "primary_resets_at" to "2026-11-17T12:00:00Z",
                            "secondary_used_percent" to "7",
                            "secondary_window_minutes" to "10080",
                        ),
                ),
                event(
                    "rate_limit",
                    "13:49:10",
                    "a1",
                    data = mapOf("limit_id" to "codex", "primary_used_percent" to "100", "reached" to "rate_limit_reached"),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(
            line(
                "13:48:50",
                "rate_limit",
                "limit=codex name=Codex primary=42%/300m primary_resets_at=2026-11-17T12:00:00Z secondary=7%/10080m",
            ) in lines,
            out,
        )
        assertTrue(line("13:49:10", "rate_limit", "limit=codex primary=100% reached=rate_limit_reached") in lines, out)
    }

    @Test
    fun `bl-209 a Claude Code rate limit shows in the same format as the Codex one`() {
        // The data the Claude Code runner's events record: the recorded seven day window, then a five hour one that is reached.
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "rate_limit",
                    "13:48:50",
                    "a1",
                    data =
                        mapOf(
                            "limit_id" to "claude",
                            "primary_used_percent" to "74",
                            "primary_window_minutes" to "10080",
                            "primary_resets_at" to "2026-11-17T09:00:00Z",
                        ),
                ),
                event(
                    "rate_limit",
                    "13:49:10",
                    "a1",
                    data =
                        mapOf(
                            "limit_id" to "claude",
                            "primary_used_percent" to "100",
                            "primary_window_minutes" to "300",
                            "primary_resets_at" to "2026-11-16T14:00:00Z",
                            "secondary_used_percent" to "74",
                            "secondary_window_minutes" to "10080",
                            "secondary_resets_at" to "2026-11-17T09:00:00Z",
                            "reached" to "rejected",
                        ),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:50", "rate_limit", "limit=claude primary=74%/10080m primary_resets_at=2026-11-17T09:00:00Z") in lines, out)
        assertTrue(
            line(
                "13:49:10",
                "rate_limit",
                "limit=claude primary=100%/300m primary_resets_at=2026-11-16T14:00:00Z secondary=74%/10080m " +
                    "secondary_resets_at=2026-11-17T09:00:00Z reached=rejected",
            ) in lines,
            out,
        )
    }

    @Test
    fun `m3-11 session_rotated shows its reason, the rotation of the cap, the occupancy and both threads`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "session_rotated",
                    "13:49:00",
                    "a1",
                    data =
                        mapOf(
                            "reason" to "soft_limit",
                            "occupancy_tokens" to "250000",
                            "window" to "1000000",
                            "fraction" to "0.2500",
                            "rotation" to "1",
                            "max_rotations" to "5",
                            "old_thread_id" to "1b2c3d4e-0000-old",
                            "old_runtime_session_id" to "runtime-1",
                            "new_thread_id" to "5f6a7b8c-0000-new",
                            "new_runtime_session_id" to "runtime-2",
                        ),
                ),
                event(
                    "issue_parked",
                    "13:50:00",
                    "a1",
                    data = mapOf("reason" to "max_rotations", "rotations" to "5", "max_rotations" to "5", "fraction" to "0.3000"),
                ),
            )

        val out = lines.joinToString("\n")
        val rotated =
            line(
                "13:49:00",
                "session_rotated",
                "reason=soft_limit rotation=1/5 occupancy_tokens=250000 window=1000000 fraction=0.2500 from=1b2c3d4e to=5f6a7b8c",
            )
        assertTrue(rotated in lines, out)
        assertTrue(line("13:50:00", "issue_parked", "reason=max_rotations rotations=5/5 fraction=0.3000") in lines, out)
    }

    @Test
    fun `m3-14 session_rotated shows the budget and the cuts of a rotated prompt cut to fit`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "session_rotated",
                    "13:49:00",
                    "a1",
                    data =
                        mapOf(
                            "reason" to "soft_limit",
                            "occupancy_tokens" to "450",
                            "window" to "2000",
                            "fraction" to "0.2250",
                            "rotation" to "1",
                            "max_rotations" to "5",
                            "prompt_tokens" to "98",
                            "prompt_limit_tokens" to "100",
                            "over_limit" to "false",
                            "token_counter" to "estimate",
                            "cut_template_tokens" to "0",
                            "cut_issue_tokens" to "0",
                            "cut_verify_findings_tokens" to "0",
                            "cut_session_brief_tokens" to "60",
                            "cut_lessons_tokens" to "0",
                            "cut_context_sources_tokens" to "0",
                        ),
                ),
            )

        val out = lines.joinToString("\n")
        val rotated =
            line(
                "13:49:00",
                "session_rotated",
                "reason=soft_limit rotation=1/5 occupancy_tokens=450 window=2000 fraction=0.2250 prompt_tokens=98 " +
                    "prompt_limit_tokens=100 over_limit=false cut_template_tokens=0 cut_issue_tokens=0 cut_verify_findings_tokens=0 " +
                    "cut_session_brief_tokens=60 cut_lessons_tokens=0 cut_context_sources_tokens=0",
            )
        assertTrue(rotated in lines, out)
    }

    @Test
    fun `m3-12 a turn interrupted at the hard limit, its rotation and the split_issue park show in the timeline`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "turn_completed",
                    "13:48:59",
                    "a1",
                    data = mapOf("turn" to "1", "max_turns" to "20", "turn_id" to "turn-1", "interrupted" to "hard_limit"),
                ),
                event(
                    "session_rotated",
                    "13:49:00",
                    "a1",
                    data =
                        mapOf(
                            "reason" to "hard_limit",
                            "occupancy_tokens" to "260000",
                            "window" to "1000000",
                            "fraction" to "0.2600",
                            "rotation" to "1",
                            "max_rotations" to "5",
                        ),
                ),
                event(
                    "issue_parked",
                    "13:50:00",
                    "a1",
                    data =
                        mapOf(
                            "reason" to "split_issue",
                            "without_progress" to "2",
                            "rotations" to "1",
                            "limit" to "hard_limit",
                            "fraction" to "0.2700",
                        ),
                ),
            )

        val out = lines.joinToString("\n")
        assertTrue(lines.any { "turn_completed" in it && it.endsWith("interrupted=hard_limit") }, out)
        val rotated =
            line("13:49:00", "session_rotated", "reason=hard_limit rotation=1/5 occupancy_tokens=260000 window=1000000 fraction=0.2600")
        assertTrue(rotated in lines, out)
        val park = line("13:50:00", "issue_parked", "reason=split_issue without_progress=2 rotations=1 limit=hard_limit fraction=0.2700")
        assertTrue(park in lines, out)
    }

    @Test
    fun `bl-136 interrupt_grace_expired shows the grace, the occupancy and the usage known when the flow was cancelled`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "interrupt_grace_expired",
                    "13:49:10",
                    "a1",
                    data =
                        mapOf(
                            "grace_ms" to "10000",
                            "occupancy_tokens" to "300000",
                            "window" to "1000000",
                            "fraction" to "0.3000",
                            "attempt_tokens" to "9000",
                            "total_cost_usd" to "0.09",
                        ),
                ),
                event("interrupt_grace_expired", "13:50:10", "a1", data = mapOf("grace_ms" to "10000", "occupancy_tokens" to "260000")),
            )

        val out = lines.joinToString("\n")
        // The name is longer than the name column, so one space follows it.
        val expired =
            "  13:49:10  interrupt_grace_expired " +
                "grace_ms=10000 occupancy_tokens=300000 window=1000000 fraction=0.3000 attempt_tokens=9000 total_cost_usd=0.09"
        assertTrue(expired in lines, out)
        // A model without a known window and a session without a usage report leave those fields out.
        assertTrue("  13:50:10  interrupt_grace_expired grace_ms=10000 occupancy_tokens=260000" in lines, out)
    }

    @Test
    fun `m4-04 stop_grace_expired shows the grace, the wake and the usage known after the stop`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event(
                    "stop_grace_expired",
                    "13:49:10",
                    "a1",
                    data =
                        mapOf(
                            "grace_ms" to "5000",
                            "wake" to "true",
                            "wake_messages" to "2",
                            "attempt_tokens" to "9000",
                            "total_cost_usd" to "0.09",
                        ),
                ),
                event(
                    "stop_grace_expired",
                    "13:50:10",
                    "a1",
                    data = mapOf("grace_ms" to "5000", "wake" to "false", "wake_messages" to "0"),
                ),
            )

        val out = lines.joinToString("\n")
        val details = "grace_ms=5000 wake=true wake_messages=2 attempt_tokens=9000 total_cost_usd=0.09"
        assertTrue(line("13:49:10", "stop_grace_expired", details) in lines, out)
        // An attempt without a usage report leaves the usage out.
        assertTrue(line("13:50:10", "stop_grace_expired", "grace_ms=5000 wake=false wake_messages=0") in lines, out)
    }

    @Test
    fun `m4-02 context_window_unknown shows the model and the runner in each attempt`() {
        val unknown = mapOf("model" to "openai/gpt-unknown-9", "runner" to "codex-app-server")
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("context_window_unknown", "13:48:50", "a1", data = unknown),
                event("attempt_started", "13:49:40", "a2"),
                event("context_window_unknown", "13:49:50", "a2", data = unknown),
                event("context_window_unknown", "13:49:55", "a2", data = mapOf("runner" to "command")),
            )

        val out = lines.joinToString("\n")
        val details = "model=openai/gpt-unknown-9 runner=codex-app-server"
        assertTrue(line("13:48:50", "context_window_unknown", details) in lines, out)
        assertTrue(line("13:49:50", "context_window_unknown", details) in lines, out)
        // A runner that named no model leaves the model out.
        assertTrue(line("13:49:55", "context_window_unknown", "runner=command") in lines, out)
    }

    @Test
    fun `a running attempt header shows the tokens so far`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("usage", "13:48:50", "a1", data = mapOf("attempt_tokens" to "812004", "max_tokens" to "2000000")),
            )

        assertTrue(lines.single { it.startsWith("Attempt 1 ") }.endsWith("running, 812004 tokens so far"), lines.joinToString("\n"))
    }

    // Dispatch rules and stop (m1-09).

    @Test
    fun `attempt header of an attempt canceled by loop stop says stop requested`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("phase", "13:48:52", "a1", phase = "Failed", data = mapOf("reason" to "stop_requested")),
                event("attempt_finished", "13:48:52", "a1", data = mapOf("outcome" to "canceled", "reason" to "stop_requested")),
            )

        val header = lines.single { it.startsWith("Attempt 1 ") }
        assertTrue(header.endsWith("canceled in 12s, stop requested"), header)
    }

    @Test
    fun `attempt header of an attempt canceled by a signal or an internal error says so in words`() {
        fun header(reason: String): String {
            val lines =
                trace(
                    event("attempt_started", "13:48:40", "a1"),
                    event("attempt_finished", "13:48:52", "a1", data = mapOf("outcome" to "canceled", "reason" to reason)),
                )
            return lines.single { it.startsWith("Attempt 1 ") }
        }

        val signal = header("signal")
        assertTrue(signal.endsWith("canceled in 12s, signal received"), signal)
        root.resolve("loop-home").toFile().deleteRecursively()
        val crash = header("internal_error")
        assertTrue(crash.endsWith("canceled in 12s, internal error"), crash)
    }

    @Test
    fun `attempt header of a stalled attempt says stalled`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("attempt_stalled", "13:53:52", "a1", data = mapOf("silent_ms" to "300001", "stall_timeout_ms" to "300000")),
                event("phase", "13:53:52", "a1", phase = "Stalled", data = mapOf("reason" to "stalled")),
                event("attempt_finished", "13:53:52", "a1", data = mapOf("outcome" to "failed", "reason" to "stalled", "turns" to "1")),
            )

        val header = lines.single { it.startsWith("Attempt 1 ") }
        assertTrue(header.endsWith("failed in 312s, 1 turn, stalled"), header)
    }

    @Test
    fun `a candidate skipped for a blocker shows its reason`() {
        val lines =
            trace(event("candidate_skipped", "13:48:40", data = mapOf("reason" to "blocked_by t-0 (todo)")))

        val expected = "13:48:40  ${"candidate_skipped".padEnd(NAME_WIDTH)}reason=\"blocked_by t-0 (todo)\""
        assertTrue(lines.any { it.trim() == expected }, lines.joinToString("\n"))
    }

    // Host side tool requests (m1-23).

    @Test
    fun `tool requests show who applied them, why one failed and what was dropped`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("issue_move_requested", "13:48:41", "a1", data = mapOf("to" to "review")),
                event("issue_comment_requested", "13:48:41", "a1", data = mapOf("chars" to "12")),
                event("issue_moved", "13:48:42", "a1", data = mapOf("from" to "todo", "to" to "review", "by" to "host")),
                event(
                    "tool_request_failed",
                    "13:48:42",
                    "a1",
                    data = mapOf("request" to "comment", "chars" to "12", "reason" to "The comment is empty"),
                ),
                event("tool_request_dropped", "13:48:43", "a1", data = mapOf("request" to "move", "to" to "done")),
            )

        val out = lines.joinToString("\n")
        assertTrue(line("13:48:41", "issue_move_requested", "to=review") in lines, out)
        // The name fills the column, so one space separates it from the details.
        assertTrue("  13:48:41  issue_comment_requested chars=12" in lines, out)
        assertTrue(line("13:48:42", "issue_moved", "to=review by host") in lines, out)
        assertTrue(line("13:48:42", "tool_request_failed", "request=comment chars=12 reason=\"The comment is empty\"") in lines, out)
        assertTrue(line("13:48:43", "tool_request_dropped", "request=move to=done") in lines, out)
    }

    @Test
    fun `a long event name is always followed by a space before its details`() {
        val lines =
            trace(
                event("attempt_started", "13:48:40", "a1"),
                event("issue_comment_requested", "13:48:41", "a1", data = mapOf("chars" to "32")),
                event("tool_request_dropped", "13:48:43", "a1", data = mapOf("request" to "comment", "chars" to "32")),
            )

        val out = lines.joinToString("\n")
        assertTrue(lines.any { Regex("""\bissue_comment_requested +chars=32$""").containsMatchIn(it) }, out)
        assertTrue(lines.any { Regex("""\btool_request_dropped +request=comment chars=32$""").containsMatchIn(it) }, out)
    }

    private fun phaseLine(
        lines: List<String>,
        phase: String,
    ): String = lines.single { line -> line.split(Regex("\\s+")).getOrNull(2) == phase }

    private fun distinct(column: String): List<String> =
        fixture
            .query("SELECT DISTINCT $column FROM events WHERE issue_id = 'default:t-1' AND $column IS NOT NULL")
            .map { it[column]!! }

    private companion object {
        const val NAME_WIDTH = 23

        val EXPECTED_PHASES =
            listOf(
                "PreparingWorkspace",
                "BuildingPrompt",
                "LaunchingAgentProcess",
                "InitializingSession",
                "StreamingTurn",
                "Finishing",
                "Succeeded",
            )
        val PHASE_NAMES =
            EXPECTED_PHASES.toSet() + setOf("Failed", "TimedOut", "Stalled", "CanceledByReconciliation")
    }
}
