package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.AttemptLedger
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m3-10: `loop.session_brief` is set on a retry and on a reused workspace, null on a fresh first dispatch, and the
 * `session_brief` slot of `BuildingPrompt` is what the brief added to the prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionBriefPromptTest {
    private val root: Path = createTempDirectory("loop-session-brief-prompt").toRealPath()

    private val prompt =
        "Work on {{ issue.identifier }}. attempt=[{{ attempt }}]{% if loop.session_brief %}\n# Brief\n{{ loop.session_brief }}{% endif %}"

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
            pollingIntervalMs = 1000,
            promptTemplate = prompt,
        )

    /** Remembers every row it is given, like the store, so a retry reads the attempt before it. */
    private class RowLedger : AttemptLedger by AttemptLedger.NONE {
        val rows = mutableListOf<LedgerRow>()

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            rows += LedgerRow(attempt, rows.size + 1, 0)
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> = Result.success(rows.filter { it.attempt.issueId == issueId })
    }

    private fun TestScope.orchestrator(
        runner: FakeRunner,
        sink: FakeSink,
        ledger: AttemptLedger = AttemptLedger.NONE,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m3-10"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
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
            attemptLedger = ledger,
            attemptEvents = { attempt -> Result.success(sink.events.filter { it.attemptId == attempt }) },
        )
    }

    private fun List<RecordedEvent>.buildingPrompts(): List<RecordedEvent> =
        filter {
            it.kind == "phase" &&
                it.phase == AttemptPhase.BuildingPrompt.name
        }

    private fun RecordedEvent.slotTotal(): Int =
        data.filterKeys { it.startsWith("slot_") && it.endsWith("_chars") }.values.sumOf { it.toInt() }

    @Test
    fun `a fresh first dispatch renders loop session_brief null`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()

            orchestrator(runner, sink).tick()

            val rendered = runner.calls.single().second
            assertEquals("Work on m3-10. attempt=[]", rendered)
            val building = sink.events.buildingPrompts().single()
            assertEquals("0", building.data["slot_session_brief_chars"])
            assertEquals(rendered.length, building.slotTotal())
        }

    @Test
    fun `a first dispatch into a reused workspace renders the brief, and its slot measures it`() =
        runTest {
            val folder = workflow.workspaceRoot.resolve("m3-10").createDirectories()
            folder.resolve(".loop").createDirectories()
            folder.resolve(".loop/progress.md").writeText("- [ ] the step an earlier attempt left\n")
            val runner = FakeRunner()
            val sink = FakeSink()

            orchestrator(runner, sink).tick()

            val rendered = runner.calls.single().second
            assertTrue(rendered.startsWith("Work on m3-10. attempt=[]\n# Brief\n### Goal and acceptance criteria"), rendered)
            assertTrue(rendered.contains("### Agent notes, unverified"), rendered)
            assertTrue(rendered.contains("- [ ] the step an earlier attempt left"), rendered)
            val building = sink.events.buildingPrompts().single()
            val brief = rendered.substringAfter("Work on m3-10. attempt=[]")
            assertEquals(brief.length.toString(), building.data["slot_session_brief_chars"])
            assertEquals(rendered.length.toString(), building.data["prompt_chars"])
            assertEquals(rendered.length, building.slotTotal())
        }

    @Test
    fun `a retry renders the brief with the counters of the attempt before it`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(runner, sink, RowLedger())

            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            val (first, retry) = runner.calls.map { it.second }
            assertEquals("Work on m3-10. attempt=[]", first)
            assertTrue(retry.startsWith("Work on m3-10. attempt=[1]\n# Brief\n### Goal and acceptance criteria"), retry)
            assertTrue(retry.contains("- Attempts before this one: 1"), retry)
            assertTrue(retry.contains("- Rotations: 0"), retry)
            val building = sink.events.buildingPrompts().last()
            assertEquals(retry.substringAfter("attempt=[1]").length.toString(), building.data["slot_session_brief_chars"])
            assertEquals(retry.length, building.slotTotal())
        }
}
