package loop.core.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.LedgerPeak
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
import loop.core.workflow.ContextConfig
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

/** m3-08: the orchestrator keeps the latest and the peak occupancy of a session and records one `context_usage` per turn end. */
class OrchestratorContextUsageTest {
    private val root: Path = createTempDirectory("loop-orchestrator-context").toRealPath()
    private val sink = FakeSink()
    private val logLines = mutableListOf<String>()
    private val ledgerLines = mutableListOf<LedgerTurn>()

    private fun workflow(
        maxTurns: Int = 2,
        windows: Map<String, Long> = ContextConfig.DEFAULT_WINDOWS,
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
            promptTemplate = "Do {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxTurns = maxTurns), context = ContextConfig(windows = windows)),
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
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            turnLedger =
                TurnLedger {
                    ledgerLines += it
                    Result.success(Unit)
                },
        )
    }

    /** A session whose turn `n` reports the occupancies of [occupancy] for `n`, each with [model], then completes. */
    private fun runner(
        model: String? = OPUS,
        occupancy: (Int) -> List<Long>,
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.sessionScript = { turn, _ -> turn(turn, occupancy(turn), model) }
        }

    private fun turn(
        turn: Int,
        occupancy: List<Long>,
        model: String?,
    ): Flow<RunnerEvent> =
        flow {
            val turnId = TurnId("tu$turn")
            val sessionId = SessionId("th1-tu$turn")
            if (turn == 1) {
                emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
            } else {
                emit(RunnerEvent.TurnStarted(sessionId, threadId = "th1", turnId = turnId, turn = turn))
            }
            occupancy.forEach { emit(RunnerEvent.ContextUsage(sessionId, turnId, it, model)) }
            emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    @Test
    fun `m3-08 one context_usage per turn end with the latest and peak occupancy, the window and the fraction`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            val runner = runner { turn -> if (turn == 1) listOf(300_000L, 120_000L) else listOf(200_000L) }

            orchestrator(tracker, runner).tick()

            val recorded = sink.ofKind("context_usage")
            assertEquals(
                listOf(
                    mapOf(
                        "latest_tokens" to "120000",
                        "peak_tokens" to "300000",
                        "model" to OPUS,
                        "window" to "1000000",
                        "fraction" to "0.1200",
                    ),
                    mapOf(
                        "latest_tokens" to "200000",
                        "peak_tokens" to "300000",
                        "model" to OPUS,
                        "window" to "1000000",
                        "fraction" to "0.2000",
                    ),
                ),
                recorded.map { it.data },
            )
            assertEquals(listOf("tu1", "tu2"), recorded.map { it.turnId?.value })
            assertEquals(listOf("th1-tu1", "th1-tu2"), recorded.map { it.sessionId?.value })
            // Each comes right before its turn's end.
            val kinds = sink.events.map { it.kind }.filter { it == "context_usage" || it.startsWith("turn_") }
            assertEquals(
                listOf("turn_started", "context_usage", "turn_completed", "turn_started", "context_usage", "turn_completed"),
                kinds,
            )
        }

    @Test
    fun `m3-08 the window comes from context windows for the model, a configured one included`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            val haiku = "anthropic/claude-haiku-5-5"
            val runner = runner(model = haiku) { listOf(43_400L) }

            orchestrator(tracker, runner, workflow(maxTurns = 1, windows = ContextConfig.DEFAULT_WINDOWS + (haiku to 200_000L))).tick()

            val data = sink.ofKind("context_usage").single().data
            assertEquals("200000", data["window"])
            assertEquals("0.2170", data["fraction"])
            assertTrue(logLines.none { "context_window" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `m3-08 a turn without a report repeats the session's latest and peak`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            val runner = runner { turn -> if (turn == 1) listOf(50_000L) else emptyList() }

            orchestrator(tracker, runner).tick()

            val recorded = sink.ofKind("context_usage")
            assertEquals(listOf("tu1", "tu2"), recorded.map { it.turnId?.value })
            assertEquals(listOf("50000", "50000"), recorded.map { it.data["latest_tokens"] })
        }

    @Test
    fun `m3-08 a model with no window reports tokens without a fraction and warns once, naming the model`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            val unknown = "anthropic/claude-unknown-9"
            val runner = runner(model = unknown) { turn -> listOf(10_000L * turn) }

            orchestrator(tracker, runner).tick()

            assertEquals(
                listOf(
                    mapOf("latest_tokens" to "10000", "peak_tokens" to "10000", "model" to unknown),
                    mapOf("latest_tokens" to "20000", "peak_tokens" to "20000", "model" to unknown),
                ),
                sink.ofKind("context_usage").map { it.data },
            )
            val warnings = logLines.filter { "level=warn" in it && "event=context_window" in it }
            assertEquals(1, warnings.size, logLines.joinToString("\n"))
            assertTrue("model=$unknown" in warnings.single(), warnings.single())
            assertTrue("context.windows" in warnings.single(), warnings.single())
        }

    @Test
    fun `m3-08 a runner that names no model reports tokens without a fraction and warns once`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            val runner = runner(model = null) { listOf(7_000L) }

            orchestrator(tracker, runner).tick()

            val tokensOnly = mapOf("latest_tokens" to "7000", "peak_tokens" to "7000")
            assertEquals(listOf(tokensOnly, tokensOnly), sink.ofKind("context_usage").map { it.data })
            assertEquals(1, logLines.count { "level=warn" in it && "event=context_window" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `m3-08 a runner that reports no occupancy records no context_usage`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))

            orchestrator(tracker, runner { emptyList() }).tick()

            assertEquals(2, sink.ofKind("turn_completed").size)
            assertTrue(sink.ofKind("context_usage").isEmpty(), sink.events.map { it.kind }.toString())
            assertTrue(logLines.none { "context_window" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `m3-09 attempt_started records whether the attempt's runner declares occupancy`() =
        runTest {
            val declaring = runner { listOf(1_000L) }.apply { occupancy = true }
            orchestrator(FakeTracker(listOf(issue("m3-09"))), declaring).tick()
            val silent = runner { emptyList() }
            orchestrator(FakeTracker(listOf(issue("m3-10"))), silent).tick()

            assertEquals(listOf("true", "false"), sink.ofKind("attempt_started").map { it.data["occupancy"] })
        }

    @Test
    fun `bl-132 two sessions of one attempt each record their own peak, the second starting from zero`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-08")))
            // Session 1 crosses the soft limit in its only turn and rotates; session 2 runs two turns well below it.
            val runner = FakeRunner().apply { occupancy = true }
            runner.sessionScript = { turn, _ ->
                val session = runner.sessionsOpened
                val reports = if (session == 1) listOf(240_000L, 210_000L) else listOf(listOf(30_000L), listOf(20_000L))[turn - 1]
                flow {
                    val thread = "th$session"
                    val turnId = TurnId("s${session}t$turn")
                    val sessionId = SessionId("$thread-${turnId.value}")
                    if (turn == 1) {
                        emit(RunnerEvent.SessionStarted(sessionId, threadId = thread, turnId = turnId))
                    } else {
                        emit(RunnerEvent.TurnStarted(sessionId, threadId = thread, turnId = turnId, turn = turn))
                    }
                    reports.forEach { emit(RunnerEvent.ContextUsage(sessionId, turnId, it, OPUS)) }
                    emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                }
            }

            orchestrator(tracker, runner, workflow(maxTurns = 3)).tick()

            assertEquals(2, runner.sessionsOpened)
            // The 25 percent rule holds per session, so the ledger's peak is the session's, never the attempt's.
            assertEquals(listOf("th1", "th2", "th2"), ledgerLines.map { it.threadId })
            val peaks = listOf(240_000L, 30_000L, 30_000L).map { LedgerPeak(it, 1_000_000L) }
            assertEquals(peaks, ledgerLines.map { it.peak })
            assertEquals(listOf("240000", "30000", "30000"), sink.ofKind("context_usage").map { it.data["peak_tokens"] })
            assertEquals(listOf("210000", "30000", "20000"), sink.ofKind("context_usage").map { it.data["latest_tokens"] })
        }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
    }
}
