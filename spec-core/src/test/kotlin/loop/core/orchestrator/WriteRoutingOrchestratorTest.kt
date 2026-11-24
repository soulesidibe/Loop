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
import loop.core.trackers.TrackerEntry
import loop.core.trackers.TrackerPoll
import loop.core.trackers.TrackerRegistry
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.waiting.WaitKeys
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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-14, story 114, bl-153: with a primary tracker `local` and an extra tracker `gh`, every write the orchestrator
 * makes reaches the issue's own tracker, never the other: the agent's `move` and `comment` applied at the drain, and
 * Loop's own writes, the evidence summary, the verified move and the move of an approved delivery. Each write is made
 * with the current issue, so the adapter reads its `native_ref`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteRoutingOrchestratorTest {
    private val root: Path = createTempDirectory("loop-write-routing").toRealPath()
    private val sink = FakeSink()
    private var waiting = FakeWaitingIssues()

    private val local = FakeTracker(listOf(issue("l-1"))).apply { filterByStates = true }
    private val github =
        FakeTracker(listOf(issue("26", state = "open", labels = listOf("gh-ready")).copy(nativeRef = GH_REF))).apply {
            filterByStates = true
        }

    private val ghEntry =
        TrackerEntry(
            id = "gh",
            kind = "github",
            dir = root,
            settings = null,
            activeStates = listOf("open"),
            terminalStates = listOf("closed"),
            requiredLabels = listOf("gh-ready"),
        )

    private fun workflow(policy: DeliveryPolicy? = null): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = 5, maxTurns = 1),
                    kinds =
                        policy?.let {
                            KindsConfig(KindConfig(listOf(CheckConfig.Command("./gradlew test")), delivery = DeliveryConfig(it)))
                        },
                ),
            trackerId = "local",
            extraTrackers = listOf(ghEntry),
        )

    private val trackers: TrackerPoll =
        TrackerPoll.of(TrackerRegistry.build(workflow().trackers) { entry -> if (entry.id == "local") local else github })

    /** A runner whose session queues [requests] the way `loop tool` does, for every issue. */
    private fun queueing(vararg requests: ToolRequest): FakeRunner =
        FakeRunner().apply {
            onCall = { ctx ->
                val queue = ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent)
                requests.forEach { queue.enqueue(it) }
            }
        }

    /** The tracker id and the states of each tool config snapshot written, by attempt directory. */
    private val snapshots = mutableMapOf<String, Pair<String, List<String>>>()

    private fun TestScope.orchestrator(
        workflow: WorkflowFile,
        runner: FakeRunner = FakeRunner(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = trackers,
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
            toolConfigWriter =
                ToolConfigWriter { target, _, _, tracker, states ->
                    snapshots[target.parent.fileName.toString()] = tracker.id to states
                    target.writeText("{}")
                },
        )
    }

    /** The native ids [tracker] got the writes by issue for, with the `native_ref` of each. */
    private fun FakeTracker.written(): List<Pair<String, Map<String, String>?>> = writtenIssues.map { it.id to it.nativeRef }

    @Test
    fun `the agent's move and comment reach the issue's own tracker, with the issue and its native_ref`() =
        runTest {
            orchestrator(workflow(), queueing(ToolRequest.Comment("a question"), ToolRequest.Move("needs_info"))).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("id-l-1" to "needs_info"), local.moves)
            assertEquals(listOf("id-26" to "needs_info"), github.moves)
            assertEquals(listOf("id-l-1"), local.comments.map { it.first })
            assertEquals(listOf("id-26"), github.comments.map { it.first })
            assertEquals(listOf("a question"), github.comments.map { it.second.text })
            // The native adapter gets the current issue with its own id, and the github one its native_ref.
            assertEquals(listOf("id-l-1" to null, "id-l-1" to null), local.written())
            assertEquals(listOf("id-26" to GH_REF, "id-26" to GH_REF), github.written())
        }

    @Test
    fun `the tool config snapshot of each attempt holds the issue's own tracker and its states`() =
        runTest {
            github.definedStatesOverride = listOf("open", "closed", "review", "needs_info")

            orchestrator(workflow()).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(
                setOf("local" to local.definedStates, "gh" to listOf("open", "closed", "review", "needs_info")),
                snapshots.values.toSet(),
            )
        }

    @Test
    fun `under human Loop's evidence summary reaches the issue's own tracker`() =
        runTest {
            orchestrator(workflow(DeliveryPolicy.Human), queueing(ToolRequest.Move("review"))).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(2, sink.events.count { it.kind == "approval_requested" }, sink.events.map { it.kind }.toString())
            assertEquals(listOf("id-l-1"), local.comments.map { it.first })
            assertEquals(listOf("id-26"), github.comments.map { it.first })
            assertContains(
                github.comments
                    .single()
                    .second.text,
                "26",
            )
            assertEquals(listOf("id-26" to GH_REF), github.written())
            assertEquals(emptyList(), local.moves + github.moves, "nothing moves before the approval")
        }

    @Test
    fun `under auto Loop's verified move reaches the issue's own tracker`() =
        runTest {
            orchestrator(workflow(DeliveryPolicy.Auto), queueing(ToolRequest.Move("review"))).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("id-l-1" to "review"), local.moves)
            assertEquals(listOf("id-26" to "review"), github.moves)
            assertEquals(listOf("id-26" to GH_REF), github.written())
            val verified = sink.events.filter { it.kind == "issue_moved" && it.data["verified"] == "true" }
            assertEquals(setOf("local:id-l-1", "gh:id-26"), verified.map { it.issueId }.toSet())
        }

    @Test
    fun `an approved delivery moves the issue on its own tracker`() =
        runTest {
            waiting = FakeWaitingIssues(approved("gh:id-26", "26"), approved("local:id-l-1", "l-1"))

            orchestrator(workflow(DeliveryPolicy.Human)).tick()

            assertEquals(listOf("id-l-1" to "review"), local.moves)
            assertEquals(listOf("id-26" to "review"), github.moves)
            assertEquals(listOf("id-26" to GH_REF), github.written())
            assertTrue(local.comments.isEmpty() && github.comments.isEmpty())
        }

    private fun approved(
        issueId: String,
        identifier: String,
    ) = WaitingIssue(
        projectId = PROJECT,
        issueId = issueId,
        identifier = identifier,
        reason = WaitReason.Approval,
        attemptId = AttemptId("dead1"),
        runId = RunId("r0"),
        since = FIXED_NOW,
        data =
            mapOf(
                WaitKeys.TO to "review",
                WaitKeys.FINGERPRINT to "tree:abc",
                WaitingIssue.APPROVED_BY to "soule",
                WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
            ),
    )

    private companion object {
        val PROJECT = ProjectId("p1")
        val GH_REF = mapOf("repo" to "octocat/Hello-World", "number" to "26", "node_id" to "I_26")
    }
}
