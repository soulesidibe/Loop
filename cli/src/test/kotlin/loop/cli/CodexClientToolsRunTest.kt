package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.runner.claudecode.PreflightResult
import loop.runner.codex.LoopClientTools
import loop.tracker.github.GitHubTracker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * m3-22, 17.5 items 15 and 16, end to end with no model call: `loop run --once` with the Codex runner on a scripted app
 * server that calls Loop's client tools. Each call goes through the real `loop tool mcp` of the installed launcher, so
 * it is queued, recorded and applied at the drain with the `files` tracker exactly like the Claude Code runner's MCP
 * calls ([ClaudeCodeTrackerToolsTest], [ClaudeCodeSubmitResultTest]), and the session gets the server's reply. The
 * last tests check that the client tools take the arguments `loop tool mcp` declares, with the resource tools of
 * m4-51 when the workflow declares `resources:`.
 */
class CodexClientToolsRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val question = "Should the export include archived items? Options: include them, or skip them. I would include them."

    @Test
    fun `17_5 item 16 a Codex session's comment and move act on the tracker host side, and a refused move gets a failure result`() {
        val codex =
            fakeCodex(
                "comment" to buildJsonObject { put("text", question) },
                "move" to buildJsonObject { put("state", "shipped") },
                "move" to buildJsonObject { put("state", "needs_info") },
            )
        useCodex(codex)
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        // In Loop mode a move to needs_info is not a submitted result: it applies as asked, with no verification.
        assertEquals("needs_info", fixture.stateOf("t-1"), result.output + result.stderr)
        assertContains(fixture.issueFile("t-1").readText(), question)
        val applied = listOf("issue_comment_requested", "issue_move_requested", "issue_commented", "issue_moved")
        assertEquals(applied, kinds(applied + "result_submitted"))
        assertEquals("""{"chars":"${question.length}"}""", dataOf("issue_comment_requested"))
        assertEquals("""{"to":"needs_info"}""", dataOf("issue_move_requested"))
        assertContains(dataOf("issue_moved"), "\"by\":\"host\"")
        assertContains(dataOf("issue_moved"), "\"to\":\"needs_info\"")
        assertContains(dataOf("issue_commented"), "\"chars\":\"${question.length}\"")
        val (comment, refused, move) = answers(codex)
        assertEquals(
            true to "Queued a comment on t-1 (${question.length} characters).",
            comment.first to comment.second.substringBefore(" Loop"),
        )
        assertEquals(false, refused.first, "$refused")
        assertContains(refused.second, "\"shipped\" is not a state of the files tracker")
        assertContains(refused.second, "Nothing queued.")
        assertEquals(true, move.first, "$move")
        assertContains(move.second, "Queued a move of t-1 to \"needs_info\".")
        // The refused move went on to the next call, and the turn completed.
        assertEquals(1, fixture.query("SELECT kind FROM events WHERE kind = 'turn_completed'").size)
        val tools = threadStart(codex).getValue("dynamicTools").jsonArray.map { it.jsonObject }
        assertEquals(LoopClientTools.NAMES, tools.map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `17_5 item 16 a Codex session's submit_result is recorded and verified, and the session gets the reply`() {
        val result =
            buildJsonObject {
                put("status", "done")
                putJsonObject("deliverable") {
                    put("type", "files")
                    put("paths", Json.parseToJsonElement("""["hello.txt"]"""))
                }
                put("summary", "Wrote hello.txt.")
                put(
                    "acceptance",
                    Json.parseToJsonElement("""[{"criterion":"hello.txt exists","proof":"check","ref":"test -f hello.txt"}]"""),
                )
            }
        val codex = fakeCodex("submit_result" to result, before = "printf 'hello\\n' > hello.txt")
        useCodex(codex)
        fixture.issue("t-1")

        val run = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, run.statusCode, run.output + run.stderr)
        assertEquals("review", fixture.stateOf("t-1"), run.output + run.stderr)
        val expected = listOf("result_requested", "result_submitted", "check_started", "check_finished", "verdict", "issue_moved")
        assertEquals(expected, kinds(expected))
        assertContains(dataOf("result_requested"), "\"status\":\"done\"")
        assertContains(dataOf("result_submitted"), "\"source\":\"mcp\"")
        assertContains(dataOf("verdict"), "\"verdict\":\"verified\"")
        assertContains(dataOf("issue_moved"), "\"verified\":\"true\"")
        val (success, text) = answers(codex).single()
        assertEquals(true, success, text)
        assertContains(text, "Queued a done result for t-1.")
        assertContains(text, "Do not call submit_result again.")
    }

    @Test
    fun `m4-14 with a files and a GitHub tracker each Codex session's comment and move reach its issue's own tracker only`() {
        RecordedGitHubIssue().use { github ->
            val codex =
                fakeCodex(
                    "comment" to buildJsonObject { put("text", question) },
                    "move" to buildJsonObject { put("state", "needs_info") },
                )
            useCodex(codex, github)
            fixture.issue("l-1")

            val result = LoopCommand(host(GITHUB_TOKEN)).test(listOf("run", fixture.workflow.toString(), "--once"))

            assertEquals(0, result.statusCode, result.output + result.stderr)
            assertEquals("needs_info", fixture.stateOf("l-1"), result.output + result.stderr)
            assertEquals(
                1,
                fixture
                    .issueFile("l-1")
                    .readText()
                    .split(question)
                    .size - 1,
                fixture.issueFile("l-1").readText(),
            )
            assertEquals(
                listOf(
                    "POST /api/repos/octocat/Hello-World/issues/26/comments",
                    "POST /api/repos/octocat/Hello-World/issues/26/labels",
                    "DELETE /api/repos/octocat/Hello-World/issues/26/labels/gh-ready",
                ),
                github.writePaths,
                result.output + result.stderr,
            )
            assertContains(github.writes.first().body, "archived items")
            val applied = listOf("issue_comment_requested", "issue_move_requested", "issue_commented", "issue_moved")
            for (issue in listOf("local:l-1", "gh:26")) {
                assertEquals(applied, kinds(applied + "tool_request_failed", issue), issue)
            }
            // Each session's move lists the states of its issue's own tracker, and both calls were queued.
            val sessions = sessions(codex)
            val moveStates =
                sessions.map { session ->
                    threadStart(session)
                        .getValue("dynamicTools")
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it.getValue("name").jsonPrimitive.content == "move" }
                        .getValue("inputSchema")
                        .jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("state")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                }
            assertEquals(setOf(FILES_STATES, GitHubTracker.DEFINED_STATES), moveStates.toSet())
            sessions.forEach { session -> assertEquals(listOf(true, true), answers(session).map { it.first }, "${answers(session)}") }
        }
    }

    @Test
    fun `m3-22 the client tools take the arguments loop tool mcp declares, move narrowed to the tracker's states`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val listed = listTools(fixture.toolEnv(ATTEMPT, "t-1"))
        val states = FILES_STATES
        val specs = LoopClientTools("files", states).specs.map { it.jsonObject }

        assertEquals(LoopClientTools.NAMES, listed.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listed.map { it.getValue("name") }, specs.map { it.getValue("name") })
        listed.zip(specs).forEach { (mcp, codex) ->
            val name = mcp.getValue("name").jsonPrimitive.content
            val mcpSchema = mcp.getValue("inputSchema").jsonObject
            val codexSchema = codex.getValue("inputSchema").jsonObject
            assertEquals(mcpSchema["required"], codexSchema["required"], name)
            val mcpProperties = mcpSchema.getValue("properties").jsonObject
            val codexProperties = codexSchema.getValue("properties").jsonObject
            // m4-31: ask has the same arguments and description on both runners.
            if (name == "submit_result" || name == "ask") {
                assertEquals(mcpProperties, codexProperties, name)
                assertEquals(mcp["description"], codex["description"], name)
            } else {
                // move and comment: one string argument of the same name; move lists the states and names the tracker.
                assertEquals(mcpProperties.keys, codexProperties.keys, name)
                codexProperties.forEach { (argument, schema) ->
                    assertEquals(mcpProperties.getValue(argument).jsonObject["type"], schema.jsonObject["type"], "$name $argument")
                }
            }
        }
        val move = specs.single { it.getValue("name").jsonPrimitive.content == "move" }
        val state =
            move
                .getValue("inputSchema")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("state")
                .jsonObject
        assertEquals(states, state.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        val ask = specs.single { it.getValue("name").jsonPrimitive.content == "ask" }
        val description = ask.getValue("description").jsonPrimitive.content
        assertContains(description, "Use it only for a question a human must answer")
        assertContains(description, "Technical choices are your own")
    }

    @Test
    fun `m4-51 with resources the client tools add request_resource and release_resource, as loop tool mcp lists them`() {
        fixture.writeWorkflow(
            extra =
                """
                |resources:
                |  - kind: android-device
                |    id: pixel-7
                |    can: [ui tests]
                |    cannot: [push]
                |  - kind: ios-simulator
                |    id: iphone-16
                """.trimMargin(),
        )
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val listed = listTools(env)
        // The tools of the snapshot `loop run` wrote, read the way the Codex runner reads them.
        val loaded = LoopClientTools.load(mapOf("LOOP_LAUNCHER" to "loop", "LOOP_TOOL_CONFIG" to env.getValue("LOOP_TOOL_CONFIG")!!))
        val tools = assertIs<LoopClientTools.Loaded.Ready>(loaded, "$loaded").tools
        val specs = tools.specs.map { it.jsonObject }

        assertEquals(listOf("pixel-7", "iphone-16"), tools.resources)
        assertEquals(listed.map { it.getValue("name") }, specs.map { it.getValue("name") })
        assertEquals(tools.names, specs.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(LoopClientTools.NAMES + LoopClientTools.RESOURCE_NAMES, tools.names)
        listed.zip(specs).drop(LoopClientTools.NAMES.size).forEach { (mcp, codex) ->
            val name = mcp.getValue("name").jsonPrimitive.content
            assertEquals(mcp["description"], codex["description"], name)
            val mcpSchema = mcp.getValue("inputSchema").jsonObject
            val codexSchema = codex.getValue("inputSchema").jsonObject
            assertEquals(mcpSchema["required"], codexSchema["required"], name)
            assertEquals(mcpSchema["properties"], codexSchema["properties"], name)
        }
    }

    // Helpers.

    private fun useCodex(
        command: Path,
        github: RecordedGitHubIssue? = null,
    ) = fixture.workflow.writeText(
        """
            |---
            |tracker:
            |${if (github == null) "" else "  id: local"}
            |  kind: files
            |  active_states: [todo]
            |  required_labels: [ready-for-agent]
            |${github?.trackersYaml?.trimEnd()?.replace("\n", "\n|").orEmpty()}
            |workspace:
            |  root: ./workspaces
            |runner:
            |  kind: codex-app-server
            |runners:
            |  codex:
            |    command: "$command"
            |    strict_mcp: false
            |    read_timeout_ms: 30000
            |agent:
            |  max_turns: 1
            |retries:
            |  max_attempts: 1
            |  max_continuations: 1
            |kinds:
            |  code:
            |    delivery: { policy: auto, auto_merge: { enabled: true } }
            |    verify:
            |      checks:
            |        - { type: command, run: "test -f hello.txt" }
            |---
            |Work on {{ issue.identifier }}
            |
        """.trimMargin(),
    )

    /**
     * Writes a scripted app server: it answers the startup with the real codex 0.167.0 answers (m3-02), runs [before]
     * in the workspace, then sends one `item/tool/call` per entry of [calls], each once the answer to the one before
     * arrived, and completes the turn. Every client line goes to `received-<pid>.jsonl` beside the script, one file per
     * session, so the sessions of two issues run at once do not mix.
     */
    private fun fakeCodex(
        vararg calls: Pair<String, JsonObject>,
        before: String = "",
    ): Path {
        val dir = root.resolve("codex-bin").createDirectories()
        var files = 0

        fun line(text: String): String = dir.resolve("line-${++files}.json").also { it.writeText(text + "\n") }.toString()
        val read = "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$dir'/received-\$\$.jsonl\n"
        val script = StringBuilder("#!/bin/bash\n")
        script.append(read).append("cat '${line(INITIALIZE)}'\n")
        script.append(read).append(read).append("cat '${line(THREAD_START)}'\n")
        script.append(read).append("cat '${line(TURN_START)}'\n")
        if (before.isNotEmpty()) script.append(before).append('\n')
        calls.forEachIndexed { index, (tool, arguments) ->
            val request =
                buildJsonObject {
                    put("id", FIRST_CALL_ID + index)
                    put("method", "item/tool/call")
                    putJsonObject("params") {
                        put("callId", "call-$index")
                        put("threadId", THREAD_ID)
                        put("turnId", TURN_ID)
                        put("tool", tool)
                        put("arguments", arguments)
                    }
                }
            script.append("cat '${line(request.toString())}'\n").append(read)
        }
        script.append("cat '${line(TURN_COMPLETED)}'\n")
        script.append("while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$dir'/received-\$\$.jsonl; done\nexit 0\n")
        val file = dir.resolve("codex")
        file.writeText(script.toString())
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    /** The client lines of each session, by session. */
    private fun sessions(codex: Path): List<List<JsonObject>> =
        codex.parent
            .listDirectoryEntries("received-*.jsonl")
            .map { file -> file.readLines().map { Json.parseToJsonElement(it).jsonObject } }

    private fun received(codex: Path): List<JsonObject> = sessions(codex).single()

    private fun threadStart(codex: Path): JsonObject = threadStart(received(codex))

    private fun threadStart(session: List<JsonObject>): JsonObject =
        session.single { it["method"]?.jsonPrimitive?.content == "thread/start" }.getValue("params").jsonObject

    /** Loop's answers to the tool calls of the one session, in order: `success` and the one text. */
    private fun answers(codex: Path): List<Pair<Boolean, String>> = answers(received(codex))

    /** Loop's answers to the tool calls of [session], in order. */
    private fun answers(session: List<JsonObject>): List<Pair<Boolean, String>> {
        val byId =
            session.filter { it["method"] == null && it["id"] != null }.associateBy { it.getValue("id").jsonPrimitive.content }
        return generateSequence(FIRST_CALL_ID) { it + 1 }
            .map { byId[it.toString()] }
            .takeWhile { it != null }
            .map { answer ->
                val result = answer!!.getValue("result").jsonObject
                val text =
                    result
                        .getValue("contentItems")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content
                result
                    .getValue("success")
                    .jsonPrimitive.content
                    .toBooleanStrict() to text
            }.toList()
    }

    private fun kinds(
        of: List<String>,
        issue: String = "default:t-1",
    ): List<String> =
        fixture
            .query("SELECT kind FROM events WHERE issue_id = '$issue' ORDER BY seq")
            .mapNotNull { it["kind"] }
            .filter { it in of }

    private fun dataOf(kind: String) = fixture.query("SELECT data FROM events WHERE kind = '$kind'").single()["data"].orEmpty()

    /** The tools `loop tool mcp` lists in the attempt [env]: the handshake, then `tools/list`. */
    private fun listTools(env: Map<String, String?>): List<JsonObject> {
        val run = fixture.start("tool", "mcp", cwd = root, env = env)
        val stdin = run.process.outputStream.bufferedWriter()
        listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"clientInfo":{"name":"loop-codex-runner","version":"test"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
        ).forEach { stdin.write(it + "\n") }
        stdin.flush()
        val deadline = System.nanoTime() + 60_000_000_000L
        var reply: JsonObject? = null
        while (reply == null && System.nanoTime() < deadline && run.process.isAlive) {
            reply =
                run
                    .stdoutSoFar()
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .firstOrNull { it["id"]?.jsonPrimitive?.content == "2" }
            if (reply == null) Thread.sleep(50)
        }
        stdin.close()
        val result = run.await()
        val message = reply ?: fail("no tools/list reply: $result")
        return message
            .getValue("result")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map { it.jsonObject }
    }

    private fun host(githubToken: String? = null): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ) + listOfNotNull(githubToken?.let { "GITHUB_TOKEN" to it }),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
        )

    private companion object {
        const val ATTEMPT = "attempt-codex-tools"
        const val GITHUB_TOKEN = "ghp_m414CodexToken0123456789abcdefghij"
        val FILES_STATES = listOf("todo", "in_progress", "review", "needs_info", "done", "canceled")
        const val FIRST_CALL_ID = 100
        const val THREAD_ID = "01a1e51d-2053-7123-b68c-519b77a7d22a"
        const val TURN_ID = "01a1e51d-0000-7000-8000-000000000003"

        /** The real `initialize` answer of codex 0.167.0, recorded in m3-02. */
        const val INITIALIZE =
            """{"id":1,"result":{"userAgent":"loop/0.167.0 (Mac OS; arm64) unknown (loop; 0.0.0)","codexHome":"/home/user/.codex",""" +
                """"platformFamily":"unix","platformOs":"macos"}}"""

        /** The real `thread/start` answer of codex 0.167.0, recorded in m3-02. */
        val THREAD_START: String =
            """{"id":2,"result":{"thread":{"id":"$THREAD_ID","environments":[{"environmentId":"local","cwd":"/workspace",""" +
                """"runtimeWorkspaceRoots":["/workspace"]}],"extra":null,"sessionId":"$THREAD_ID","forkedFromId":null,""" +
                """"parentThreadId":null,"preview":"","ephemeral":true,"section":null,"sectionEnteredAt":null,"projectId":null,""" +
                """"historyMode":"paginated","modelProvider":"openai","model":"gpt-5.1-codex-max","reasoningEffort":null,""" +
                """"createdAt":1794845253,"updatedAt":1794845253,"recencyAt":1794845253,"status":{"type":"idle"},"path":null,""" +
                """"cwd":"/workspace","cliVersion":"0.167.0","originator":"loop","source":"vscode","canAcceptDirectInput":true,""" +
                """"threadSource":null,"agentNickname":null,"agentRole":null,"gitInfo":null,"name":null,"daybreakEnabled":null,""" +
                """"turns":[]},"model":"gpt-5.1-codex-max","modelProvider":"openai","serviceTier":null,"disabledPluginIds":[],""" +
                """"cwd":"/workspace","runtimeWorkspaceRoots":["/workspace"],"instructionSources":[],"approvalPolicy":"on-request",""" +
                """"approvalsReviewer":"user","sandbox":{"type":"readOnly","networkAccess":false},""" +
                """"activePermissionProfile":{"id":":read-only","extends":null},"reasoningEffort":null,""" +
                """"multiAgentMode":"explicitRequestOnly"}}"""

        val TURN_START: String = """{"id":3,"result":{"turn":{"id":"$TURN_ID","items":[],"status":"inProgress"}}}"""

        val TURN_COMPLETED: String =
            """{"method":"turn/completed","params":{"threadId":"$THREAD_ID","turn":{"id":"$TURN_ID","items":[],"status":"completed"}}}"""
    }
}
