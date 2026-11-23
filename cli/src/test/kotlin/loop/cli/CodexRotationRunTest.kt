package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m3-23, story 42 with plan 8.3 and 8.4 on Codex, end to end with no model call: `loop run --once` with the Codex runner
 * on a scripted app server whose first spawn reports the occupancy of its thread in the `last` usage of
 * `thread/tokenUsage/updated`. Over the soft limit the session rotates at its turn end, over the hard limit Loop
 * interrupts the turn with `turn/interrupt` and rotates; either way a second app server starts a new thread whose first
 * turn gets the rotation prompt with the session brief. The rotation shows in `loop trace` and each session's peak in
 * the ledger. The window is `context.windows` for `openai/gpt-5.1-codex-max`, the model of the recorded `thread/start`
 * answer, set to 1000 tokens.
 */
class CodexRotationRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `m3-23 a Codex session over the soft limit rotates at its turn end onto a new thread with the session brief`() {
        val calls = root.resolve("codex-calls")
        fixture.workflow.writeText(workflow(rotatingCodex(calls, hardLimit = false)))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        assertEquals("review", fixture.stateOf("t-1"), result.output + result.stderr)
        // Two app servers in the same workspace, each starting its own thread.
        assertEquals("2", calls.resolve("count").readLines().single())
        assertEquals(1, listOf("cwd-1", "cwd-2").map { calls.resolve(it).readLines().single() }.distinct().size)
        listOf(1, 2).forEach { n -> assertEquals(1, received(calls, n).count { it.method() == "thread/start" }, "spawn $n") }
        val first = turnInput(calls, 1)
        val second = turnInput(calls, 2)
        assertFalse("kind=" in first, first)
        assertContains(second, "Work on t-1 kind=rotation BRIEF ### Goal and acceptance criteria")
        assertContains(second, "- Rotations: 1")
        assertTrue(received(calls, 1).none { it.method() == "turn/interrupt" }, "${received(calls, 1)}")
        val rotated = dataOf("session_rotated")
        listOf(
            "\"reason\":\"soft_limit\"",
            "\"occupancy_tokens\":\"220\"",
            "\"window\":\"1000\"",
            "\"fraction\":\"0.2200\"",
            "\"rotation\":\"1\"",
            "\"old_thread_id\":\"$THREAD_1\"",
            "\"new_thread_id\":\"$THREAD_2\"",
        ).forEach { assertContains(rotated, it) }
        val lines = ledger()
        assertEquals(2, lines.size, "$lines")
        assertPeak(lines[0], THREAD_1, peak = 220, fraction = "0.22", rotations = 0)
        assertPeak(lines[1], THREAD_2, peak = 50, fraction = "0.05", rotations = 1)
        // bl-147: the workflow names no model, so each line holds the model the thread/start answer named.
        lines.forEach { assertContains(it, "\"model\":\"openai/gpt-5.1-codex-max\"") }
        val trace = LoopCommand(host()).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(
            trace.stdout,
            "reason=soft_limit rotation=1/5 occupancy_tokens=220 window=1000 fraction=0.2200 from=0a000001 to=0b000002",
        )
        assertContains(trace.stdout, "latest_tokens=220 peak_tokens=220 window=1000 fraction=0.2200 model=openai/gpt-5.1-codex-max")
        assertContains(trace.stdout, "latest_tokens=50 peak_tokens=50 window=1000 fraction=0.0500 model=openai/gpt-5.1-codex-max")
    }

    @Test
    fun `m3-23 a Codex session over the hard limit has its turn interrupted, then rotates onto a new thread`() {
        val calls = root.resolve("codex-calls")
        fixture.workflow.writeText(workflow(rotatingCodex(calls, hardLimit = true)))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        assertEquals("review", fixture.stateOf("t-1"), result.output + result.stderr)
        assertEquals("2", calls.resolve("count").readLines().single())
        // Loop asked the first app server to interrupt its turn, on its thread and turn.
        val interrupt = received(calls, 1).single { it.method() == "turn/interrupt" }.getValue("params").jsonObject
        assertEquals(THREAD_1, interrupt.getValue("threadId").jsonPrimitive.content)
        assertEquals(TURN_1, interrupt.getValue("turnId").jsonPrimitive.content)
        assertContains(turnInput(calls, 2), "Work on t-1 kind=rotation BRIEF ### Goal and acceptance criteria")
        val rows = fixture.query("SELECT kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        // The interrupted turn is not a failed one.
        assertTrue(rows.none { it["kind"] == "turn_failed" }, "$rows")
        assertContains(rows.first { it["kind"] == "turn_completed" }["data"].orEmpty(), "\"interrupted\":\"hard_limit\"")
        val rotated = dataOf("session_rotated")
        listOf(
            "\"reason\":\"hard_limit\"",
            "\"occupancy_tokens\":\"300\"",
            "\"old_thread_id\":\"$THREAD_1\"",
            "\"new_thread_id\":\"$THREAD_2\"",
        ).forEach { assertContains(rotated, it) }
        val lines = ledger()
        assertEquals(2, lines.size, "$lines")
        // The peak holds the request Codex reported after the interrupt, 301 tokens.
        assertPeak(lines[0], THREAD_1, peak = 301, fraction = "0.301", rotations = 0)
        assertContains(lines[0], "\"interrupted\":\"hard_limit\"")
        // The usage Codex reported after the interrupt counts: a total of 601 input tokens and 2 output tokens.
        assertContains(lines[0], "\"tokens\":603")
        assertPeak(lines[1], THREAD_2, peak = 50, fraction = "0.05", rotations = 1)
        val trace = LoopCommand(host()).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(
            trace.stdout,
            "reason=hard_limit rotation=1/5 occupancy_tokens=300 window=1000 fraction=0.3000 from=0a000001 to=0b000002",
        )
        assertContains(trace.stdout, "interrupted=hard_limit")
    }

    // Helpers.

    // m3-14: the prompt limit at the soft limit, 200 tokens of the 1000 token window, so the rotated prompt fits whole
    // and shows the brief as it is built.
    private fun workflow(codex: Path): String =
        """
        |---
        |tracker:
        |  kind: files
        |  active_states: [todo]
        |  required_labels: [ready-for-agent]
        |context:
        |  prompt_limit: 0.20
        |  hard_limit: 0.25 # m3-11 limits, above the default of 0.21 (m4-02), so 220 tokens rotate at the soft limit
        |  windows:
        |    $MODEL: 1000
        |workspace:
        |  root: ./workspaces
        |runner:
        |  kind: codex-app-server
        |runners:
        |  codex:
        |    command: "$codex"
        |    strict_mcp: false
        |    turn_timeout_ms: 20000
        |retries:
        |  max_attempts: 1
        |---
        |Work on {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}{% if loop.session_brief %} BRIEF {{ loop.session_brief }}{% endif %}
        |
        """.trimMargin()

    /**
     * Writes a scripted app server whose spawns count themselves in [calls] and record their working directory and every
     * client line, `received-<n>.jsonl`. Each answers the startup with the real codex 0.167.0 answers (m3-02) and its own
     * thread. The first reports an occupancy of 220 tokens and completes its turn, or with [hardLimit] reports 300, waits
     * for the next client line, the interrupt, then reports its last request, 301 input tokens, and ends its turn as
     * `interrupted`. The second reports 50, moves the issue to review through `loop tool` and completes its turn.
     */
    private fun rotatingCodex(
        calls: Path,
        hardLimit: Boolean,
    ): Path {
        val dir = root.resolve("codex-bin").createDirectories()
        calls.createDirectories()
        var files = 0

        fun cat(text: String): String = "cat '${dir.resolve("line-${++files}.json").also { it.writeText(text + "\n") }}'"
        val read = "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$calls/received-'\"\$n\"'.jsonl'"
        val first =
            if (hardLimit) {
                listOf(
                    cat(tokenUsage(THREAD_1, TURN_1, last = 300, total = 300)),
                    read,
                    cat(tokenUsage(THREAD_1, TURN_1, last = 301, total = 601)),
                    cat(turnCompleted(THREAD_1, TURN_1, "interrupted")),
                )
            } else {
                listOf(cat(tokenUsage(THREAD_1, TURN_1, last = 220, total = 220)), cat(turnCompleted(THREAD_1, TURN_1, "completed")))
            }
        val script =
            listOf(
                "#!/bin/bash",
                "n=$(cat '$calls/count' 2>/dev/null || echo 0)",
                "n=$((n + 1))",
                "echo \"\$n\" > '$calls/count'",
                "pwd > '$calls/cwd-'\"\$n\"",
                read,
                cat(INITIALIZE),
                read,
                read,
                "if [ \"\$n\" = 1 ]; then",
                cat(threadStart(THREAD_1)),
                read,
                cat(turnStart(TURN_1)),
            ) + first +
                listOf(
                    "else",
                    cat(threadStart(THREAD_2)),
                    read,
                    cat(turnStart(TURN_2)),
                    cat(tokenUsage(THREAD_2, TURN_2, last = 50, total = 50)),
                    "loop tool move review > /dev/null",
                    cat(turnCompleted(THREAD_2, TURN_2, "completed")),
                    "fi",
                    "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$calls/received-'\"\$n\"'.jsonl'; done",
                    "exit 0",
                    "",
                )
        val file = dir.resolve("codex")
        file.writeText(script.joinToString("\n"))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    private fun received(
        calls: Path,
        spawn: Int,
    ): List<JsonObject> {
        val file = calls.resolve("received-$spawn.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun JsonObject.method(): String? = get("method")?.jsonPrimitive?.content

    /** The text of the first `turn/start` spawn [spawn] read: the session's first prompt. */
    private fun turnInput(
        calls: Path,
        spawn: Int,
    ): String =
        received(calls, spawn)
            .first { it.method() == "turn/start" }
            .getValue("params")
            .jsonObject
            .getValue("input")
            .jsonArray
            .single()
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    private fun dataOf(kind: String) = fixture.query("SELECT data FROM events WHERE kind = '$kind'").single()["data"].orEmpty()

    private fun ledger(): List<String> =
        fixture.loopHome
            .resolve("ledger")
            .resolve("${fixture.projectId()}.jsonl")
            .readLines()

    private fun assertPeak(
        line: String,
        thread: String,
        peak: Int,
        fraction: String,
        rotations: Int,
    ) {
        listOf(
            "\"thread_id\":\"$thread\"",
            "\"peak_tokens\":$peak",
            "\"window\":1000",
            "\"peak_fraction\":$fraction",
            "\"rotations\":$rotations",
        ).forEach { assertContains(line, it) }
    }

    private fun host(): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
        )

    private companion object {
        const val MODEL = "openai/gpt-5.1-codex-max"
        const val THREAD_1 = "0a000001-0000-7000-8000-000000000001"
        const val THREAD_2 = "0b000002-0000-7000-8000-000000000002"
        const val TURN_1 = "0a000001-0000-7000-8000-0000000000a1"
        const val TURN_2 = "0b000002-0000-7000-8000-0000000000b2"

        /** The real `initialize` answer of codex 0.167.0, recorded in m3-02. */
        const val INITIALIZE =
            """{"id":1,"result":{"userAgent":"loop/0.167.0 (Mac OS; arm64) unknown (loop; 0.0.0)","codexHome":"/home/user/.codex",""" +
                """"platformFamily":"unix","platformOs":"macos"}}"""

        /** The real `thread/start` answer of codex 0.167.0, recorded in m3-02, its thread renamed [thread]. */
        fun threadStart(thread: String): String =
            """{"id":2,"result":{"thread":{"id":"$thread","environments":[{"environmentId":"local","cwd":"/workspace",""" +
                """"runtimeWorkspaceRoots":["/workspace"]}],"extra":null,"sessionId":"$thread","forkedFromId":null,""" +
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

        fun turnStart(turn: String): String = """{"id":3,"result":{"turn":{"id":"$turn","items":[],"status":"inProgress"}}}"""

        /** A `thread/tokenUsage/updated` whose `last` request had [last] input tokens and whose `total` has [total], 1 output token each. */
        fun tokenUsage(
            thread: String,
            turn: String,
            last: Int,
            total: Int,
        ): String {
            fun breakdown(
                input: Int,
                output: Int,
            ) =
                """{"cachedInputTokens":0,"inputTokens":$input,"outputTokens":$output,"reasoningOutputTokens":0,"totalTokens":${input + output}}"""
            val outputs = if (total == last) 1 else 2
            return """{"method":"thread/tokenUsage/updated","params":{"threadId":"$thread","turnId":"$turn",""" +
                """"tokenUsage":{"last":${breakdown(last, 1)},"total":${breakdown(total, outputs)},"modelContextWindow":258400}}}"""
        }

        fun turnCompleted(
            thread: String,
            turn: String,
            status: String,
        ): String = """{"method":"turn/completed","params":{"threadId":"$thread","turn":{"id":"$turn","items":[],"status":"$status"}}}"""
    }
}
