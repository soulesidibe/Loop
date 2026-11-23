package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.LedgerTurn
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TrackerError
import loop.api.TurnId
import loop.api.TurnLedger
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.verify.VerifyFindings
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.ContextConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * m3-11, story 89 soft limit part (plan 8.2 and 8.4): a session whose latest occupancy reaches `context.soft_limit` of
 * its window during a turn is ended at that turn's end and a fresh one starts in the same workspace with a session
 * brief. Not a retry and not a failure; counted, traced and capped by `context.max_rotations`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRotationTest {
    private val root: Path = createTempDirectory("loop-orchestrator-rotation").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val ledgerLines = mutableListOf<LedgerTurn>()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxTurns: Int = 20,
        maxRotations: Int = 5,
        windows: Map<String, Long> = ContextConfig.DEFAULT_WINDOWS,
        template: String = TEMPLATE,
        checks: List<CheckConfig>? = null,
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = template,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns),
                    // The m3-11 hard limit of 0.25, above the default of 0.21 (m4-02), so these soft limit cases reach
                    // the soft limit and not the hard one.
                    context = ContextConfig(hardLimit = 0.25, maxRotations = maxRotations, windows = windows),
                    kinds = checks?.let { KindsConfig(KindConfig(it, delivery = DeliveryConfig(DeliveryPolicy.Auto))) },
                    retries = RetriesConfig(maxAttempts = 5),
                ),
        )

    private fun orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
        checks: CheckRunner = CheckRunner.NONE,
        clock: Clock = FixedClock(),
    ): Orchestrator {
        var next = 0
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            turnLedger =
                TurnLedger {
                    ledgerLines += it
                    Result.success(Unit)
                },
            checkRunnerFor = { checks },
            fingerprinter = Fingerprinter { Result.success("fp") },
        )
    }

    /** A check runner that answers its calls with [results] in order, exit code and output, the last one repeated. */
    private class ScriptedChecks(
        private vararg val results: Pair<Int, String>,
    ) : CheckRunner {
        var calls = 0

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            val (code, output) = results.getOrElse(calls) { results.last() }
            calls++
            return CheckResult(code, timedOut = false, durationMs = 1200, output = output)
        }
    }

    /** The findings the agent reads after a refutation of [CHECK] with [output], the way the orchestrator builds them. */
    private fun findingsOf(output: String): String =
        VerifyFindings.of(CHECK, CheckResult(1, timedOut = false, durationMs = 1200, output = output), Redactor.NONE).text

    /** Queues a move to review the way `loop tool move` does, in the tool request folder of the attempt [config] names. */
    private class Submitter {
        var config: Path? = null
        private var now = 1_000L

        fun submit() = ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ }).enqueue(ToolRequest.Move("review"))
    }

    /**
     * A runner declaring [occupancy] whose sessions are numbered from 1 in the order they open: session `s`, turn `t`
     * of it, reports the occupancies `occupancy(s, t)` with [model] and the usage `usage(s, t)`, runs [onTurn], then
     * completes.
     */
    private fun runner(
        occupancy: Boolean = true,
        model: String? = OPUS,
        usage: (Int, Int) -> Long? = { _, _ -> null },
        onTurn: suspend (Int, Int) -> Unit = { _, _ -> },
        reports: (Int, Int) -> List<Long>,
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.occupancy = occupancy
            runner.sessionScript = { turn, _ ->
                val session = runner.sessionsOpened
                flow {
                    val thread = "th$session"
                    val turnId = TurnId("s${session}t$turn")
                    val sessionId = SessionId("$thread-${turnId.value}")
                    if (turn == 1) {
                        emit(RunnerEvent.SessionStarted(sessionId, thread, turnId, runtimeSessionId = "runtime-$session"))
                    } else {
                        emit(RunnerEvent.TurnStarted(sessionId, thread, turnId, turn))
                    }
                    reports(session, turn).forEach { emit(RunnerEvent.ContextUsage(sessionId, turnId, it, model)) }
                    usage(session, turn)?.let { emit(RunnerEvent.Usage(sessionId, turnId, it, 0, 0, 0, it / 100.0)) }
                    onTurn(session, turn)
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `m3-11 a session over the soft limit rotates at its turn end into a new session with retry_kind rotation and the brief`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val workspaces = mutableListOf<Path>()
            val runner =
                runner(onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-11", "review") }) { session, _ ->
                    if (session == 1) listOf(100_000L, 240_000L) else listOf(30_000L)
                }
            runner.onCall = { workspaces.add(it.workspace) }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(2, summary.turns)
            assertEquals(2, runner.sessionsOpened)
            assertEquals(2, runner.stops)
            assertEquals(1, workspaces.distinct().size, "$workspaces")
            // The new session starts at its own turn 1, so a runtime starts it empty, never resumed.
            assertEquals(listOf(1, 1), runner.turnInputs.map { it.first })
            assertEquals("Do m3-11", runner.turnInputs[0].second)
            val rotated = runner.turnInputs[1].second
            assertTrue(rotated.startsWith("Do m3-11 kind=rotation\n### Goal and acceptance criteria"), rotated)
            assertTrue("- Rotations: 1" in rotated, rotated)
            val event = sink.ofKind("session_rotated").single()
            assertEquals(
                mapOf(
                    "reason" to "soft_limit",
                    "occupancy_tokens" to "240000",
                    "window" to "1000000",
                    "fraction" to "0.2400",
                    "rotation" to "1",
                    "max_rotations" to "5",
                    "old_thread_id" to "th1",
                    "old_runtime_session_id" to "runtime-1",
                    "new_thread_id" to "th2",
                    "new_runtime_session_id" to "runtime-2",
                ),
                event.data,
            )
            assertEquals(summary.attemptId, event.attemptId)
            // Traced between the old session's last turn and the new session's first.
            val kinds = sink.events.map { it.kind }.filter { it == "session_rotated" || it.startsWith("turn_") }
            assertEquals(listOf("turn_started", "turn_completed", "session_rotated", "turn_started", "turn_completed"), kinds)
            val started = sink.ofKind("turn_started").map { it.data["turn"] to it.data["thread_id"] }
            assertEquals(listOf("1" to "th1", "2" to "th2"), started)
            // The once per attempt phases are not recorded again for the new session.
            assertEquals(1, sink.events.count { it.phase == "InitializingSession" })
        }

    @Test
    fun `m3-11 a failing stop during a rotation records session_stop_failed and starts no second session`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner { _, _ -> listOf(240_000L) }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("session_stop_failed", summary.reason)
            assertEquals(1, summary.turns)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(1, runner.stops)
            val failed = sink.ofKind("session_stop_failed").single()
            assertTrue("process group 4242" in failed.data.getValue("reason"), failed.data.toString())
            assertTrue(sink.ofKind("session_rotated").isEmpty())
            // An error retry, counted like any failure.
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("error", retry.data["kind"])
            assertEquals("1", retry.data["failures"])
        }

    @Test
    fun `m3-11 a rotation takes no max_attempts slot and turns keep counting toward max_turns`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner { _, _ -> listOf(240_000L) }

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 3)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(3, summary.turns)
            // Two rotations; the last turn ends the attempt, so it rotates no more.
            assertEquals(3, runner.sessionsOpened)
            assertEquals(listOf(1, 1, 1), runner.turnInputs.map { it.first })
            assertEquals(listOf("1", "2"), sink.ofKind("session_rotated").map { it.data["rotation"] })
            assertEquals(listOf("1/3", "2/3", "3/3"), sink.ofKind("turn_started").map { "${it.data["turn"]}/${it.data["max_turns"]}" })
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("continuation", retry.data["kind"])
            assertEquals("0", retry.data["failures"])
            assertTrue(parked.records.isEmpty())
            // The ledger: every turn line carries the attempt's rotations so far.
            assertEquals(listOf(0, 1, 2), ledgerLines.map { it.rotations })
            assertEquals(listOf("th1", "th2", "th3"), ledgerLines.map { it.threadId })
        }

    @Test
    fun `m3-11 the rotation past max_rotations parks the issue with max_rotations`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner { _, _ -> listOf(240_000L) }

            val summary = orchestrator(tracker, runner, workflow(maxRotations = 1)).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("max_rotations", summary.parkReason?.wire)
            assertEquals(2, summary.turns)
            assertEquals(2, runner.sessionsOpened)
            assertEquals(2, runner.stops)
            assertEquals(1, sink.ofKind("session_rotated").size)
            val park = sink.ofKind("issue_parked").single()
            assertEquals("max_rotations", park.data["reason"])
            assertEquals("1", park.data["rotations"])
            assertEquals("1", park.data["max_rotations"])
            assertEquals("240000", park.data["occupancy_tokens"])
            assertEquals("0.2400", park.data["fraction"])
            assertEquals(
                "max_rotations",
                parked.records.values
                    .single()
                    .reason.wire,
            )
            assertTrue(sink.ofKind("retry_scheduled").isEmpty())
        }

    @Test
    fun `m3-11 a runner that declares no occupancy never rotates, whatever it reports`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner(occupancy = false) { _, _ -> listOf(900_000L) }

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 3)).tick().attempts.single()

            assertEquals(3, summary.turns)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertTrue(sink.ofKind("session_rotated").isEmpty())
        }

    @Test
    fun `m3-11 a session whose model has no window never rotates`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner(model = "anthropic/claude-unknown-9") { _, _ -> listOf(900_000L) }

            orchestrator(tracker, runner, workflow(maxTurns = 3)).tick()

            assertEquals(1, runner.sessionsOpened)
            assertTrue(sink.ofKind("session_rotated").isEmpty())
        }

    @Test
    fun `m3-14 a rotated prompt over the prompt limit is cut and its cuts are recorded on session_rotated`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            // A 2000 token window: the soft limit is 400 tokens and the prompt limit 100, which the brief is over.
            val runner =
                runner(onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-11", "review") }) { session, _ ->
                    if (session == 1) listOf(450L) else listOf(30L)
                }

            orchestrator(tracker, runner, workflow(windows = mapOf(OPUS to 2_000L))).tick()

            assertEquals(2, runner.sessionsOpened)
            val rotated = runner.turnInputs[1].second
            assertTrue(rotated.startsWith("Do m3-11 kind=rotation\n### Goal and acceptance criteria"), rotated)
            assertTrue("(cut to fit the prompt limit)" in rotated || "(older lines cut to fit the prompt limit)" in rotated, rotated)
            val data = sink.ofKind("session_rotated").single().data
            assertEquals("100", data["prompt_limit_tokens"])
            assertEquals("false", data["over_limit"])
            assertTrue(data.getValue("prompt_tokens").toInt() <= 100, data.toString())
            assertTrue(data.getValue("cut_session_brief_tokens").toInt() > 0, data.toString())
            listOf("template", "issue", "verify_findings", "lessons", "context_sources").forEach { slot ->
                assertEquals("0", data["cut_${slot}_tokens"], slot)
            }
            // The rotation's own data is still there, the cut data after it.
            assertEquals("soft_limit", data["reason"])
            assertEquals("th2", data["new_thread_id"])
        }

    @Test
    fun `m3-11 the attempt's spend keeps the closed session's totals after a rotation`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner =
                runner(
                    usage = { session, _ -> if (session == 1) 1_000L else 200L },
                    onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-11", "review") },
                ) { session, _ -> if (session == 1) listOf(240_000L) else listOf(10_000L) }

            orchestrator(tracker, runner).tick()

            val usages = sink.ofKind("usage").map { it.data["attempt_tokens"] to it.data["total_cost_usd"] }
            assertEquals(listOf("1000" to "10.0", "1200" to "12.0"), usages)
            assertEquals(listOf(1_000L, 200L), ledgerLines.map { it.tokens?.total })
        }

    @Test
    fun `bl-132 a wake's usage the old session reports at its stop counts in the attempt's spend`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner =
                runner(
                    usage = { session, _ -> if (session == 1) 1_000L else 200L },
                    onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-11", "review") },
                ) { session, _ -> if (session == 1) listOf(240_000L) else listOf(10_000L) }
            // The wake of session 1 ended while the process was idle: its result line, read at the stop, brings it to 1500.
            runner.stopUsage = { session ->
                if (session == 1) listOf(RunnerEvent.Usage(SessionId("th1-s1t1"), TurnId("s1t1"), 1_500L, 0, 0, 0, 15.0)) else emptyList()
            }

            orchestrator(tracker, runner).tick()

            val usages = sink.ofKind("usage").map { it.data["attempt_tokens"] to it.data["total_cost_usd"] }
            assertEquals(listOf("1000" to "10.0", "1500" to "15.0", "1700" to "17.0"), usages)
            // Recorded before the new session starts, so the next turn line counts it.
            val kinds = sink.events.map { it.kind }.filter { it == "usage" || it == "session_rotated" }
            assertEquals(listOf("usage", "usage", "session_rotated", "usage"), kinds)
            assertEquals(listOf(1_000L, 700L), ledgerLines.map { it.tokens?.total })
        }

    @Test
    fun `bl-132 a rotated prompt that fails to render still records session_rotated before the attempt ends`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner = runner { _, _ -> listOf(240_000L) }
            val template = TEMPLATE + "{% if loop.retry_kind == \"rotation\" %}{{ nope }}{% endif %}"

            val summary = orchestrator(tracker, runner, workflow(template = template)).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertTrue(summary.reason.orEmpty().startsWith("template error"), summary.reason)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(1, runner.stops)
            val event = sink.ofKind("session_rotated").single()
            assertEquals("1", event.data["rotation"])
            assertEquals("th1", event.data["old_thread_id"])
            assertFalse("new_thread_id" in event.data, event.data.toString())
            val kinds = sink.events.map { it.kind }.filter { it == "session_rotated" || it == "attempt_finished" || it.startsWith("turn_") }
            assertEquals(listOf("turn_started", "turn_completed", "session_rotated", "attempt_finished"), kinds)
        }

    @Test
    fun `bl-132 a rotated prompt shows the issue as the tracker returns it at the rotation`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner =
                runner(
                    onTurn = { session, _ ->
                        if (session == 1) tracker.issues[0] = tracker.issues[0].copy(title = "Title as edited")
                        if (session == 2) tracker.moveIssue("id-m3-11", "review")
                    },
                ) { session, _ -> if (session == 1) listOf(240_000L) else listOf(10_000L) }

            orchestrator(tracker, runner, workflow(template = TITLED)).tick()

            assertTrue(runner.turnInputs[0].second.startsWith("Do m3-11: Title of m3-11"), runner.turnInputs[0].second)
            val rotated = runner.turnInputs[1].second
            assertTrue(rotated.startsWith("Do m3-11: Title as edited kind=rotation\n"), rotated)
            assertTrue("m3-11: Title as edited" in rotated.substringAfter("### Goal and acceptance criteria"), rotated)
            assertTrue(logLines.none { "level=warn" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `bl-132 a rotated prompt shows the issue as dispatched when the read at the rotation fails, with a warning`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val runner =
                runner(
                    onTurn = { session, _ ->
                        if (session == 1) tracker.issues[0] = tracker.issues[0].copy(title = "Title as edited")
                        if (session == 2) {
                            tracker.fetchByIdsFailure = null
                            tracker.moveIssue("id-m3-11", "review")
                        }
                    },
                ) { session, _ -> if (session == 1) listOf(240_000L) else listOf(10_000L) }
            // The turn end's read succeeds; the read right before the rotated prompt, after the stop, fails.
            runner.onStop =
                { if (runner.sessionsOpened == 1) tracker.fetchByIdsFailure = TrackerError.Unavailable(IOException("tracker down")) }

            val summary = orchestrator(tracker, runner, workflow(template = TITLED)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(1, sink.ofKind("session_rotated").size)
            val rotated = runner.turnInputs[1].second
            assertTrue(rotated.startsWith("Do m3-11: Title of m3-11 kind=rotation\n"), rotated)
            val warning = logLines.single { "level=warn" in it && "event=rotation_issue" in it }
            assertTrue("tracker down" in warning, warning)
        }

    @Test
    fun `bl-132 a rotation on a retry whose latest verdict is verified carries no findings of an earlier attempt`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            // Every move the delivery tries fails, so a verified result leaves the issue active.
            tracker.moveFailure = TrackerError.Unavailable(IOException("moves are closed"))
            // Attempt 1 is refuted on both its turns (each failing check runs again); attempt 2's check passes.
            val checks =
                ScriptedChecks(
                    1 to "turn one failure",
                    1 to "turn one failure",
                    1 to "turn two failure",
                    1 to "turn two failure",
                    0 to "ok",
                )
            val submitter = Submitter()
            val runner =
                runner(onTurn = { session, turn -> if (session == 1 || (session == 2 && turn == 1)) submitter.submit() }) { session, _ ->
                    // Session 2 is attempt 2's first: it crosses the soft limit.
                    if (session == 2) listOf(240_000L) else emptyList()
                }
            runner.onCall = { submitter.config = it.env["LOOP_TOOL_CONFIG"]?.let { path -> Path.of(path) } }
            val workflow = workflow(maxTurns = 2, template = FINDINGS, checks = listOf(CheckConfig.Command(CHECK)))
            val orchestrator = orchestrator(tracker, runner, workflow, checks, SchedulerClock(testScheduler))

            val first = orchestrator.tick().attempts.single()
            advanceTimeBy(60_000)
            orchestrator.tick()

            assertEquals("refuted", first.reason)
            // Attempt 2 was dispatched with attempt 1's findings.
            val dispatched = runner.turnInputs[2]
            assertEquals(1, dispatched.first)
            assertTrue(
                dispatched.second.startsWith("Do m3-11 kind=refuted\nFindings: ${findingsOf("turn two failure")}"),
                dispatched.second,
            )
            assertEquals(1, sink.ofKind("session_rotated").size)
            assertEquals(3, runner.sessionsOpened)
            val rotated = runner.turnInputs[3].second
            assertTrue(rotated.startsWith("Do m3-11 kind=rotation\n### Goal"), rotated)
            assertFalse("Findings:" in rotated, rotated)
            assertFalse("Findings of the last refutation" in rotated, rotated)
            assertFalse("failure" in rotated, rotated)
        }

    @Test
    fun `bl-132 two refuted turns in one attempt, the rotated prompt carries the second verdict's findings`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            val checks = ScriptedChecks(1 to "first failure", 1 to "first failure", 1 to "second failure")
            val submitter = Submitter()
            val runner =
                runner(onTurn = { session, _ -> if (session == 1) submitter.submit() }) { session, turn ->
                    // The second turn of session 1 crosses the soft limit.
                    if (session == 1 && turn == 2) listOf(240_000L) else emptyList()
                }
            runner.onCall = { submitter.config = it.env["LOOP_TOOL_CONFIG"]?.let { path -> Path.of(path) } }
            val workflow = workflow(maxTurns = 3, template = FINDINGS, checks = listOf(CheckConfig.Command(CHECK)))

            orchestrator(tracker, runner, workflow, checks).tick()

            assertEquals(1, sink.ofKind("session_rotated").size)
            assertEquals(listOf("refuted", "refuted"), sink.ofKind("verdict").map { it.data["verdict"] })
            val rotated = runner.turnInputs[2].second
            val second = findingsOf("second failure")
            assertTrue(rotated.startsWith("Do m3-11 kind=rotation\nFindings: $second\n### Goal"), rotated)
            assertTrue("Findings of the last refutation:\n$second" in rotated, rotated)
            assertFalse("first failure" in rotated, rotated)
        }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
        const val CHECK = "./gradlew test"
        const val TEMPLATE =
            "Do {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                "{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
        const val TITLED =
            "Do {{ issue.identifier }}: {{ issue.title }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                "{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
        const val FINDINGS =
            "Do {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}" +
                "{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
    }
}
