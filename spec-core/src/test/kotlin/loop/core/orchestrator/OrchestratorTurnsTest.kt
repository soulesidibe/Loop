package loop.core.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The turn loop of an attempt (m1-06, spec 16.5 and 10.2): continuation turns on one agent session. */
class OrchestratorTurnsTest {
    private val root: Path = createTempDirectory("loop-orchestrator-turns").toRealPath()
    private val sink = FakeSink()
    private val hookRunner = FakeHookRunner()

    private fun workflow(maxTurns: Int = 20): WorkflowFile =
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
            promptTemplate = "Do {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns),
                    hooks = HooksConfig(beforeRun = "echo before", afterRun = "echo after"),
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    /** A session whose turns all complete; [onTurn] runs inside turn `n` before it ends, for example to move the issue. */
    private fun sessionRunner(onTurn: suspend (Int) -> Unit = {}): FakeRunner =
        FakeRunner().also { runner ->
            runner.sessionScript = { turn, _ ->
                flow {
                    onTurn(turn)
                    emitAll(sessionTurn(turn))
                }
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `three turns on one session then review`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { turn -> if (turn == 3) tracker.moveIssue("id-m1-02", "review") }

            val result = orchestrator(tracker, runner).tick()

            val summary = result.attempts.single()
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals("review", summary.issueState)
            assertEquals(1, runner.sessionsOpened)
            assertEquals(listOf(1, 2, 3), runner.turnInputs.map { it.first })
            assertEquals("Do m1-02", runner.turnInputs[0].second)
            assertTrue(runner.turnInputs[1].second.startsWith("Continue working on m1-02. This is turn 2 of 20."))
            assertTrue(runner.turnInputs[2].second.startsWith("Continue working on m1-02. This is turn 3 of 20."))
            assertEquals(1, sink.ofKind("attempt_started").size)
            val started = sink.ofKind("turn_started")
            assertEquals(listOf("1", "2", "3"), started.map { it.data["turn"] })
            assertEquals(setOf("th1"), started.map { it.data["thread_id"] }.toSet())
            assertEquals(listOf("tu1", "tu2", "tu3"), started.map { it.data["turn_id"] })
            assertEquals(setOf("20"), started.map { it.data["max_turns"] }.toSet())
            assertEquals(3, sink.ofKind("turn_completed").size)
            assertEquals(1, sink.ofKind("phase").count { it.phase == AttemptPhase.StreamingTurn.name })
            assertEquals(1, sink.ofKind("phase").count { it.phase == AttemptPhase.Finishing.name })
        }

    @Test
    fun `stops at max_turns and records the outcome`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner()

            val summary = orchestrator(tracker, runner, workflow(maxTurns = 2)).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.MAX_TURNS, summary.exitReason)
            assertEquals(2, summary.turns)
            assertEquals("todo", summary.issueState)
            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(mapOf("exit_reason" to "max_turns", "turns" to "2"), sink.ofKind("attempt_finished").single().data - "outcome")
            val succeeded = sink.ofKind("phase").single { it.phase == AttemptPhase.Succeeded.name }
            assertEquals("max_turns", succeeded.data["exit_reason"])
            assertEquals("2", succeeded.data["turns"])
            // The last refresh is the closing one: one read per turn, no second read after the loop.
            assertEquals(2, tracker.requestedIds.size)
        }

    @Test
    fun `issue moved out of active states between turns gets no further turn`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { turn -> if (turn == 2) tracker.moveIssue("id-m1-02", "needs_info") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.NOT_ACTIVE, summary.exitReason)
            assertEquals("needs_info", summary.issueState)
        }

    @Test
    fun `issue moved to a terminal state gets no further turn`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { tracker.moveIssue("id-m1-02", "done") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(ExitReason.TERMINAL, summary.exitReason)
        }

    @Test
    fun `issue missing on refresh ends the attempt normally`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { tracker.issues.clear() }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.NOT_FOUND, summary.exitReason)
            assertNull(summary.issueState)
        }

    @Test
    fun `issue losing a required label gets no further turn`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { tracker.issues[0] = tracker.issues[0].copy(labels = emptyList()) }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.NOT_ELIGIBLE, summary.exitReason)
        }

    @Test
    fun `refresh error fails the attempt`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { tracker.fetchByIdsFailure = TrackerError.Unavailable(IOException("tracker down")) }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("issue_state_refresh_error", summary.reason)
            assertEquals(1, runner.stops)
            val finished = sink.ofKind("attempt_finished").single().data
            assertEquals("issue_state_refresh_error", finished["reason"])
            assertTrue("tracker down" in finished.getValue("error"), finished.toString())
        }

    @Test
    fun `a cancellation thrown by session stop does not replace the attempt result`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner()
            runner.onStop = { throw CancellationException("stop cancelled") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(AttemptOutcome.Succeeded.name.lowercase(), sink.ofKind("attempt_finished").single().data["outcome"])
        }

    @Test
    fun `m3-07 a failing session stop records session_stop_failed with its reason and keeps the attempt result`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = sessionRunner { tracker.moveIssue("id-m1-02", "review") }
            runner.onStop = { throw IllegalStateException("could not stop the claude-code process group 4242") }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(1, runner.stops)
            val failed = sink.ofKind("session_stop_failed").single()
            assertTrue("could not stop the claude-code process group 4242" in failed.data.getValue("reason"), failed.data.toString())
            assertEquals(summary.attemptId, failed.attemptId)
            assertEquals("m1-02", failed.issueIdentifier)
        }

    @Test
    fun `a failed continuation turn fails the attempt and records turn_failed`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner()
            runner.sessionScript = { turn, _ -> sessionTurn(turn, exitCode = if (turn == 2) 3 else 0) }

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("exit_code=3", summary.reason)
            assertEquals(2, summary.turns)
            val failed = sink.ofKind("turn_failed").single()
            assertEquals(
                mapOf("turn" to "2", "max_turns" to "20", "thread_id" to "th1", "turn_id" to "tu2", "reason" to "exit_code=3"),
                failed.data,
            )
            assertEquals(1, sink.ofKind("turn_completed").size)
            assertEquals(1, runner.stops)
        }

    @Test
    fun `before_run and after_run run once across three turns, after_run after stop`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val order = mutableListOf<String>()
            hookRunner.onRun = { order += it.name }
            val runner = sessionRunner { turn -> if (turn == 3) tracker.moveIssue("id-m1-02", "review") }
            runner.onStop = { order += "stop" }

            orchestrator(tracker, runner).tick()

            assertEquals(listOf("BEFORE_RUN", "stop", "AFTER_RUN"), order.filter { it != "AFTER_CREATE" })
            assertEquals(3, runner.turnInputs.size)
        }

    @Test
    fun `runner without continuation exits after one turn with no_continuation`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner()

            val summary = orchestrator(tracker, runner).tick().attempts.single()

            assertEquals(1, runner.calls.size)
            assertEquals(0, runner.sessionsOpened)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(ExitReason.NO_CONTINUATION, summary.exitReason)
            assertEquals(1, summary.turns)
            assertEquals("1", sink.ofKind("turn_started").single().data["turn"])
        }
}
