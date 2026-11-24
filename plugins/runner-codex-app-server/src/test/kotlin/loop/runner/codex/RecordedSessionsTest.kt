package loop.runner.codex

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.ServerRequest
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * m3-03, story 42: the Codex runner against real sessions of the pinned codex CLI, recorded and scrubbed in
 * `src/test/resources/real/` (see its README). Each test replays one recording through [FakeAppServer.Script.replaySession],
 * which waits for the runner where the real server waited, and checks what the runner made of it. The expected values
 * are read from the recording itself, so a test says what the runner must do with what Codex really sent.
 */
class RecordedSessionsTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    @Test
    fun `m3-03 every recorded line is a protocol message the generated types read`() {
        FIXTURES.forEach { name ->
            val lines = recording(name)
            assertTrue(lines.isNotEmpty(), name)
            lines.forEach { message ->
                val method = message["method"]?.jsonPrimitive?.content
                when {
                    method == null -> assertTrue("result" in message || "error" in message, "$name: $message")
                    "id" in message -> CodexJson.format.decodeFromJsonElement(ServerRequest.serializer(), message)
                    else -> CodexJson.format.decodeFromJsonElement(ServerNotification.serializer(), message)
                }
            }
        }
    }

    @Test
    fun `m3-03 a recorded success completes the turn with the recorded usage`() {
        val lines = recording("success")

        val events = replay("success")

        assertIs<RunnerEvent.SessionStarted>(events.first(), "$events")
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(expectedUsage(lines), events.filterIsInstance<RunnerEvent.Usage>().last().counts(), "$events")
    }

    @Test
    fun `m3-03 a recorded model refusal fails the turn with Codex's message`() {
        val message =
            recording("failure")
                .single { it.method() == "turn/completed" }
                .getValue("params")
                .jsonObject
                .getValue("turn")
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("message")
                .jsonPrimitive
                .content

        val events = replay("failure")

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last(), "$events")
        assertTrue(message in failed.reason, "${failed.reason} does not carry $message")
        assertTrue(events.none { it is RunnerEvent.TurnCompleted }, "$events")
    }

    /**
     * bl-141: the recorded refusal, `modelNotFound` on a ChatGPT login, replayed with `runners.codex.model` set. The
     * runner sends the model on `thread/start`, and the refused turn is `runner_unavailable`, not an agent failure.
     */
    @Test
    fun `bl-141 a recorded model refusal is runner_unavailable, and thread start carries the configured model`() {
        val model =
            recording("failure")
                .single { it["id"]?.jsonPrimitive?.content == "2" && "result" in it }
                .getValue("result")
                .jsonObject
                .getValue("model")
                .jsonPrimitive
                .content

        val received = mutableListOf<JsonObject>()
        val events = replay("failure", received, model = model)

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last(), "$events")
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertTrue("runners.codex.model" in failed.reason, failed.reason)
        val thread = received.single { it.method() == "thread/start" }
        assertEquals(model, thread.params()["model"]?.jsonPrimitive?.content, "$thread")
    }

    @Test
    fun `m3-03 a recorded command approval is answered by the runner's rules and the turn completes`() {
        val requests = recording("approval").filter { it.method() == "item/commandExecution/requestApproval" }
        assertEquals(2, requests.size)

        val received = mutableListOf<JsonObject>()
        val events = replay("approval", received)

        val answers = requests.map { request -> received.single { it["id"] == request["id"] && "method" !in it } }
        answers.forEach { answer ->
            assertEquals(
                "accept",
                answer
                    .getValue("result")
                    .jsonObject
                    .getValue("decision")
                    .jsonPrimitive.content,
                "$answer",
            )
        }
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
    }

    @Test
    fun `m3-03 a recorded user input request ends the turn as input required, unanswered`() {
        val request = recording("input-request").single { it.method() == "item/tool/requestUserInput" }

        val received = mutableListOf<JsonObject>()
        val events = replay("input-request", received)

        assertIs<RunnerEvent.TurnInputRequired>(events.last(), "$events")
        assertTrue(received.none { it["id"] == request["id"] && "method" !in it }, "the runner answered: $received")
    }

    @Test
    fun `m3-03 recorded usage and rate limits become Usage and RateLimit events`() {
        val lines = recording("usage")
        val limits =
            lines
                .filter { it.method() == "account/rateLimits/updated" }
                .last()
                .params()
                .getValue("rateLimits")
                .jsonObject
        val primary = limits.getValue("primary").jsonObject

        val events = replay("usage")

        assertEquals(expectedUsage(lines), events.filterIsInstance<RunnerEvent.Usage>().last().counts(), "$events")
        val limit = events.filterIsInstance<RunnerEvent.RateLimit>().last()
        assertEquals(limits.getValue("limitId").jsonPrimitive.content, limit.limitId)
        assertEquals(
            primary
                .getValue("usedPercent")
                .jsonPrimitive.long
                .toInt(),
            limit.primary?.usedPercent,
        )
        assertEquals(primary.getValue("windowDurationMins").jsonPrimitive.long, limit.primary?.windowMinutes)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
    }

    private data class Counts(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheCreation: Long,
    )

    private fun RunnerEvent.Usage.counts() = Counts(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    /** The counts of the session's last `thread/tokenUsage/updated` total, mapped as m3-21 maps them, the cached input apart. */
    private fun expectedUsage(lines: List<JsonObject>): Counts {
        val total =
            lines
                .last { it.method() == "thread/tokenUsage/updated" }
                .params()
                .getValue("tokenUsage")
                .jsonObject
                .getValue("total")
                .jsonObject

        fun count(key: String) = total[key]?.jsonPrimitive?.long ?: 0
        val cached = count("cachedInputTokens")
        val cacheWrite = count("cacheWriteInputTokens")
        return Counts(count("inputTokens") - cached - cacheWrite, count("outputTokens"), cached, cacheWrite)
    }

    private fun JsonObject.method(): String? = this["method"]?.jsonPrimitive?.content

    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    private fun recording(name: String): List<JsonObject> =
        javaClass
            .getResource("/real/$name.jsonl")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { CodexJson.format.parseToJsonElement(it).jsonObject }

    /**
     * Runs the runner, with [model] as `runners.codex.model`, against the replayed recording [name], the client lines
     * the fake read added to [received].
     */
    private fun replay(
        name: String,
        received: MutableList<JsonObject> = mutableListOf(),
        model: String? = null,
    ): List<RunnerEvent> {
        val text = javaClass.getResource("/real/$name.jsonl")!!.readText()
        val server = FakeAppServer.write(dir) { replaySession(text.lines()) }
        val events =
            CompletableFuture
                .supplyAsync { runBlocking { runner(server.command, model).run(context(), "Work on c-01").toList() } }
                .get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
        if (server.received.exists()) {
            received +=
                server.received
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map { CodexJson.format.parseToJsonElement(it).jsonObject }
        }
        return events
    }

    private fun runner(
        command: String,
        model: String? = null,
    ): CodexAppServerRunner =
        CodexAppServerRunner(
            CodexAppServerSettings(command, readTimeout = FakeAppServer.READ_TIMEOUT, strictMcp = false, model = model),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = ProcessGroupLauncher()::launch,
            loopVersion = "test-build",
        )

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "c-01",
                    title = "Codex",
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

    private companion object {
        val FIXTURES = listOf("success", "failure", "approval", "input-request", "usage")
        val TIMEOUT = 30.seconds
    }
}
