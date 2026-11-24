package loop.runner.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AcpTelemetryAndInterruptTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private fun usageUpdate(
        sessionId: String,
        used: Long,
    ) = buildJsonObject {
        put("sessionId", sessionId)
        put(
            "update",
            buildJsonObject {
                put("sessionUpdate", "usage_update")
                put("used", used)
                put("size", 1_048_576)
            },
        )
    }

    @Test
    fun `maps usage_update to deduplicated ContextUsage and accumulates result usage across turns`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-tel")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "s-tel")
                // Turn 1
                awaitClient()
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-tel", 0))
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-tel", 16_220))
                // Duplicate occupancy should be skipped.
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-tel", 16_220))
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-tel", 17_630))
                response(
                    id = 4,
                    result =
                        buildJsonObject {
                            put("stopReason", "end_turn")
                            put(
                                "usage",
                                buildJsonObject {
                                    put("inputTokens", 1_000)
                                    put("cachedReadTokens", 15_000)
                                    put("cachedWriteTokens", 200)
                                    put("outputTokens", 430)
                                    put("thoughtTokens", 100)
                                    put("totalTokens", 16_430)
                                },
                            )
                        },
                )
                // Turn 2
                awaitClient()
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-tel", 22_000))
                response(
                    id = 5,
                    result =
                        buildJsonObject {
                            put("stopReason", "end_turn")
                            put(
                                "usage",
                                buildJsonObject {
                                    put("inputTokens", 500)
                                    put("cachedReadTokens", 21_000)
                                    put("cachedWriteTokens", 0)
                                    put("outputTokens", 300)
                                    put("totalTokens", 21_800)
                                },
                            )
                        },
                )
            }

        val runner = agyRunner(server)
        val (turn1, turn2) =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = runner.openSession(context())
                    try {
                        session.runTurn("Turn 1", 1).toList() to session.runTurn("Turn 2", 2).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val occ1 = turn1.filterIsInstance<RunnerEvent.ContextUsage>()
        assertEquals(listOf(16_220L, 17_630L), occ1.map { it.occupancyTokens })
        assertEquals(listOf("google/gemini-4-neon-high", "google/gemini-4-neon-high"), occ1.map { it.model })

        val usage1 = turn1.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(1_000L, usage1.inputTokens)
        assertEquals(430L, usage1.outputTokens)
        assertEquals(15_000L, usage1.cacheReadInputTokens)
        assertEquals(200L, usage1.cacheCreationInputTokens)
        assertNull(usage1.totalCostUsd)

        val occ2 = turn2.filterIsInstance<RunnerEvent.ContextUsage>().single()
        assertEquals(22_000L, occ2.occupancyTokens)
        assertEquals("google/gemini-4-neon-high", occ2.model)

        // Turn 2 usage is absolute for the whole session (turn 1 + turn 2).
        val usage2 = turn2.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(1_500L, usage2.inputTokens)
        assertEquals(730L, usage2.outputTokens)
        assertEquals(36_000L, usage2.cacheReadInputTokens)
        assertEquals(200L, usage2.cacheCreationInputTokens)
        assertNull(usage2.totalCostUsd)
    }

    @Test
    fun `interrupt sends session cancel, cancels permission requests, preserves usage, and completes cleanly`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-int")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "s-int")
                awaitClient()
                notification(AcpHandshake.METHOD_SESSION_UPDATE, usageUpdate("s-int", 300_000))
                // Wait for session/cancel from Loop.
                awaitClient()
                // Send a late permission request while cancelling: Loop must reply cancelled.
                request(
                    id = 77,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-int")
                            put(
                                "toolCall",
                                buildJsonObject {
                                    put("name", "read_file")
                                    put("kind", "read")
                                    put("rawInput", buildJsonObject { put("Path", workspace.resolve("a.txt").toString()) })
                                },
                            )
                            put(
                                "options",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("optionId", "allow_once")
                                            put("kind", "allow_once")
                                        },
                                    )
                                },
                            )
                        },
                )
                awaitClient()
                response(
                    id = 4,
                    result =
                        buildJsonObject {
                            put("stopReason", "cancelled")
                            put(
                                "usage",
                                buildJsonObject {
                                    put("inputTokens", 15_000)
                                    put("cachedReadTokens", 400)
                                    put("outputTokens", 89)
                                    put("totalTokens", 15_489)
                                },
                            )
                        },
                )
            }

        val runner = agyRunner(server)
        val events = mutableListOf<RunnerEvent>()
        runBlocking {
            withTimeout(20.seconds) {
                val session = runner.openSession(context())
                val sawHighOccupancy = CompletableFuture<Unit>()
                val job =
                    launch(Dispatchers.IO) {
                        session.runTurn("Work", 1).collect { event ->
                            events += event
                            if (event is RunnerEvent.ContextUsage && event.occupancyTokens >= 300_000L) {
                                session.interrupt()
                                sawHighOccupancy.complete(Unit)
                            }
                        }
                    }
                sawHighOccupancy.get(10, TimeUnit.SECONDS)
                job.join()
                session.stop()
            }
        }

        val usage = events.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(15_000L, usage.inputTokens)
        assertEquals(89L, usage.outputTokens)
        assertEquals(400L, usage.cacheReadInputTokens)
        assertIs<RunnerEvent.TurnCompleted>(events.last())

        val sent = server.received.readLines().map { AcpFraming.json.parseToJsonElement(it).jsonObject }
        val cancel = sent.single { it["method"]?.jsonPrimitive?.content == "session/cancel" }
        assertEquals("s-int", cancel["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        val permReply = sent.single { it["id"] == JsonPrimitive(77) }
        assertEquals(
            "cancelled",
            permReply["result"]!!
                .jsonObject["outcome"]!!
                .jsonObject["outcome"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `classifies structured unavailability errors as RunnerUnavailable and ordinary errors as null`() {
        assertEquals(
            RunnerEvent.TurnFailed.Category.RunnerUnavailable,
            AcpErrors.classify("session/set_model", -32602, "Unknown model ID: gemini-bad").category,
        )
        assertEquals(
            RunnerEvent.TurnFailed.Category.RunnerUnavailable,
            AcpErrors.classify("session/prompt", -32000, "IneligibleTierError: not supported").category,
        )
        val rateLimited =
            AcpErrors.classify(
                "session/prompt",
                -32000,
                "Too many requests",
                buildJsonObject {
                    put("httpStatus", 429)
                    put("retryAfterSeconds", 30)
                },
            )
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, rateLimited.category)
        assertEquals(30.seconds, rateLimited.retryAfter)

        assertEquals(
            RunnerEvent.TurnFailed.Category.RunnerUnavailable,
            AcpErrors.classify("session/prompt", -32000, "fetch failed: ECONNRESET").category,
        )

        // HTTP 500 with transport wording reached the server, so it is an ordinary failure.
        val serverError =
            AcpErrors.classify(
                "session/prompt",
                -32000,
                "network error",
                buildJsonObject { put("httpStatus", 500) },
            )
        assertNull(serverError.category)
        assertNull(AcpErrors.classify("session/prompt", -32603, "Internal error").category)
        // Free-text error phrases without structured codes or HTTP statuses stay ordinary failures (null).
        assertNull(AcpErrors.classify("session/prompt", -32000, "Buffer capacity exceeded in user test").category)
        assertNull(AcpErrors.classify("session/prompt", -32000, "Access denied reading /etc/shadow").category)
        assertNull(AcpErrors.classify("session/prompt", -32000, "PERMISSION_DENIED on workspace file").category)
        assertNull(AcpErrors.classify("session/prompt", -32000, "User test hit a rate limit fixture").category)
        assertNull(AcpErrors.classify("session/prompt", -32000, "Worker pool overloaded during unit test").category)
        assertNull(AcpErrors.classify("session/prompt", -32000, "fetch failed in mock client").category)
        // Structured error codes still classify as RunnerUnavailable.
        assertEquals(
            RunnerEvent.TurnFailed.Category.RunnerUnavailable,
            AcpErrors.classify("session/prompt", -32000, "RESOURCE_EXHAUSTED").category,
        )
        assertEquals(
            RunnerEvent.TurnFailed.Category.RunnerUnavailable,
            AcpErrors.classify("session/prompt", -32000, "HTTP 503").category,
        )
    }

    @Test
    fun `prompt JSON-RPC unavailability error emits TurnFailed with RunnerUnavailable category`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-err")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "s-err")
                awaitClient()
                error(id = 4, code = -32000, message = "429 Too Many Requests: quota exceeded")
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertTrue("quota exceeded" in failed.reason, failed.reason)
    }

    private fun agyRunner(server: FakeAcpServer): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = server.agyCommand,
                    preset = AcpSettings.PRESET_AGY,
                    model = FakeAcpServer.DEFAULT_MODEL,
                    readTimeout = FakeAcpServer.READ_TIMEOUT,
                ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
        )

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-06",
                    title = "Telemetry test",
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
