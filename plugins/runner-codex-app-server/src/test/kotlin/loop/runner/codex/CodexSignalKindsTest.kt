package loop.runner.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.schema.PinnedSchema
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m3-21, 17.5 item 14: approval, user input, usage and rate limit signals are each interpreted per protocol, one
 * mapping per message kind of the pinned schema, one test per kind. Every request and notification here is checked
 * against the pinned schema, and the kinds are read from it, so a schema update that adds an approval, a user input,
 * a usage or a rate limit kind fails here until the runner maps it. The turn level behavior of each approval kind is in
 * [CodexServerRequestsTest]; usage and rate limits through a fake server are in [CodexTelemetryTest].
 */
class CodexSignalKindsTest {
    @TempDir
    lateinit var workspace: Path

    private val schema = PinnedSchema.load()

    private val bundle: JsonObject by lazy {
        CodexJson.format
            .parseToJsonElement(Path(System.getProperty("loop.codexSchema")).readText())
            .jsonObject
            .getValue("definitions")
            .jsonObject
    }

    /** The `method` of each variant of the union [name] of the pinned schema. */
    private fun methodsOf(name: String): Set<String> =
        bundle
            .getValue(name)
            .jsonObject
            .getValue("oneOf")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("method")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content
            }.toSet()

    @Test
    fun `17_5 item 14 every server request kind of the pinned schema has its own rule, but the two that are no signal`() {
        val policy = ServerRequestPolicy(workspace, null)
        val unmapped =
            methodsOf("ServerRequest").filter { method ->
                val answer = policy.answer(request(method, JsonObject(emptyMap())))
                answer is ServerRequestPolicy.Answer.Write && errorCode(answer.line) == -32601L
            }

        assertEquals(setOf("account/chatgptAuthTokens/refresh", "attestation/generate"), unmapped.toSet())
    }

    @Test
    fun `17_5 item 14 the usage and rate limit kinds of the pinned schema are the two notifications the runner reads`() {
        val notifications = methodsOf("ServerNotification")
        val signals = notifications.filter { "tokenusage" in it.lowercase() || "ratelimit" in it.lowercase() }

        assertEquals(setOf("thread/tokenUsage/updated", "account/rateLimits/updated"), signals.toSet())
        assertEquals(signals.toSet(), Telemetry.METHODS)
    }

    @Test
    fun `17_5 item 14 a command approval request maps to its accept or decline answer`() {
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", THREAD)
                put("turnId", TURN)
                put("startedAtMs", 1L)
                put("command", "./gradlew test")
                put("cwd", workspace.toString())
            }

        val answer = write(request("item/commandExecution/requestApproval", params))

        assertEquals(buildJsonObject { put("decision", "accept") }, result(answer.line))
        assertEquals(emptyList(), schema.problems("CommandExecutionRequestApprovalResponse", result(answer.line)))
        assertEquals("loop: accepted the command approval request 7", answer.note)
    }

    @Test
    fun `17_5 item 14 a file change approval request maps to its answer, from the changes its item notifications named`() {
        val policy = ServerRequestPolicy(workspace, null)
        val started =
            buildJsonObject {
                put("threadId", THREAD)
                put("turnId", TURN)
                put("startedAtMs", 1L)
                putJsonObject("item") {
                    put("type", "fileChange")
                    put("id", "item-1")
                    put("status", "inProgress")
                    put(
                        "changes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("path", workspace.resolve("a.txt").toString())
                                    put("diff", "@@ -1 +1 @@\n-a\n+b\n")
                                    putJsonObject("kind") {
                                        put("type", "update")
                                        put("move_path", JsonNull)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        policy.observe(notification("item/started", started))
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", THREAD)
                put("turnId", TURN)
                put("startedAtMs", 1L)
            }

        val answer = write(request("item/fileChange/requestApproval", params), policy)

        assertEquals(buildJsonObject { put("decision", "accept") }, result(answer.line))
        assertEquals(emptyList(), schema.problems("FileChangeRequestApprovalResponse", result(answer.line)))
    }

    @Test
    fun `17_5 item 14 the older exec approval request maps to its approved answer`() {
        val params =
            buildJsonObject {
                put("callId", "call-1")
                put("conversationId", THREAD)
                put("cwd", workspace.toString())
                put("command", JsonArray(listOf(JsonPrimitive("ls"), JsonPrimitive("-la"))))
                put("parsedCmd", buildJsonArray { })
            }

        val answer = write(request("execCommandApproval", params))

        assertEquals(buildJsonObject { put("decision", "approved") }, result(answer.line))
        assertEquals(emptyList(), schema.problems("ExecCommandApprovalResponse", result(answer.line)))
    }

    @Test
    fun `17_5 item 14 the older patch approval request maps to its approved answer`() {
        val params =
            buildJsonObject {
                put("callId", "call-1")
                put("conversationId", THREAD)
                putJsonObject("fileChanges") {
                    putJsonObject(workspace.resolve("a.txt").toString()) {
                        put("type", "update")
                        put("unified_diff", "@@ -1 +1 @@\n-a\n+b\n")
                        put("move_path", JsonNull)
                    }
                }
            }

        val answer = write(request("applyPatchApproval", params))

        assertEquals(buildJsonObject { put("decision", "approved") }, result(answer.line))
        assertEquals(emptyList(), schema.problems("ApplyPatchApprovalResponse", result(answer.line)))
    }

    @Test
    fun `17_5 item 14 a permissions approval request maps to an answer that grants nothing`() {
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", THREAD)
                put("turnId", TURN)
                put("cwd", workspace.toString())
                put("startedAtMs", 1L)
                put("reason", JsonNull)
                putJsonObject("permissions") { }
            }

        val answer = write(request("item/permissions/requestApproval", params))

        assertEquals(buildJsonObject { putJsonObject("permissions") { } }, result(answer.line))
        assertEquals("loop: granted nothing to the permissions request 7", answer.note)
    }

    @Test
    fun `17_5 item 14 an MCP elicitation request maps to a decline`() {
        val answer = write(request("mcpServer/elicitation/request", JsonObject(emptyMap())))

        assertEquals("decline", result(answer.line)["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `17_5 item 14 a user input request maps to input required, with no answer written`() {
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", THREAD)
                put("turnId", TURN)
                put("isBlocking", true)
                put("questions", buildJsonArray { })
            }

        val answer = ServerRequestPolicy(workspace, null).answer(request("item/tool/requestUserInput", params))

        assertEquals(ServerRequestPolicy.Answer.InputRequired("item/tool/requestUserInput"), answer)
    }

    @Test
    fun `17_5 item 14 a token usage notification maps to a Usage event, absolute and with no cost`() {
        val params =
            buildJsonObject {
                put("threadId", THREAD)
                put("turnId", TURN)
                putJsonObject("tokenUsage") {
                    put("last", breakdown(input = 300, cached = 100, output = 50))
                    put("total", breakdown(input = 1_000, cached = 600, output = 200))
                }
            }

        val signal = Telemetry().read(notification("thread/tokenUsage/updated", params))

        assertEquals(
            RunnerEvent.Usage(SESSION, TurnId(TURN), 400, 200, 600, 0, totalCostUsd = null),
            signal.single().event(SESSION, TurnId(TURN)),
        )
    }

    @Test
    fun `17_5 item 14 a rate limit notification maps to a RateLimit event`() {
        val params =
            buildJsonObject {
                putJsonObject("rateLimits") {
                    put("limitId", "codex")
                    putJsonObject("primary") {
                        put("usedPercent", 42)
                        put("windowDurationMins", 300)
                    }
                    put("rateLimitReachedType", "rate_limit_reached")
                }
            }

        val signal = Telemetry().read(notification("account/rateLimits/updated", params))

        val event = assertIs<RunnerEvent.RateLimit>(signal.single().event(SESSION, TurnId(TURN)))
        assertEquals("codex", event.limitId)
        assertNull(event.limitName)
        assertEquals(RunnerEvent.RateLimit.Window(42, 300, null), event.primary)
        assertNull(event.secondary)
        assertEquals("rate_limit_reached", event.reached)
    }

    @Test
    fun `m3-21 another notification is no signal`() {
        val params =
            buildJsonObject {
                put("threadId", THREAD)
                putJsonObject("status") { put("type", "idle") }
            }

        assertEquals(emptyList(), Telemetry().read(notification("thread/status/changed", params)))
    }

    private fun breakdown(
        input: Long,
        cached: Long,
        output: Long,
    ) = buildJsonObject {
        put("inputTokens", input)
        put("cachedInputTokens", cached)
        put("outputTokens", output)
        put("reasoningOutputTokens", 0)
        put("totalTokens", input + output)
    }

    private fun write(
        request: Frame.Request,
        policy: ServerRequestPolicy = ServerRequestPolicy(workspace, null),
    ): ServerRequestPolicy.Answer.Write = assertIs<ServerRequestPolicy.Answer.Write>(policy.answer(request))

    private fun request(
        method: String,
        params: JsonObject,
    ): Frame.Request {
        val line = FakeAppServer.serverRequestLine(7, method, params)
        if (params.isNotEmpty()) {
            assertEquals(emptyList(), schema.problems("ServerRequest", CodexJson.format.parseToJsonElement(line)), line)
        }
        return assertIs<Frame.Request>(ProtocolFraming.decode(line))
    }

    private fun notification(
        method: String,
        params: JsonObject,
    ): Frame.Notification {
        val line =
            JsonObject(mapOf("method" to JsonPrimitive(method), "params" to params)).toString()
        assertEquals(emptyList(), schema.problems("ServerNotification", CodexJson.format.parseToJsonElement(line)), line)
        return assertIs<Frame.Notification>(ProtocolFraming.decode(line))
    }

    private fun result(line: String): JsonObject =
        CodexJson.format
            .parseToJsonElement(line)
            .jsonObject
            .getValue("result")
            .jsonObject

    private fun errorCode(line: String): Long? =
        CodexJson.format
            .parseToJsonElement(line)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content
            ?.toLongOrNull()

    private companion object {
        const val THREAD = "019a0000-0000-7000-8000-000000000001"
        const val TURN = "019a0000-0000-7000-8000-000000000002"
        val SESSION = SessionId("$THREAD-$TURN")
    }
}
