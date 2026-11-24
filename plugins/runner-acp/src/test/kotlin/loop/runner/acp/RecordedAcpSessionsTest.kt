package loop.runner.acp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Replays scrubbed Spike 0 `agy 1.5.16` ACP recordings through `FakeAcpServer` and `AcpRunner`. */
class RecordedAcpSessionsTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    @Test
    fun `replays recorded agy 1_5_16 success turn with occupancy and usage`() {
        val lines = resourceLines("/recorded/success_turn.jsonl")
        val server = replayServer(dir.resolve("ok"), lines)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = "gemini-3.8-flash-low",
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Reply hi").toList() } }

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("7dfce78b-8ae9-4ecb-9435-09e781c3485d", started.runtimeSessionId)

        // used: 0 is skipped; used: 10384 is emitted with google/gemini-3.8-flash-low.
        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>().single()
        assertEquals(10_384L, occupancy.occupancyTokens)
        assertEquals("google/gemini-3.8-flash-low", occupancy.model)

        val usage = events.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(10_383L, usage.inputTokens)
        assertEquals(1L, usage.outputTokens)
        assertEquals(0L, usage.cacheReadInputTokens)
        assertEquals(0L, usage.cacheCreationInputTokens)
        assertNull(usage.totalCostUsd)

        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `replays recorded agy 1_5_16 unknown model error as RunnerUnavailable`() {
        val lines = resourceLines("/recorded/invalid_model.jsonl")
        val server = replayServer(dir.resolve("bad"), lines)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = "gemini-no-such-model",
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Reply hi").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertTrue("Unknown model ID" in failed.reason, failed.reason)
    }

    private fun replayServer(
        targetDir: Path,
        lines: List<String>,
    ): FakeAcpServer =
        FakeAcpServer.write(targetDir) {
            for (line in lines) {
                val obj = AcpFraming.json.parseToJsonElement(line).jsonObject
                // Wait for the client's request before emitting each response/error frame.
                if ("id" in obj && "method" !in obj) {
                    awaitClient()
                }
                raw(line)
            }
        }

    private fun resourceLines(path: String): List<String> =
        checkNotNull(RecordedAcpSessionsTest::class.java.getResource(path)) { "missing test resource $path" }
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-rec",
                    title = "Recorded test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )
}
