package loop.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Loop's MCP server (m2-16) over newline delimited JSON-RPC, as Claude Code speaks it on the server's stdin and
 * stdout: the handshake, the `submit_result` tool and its section 7 schema, the `move` and `comment` tools (m2-17),
 * and the end of input.
 */
class LoopMcpServerTest {
    /** A client of the server on pipes: [request] writes one line and reads the response of the same id. */
    private class Client(
        handler: SubmitResultHandler,
        move: MoveHandler = MoveHandler { ToolReply("moved") },
        comment: CommentHandler = CommentHandler { ToolReply("commented") },
        trimmer: ReplyTrimmer = ReplyTrimmer.NONE,
        resources: ResourceHandler? = null,
    ) {
        private val input = Pipe.open()
        private val output = Pipe.open()
        private val toServer = Channels.newOutputStream(input.sink())
        private val serverIn = Channels.newInputStream(input.source())
        private val serverOut = Channels.newOutputStream(output.sink())
        private val fromServer: BufferedReader = Channels.newInputStream(output.source()).bufferedReader()
        private var nextId = 1

        val serving =
            Thread {
                try {
                    runBlocking { LoopMcpServer.serve(serverIn, serverOut, "test", handler, move, comment, trimmer, resources) }
                } finally {
                    serverOut.close()
                }
            }.apply { start() }

        fun send(message: String) {
            toServer.write("$message\n".toByteArray())
            toServer.flush()
        }

        suspend fun request(
            method: String,
            params: String,
        ): JsonObject {
            val id = nextId++
            send("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            return withContext(Dispatchers.IO) {
                withTimeout(10.seconds) {
                    generateSequence { fromServer.readLine() }
                        .map { Json.parseToJsonElement(it).jsonObject }
                        .firstOrNull { it["id"]?.jsonPrimitive?.content == id.toString() }
                        ?: error("the server closed its output")
                }
            }
        }

        suspend fun initialize(): JsonObject {
            val response =
                request(
                    "initialize",
                    """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1.0"}}""",
                )
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            return response
        }

        fun close() {
            toServer.close()
            serving.join(10_000)
        }
    }

    private fun withClient(
        handler: SubmitResultHandler = SubmitResultHandler { ToolReply("ok") },
        move: MoveHandler = MoveHandler { ToolReply("moved") },
        comment: CommentHandler = CommentHandler { ToolReply("commented") },
        trimmer: ReplyTrimmer = ReplyTrimmer.NONE,
        resources: ResourceHandler? = null,
        block: suspend Client.() -> Unit,
    ) {
        val client = Client(handler, move, comment, trimmer, resources)
        try {
            runBlocking { client.block() }
        } finally {
            client.close()
        }
    }

