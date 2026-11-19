package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.SavedToolOutput
import loop.core.orchestrator.ToolOutputQueue
import loop.core.orchestrator.ToolRequest
import loop.core.workflow.TokenCounters
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * m2-16: `loop tool mcp`, Loop's MCP server over stdio, as Claude Code spawns it. Its `submit_result` takes the
 * PLAN section 7 result and gives the same verdict path as `.loop/result.json`, with no model call: the agents
 * here are scripts that speak JSON-RPC to the real server. Its `move` and `comment` (m2-17) queue what `loop tool
 * move` and `loop tool comment` queue.
 */
class ToolMcpCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val doneResult =
        """{"status":"done","deliverable":{"type":"files","paths":["hello.txt"]},"summary":"Wrote hello.txt.",""" +
            """"acceptance":[{"criterion":"hello.txt exists","proof":"check","ref":"test -f hello.txt"}]}"""

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
            """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
    private val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    private fun call(
        arguments: String,
        tool: String = "submit_result",
        id: Int = 2,
    ) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    /**
     * Runs `loop tool mcp` with [env] like an MCP client: the handshake, then one `submit_result` call with
     * [arguments]. Its stdin stays open until the reply is read, as Claude Code keeps it, then closes. Every stdout
     * line must be JSON-RPC; returns the reply's result and the run.
     */
    private fun submit(
        env: Map<String, String?>,
        arguments: String,
        tool: String = "submit_result",
    ): Pair<JsonObject, LoopResult> {
        val run = fixture.start("tool", "mcp", cwd = root, env = env)
        val stdin = run.process.outputStream.bufferedWriter()
        listOf(initialize, initialized, call(arguments, tool)).forEach { stdin.write(it + "\n") }
        stdin.flush()
        val deadline = System.nanoTime() + 60_000_000_000L
        var reply: JsonObject? = null
        while (reply == null && System.nanoTime() < deadline && run.process.isAlive) {
            reply = messages(run.stdoutSoFar()).firstOrNull { it["id"]?.jsonPrimitive?.content == "2" }
            if (reply == null) Thread.sleep(50)
        }
        stdin.close()
        val result = run.await()
        val message = reply ?: messages(result.stdout).firstOrNull { it["id"]?.jsonPrimitive?.content == "2" } ?: fail("no reply: $result")
        return message.getValue("result").jsonObject to result
    }

    /** The JSON-RPC messages of [stdout], failing on any line that is not one: stdout belongs to the protocol. */
    private fun messages(stdout: String): List<JsonObject> =
        stdout.lines().filter { it.isNotBlank() }.map { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrElse { fail("not a JSON-RPC line on stdout: $line") }
        }

    private fun JsonObject.text(): String =
        getValue("content")
            .jsonArray
            .single()
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = this["isError"]?.jsonPrimitive?.boolean == true

    /** Loop mode with one command check [run], one turn per attempt. */
    private fun loopMode(run: String = "test -f hello.txt") =
        fixture.writeWorkflow(
            extra =
                """
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
                |        - { type: command, run: "$run" }
                """.trimMargin(),
        )

    private fun events(kind: String): List<Map<String, String?>> =
        fixture.query("SELECT attempt_id, issue_identifier, data FROM events WHERE kind = '$kind' ORDER BY seq")

    @Test
    fun `a submit_result call over MCP gives the same verdict path as the result file`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        val replies = root.resolve("replies")
        // t-1 submits through the result file, t-2 calls submit_result on `loop tool mcp`, its stdin open until the reply.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |if [ "${'$'}LOOP_ISSUE_IDENTIFIER" = t-1 ]; then
            |  mkdir -p .loop
            |  printf '%s\n' '$doneResult' > .loop/result.json
            |else
            |  rpc=${'$'}(mktemp -d)
            |  mkfifo "${'$'}rpc/in"
            |  loop tool mcp < "${'$'}rpc/in" > '$replies' &
            |  exec 3> "${'$'}rpc/in"
            |  printf '%s\n' '$initialize' '$initialized' '${call(doneResult)}' >&3
            |  n=0
            |  until grep -q '"id":2' '$replies'; do n=${'$'}((n + 1)); [ ${'$'}n -lt 600 ] || exit 9; sleep 0.1; done
            |  exec 3>&-
            |  wait
            |fi
            |
            """.trimMargin(),
        )
        loopMode()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", timeoutSeconds = 120)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals("review", fixture.stateOf("t-2"), result.toString())
        val verification = listOf("result_submitted", "check_started", "check_finished", "verdict", "issue_moved")

        fun kindsOf(id: String) =
            fixture
                .query("SELECT kind FROM events WHERE issue_identifier = '$id' ORDER BY seq")
                .mapNotNull { it["kind"] }
                .filter { it in verification }
        assertEquals(verification, kindsOf("t-2"))
        assertEquals(kindsOf("t-1"), kindsOf("t-2"))
        val data = events("result_submitted").single { it["issue_identifier"] == "t-2" }["data"].orEmpty()
        assertContains(data, "\"to\":\"review\"")
        assertContains(data, "\"status\":\"done\"")
        assertContains(data, "\"source\":\"mcp\"")
        assertContains(data, "\"acceptance\":\"1\"")
        assertContains(events("verdict").single { it["issue_identifier"] == "t-2" }["data"].orEmpty(), "\"verdict\":\"verified\"")
        val reply = messages(replies.readText()).single { it["id"]?.jsonPrimitive?.content == "2" }.getValue("result").jsonObject
        assertTrue(!reply.isError(), "$reply")
        assertContains(reply.text(), "Queued a done result for t-2.")
        // Saved beside the evidence like the result file.
        val attempt = events("result_submitted").single { it["issue_identifier"] == "t-2" }["attempt_id"]
        val saved = fixture.loopHome.resolve("attempts/${fixture.projectId()}/$attempt.result-1.json")
        assertEquals(Json.parseToJsonElement(doneResult), Json.parseToJsonElement(saved.readText()))
        val trace = fixture.loop("trace", "t-2", "--workflow", fixture.workflow.toString())
        assertTrue(
            trace.stdout.lines().any { "result_submitted" in it && "status=done" in it && "from submit_result" in it },
            trace.stdout,
        )
        val log = fixture.loop("log", fixture.workflow.toString())
        assertContains(log.stdout, "t-2  submitted its result with submit_result, a move to review")
    }

    @Test
    fun `a valid call is queued from the snapshot alone and recorded, and stdout holds only JSON-RPC`() {
        fixture.issue("t-1")
        loopMode()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val (reply, run) = submit(fixture.toolEnv(ATTEMPT, "t-1"), doneResult)

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(!reply.isError(), "$reply")
        assertEquals(
            "Queued a done result for t-1. Loop applies it when this turn ends: it runs its checks and moves the issue " +
                "to review only if they pass. Do not call submit_result again.",
            reply.text(),
        )
        val queued = fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request }
        val request = assertIs<ToolRequest.Result>(queued.single())
        assertEquals(Json.parseToJsonElement(doneResult), Json.parseToJsonElement(request.text))
        val recorded = fixture.query("SELECT attempt_id, data FROM events WHERE kind = 'result_requested'").single()
        assertEquals(ATTEMPT, recorded["attempt_id"])
        assertContains(recorded["data"].orEmpty(), "\"status\":\"done\"")
        assertEquals("todo", fixture.stateOf("t-1"), "a tool only queues")
    }

    @Test
    fun `an invalid result is a tool error naming the field, and nothing is queued`() {
        fixture.issue("t-1")
        loopMode()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val (reply, run) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"status":"blocked"}""")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(reply.isError(), "$reply")
        assertEquals(
            "loop tool mcp: the result is invalid: summary is required when status is blocked. " +
                "Fix it and call submit_result again. Nothing queued.",
            reply.text(),
        )
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind = 'result_requested'").size)
    }

    @Test
    fun `a call from a finished attempt is a tool error, and nothing is queued`() {
        fixture.issue("t-1")
        loopMode()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptFinished(ATTEMPT, "t-1"))

        val (reply, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), doneResult)

        assertTrue(reply.isError(), "$reply")
        assertContains(reply.text(), "attempt ${ATTEMPT.take(8)} is not live")
        assertContains(reply.text(), "Nothing queued.")
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
    }

    /**
     * An agent script step that calls [calls], pairs of a tool and its arguments, on `loop tool mcp` in one session,
     * as Claude Code does (m2-17): each call waits for the reply of the one before, since the server handles calls
     * concurrently, and stdin stays open until the last reply is in [replies].
     */
    private fun mcpCalls(
        replies: Path,
        vararg calls: Pair<String, String>,
    ): String {
        val steps =
            calls.mapIndexed { index, (tool, arguments) ->
                val id = index + 2
                "  printf '%s\\n' '${call(arguments, tool, id)}' >&3\n" +
                    "  n=0; until grep -q '\"id\":$id' '$replies'; do n=${'$'}((n + 1)); [ ${'$'}n -lt 600 ] || exit 9; sleep 0.1; done"
            }
        return """
            |  rpc=${'$'}(mktemp -d)
            |  mkfifo "${'$'}rpc/in"
            |  loop tool mcp < "${'$'}rpc/in" > '$replies' &
            |  exec 3> "${'$'}rpc/in"
            |  printf '%s\n' '$initialize' '$initialized' >&3
            |${steps.joinToString("\n")}
            |  exec 3>&-
            |  wait
            """.trimMargin()
    }

    private fun kindsOf(
        id: String,
        kinds: List<String>,
    ) = fixture
        .query("SELECT kind FROM events WHERE issue_identifier = '$id' ORDER BY seq")
        .mapNotNull { it["kind"] }
        .filter { it in kinds }

    private fun dataOf(
        kind: String,
        id: String,
    ) = events(kind).single { it["issue_identifier"] == id }["data"].orEmpty()

    @Test
    fun `move and comment over MCP queue the requests, events and replies of loop tool (m2-17)`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptStarted(OTHER, "t-2"))
        val comment = "  Should the export include archived items?  "

        val cliComment = fixture.loop("tool", "comment", comment, cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))
        val cliMove = fixture.loop("tool", "move", "needs_info", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))
        val (mcpComment, _) = submit(fixture.toolEnv(OTHER, "t-2"), """{"text":${JsonPrimitive(comment)}}""", "comment")
        val (mcpMove, _) = submit(fixture.toolEnv(OTHER, "t-2"), """{"state":"needs_info"}""", "move")

        assertEquals(0, cliComment.exitCode, cliComment.toString())
        assertEquals(0, cliMove.exitCode, cliMove.toString())
        assertTrue(!mcpComment.isError() && !mcpMove.isError(), "$mcpComment $mcpMove")
        assertEquals(cliComment.stdout.trim().replace("t-1", "t-2"), mcpComment.text())
        assertEquals(cliMove.stdout.trim().replace("t-1", "t-2"), mcpMove.text())
        val expected = listOf(ToolRequest.Comment(comment.trim()), ToolRequest.Move("needs_info"))
        assertEquals(expected, fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request })
        assertEquals(expected, fixture.queuedRequests(OTHER).map { assertIs<QueuedRequest.Valid>(it).request })
        listOf("issue_comment_requested", "issue_move_requested").forEach { kind ->
            // The same data, no source: an MCP request and a CLI request are the same request.
            assertEquals(dataOf(kind, "t-1"), dataOf(kind, "t-2"), kind)
            assertTrue("source" !in dataOf(kind, "t-2"), dataOf(kind, "t-2"))
        }
        assertEquals("todo", fixture.stateOf("t-2"), "a tool only queues")
    }

    @Test
    fun `a comment over MCP is redacted like a CLI comment (m2-17)`() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val (reply, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"text":"the key is $token, ok?"}""", "comment")

        assertTrue(!reply.isError(), "$reply")
        val queued = fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request }
        assertEquals(listOf<ToolRequest>(ToolRequest.Comment("the key is [redacted:github-token], ok?")), queued)
        assertTrue(token !in fixture.query("SELECT * FROM events").joinToString { it.toString() })
    }

    @Test
    fun `an unknown state or an empty comment over MCP is a tool error, and nothing is queued (m2-17)`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val (move, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"state":"reviewed"}""", "move")
        val (comment, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"text":"  \n "}""", "comment")

        assertTrue(move.isError(), "$move")
        assertEquals(
            "loop tool mcp: \"reviewed\" is not a state of the files tracker. " +
                "Valid states: todo, in_progress, review, needs_info, done, canceled. Nothing queued.",
            move.text(),
        )
        assertTrue(comment.isError(), "$comment")
        assertEquals(
            "loop tool mcp: the comment is empty. Pass the text to write, for example " +
                "loop tool comment \"Should the export include archived items?\". Nothing queued.",
            comment.text(),
        )
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind LIKE 'issue_%_requested'").size)
    }

    @Test
    fun `m3-14 a reply over tool_output_tokens is trimmed, and its full text is kept for the attempt file`() {
        fixture.writeWorkflow(
            extra =
                """
                |context:
                |  tool_output_tokens: 48
                """.trimMargin(),
        )
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val (move, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"state":"reviewed"}""", "move")
        val (comment, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"text":"ok"}""", "comment")

        val full =
            "loop tool mcp: \"reviewed\" is not a state of the files tracker. " +
                "Valid states: todo, in_progress, review, needs_info, done, canceled. Nothing queued."
        assertTrue(move.isError(), "$move")
        assertTrue(TokenCounters.estimate.count(move.text()) <= 48, move.text())
        val note =
            Regex(
                """\n\(cut to fit context\.tool_output_tokens: (\d+) of its characters are left out\. The full text is in the attempt file\.\)$""",
            )
        val match = note.find(move.text()) ?: fail(move.text())
        val kept = move.text().substring(0, match.range.first)
        assertTrue(kept.isNotEmpty() && full.startsWith(kept), move.text())
        assertEquals(full.length - kept.length, match.groupValues[1].toInt())
        assertTrue("(cut" !in comment.text(), "a reply within the limit is whole: ${comment.text()}")
        assertEquals(
            listOf(SavedToolOutput("move", full)),
            ToolOutputQueue(fixture.attemptDir(ATTEMPT)).pending().map { it.second },
            "only the trimmed reply is kept, in full",
        )
    }

    @Test
    fun `a move or a comment over MCP from a finished attempt is a tool error, and nothing is queued (m2-17)`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"), fixture.attemptFinished(ATTEMPT, "t-1"))

        val (move, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"state":"review"}""", "move")
        val (comment, _) = submit(fixture.toolEnv(ATTEMPT, "t-1"), """{"text":"hello"}""", "comment")

        listOf(move, comment).forEach { reply ->
            assertTrue(reply.isError(), "$reply")
            assertTrue(reply.text().startsWith("loop tool mcp: attempt ${ATTEMPT.take(8)} is not live (finished at "), reply.text())
            assertTrue(reply.text().endsWith("). Nothing queued."), reply.text())
        }
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind LIKE 'issue_%_requested'").size)
    }

    @Test
    fun `move and comment over MCP are applied at the drain like loop tool (m2-17)`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        val replies = root.resolve("replies")
        val question = "Should the export include archived items?"
        // Symphony mode: t-1 runs loop tool, t-2 calls the same tools on `loop tool mcp`.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |if [ "${'$'}LOOP_ISSUE_IDENTIFIER" = t-1 ]; then
            |  loop tool comment '$question'
            |  loop tool move needs_info
            |else
            |${mcpCalls(replies, "comment" to """{"text":"$question"}""", "move" to """{"state":"needs_info"}""")}
            |fi
            |
            """.trimMargin(),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", timeoutSeconds = 120)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("needs_info", fixture.stateOf("t-1"), result.toString())
        assertEquals("needs_info", fixture.stateOf("t-2"), result.toString())
        assertContains(fixture.issueFile("t-2").readText(), question)
        val applied = listOf("issue_comment_requested", "issue_move_requested", "issue_commented", "issue_moved")
        assertEquals(applied, kindsOf("t-2", applied))
        assertEquals(kindsOf("t-1", applied), kindsOf("t-2", applied))
        listOf("issue_commented", "issue_moved").forEach { assertEquals(dataOf(it, "t-1"), dataOf(it, "t-2"), it) }
        val answers = messages(replies.readText()).filter { it["id"]?.jsonPrimitive?.content in setOf("2", "3") }
        assertEquals(2, answers.size, replies.readText())
        answers.forEach { assertTrue(!it.getValue("result").jsonObject.isError(), "$it") }
    }

    @Test
    fun `in Loop mode a move over MCP to the handoff or a terminal state is intercepted like loop tool move (m2-17)`() {
        listOf("t-1", "t-2", "t-3", "t-4").forEach { fixture.issue(it) }
        val replies = root.resolve("replies")
        // t-1 and t-3 run loop tool move, t-2 and t-4 call move on `loop tool mcp`: to review, then to done.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |case "${'$'}LOOP_ISSUE_IDENTIFIER" in
            |  t-1) loop tool move review ;;
            |  t-3) loop tool move done ;;
            |  t-2)
            |${mcpCalls(replies, "move" to """{"state":"review"}""")}
            |  ;;
            |  t-4)
            |${mcpCalls(root.resolve("replies-4"), "move" to """{"state":"done"}""")}
            |  ;;
            |esac
            |
            """.trimMargin(),
        )
        loopMode()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", timeoutSeconds = 180)

        assertEquals(0, result.exitCode, result.toString())
        val verification = listOf("issue_move_requested", "result_submitted", "check_started", "check_finished", "verdict", "issue_moved")
        assertEquals(verification, kindsOf("t-2", verification))
        assertEquals(kindsOf("t-1", verification), kindsOf("t-2", verification))
        assertEquals(kindsOf("t-3", verification), kindsOf("t-4", verification))
        assertEquals(fixture.stateOf("t-1"), fixture.stateOf("t-2"))
        assertEquals(fixture.stateOf("t-3"), fixture.stateOf("t-4"))
        assertEquals("review", fixture.stateOf("t-2"), result.toString())
        assertContains(dataOf("result_submitted", "t-2"), "\"to\":\"review\"")
        assertContains(dataOf("result_submitted", "t-4"), "\"to\":\"done\"")
        listOf("t-2", "t-4").forEach { id ->
            assertEquals(
                dataOf("result_submitted", if (id == "t-2") "t-1" else "t-3"),
                dataOf("result_submitted", id),
                "an intercepted MCP move is the same submitted result",
            )
            assertContains(dataOf("verdict", id), "\"verdict\":\"verified\"")
        }
    }

    @Test
    fun `outside an attempt the server does not start and the exit is 4`() {
        fixture.issue("t-1")
        loopMode()

        val result = fixture.loop("tool", "mcp", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1") + ("LOOP_TOOL_CONFIG" to null))

        assertEquals(4, result.exitCode, result.toString())
        assertEquals("", result.stdout)
        assertContains(result.stderr, "loop tool mcp: not inside an attempt.")
    }

    private companion object {
        const val ATTEMPT = "9c4e2f1a-0000-4000-8000-000000000016"
        const val OTHER = "9c4e2f1a-0000-4000-8000-000000000017"
    }
}
