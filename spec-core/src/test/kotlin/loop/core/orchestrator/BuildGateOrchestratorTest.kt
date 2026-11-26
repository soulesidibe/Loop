package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptContext
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workflow.workflowHash
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bl-188: the attempts' verification builds pass the build gate, so two attempts that hold agent slots never build at
 * once beyond `agent.max_concurrent_verifications`, and a reload that lowers the cap holds the builds not started yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildGateOrchestratorTest {
    private val root: Path = createTempDirectory("loop-build-gate").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxConcurrentAgents: Int,
        maxConcurrentVerifications: Int? = null,
    ): WorkflowFile {
        // The text only gives each config its own hash, so a changed one reloads.
        val text = "agents $maxConcurrentAgents, verifications $maxConcurrentVerifications"
        return WorkflowFile(
            config =
                WorkflowConfig(
                    agent =
                        AgentConfig(
                            maxConcurrentAgents = maxConcurrentAgents,
                            maxConcurrentVerifications = maxConcurrentVerifications,
                            maxTurns = 1,
                        ),
                    kinds =
                        KindsConfig(
                            KindConfig(listOf(CheckConfig.Command("./gradlew build")), delivery = DeliveryConfig(DeliveryPolicy.Auto)),
                        ),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = POLL_MS,
            promptTemplate = "Work on {{ issue.identifier }}",
            sourceHash = workflowHash(text),
            sourceText = text,
        )
    }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        checks: HeldBuilds,
        workflow: () -> WorkflowFile,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow(),
            tracker = tracker,
            runner = runner,
            sink = FakeSink(),
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow().workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            preflight = PreflightCheck { PreflightResult.Ok(workflow()) },
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
        )
    }

    /** A runner whose one turn queues `loop tool move review`, once [ready] lets the issue's turn go on. */
    private fun submitting(ready: (AttemptContext) -> CompletableDeferred<Unit>? = { null }): FakeRunner =
        FakeRunner { ctx, _ ->
            flow {
                ready(ctx)?.await()
                ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                emitAll(succeedingTurn())
            }
        }

    private fun HeldBuilds.issues(): List<String> = started.map { it.fileName.toString() }

    @Test
    fun `with two agent slots and one verification slot two attempts that submit at once never build together`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("bl-1"), issue("bl-2")))
            val runner = submitting()
            val checks = HeldBuilds()
            val job = launch { orchestrator(tracker, runner, checks) { workflow(2, maxConcurrentVerifications = 1) }.runUntilCancelled() }
            runCurrent()

            assertEquals(2, runner.calls.size, "both attempts hold an agent slot")
            assertEquals(listOf("bl-1"), checks.issues())
            assertTrue(logLines.any { "event=build_gate" in it && "issue_identifier=bl-2" in it && "1 ahead" in it }, logLines.joinToString("\n"))

            checks.releaseNext()
            runCurrent()
            assertEquals(listOf("bl-1", "bl-2"), checks.issues())

            checks.releaseNext()
            runCurrent()
            assertEquals(1, checks.most)
            assertEquals(setOf("id-bl-1" to "review", "id-bl-2" to "review"), tracker.moves.toSet())
            job.cancelAndJoin()
        }

    @Test
    fun `a reload that lowers the cap during one build holds a build submitted afterwards until the first ends`() =
        runTest {
            var current = workflow(2)
            val tracker = FakeTracker(listOf(issue("bl-1"), issue("bl-2")))
            val second = CompletableDeferred<Unit>()
            val runner = submitting { ctx -> second.takeIf { ctx.issue.identifier == "bl-2" } }
            val checks = HeldBuilds()
            val job = launch { orchestrator(tracker, runner, checks) { current }.runUntilCancelled() }
            runCurrent()
            assertEquals(2, runner.calls.size, "both attempts launched under the cap of 2")
            assertEquals(listOf("bl-1"), checks.issues())

            current = workflow(1)
            advanceTimeBy(POLL_MS)
            runCurrent()
            second.complete(Unit)
            runCurrent()
            assertEquals(listOf("bl-1"), checks.issues(), "the lowered cap holds the second build")

            checks.releaseNext()
            runCurrent()
            assertEquals(listOf("bl-1", "bl-2"), checks.issues())
            checks.releaseNext()
            runCurrent()
            assertEquals(1, checks.most)
            assertEquals(setOf("id-bl-1" to "review", "id-bl-2" to "review"), tracker.moves.toSet())
            job.cancelAndJoin()
        }

    private companion object {
        const val POLL_MS = 1000L
        val PROJECT = ProjectId("p1")
    }
}
