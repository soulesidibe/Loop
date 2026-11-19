package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.AttemptRecordWriter
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m1-13: the turn 1 rendered prompt goes to the Attempt file, and a failed write never fails the Attempt. */
class PromptRecordTest {
    private val root: Path = createTempDirectory("loop-prompt-record").toRealPath()
    private val sink = FakeSink()
    private val logLines = mutableListOf<String>()
    private val secret = "Lp7secretQ93xZ0vK2mN5"

    private fun workflow(prompt: String = "Do {{ issue.identifier }}: {{ issue.title }}"): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = prompt,
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 2), hooks = HooksConfig()),
        )

    private fun orchestrator(
        writer: AttemptRecordWriter,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-02"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a1" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) },
            secretsOf = { emptyMap() },
            attemptRecords = writer,
        )
    }

    private fun sessionRunner(): FakeRunner = FakeRunner().also { it.sessionScript = { turn, _ -> sessionTurn(turn) } }

    /** One call of [RecordingWriter], with the kinds and phases the sink held at that moment. */
    private data class Append(
        val projectId: ProjectId,
        val attemptId: AttemptId,
        val record: AttemptRecord,
        val phasesSoFar: List<String?>,
    )

    private inner class RecordingWriter(
        private val answer: () -> Result<Long> = { Result.success(1L) },
    ) : AttemptRecordWriter {
        val appends = mutableListOf<Append>()

        override fun append(
            projectId: ProjectId,
            attemptId: AttemptId,
            record: AttemptRecord,
        ): Result<Long> {
            appends += Append(projectId, attemptId, record, sink.events.filter { it.kind == "phase" }.map { it.phase })
            return answer()
        }
    }

    private fun ofKind(kind: String): List<RecordedEvent> = sink.events.filter { it.kind == kind }

    @Test
    fun `the turn 1 prompt is appended once, right after the render, and continuation turns are not`() =
        runTest {
            val writer = RecordingWriter()
            val runner = sessionRunner()

            val summary = orchestrator(writer, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(2, runner.turnInputs.size, "two turns ran")
            val append = writer.appends.single()
            assertEquals(ProjectId("p1"), append.projectId)
            assertEquals(AttemptId("a1"), append.attemptId)
            assertEquals(AttemptRecord.Prompt(FixedClock().now(), turn = 1, text = "Do m1-02: Title of m1-02"), append.record)
            assertEquals(runner.turnInputs.first().second, (append.record as AttemptRecord.Prompt).text)
            assertEquals(listOf(AttemptPhase.PreparingWorkspace.name), append.phasesSoFar)
            assertEquals(emptyList(), ofKind("prompt_record_failed"))
        }

    @Test
    fun `a failed write is recorded with a redacted reason and the attempt goes on`() =
        runTest {
            val writer = RecordingWriter { Result.failure(IOException("disk full near $secret")) }
            val runner = sessionRunner()

            val summary = orchestrator(writer, runner).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(2, runner.turnInputs.size)
            val failed = ofKind("prompt_record_failed").single()
            assertEquals(AttemptId("a1"), failed.attemptId)
            assertEquals("cannot record the prompt: disk full near [redacted:LOOP_TEST_SECRET]", failed.data["reason"])
            assertTrue(
                ofKind("phase").any { it.phase == AttemptPhase.BuildingPrompt.name && it.data["prompt_chars"] == "24" },
                sink.events.toString(),
            )
            assertTrue(logLines.any { "event=prompt_record outcome=failed" in it }, logLines.joinToString("\n"))
            assertFalse(logLines.any { secret in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `a writer that throws does not fail the attempt either`() =
        runTest {
            val writer = AttemptRecordWriter { _, _, _ -> throw IllegalStateException("broken writer") }

            val summary = orchestrator(writer, sessionRunner()).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals("cannot record the prompt: broken writer", ofKind("prompt_record_failed").single().data["reason"])
        }

    @Test
    fun `a template error appends nothing`() =
        runTest {
            val writer = RecordingWriter()

            val summary = orchestrator(writer, sessionRunner(), workflow(prompt = "{{ nope }}")).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals(emptyList(), writer.appends)
        }
}
