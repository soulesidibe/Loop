package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowSource
import loop.core.workflow.command
import loop.core.workflow.commandRunner
import loop.core.workflow.workflowHash
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Spec 6.2 and m1-15: a changed `WORKFLOW.md` becomes the effective config on the next Ok preflight. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorReloadTest {
    private val root: Path = createTempDirectory("loop-reload").toRealPath()
    private val logLines = mutableListOf<String>()

    /** A workflow as the loader would give it for this text: its hash, text and the typed values asked. */
    private fun workflow(
        maxTurns: Int = 5,
        active: List<String> = listOf("todo"),
        workspaces: String = "workspaces",
        command: String = "./agent.sh",
        extra: String = "",
    ): WorkflowFile {
        val text =
            "---\n" +
                "tracker:\n  kind: files\n  active_states: [${active.joinToString(", ")}]\n" +
                "workspace:\n  root: ./$workspaces\n" +
                "runner:\n  kind: command\nrunners:\n  command:\n    command: $command\n" +
                "agent:\n  max_turns: $maxTurns\n" +
                extra +
                "---\nWork on {{ issue.identifier }}\n"
        return WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = active,
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve(workspaces),
            runner = commandRunner(command),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxTurns = maxTurns)),
            sourceHash = workflowHash(text),
            sourceText = text,
        )
    }

    private fun invalid(text: String): PreflightResult.Failed =
        PreflightResult.Failed(
            "invalid_config",
            "agent.max_turns must be a positive integer, got 0",
            WorkflowSource(root.resolve("WORKFLOW.md"), text),
        )

    private fun orchestrator(
        tracker: FakeTracker,
        sink: FakeSink,
        workflow: WorkflowFile,
        preflight: PreflightCheck,
        runner: FakeRunner = FakeRunner(),
        clock: Clock = FixedClock(),
        runnerFor: ((WorkflowFile) -> FakeRunner)? = null,
        workspacesFor: ((WorkflowFile) -> WorkspaceManager)? = null,
        reloadWarnings: (WorkflowFile, WorkflowFile) -> List<String> = { _, _ -> emptyList() },
        redactor: SecretRedactor = SecretRedactor(),
    ): Orchestrator {
        var next = 0
        val workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner())
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = workspaces,
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            preflight = preflight,
            parked = FakeParkedIssues(),
            redactor = redactor,
            secretsOf = { emptyMap() },
            runnerFor = runnerFor ?: { runner },
            workspacesFor = workspacesFor ?: { workspaces },
            reloadWarnings = reloadWarnings,
        )
    }

    private fun List<RecordedEvent>.started(): List<RecordedEvent> = filter { it.kind == "attempt_started" }

    private fun List<RecordedEvent>.turnLimits(): List<String?> = filter { it.kind == "turn_completed" }.map { it.data["max_turns"] }

    @Test
    fun `a changed file becomes the effective config and the reload is recorded with its hash and diff`() =
        runTest {
            val a = workflow(maxTurns = 5)
            val b = workflow(maxTurns = 7)
            val results = ArrayDeque(listOf(PreflightResult.Ok(a), PreflightResult.Ok(b)))
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, sink, a, PreflightCheck { results.removeFirst() })

            orchestrator.tick()
            tracker.issues.clear()
            tracker.issues += issue("m1-02")
            orchestrator.tick()

            assertEquals(listOf("5", "7"), sink.events.turnLimits())
            assertEquals(listOf(a.sourceHash, b.sourceHash), sink.events.started().map { it.data["config_version"] })
            val reloaded = sink.events.single { it.kind == "config_reloaded" }
            assertNull(reloaded.issueId)
            assertEquals(RunId("r1"), reloaded.runId)
            assertEquals(b.sourceHash, reloaded.data["config_version"])
            assertEquals(a.sourceHash, reloaded.data["previous_version"])
            val diff = checkNotNull(reloaded.data["diff"])
            assertTrue("-  max_turns: 5\n+  max_turns: 7\n" in diff, diff)
            assertTrue(diff.startsWith("--- WORKFLOW.md ${a.sourceHash.take(8)}\n+++ WORKFLOW.md ${b.sourceHash.take(8)}\n"), diff)
            assertNull(reloaded.data["diff_truncated"])
            // Recorded before the second attempt starts, so the trace order reads right.
            assertTrue(sink.events.indexOf(reloaded) < sink.events.indexOf(sink.events.started()[1]))
            assertTrue(
                logLines.any {
                    "level=info event=workflow_reload outcome=completed" in it &&
                        "config_version=${b.sourceHash.take(8)}" in it &&
                        "previous_version=${a.sourceHash.take(8)}" in it
                },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `an unchanged file records no reload`() =
        runTest {
            val a = workflow()
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(emptyList()), sink, a, PreflightCheck { PreflightResult.Ok(a.copy()) })

            orchestrator.tick()
            orchestrator.tick()

            assertTrue(sink.events.none { it.kind.startsWith("config_") }, sink.events.toString())
            assertTrue(logLines.none { "workflow_reload" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `an invalid edit keeps the last good config and its error is recorded once`() =
        runTest {
            val a = workflow(maxTurns = 5)
            val bad = a.sourceText.replace("max_turns: 5", "max_turns: 0")
            val results = ArrayDeque(listOf(invalid(bad), invalid(bad), PreflightResult.Ok(a)))
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, sink, a, PreflightCheck { results.removeFirst() }, runner)

            orchestrator.tick()
            orchestrator.tick()

            assertTrue(runner.calls.isEmpty(), "no dispatch while the file is invalid")
            val rejected = sink.events.single { it.kind == "config_reload_failed" }
            assertEquals(workflowHash(bad), rejected.data["config_version"])
            assertEquals(a.sourceHash, rejected.data["active_version"])
            assertEquals("invalid_config", rejected.data["error"])
            assertEquals("agent.max_turns must be a positive integer, got 0", rejected.data["message"])
            assertTrue("-  max_turns: 5\n+  max_turns: 0\n" in checkNotNull(rejected.data["diff"]))
            assertEquals(2, sink.events.count { it.kind == "preflight" && it.data["result"] == "failed" })
            assertEquals(
                1,
                logLines.count { "level=error event=workflow_reload outcome=failed" in it && "error=invalid_config" in it },
                logLines.joinToString("\n"),
            )

            // The file is back to the last good text: nothing to reload, the next attempt runs with it.
            orchestrator.tick()

            assertTrue(sink.events.none { it.kind == "config_reloaded" })
            assertEquals(listOf(a.sourceHash), sink.events.started().map { it.data["config_version"] })
            assertEquals(listOf("5"), sink.events.turnLimits())
        }

    @Test
    fun `a fix after an invalid edit is a reload from the last good config`() =
        runTest {
            val a = workflow(maxTurns = 5)
            val c = workflow(maxTurns = 7)
            val bad = a.sourceText.replace("max_turns: 5", "max_turns: 0")
            val results = ArrayDeque(listOf(invalid(bad), PreflightResult.Ok(c)))
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-01"))), sink, a, PreflightCheck { results.removeFirst() })

            orchestrator.tick()
            orchestrator.tick()

            val reloaded = sink.events.single { it.kind == "config_reloaded" }
            assertEquals(a.sourceHash, reloaded.data["previous_version"])
            assertEquals(c.sourceHash, reloaded.data["config_version"])
            assertEquals(listOf("7"), sink.events.turnLimits())
        }

    @Test
    fun `a missing file is recorded once per stretch without a version`() =
        runTest {
            val a = workflow()
            val missing = PreflightResult.Failed("missing_workflow_file", "no workflow file at ${a.path}")
            val results = ArrayDeque(listOf(missing, missing, PreflightResult.Ok(a), missing))
            val sink = FakeSink()
            val orchestrator = orchestrator(FakeTracker(emptyList()), sink, a, PreflightCheck { results.removeFirst() })

            repeat(4) { orchestrator.tick() }

            val rejected = sink.events.filter { it.kind == "config_reload_failed" }
            assertEquals(2, rejected.size)
            rejected.forEach { event ->
                assertEquals("missing_workflow_file", event.data["error"])
                assertEquals(a.sourceHash, event.data["active_version"])
                assertNull(event.data["config_version"])
                assertNull(event.data["diff"])
            }
        }

    @Test
    fun `a running attempt keeps the config it started with`() =
        runTest {
            val clock = SchedulerClock(testScheduler)
            val a = workflow(maxTurns = 2)
            // The reloaded file makes todo inactive and allows one turn: neither reaches the running attempt.
            val b = workflow(maxTurns = 1, active = listOf("in_progress"))
            var current: PreflightResult = PreflightResult.Ok(a)
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val runner = FakeRunner()
            val turnOne = CompletableDeferred<Unit>()
            runner.sessionScript = { turn, _ ->
                flow {
                    if (turn == 1) turnOne.await()
                    sessionTurn(turn).collect { emit(it) }
                }
            }
            val orchestrator = orchestrator(tracker, sink, a, PreflightCheck { current }, runner, clock)

            val run = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            current = PreflightResult.Ok(b)
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, sink.events.count { it.kind == "config_reloaded" })
            turnOne.complete(Unit)
            runCurrent()
            run.cancelAndJoin()

            assertEquals(listOf(1, 2), runner.turnInputs.map { it.first })
            assertEquals(listOf("2", "2"), sink.events.turnLimits())
            assertEquals(listOf(a.sourceHash), sink.events.started().map { it.data["config_version"] })
        }

    @Test
    fun `the next attempt gets the runner and workspaces of the reloaded config`() =
        runTest {
            val a = workflow(command = "./a.sh", workspaces = "ws-a")
            val b = workflow(command = "./b.sh", workspaces = "ws-b")
            val results = ArrayDeque(listOf(PreflightResult.Ok(a), PreflightResult.Ok(b)))
            val runners = mutableMapOf<String, FakeRunner>()
            val tracker = FakeTracker(listOf(issue("m1-01")))
            val sink = FakeSink()
            val orchestrator =
                orchestrator(
                    tracker,
                    sink,
                    a,
                    PreflightCheck { results.removeFirst() },
                    runnerFor = { wf -> runners.getOrPut(wf.runner.command) { FakeRunner() } },
                    workspacesFor = { wf -> WorkspaceManager(Workspaces(wf.workspaceRoot), HooksConfig(), FakeHookRunner()) },
                )

            orchestrator.tick()
            tracker.issues.clear()
            tracker.issues += issue("m1-02")
            orchestrator.tick()

            assertEquals(listOf("m1-01"), runners.getValue("./a.sh").calls.map { it.first.issue.identifier })
            assertEquals(listOf("m1-02"), runners.getValue("./b.sh").calls.map { it.first.issue.identifier })
            val prepared = sink.events.filter { it.phase == AttemptPhase.PreparingWorkspace.name }.map { it.data["workspace"] }
            assertEquals(listOf(root.resolve("ws-a/m1-01").toString(), root.resolve("ws-b/m1-02").toString()), prepared)
        }

    @Test
    fun `reload warnings are logged`() =
        runTest {
            val a = workflow()
            val b = workflow(maxTurns = 9)
            val results = ArrayDeque(listOf(PreflightResult.Ok(b)))
            val orchestrator =
                orchestrator(
                    FakeTracker(emptyList()),
                    FakeSink(),
                    a,
                    PreflightCheck { results.removeFirst() },
                    reloadWarnings = { old, new -> listOf("from ${old.config.agent.maxTurns} to ${new.config.agent.maxTurns}") },
                )

            orchestrator.tick()

            assertTrue(
                logLines.any { "level=warn event=workflow_reload" in it && "reason=\"from 5 to 9\"" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a large diff is redacted then cut`() =
        runTest {
            val secret = "s3cr3t-value-1234"
            val a = workflow()
            val b = workflow(extra = "x:\n  token: $secret\n" + (1..3000).joinToString("") { "  k$it: v$it\n" })
            val results = ArrayDeque(listOf(PreflightResult.Ok(b)))
            val redactor = SecretRedactor().apply { learn(mapOf("TOKEN" to secret)) }
            val sink = FakeSink()
            val orchestrator =
                orchestrator(FakeTracker(emptyList()), sink, a, PreflightCheck { results.removeFirst() }, redactor = redactor)

            orchestrator.tick()

            val reloaded = sink.events.single { it.kind == "config_reloaded" }
            val diff = checkNotNull(reloaded.data["diff"])
            assertTrue(secret !in diff)
            assertTrue(diff.length <= 16_000, "${diff.length}")
            assertEquals("true", reloaded.data["diff_truncated"])
        }
}
