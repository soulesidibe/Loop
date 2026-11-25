package loop.runner.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.api.LedgerTokens
import loop.api.RiskReview
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.v2.TurnStatus
import loop.runner.codex.schema.PinnedSchema
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-214: the merge risk reviewer of Codex. It runs one turn of the Codex app server in the issue workspace with the
 * model of `risk.reviewer`, on the read-only sandbox, declining every command and file change approval, and answers with
 * the text of the turn's last agent message and the session's tokens. Each server is a [FakeAppServer]; every request
 * Loop writes is checked against the pinned schema.
 */
class CodexRiskReviewerTest {
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

    private val verdict = """{"dimensions":{"blast_radius":"low"}}"""

    @Test
    fun `bl-214 the answer is the text of the final agent message, with the session tokens and no cost`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                notification(agentMessage("msg-1", "Reading the diff first.", "commentary"))
                notification(agentMessage("msg-2", verdict, "final_answer"))
                raw(USAGE)
                turnCompleted()
            }

        val review = review(server).getOrThrow()

        assertEquals(RiskReview(verdict, LedgerTokens(3_119, 5, 12_288, 0), null), review)
    }

    @Test
    fun `bl-214 the thread runs the reviewer model on the read-only sandbox, the prompt as its only input, in the workspace`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                notification(agentMessage("msg-1", verdict, "final_answer"))
                turnCompleted()
            }

        review(server, model = "gpt-6.1-sol").getOrThrow()

        val lines = received(server)
        val thread = lines.single { it["method"]?.jsonPrimitive?.content == "thread/start" }
        val turn = lines.single { it["method"]?.jsonPrimitive?.content == "turn/start" }
        val threadParams = thread.getValue("params").jsonObject
        val turnParams = turn.getValue("params").jsonObject
        assertEquals("gpt-6.1-sol", threadParams.getValue("model").jsonPrimitive.content)
        assertEquals("read-only", threadParams.getValue("sandbox").jsonPrimitive.content)
        assertEquals("untrusted", threadParams.getValue("approvalPolicy").jsonPrimitive.content)
        assertEquals(workspace.toAbsolutePath().toString(), threadParams.getValue("cwd").jsonPrimitive.content)
        assertEquals(
            "readOnly",
            turnParams
                .getValue("sandboxPolicy")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
        assertEquals("untrusted", turnParams.getValue("approvalPolicy").jsonPrimitive.content)
        assertContains(turnParams.getValue("input").toString(), "Review this pull request.")
        assertEquals(emptyList(), schema.problems("v2/ThreadStartParams", threadParams))
        assertEquals(emptyList(), schema.problems("v2/TurnStartParams", turnParams))
        assertTrue("dynamicTools" !in threadParams, "a reviewer declares no Loop tool: $threadParams")
    }

    @Test
    fun `bl-214 every command approval is declined, even a plain read, so the reviewer never runs what Codex does not hold safe`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                serverRequest(7, "item/commandExecution/requestApproval", commandApproval("./gradlew test"))
                awaitClient()
                serverRequest(8, "item/commandExecution/requestApproval", commandApproval("touch notes.txt"))
                awaitClient()
                notification(agentMessage("msg-1", verdict, "final_answer"))
                turnCompleted()
            }

        assertEquals(verdict, review(server).getOrThrow().text)

        assertEquals(listOf(decision("decline"), decision("decline")), answers(server, 7, 8))
        answers(server, 7, 8).forEach { assertEquals(emptyList(), schema.problems("CommandExecutionRequestApprovalResponse", it)) }
    }

    @Test
    fun `bl-214 a file change approval and the older approval forms are declined`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                raw(fileChangeStarted("item-1", "${workspace.toAbsolutePath()}/src/Main.kt"))
                serverRequest(7, "item/fileChange/requestApproval", fileChangeApproval("item-1"))
                awaitClient()
                serverRequest(8, "execCommandApproval", execApproval(listOf("ls", "-la")))
                awaitClient()
                serverRequest(9, "applyPatchApproval", patchApproval("${workspace.toAbsolutePath()}/src/Main.kt"))
                awaitClient()
                notification(agentMessage("msg-1", verdict, "final_answer"))
                turnCompleted()
            }

        review(server).getOrThrow()

        val (fileChange, exec, patch) = answers(server, 7, 8, 9)
        assertEquals(decision("decline"), fileChange)
        assertTrue("denied" in exec.getValue("decision").jsonObject, "$exec")
        assertTrue("denied" in patch.getValue("decision").jsonObject, "$patch")
        assertEquals(emptyList(), schema.problems("FileChangeRequestApprovalResponse", fileChange))
        assertEquals(emptyList(), schema.problems("ExecCommandApprovalResponse", exec))
        assertEquals(emptyList(), schema.problems("ApplyPatchApprovalResponse", patch))
    }

    @Test
    fun `bl-214 a read of the holdout folder is declined, the rejection naming the holdout, never the folder`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                serverRequest(7, "execCommandApproval", execApproval(listOf("python3", "-c", "print(open('$holdout/check.sh').read())")))
                awaitClient()
                notification(agentMessage("msg-1", verdict, "final_answer"))
                turnCompleted()
            }

        review(server, holdoutDir = holdout).getOrThrow()

        val rejection =
            answers(server, 7)
                .single()
                .getValue("decision")
                .jsonObject
                .getValue("denied")
                .jsonObject
                .getValue("rejection")
                .jsonPrimitive.content
        assertContains(rejection, "holdout folder")
        assertTrue(holdout.toString() !in rejection, rejection)
    }

    @Test
    fun `bl-214 a failed turn is a crash naming the turn failure`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                turnCompleted(status = TurnStatus.FAILED, error = "model refused")
            }

        val error = assertIs<RiskReviewError.Crashed>(review(server).exceptionOrNull())

        assertContains(error.message.orEmpty(), "turn_failed")
        assertContains(error.message.orEmpty(), "model refused")
    }

    @Test
    fun `bl-214 a turn with no agent message is a crash`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                turnCompleted()
            }

        val error = assertIs<RiskReviewError.Crashed>(review(server).exceptionOrNull())

        assertContains(error.message.orEmpty(), "no answer")
    }

    @Test
    fun `bl-214 a turn past the timeout is a timeout, and the app server is stopped`() {
        val server =
            FakeAppServer.write(dir) {
                strictHandshake()
                shell("sleep 30")
                turnCompleted()
            }

        val started = System.nanoTime()
        val error = assertIs<RiskReviewError.Timeout>(review(server, timeoutMs = 1_500).exceptionOrNull())

        assertContains(error.message.orEmpty(), "1500 ms")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 20_000, "the review did not wait for the sleeping server")
    }

    @Test
    fun `bl-214 a command that cannot start is a crash`() {
        val error =
            assertIs<RiskReviewError.Crashed>(
                reviewWith(CodexAppServerSettings(command = "${dir.resolve("no-such-codex")} app-server")).exceptionOrNull(),
            )

        assertContains(error.message.orEmpty(), "agent_not_found")
    }

    @Test
    fun `bl-214 the reviewer names its command, never its env`() {
        val reviewer = CodexRiskReviewer(CodexAppServerSettings(command = "/opt/codex app-server"), mapOf("OPENAI_API_KEY" to "sk-secret"))

        assertEquals("CodexRiskReviewer(command=/opt/codex app-server)", reviewer.toString())
        assertNull(Regex("sk-secret").find(reviewer.toString()))
    }

    @Test
    fun `bl-214 commandOf quotes words so the runner splits them back unchanged`() {
        listOf(
            listOf("/usr/local/bin/codex", "app-server"),
            listOf("/Users/me/My Tools/codex", "app-server", "-c", "model=gpt-6.1-sol"),
            listOf("/tmp/it's here/codex", "app-server"),
        ).forEach { words ->
            assertEquals(words, CodexAppServerSettings(command = CodexAppServerSettings.commandOf(words)).argv, "$words")
        }
    }

    private fun review(
        server: FakeAppServer,
        model: String = "gpt-6.1-sol",
        timeoutMs: Long = 30_000,
        holdoutDir: Path? = null,
    ) = reviewWith(
        CodexAppServerSettings(server.command, readTimeout = FakeAppServer.READ_TIMEOUT, holdoutDir = holdoutDir),
        model,
        timeoutMs,
    )

    private fun reviewWith(
        settings: CodexAppServerSettings,
        model: String = "gpt-6.1-sol",
        timeoutMs: Long = 30_000,
    ) = CompletableFuture
        .supplyAsync {
            runBlocking {
                CodexRiskReviewer(settings, mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()))
                    .review(RiskReviewRequest(workspace, model, "Review this pull request.", timeoutMs))
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

    /** Loop's answers to the server requests [ids], each its `result`, in order. */
    private fun answers(
        server: FakeAppServer,
        vararg ids: Long,
    ): List<JsonObject> {
        val lines = received(server)
        return ids.map { id -> lines.single { it["id"] == JsonPrimitive(id) && "result" in it }.getValue("result").jsonObject }
    }

    private fun decision(value: String): JsonObject = buildJsonObject { put("decision", value) }

    private fun agentMessage(
        id: String,
        text: String,
        phase: String,
    ) = CodexJson.format.decodeFromString(
        loop.runner.codex.protocol.ServerNotification
            .serializer(),
        buildJsonObject {
            put("method", "item/completed")
            putJsonObject("params") {
                putJsonObject("item") {
                    put("type", "agentMessage")
                    put("id", id)
                    put("text", text)
                    put("phase", phase)
                }
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("completedAtMs", 1L)
            }
        }.toString(),
    )

    private fun commandApproval(command: String): JsonObject =
        buildJsonObject {
            put("itemId", "item-c")
            put("threadId", FakeAppServer.THREAD_ID)
            put("turnId", FakeAppServer.TURN_ID)
            put("startedAtMs", 1L)
            put("command", command)
            put("cwd", workspace.toString())
        }

    private fun fileChangeStarted(
        item: String,
        path: String,
    ): String =
        buildJsonObject {
            put("method", "item/started")
            putJsonObject("params") {
                put("threadId", FakeAppServer.THREAD_ID)
                put("turnId", FakeAppServer.TURN_ID)
                put("startedAtMs", 1L)
                putJsonObject("item") {
                    put("type", "fileChange")
                    put("id", item)
                    put("status", "inProgress")
                    put(
                        "changes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("path", path)
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
        }.toString()

    private fun fileChangeApproval(item: String): JsonObject =
        buildJsonObject {
            put("itemId", item)
            put("threadId", FakeAppServer.THREAD_ID)
            put("turnId", FakeAppServer.TURN_ID)
            put("startedAtMs", 1L)
        }

    private fun execApproval(command: List<String>): JsonObject =
        buildJsonObject {
            put("conversationId", FakeAppServer.THREAD_ID)
            put("callId", "call-1")
            put("approvalId", JsonNull)
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
            put("cwd", workspace.toString())
            put("parsedCmd", buildJsonArray {})
        }

    private fun patchApproval(path: String): JsonObject =
        buildJsonObject {
            put("conversationId", FakeAppServer.THREAD_ID)
            put("callId", "call-2")
            putJsonObject("fileChanges") {
                putJsonObject(path) {
                    put("type", "update")
                    put("unified_diff", "@@ -1 +1 @@\n-a\n+b\n")
                    put("move_path", JsonNull)
                }
            }
        }

    private companion object {
        const val TIMEOUT_SECONDS = 60L

        /** The recorded `thread/tokenUsage/updated` of `success.jsonl`, on the fake's thread and turn. */
        val USAGE: String =
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"${FakeAppServer.THREAD_ID}",""" +
                """"turnId":"${FakeAppServer.TURN_ID}","tokenUsage":{"total":{"totalTokens":15412,"inputTokens":15407,""" +
                """"cachedInputTokens":12288,"cacheWriteInputTokens":0,"outputTokens":5,"reasoningOutputTokens":0},""" +
                """"last":{"totalTokens":15412,"inputTokens":15407,"cachedInputTokens":12288,"cacheWriteInputTokens":0,""" +
                """"outputTokens":5,"reasoningOutputTokens":0},"modelContextWindow":475000}},"emittedAtMs":1794921218134}"""
    }
}
