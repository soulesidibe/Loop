package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.ReadyConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** m2-11: in Loop mode a kind's readiness check runs before dispatch; a failing issue is bounced once. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadinessDispatchTest {
    private val root: Path = createTempDirectory("loop-ready").toRealPath()

    private val withCriteria = "## What\n\nDo it.\n\n## Acceptance criteria\n\n- [ ] it works\n"
    private val withoutCriteria = "## What\n\nDo it.\n"

    private fun workflow(kinds: KindsConfig? = KindsConfig(KindConfig(emptyList(), ReadyConfig(listOf("Acceptance criteria"))))) =
        WorkflowFile(
            config = WorkflowConfig(kinds = kinds),
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
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        logLines: MutableList<String> = mutableListOf(),
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
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
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

    private fun FakeSink.ofKind(kind: String) = events.filter { it.kind == kind }

    @Test
    fun `an issue without the section is not dispatched, commented and labeled needs-info once`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-11", description = withoutCriteria)))
            val runner = FakeRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink)

            orchestrator.tick()
            orchestrator.tick()
            orchestrator.tick()

            assertEquals(0, runner.calls.size)
            val (issueId, comment) = tracker.comments.single()
            assertEquals("id-m2-11", issueId)
            assertEquals("loop", comment.author)
            assertContains(comment.text, "## Acceptance criteria")
            assertEquals(listOf(Triple("id-m2-11", listOf("needs-info"), emptyList<String>())), tracker.labelWrites)
            assertEquals(listOf("ready-for-agent", "needs-info"), tracker.issues.single().labels)
            val skipped = sink.ofKind("candidate_skipped").single()
            assertEquals(mapOf("reason" to "not_ready (missing Acceptance criteria)"), skipped.data)
            val commented = sink.ofKind("issue_commented").single()
            assertEquals("not_ready", commented.data["reason"])
            assertEquals(comment.text.length.toString(), commented.data["chars"])
            assertEquals(mapOf("add" to "needs-info", "reason" to "not_ready"), sink.ofKind("issue_labeled").single().data)
            assertTrue(sink.events.none { it.attemptId != null }, "no attempt was started")
        }

    @Test
    fun `an issue already labeled needs-info gets no new comment or label`() =
        runTest {
            val labels = listOf("ready-for-agent", "needs-info")
            val tracker = FakeTracker(listOf(issue("m2-11", labels = labels, description = withoutCriteria)))
            val runner = FakeRunner()

            orchestrator(tracker, runner, FakeSink()).tick()

            assertEquals(0, runner.calls.size)
            assertEquals(emptyList(), tracker.comments)
            assertEquals(emptyList(), tracker.labelWrites)
        }

    @Test
    fun `a failed label write is logged and the comment is still not repeated in the process`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-11", description = withoutCriteria)))
            tracker.labelFailure = TrackerError.Unsupported("labels")
            val runner = FakeRunner()
            val lines = mutableListOf<String>()
            val orchestrator = orchestrator(tracker, runner, FakeSink(), logLines = lines)

            orchestrator.tick()
            orchestrator.tick()

            assertEquals(0, runner.calls.size)
            assertEquals(1, tracker.comments.size)
            val warning = lines.single { "event=readiness" in it && "outcome=failed" in it }
            assertContains(warning, "level=warn")
            assertContains(warning, "labels")
        }

    @Test
    fun `an issue with the section dispatches as before`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-11", description = withCriteria)))
            val runner = FakeRunner()
            val sink = FakeSink()

            orchestrator(tracker, runner, sink).tick()

            assertEquals(1, runner.calls.size)
            assertEquals(emptyList(), tracker.comments)
            assertEquals(emptyList(), tracker.labelWrites)
            assertTrue(sink.ofKind("candidate_skipped").isEmpty())
        }

    @Test
    fun `an issue fixed after its bounce dispatches on a later tick`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-11", description = withoutCriteria)))
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, runner, FakeSink())
            orchestrator.tick()
            assertEquals(0, runner.calls.size)

            tracker.issues[0] = tracker.issues[0].copy(description = withCriteria)
            orchestrator.tick()

            assertEquals(1, runner.calls.size)
            assertEquals(1, tracker.comments.size)
        }

    @Test
    fun `no readiness check runs in Symphony mode or for a kind without ready`() =
        runTest {
            for (kinds in listOf(null, KindsConfig(KindConfig(emptyList())))) {
                val tracker = FakeTracker(listOf(issue("m2-11", description = withoutCriteria)))
                val runner = FakeRunner()

                orchestrator(tracker, runner, FakeSink(), workflow(kinds)).tick()

                assertEquals(1, runner.calls.size, "kinds=$kinds")
                assertEquals(emptyList(), tracker.comments)
                assertEquals(emptyList(), tracker.labelWrites)
            }
        }
}
