package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import loop.api.AttemptId
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SessionId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.resources.ResourceLocks
import loop.store.MachineSlot
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** `loop status` (m1-14): the runtime snapshot of a project, read from the store, end to end through the installed launcher. */
class StatusCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val project by lazy { ProjectId(fixture.projectId()) }

    private fun event(
        kind: String,
        issue: String,
        attempt: String?,
        ts: String,
        run: String = LoopFixture.RUN_ID,
        data: Map<String, String> = emptyMap(),
        session: String? = null,
    ) = RecordedEvent(
        ts = Instant.parse(ts),
        projectId = project,
        kind = kind,
        runId = RunId(run),
        issueId = issue,
        issueIdentifier = issue,
        attemptId = attempt?.let(::AttemptId),
        sessionId = session?.let(::SessionId),
        data = data,
    )

    /** The `turn_started` of turn [turn] on [thread], with the session id `<thread>-tu<turn>` (m3-09). */
    private fun turnStarted(
        issue: String,
        attempt: String,
        ts: String,
        thread: String,
        turn: Int,
    ) = event(
        "turn_started",
        issue,
        attempt,
        ts,
        data = mapOf("turn" to "$turn", "thread_id" to thread, "turn_id" to "tu$turn"),
        session = "$thread-tu$turn",
    )

    /** The `context_usage` recorded at the end of turn [turn] on [thread] (m3-08), the fraction when [window] is set. */
    private fun contextUsage(
        issue: String,
        attempt: String,
        ts: String,
        thread: String,
        turn: Int,
        latest: Long,
        peak: Long,
        window: Long? = 1_000_000,
        model: String = "anthropic/claude-opus-5-5",
    ) = event(
        "context_usage",
        issue,
        attempt,
        ts,
        data =
            buildMap {
                put("latest_tokens", "$latest")
                put("peak_tokens", "$peak")
                put("model", model)
                window?.let {
                    put("window", "$it")
                    put("fraction", String.format(java.util.Locale.ROOT, "%.4f", latest.toDouble() / it))
                }
            },
        session = "$thread-tu$turn",
    )

    private fun park(
        issue: String,
        parkedAt: String,
    ) = SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
        runBlocking {
            store
                .park(
                    ParkedIssue(
                        projectId = project,
                        issueId = issue,
                        identifier = issue,
                        reason = ParkReason.MaxAttempts,
                        state = "todo",
                        labels = setOf("ready-for-agent"),
                        parkedAt = Instant.parse(parkedAt),
                        runId = RunId(OLD_RUN),
                    ),
                ).getOrThrow()
        }
    }

    private fun wait(
        issue: String,
        attempt: String,
        since: String,
        reason: WaitReason = WaitReason.Verify,
    ) = SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
        runBlocking {
            store
                .startWaiting(
                    WaitingIssue(
                        projectId = project,
                        issueId = issue,
                        identifier = issue,
                        reason = reason,
                        attemptId = AttemptId(attempt),
                        runId = RunId(LoopFixture.RUN_ID),
                        since = Instant.parse(since),
                        data = mapOf("to" to "review"),
                    ),
                ).getOrThrow()
        }
    }

    /**
     * t-1 runs its second attempt in the live run, t-2 waits for its first retry after a failed attempt,
     * t-3 was parked by an earlier run. t-4 retried and its retry was dropped, t-5 was left open by a dead run.
     * t-6 waits for the checks of the result its live attempt submitted (m2-04).
     */
    private fun seedThreeIssues() {
        fixture.seed(
            event("attempt_started", "t-1", A1, "2026-11-11T09:00:00Z", run = OLD_RUN),
            event("attempt_finished", "t-1", A1, "2026-11-11T09:01:00Z", run = OLD_RUN),
            event("attempt_started", "t-3", C1, "2026-11-11T09:10:00Z", run = OLD_RUN),
            event("attempt_finished", "t-3", C1, "2026-11-11T09:20:00Z", run = OLD_RUN),
            event("issue_parked", "t-3", C1, "2026-11-11T09:30:00Z", run = OLD_RUN, data = mapOf("reason" to "max_attempts")),
            event("attempt_started", "t-5", E1, "2026-11-11T09:40:00Z", run = OLD_RUN),
            event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z"),
            event("turn_started", "t-1", A2, "2026-11-11T10:02:04Z"),
            event("usage", "t-1", A2, "2026-11-11T10:03:00Z", data = mapOf("attempt_tokens" to "1200")),
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z"),
            event("attempt_finished", "t-2", B1, "2026-11-11T10:04:50Z"),
            event(
                "retry_scheduled",
                "t-2",
                B1,
                "2026-11-11T10:04:50Z",
                data =
                    mapOf(
                        "kind" to "error",
                        "attempt" to "1",
                        "delay_ms" to "10000",
                        "due_at" to "2026-11-11T10:05:00Z",
                        "error" to "agent exited with code 1",
                    ),
            ),
            event("retry_scheduled", "t-4", null, "2026-11-11T10:04:00Z", data = mapOf("kind" to "continuation", "attempt" to "1")),
            event("retry_dropped", "t-4", null, "2026-11-11T10:04:01Z", data = mapOf("reason" to "terminal")),
            event("attempt_started", "t-6", F1, "2026-11-11T10:05:00Z"),
            event("result_submitted", "t-6", F1, "2026-11-11T10:06:00Z", data = mapOf("to" to "review")),
        )
        park("t-3", "2026-11-11T09:30:00Z")
        wait("t-6", F1, "2026-11-11T10:06:00Z")
    }

    @Test
    fun `status shows a running, a retrying, a waiting and a parked issue with their state and attempt`() {
        seedThreeIssues()
        val lock = holdRunFile()
        try {
            val result = fixture.loop("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            val pid = ProcessHandle.current().pid()
            assertEquals(
                listOf(
                    "Project ${fixture.projectId()}   ${fixture.workflow.toRealPath()}",
                    "Run ${LoopFixture.RUN_ID.take(8)}  pid $pid",
                    "",
                    "running   t-1  attempt 2  a2000000  started=10:02:03 turns=1 tokens=1200",
                    "retrying  t-2  attempt 1  b1000000  kind=error retry=1 due=10:05:00 error=\"agent exited with code 1\"",
                    "waiting   t-6  attempt 1  f1000000  reason=verify since=2026-11-11T10:06:00Z",
                    "parked    t-3  attempt 1  c1000000  reason=max_attempts state=todo since=2026-11-11T09:30:00Z",
                ),
                result.stdout.trimEnd().lines(),
            )
            assertEquals("", result.stderr, result.toString())
        } finally {
            lock.release()
        }
    }

    @Test
    fun `status with full ids prints the attempt and run ids in full`() {
        seedThreeIssues()
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", "--full-ids", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertContains(result.stdout, "Run ${LoopFixture.RUN_ID}  pid")
            assertContains(result.stdout, "attempt 2  $A2  started=")
        } finally {
            lock.release()
        }
    }

    @Test
    fun `without a live run nothing is running or retrying and waiting and parked issues still show`() {
        seedThreeIssues()
        // A run file whose process is gone, as a crashed run leaves it: not a live run, and left in place.
        val gone = ProcessBuilder("true").start()
        gone.waitFor()
        val runFiles = RunFiles(fixture.loopHome, project)
        runFiles.dir.toFile().mkdirs()
        runFiles.runFile.writeText(
            """{"pid":${gone.pid()},"started_at":"2026-11-10T20:00:00Z","run_id":"${LoopFixture.RUN_ID}","workflow":"${fixture.workflow}"}""",
        )

        val result = fixture.inProcess("status", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Project ${fixture.projectId()}   ${fixture.workflow.toRealPath()}",
                "No run in progress.",
                "",
                "waiting   t-6  attempt 1  f1000000  reason=verify since=2026-11-11T10:06:00Z",
                "parked    t-3  attempt 1  c1000000  reason=max_attempts state=todo since=2026-11-11T09:30:00Z",
            ),
            result.stdout.trimEnd().lines(),
        )
        assertTrue(runFiles.runFile.toFile().exists(), "loop status removed the run file")
    }

    @Test
    fun `m4-10 a draining run says so and prints what still runs, without a retry of another issue or an approval to give`() {
        seedThreeIssues()
        // t-7 waits for a human's approval, which does not hold the drain.
        fixture.seed(event("attempt_started", "t-7", G1, "2026-11-11T10:05:30Z"))
        wait("t-7", G1, "2026-11-11T10:06:30Z", reason = WaitReason.Approval)
        fixture.seed(
            RecordedEvent(
                ts = Instant.parse("2026-11-11T10:07:00Z"),
                projectId = project,
                kind = "run_draining",
                runId = RunId(LoopFixture.RUN_ID),
                data = mapOf("attempts" to "t-1", "waits" to "t-6"),
            ),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            val lines = result.stdout.trimEnd().lines()
            assertEquals("Run ${LoopFixture.RUN_ID.take(8)}  pid ${ProcessHandle.current().pid()}  draining", lines[1], result.toString())
            assertEquals(
                listOf(
                    "draining  run  since=2026-11-11T10:07:00Z still=t-1,t-6  stops once these end, loop stop stops it at once",
                    "running   t-1  attempt 2  a2000000  started=10:02:03 turns=1 tokens=1200",
                    "retrying  t-2  attempt 1  b1000000  kind=error retry=1 due=10:05:00 error=\"agent exited with code 1\"",
                ),
                lines.drop(3).take(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `m4-10 a drain loop drain asked for and the run has not noticed yet begins at the next poll`() {
        fixture.seed(event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z"))
        val lock = holdRunFile()
        try {
            RunFiles(fixture.loopHome, project).requestDrain(LoopFixture.RUN_ID)

            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            val lines = result.stdout.trimEnd().lines()
            assertContains(lines[1], "  draining")
            assertEquals(
                listOf(
                    "draining  run  still=t-1  begins at the next poll, loop stop stops it at once",
                    "running   t-1  attempt 1  a2000000  started=10:02:03 turns=0",
                ),
                lines.drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `bl-175 a delivery queue the live run paused prints one paused line, above the issues`() {
        fixture.seed(
            event(
                "delivery_queue_paused",
                "t-2",
                B1,
                "2026-11-11T10:04:50Z",
                data = mapOf("issue" to "t-2", "reason" to "ci_timeout"),
            ),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "paused    delivery queue  issue=t-2 reason=ci_timeout since=2026-11-11T10:04:50Z  resume with loop approve or a restart",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `bl-39 a runner hold of the live run prints one held line with its reason and next probe, above the issues`() {
        val reason = "turn_failed: Failed to authenticate. API Error: 403"
        fixture.seed(
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z"),
            event("attempt_finished", "t-2", B1, "2026-11-11T10:04:50Z"),
            event(
                "retry_scheduled",
                "t-2",
                B1,
                "2026-11-11T10:04:50Z",
                data = mapOf("kind" to "error", "attempt" to "1", "due_at" to "2026-11-11T10:05:50Z", "error" to reason),
            ),
            event(
                "runner_hold_started",
                "t-2",
                B1,
                "2026-11-11T10:04:50Z",
                data = mapOf("reason" to reason, "hold" to "1", "delay_ms" to "60000", "probe_at" to "2026-11-11T10:05:50Z"),
            ),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "held      runner unavailable  hold=1 next_probe=10:05:50 reason=\"$reason\"",
                    "retrying  t-2  attempt 1  b1000000  kind=error retry=1 due=10:05:50 error=\"$reason\"",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `bl-172 status shows each merge wait's place in the delivery queue, the oldest first, ties by issue id`() {
        fixture.seed(
            event("attempt_started", "t-1", A1, "2026-11-11T09:00:00Z", run = OLD_RUN),
            event("attempt_started", "t-2", B1, "2026-11-11T09:01:00Z", run = OLD_RUN),
            event("attempt_started", "t-3", C1, "2026-11-11T09:02:00Z", run = OLD_RUN),
            event("attempt_started", "t-6", F1, "2026-11-11T09:03:00Z", run = OLD_RUN),
        )
        wait("t-3", C1, "2026-11-11T10:00:00Z", WaitReason.Merge)
        wait("t-2", B1, "2026-11-11T10:05:00Z", WaitReason.Merge)
        wait("t-1", A1, "2026-11-11T10:05:00Z", WaitReason.Merge)
        wait("t-6", F1, "2026-11-11T10:01:00Z", WaitReason.Approval)

        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "waiting   t-3  attempt 1  c1000000  reason=merge since=2026-11-11T10:00:00Z queue=1",
                "waiting   t-6  attempt 1  f1000000  reason=approval since=2026-11-11T10:01:00Z",
                "waiting   t-1  attempt 1  a1000000  reason=merge since=2026-11-11T10:05:00Z queue=2",
                "waiting   t-2  attempt 1  b1000000  reason=merge since=2026-11-11T10:05:00Z queue=3",
            ),
            result.stdout
                .trimEnd()
                .lines()
                .drop(3),
        )
    }

    @Test
    fun `status of an empty project says nothing is running, retrying, waiting or parked`() {
        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Project ${fixture.projectId()}   ${fixture.workflow.toRealPath()}",
                "No run in progress.",
                "",
                "Nothing running, retrying, waiting or parked.",
            ),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `a run file that cannot be read exits 3 and is left in place`() {
        val runFiles = RunFiles(fixture.loopHome, project)
        runFiles.dir.toFile().mkdirs()
        runFiles.runFile.writeText("not json")

        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(3, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith("loop status: cannot read the run file at ${runFiles.runFile}: "), result.toString())
        assertTrue(runFiles.runFile.toFile().exists(), "loop status removed the run file")
    }

    @Test
    fun `a workflow that does not load exits 1`() {
        val result = fixture.inProcess("status", root.resolve("missing/WORKFLOW.md").toString())

        assertEquals(1, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith("loop status: "), result.toString())
    }

    @Test
    fun `status works while loop run works in another process`() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |case "${'$'}LOOP_ISSUE_IDENTIFIER" in
            |  s-run) sleep 60 ;;
            |  *) exit 1 ;;
            |esac
            |
            """.trimMargin(),
        )
        fixture.issue("s-run")
        fixture.issue("s-retry")
        fixture.issue("s-park")
        // Parked by an earlier run with the tracker state and labels it still has, so the run keeps it parked.
        fixture.seed(
            event("attempt_started", "s-park", C1, "2026-11-11T09:10:00Z", run = OLD_RUN),
            event("attempt_finished", "s-park", C1, "2026-11-11T09:20:00Z", run = OLD_RUN),
            event("issue_parked", "s-park", C1, "2026-11-11T09:30:00Z", run = OLD_RUN, data = mapOf("reason" to "max_attempts")),
        )
        park("s-park", "2026-11-11T09:30:00Z")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            val wanted =
                listOf(
                    Regex("""^running +s-run +attempt 1 +\S{8}  started=\d\d:\d\d:\d\d""", RegexOption.MULTILINE),
                    Regex("""^retrying +s-retry +attempt \d+ +\S{8}  kind=error retry=\d+ due=""", RegexOption.MULTILINE),
                    Regex("""^parked +s-park +attempt 1 +c1000000  reason=max_attempts state=todo""", RegexOption.MULTILINE),
                )
            val deadline = System.nanoTime() + WAIT_NANOS
            var last: LoopResult? = null
            while (System.nanoTime() < deadline) {
                val status = fixture.inProcess("status", fixture.workflow.toString())
                last = status
                assertEquals(0, status.exitCode, status.toString())
                if (wanted.all { it.containsMatchIn(status.stdout) }) {
                    assertContains(status.stdout, "pid ${running.process.pid()}")
                    // Kept in the test report as the evidence of a status read from another process.
                    println(status.stdout)
                    return
                }
                Thread.sleep(POLL_MS)
            }
            fail("loop status never showed all three issues\nlast status:\n$last\nrun so far:\n${running.stdoutSoFar()}")
        } finally {
            running.process.descendants().forEach { it.destroyForcibly() }
            running.process.destroyForcibly()
            running.process.waitFor()
        }
    }

    @Test
    fun `m3-09 status prints the running session's latest and peak occupancy with their fraction of the window`() {
        fixture.seed(
            event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-1", A2, "2026-11-11T10:02:04Z", "th1", 1),
            contextUsage("t-1", A2, "2026-11-11T10:03:00Z", "th1", 1, latest = 300_000, peak = 300_000),
            // A second session, as a rotation starts one: its own count, not the first session's peak.
            turnStarted("t-1", A2, "2026-11-11T10:03:01Z", "th2", 2),
            contextUsage("t-1", A2, "2026-11-11T10:04:00Z", "th2", 2, latest = 40_000, peak = 120_000),
            // t-2 runs a new session with no report yet: the earlier session's numbers are not the running one's.
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-2", B1, "2026-11-11T10:03:01Z", "th8", 1),
            contextUsage("t-2", B1, "2026-11-11T10:03:30Z", "th8", 1, latest = 90_000, peak = 90_000),
            turnStarted("t-2", B1, "2026-11-11T10:03:31Z", "th9", 2),
            // t-3's model has no window: tokens only.
            event("attempt_started", "t-3", C1, "2026-11-11T10:05:00Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-3", C1, "2026-11-11T10:05:01Z", "th5", 1),
            contextUsage("t-3", C1, "2026-11-11T10:05:30Z", "th5", 1, latest = 7_000, peak = 8_000, window = null, model = UNKNOWN),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "running   t-1  attempt 1  a2000000  started=10:02:03 turns=2 " +
                        "latest_tokens=40000 peak_tokens=120000 window=1000000 fraction=0.0400 peak_fraction=0.1200",
                    "running   t-2  attempt 1  b1000000  started=10:03:00 turns=2",
                    "running   t-3  attempt 1  c1000000  started=10:05:00 turns=1 latest_tokens=7000 peak_tokens=8000 window=none",
                    "          ${noWindow(UNKNOWN)}",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `m4-02 status shows a running session whose model has no window as such, before and after its first turn ends`() {
        fixture.seed(
            // t-1 is in its first turn: context_window_unknown came at the first report, no context_usage yet.
            event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-1", A2, "2026-11-11T10:02:04Z", "th1", 1),
            event(
                "context_window_unknown",
                "t-1",
                A2,
                "2026-11-11T10:02:10Z",
                data = mapOf("model" to UNKNOWN, "runner" to "codex-app-server"),
                session = "th1-tu1",
            ),
            // t-2's runner named no model; its turn ended, so its occupancy is known in tokens.
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-2", B1, "2026-11-11T10:03:01Z", "th8", 1),
            event("context_window_unknown", "t-2", B1, "2026-11-11T10:03:10Z", data = mapOf("runner" to "command"), session = "th8-tu1"),
            event(
                "context_usage",
                "t-2",
                B1,
                "2026-11-11T10:03:30Z",
                data = mapOf("latest_tokens" to "90000", "peak_tokens" to "90000"),
                session = "th8-tu1",
            ),
            // t-3 was rerouted onto a model with a window: its latest report has a fraction, so it has a window.
            event("attempt_started", "t-3", C1, "2026-11-11T10:05:00Z", data = mapOf("occupancy" to "true")),
            turnStarted("t-3", C1, "2026-11-11T10:05:01Z", "th5", 1),
            event(
                "context_window_unknown",
                "t-3",
                C1,
                "2026-11-11T10:05:10Z",
                data = mapOf("model" to UNKNOWN, "runner" to "codex-app-server"),
                session = "th5-tu1",
            ),
            contextUsage("t-3", C1, "2026-11-11T10:05:30Z", "th5", 1, latest = 7_000, peak = 8_000),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "running   t-1  attempt 1  a2000000  started=10:02:03 turns=1 window=none",
                    "          ${noWindow(UNKNOWN)}",
                    "running   t-2  attempt 1  b1000000  started=10:03:00 turns=1 latest_tokens=90000 peak_tokens=90000 window=none",
                    "          ${noWindow(null)}",
                    "running   t-3  attempt 1  c1000000  started=10:05:00 turns=1 " +
                        "latest_tokens=7000 peak_tokens=8000 window=1000000 fraction=0.0070 peak_fraction=0.0080",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `m3-09 status says for each running attempt of a runner without occupancy that only the prompt limit and the turn cap hold`() {
        fixture.seed(
            event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z", data = mapOf("occupancy" to "false")),
            turnStarted("t-1", A2, "2026-11-11T10:02:04Z", "th1", 1),
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z", data = mapOf("occupancy" to "false")),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "running   t-1  attempt 1  a2000000  started=10:02:03 turns=1",
                    "          $NO_OCCUPANCY",
                    "running   t-2  attempt 1  b1000000  started=10:03:00 turns=0",
                    "          $NO_OCCUPANCY",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `m3-09 an older store, whose attempt_started has no occupancy, still reads, with no guarantee line`() {
        // As m3-08 recorded them: no occupancy on attempt_started, context_usage with its data.
        fixture.seed(
            event("attempt_started", "t-1", A2, "2026-11-11T10:02:03Z", data = mapOf("loop_version" to "abc")),
            turnStarted("t-1", A2, "2026-11-11T10:02:04Z", "th1", 1),
            contextUsage("t-1", A2, "2026-11-11T10:03:00Z", "th1", 1, latest = 120_000, peak = 300_000),
            event("attempt_started", "t-2", B1, "2026-11-11T10:03:00Z"),
        )
        val lock = holdRunFile()
        try {
            val result = fixture.inProcess("status", fixture.workflow.toString())

            assertEquals(0, result.exitCode, result.toString())
            assertEquals(
                listOf(
                    "running   t-1  attempt 1  a2000000  started=10:02:03 turns=1 " +
                        "latest_tokens=120000 peak_tokens=300000 window=1000000 fraction=0.1200 peak_fraction=0.3000",
                    "running   t-2  attempt 1  b1000000  started=10:03:00 turns=0",
                ),
                result.stdout
                    .trimEnd()
                    .lines()
                    .drop(3),
            )
        } finally {
            lock.release()
        }
    }

    @Test
    fun `m3-09 with the command runner, status read from another process says the attempt has only the prompt limit and the turn cap`() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |sleep 60
            |
            """.trimMargin(),
        )
        fixture.issue("s-run")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            val note = Regex.escape(NO_OCCUPANCY)
            val wanted = Regex("""^running +s-run +attempt 1 +\S{8}  started=.*\n {10}$note$""", RegexOption.MULTILINE)
            val deadline = System.nanoTime() + WAIT_NANOS
            var last: LoopResult? = null
            while (System.nanoTime() < deadline) {
                val status = fixture.inProcess("status", fixture.workflow.toString())
                last = status
                assertEquals(0, status.exitCode, status.toString())
                if (wanted.containsMatchIn(status.stdout)) {
                    println(status.stdout)
                    return
                }
                Thread.sleep(POLL_MS)
            }
            fail("loop status never showed the weaker guarantee line\nlast status:\n$last\nrun so far:\n${running.stdoutSoFar()}")
        } finally {
            running.process.descendants().forEach { it.destroyForcibly() }
            running.process.destroyForcibly()
            running.process.waitFor()
        }
    }

    /**
     * m3-16: another project of the same machine holds one slot from a live run and spent 1200 tokens and 0.5 usd today,
     * and a crashed run left a slot that does not count.
     */
    private fun seedMachine() {
        val other = ProjectId("bbbbbbbbbbbbbbbb")
        val otherRun = RunFiles(fixture.loopHome, other)
        otherRun.dir.toFile().mkdirs()
        otherRun.runFile.writeText(Json.encodeToString(RunRecord.serializer(), RunRecord.ofCurrentProcess("r-other", fixture.workflow)))
        val gone = ProcessBuilder("true").start()
        gone.waitFor()
        val now = Clock.System.now()
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking {
                val self = ProcessHandle.current().pid()
                store.claimMachineSlot(MachineSlot(other, "h1", RunId("r-other"), self, now), cap = 3) { true }.getOrThrow()
                store
                    .claimMachineSlot(
                        MachineSlot(ProjectId("cccccccccccccccc"), "h2", RunId("crashed"), gone.pid(), now),
                        cap = 3,
                    ) { true }
                    .getOrThrow()
                store
                    .recordAttempt(
                        LedgerAttempt(
                            projectId = other,
                            runId = RunId("r-other"),
                            issueId = "o-1",
                            identifier = "o-1",
                            attemptId = AttemptId("o1000000-0000-4000-8000-000000000000"),
                            setup = LedgerSetup("files", null, "command", null, "t".repeat(64), null),
                            startedAt = now - 2.hours,
                            finishedAt = now - 1.hours,
                            outcome = "succeeded",
                            reason = null,
                            turns = 1,
                            issueState = "review",
                            tokens = LedgerTokens(1000, 200, 0, 0),
                            costUsd = 0.5,
                            checks = null,
                            guards = null,
                            verdict = null,
                        ),
                    ).getOrThrow()
            }
        }
    }

    @Test
    fun `with a machine config status shows the machine slots in use, the cap and the machine day spend`() {
        seedMachine()
        fixture.loopHome.resolve("config.yaml").writeText("machine:\n  max_concurrent_agents: 2\n  per_day_usd: 10\n")

        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Project ${fixture.projectId()}   ${fixture.workflow.toRealPath()}",
                "No run in progress.",
                "Machine slots 1 of 2 in use",
                "Machine day spend 1200 tokens, 0.5 usd of 10",
                "",
                "Nothing running, retrying, waiting or parked.",
            ),
            result.stdout.trimEnd().lines(),
        )
    }

    @Test
    fun `without a cap status says so, and a malformed machine config is named`() {
        seedMachine()
        fixture.loopHome.resolve("config.yaml").writeText("machine:\n  per_day_tokens: 5000\n")
        assertContains(
            fixture.inProcess("status", fixture.workflow.toString()).stdout,
            "Machine slots 1 in use, no cap\nMachine day spend 1200 tokens of 5000, 0.5 usd\n",
        )

        fixture.loopHome.resolve("config.yaml").writeText("machine:\n  max_concurrent_agents: none\n")
        val result = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Machine config fails, no dispatch until it is fixed: ")
        assertContains(result.stdout, "machine.max_concurrent_agents must be a positive integer, got \"none\"")
    }

    @Test
    fun `without a machine config status prints no machine line`() {
        seedMachine()

        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(result.stdout.lines().none { it.startsWith("Machine") }, result.toString())
    }

    @Test
    fun `m4-50 status shows each declared resource, then each other one the project holds or waits for, with its holder and queue`() {
        fixture.writeWorkflow(extra = "resources:\n  - { kind: avd, id: api35 }\n  - { kind: display, id: main }\n")
        val other = ProjectId("0123456789abcdef")
        val granted = Instant.parse("2026-11-25T09:30:00.123Z")
        val clock =
            object : Clock {
                override fun now() = granted
            }
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            val locks = ResourceLocks(store, store, clock, RunId(LoopFixture.RUN_ID))
            runBlocking {
                locks.request(project, "t-1", "t-1", AttemptId("a1000000-0000-4000-8000-000000000001"), "avd", "api35").getOrThrow()
                locks.request(other, "o-9", "o-9", AttemptId("o9000000-0000-4000-8000-000000000009"), "avd", "api35").getOrThrow()
                locks.request(project, "t-2", "t-2", AttemptId("a2000000-0000-4000-8000-000000000002"), "avd", "api35").getOrThrow()
                locks.request(project, "t-3", "t-3", AttemptId("a3000000-0000-4000-8000-000000000003"), "gpu", "one").getOrThrow()
                locks.request(other, "o-9", "o-9", AttemptId("o9000000-0000-4000-8000-000000000009"), "avd", "theirs").getOrThrow()
            }
        }

        val result = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            listOf(
                "Resource avd api35  held_by=t-1 attempt=a1000000 since=2026-11-25T09:30:00Z queue=01234567/o-9,t-2",
                "Resource display main  free",
                "Resource gpu one  held_by=t-3 attempt=a3000000 since=2026-11-25T09:30:00Z",
            ),
            result.stdout.lines().filter { it.startsWith("Resource") },
        )
    }

    /** Takes the project's run file for this JVM, as a live `loop run` holds it. */
    private fun holdRunFile(): RunLock {
        val acquired = RunFiles(fixture.loopHome, project).acquire(RunRecord.ofCurrentProcess(LoopFixture.RUN_ID, fixture.workflow))
        return (acquired as? RunFiles.Acquired.Held)?.lock ?: fail("the run file is busy: $acquired")
    }

    private companion object {
        const val OLD_RUN = "0ld00000-0000-4000-8000-000000000000"
        const val A1 = "a1000000-0000-4000-8000-000000000000"
        const val A2 = "a2000000-0000-4000-8000-000000000000"
        const val B1 = "b1000000-0000-4000-8000-000000000000"
        const val C1 = "c1000000-0000-4000-8000-000000000000"
        const val E1 = "e1000000-0000-4000-8000-000000000000"
        const val F1 = "f1000000-0000-4000-8000-000000000000"
        const val G1 = "71000000-0000-4000-8000-000000000000"
        const val WAIT_NANOS = 90_000_000_000L
        const val POLL_MS = 300L
        const val NO_OCCUPANCY =
            "the runner reports no context occupancy, so only the prompt limit and the turn cap (agent.max_turns) hold for this attempt"
        const val UNKNOWN = "openai/gpt-unknown-9"

        /** m4-02: the line under a running session whose [model] has no window, null for a runner that named none. */
        fun noWindow(model: String?): String =
            (model?.let { "no window for $it in context.windows" } ?: "the runner named no model") +
                ", so the soft and hard limits do not hold for this session"
    }
}
