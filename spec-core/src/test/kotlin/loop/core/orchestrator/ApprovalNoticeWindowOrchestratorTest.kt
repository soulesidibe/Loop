package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.events.HumanNotified
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-257: a `loop send-back` or a `loop approve` committed while Loop sends the approval notice, with a poll run in that
 * window, is never deleted when the verification that started the approval wait ends. The notice is held on its
 * `human_notified` write, the human command and the poll run while it is held, then it is released. Never by timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalNoticeWindowOrchestratorTest {
    private val root: Path = createTempDirectory("loop-notice-window").toRealPath()
    private val key = PROJECT.value to "id-t-1"

    private val template =
        "Work on {{ issue.identifier }}. kind=[{{ loop.retry_kind }}]" +
            "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"
    private val sentBackPrompt = "Work on t-1. kind=[sent_back]\nFindings: Sent back by soule: Use Save."

    private val workflow: WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = template,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds =
                        KindsConfig(
                            KindConfig(listOf(CheckConfig.Command("./gradlew test")), delivery = DeliveryConfig(DeliveryPolicy.Human)),
                        ),
                ),
        )

    /** Records every event; the first `human_notified` write runs [duringNotice] before it returns. */
    private class NoticeSink : EventSink {
        val events = mutableListOf<RecordedEvent>()
        var duringNotice: suspend () -> Unit = {}

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            if (event.kind == HumanNotified.name) {
                val hook = duringNotice
                duringNotice = {}
                hook()
            }
            events += event
            return Result.success(Unit)
        }
    }

    private inner class Run(
        scope: TestScope,
    ) {
        val tracker = FakeTracker(listOf(issue("t-1")))
        val waiting = FakeWaitingIssues()
        val sink = NoticeSink()

        /** Each session asks to move the issue to review, so its result is verified and waits for approval. */
        val runner =
            FakeRunner().apply {
                onCall = { ctx ->
                    ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                }
            }

        val orchestrator: Orchestrator =
            run {
                var next = 0
                val clock = SchedulerClock(scope.testScheduler)
                Orchestrator(
                    workflow = workflow,
                    tracker = tracker,
                    runner = runner,
                    sink = sink,
                    log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                    clock = clock,
                    workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
                    newId = { "a${++next}" },
                    runId = RunId("r1"),
                    projectId = PROJECT,
                    launcher = root.resolve("install/bin/loop"),
                    loopHome = root.resolve("home"),
                    hostPath = "/usr/bin",
                    parked = FakeParkedIssues(),
                    redactor = SecretRedactor(),
                    secretsOf = { emptyMap() },
                    checkRunnerFor = { CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1200) } },
                    waiting = waiting,
                    fingerprinter = Fingerprinter { Result.success("tree:abc") },
                )
            }

        fun stored(): WaitingIssue? = waiting.records[key]

        /**
         * Runs the first tick until its attempt holds inside the approval notice, runs [inWindow] there, then releases
         * the notice and lets the tick end.
         */
        suspend fun tickHeldInNotice(
            scope: TestScope,
            inWindow: suspend () -> Unit,
        ) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            sink.duringNotice = {
                entered.complete(Unit)
                release.await()
            }
            val first = scope.async { orchestrator.tick() }
            entered.await()
            val held = assertNotNull(stored(), "the approval wait is saved before the notice")
            assertEquals(WaitReason.Approval, held.reason)
            inWindow()
            release.complete(Unit)
            first.await()
        }
    }

    @Test
    fun `bl-257 a send back and a poll during the approval notice keep the sent back row, the next dispatch carries the reason`() =
        runTest {
            val run = Run(this)

            run.tickHeldInNotice(this) {
                assertNotNull(run.waiting.sendBack(PROJECT, "id-t-1", "soule", "Use Save.", FIXED_NOW).getOrThrow())
                run.orchestrator.tick()
            }

            val row = assertNotNull(run.stored(), "the sent back approval row is still in the store")
            assertEquals(WaitReason.Approval, row.reason)
            assertTrue(row.sentBack)
            assertEquals("Use Save.", row.data[WaitingIssue.SENT_BACK_REASON])
            assertEquals(1, run.runner.calls.size)

            run.orchestrator.tick()

            assertEquals(2, run.runner.calls.size)
            assertEquals(sentBackPrompt, run.runner.calls[1].second, "the next dispatch is a sent back retry with the reason")
        }

    @Test
    fun `bl-257 an approve and a poll during the approval notice keep the approved row, which is delivered`() =
        runTest {
            val run = Run(this)

            run.tickHeldInNotice(this) {
                assertNotNull(run.waiting.approve(PROJECT, "id-t-1", "soule", FIXED_NOW).getOrThrow())
                run.orchestrator.tick()
            }

            val row = assertNotNull(run.stored(), "the approved row is still in the store")
            assertTrue(row.approved)
            assertEquals(emptyList(), run.tracker.moves)

            run.orchestrator.tick()

            assertEquals(listOf("id-t-1" to "review"), run.tracker.moves, "the approval is delivered")
            assertNull(run.stored(), "the delivery ends the wait")
            assertEquals(1, run.runner.calls.size, "no agent runs again")
        }

    @Test
    fun `bl-257 control, a poll during the approval notice without a human command keeps the approval wait`() =
        runTest {
            val run = Run(this)

            run.tickHeldInNotice(this) { run.orchestrator.tick() }

            val row = assertNotNull(run.stored(), "the approval wait stays")
            assertEquals(WaitReason.Approval, row.reason)
            assertTrue(!row.approved && !row.sentBack)

            run.orchestrator.tick()

            assertEquals(1, run.runner.calls.size, "the issue waits for a human")
            assertEquals(emptyList(), run.tracker.moves)
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
