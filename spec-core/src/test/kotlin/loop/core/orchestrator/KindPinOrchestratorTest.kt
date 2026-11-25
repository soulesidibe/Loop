package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.verify.WorkspaceFingerprint
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindMatch
import loop.core.workflow.KindsConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * m4-18, the kind pinned on the waits: an issue's verify and approval waits hold the kind it was dispatched under, so
 * its delivery and a restored check follow that kind even when a human moved the issue into another kind's match in
 * between. Two kinds: `code`, the default, delivers a pull request; `quick`, for the `in_progress` state, moves the
 * issue. Each has its own check, so the check that ran names the kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KindPinOrchestratorTest {
    private val root: Path = createTempDirectory("loop-kind-pin").toRealPath()
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val empty = WorkspaceFingerprint.EMPTY_HASH
    private val clean = "git:$sha:$empty:$empty"

    private val workflow: WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds =
                        KindsConfig(
                            linkedMapOf(
                                "code" to
                                    KindConfig(
                                        listOf(CheckConfig.Command("code-check")),
                                        delivery =
                                            DeliveryConfig(DeliveryPolicy.Human, PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN")),
                                    ),
                                "quick" to
                                    KindConfig(
                                        listOf(CheckConfig.Command("quick-check")),
                                        delivery = DeliveryConfig(DeliveryPolicy.Human),
                                        match = KindMatch(state = "in_progress"),
                                    ),
                            ),
                        ),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress", "doing"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work on {{ issue.identifier }}",
        )

    /** The checks run, by their command, each passing. */
    private val ran = mutableListOf<String>()

    private val checks = CheckRunner { check, _, _ -> CheckResult(0, timedOut = false, durationMs = 1).also { ran += (check as CheckConfig.Command).run } }

    private fun tracker(state: String) =
        FakeTracker(listOf(issue("t-1", state = state))).apply {
            definedStatesOverride = listOf("todo", "in_progress", "doing", "review", "needs_info", "done", "canceled")
        }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        runner: FakeRunner = FakeRunner(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
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
            checkRunnerFor = { checks },
            waiting = waiting,
            fingerprinter = Fingerprinter { Result.success(clean) },
            deliveryFor = { null },
        )
    }

    /** A runner whose one turn first lets a human move the issue to [humanTo], then queues the agent's [requests]. */
    private fun runner(
        tracker: FakeTracker,
        humanTo: String?,
        vararg requests: ToolRequest,
    ): FakeRunner =
        FakeRunner().apply {
            onCall = { ctx ->
                humanTo?.let { tracker.issues[0] = tracker.issues[0].copy(state = it) }
                val queue = ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent)
                requests.forEach { queue.enqueue(it) }
            }
        }

    /** The stored wait of t-1, approved by a human. */
    private fun FakeWaitingIssues.approve() {
        val key = PROJECT.value to "id-t-1"
        val record = records.getValue(key)
        records[key] = record.copy(data = record.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()))
    }

    private fun stored(
        reason: WaitReason,
        data: Map<String, String>,
    ) = WaitingIssue(PROJECT, "id-t-1", "t-1", reason, AttemptId("dead1"), RunId("r0"), FIXED_NOW, data)

    @Test
    fun `an issue dispatched as code and moved into the quick state before submit is delivered as code, a pull request`() =
        runTest {
            val tracker = tracker("todo")
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, waiting, sink, runner(tracker, "in_progress", ToolRequest.Move("review")))

            orchestrator.tick()

            assertEquals(listOf("code-check"), ran)
            val record = assertNotNull(waiting.records.values.singleOrNull())
            assertEquals(WaitReason.Approval, record.reason)
            assertEquals("code", record.data[WaitKeys.KIND])

            waiting.approve()
            orchestrator.tick()

            // The pull request delivery ran, and this host has no executor, so it failed for good: no plain move.
            assertEquals("pull_request", sink.events.single { it.kind == "delivery_failed" }.data["step"])
            assertEquals(emptyList(), tracker.moves)
            assertEquals("in_progress", tracker.issues.single().state)
        }

    @Test
    fun `an issue dispatched as quick and moved back to todo before submit is delivered as quick, a plain move`() =
        runTest {
            val tracker = tracker("in_progress")
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, waiting, sink, runner(tracker, "todo", ToolRequest.Move("review")))

            orchestrator.tick()

            assertEquals(listOf("quick-check"), ran)
            assertEquals("quick", waiting.records.values.single().data[WaitKeys.KIND])

            waiting.approve()
            orchestrator.tick()

            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `a restored verify wait reruns the checks of its pinned kind and pins the approval wait it starts`() =
        runTest {
            val tracker = tracker("todo")
            Files.createDirectories(root.resolve("workspaces/t-1"))
            val waiting = FakeWaitingIssues(stored(WaitReason.Verify, mapOf(WaitKeys.TO to "review", WaitKeys.KIND to "quick")))

            orchestrator(tracker, waiting, FakeSink()).tick()

            assertEquals(listOf("quick-check"), ran)
            val record = waiting.records.values.single()
            assertEquals(WaitReason.Approval, record.reason)
            assertEquals("quick", record.data[WaitKeys.KIND])
        }

    @Test
    fun `a restored verify wait stored without a kind reruns the checks of the kind the issue matches now`() =
        runTest {
            val tracker = tracker("in_progress")
            Files.createDirectories(root.resolve("workspaces/t-1"))
            val waiting = FakeWaitingIssues(stored(WaitReason.Verify, mapOf(WaitKeys.TO to "review")))

            orchestrator(tracker, waiting, FakeSink()).tick()

            assertEquals(listOf("quick-check"), ran)
        }

    @Test
    fun `a restored verify wait whose kind left the workflow is kept and runs no check`() =
        runTest {
            val tracker = tracker("todo")
            Files.createDirectories(root.resolve("workspaces/t-1"))
            val left = stored(WaitReason.Verify, mapOf(WaitKeys.TO to "review", WaitKeys.KIND to "content"))
            val waiting = FakeWaitingIssues(left)
            val runner = FakeRunner()

            orchestrator(tracker, waiting, FakeSink(), runner).tick()

            assertEquals(emptyList(), ran)
            assertEquals(0, runner.calls.size, "the waiting issue is not dispatched")
            assertEquals(listOf(left), waiting.records.values.toList())
        }

    @Test
    fun `an approved wait whose kind left the workflow is kept, nothing is delivered`() =
        runTest {
            val tracker = tracker("todo")
            val approved =
                stored(
                    WaitReason.Approval,
                    mapOf(
                        WaitKeys.TO to "review",
                        WaitKeys.FINGERPRINT to clean,
                        WaitKeys.KIND to "content",
                        WaitingIssue.APPROVED_BY to "soule",
                        WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
                    ),
                )
            val waiting = FakeWaitingIssues(approved)
            val sink = FakeSink()

            orchestrator(tracker, waiting, sink).tick()

            assertEquals(emptyList(), tracker.moves)
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
            assertEquals(listOf(approved), waiting.records.values.toList())
        }

    @Test
    fun `an agent move into another kind's state is refused, a move that keeps the kind is applied`() =
        runTest {
            val tracker = tracker("todo")
            val sink = FakeSink()
            val runner = runner(tracker, null, ToolRequest.Move("in_progress"), ToolRequest.Move("doing"))

            orchestrator(tracker, FakeWaitingIssues(), sink, runner).tick()

            assertEquals(listOf("id-t-1" to "doing"), tracker.moves)
            val failed = sink.events.single { it.kind == "tool_request_failed" }.data
            assertEquals("in_progress", failed["to"])
            assertEquals("the move to in_progress would change the kind of the issue from code to quick", failed["reason"])
        }

    companion object {
        val PROJECT = ProjectId("p1")
    }
}
