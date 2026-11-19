package loop.core.orchestrator

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan seam 6 (m1-11): the orchestrator redacts what it writes before any cut, so no
 * fragment of a secret reaches the sink, even one that does not redact on its own.
 */
class OrchestratorRedactionTest {
    private val root: Path = createTempDirectory("loop-orchestrator-redaction").toRealPath()
    private val logLines = mutableListOf<String>()
    private val sink = FakeSink()
    private val hookRunner = FakeHookRunner()
    private val secret = "s3cr3tvalue0123456789"

    private fun workflow(hooks: HooksConfig = HooksConfig()): WorkflowFile =
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

    private fun orchestrator(
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
        preflight: PreflightCheck = PreflightCheck { PreflightResult.Ok(workflow) },
        secretsOf: (WorkflowFile) -> Map<String, String> = { mapOf("LOOP_TRACKER_TOKEN" to secret) },
    ): Orchestrator {
        val redactor = SecretRedactor()
        var next = 0
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-11"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = redactor),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            preflight = preflight,
            parked = FakeParkedIssues(),
            redactor = redactor,
            secretsOf = secretsOf,
        )
    }

    private fun RecordedEvent.text(): String = listOfNotNull(issueIdentifier, phase).joinToString(" ") + " " + data.values.joinToString(" ")

    private fun assertNoTrace(value: String = secret) {
        val fragment = value.take(8)
        sink.events.forEach { assertFalse(it.text().contains(fragment), "${it.kind} holds a fragment: ${it.text()}") }
        logLines.forEach { assertFalse(it.contains(fragment), "log line holds a fragment: $it") }
    }

    @Test
    fun `a runner line with a secret across the 2000 character cut leaves no fragment`() =
        runTest {
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "x".repeat(1990) + secret))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            orchestrator(runner).tick()

            val line =
                sink.events
                    .single { it.kind == "runner_output" }
                    .data
                    .getValue("line")
            assertTrue(line.startsWith("x".repeat(1990) + "[redac"), line.takeLast(20))
            assertNoTrace()
        }

    @Test
    fun `a failing turn reason and an input required reason leave no fragment`() =
        runTest {
            val failing =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "auth failed for $secret"))
                    }
                }
            orchestrator(failing).tick()
            val asking =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnInputRequired(SESSION, TURN, reason = "paste $secret here"))
                    }
                }

            val result = orchestrator(asking).tick()

            assertTrue(sink.events.any { it.data["reason"] == "auth failed for [redacted:LOOP_TRACKER_TOKEN]" })
            assertFalse(
                result.attempts
                    .single()
                    .reason
                    .orEmpty()
                    .contains(secret.take(8)),
            )
            assertNoTrace()
        }

    @Test
    fun `a hook command across the 200 character cut and its output leave no fragment`() =
        runTest {
            // The secret starts at offset 180, so 20 of its characters would survive a plain cut to 200.
            val command = "echo " + "x".repeat(175) + secret
            hookRunner.exitCodes[HookName.AFTER_CREATE] = 1
            hookRunner.output[HookName.AFTER_CREATE] = "cloning\nfailed with $secret\n"

            orchestrator(FakeRunner(), workflow(HooksConfig(afterCreate = command))).tick()

            val hook = sink.events.single { it.kind == "hook_finished" }
            assertTrue(hook.data.getValue("command").startsWith("echo " + "x".repeat(175) + "[redacted:"), hook.data.toString())
            assertTrue(hook.data.getValue("output").contains("failed with [redacted:LOOP_TRACKER_TOKEN]"), hook.data.toString())
            assertNoTrace()
        }

    @Test
    fun `a failed preflight message leaves no fragment`() =
        runTest {
            val workflow = workflow()
            var ticks = 0
            val preflight =
                PreflightCheck {
                    if (++ticks == 1) PreflightResult.Ok(workflow) else PreflightResult.Failed("invalid_config", "bad token $secret")
                }
            val orchestrator = orchestrator(FakeRunner(), workflow, preflight)

            orchestrator.tick()
            orchestrator.tick()

            val failed = sink.events.last { it.kind == "preflight" }
            assertTrue(failed.data["message"] == "bad token [redacted:LOOP_TRACKER_TOKEN]", failed.data.toString())
            assertNoTrace()
        }

    @Test
    fun `an ok preflight learns the secrets of the workflow it read`() =
        runTest {
            val newSecret = "n3wcr3dential9876543210"
            val started = workflow()
            val reread = started.copy(promptTemplate = "Reread {{ issue.identifier }}")
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stderr, "using $newSecret"))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val secretsOf = { workflow: WorkflowFile ->
                if (workflow == reread) mapOf("LOOP_NEW_TOKEN" to newSecret) else emptyMap()
            }

            orchestrator(runner, started, PreflightCheck { PreflightResult.Ok(reread) }, secretsOf).tick()

            val line =
                sink.events
                    .single { it.kind == "runner_output" }
                    .data
                    .getValue("line")
            assertTrue(line == "using [redacted:LOOP_NEW_TOKEN]", line)
            assertNoTrace(newSecret)
        }
}
