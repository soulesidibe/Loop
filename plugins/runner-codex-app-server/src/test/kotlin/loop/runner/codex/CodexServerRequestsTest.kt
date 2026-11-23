package loop.runner.codex

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * m3-20, 17.5 items 10 to 12 and the Loop mode guards on Codex: the runner answers every server request of a turn by
 * rule, at once, so no approval, tool call or user input leaves the turn stalled. Each server is a [FakeAppServer]
 * that writes a request, waits for the client's line and then completes the turn, so a request left unanswered fails
 * the turn with `turn_timeout` instead of completing it. Every request a fake writes and every answer Loop writes is
 * checked against the pinned schema.
 */
class CodexServerRequestsTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val schema = PinnedSchema.load()

    private val holdout: Path by lazy {
        dir.resolve("loop-home/holdout").createDirectories().also { it.resolve("check.sh").writeText("exit 0\n") }
    }

    @Test
    fun `17_5 item 10 a command approval request is accepted by rule and the turn goes on`() {
        val (events, answer) = commandApproval("./gradlew :plugins:runner-codex-app-server:test")

        assertEquals(decision("accept"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(emptyList(), schema.problems("CommandExecutionRequestApprovalResponse", answer))
    }

    @Test
    fun `17_5 item 10 m3-20 a git push approval request is declined and the turn goes on`() {
        val (events, answer) = commandApproval("git add -A && git commit -m wip && git push origin loop/c-01")

        assertEquals(decision("decline"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertTrue(loopLines(events).any { "declined the command approval request 7" in it && "denied command" in it }, "$events")
        assertEquals(emptyList(), schema.problems("CommandExecutionRequestApprovalResponse", answer))
    }

    @Test
    fun `m3-20 a gh command approval request is declined`() {
        val (events, answer) = commandApproval("bash -lc 'gh pr create --fill'")

        assertEquals(decision("decline"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
    }

    @Test
    fun `m3-20 a history rewrite approval request is declined`() {
        listOf("git rebase -i HEAD~3", "git reset --hard origin/main", "git commit --amend --no-edit").forEach { command ->
            val (events, answer) = commandApproval(command, subdir = command.substringAfter("git ").substringBefore(' '))

            assertEquals(decision("decline"), answer, command)
            assertIs<RunnerEvent.TurnCompleted>(events.last(), "$command: $events")
        }
    }

    @Test
    fun `m3-20 loop approve and loop resume approval requests are declined, by name or launcher path`() {
        listOf("loop approve c-01", "/opt/loop/bin/loop resume c-01").forEach { command ->
            val (_, answer) = commandApproval(command, subdir = command.substringAfterLast("loop ").substringBefore(' '))

            assertEquals(decision("decline"), answer, command)
        }
    }

    @Test
    fun `m3-20 a read of the holdout folder through a command approval request is declined`() {
        val (events, answer) = commandApproval("python3 -c \"print(open('$holdout/check.sh').read())\"", holdoutDir = holdout)

        assertEquals(decision("decline"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val line = loopLines(events).single { "declined the command approval request" in it }
        assertTrue("holdout folder" in line, line)
        assertTrue(holdout.toString() !in line, "the log line never names the folder: $line")
    }

    @Test
    fun `m3-20 a read of the holdout folder by a relative path, through home, or by a parsed read action is declined`() {
        val relative = workspace.relativize(holdout).toString()
        val inHome = home.resolve("loop-home/holdout").createDirectories()
        listOf(
            Triple("sh -c 'cat $relative/check.sh'", holdout, null),
            Triple("python3 read.py ~/loop-home/holdout/check.sh", inHome, null),
            Triple("python3 read.py", holdout, readAction("$holdout/check.sh")),
        ).forEachIndexed { index, (command, folder, action) ->
            val (_, answer) = commandApproval(command, holdoutDir = folder, subdir = "case-$index", action = action)

            assertEquals(decision("decline"), answer, command)
        }
    }

    @Test
    fun `m3-20 a command approval request with no command is declined, even with a parsed action`() {
        val unknownAction =
            buildJsonObject {
                put("type", "unknown")
                put("command", "./gradlew build")
            }
        listOf(null to null, "  " to null, null to unknownAction).forEachIndexed { index, (command, action) ->
            val (events, answer) = commandApproval(command, subdir = "no-command-$index", action = action)

            assertEquals(decision("decline"), answer, "case $index")
            assertIs<RunnerEvent.TurnCompleted>(events.last(), "case $index: $events")
            assertTrue(loopLines(events).any { "declined the command approval request 7" in it && "no command" in it }, "$events")
        }
    }

    @Test
    fun `m3-20 a command approval request of a kind other than a plain command is declined`() {
        listOf("writeStdin", "humanCommand").forEach { kind ->
            val (events, answer) = commandApproval("./gradlew build", subdir = "kind-$kind", kind = kind)

            assertEquals(decision("decline"), answer, kind)
            assertIs<RunnerEvent.TurnCompleted>(events.last(), "$kind: $events")
            assertTrue(loopLines(events).any { "declined the command approval request 7" in it && "kind" in it }, "$events")
        }
        // The schema's default kind, written out, is a plain command.
        val (_, plain) = commandApproval("./gradlew build", subdir = "kind-command", kind = "command")
        assertEquals(decision("accept"), plain)
    }

    @Test
    fun `m3-20 a legacy exec approval request with an empty command is denied`() {
        val answer = legacy("execCommandApproval", execParams(emptyList()), "empty")

        assertEquals("denied", (answer["decision"] as? JsonObject)?.keys?.single(), "$answer")
    }

    @Test
    fun `m3-20 a command naming the holdout folder is accepted when the host gave no holdout folder`() {
        val (_, answer) = commandApproval("python3 -c \"print(open('$holdout/check.sh').read())\"", holdoutDir = null)

        assertEquals(decision("accept"), answer)
    }

    @Test
    fun `17_5 item 10 a file change approval request inside the workspace is accepted by rule`() {
        val (events, answer) = fileChangeApproval(listOf(workspace.resolve("src/Main.kt").toString(), "README.md"))

        assertEquals(decision("accept"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(emptyList(), schema.problems("FileChangeRequestApprovalResponse", answer))
    }

    @Test
    fun `17_5 item 10 m3-20 an edit under the holdout folder is declined and the turn goes on`() {
        val (events, answer) = fileChangeApproval(listOf("README.md", "$holdout/check.sh"), holdoutDir = holdout)

        assertEquals(decision("decline"), answer)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertTrue(loopLines(events).any { "declined the file change approval request 7" in it && "holdout folder" in it }, "$events")
        assertEquals(emptyList(), schema.problems("FileChangeRequestApprovalResponse", answer))
    }

    @Test
    fun `m3-20 a move into the holdout folder, a patch update into it, or a grant of its root is declined`() {
        val moved = fileChangeApproval(listOf("README.md"), holdoutDir = holdout, movePath = "$holdout/check.sh", subdir = "move")
        val updated = fileChangeApproval(listOf("README.md"), holdoutDir = holdout, patchUpdate = "$holdout/new.sh", subdir = "update")
        val granted = fileChangeApproval(listOf("README.md"), holdoutDir = holdout, grantRoot = holdout.toString(), subdir = "grant")

        assertEquals(decision("decline"), moved.second)
        assertEquals(decision("decline"), updated.second)
        assertEquals(decision("decline"), granted.second)
    }

    @Test
    fun `m3-20 a file change approval request for an item whose changes were never seen is declined`() {
        val (_, answer) = fileChangeApproval(changes = null, holdoutDir = holdout)

        assertEquals(decision("decline"), answer)
    }

    @Test
    fun `m3-20 the legacy exec and patch approvals follow the same rules with approved and denied`() {
        val push = legacy("execCommandApproval", execParams(listOf("git", "push")), "push")
        val build = legacy("execCommandApproval", execParams(listOf("./gradlew", "build")), "build")
        val patch =
            legacy(
                "applyPatchApproval",
                buildJsonObject {
                    put("callId", "call-1")
                    put("conversationId", FakeAppServer.THREAD_ID)
                    putJsonObject("fileChanges") {
                        putJsonObject("$holdout/check.sh") {
                            put("type", "update")
                            put("unified_diff", "@@ -1 +1 @@\n-exit 0\n+exit 1\n")
                        }
                    }
                },
                "patch",
            )

        fun denied(reason: String) =
            buildJsonObject {
                putJsonObject(
                    "decision",
                ) { putJsonObject("denied") { put("rejection", "Loop refused this request: $reason.") } }
            }
        assertEquals(denied("it runs a denied command (git push, gh, a history rewrite or an operator command)"), push)
        assertEquals(decision("approved"), build)
        assertEquals(denied("it touches the holdout folder"), patch)
        listOf(push, build, patch).forEach { assertEquals(emptyList(), schema.problems("ExecCommandApprovalResponse", it)) }
    }

    @Test
    fun `17_5 item 11 an unsupported dynamic tool call gets a failure response and the turn completes`() {
        val params =
            buildJsonObject {
                put("callId", "call-1")
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("tool", "deploy_to_production")
                putJsonObject("arguments") { put("force", true) }
            }
        val (events, answer) = request("item/tool/call", params)

        assertEquals(
            false,
            answer
                .getValue("success")
                .jsonPrimitive.content
                .toBooleanStrict(),
        )
        val text =
            answer
                .getValue("contentItems")
                .let { it as JsonArray }
                .single()
                .jsonObject
        assertEquals("inputText", text.getValue("type").jsonPrimitive.content)
        assertTrue("deploy_to_production" in text.getValue("text").jsonPrimitive.content, "$text")
        assertEquals(emptyList(), schema.problems("DynamicToolCallResponse", answer))
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(1, events.count(::isTerminal), "$events")
        assertTrue(loopLines(events).any { "unsupported dynamic tool call" in it }, "$events")
    }

    @Test
    fun `17_5 item 12 a user input request ends the turn with input_required as its only terminal event`() {
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("isBlocking", true)
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "q1")
                                put("header", "Branch")
                                put("question", "Which branch should I push to?")
                            },
                        )
                    },
                )
            }
        val server =
            FakeAppServer.write(dir.resolve("input")) {
                handshake()
                serverRequest(7, "item/tool/requestUserInput", params)
                awaitClient()
                turnCompleted()
            }
        assertEquals(emptyList(), schema.problems("ServerRequest", serverRequest(7, "item/tool/requestUserInput", params)))

        val events = runAll(runner(server.command))

        val ended = assertIs<RunnerEvent.TurnInputRequired>(events.last(), "$events")
        assertEquals("item/tool/requestUserInput", ended.reason)
        assertEquals(1, events.count(::isTerminal), "$events")
        // No answer was written: the request is not answered, the turn ends and the app server is stopped.
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start"), received(server).map { it.string("method") })
    }

    @Test
    fun `m3-20 a permissions request is granted nothing, an elicitation declined and any other request refused, never stalled`() {
        val permissions =
            request(
                "item/permissions/requestApproval",
                buildJsonObject {
                    put("itemId", "item-1")
                    put("threadId", FakeAppServer.THREAD_ID)
                    put("turnId", FakeAppServer.TURN_ID)
                    put("startedAtMs", 1L)
                    put("cwd", workspace.toString())
                    putJsonObject("permissions") { put("network", JsonNull) }
                },
                subdir = "permissions",
            )
        val elicitation =
            request(
                "mcpServer/elicitation/request",
                buildJsonObject {
                    put("serverName", "operator_server")
                    put("threadId", FakeAppServer.THREAD_ID)
                    put("mode", "url")
                    put("elicitationId", "e-1")
                    put("message", "Sign in")
                    put("url", "https://example.com/login")
                },
                subdir = "elicitation",
            )
        val attestation = request("attestation/generate", buildJsonObject { }, subdir = "attestation", error = true)

        assertEquals(buildJsonObject { putJsonObject("permissions") { } }, permissions.second)
        assertEquals(emptyList(), schema.problems("PermissionsRequestApprovalResponse", permissions.second))
        assertEquals(buildJsonObject { put("action", "decline") }, elicitation.second)
        assertEquals(emptyList(), schema.problems("McpServerElicitationRequestResponse", elicitation.second))
        assertEquals(
            -32601,
            attestation.second
                .getValue("code")
                .jsonPrimitive.content
                .toInt(),
        )
        listOf(permissions, elicitation, attestation).forEach { (events, _) ->
            assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        }
    }

    @Test
    fun `m3-20 strict MCP, the default, disables every MCP server config read lists and the apps feature on thread start`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                turnCompleted()
            }

        val events = runAll(runner(server.command, strictMcp = true))

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "config/read", "thread/start", "turn/start"), sent.map { it.string("method") })
        assertEquals(listOf("1", null, "2", "3", "4"), sent.map { it["id"]?.jsonPrimitive?.content })
        assertEquals(workspace.toAbsolutePath().toString(), sent[2].obj("params").string("cwd"))
        val config = sent[3].obj("params").obj("config")
        assertEquals(
            CodexJson.format.parseToJsonElement(
                """{"allow_login_shell":false,"mcp_servers":{"operator_server":{"enabled":false}},"features":{"apps":false}}""",
            ),
            config,
        )
        sent.filter { it["id"] != null }.forEach { assertEquals(emptyList(), schema.problems("ClientRequest", it), "$it") }
        assertEquals(emptyList(), schema.problems("v2/ConfigReadResponse", FakeAppServer.configReadResult()))
        assertTrue(CodexAppServerSettings().strictMcp, "strict MCP is the default")
    }

    @Test
    fun `m3-20 strict MCP with no MCP server in the config still turns the apps feature off`() {
        val noServers =
            FakeAppServer.configReadResult().jsonObject.let { result ->
                val config = result.getValue("config").jsonObject
                JsonObject(result + ("config" to JsonObject(config - "mcp_servers")))
            }
        val server =
            FakeAppServer.write(dir) {
                strictHandshake(config = noServers)
                turnCompleted()
            }

        runAll(runner(server.command, strictMcp = true))

        val config = received(server)[3].obj("params").obj("config")
        assertEquals(
            CodexJson.format.parseToJsonElement("""{"allow_login_shell":false,"mcp_servers":{},"features":{"apps":false}}"""),
            config,
        )
    }

    @Test
    fun `m3-20 strict MCP refuses to start when config read fails, rather than run with the operator's servers`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(1, FakeAppServer.initializeResult())
                awaitClient()
                awaitClient()
                error(2, -32600, "config/read is not available")
            }

        val events = runAll(runner(server.command, strictMcp = true))

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last(), "$events")
        assertEquals("response_error: config/read failed with code -32600: config/read is not available", failed.reason)
        assertEquals(listOf("initialize", "initialized", "config/read"), received(server).map { it.string("method") })
    }

    /**
     * The name is kept from attempt 1 (the deleted_tests guard counts a rename as a deletion). Since send back 2 of
     * pull request #104, thread start always carries `allow_login_shell: false`, so with strict MCP off its `config`
     * holds that key alone, with no MCP server and no feature.
     */
    @Test
    fun `m3-20 with strict MCP off there is no config read and thread start carries no config`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command, strictMcp = false))

        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start"), sent.map { it.string("method") })
        assertEquals(CodexJson.format.parseToJsonElement("""{"allow_login_shell":false}"""), sent[2].obj("params")["config"])
    }

    @Test
    fun `m3-20 every thread start turns the login shell off, so a profile export never reaches an agent command`() {
        val threadConfigs =
            listOf(true, false).map { strict ->
                val server =
                    FakeAppServer.write(dir.resolve("strict-$strict")) {
                        if (strict) strictHandshake() else handshake()
                        turnCompleted()
                    }

                runAll(runner(server.command, strictMcp = strict))

                val thread = received(server).single { it.string("method") == "thread/start" }
                assertEquals(emptyList(), schema.problems("ClientRequest", thread), "$thread")
                thread.obj("params").obj("config")
            }

        threadConfigs.forEach { config -> assertEquals(JsonPrimitive(false), config["allow_login_shell"], "$config") }
    }

    @Test
    fun `m3-20 thread start and turn start ask Codex for approvals with the untrusted policy`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command))

        val (_, _, thread, turn) = received(server)
        assertEquals("untrusted", thread.obj("params").string("approvalPolicy"))
        assertEquals("untrusted", turn.obj("params").string("approvalPolicy"))
    }

    // Helpers.

    private fun decision(value: String): JsonObject = buildJsonObject { put("decision", value) }

    private fun readAction(path: String): JsonObject =
        buildJsonObject {
            put("type", "read")
            put("command", "python3 read.py")
            put("name", "check.sh")
            put("path", path)
        }

    /** Runs a turn whose server asks to approve [command]; the turn's events and Loop's answer. */
    private fun commandApproval(
        command: String?,
        holdoutDir: Path? = null,
        subdir: String = "command",
        action: JsonObject? = null,
        kind: String? = null,
    ): Pair<List<RunnerEvent>, JsonObject> {
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("startedAtMs", 1L)
                put("command", command?.let { JsonPrimitive(it) } ?: JsonNull)
                if (kind != null) put("kind", kind)
                put("cwd", workspace.toString())
                if (action != null) put("commandActions", buildJsonArray { add(action) })
            }
        return request("item/commandExecution/requestApproval", params, holdoutDir, subdir)
    }

    /**
     * Runs a turn whose server starts a file change item with [changes] (none when null), then asks to approve it;
     * [movePath] moves the first change, [patchUpdate] adds a change in an `item/fileChange/patchUpdated`.
     */
    private fun fileChangeApproval(
        changes: List<String>?,
        holdoutDir: Path? = null,
        movePath: String? = null,
        patchUpdate: String? = null,
        grantRoot: String? = null,
        subdir: String = "file-change",
    ): Pair<List<RunnerEvent>, JsonObject> {
        fun change(
            path: String,
            move: String?,
        ) = buildJsonObject {
            put("path", path)
            put("diff", "@@ -1 +1 @@\n-a\n+b\n")
            putJsonObject("kind") {
                put("type", "update")
                put("move_path", move?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        val started =
            changes?.let {
                buildJsonObject {
                    put("method", "item/started")
                    putJsonObject("params") {
                        put("threadId", FakeAppServer.THREAD_ID)
                        put("turnId", FakeAppServer.TURN_ID)
                        put("startedAtMs", 1L)
                        putJsonObject("item") {
                            put("type", "fileChange")
                            put("id", "item-1")
                            put("status", "inProgress")
                            put("changes", JsonArray(it.mapIndexed { i, path -> change(path, movePath.takeIf { i == 0 }) }))
                        }
                    }
                }
            }
        val updated =
            patchUpdate?.let {
                buildJsonObject {
                    put("method", "item/fileChange/patchUpdated")
                    putJsonObject("params") {
                        put("threadId", FakeAppServer.THREAD_ID)
                        put("turnId", FakeAppServer.TURN_ID)
                        put("itemId", "item-1")
                        put("changes", buildJsonArray { add(change(it, null)) })
                    }
                }
            }
        listOfNotNull(started, updated).forEach { assertEquals(emptyList(), schema.problems("ServerNotification", it), "$it") }
        val params =
            buildJsonObject {
                put("itemId", "item-1")
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("startedAtMs", 1L)
                if (grantRoot != null) put("grantRoot", grantRoot)
            }
        return request("item/fileChange/requestApproval", params, holdoutDir, subdir, before = listOfNotNull(started, updated))
    }

    private fun execParams(command: List<String>): JsonObject =
        buildJsonObject {
            put("callId", "call-1")
            put("conversationId", FakeAppServer.THREAD_ID)
            put("cwd", workspace.toString())
            put("command", JsonArray(command.map { JsonPrimitive(it) }))
            put("parsedCmd", buildJsonArray { })
        }

    private fun legacy(
        method: String,
        params: JsonObject,
        subdir: String,
    ): JsonObject {
        val (events, answer) = request(method, params, holdout, subdir)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        return answer
    }

    /**
     * Runs a turn whose server writes the [before] notifications, then the request [method] with id 7 and [params],
     * waits for one client line, then completes the turn. Returns the turn's events and Loop's answer to id 7: its
     * `result`, or its `error` when [error].
     */
    private fun request(
        method: String,
        params: JsonObject,
        holdoutDir: Path? = null,
        subdir: String = "request",
        before: List<JsonObject> = emptyList(),
        error: Boolean = false,
    ): Pair<List<RunnerEvent>, JsonObject> {
        assertEquals(emptyList(), schema.problems("ServerRequest", serverRequest(7, method, params)), "the fake's request is not valid")
        val server =
            FakeAppServer.write(dir.resolve(subdir)) {
                handshake()
                before.forEach { raw(it.toString()) }
                serverRequest(7, method, params)
                awaitClient()
                turnCompleted()
            }

        val events = runAll(runner(server.command, holdoutDir = holdoutDir))

        val answer = received(server).single { it["id"]?.jsonPrimitive?.content == "7" }
        assertEquals(null, answer["method"], "an answer has no method: $answer")
        return events to (if (error) answer.obj("error") else answer.obj("result"))
    }

    private fun serverRequest(
        id: Long,
        method: String,
        params: JsonObject,
    ): JsonElement = CodexJson.format.parseToJsonElement(FakeAppServer.serverRequestLine(id, method, params))

    private fun loopLines(events: List<RunnerEvent>): List<String> =
        events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stderr && it.line.startsWith("loop: ") }.map { it.line }

    private fun runner(
        command: String,
        holdoutDir: Path? = null,
        strictMcp: Boolean = false,
    ): CodexAppServerRunner =
        CodexAppServerRunner(
            // Strict MCP adds config/read to the startup; the tests of other requests leave it out, the strict tests set it.
            CodexAppServerSettings(
                command,
                turnTimeout = 10.seconds,
                readTimeout = FakeAppServer.READ_TIMEOUT,
                strictMcp = strictMcp,
                holdoutDir = holdoutDir,
            ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = ProcessGroupLauncher()::launch,
            loopVersion = "test-build",
        )

    private fun runAll(runner: CodexAppServerRunner): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(), "Work on c-01").toList() } }
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

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
        const val TIMEOUT_SECONDS = 30L
    }
}
