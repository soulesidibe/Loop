package loop.runner.codex

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.schema.PinnedSchema
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * m3-22, 17.5 items 15 and 16 (tools 1): Loop's tools, `submit_result`, `move`, `comment` and, since m4-31, `ask`, are
 * the Codex thread's client tools. The thread start declares them, built from the attempt's tool config snapshot, so `move` accepts the
 * states of the workflow's tracker; a call is run through `loop tool mcp`, here a [FakeLoopTool], and its reply goes
 * back to the session. Since m4-51 a workflow with `resources:` adds `request_resource` and `release_resource`, whose
 * answers, those of m4-25, reach the session unchanged. Every message is checked against the pinned schema; the tool
 * list against `DynamicToolSpec`, which the pinned schema holds although only the experimental `thread/start` refers
 * to it.
 */
class CodexClientToolsTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val schema = PinnedSchema.load()

    @Test
    fun `17_5 item 15 the thread start carries submit_result, move and comment as client tools with valid schemas`() {
        val loop = FakeLoopTool.write(dir)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        val events = runAll(runner(server.command), attemptEnv(loop.launcher, snapshot("files", FILES_STATES)))

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start"), sent.map { it["method"]?.jsonPrimitive?.content })
        // Codex refuses thread/start.dynamicTools without the experimental API (codex 0.167.0, m3-22).
        assertEquals(
            "true",
            sent[0]
                .obj("params")
                .obj("capabilities")
                .getValue("experimentalApi")
                .jsonPrimitive.content,
        )
        val thread = sent[2]
        val tools =
            thread
                .obj("params")
                .getValue("dynamicTools")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(listOf("submit_result", "move", "comment", "ask"), tools.map { it.string("name") })
        tools.forEach { tool ->
            assertEquals(emptyList(), schema.problems("v2/DynamicToolSpec", tool), "$tool")
            assertEquals("function", tool.string("type"))
            assertTrue(tool.string("description").orEmpty().isNotBlank(), "$tool")
            val input = tool.obj("inputSchema")
            assertEquals("object", input.string("type"), "$tool")
            val properties = input.obj("properties").keys
            val required = input.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(required.isNotEmpty() && properties.containsAll(required), "$tool")
        }
        // The rest of the request is the stable thread/start.
        val stable = JsonObject(thread + ("params" to JsonObject(thread.obj("params") - "dynamicTools")))
        assertEquals(emptyList(), schema.problems("ClientRequest", stable))
        val (submit, move, comment, ask) = tools.map { it.obj("inputSchema") }
        assertEquals(
            listOf("status", "deliverable", "summary", "acceptance", "residual_human_steps", "followups", "notes_for_next_attempt"),
            submit.obj("properties").keys.toList(),
        )
        assertEquals(listOf("status"), submit.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("done", "blocked", "needs_info", "failed"),
            submit
                .obj("properties")
                .obj("status")
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals("string", move.obj("properties").obj("state").string("type"))
        assertEquals(listOf("state"), move.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", comment.obj("properties").obj("text").string("type"))
        assertEquals(listOf("text"), comment.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("kind", "question", "options", "recommendation"), ask.obj("properties").keys.toList())
        assertEquals(listOf("kind", "question"), ask.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, loop.runs(), "no call, so loop tool mcp never ran")
    }

    @Test
    fun `17_5 item 16 tools 1 with the files tracker, move accepts the files tracker's states`() {
        val tools = advertised(snapshot("files", FILES_STATES))

        val move = tools.single { it.string("name") == "move" }
        assertEquals(FILES_STATES, stateEnum(move))
        assertTrue("files tracker" in move.string("description").orEmpty(), "$move")
        assertTrue("files tracker" in stateProperty(move).string("description").orEmpty(), "$move")
        assertEquals(listOf("submit_result", "move", "comment", "ask"), tools.map { it.string("name") })
    }

    @Test
    fun `17_5 item 16 tools 1 with the github tracker, move accepts the github tracker's states`() {
        val tools = advertised(snapshot("github", GITHUB_STATES))

        val move = tools.single { it.string("name") == "move" }
        assertEquals(GITHUB_STATES, stateEnum(move))
        assertTrue("github tracker" in move.string("description").orEmpty(), "$move")
        assertTrue("files" !in move.toString() && "todo" !in move.toString(), "nothing of another tracker: $move")
        assertEquals(listOf("submit_result", "move", "comment", "ask"), tools.map { it.string("name") })
    }

    @Test
    fun `m3-22 outside an attempt Loop started no tool is declared and the stable protocol is kept`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command), env = emptyMap())

        val sent = received(server)
        assertEquals(
            "false",
            sent[0]
                .obj("params")
                .obj("capabilities")
                .getValue("experimentalApi")
                .jsonPrimitive.content,
        )
        assertNull(sent[2].obj("params")["dynamicTools"])
        sent.filter { it["id"] != null }.forEach { assertEquals(emptyList(), schema.problems("ClientRequest", it), "$it") }
    }

    @Test
    fun `m3-22 an unreadable tool config snapshot declares no tool and says so on stderr`() {
        val loop = FakeLoopTool.write(dir)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        val events = runAll(runner(server.command), attemptEnv(loop.launcher, dir.resolve("gone/tool-config.json")))

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertNull(received(server)[2].obj("params")["dynamicTools"])
        assertTrue(loopLines(events).any { "declares no Loop tool" in it && "tool config snapshot" in it }, "${loopLines(events)}")
    }

    @Test
    fun `17_5 item 16 a submit_result call runs through loop tool mcp and its reply answers the session`() {
        val reply = "Queued a done result for c-01. Loop applies it when this turn ends. Do not call submit_result again."
        val arguments =
            buildJsonObject {
                put("status", "done")
                put("summary", "The export works.")
            }

        val call = call("submit_result", arguments, FakeLoopTool.reply(reply))

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "submit_result", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
        assertTrue(
            loopLines(call.events).any { it == "loop: ran the Loop tool submit_result for the tool call request 7" },
            "${call.events}",
        )
        val env = call.loop.env.readLines()
        assertTrue("LOOP_SESSION_ID=${FakeAppServer.THREAD_ID}-${FakeAppServer.TURN_ID}" in env, "$env")
        assertTrue("LOOP_TURN_ID=${FakeAppServer.TURN_ID}" in env, "$env")
        assertTrue("LOOP_ATTEMPT_ID=attempt-1" in env && env.any { it.startsWith("LOOP_TOOL_CONFIG=") }, "$env")
    }

    @Test
    fun `17_5 item 16 a move call runs through loop tool mcp and its reply answers the session`() {
        val reply = "Queued a move of c-01 to \"needs_info\". Loop applies this in the tracker when this turn ends. Do not run it again."
        val arguments = buildJsonObject { put("state", "needs_info") }

        val call = call("move", arguments, FakeLoopTool.reply(reply))

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "move", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `17_5 item 16 a comment call runs through loop tool mcp and its reply answers the session`() {
        val reply = "Queued a comment on c-01 (34 characters). Loop applies this in the tracker when this turn ends. Do not run it again."
        val arguments = buildJsonObject { put("text", "Should the export include archived?") }

        val call = call("comment", arguments, FakeLoopTool.reply(reply))

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "comment", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m4-31 ask has kind and question required, options a list of strings, and says it is only for a human`() {
        val ask = advertised(snapshot("files", FILES_STATES)).single { it.string("name") == "ask" }

        assertEquals(emptyList(), schema.problems("v2/DynamicToolSpec", ask), "$ask")
        val properties = ask.obj("inputSchema").obj("properties")
        assertEquals(
            listOf("engineering", "design", "product", "human"),
            properties.obj("kind").getValue("enum").jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        assertEquals("string", properties.obj("question").string("type"))
        assertEquals("array", properties.obj("options").string("type"))
        assertEquals("string", properties.obj("options").obj("items").string("type"))
        assertEquals("string", properties.obj("recommendation").string("type"))
        val description = ask.string("description").orEmpty()
        assertTrue("only for a question a human must answer" in description, description)
        assertTrue("Technical choices are your own" in description, description)
    }

    @Test
    fun `m4-31 an ask call runs through loop tool mcp and its pending answer reaches the turn unchanged`() {
        val reply = """{"status":"pending","message":"End your turn now."}"""
        val arguments =
            buildJsonObject {
                put("kind", "product")
                put("question", "Should the export include archived items?")
            }

        val call = call("ask", arguments, FakeLoopTool.reply(reply))

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "ask", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `17_5 item 16 a call loop tool mcp refuses gets a failure result and the turn goes on`() {
        val refusal =
            "loop tool mcp: \"shipped\" is not a state of the files tracker. Valid states: todo, review. Nothing queued."

        val call = call("move", buildJsonObject { put("state", "shipped") }, FakeLoopTool.reply(refusal, isError = true))

        assertEquals(failure(refusal), call.answer)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
        assertEquals(1, call.events.count(::isTerminal), "${call.events}")
        assertTrue(loopLines(call.events).any { "ran the Loop tool move for the tool call request 7, refused" in it }, "${call.events}")
    }

    @Test
    fun `m3-22 a call whose arguments are not an object gets a failure result without running loop tool mcp`() {
        val call = call("move", JsonPrimitive("review"))

        val text = text(call.answer)
        assertEquals(failure(text), call.answer)
        assertTrue("arguments of move must be a JSON object" in text && "Nothing queued." in text, text)
        assertEquals(0, call.loop.runs())
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m3-22 a call to a tool Loop does not declare gets a failure result naming the tools it serves`() {
        val call = call("deploy_to_production", buildJsonObject { put("force", true) })

        val text = text(call.answer)
        assertEquals(failure(text), call.answer)
        assertTrue("deploy_to_production" in text && "submit_result, move, comment and ask" in text, text)
        assertEquals(0, call.loop.runs())
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m3-22 a loop tool mcp that exits before answering gives a failure result with its refusal, and the turn goes on`() {
        val refusal = "loop tool mcp: run this inside an attempt Loop started. Nothing queued."
        val loop = FakeLoopTool.write(dir, exitCode = 4, stderr = refusal + "\n")

        val call = call("comment", buildJsonObject { put("text", "hello") }, loop = loop)

        val text = text(call.answer)
        assertEquals(failure(text), call.answer)
        assertTrue("exited 4 before answering" in text && refusal in text, text)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m4-51 with resources the thread start carries request_resource and release_resource after Loop's four tools`() {
        val tools = advertised(snapshot("files", FILES_STATES, resources = listOf("pixel-7", "iphone-16")))

        assertEquals(
            listOf("submit_result", "move", "comment", "ask", "request_resource", "release_resource"),
            tools.map { it.string("name") },
        )
        tools.drop(4).forEach { tool ->
            assertEquals("function", tool.string("type"))
            assertTrue(tool.string("description").orEmpty().isNotBlank(), "$tool")
            val input = tool.obj("inputSchema")
            assertEquals("object", input.string("type"), "$tool")
            assertEquals(listOf("name"), input.obj("properties").keys.toList(), "$tool")
            assertEquals(listOf("name"), input.getValue("required").jsonArray.map { it.jsonPrimitive.content }, "$tool")
            val name = input.obj("properties").obj("name")
            assertEquals("string", name.string("type"), "$tool")
            // No enum: an unknown name reaches loop tool mcp, which answers it with its structured failure (m4-25).
            assertNull(name["enum"], "$tool")
            assertEquals("The name of a resource of the workflow: pixel-7, iphone-16.", name.string("description"))
        }
        val (request, release) = tools.drop(4).map { it.string("description").orEmpty() }
        assertTrue("status granted" in request && "status queued" in request && "position" in request, request)
        assertTrue("release_resource" in request, request)
        assertTrue("Give back a resource" in release, release)
    }

    @Test
    fun `m4-51 without resources the thread start carries no resource tool`() {
        val absent = advertised(snapshot("files", FILES_STATES))
        val empty = advertised(snapshot("files", FILES_STATES, resources = emptyList()))

        assertEquals(listOf("submit_result", "move", "comment", "ask"), absent.map { it.string("name") })
        assertEquals(listOf("submit_result", "move", "comment", "ask"), empty.map { it.string("name") })
    }

    @Test
    fun `m4-51 a snapshot whose resources are not a list of ids declares no tool and says so on stderr`() {
        val loop = FakeLoopTool.write(dir)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }
        val config = dir.resolve("tool-config-bad.json")
        config.writeText(snapshotJson("files", FILES_STATES).let { JsonObject(it + ("resources" to JsonPrimitive("pixel-7"))) }.toString())

        val events = runAll(runner(server.command), attemptEnv(loop.launcher, config))

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertNull(received(server)[2].obj("params")["dynamicTools"])
        assertTrue(loopLines(events).any { "declares no Loop tool" in it && "resources" in it }, "${loopLines(events)}")
    }

    @Test
    fun `m4-51 a request_resource call runs through loop tool mcp and its granted answer reaches the turn unchanged`() {
        val reply =
            """{"status":"granted","resource":"pixel-7","kind":"android-device","prepared":{"serial":"emulator-5554"},""" +
                """"can":["ui tests"],"cannot":["push"],"message":"Granted pixel-7, ready: it is yours until you call """ +
                """release_resource or your attempt ends."}"""
        val arguments = buildJsonObject { put("name", "pixel-7") }

        val call = call("request_resource", arguments, FakeLoopTool.reply(reply), config = resourcesSnapshot())

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "request_resource", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
        assertTrue(
            loopLines(call.events).any { it == "loop: ran the Loop tool request_resource for the tool call request 7" },
            "${call.events}",
        )
    }

    @Test
    fun `m4-51 a queued answer reaches the turn unchanged, with its position`() {
        val reply =
            """{"status":"queued","resource":"pixel-7","kind":"android-device","position":2,"message":"Queued at position 2: """ +
                """another attempt holds pixel-7. Call request_resource with the same name again later to check; your place is kept."}"""

        val arguments = buildJsonObject { put("name", "pixel-7") }

        val call = call("request_resource", arguments, FakeLoopTool.reply(reply), config = resourcesSnapshot())

        assertEquals(success(reply), call.answer)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m4-51 a structured failure reaches the turn unchanged as a failure result, and the turn goes on`() {
        val reply =
            """{"status":"error","error":"unknown_resource","message":"\"pixel-9\" is not a resource of the workflow. """ +
                """The resources are: pixel-7, iphone-16."}"""
        val arguments = buildJsonObject { put("name", "pixel-9") }

        val call = call("request_resource", arguments, FakeLoopTool.reply(reply, isError = true), config = resourcesSnapshot())

        assertEquals(failure(reply), call.answer)
        assertMcpCall(call.loop, "request_resource", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
        assertEquals(1, call.events.count(::isTerminal), "${call.events}")
        assertTrue(
            loopLines(call.events).any { "ran the Loop tool request_resource for the tool call request 7, refused" in it },
            "${call.events}",
        )
    }

    @Test
    fun `m4-51 a release_resource call runs through loop tool mcp and its released answer reaches the turn unchanged`() {
        val reply =
            """{"status":"released","resource":"pixel-7","kind":"android-device","message":"Released pixel-7. Stop using it now: """ +
                """Loop gives it back and grants it to the next attempt in its queue."}"""
        val arguments = buildJsonObject { put("name", "pixel-7") }

        val call = call("release_resource", arguments, FakeLoopTool.reply(reply), config = resourcesSnapshot())

        assertEquals(success(reply), call.answer)
        assertMcpCall(call.loop, "release_resource", arguments)
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    @Test
    fun `m4-51 without resources a request_resource call gets a failure result without running loop tool mcp`() {
        val call = call("request_resource", buildJsonObject { put("name", "pixel-7") })

        val text = text(call.answer)
        assertEquals(failure(text), call.answer)
        assertTrue("request_resource" in text && "submit_result, move, comment and ask" in text, text)
        assertEquals(0, call.loop.runs())
        assertIs<RunnerEvent.TurnCompleted>(call.events.last(), "${call.events}")
    }

    // Helpers.

    private class Call(
        val events: List<RunnerEvent>,
        val answer: JsonObject,
        val loop: FakeLoopTool,
    )

    /** Runs a turn whose server calls the client tool [tool] with [arguments] as request 7; the fake answers with [reply]. */
    private fun call(
        tool: String,
        arguments: JsonElement,
        reply: String = FakeLoopTool.reply("Queued."),
        loop: FakeLoopTool = FakeLoopTool.write(dir, reply),
        config: Path = snapshot("files", FILES_STATES),
    ): Call {
        val params =
            buildJsonObject {
                put("callId", "call-1")
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("tool", tool)
                put("arguments", arguments)
            }
        val request = CodexJson.format.parseToJsonElement(FakeAppServer.serverRequestLine(7, "item/tool/call", params))
        assertEquals(emptyList(), schema.problems("ServerRequest", request), "the fake's request is not valid")
        val server =
            FakeAppServer.write(dir) {
                handshake()
                serverRequest(7, "item/tool/call", params)
                awaitClient()
                turnCompleted()
            }

        val events = runAll(runner(server.command), attemptEnv(loop.launcher, config))

        val answer = received(server).single { it["id"]?.jsonPrimitive?.content == "7" }.obj("result")
        assertEquals(emptyList(), schema.problems("DynamicToolCallResponse", answer), "$answer")
        return Call(events, answer, loop)
    }

    /** Checks that [loop] ran once as `tool mcp`, was initialized, and got one `tools/call` of [tool] with [arguments]. */
    private fun assertMcpCall(
        loop: FakeLoopTool,
        tool: String,
        arguments: JsonObject,
    ) {
        assertEquals(listOf("tool mcp"), loop.argv.readLines())
        val lines = loop.receivedLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), lines.map { it.string("method") })
        assertEquals(listOf("1", null, "2"), lines.map { it["id"]?.jsonPrimitive?.content })
        lines.forEach { assertEquals("2.0", it.string("jsonrpc")) }
        val params = lines[2].obj("params")
        assertEquals(tool, params.string("name"))
        assertEquals(arguments, params.getValue("arguments"))
    }

    /** The client tools a thread start declares with the tool config snapshot [config]. */
    private fun advertised(config: Path): List<JsonObject> {
        val loop = FakeLoopTool.write(dir)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }
        runAll(runner(server.command), attemptEnv(loop.launcher, config))
        val tools =
            received(server)[2]
                .obj("params")
                .getValue("dynamicTools")
                .jsonArray
                .map { it.jsonObject }
        tools.forEach { assertEquals(emptyList(), schema.problems("v2/DynamicToolSpec", it), "$it") }
        return tools
    }

    private fun stateProperty(move: JsonObject): JsonObject = move.obj("inputSchema").obj("properties").obj("state")

    private fun stateEnum(move: JsonObject): List<String> = stateProperty(move).getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    /**
     * Writes a tool config snapshot of a [kind] tracker with [states], as `loop run` writes one (m1-23), with
     * [resources], the ids of `resources:` (m4-25), when given.
     */
    private fun snapshot(
        kind: String,
        states: List<String>,
        resources: List<String>? = null,
    ): Path {
        val file = dir.resolve("tool-config-$kind-${resources?.size}.json")
        val json = snapshotJson(kind, states)
        val entries =
            resources?.map { id ->
                buildJsonObject {
                    put("kind", "android-device")
                    put("id", id)
                    put("can", JsonArray(listOf(JsonPrimitive("ui tests"))))
                    put("cannot", JsonArray(listOf(JsonPrimitive("push"))))
                }
            }
        file.writeText((if (entries == null) json else JsonObject(json + ("resources" to JsonArray(entries)))).toString())
        return file
    }

    private fun snapshotJson(
        kind: String,
        states: List<String>,
    ): JsonObject =
        buildJsonObject {
            put("version", 1)
            put("project_id", "project-1")
            put("workflow_dir", dir.toString())
            put("tracker_kind", kind)
            put("defined_states", JsonArray(states.map(::JsonPrimitive)))
            putJsonObject("provider") {}
        }

    private fun resourcesSnapshot(): Path = snapshot("files", FILES_STATES, resources = listOf("pixel-7", "iphone-16"))

    private fun attemptEnv(
        launcher: Path,
        config: Path,
    ): Map<String, String> =
        mapOf(
            "LOOP_LAUNCHER" to launcher.toString(),
            "LOOP_TOOL_CONFIG" to config.toString(),
            "LOOP_ATTEMPT_ID" to "attempt-1",
            "LOOP_ISSUE_ID" to "issue-1",
            "LOOP_ISSUE_IDENTIFIER" to "c-01",
            "LOOP_HOME" to home.resolve("loop-home").toString(),
        )

    private fun success(text: String): JsonObject = toolResult(true, text)

    private fun failure(text: String): JsonObject = toolResult(false, text)

    private fun toolResult(
        success: Boolean,
        text: String,
    ): JsonObject =
        CodexJson.format
            .parseToJsonElement(
                """{"success":$success,"contentItems":[{"type":"inputText","text":${JsonPrimitive(text)}}]}""",
            ).jsonObject

    private fun text(answer: JsonObject): String =
        answer
            .getValue("contentItems")
            .jsonArray
            .single()
            .jsonObject
            .string("text")
            .orEmpty()

    private fun loopLines(events: List<RunnerEvent>): List<String> =
        events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stderr && it.line.startsWith("loop: ") }.map { it.line }

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

    private fun runner(command: String): CodexAppServerRunner =
        CodexAppServerRunner(
            CodexAppServerSettings(command, turnTimeout = 10.seconds, readTimeout = FakeAppServer.READ_TIMEOUT, strictMcp = false),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = ProcessGroupLauncher()::launch,
            loopVersion = "test-build",
        )

    private fun runAll(
        runner: CodexAppServerRunner,
        env: Map<String, String>,
    ): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(env), "Work on c-01").toList() } }
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun context(env: Map<String, String>): AttemptContext =
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
            env = env,
        )

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** The `files` tracker's defined states. */
        val FILES_STATES = listOf("todo", "in_progress", "review", "needs_info", "done", "canceled")

        /** The `github` tracker's defined states: GitHub's two, then the two handoff states (m2-23). */
        val GITHUB_STATES = listOf("open", "closed", "review", "needs_info")
    }
}
