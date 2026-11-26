package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The workspace hooks around attempts (spec 9.4, 17.2), with a fake hook runner. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorHooksTest {
    private val root: Path = createTempDirectory("loop-orchestrator-hooks").toRealPath()
    private val logLines = mutableListOf<String>()
    private val hookRunner = FakeHookRunner()
    private val sink = FakeSink()

    private val allHooks =
        HooksConfig(
            afterCreate = "sh after_create.sh",
            beforeRun = "sh before_run.sh",
            afterRun = "sh after_run.sh",
            beforeRemove = "sh before_remove.sh",
            timeoutMs = 30_000,
        )

    private fun workflow(hooks: HooksConfig): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config = WorkflowConfig(hooks = hooks),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner = FakeRunner(),
        hooks: HooksConfig = allHooks,
    ): Orchestrator {
        val workflow = workflow(hooks)
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), hooks, hookRunner),
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

    private fun hookEvents(): List<RecordedEvent> = sink.events.filter { it.kind == "hook_finished" }

    @Test
    fun `after_create runs only for a new workspace`() =
        runTest {
            val runner = FakeRunner()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-04"))), runner)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(2, runner.calls.size, "the continuation retry did not start a second attempt")
            assertEquals(1, hookRunner.hooks().count { it == HookName.AFTER_CREATE })
        }

    @Test
    fun `before_run runs before every attempt, ahead of the runner`() =
        runTest {
            val order = mutableListOf<String>()
            hookRunner.onRun = { order += it.id }
            val runner = FakeRunner().apply { onCall = { order += "runner" } }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-04"))), runner)

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(2, runner.calls.size, "the continuation retry did not start a second attempt")
            assertEquals(
                listOf("after_create", "before_run", "runner", "before_run", "runner"),
                order.filter { it != "after_run" },
            )
        }

    @Test
    fun `a failed before_run fails the attempt without running the agent and records the hook`() =
        runTest {
            hookRunner.exitCodes[HookName.BEFORE_RUN] = 3
            hookRunner.output[HookName.BEFORE_RUN] = "fetching\nfetch failed\n"
            val runner = FakeRunner()

            val result = orchestrator(FakeTracker(listOf(issue("m1-04"))), runner).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            assertEquals(emptyList(), runner.calls)
            val finished = sink.events.single { it.kind == "attempt_finished" }
            assertEquals("hook before_run failed with exit code 3", finished.data["reason"])
            val event = hookEvents().single { it.data["hook"] == "before_run" }
            assertEquals(
                mapOf(
                    "hook" to "before_run",
                    "command" to "sh before_run.sh",
                    "exit_code" to "3",
                    "timed_out" to "false",
                    "duration_ms" to "12",
                    "output" to "fetching\nfetch failed\n",
                ),
                event.data,
            )
            assertNull(event.phase)
            assertEquals(AttemptId("a1"), event.attemptId)
            assertEquals("id-m1-04", event.issueId)
        }

    @Test
    fun `a before_run timeout fails the attempt and records the output`() =
        runTest {
            hookRunner.timedOut += HookName.BEFORE_RUN
            hookRunner.output[HookName.BEFORE_RUN] = "waiting for the lock\n"

            val result = orchestrator(FakeTracker(listOf(issue("m1-04")))).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            val finished = sink.events.single { it.kind == "attempt_finished" }
            assertEquals("hook before_run timed out after 30000 ms", finished.data["reason"])
            val event = hookEvents().single { it.data["hook"] == "before_run" }
            assertEquals("true", event.data["timed_out"])
            assertEquals("waiting for the lock\n", event.data["output"])
            assertFalse("exit_code" in event.data, event.data.toString())
        }

    @Test
    fun `after_run runs after a successful attempt and its failure is ignored`() =
        runTest {
            val order = mutableListOf<String>()
            hookRunner.onRun = { order += it.id }
            hookRunner.exitCodes[HookName.AFTER_RUN] = 1
            val runner = FakeRunner().apply { onCall = { order += "runner" } }

            val result = orchestrator(FakeTracker(listOf(issue("m1-04"))), runner).tick()

            assertEquals(AttemptOutcome.Succeeded, result.attempts.single().outcome)
            assertEquals(listOf("after_create", "before_run", "runner", "after_run"), order)
            assertEquals("1", hookEvents().single { it.data["hook"] == "after_run" }.data["exit_code"])
            assertEquals("succeeded", sink.events.single { it.kind == "attempt_finished" }.data["outcome"])
        }

    @Test
    fun `after_run also runs after a failed turn and after a failed before_run`() =
        runTest {
            val failingTurn =
                FakeRunner { _, _ ->
                    flow { emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 2, reason = "exit_code=2")) }
                }
            orchestrator(FakeTracker(listOf(issue("m1-04"))), failingTurn).tick()
            hookRunner.exitCodes[HookName.BEFORE_RUN] = 1
            // A fresh orchestrator: the first one holds the issue in its retry queue (m1-07).
            orchestrator(FakeTracker(listOf(issue("m1-04"))), failingTurn).tick()

            assertEquals(
                listOf(HookName.AFTER_CREATE, HookName.BEFORE_RUN, HookName.AFTER_RUN, HookName.BEFORE_RUN, HookName.AFTER_RUN),
                hookRunner.hooks(),
            )
        }

    @Test
    fun `after_run runs after a cancelled attempt with a timeout of at most 5 s`() =
        runTest {
            val hanging =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        awaitCancellation()
                    }
                }
            val job = launch { orchestrator(FakeTracker(listOf(issue("m1-04"))), hanging).tick() }

            runCurrent()
            job.cancelAndJoin()

            val afterRun = hookRunner.calls.single { it.hook == HookName.AFTER_RUN }
            assertEquals(5_000, afterRun.timeoutMs)
            assertEquals(30_000, hookRunner.calls.single { it.hook == HookName.BEFORE_RUN }.timeoutMs)
            assertEquals("canceled", sink.events.single { it.kind == "attempt_finished" }.data["outcome"])
        }

    @Test
    fun `hooks and the agent run in the workspace with the issue variables`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-04"))), runner).tick()

            val workspace = root.resolve("workspaces/m1-04")
            assertEquals(
                workspace,
                runner.calls
                    .single()
                    .first.workspace,
            )
            assertEquals(listOf(workspace), hookRunner.calls.map { it.cwd }.distinct())
            for (call in hookRunner.calls) {
                assertEquals("id-m1-04", call.env["LOOP_ISSUE_ID"])
                assertEquals("m1-04", call.env["LOOP_ISSUE_IDENTIFIER"])
                assertEquals("m1-04", call.env["LOOP_WORKSPACE_KEY"])
                assertEquals(root.resolve("WORKFLOW.md").toString(), call.env["LOOP_WORKFLOW"])
            }
        }

    @Test
    fun `hook start, completion, failure and timeout are logged`() =
        runTest {
            hookRunner.exitCodes[HookName.BEFORE_RUN] = 3
            hookRunner.output[HookName.BEFORE_RUN] = "fetching\nfetch failed\n\n"
            hookRunner.timedOut += HookName.AFTER_RUN

            orchestrator(FakeTracker(listOf(issue("m1-04")))).tick()

            val ids = "run_id=r1 project_id=p1 issue_id=id-m1-04 issue_identifier=m1-04 attempt_id=a1"
            assertLogged("level=info event=hook outcome=started $ids hook=before_run")
            assertLogged("level=info event=hook outcome=completed $ids hook=after_create duration_ms=12")
            assertLogged("level=error event=hook outcome=failed $ids hook=before_run exit_code=3 duration_ms=12 reason=\"fetch failed\"")
            assertLogged(
                "level=warn event=hook outcome=failed $ids hook=after_run timed_out=true duration_ms=12 " +
                    "reason=\"timed out after 30000 ms\"",
            )
        }

    @Test
    fun `a before_run stopped by a cancel is logged and after_run still runs`() =
        runTest {
            hookRunner.onRun = { if (it == HookName.BEFORE_RUN) awaitCancellation() }
            val job = launch { orchestrator(FakeTracker(listOf(issue("m1-04")))).tick() }

            runCurrent()
            job.cancelAndJoin()

            val ids = "run_id=r1 project_id=p1 issue_id=id-m1-04 issue_identifier=m1-04 attempt_id=a1"
            assertLogged("level=warn event=hook outcome=failed $ids hook=before_run reason=\"hook before_run was stopped\"")
            assertEquals(5_000, hookRunner.calls.single { it.hook == HookName.AFTER_RUN }.timeoutMs)
        }

    @Test
    fun `bl-04 a before_run stopped by a cancel is recorded as hook_finished with stopped and no exit code`() =
        runTest {
            hookRunner.onRun = { if (it == HookName.BEFORE_RUN) awaitCancellation() }
            hookRunner.output[HookName.BEFORE_RUN] = "fetching\n"
            val job = launch { orchestrator(FakeTracker(listOf(issue("m1-04")))).tick() }

            runCurrent()
            job.cancelAndJoin()

            val event = hookEvents().single { it.data["hook"] == "before_run" }
            assertEquals(
                mapOf(
                    "hook" to "before_run",
                    "command" to "sh before_run.sh",
                    "timed_out" to "false",
                    "stopped" to "true",
                    "duration_ms" to "12",
                    "output" to "fetching\n",
                ),
                event.data,
            )
            assertEquals(AttemptId("a1"), event.attemptId)
            assertFalse("stopped" in hookEvents().single { it.data["hook"] == "after_create" }.data, "a hook that ended has no stopped key")
        }

    @Test
    fun `bl-04 the PreparingWorkspace phase is recorded before after_create and before_run`() =
        runTest {
            orchestrator(FakeTracker(listOf(issue("m1-04")))).tick()

            val order =
                sink.events
                    .filter { it.kind == "hook_finished" || (it.kind == "phase" && it.phase == "PreparingWorkspace") }
                    .map { it.data["hook"] ?: it.phase }
            assertEquals(listOf("PreparingWorkspace", "after_create", "before_run", "after_run"), order)
            val phase = sink.events.single { it.phase == "PreparingWorkspace" }
            assertEquals(root.resolve("workspaces/m1-04").toString(), phase.data["workspace"])
        }

    @Test
    fun `removeWorkspace runs before_remove and removes the workspace`() =
        runTest {
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-04"))))
            orchestrator.tick()
            val workspace = root.resolve("workspaces/m1-04")
            assertTrue(workspace.exists())

            orchestrator.removeWorkspace(issue("m1-04", state = "done")).getOrThrow()

            assertFalse(workspace.exists())
            val call = hookRunner.calls.single { it.hook == HookName.BEFORE_REMOVE }
            assertEquals(workspace, call.cwd)
            assertEquals("id-m1-04", call.env["LOOP_ISSUE_ID"])
            assertEquals("m1-04", call.env["LOOP_ISSUE_IDENTIFIER"])
            assertEquals("m1-04", call.env["LOOP_WORKSPACE_KEY"])
            assertEquals(root.resolve("WORKFLOW.md").toString(), call.env["LOOP_WORKFLOW"])
            assertFalse("LOOP_ATTEMPT_ID" in call.env)
            val event = hookEvents().single { it.data["hook"] == "before_remove" }
            assertEquals("id-m1-04", event.issueId)
            assertNull(event.attemptId)
            assertLogged(
                "level=info event=hook outcome=started run_id=r1 project_id=p1 issue_id=id-m1-04 issue_identifier=m1-04 hook=before_remove",
            )
        }

    @Test
    fun `removeWorkspace ignores a before_remove failure and still removes the workspace`() =
        runTest {
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-04"))))
            orchestrator.tick()
            hookRunner.exitCodes[HookName.BEFORE_REMOVE] = 128
            hookRunner.output[HookName.BEFORE_REMOVE] = "fatal: not a working tree\n"

            val result = orchestrator.removeWorkspace(issue("m1-04", state = "done"))

            assertTrue(result.isSuccess, result.toString())
            assertFalse(root.resolve("workspaces/m1-04").exists())
            assertEquals("128", hookEvents().single { it.data["hook"] == "before_remove" }.data["exit_code"])
            assertTrue(
                logLines.any { "level=warn event=hook outcome=failed" in it && "hook=before_remove exit_code=128" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a truncated hook output is marked on hook_finished, a whole one is not`() =
        runTest {
            hookRunner.truncated += HookName.BEFORE_RUN

            orchestrator(FakeTracker(listOf(issue("m1-04")))).tick()

            val byHook = hookEvents().associateBy { it.data.getValue("hook") }
            assertEquals("true", byHook.getValue("before_run").data["output_truncated"])
            assertNull(byHook.getValue("after_create").data["output_truncated"])
        }

    private fun assertLogged(expected: String) {
        assertTrue(logLines.any { it.substringAfter(" ") == expected }, "no line\n$expected\nin\n${logLines.joinToString("\n")}")
    }
}
