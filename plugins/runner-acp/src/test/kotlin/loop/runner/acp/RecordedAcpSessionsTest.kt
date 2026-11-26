package loop.runner.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Replays scrubbed `agy 1.5.16` ACP recordings through `FakeAcpServer` and `AcpRunner`. Loop now refuses an `agy` older
 * than [AcpSettings.MIN_AGY_VERSION], so [resourceLines] rewrites the recorded `agentInfo.version` to the pinned version
 * and the frames stay protocol fixtures. One test replays the untouched version and expects the refusal.
 */
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

    @Test
    fun `replays recorded agy 1_5_16 permission requests allowing safe command and declining holdout command`() {
        val lines = resourceLines("/recorded/permission_request.jsonl")
        val server = replayServer(dir.resolve("perm"), lines)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = "gemini-3.8-flash-low",
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                        holdoutDir = Path.of("/tmp/loop-holdout"),
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Run commands").toList() } }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val replies =
            server.received
                .readLines()
                .filter { it.isNotBlank() }
                .map { AcpFraming.json.parseToJsonElement(it).jsonObject }
                .filter { "result" in it }
                .associate {
                    it["id"]!!.jsonPrimitive.content to
                        it["result"]!!
                            .jsonObject["outcome"]!!
                            .jsonObject["optionId"]!!
                            .jsonPrimitive.content
                }
        assertEquals(mapOf("1" to "allow", "2" to "deny"), replies)
    }

    @Test
    fun `replays recorded agy 1_5_16 cancelled turn preserving partial usage`() {
        val lines = resourceLines("/recorded/cancelled_turn.jsonl")
        val server = replayServer(dir.resolve("cancel"), lines, awaitCancelBeforeFinalResponse = true)
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

        val events = mutableListOf<RunnerEvent>()
        runBlocking {
            withTimeout(20.seconds) {
                val session = runner.openSession(context())
                val interrupted = CompletableFuture<Unit>()
                val job =
                    launch(Dispatchers.IO) {
                        session.runTurn("Write an essay", 1).collect { event ->
                            events += event
                            if (event is RunnerEvent.ContextUsage && event.occupancyTokens >= 10_000L && !interrupted.isDone) {
                                session.interrupt()
                                interrupted.complete(Unit)
                            }
                        }
                    }
                interrupted.get(10, TimeUnit.SECONDS)
                job.join()
                session.stop()
            }
        }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val usage = events.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(10_440L, usage.inputTokens)
        assertEquals(1L, usage.outputTokens)
        val sent = server.received.readLines().map { AcpFraming.json.parseToJsonElement(it).jsonObject }
        assertTrue(sent.any { it["method"]?.jsonPrimitive?.content == "session/cancel" })
    }

    @Test
    fun `replays recorded agy 1_5_16 session load missing error on generic ACP`() {
        val lines = resourceLines("/recorded/session_load_missing.jsonl")
        val server = replayServer(dir.resolve("load-miss"), lines)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.command,
                        strictMcp = false,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            )

        val events =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = assertNotNull(runner.resumeSession(context(), "00000000-0000-0000-0000-000000000000"))
                    try {
                        session.runTurn("Resume", 1).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(
            "response_error: session/load failed with code -32002: Session not found: 00000000-0000-0000-0000-000000000000",
            failed.reason,
        )
    }

    @Test
    fun `replays recorded agy 1_5_16 bare model id without thought level suffix`() {
        val lines = resourceLines("/recorded/bare_model_turn.jsonl")
        val server = replayServer(dir.resolve("bare"), lines)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = "gemini-pro-orchestrator-neon",
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Reply hi.").toList() } }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>().last()
        assertEquals("google/gemini-pro-orchestrator-neon", occupancy.model)
        assertTrue(occupancy.occupancyTokens > 0L)
    }

    @Test
    fun `the untouched agy 1_5_16 recording is refused as older than the minimum`() {
        val lines = resourceLines("/recorded/success_turn.jsonl", asRecorded = true)
        assertTrue(lines.first().contains(RECORDED_VERSION), "the recording names agy 1.5.16")
        val server = replayServer(dir.resolve("old"), lines.take(1))
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

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(
            "response_error: agy 1.5.16 is older than the minimum version ${AcpSettings.MIN_AGY_VERSION} " +
                "(pinned ${AcpSettings.PINNED_AGY_VERSION})",
            failed.reason,
        )
    }

    private fun replayServer(
        targetDir: Path,
        lines: List<String>,
        awaitCancelBeforeFinalResponse: Boolean = false,
    ): FakeAcpServer =
        FakeAcpServer.write(targetDir) {
            var awaitedPrompt = false
            for ((index, line) in lines.withIndex()) {
                val obj = AcpFraming.json.parseToJsonElement(line).jsonObject
                val isResponse = "id" in obj && "method" !in obj
                val isServerRequest = "id" in obj && "method" in obj
                val isPromptResponse = isResponse && obj["id"]?.jsonPrimitive?.content == "4"
                if (isResponse) {
                    if (isPromptResponse && awaitedPrompt) {
                        // Already read session/prompt before emitting server requests or notifications.
                        if (awaitCancelBeforeFinalResponse) awaitClient()
                    } else {
                        awaitClient()
                        if (isPromptResponse && awaitCancelBeforeFinalResponse) awaitClient()
                    }
                } else if (isServerRequest && !awaitedPrompt) {
                    awaitClient()
                    awaitedPrompt = true
                } else if (awaitCancelBeforeFinalResponse && !awaitedPrompt && index == 4) {
                    awaitClient()
                    awaitedPrompt = true
                }
                raw(line)
                if (isServerRequest) {
                    // Read the client's reply to session/request_permission before continuing.
                    awaitClient()
                }
            }
        }

    private fun resourceLines(
        path: String,
        asRecorded: Boolean = false,
    ): List<String> =
        checkNotNull(RecordedAcpSessionsTest::class.java.getResource(path)) { "missing test resource $path" }
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                if (asRecorded || "\"agentInfo\"" !in line) {
                    line
                } else {
                    line.replace(RECORDED_VERSION, "\"version\":\"${AcpSettings.PINNED_AGY_VERSION}\"")
                }
            }

    private companion object {
        const val RECORDED_VERSION = "\"version\":\"1.5.16\""
    }

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
