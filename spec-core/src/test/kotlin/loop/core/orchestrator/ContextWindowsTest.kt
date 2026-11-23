package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import loop.core.workflow.ContextConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.RunnerSettings
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-02, stories 101 and 102 (bl-125, bl-138 window part): the window `context.windows` gives a session's model, and a
 * model with no window recorded as `context_window_unknown` once per attempt, with the model and the runner. The
 * pinned Codex model's window ships in the defaults, so a Codex session gets a fraction and rotates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextWindowsTest {
    private val root: Path = createTempDirectory("loop-context-windows").toRealPath()
    private val sink = FakeSink()
    private val logLines = mutableListOf<String>()

    // The window logic alone.

    @Test
    fun `the window of a model is its context windows entry, the shipped Codex one included`() {
        val windows = ContextWindows(ContextConfig().windows, runner = "codex-app-server", records = true)

        assertEquals(475_000L, windows.windowOf(CODEX))
        assertEquals(1_000_000L, windows.windowOf(OPUS))
        assertNull(windows.windowOf(UNKNOWN))
        assertNull(windows.windowOf(null))
    }

    @Test
    fun `a model with a window is never unknown`() {
        val windows = ContextWindows(ContextConfig().windows, runner = "codex-app-server", records = true)

        assertNull(windows.firstUnknown(CODEX))
        assertNull(windows.firstUnknown(OPUS))
    }

    @Test
    fun `the first report of a model with no window is unknown once, with the model and the runner`() {
        val windows = ContextWindows(ContextConfig().windows, runner = "codex-app-server", records = true)

        val first = windows.firstUnknown(UNKNOWN)

        assertEquals(mapOf("model" to UNKNOWN, "runner" to "codex-app-server"), first?.data)
        assertEquals(true, first?.recorded)
        assertNull(windows.firstUnknown(UNKNOWN))
        // Another model with no window, as a reroute gives, is its own first report.
        assertEquals(mapOf("model" to "openai/gpt-other", "runner" to "codex-app-server"), windows.firstUnknown("openai/gpt-other")?.data)
    }

    @Test
    fun `a runner that names no model is unknown once, with the runner only`() {
        val windows = ContextWindows(ContextConfig().windows, runner = "command", records = true)

        val first = windows.firstUnknown(null)

        assertEquals(mapOf("runner" to "command"), first?.data)
        assertTrue("the runner named no model" in first?.reason.orEmpty(), first?.reason)
        assertNull(windows.firstUnknown(null))
    }

    @Test
    fun `the reason names the model and the key to add`() {
        val reason = ContextWindows(ContextConfig().windows, runner = "command", records = true).firstUnknown(UNKNOWN)?.reason.orEmpty()

        assertTrue("no window for $UNKNOWN in context.windows" in reason, reason)
        assertTrue("Add $UNKNOWN under context.windows" in reason, reason)
    }

    @Test
    fun `outside Loop mode or without occupancy an unknown window is logged, not recorded`() {
        val windows = ContextWindows(ContextConfig().windows, runner = "command", records = false)

        assertEquals(false, windows.firstUnknown(UNKNOWN)?.recorded)
    }

    // The orchestrator, with the fake runner.

    private fun workflow(
        loopMode: Boolean = true,
        maxTurns: Int = 1,
        context: ContextConfig = ContextConfig(),
        runner: RunnerSettings = commandRunner("./agent.sh"),
    ): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = runner,
            pollingIntervalMs = 1000,
            promptTemplate = "Do {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns),
                    context = context,
                    kinds = if (loopMode) KindsConfig(KindConfig(emptyList())) else null,
                    retries = RetriesConfig(maxAttempts = 5),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        workflow: WorkflowFile,
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
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    /**
     * A runner declaring [occupancy] whose sessions are numbered from 1 in the order they open: session `s`, turn `t`
     * of it, reports the occupancies `reports(s, t)` with [model], runs [onTurn], then fails the turn when [fail] says so
     * or completes it.
     */
    private fun runner(
        model: String?,
        occupancy: Boolean = true,
        fail: Boolean = false,
        onTurn: suspend (Int, Int) -> Unit = { _, _ -> },
        reports: (Int, Int) -> List<Long>,
    ): FakeRunner =
        FakeRunner().also { runner ->
            runner.occupancy = occupancy
            runner.sessionScript = { turn, _ -> turn(runner.sessionsOpened, turn, model, fail, onTurn, reports) }
        }

    private fun turn(
        session: Int,
        turn: Int,
        model: String?,
        fail: Boolean,
        onTurn: suspend (Int, Int) -> Unit,
        reports: (Int, Int) -> List<Long>,
    ): Flow<RunnerEvent> =
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
            onTurn(session, turn)
            if (fail) {
                emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = 1, reason = "boom"))
            } else {
                emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
            }
        }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    private fun warnings(): List<String> = logLines.filter { "level=warn" in it && "event=context_window" in it }

    @Test
    fun `m4-02 a model with no window records context_window_unknown once per attempt, with the model and the runner`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            // Each attempt reports twice in its turn, and fails, so a second attempt follows.
            val runner = runner(model = UNKNOWN, fail = true) { _, _ -> listOf(10_000L, 20_000L) }
            val orchestrator = orchestrator(tracker, runner, workflow())

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(2, sink.ofKind("attempt_started").size)
            val unknown = sink.ofKind("context_window_unknown")
            assertEquals(listOf("a1", "a2"), unknown.map { it.attemptId?.value })
            assertEquals(List(2) { mapOf("model" to UNKNOWN, "runner" to "command") }, unknown.map { it.data })
            assertEquals(listOf("th1-s1t1", "th2-s2t1"), unknown.map { it.sessionId?.value })
            // The operator log warns once per attempt too, not once per process.
            assertEquals(2, warnings().size, logLines.joinToString("\n"))
            assertTrue(warnings().all { "model=$UNKNOWN" in it }, warnings().joinToString("\n"))
            // The occupancy is still recorded, in tokens, without a fraction.
            assertTrue(sink.ofKind("context_usage").all { "fraction" !in it.data && it.data["latest_tokens"] == "20000" })
        }

    @Test
    fun `m4-02 context_window_unknown comes before the turn's end, at the first report`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            val runner = runner(model = UNKNOWN) { _, _ -> listOf(10_000L) }

            orchestrator(tracker, runner, workflow()).tick()

            val kinds = sink.events.map { it.kind }.filter { it in setOf("turn_started", "context_window_unknown", "turn_completed") }
            assertEquals(listOf("turn_started", "context_window_unknown", "turn_completed"), kinds)
        }

    @Test
    fun `m4-02 a runner that names no model records context_window_unknown without a model`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            val runner = runner(model = null) { _, _ -> listOf(10_000L) }

            orchestrator(tracker, runner, workflow()).tick()

            assertEquals(listOf(mapOf("runner" to "command")), sink.ofKind("context_window_unknown").map { it.data })
        }

    @Test
    fun `m4-02 a model with a window records no context_window_unknown`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            val runner = runner(model = OPUS) { _, _ -> listOf(10_000L) }

            orchestrator(tracker, runner, workflow()).tick()

            assertTrue(sink.ofKind("context_window_unknown").isEmpty())
            assertTrue(warnings().isEmpty(), logLines.joinToString("\n"))
        }

    @Test
    fun `m4-02 outside Loop mode an unknown window is only logged`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            val runner = runner(model = UNKNOWN) { _, _ -> listOf(10_000L) }

            orchestrator(tracker, runner, workflow(loopMode = false)).tick()

            assertTrue(sink.ofKind("context_window_unknown").isEmpty())
            assertEquals(1, warnings().size, logLines.joinToString("\n"))
        }

    @Test
    fun `m4-02 a runner that declares no occupancy records no context_window_unknown`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            val runner = runner(model = UNKNOWN, occupancy = false) { _, _ -> listOf(10_000L) }

            orchestrator(tracker, runner, workflow()).tick()

            assertTrue(sink.ofKind("context_window_unknown").isEmpty())
        }

    @Test
    fun `m4-02 a Codex session on the pinned model with no context windows gets a fraction and rotates at the soft limit`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            // 96000 of 475000 is 0.2021: at or above the soft limit of 0.20, below the hard limit of 0.21.
            val runner =
                runner(model = CODEX, onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m4-02", "review") }) { session, _ ->
                    if (session == 1) listOf(40_000L, 96_000L) else listOf(30_000L)
                }

            orchestrator(tracker, runner, workflow(loopMode = false, maxTurns = 5, runner = RunnerSettings("codex-app-server"))).tick()

            val usage = sink.ofKind("context_usage").first().data
            assertEquals("475000", usage["window"])
            assertEquals("0.2021", usage["fraction"])
            val rotated = sink.ofKind("session_rotated").single().data
            assertEquals("soft_limit", rotated["reason"])
            assertEquals("96000", rotated["occupancy_tokens"])
            assertEquals("475000", rotated["window"])
            assertEquals("0.2021", rotated["fraction"])
            assertEquals(2, runner.sessionsOpened)
            assertTrue(runner.interrupts.isEmpty(), "${runner.interrupts}")
            assertTrue(sink.ofKind("context_window_unknown").isEmpty())
            assertFalse(logLines.any { "event=context_window" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `m4-02 a Codex session at the default hard limit of 0_21 is interrupted`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m4-02")))
            // 99750 of 475000 is exactly 0.21.
            val runner =
                runner(model = CODEX, onTurn = { session, _ -> if (session == 2) tracker.moveIssue("id-m4-02", "review") }) { session, _ ->
                    if (session == 1) listOf(99_750L) else listOf(30_000L)
                }

            orchestrator(tracker, runner, workflow(loopMode = false, maxTurns = 5, runner = RunnerSettings("codex-app-server"))).tick()

            assertEquals("hard_limit", sink.ofKind("session_rotated").single().data["reason"])
            assertEquals(listOf(1), runner.interrupts)
        }

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
        const val CODEX = "openai/gpt-6.1-sol"
        const val UNKNOWN = "openai/gpt-unknown-9"
    }
}
