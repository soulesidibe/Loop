package loop.core.orchestrator

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.AttemptRecordWriter
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m2-18: every runner line goes in full to the Attempt file, and the `runner_output` event keeps a bounded
 * preview with the full length, a `truncated` flag and the record's sequence number.
 */
class RunnerOutputRecordTest {
    private val root: Path = createTempDirectory("loop-runner-output-record").toRealPath()
    private val sink = FakeSink()
    private val logLines = mutableListOf<String>()
    private val secret = "Lp7secretQ93xZ0vK2mN5"

    private val workflow =
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
            promptTemplate = "Do {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 1)),
        )

    /** Keeps the records in memory and numbers them from 1, as the Attempt file does. */
    private class MemoryWriter(
        private val answer: (AttemptRecord) -> Result<Unit> = { Result.success(Unit) },
    ) : AttemptRecordWriter {
        val records = mutableListOf<AttemptRecord>()

        override fun append(
            projectId: ProjectId,
            attemptId: AttemptId,
            record: AttemptRecord,
        ): Result<Long> =
            answer(record).map {
                records += record
                records.size.toLong()
            }
    }

    private fun runner(vararg lines: String): FakeRunner =
        FakeRunner { _, _ ->
            flow {
                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                lines.forEach { emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, it)) }
                emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
            }
        }

    private fun orchestrator(
        runner: FakeRunner,
        writer: AttemptRecordWriter?,
    ): Orchestrator {
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("m1-02"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = loop.api.Redactor.NONE),
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

    private fun outputs(): List<RecordedEvent> = sink.events.filter { it.kind == "runner_output" }

    @Test
    fun `a line longer than 2000 characters goes in full to the attempt file and the event keeps a bounded preview`() =
        runTest {
            val writer = MemoryWriter()
            val long = "x".repeat(4990) + secret

            val summary = orchestrator(runner(long, "short"), writer).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            val redacted = "x".repeat(4990) + "[redacted:LOOP_TEST_SECRET]"
            assertEquals(
                listOf(
                    AttemptRecord.RunnerOutput(FixedClock().now(), RunnerEvent.Output.Stream.Stdout, redacted),
                    AttemptRecord.RunnerOutput(FixedClock().now(), RunnerEvent.Output.Stream.Stdout, "short"),
                ),
                writer.records.filterIsInstance<AttemptRecord.RunnerOutput>(),
            )
            val (first, second) = outputs()
            // The prompt is record 1, so the two lines are records 2 and 3.
            assertEquals(
                mapOf(
                    "stream" to "stdout",
                    "line" to "x".repeat(2000),
                    "chars" to redacted.length.toString(),
                    "truncated" to "true",
                    "record_seq" to "2",
                ),
                first.data,
            )
            assertEquals(mapOf("stream" to "stdout", "line" to "short", "chars" to "5", "record_seq" to "3"), second.data)
            assertFalse(sink.events.any { secret in it.data.values.joinToString() })
        }

    @Test
    fun `m3-14 the full text of a trimmed tool reply is appended to the attempt file, redacted, and leaves the attempt directory`() =
        runTest {
            val writer = MemoryWriter()
            val full = "loop tool mcp: a long refusal that names $secret. Nothing queued."
            var saved: Path? = null
            val runner =
                FakeRunner { ctx, _ ->
                    val dir = Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent
                    saved = ToolOutputQueue(dir).save(SavedToolOutput("move", full))
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            orchestrator(runner, writer).tick()

            val redacted = "loop tool mcp: a long refusal that names [redacted:LOOP_TEST_SECRET]. Nothing queued."
            assertEquals(
                listOf(AttemptRecord.ToolOutput(FixedClock().now(), "move", redacted)),
                writer.records.filterIsInstance<AttemptRecord.ToolOutput>(),
            )
            assertFalse(checkNotNull(saved).exists(), "the saved reply is removed once it is in the attempt file")
        }

    @Test
    fun `a failed write is recorded once with a redacted reason, the events lose their seq and the attempt goes on`() =
        runTest {
            val writer =
                MemoryWriter { record ->
                    if (record is AttemptRecord.RunnerOutput) {
                        Result.failure(
                            IOException("disk full near $secret"),
                        )
                    } else {
                        Result.success(Unit)
                    }
                }

            val summary = orchestrator(runner("one", "two"), writer).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            val failed = sink.events.filter { it.kind == "runner_output_record_failed" }
            assertEquals(1, failed.size, sink.events.map { it.kind }.toString())
            assertEquals("cannot record the runner output: disk full near [redacted:LOOP_TEST_SECRET]", failed.single().data["reason"])
            assertEquals(listOf(null, null), outputs().map { it.data["record_seq"] })
            assertEquals(listOf("one", "two"), outputs().map { it.data["line"] })
            assertTrue(logLines.any { "event=runner_output_record outcome=failed" in it }, logLines.joinToString("\n"))
            assertFalse(logLines.any { secret in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `without an attempt file writer the event keeps the preview, the length and the flag`() =
        runTest {
            orchestrator(runner("y".repeat(2500)), writer = null).tick()

            assertEquals(
                mapOf("stream" to "stdout", "line" to "y".repeat(2000), "chars" to "2500", "truncated" to "true"),
                outputs().single().data,
            )
            assertEquals(emptyList(), sink.events.filter { it.kind.endsWith("_record_failed") })
        }
}