    @Test
    fun `the handshake names the loop server and its tools capability`() =
        withClient {
            val result = initialize().getValue("result").jsonObject
            assertEquals(
                "loop",
                result
                    .getValue("serverInfo")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertTrue("tools" in result.getValue("capabilities").jsonObject, "$result")
        }

    @Test
    fun `submit_result is listed with the section 7 schema, status required`() =
        withClient {
            initialize()
            val tools =
                request("tools/list", "{}")
                    .getValue("result")
                    .jsonObject
                    .getValue("tools")
                    .jsonArray
            // m2-17: move and comment are listed beside it.
            val tool = tools.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "submit_result" }
            val schema = tool.getValue("inputSchema").jsonObject
            assertEquals("object", schema.getValue("type").jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("status"))), schema["required"])
            val properties = schema.getValue("properties").jsonObject
            assertEquals(
                listOf("status", "deliverable", "summary", "acceptance", "residual_human_steps", "followups", "notes_for_next_attempt"),
                properties.keys.toList(),
            )
            val statuses =
                properties
                    .getValue("status")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf("done", "blocked", "needs_info", "failed"), statuses)
            val deliverable =
                properties
                    .getValue("deliverable")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
            val types =
                deliverable
                    .getValue("type")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf("pull_request", "files", "report", "external_action"), types)
            val row =
                properties
                    .getValue("acceptance")
                    .jsonObject
                    .getValue("items")
                    .jsonObject
            val proofs =
                row
                    .getValue("properties")
                    .jsonObject
                    .getValue("proof")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf("check", "review", "human"), proofs)
        }

    @Test
    fun `a call hands its arguments to the handler as written and returns its reply`() {
        var received: JsonObject? = null
        val arguments = """{"status":"done","summary":"Wrote hello.txt.","acceptance":[{"criterion":"c","proof":"check","ref":null}]}"""
        withClient(
            SubmitResultHandler {
                received = it
                ToolReply("Queued your done result.")
            },
        ) {
            initialize()
            val result =
                request("tools/call", """{"name":"submit_result","arguments":$arguments}""")
                    .getValue("result")
                    .jsonObject
            assertEquals(Json.parseToJsonElement(arguments).jsonObject, received)
            val content =
                result
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("text", content.getValue("type").jsonPrimitive.content)
            assertEquals("Queued your done result.", content.getValue("text").jsonPrimitive.content)
            assertTrue(result["isError"]?.jsonPrimitive?.boolean != true, "$result")
        }
    }

    @Test
    fun `a refused call is a tool error with the handler's text`() =
        withClient(SubmitResultHandler { ToolReply("status is required. Nothing queued.", isError = true) }) {
            initialize()
            val result = request("tools/call", """{"name":"submit_result","arguments":{}}""").getValue("result").jsonObject
            assertEquals(true, result.getValue("isError").jsonPrimitive.boolean)
            val text =
                result
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            assertEquals("status is required. Nothing queued.", text)
        }

    @Test
    fun `a call without arguments hands an empty object to the handler`() {
        var received: JsonObject? = null
        withClient(
            SubmitResultHandler {
                received = it
                ToolReply("checked")
            },
        ) {
            initialize()
            val response = request("tools/call", """{"name":"submit_result"}""")
            assertNull(response["error"], "$response")
            assertEquals(JsonObject(emptyMap()), received)
        }
    }

    @Test
    fun `tools list returns submit_result, move and comment (m2-17)`() =
        withClient {
            initialize()
            val tools =
                request("tools/list", "{}")
                    .getValue("result")
                    .jsonObject
                    .getValue("tools")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("submit_result", "move", "comment"), tools.map { it.getValue("name").jsonPrimitive.content })

            fun schema(name: String) = tools.single { it.getValue("name").jsonPrimitive.content == name }.getValue("inputSchema").jsonObject

            fun propertyType(
                name: String,
                property: String,
            ) = schema(name)
                .getValue("properties")
                .jsonObject
                .getValue(property)
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
            assertEquals(JsonArray(listOf(JsonPrimitive("state"))), schema("move")["required"])
            assertEquals(
                listOf("state"),
                schema("move")
                    .getValue("properties")
                    .jsonObject.keys
                    .toList(),
            )
            assertEquals("string", propertyType("move", "state"))
            assertEquals(JsonArray(listOf(JsonPrimitive("text"))), schema("comment")["required"])
            assertEquals(
                listOf("text"),
                schema("comment")
                    .getValue("properties")
                    .jsonObject.keys
                    .toList(),
            )
            assertEquals("string", propertyType("comment", "text"))
            val move =
                tools
                    .single { it.getValue("name").jsonPrimitive.content == "move" }
                    .getValue("description")
                    .jsonPrimitive.content
            assertContains(move, "submit_result")
            assertContains(move, "needs_info")
        }

    @Test
    fun `a move call hands its state to the move handler and returns its reply (m2-17)`() {
        val received = mutableListOf<String>()
        withClient(
            move =
                MoveHandler {
                    received += it
                    ToolReply("Queued a move.")
                },
        ) {
            initialize()
            val result = request("tools/call", """{"name":"move","arguments":{"state":"needs_info"}}""").getValue("result").jsonObject
            assertEquals(listOf("needs_info"), received)
            assertEquals("Queued a move.", result.text())
            assertTrue(result["isError"]?.jsonPrimitive?.boolean != true, "$result")
        }
    }

    @Test
    fun `a comment call hands its text to the comment handler, a refusal is a tool error (m2-17)`() {
        val received = mutableListOf<String>()
        withClient(
            comment =
                CommentHandler {
                    received += it
                    ToolReply("the comment is empty. Nothing queued.", isError = true)
                },
        ) {
            initialize()
            val result = request("tools/call", """{"name":"comment","arguments":{"text":"  "}}""").getValue("result").jsonObject
            assertEquals(listOf("  "), received)
            assertEquals(true, result.getValue("isError").jsonPrimitive.boolean)
            assertEquals("the comment is empty. Nothing queued.", result.text())
        }
    }

    @Test
    fun `a move or comment call without its argument is a tool error and reaches no handler (m2-17)`() {
        var calls = 0
        withClient(
            move = MoveHandler { ToolReply("moved").also { calls++ } },
            comment = CommentHandler { ToolReply("commented").also { calls++ } },
        ) {
            initialize()
            val noState = request("tools/call", """{"name":"move","arguments":{}}""").getValue("result").jsonObject
            val numberState = request("tools/call", """{"name":"move","arguments":{"state":3}}""").getValue("result").jsonObject
            val noText = request("tools/call", """{"name":"comment"}""").getValue("result").jsonObject
            assertEquals(0, calls)
            listOf(noState, numberState).forEach {
                assertEquals(true, it.getValue("isError").jsonPrimitive.boolean, "$it")
                assertEquals("state is required, a string. Nothing queued.", it.text())
            }
            assertEquals(true, noText.getValue("isError").jsonPrimitive.boolean, "$noText")
            assertEquals("text is required, a string. Nothing queued.", noText.text())
        }
    }

    @Test
    fun `m3-14 every reply goes through the trimmer before the agent reads it, a refusal staying a tool error`() {
        val trimmed = mutableListOf<Pair<String, String>>()
        withClient(
            handler = SubmitResultHandler { ToolReply("the result is invalid: " + "x".repeat(500), isError = true) },
            move = MoveHandler { ToolReply("Queued a move.") },
            trimmer =
                ReplyTrimmer { tool, reply ->
                    trimmed += tool to reply.text
                    reply.copy(text = reply.text.take(20).trimEnd() + " (cut)")
                },
        ) {
            initialize()
            val submit = request("tools/call", """{"name":"submit_result","arguments":{}}""").getValue("result").jsonObject
            val move = request("tools/call", """{"name":"move","arguments":{"state":"needs_info"}}""").getValue("result").jsonObject
            val missing = request("tools/call", """{"name":"comment","arguments":{}}""").getValue("result").jsonObject

            assertEquals("the result is invali (cut)", submit.text())
            assertEquals(true, submit.getValue("isError").jsonPrimitive.boolean)
            assertEquals("Queued a move. (cut)", move.text())
            assertEquals("text is required, a (cut)", missing.text())
            assertEquals(true, missing.getValue("isError").jsonPrimitive.boolean)
            assertEquals(listOf("submit_result", "move", "comment"), trimmed.map { it.first })
            assertEquals("the result is invalid: " + "x".repeat(500), trimmed.first().second, "the trimmer gets the full text")
        }
    }

    /** A resource handler of the m4-25 tests: [calls] gets `request <name>` or `release <name>`. */
    private class Resources(
        val calls: MutableList<String> = mutableListOf(),
    ) : ResourceHandler {
        override val names = listOf("api35", "iphone-16")

        override suspend fun request(name: String): ToolReply = ToolReply("""{"status":"granted"}""").also { calls += "request $name" }

        override suspend fun release(name: String): ToolReply = ToolReply("""{"status":"released"}""").also { calls += "release $name" }
    }

    private suspend fun Client.tools(): List<JsonObject> =
        request("tools/list", "{}")
            .getValue("result")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun `m4-25 without resources the server lists no resource tool`() =
        withClient {
            initialize()
            assertEquals(listOf("submit_result", "move", "comment"), tools().map { it.getValue("name").jsonPrimitive.content })
            val call = request("tools/call", """{"name":"request_resource","arguments":{"name":"api35"}}""")
            val refused =
                call["error"] != null ||
                    call["result"]
                        ?.jsonObject
                        ?.get("isError")
                        ?.jsonPrimitive
                        ?.boolean == true
            assertTrue(refused, "an unlisted tool is refused: $call")
        }

    @Test
    fun `m4-25 with resources the server lists request_resource and release_resource with a required string name`() =
        withClient(resources = Resources()) {
            initialize()
            val tools = tools()
            assertEquals(
                listOf("submit_result", "move", "comment", "request_resource", "release_resource"),
                tools.map { it.getValue("name").jsonPrimitive.content },
            )
            for (name in listOf("request_resource", "release_resource")) {
                val tool = tools.single { it.getValue("name").jsonPrimitive.content == name }
                val schema = tool.getValue("inputSchema").jsonObject
                assertEquals("object", schema.getValue("type").jsonPrimitive.content)
                assertEquals(JsonArray(listOf(JsonPrimitive("name"))), schema["required"])
                val properties = schema.getValue("properties").jsonObject
                assertEquals(listOf("name"), properties.keys.toList())
                val property = properties.getValue("name").jsonObject
                assertEquals("string", property.getValue("type").jsonPrimitive.content)
                assertContains(property.getValue("description").jsonPrimitive.content, "api35, iphone-16")
            }
            val request =
                tools
                    .single { it.getValue("name").jsonPrimitive.content == "request_resource" }
                    .getValue("description")
                    .jsonPrimitive.content
            listOf("granted", "preparing", "queued", "call request_resource again", "release_resource").forEach {
                assertContains(request, it)
            }
        }

    @Test
    fun `m4-25 a resource call hands its name to the handler, one without a name reaches no handler`() {
        val resources = Resources()
        withClient(resources = resources) {
            initialize()
            val granted =
                request(
                    "tools/call",
                    """{"name":"request_resource","arguments":{"name":"api35"}}""",
                ).getValue("result").jsonObject
            val released =
                request(
                    "tools/call",
                    """{"name":"release_resource","arguments":{"name":"api35"}}""",
                ).getValue("result").jsonObject
            val missing = request("tools/call", """{"name":"request_resource","arguments":{}}""").getValue("result").jsonObject

            assertEquals(listOf("request api35", "release api35"), resources.calls)
            assertEquals("""{"status":"granted"}""", granted.text())
            assertEquals("""{"status":"released"}""", released.text())
            assertEquals(true, missing.getValue("isError").jsonPrimitive.boolean)
            assertEquals("name is required, a string. Nothing queued.", missing.text())
        }
    }

    private fun JsonObject.text(): String =
        getValue("content")
            .jsonArray
            .single()
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    @Test
    fun `the server returns when its input ends`() {
        val client = Client(SubmitResultHandler { ToolReply("ok") })
        runBlocking {
            val done = async(Dispatchers.IO) { client.serving.join(10_000) }
            client.initialize()
            client.close()
            done.await()
        }
        assertTrue(!client.serving.isAlive, "the server still runs after the end of its input")
    }
}
