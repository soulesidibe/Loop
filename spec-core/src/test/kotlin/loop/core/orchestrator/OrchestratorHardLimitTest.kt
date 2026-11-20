package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.LedgerTurn
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.api.TurnLedger
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.CodexConfig
import loop.core.workflow.ContextConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m3-12, story 89 hard limit part and story 91 (plan 8.4, 6.6): a session whose occupancy reaches `context.hard_limit`
 * of its window during a turn is interrupted at once and rotated, and two rotations in a row without progress park the
 * issue with `split_issue` and the finding "split this issue".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorHardLimitTest {
    private val root: Path = createTempDirectory("loop-orchestrator-hard-limit").toRealPath()
    private val sink = FakeSink()
    private val parked = FakeParkedIssues()
    private val ledgerLines = mutableListOf<LedgerTurn>()

    private fun workflow(
        maxTurns: Int = 20,
        maxRotations: Int = 5,
        stallTimeoutMs: Long = CodexConfig().stallTimeoutMs,
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
            promptTemplate = TEMPLATE,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns),
                    context = ContextConfig(maxRotations = maxRotations),
                    codex = CodexConfig(stallTimeoutMs = stallTimeoutMs),
                ),
        )

    private fun orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        var next = 0
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
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
        )
    }

    /**
     * A runner declaring occupancy whose sessions are numbered from 1 in the order they open. Session `s`, turn `t` of it,
     * reports the occupancy `report(s, t)` of the Opus model when it is not null, runs [onTurn], then completes, unless
     * the report is at or above the hard limit, 250 000 tokens: then it waits until it is cancelled, like an agent that
     * goes on working. An [interruptible] runner ends that turn when Loop interrupts it instead, as Claude Code does:
     * the usage of the session, 1000 input tokens and 0.42 dollars, then a failed turn.
     */
    private fun runner(
        onTurn: suspend (Int, Int) -> Unit = { _, _ -> },
        interruptible: Boolean = false,
        report: (Int, Int) -> Long?,
    ): FakeRunner =
        FakeRunner().also { runner ->
            val interrupted = mutableMapOf<Int, CompletableDeferred<Unit>>()
            if (interruptible) runner.onInterrupt = { session -> interrupted.getOrPut(session) { CompletableDeferred() }.complete(Unit) }
            runner.occupancy = true
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
                    onTurn(session, turn)
                    val tokens = report(session, turn)
                    if (tokens != null) emit(RunnerEvent.ContextUsage(sessionId, turnId, tokens, OPUS))
                    if (tokens != null && tokens >= HARD) {
                        if (!interruptible) awaitCancellation()
                        interrupted.getOrPut(session) { CompletableDeferred() }.await()
                        emit(RunnerEvent.Usage(sessionId, turnId, 1000, 0, 0, 0, totalCostUsd = 0.42))
                        emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = "turn_failed: error_during_execution"))
                        return@flow
                    }
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    /** The workspace of [identifier], made a git work tree with one commit before the attempt reuses it. */
    private fun gitWorkspace(identifier: String): Path {
        val workspace = root.resolve("workspaces").resolve(identifier).createDirectories()
        git(workspace, "init", "-q")
        commit(workspace, "first")
        return workspace
    }

    private fun commit(
        workspace: Path,
        message: String,
    ) = git(workspace, "commit", "-q", "--allow-empty", "-m", message)

    private fun git(
        workspace: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
    }

    @Test
    fun `m3-12 a context_usage over the hard limit interrupts the turn at once and rotates with reason hard_limit`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner =
                runner(onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-12", "review") }) { session, _ ->
                    if (session == 1) 260_000L else 30_000L
                }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(2, summary.turns)
            assertEquals(2, runner.sessionsOpened)
            assertEquals(2, runner.stops)
            // The new session starts at its own turn 1, so a runtime starts it empty, never resumed.
            assertEquals(listOf(1, 1), runner.turnInputs.map { it.first })
            val rotated = runner.turnInputs[1].second
            assertTrue(rotated.startsWith("Do m3-12 kind=rotation\n### Goal and acceptance criteria"), rotated)
            val event = sink.ofKind("session_rotated").single()
            assertEquals("hard_limit", event.data["reason"])
            assertEquals("260000", event.data["occupancy_tokens"])
            assertEquals("0.2600", event.data["fraction"])
            assertEquals("1", event.data["rotation"])
            assertEquals("th1", event.data["old_thread_id"])
            assertEquals("th2", event.data["new_thread_id"])
            // The interrupted turn is not a failed turn: it completes, marked as interrupted.
            assertTrue(sink.ofKind("turn_failed").isEmpty(), sink.events.toString())
            val completed = sink.ofKind("turn_completed")
            assertEquals(listOf("hard_limit", null), completed.map { it.data["interrupted"] })
            val kinds = sink.events.map { it.kind }.filter { it == "session_rotated" || it.startsWith("turn_") || it == "context_usage" }
            val turn = listOf("turn_started", "context_usage", "turn_completed")
            assertEquals(turn + "session_rotated" + turn, kinds)
            assertEquals(listOf("hard_limit", null), ledgerLines.map { it.interrupted })
            assertEquals(listOf("completed", "completed"), ledgerLines.map { it.outcome })
        }

    @Test
    fun `m3-12 the hard limit asks the runner to interrupt the turn and keeps the usage and cost it reports then`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner =
                runner(
                    onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-12", "review") },
                    interruptible = true,
                ) { session, _ -> if (session == 1) 300_000L else 30_000L }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(listOf(1), runner.interrupts)
            // The runtime's own end of the interrupted turn is not a failure.
            assertTrue(sink.ofKind("turn_failed").isEmpty(), sink.events.toString())
            // It ended within the grace, so no expiry is recorded.
            assertTrue(sink.ofKind("interrupt_grace_expired").isEmpty(), sink.events.toString())
            assertEquals("hard_limit", sink.ofKind("session_rotated").single().data["reason"])
            val usage = sink.ofKind("usage").single()
            assertEquals("1000", usage.data["input_tokens"])
            val line = ledgerLines.first()
            assertEquals("hard_limit", line.interrupted)
            assertEquals("completed", line.outcome)
            assertEquals(1000L, line.tokens?.input)
            assertEquals(0.42, line.costUsd)
        }

    @Test
    fun `m3-12 a runner that does not end an interrupted turn is stopped after a grace`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner =
                runner(onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-12", "review") }) { session, _ ->
                    if (session == 1) 300_000L else 30_000L
                }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(listOf(1), runner.interrupts)
            assertTrue(sink.ofKind("attempt_stalled").isEmpty(), sink.events.toString())
            assertTrue(sink.ofKind("turn_failed").isEmpty(), sink.events.toString())
            assertEquals("hard_limit", sink.ofKind("session_rotated").single().data["reason"])
            // bl-136: the expiry is recorded on the interrupted turn, with the occupancy known then and no usage.
            val expired = sink.ofKind("interrupt_grace_expired").single()
            assertEquals("s1t1", expired.turnId?.value)
            assertEquals(
                mapOf("grace_ms" to "10000", "occupancy_tokens" to "300000", "window" to "1000000", "fraction" to "0.3000"),
                expired.data,
            )
        }

    @Test
    fun `bl-136 a runtime that keeps writing events after the interrupt is cancelled 10 seconds after the interrupt request`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            var interruptedAt: Long? = null
            var cancelledAt: Long? = null
            // Session 1 ignores the interrupt and writes a usage report every second for 30 seconds before its own end.
            val runner =
                FakeRunner().also { runner ->
                    runner.occupancy = true
                    runner.onInterrupt = { interruptedAt = testScheduler.currentTime }
                    runner.sessionScript = { turn, _ ->
                        val session = runner.sessionsOpened
                        flow {
                            val turnId = TurnId("s${session}t$turn")
                            val sessionId = SessionId("th$session-${turnId.value}")
                            emit(RunnerEvent.SessionStarted(sessionId, "th$session", turnId, runtimeSessionId = "runtime-$session"))
                            if (session == 1) {
                                emit(RunnerEvent.ContextUsage(sessionId, turnId, 300_000L, OPUS))
                                var ended = false
                                try {
                                    repeat(30) { n ->
                                        delay(1_000)
                                        val reported = n + 1
                                        val cost = 0.01 * reported
                                        emit(RunnerEvent.Usage(sessionId, turnId, 1_000L * reported, 0, 0, 0, totalCostUsd = cost))
                                    }
                                    ended = true
                                } finally {
                                    if (!ended) cancelledAt = testScheduler.currentTime
                                }
                                emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = "its own end"))
                            } else {
                                tracker.moveIssue("id-m3-12", "review")
                                emit(RunnerEvent.ContextUsage(sessionId, turnId, 30_000L, OPUS))
                                emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                            }
                        }
                    }
                }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(listOf(1), runner.interrupts)
            val asked = checkNotNull(interruptedAt)
            assertEquals(10_000L, checkNotNull(cancelledAt) { "the flow was never cancelled" } - asked)
            // The reports written during the grace are recorded, the turn is cut, not failed, and rotates.
            val usage = sink.ofKind("usage")
            assertTrue(usage.size in 9..10, usage.toString())
            assertTrue(sink.ofKind("turn_failed").isEmpty(), sink.events.toString())
            assertEquals("hard_limit", sink.ofKind("session_rotated").single().data["reason"])
            // The expiry names the grace, the occupancy and the latest usage known when Loop cancelled the flow.
            val expired = sink.ofKind("interrupt_grace_expired").single()
            assertEquals("s1t1", expired.turnId?.value)
            assertEquals("10000", expired.data["grace_ms"])
            assertEquals("300000", expired.data["occupancy_tokens"])
            assertEquals("0.3000", expired.data["fraction"])
            assertEquals(usage.last().data["attempt_tokens"], expired.data["attempt_tokens"])
            assertEquals(usage.last().data["total_cost_usd"], expired.data["total_cost_usd"])
            val kinds = sink.events.map { it.kind }
            assertTrue(kinds.indexOf("interrupt_grace_expired") < kinds.indexOf("turn_completed"), kinds.toString())
        }

    @Test
    fun `bl-149 a stall timeout shorter than the interrupt grace neither stalls the interrupted turn nor shortens the grace`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            var interruptedAt: Long? = null
            var cancelledAt: Long? = null
            // Session 1 goes silent after crossing the hard limit and ignores the interrupt, past the 3 second stall timeout.
            val runner =
                FakeRunner().also { runner ->
                    runner.occupancy = true
                    runner.onInterrupt = { interruptedAt = testScheduler.currentTime }
                    runner.sessionScript = { turn, _ ->
                        val session = runner.sessionsOpened
                        flow {
                            val turnId = TurnId("s${session}t$turn")
                            val sessionId = SessionId("th$session-${turnId.value}")
                            emit(RunnerEvent.SessionStarted(sessionId, "th$session", turnId, runtimeSessionId = "runtime-$session"))
                            if (session == 1) {
                                emit(RunnerEvent.ContextUsage(sessionId, turnId, 300_000L, OPUS))
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelledAt = testScheduler.currentTime
                                }
                            }
                            tracker.moveIssue("id-m3-12", "review")
                            emit(RunnerEvent.ContextUsage(sessionId, turnId, 30_000L, OPUS))
                            emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                        }
                    }
                }

            val summary = orchestrator(tracker, runner, workflow(stallTimeoutMs = 3_000L)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(10_000L, checkNotNull(cancelledAt) { "the flow was never cancelled" } - checkNotNull(interruptedAt))
            assertTrue(sink.ofKind("attempt_stalled").isEmpty(), sink.events.toString())
            assertTrue(sink.ofKind("turn_failed").isEmpty(), sink.events.toString())
            assertEquals("s1t1", sink.ofKind("interrupt_grace_expired").single().turnId?.value)
            assertEquals("hard_limit", sink.ofKind("session_rotated").single().data["reason"])
        }

    @Test
    fun `m3-12 the interrupted turn takes no max_attempts slot`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner = runner { session, _ -> if (session == 1) 300_000L else null }

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 2)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(listOf(1, 1), runner.turnInputs.map { it.first })
            val retry = sink.ofKind("retry_scheduled").single()
            assertEquals("continuation", retry.data["kind"])
            assertEquals("0", retry.data["failures"])
            assertTrue(parked.records.isEmpty())
        }

    @Test
    fun `m3-12 a failing stop during a hard limit rotation records session_stop_failed and starts no second session`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner = runner { _, _ -> 300_000L }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("session_stop_failed", summary.reason)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(1, runner.stops)
            assertEquals(1, sink.ofKind("session_stop_failed").size)
            assertTrue(sink.ofKind("session_rotated").isEmpty())
        }

    @Test
    fun `m3-12 a turn below the hard limit is not interrupted and rotates at its end on the soft limit`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner =
                runner(onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m3-12", "review") }) { session, _ ->
                    if (session == 1) 220_000L else 30_000L
                }

            orchestrator(tracker, runner).tick()

            assertEquals("soft_limit", sink.ofKind("session_rotated").single().data["reason"])
            assertNull(sink.ofKind("turn_completed").first().data["interrupted"])
        }

    @Test
    fun `m3-12 two rotations in a row with no new commit and no better check park the issue with split_issue`() =
        runTest {
            gitWorkspace("m3-12")
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner = runner { _, _ -> 300_000L }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("split_issue", summary.reason)
            assertEquals("split_issue", summary.parkReason?.wire)
            // The first crossing rotates, the second, again without progress, starts no third session.
            assertEquals(2, runner.sessionsOpened)
            assertEquals(2, runner.stops)
            assertEquals(1, sink.ofKind("session_rotated").size)
            val park = sink.ofKind("issue_parked").single()
            assertEquals("split_issue", park.data["reason"])
            assertEquals("2", park.data["without_progress"])
            assertEquals("1", park.data["rotations"])
            assertEquals("hard_limit", park.data["limit"])
            assertEquals("0.3000", park.data["fraction"])
            assertEquals(
                "split_issue",
                parked.records.values
                    .single()
                    .reason.wire,
            )
            // No max_attempts slot: no retry, and the comment names the finding for a human.
            assertTrue(sink.ofKind("retry_scheduled").isEmpty())
            val (issueId, comment) = tracker.comments.single()
            assertEquals("id-m3-12", issueId)
            assertEquals("loop", comment.author)
            assertContains(comment.text, "split this issue")
            assertContains(comment.text, "2 session rotations in a row")
            val commented = sink.ofKind("issue_commented").single()
            assertEquals("split_issue", commented.data["reason"])
        }

    @Test
    fun `m3-12 a rotation after a new commit resets the count of rotations without progress`() =
        runTest {
            val workspace = gitWorkspace("m3-12")
            val tracker = FakeTracker(listOf(issue("m3-12")))
            // Session 1 commits nothing, session 2 commits, session 3 commits nothing, session 4 hands off.
            val runner =
                runner(
                    onTurn = { session, _ ->
                        if (session == 2) commit(workspace, "work")
                        if (session == 4) tracker.moveIssue("id-m3-12", "review")
                    },
                ) { session, _ -> if (session < 4) 300_000L else 30_000L }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(4, runner.sessionsOpened)
            assertEquals(listOf("1", "2", "3"), sink.ofKind("session_rotated").map { it.data["rotation"] })
            assertTrue(parked.records.isEmpty())
            assertTrue(tracker.comments.isEmpty())
        }

    @Test
    fun `m3-12 a workspace that is not a git work tree never parks with split_issue`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-12")))
            val runner = runner { _, _ -> 300_000L }

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 3)).tick().attempts.single()

            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(2, sink.ofKind("session_rotated").size)
            assertTrue(parked.records.isEmpty())
        }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"

        /** The default hard limit, 0.25, of the Opus window of 1 000 000 tokens. */
        const val HARD = 250_000L
        const val TEMPLATE =
            "Do {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                "{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
    }
}
