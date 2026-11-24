package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A runner that cannot reach its model holds the whole run instead of using up attempts (bl-39). */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRunnerHoldTest {
    private val root: Path = createTempDirectory("loop-orchestrator-hold").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxAttempts: Int = 5,
        maxContinuations: Int = 3,
        prompt: String = PROMPT,
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
            promptTemplate = prompt,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = 2),
                    retries = RetriesConfig(maxAttempts = maxAttempts, maxContinuations = maxContinuations),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        parked: FakeParkedIssues = FakeParkedIssues(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
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
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    private fun FakeRunner.callsOf(identifier: String): Int = calls.count { it.first.issue.identifier == identifier }

    @Test
    fun `a runner_unavailable failure takes no max_attempts slot and requeues its issue at the probe`() =
        runTest {
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, workflow(maxAttempts = 1), parked)

            orchestrator.tick()

            assertEquals(emptyList(), sink.ofKind("issue_parked"), "max_attempts 1 would park an ordinary failure at once")
            assertTrue(parked.list(PROJECT).getOrThrow().isEmpty())
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("error", retry.data["kind"])
            assertEquals("0", retry.data["failures"])
            assertEquals("60000", retry.data["delay_ms"])
            assertEquals((FIXED_NOW + 1.minutes).toString(), retry.data["due_at"])
            assertEquals(REASON, retry.data["error"])
            val failed = sink.ofKind("turn_failed").single()
            assertEquals("runner_unavailable", failed.data["category"])
        }

    @Test
    fun `the hold starts at 1 minute, doubles to 15, dispatches one probe at a time and waits indefinitely`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val tracker = FakeTracker(listOf(issue("m1-02"), issue("m1-03")))
            val orchestrator = orchestrator(tracker, runner, sink, workflow(maxAttempts = 1))

            orchestrator.tick()

            assertEquals(2, runner.calls.size, "both issues were running when the runner failed")
            assertEquals(listOf("60000"), sink.ofKind("runner_hold_started").map { it.data["delay_ms"] })
            val delays = listOf(1, 2, 4, 8, 15, 15, 15, 15, 15, 15).map { it.minutes }
            delays.zipWithNext().forEachIndexed { index, (current, next) ->
                val calls = runner.calls.size
                advanceTimeBy(current - 1.milliseconds)
                orchestrator.tick()
                assertEquals(calls, runner.calls.size, "dispatched before the probe of hold ${index + 1}")
                advanceTimeBy(1.milliseconds)
                orchestrator.tick()
                assertEquals(calls + 1, runner.calls.size, "hold ${index + 1} probes with exactly one attempt")
                assertEquals(next.inWholeMilliseconds.toString(), sink.ofKind("runner_hold_started").last().data["delay_ms"])
            }
            assertEquals(delays.size, sink.ofKind("runner_hold_started").size, "one hold event per hold")
            assertEquals((1..delays.size).map { it.toString() }, sink.ofKind("runner_hold_started").map { it.data["hold"] })
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
            assertTrue(runner.callsOf("m1-02") > 1 && runner.callsOf("m1-03") > 1, "the probe is the next due retry, by turns")
        }

    @Test
    fun `a retry-after longer than the backoff sets the hold`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn(retryAfter = 5.minutes) }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            advanceTimeBy(5.minutes - 1.milliseconds)
            orchestrator.tick()

            val started = sink.ofKind("runner_hold_started").single()
            assertEquals("300000", started.data["delay_ms"])
            assertEquals("300000", started.data["retry_after_ms"])
            assertEquals(1, runner.calls.size)
            advanceTimeBy(1.milliseconds)
            orchestrator.tick()
            assertEquals(2, runner.calls.size)
        }

    @Test
    fun `each hold records one event with the issue and attempt, and one operator line naming the reason and the probe`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner { _, _ -> unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()

            val started = sink.ofKind("runner_hold_started").single()
            assertEquals(
                mapOf("reason" to REASON, "hold" to "1", "delay_ms" to "60000", "probe_at" to (FIXED_NOW + 1.minutes).toString()),
                started.data,
            )
            assertEquals("m1-02", started.issueIdentifier)
            assertEquals("a1", started.attemptId?.value)
            val line = logLines.single { "event=runner_hold" in it }
            assertTrue("outcome=started" in line && "hold=1" in line && "probe_at=${FIXED_NOW + 1.minutes}" in line, line)
            assertTrue("API Error: 403" in line, line)
        }

    @Test
    fun `a successful turn lifts the hold and resets its backoff`() =
        runTest {
            val sink = FakeSink()
            var call = 0
            // Unavailable, unavailable, then a successful probe, then unavailable again.
            val runner = FakeRunner { _, _ -> if (++call == 3) succeedingTurn() else unavailableTurn() }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink)

            orchestrator.tick()
            advanceTimeBy(1.minutes)
            orchestrator.tick()
            advanceTimeBy(2.minutes)
            orchestrator.tick()

            val lifted = sink.ofKind("runner_hold_lifted").single()
            assertEquals(mapOf("holds" to "2", "held_ms" to "180000"), lifted.data)
            assertEquals("a3", lifted.attemptId?.value)
            assertTrue(logLines.any { "event=runner_hold" in it && "outcome=released" in it })
            // The continuation of the normal exit runs at once and fails: a new hold, back at 1 minute.
            advanceTimeBy(1.seconds)
            orchestrator.tick()
            assertEquals(4, runner.calls.size)
            val again = sink.ofKind("runner_hold_started").last()
            assertEquals("1", again.data["hold"])
            assertEquals("60000", again.data["delay_ms"])
        }

    @Test
    fun `an issue that hits it 3 times in a row while another issue succeeds counts an ordinary failure`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { ctx, _ ->
                    if (ctx.issue.identifier == "m1-02") unavailableTurn() else succeedingTurn(after = 100.milliseconds)
                }
            val tracker = FakeTracker(listOf(issue("m1-02"), issue("m1-03")))
            val orchestrator = orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, maxContinuations = 1000))

            var waited = Duration.ZERO
            while (sink.ofKind("issue_parked").isEmpty() && waited < 10.minutes) {
                orchestrator.tick()
                advanceTimeBy(1.seconds)
                waited += 1.seconds
            }

            assertEquals(3, runner.callsOf("m1-02"))
            val park = sink.ofKind("issue_parked").single()
            assertEquals("m1-02", park.issueIdentifier)
            assertEquals("max_attempts", park.data["reason"])
            assertEquals("1", park.data["failures"])
            assertEquals(2, sink.ofKind("runner_hold_started").size, "the third hit starts no hold")
            assertEquals(2, sink.ofKind("runner_hold_lifted").size)
        }

    @Test
    fun `m4-08 a lost network holds the run, takes no slot, and the next attempt starts a fresh session with the brief`() =
        runTest {
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val runner = FakeRunner()
            // Session 1 loses the network, as bl-123 did; session 2, the probe after the hold, completes.
            runner.sessionScript = { turn, _ -> if (runner.sessionsOpened == 1) networkTurn() else sessionTurn(turn) }
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val workflow = workflow(maxAttempts = 1, prompt = BRIEF_PROMPT)
            val orchestrator = orchestrator(tracker, runner, sink, workflow, parked)

            orchestrator.tick()

            assertEquals("runner_unavailable", sink.ofKind("turn_failed").single().data["category"])
            val started = sink.ofKind("runner_hold_started").single()
            assertEquals(NETWORK_REASON, started.data["reason"])
            assertEquals("1", started.data["hold"])
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("0", retry.data["failures"], "the network failure takes no max_attempts slot")
            assertEquals(emptyList(), sink.ofKind("issue_parked"), "max_attempts 1 would park an ordinary failure at once")
            assertTrue(parked.list(PROJECT).getOrThrow().isEmpty())

            advanceTimeBy(1.minutes - 1.milliseconds)
            orchestrator.tick()
            assertEquals(1, runner.sessionsOpened, "nothing is dispatched while the hold lasts")
            advanceTimeBy(1.milliseconds)
            orchestrator.tick()

            assertEquals(2, runner.sessionsOpened, "the probe opens a fresh session, it resumes nothing")
            val (first, probe) = runner.turnInputs.map { it.second }
            assertEquals("m1-02 attempt=[]", first)
            assertTrue(probe.startsWith("m1-02 attempt=[1]\n# Brief\n### Goal and acceptance criteria"), probe)
            assertEquals(1, sink.ofKind("runner_hold_lifted").size)
            assertEquals(emptyList(), sink.ofKind("issue_parked"))
        }

    @Test
    fun `m4-46 after a network hold a session below the soft limit resumes, with the same prompt and its brief`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = heldRunner(tracker, heldOccupancy = 120_000)

            heldThenProbe(orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, prompt = BRIEF_PROMPT)))

            assertEquals(listOf("claude-session-1"), runner.resumedSessions, "0.12 of the window is below the soft limit 0.20")
            assertEquals(listOf(2), runner.resumedNumbers)
            val probe = runner.turnInputs[1].second
            assertTrue(probe.startsWith("m1-02 attempt=[1]\n# Brief\n### Goal and acceptance criteria"), probe)
            assertEquals(1, sink.ofKind("runner_hold_lifted").size)
        }

    @Test
    fun `m4-46 after a network hold a session at the soft limit starts fresh with the brief`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = heldRunner(tracker, heldOccupancy = 200_000)

            heldThenProbe(orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, prompt = BRIEF_PROMPT)))

            assertEquals(emptyList(), runner.resumedSessions, "0.20 of the window is at the soft limit")
            assertEquals(2, runner.sessionsOpened)
            val probe = runner.turnInputs[1].second
            assertTrue(probe.startsWith("m1-02 attempt=[1]\n# Brief\n### Goal and acceptance criteria"), probe)
        }

    @Test
    fun `m4-46 a runner that cannot resume, as Codex, starts fresh with the brief after a hold`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = heldRunner(tracker, heldOccupancy = 120_000, resumes = false)

            heldThenProbe(orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, prompt = BRIEF_PROMPT)))

            assertEquals(emptyList(), runner.resumedSessions)
            assertEquals(2, runner.sessionsOpened, "the probe opens a fresh session")
            val probe = runner.turnInputs[1].second
            assertTrue(probe.startsWith("m1-02 attempt=[1]\n# Brief\n### Goal and acceptance criteria"), probe)
        }

    @Test
    fun `m4-46 a rotation of the resumed session opens a fresh session, it never resumes`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            // The resumed session crosses the soft limit (0.205, under the hard limit 0.21), so it rotates at its turn end.
            val runner = heldRunner(tracker, heldOccupancy = 120_000, resumedOccupancy = 205_000)

            heldThenProbe(orchestrator(tracker, runner, sink, workflow(maxAttempts = 1, prompt = BRIEF_PROMPT)))

            assertEquals(3, runner.sessionsOpened)
            assertEquals(listOf(2), runner.resumedNumbers, "only the probe resumes, the rotation's session 3 is fresh")
            assertEquals("soft_limit", sink.ofKind("session_rotated").single().data["reason"])
        }

    @Test
    fun `m4-46 an ordinary failure below the soft limit starts fresh`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = heldRunner(tracker, heldOccupancy = 120_000, category = null)
            val orchestrator = orchestrator(tracker, runner, sink, workflow(maxAttempts = 3, prompt = BRIEF_PROMPT))

            orchestrator.tick()
            assertEquals(emptyList(), sink.ofKind("runner_hold_started"))
            advanceTimeBy(1.minutes)
            orchestrator.tick()

            assertEquals(2, runner.sessionsOpened)
            assertEquals(emptyList(), runner.resumedSessions)
        }

    /** Ticks the first attempt, which the hold takes, then the probe once the first period ended. */
    private suspend fun TestScope.heldThenProbe(orchestrator: Orchestrator) {
        orchestrator.tick()
        advanceTimeBy(1.minutes)
        orchestrator.tick()
    }

    /**
     * A runner declaring occupancy, with the model [OPUS] whose window is 1,000,000 tokens. Session 1 reports
     * [heldOccupancy] and loses the network ([category], `runner_unavailable` by default). Each later session `s`
     * starts with the runtime session id `claude-session-s`, reports [resumedOccupancy] when it is session 2, then
     * completes; session 2 without a rotation, or session 3, moves the issue to review, so the attempt ends.
     */
    private fun heldRunner(
        tracker: FakeTracker,
        heldOccupancy: Long,
        resumes: Boolean = true,
        resumedOccupancy: Long = 30_000,
        category: RunnerEvent.TurnFailed.Category? = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.occupancy = true
            runner.resumes = resumes
            runner.sessionScript = { turn, _ ->
                val number = runner.sessionsOpened
                flow {
                    val turnId = TurnId("s${number}t$turn")
                    val sessionId = SessionId("th$number-${turnId.value}")
                    if (turn == 1) {
                        emit(RunnerEvent.SessionStarted(sessionId, "th$number", turnId, runtimeSessionId = "claude-session-$number"))
                    } else {
                        emit(RunnerEvent.TurnStarted(sessionId, "th$number", turnId, turn))
                    }
                    if (number == 1) {
                        emit(RunnerEvent.ContextUsage(sessionId, turnId, heldOccupancy, OPUS))
                        emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = NETWORK_REASON, category = category))
                    } else {
                        val occupancy = if (number == 2) resumedOccupancy else 30_000L
                        emit(RunnerEvent.ContextUsage(sessionId, turnId, occupancy, OPUS))
                        if (occupancy < 200_000) tracker.moveIssue("id-m1-02", "review")
                        emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                    }
                }
            }
        }

    /** The turn a lost network ends, as the Claude Code runner maps the recorded result line of bl-123. */
    private fun networkTurn(): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            emit(
                RunnerEvent.TurnFailed(
                    SESSION,
                    TURN,
                    exitCode = null,
                    reason = NETWORK_REASON,
                    category = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
                ),
            )
        }

    private fun unavailableTurn(retryAfter: Duration? = null): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            emit(
                RunnerEvent.TurnFailed(
                    SESSION,
                    TURN,
                    exitCode = 1,
                    reason = REASON,
                    category = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
                    retryAfter = retryAfter,
                ),
            )
        }

    private fun succeedingTurn(after: Duration): Flow<RunnerEvent> =
        flow {
            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
            delay(after)
            emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val OPUS = "anthropic/claude-opus-5-5"
        const val PROMPT = "{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
        const val REASON = "turn_failed: Failed to authenticate. API Error: 403 Attention Required! | Cloudflare"
        const val NETWORK_REASON = "turn_failed: API Error: Can't reach the API server, check your internet or DNS (ENOTFOUND)"
        const val BRIEF_PROMPT =
            "{{ issue.identifier }} attempt=[{{ attempt }}]{% if loop.session_brief %}\n# Brief\n{{ loop.session_brief }}{% endif %}"
    }
}
