package loop.runner.acp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.api.LedgerTokens
import loop.api.RiskReview
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * bl-205: the merge risk reviewer of Gemini, the `agy` twin of the Codex reviewer. It runs one `agy acp` turn under
 * `preset: agy` in the issue workspace with the model of `risk.reviewer`, declines every command and every edit, allows
 * reads except under the holdout folder, passes no Loop tool, and answers with the text of the turn's final agent
 * message and the session's tokens. Each server is a [FakeAcpServer].
 */
class GeminiRiskReviewerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val holdout: Path by lazy {
        dir.resolve("loop-home/holdout").createDirectories().also { it.resolve("check.sh").writeText("exit 0\n") }
    }

    private val verdict = """{"dimensions":{"impact":{"verdict":"low"}}}"""

    @Test
    fun `bl-205 the answer is the text of the final agent message, with the session tokens and no cost`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                agentChunk("Reading the diff first.")
                toolCall("read_file")
                agentChunk(verdict.substring(0, 10))
                agentChunk(verdict.substring(10))
                response(4, promptResult(inputTokens = 3_119, outputTokens = 5, cachedRead = 12_288))
            }

        val review = review(server).getOrThrow()

        assertEquals(RiskReview(verdict, LedgerTokens(3_119, 5, 12_288, 0), null), review)
    }

    @Test
    fun `bl-205 the session runs the reviewer model in the workspace with no Loop tool, the prompt as its input`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake(model = "gemini-4-neon-xhigh")
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        review(server, model = "gemini-4-neon-xhigh").getOrThrow()

        val sent = received(server)
        val new = sent.single { it.method == "session/new" }.getValue("params").jsonObject
        assertEquals(workspace.toAbsolutePath().toString(), new.getValue("cwd").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), new.getValue("mcpServers").jsonArray, "a reviewer passes no Loop tool")
        val setModel = sent.single { it.method == "session/set_model" }.getValue("params").jsonObject
        assertEquals("gemini-4-neon-xhigh", setModel.getValue("modelId").jsonPrimitive.content)
        val prompt = sent.single { it.method == "session/prompt" }.getValue("params").jsonObject
        assertContains(prompt.getValue("prompt").toString(), "Review this pull request.")
    }

    @Test
    fun `bl-205 the model is set trimmed and in lower case, as the routing reads it`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake(model = MODEL)
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        assertEquals(verdict, review(server, model = "  Gemini-4-Neon-High ").getOrThrow().text)

        val setModel = received(server).single { it.method == "session/set_model" }.getValue("params").jsonObject
        assertEquals(MODEL, setModel.getValue("modelId").jsonPrimitive.content)
    }

    @Test
    fun `bl-205 a tool call update after the final message keeps the answer`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                toolCall("read_file")
                agentChunk(verdict)
                toolCallUpdate("call-1", "completed")
                response(4, promptResult())
            }

        assertEquals(verdict, review(server).getOrThrow().text)
    }

    @Test
    fun `bl-205 of two agent messages with their own ids, the answer is the last one`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                agentChunk("Here is my assessment.", messageId = "m-1")
                agentChunk(verdict.substring(0, 10), messageId = "m-2")
                agentChunk(verdict.substring(10), messageId = "m-2")
                response(4, promptResult())
            }

        assertEquals(verdict, review(server).getOrThrow().text)
    }

    @Test
    fun `bl-205 a command is declined, even a plain read, since the reviewer runs read only`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                permission(101, "run_command", "execute", "rawInput" to buildJsonObject { put("CommandLine", "cat README.md") })
                awaitClient()
                permission(102, "run_command", "execute", "rawInput" to buildJsonObject { put("CommandLine", "./gradlew test") })
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        assertEquals(verdict, review(server).getOrThrow().text)

        assertEquals(mapOf("101" to "reject_once", "102" to "reject_once"), answers(server))
    }

    @Test
    fun `bl-205 an edit, a write, a delete and a move are declined`() {
        val file = workspace.resolve("src/Main.kt").toString()
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                permission(101, "replace_file_content", "edit", "rawInput" to buildJsonObject { put("TargetFile", file) })
                awaitClient()
                permission(102, "write_to_file", "edit", "rawInput" to buildJsonObject { put("TargetFile", file) })
                awaitClient()
                permission(103, null, "delete", "locations" to locations(file))
                awaitClient()
                permission(104, null, "move", "locations" to locations(file))
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        assertEquals(verdict, review(server).getOrThrow().text)

        assertEquals(
            mapOf("101" to "reject_once", "102" to "reject_once", "103" to "reject_once", "104" to "reject_once"),
            answers(server),
        )
    }

    @Test
    fun `bl-205 a read and a search in the workspace are allowed, a read of the holdout folder is refused`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                permission(101, "view_file", "read", "rawInput" to buildJsonObject { put("Path", workspace.resolve("a.kt").toString()) })
                awaitClient()
                permission(
                    102,
                    "grep_search",
                    "search",
                    "rawInput" to buildJsonObject { put("SearchPath", workspace.toAbsolutePath().toString()) },
                )
                awaitClient()
                permission(103, "view_file", "read", "rawInput" to buildJsonObject { put("Path", holdout.resolve("check.sh").toString()) })
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        assertEquals(verdict, review(server).getOrThrow().text)

        assertEquals(mapOf("101" to "allow_once", "102" to "allow_once", "103" to "reject_once"), answers(server))
    }

    @Test
    fun `bl-205 a Loop MCP tool is declined, since a review passes none`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                permission(101, "mcp_loop_move", null)
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        review(server).getOrThrow()

        assertEquals(mapOf("101" to "reject_once"), answers(server))
    }

    @Test
    fun `bl-205 a failed turn is a crash naming the reason`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                agentChunk("Half an answer")
                error(4, -32603, "internal error")
            }

        val error = assertIs<RiskReviewError.Crashed>(review(server).exceptionOrNull())

        assertContains(error.message.orEmpty(), "agy ended in error: turn_failed: internal error")
    }

    @Test
    fun `bl-205 a turn with no agent message is a crash`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                toolCall("read_file")
                response(4, promptResult())
            }

        val error = assertIs<RiskReviewError.Crashed>(review(server).exceptionOrNull())

        assertEquals("agy ended the turn with no answer", error.message)
    }

    @Test
    fun `bl-205 a request for input is a crash`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                request(201, "session/request_user_input", buildJsonObject { put("prompt", "Which one?") })
                shell("sleep 30")
            }

        val error = assertIs<RiskReviewError.Crashed>(review(server).exceptionOrNull())

        assertContains(error.message.orEmpty(), "agy asked for input, which a review cannot give")
    }

    @Test
    fun `bl-205 a turn past the time limit is a timeout, its agy stopped`() {
        val pidFile = dir.resolve("agy.pid")
        val server =
            FakeAcpServer.write(dir) {
                shell("echo $$ > '$pidFile'")
                handshake()
                awaitClient()
                shell("sleep 30")
            }

        val started = System.nanoTime()
        val error = assertIs<RiskReviewError.Timeout>(review(server, timeoutMs = 2_000).exceptionOrNull())
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertEquals("no answer within 2000 ms", error.message)
        assertTrue(elapsed < 15_000, "the review stops at its limit, not when agy would end: $elapsed ms")
        val pid = Files.readString(pidFile).trim().toLong()
        assertTrue(
            ProcessHandle.of(pid).map { !it.isAlive }.orElse(true),
            "the agy process of a timed out review is stopped",
        )
    }

    @Test
    fun `bl-205 a command that cannot start is a crash`() {
        val reviewer =
            GeminiRiskReviewer(
                settings(command = "'${dir.resolve("missing/agy")}' acp"),
                hostEnv = hostEnv(),
            )

        val result = runBlocking { reviewer.review(RiskReviewRequest(workspace, MODEL, "Review this pull request.", 10_000)) }

        assertIs<RiskReviewError.Crashed>(result.exceptionOrNull())
    }

    @Test
    fun `bl-205 a review never keeps its isolated folder, even after the session reported its id`() {
        val server =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        review(server).getOrThrow()

        val root = home.resolve(AgyIsolationDir.FALLBACK_ROOT_NAME)
        val left = if (root.exists()) Files.list(root).use { it.toList() } else emptyList()
        assertEquals(emptyList(), left, "the isolated folder is removed when the review ends")
    }

    @Test
    fun `bl-254 in a clone the reviewer's protected roots check gets the project's base and the clone mode, and refuses`() {
        val fx = ReviewerCloneRecipe(dir.resolve("recipe"))
        val agy =
            FakeAcpServer.write(dir) {
                handshake()
                awaitClient()
                agentChunk(verdict)
                response(4, promptResult())
            }

        val live = reviewIn(agy, RiskReviewRequest(fx.workspace, MODEL, "Review this pull request.", 15_000))
        println("PROOF reviewer, review recipe, request without base or mode: ${live.exceptionOrNull()?.message ?: "answered"}")
        assertTrue(live.isSuccess, "the live setup: with the default request the check reads the clone's own main and passes")

        val request =
            RiskReviewRequest(fx.workspace, MODEL, "Review this pull request.", 15_000, baseRef = fx.base, cloneWorkspace = true)
        val guarded = reviewIn(agy, request)
        println("PROOF reviewer, review recipe, the project's base and clone mode: ${guarded.exceptionOrNull()?.message}")
        val error = assertIs<RiskReviewError.Crashed>(guarded.exceptionOrNull())
        assertContains(error.message.orEmpty(), "protected_workspace_root: added or modified workspace customization file .agents/mcp_config.json")
    }

    @Test
    fun `bl-205 toString names the command`() {
        assertEquals("GeminiRiskReviewer(command='/opt/agy' acp)", GeminiRiskReviewer(settings(command = "'/opt/agy' acp")).toString())
    }

    @Test
    fun `bl-205 commandOf quotes a word only when it needs it, and the runner splits it back`() {
        val words = listOf("/Applications/My Tools/it's/agy", "acp")

        val command = AcpSettings.commandOf(words)

        assertEquals("'/Applications/My Tools/it'\\''s/agy' acp", command)
        assertEquals(words, CommandWords.of(command, "runners.acp.command"))
    }

    private fun review(
        server: FakeAcpServer,
        model: String = MODEL,
        timeoutMs: Long = 15_000,
    ): Result<RiskReview> =
        runBlocking {
            withTimeout(30.seconds) {
                GeminiRiskReviewer(settings(server.agyCommand), hostEnv())
                    .review(RiskReviewRequest(workspace, model, "Review this pull request.", timeoutMs))
            }
        }

    private fun reviewIn(
        server: FakeAcpServer,
        request: RiskReviewRequest,
    ): Result<RiskReview> = runBlocking { withTimeout(30.seconds) { GeminiRiskReviewer(settings(server.agyCommand), hostEnv()).review(request) } }

    private fun settings(command: String): AcpSettings =
        AcpSettings(
            command = command,
            preset = AcpSettings.PRESET_AGY,
            model = "gemini-4-neon-low",
            readTimeout = FakeAcpServer.READ_TIMEOUT,
            holdoutDir = holdout,
        )

    private fun hostEnv(): Map<String, String> = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString())

    private fun FakeAcpServer.Script.handshake(model: String = MODEL) {
        initialize(id = 1)
        sessionNew(id = 2, sessionId = SESSION)
        sessionSetModel(id = 3, configUpdateModelId = model, sessionId = SESSION)
    }

    private fun FakeAcpServer.Script.agentChunk(
        text: String,
        messageId: String? = null,
    ) = update(
        buildJsonObject {
            put("sessionUpdate", "agent_message_chunk")
            if (messageId != null) put("messageId", messageId)
            put(
                "content",
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        },
    )

    private fun FakeAcpServer.Script.toolCallUpdate(
        id: String,
        status: String,
    ) = update(
        buildJsonObject {
            put("sessionUpdate", "tool_call_update")
            put("toolCallId", id)
            put("status", status)
        },
    )

    private fun FakeAcpServer.Script.toolCall(name: String) =
        update(
            buildJsonObject {
                put("sessionUpdate", "tool_call")
                put("toolCallId", "call-1")
                put("name", name)
            },
        )

    private fun FakeAcpServer.Script.update(update: JsonObject) =
        notification(
            AcpHandshake.METHOD_SESSION_UPDATE,
            buildJsonObject {
                put("sessionId", SESSION)
                put("update", update)
            },
        )

    private fun FakeAcpServer.Script.permission(
        id: Long,
        name: String?,
        kind: String?,
        vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>,
    ) = request(
        id,
        AcpHandshake.METHOD_REQUEST_PERMISSION,
        buildJsonObject {
            put("sessionId", SESSION)
            put(
                "toolCall",
                buildJsonObject {
                    if (name != null) put("name", name)
                    if (kind != null) put("kind", kind)
                    fields.forEach { (key, value) -> put(key, value) }
                },
            )
            put("options", OPTIONS)
        },
    )

    private fun locations(path: String): JsonArray = buildJsonArray { add(buildJsonObject { put("path", path) }) }

    private fun promptResult(
        inputTokens: Long = 10,
        outputTokens: Long = 1,
        cachedRead: Long = 0,
    ): JsonObject =
        buildJsonObject {
            put("stopReason", "end_turn")
            put(
                "usage",
                buildJsonObject {
                    put("inputTokens", inputTokens)
                    put("outputTokens", outputTokens)
                    put("cachedReadTokens", cachedRead)
                    put("cachedWriteTokens", 0)
                },
            )
        }

    private fun received(server: FakeAcpServer): List<JsonObject> =
        server.received
            .readLines()
            .filter { it.isNotBlank() }
            .map { AcpFraming.json.parseToJsonElement(it).jsonObject }

    /** The option Loop selected for each permission request, by request id. */
    private fun answers(server: FakeAcpServer): Map<String, String> =
        received(server)
            .filter { "result" in it }
            .associate {
                it.getValue("id").jsonPrimitive.content to
                    it
                        .getValue("result")
                        .jsonObject
                        .getValue("outcome")
                        .jsonObject
                        .getValue("optionId")
                        .jsonPrimitive.content
            }

    private val JsonObject.method: String? get() = get("method")?.jsonPrimitive?.content

    private companion object {
        const val SESSION = "review-session-1"
        const val MODEL = "gemini-4-neon-high"

        val OPTIONS =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("optionId", "allow_once")
                        put("kind", "allow_once")
                    },
                )
                add(
                    buildJsonObject {
                        put("optionId", "reject_once")
                        put("kind", "reject_once")
                    },
                )
            }
    }
}
