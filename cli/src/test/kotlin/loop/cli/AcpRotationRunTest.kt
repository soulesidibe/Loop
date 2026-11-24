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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end context rotation and preflight tests for `runner.kind: acp` (`preset: agy`) with no
 * real model call: verifies soft-limit rotation, hard-limit `session/cancel` interrupt with preserved
 * usage, and `Host.runnerPreflight` for `agy`.
 */
class AcpRotationRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `an ACP agy session over the soft limit rotates at turn end onto a fresh process with the session brief`() {
        val calls = root.resolve("acp-calls-soft")
        fixture.workflow.writeText(workflow(rotatingAgy(calls, hardLimit = false)))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        assertEquals("review", fixture.stateOf("t-1"), result.output + result.stderr)
        assertEquals("2", calls.resolve("count").readLines().single())
        assertEquals(1, listOf("cwd-1", "cwd-2").map { calls.resolve(it).readLines().single() }.distinct().size)

        val firstPrompt = promptText(calls, 1)
        val secondPrompt = promptText(calls, 2)
        assertFalse("kind=" in firstPrompt, firstPrompt)
        assertContains(secondPrompt, "Work on t-1 kind=rotation BRIEF ### Goal and acceptance criteria")
        assertContains(secondPrompt, "- Rotations: 1")
        assertTrue(received(calls, 1).none { it.method() == "session/cancel" })

        val rotated = dataOf("session_rotated")
        listOf(
            "\"reason\":\"soft_limit\"",
            "\"occupancy_tokens\":\"220\"",
            "\"window\":\"1000\"",
            "\"fraction\":\"0.2200\"",
            "\"rotation\":\"1\"",
        ).forEach { assertContains(rotated, it) }

        val lines = ledger()
        assertEquals(2, lines.size, "$lines")
        assertContains(lines[0], "\"peak_tokens\":220")
        assertContains(lines[0], "\"model\":\"google/gemini-4-neon-high\"")
        assertContains(lines[1], "\"peak_tokens\":50")
        assertContains(lines[1], "\"rotations\":1")

        val trace = LoopCommand(host()).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(trace.stdout, "reason=soft_limit rotation=1/5 occupancy_tokens=220 window=1000 fraction=0.2200")
        assertContains(trace.stdout, "model=google/gemini-4-neon-high")
    }

    @Test
    fun `an ACP agy session over the hard limit interrupts with session cancel, preserves usage, and rotates`() {
        val calls = root.resolve("acp-calls-hard")
        fixture.workflow.writeText(workflow(rotatingAgy(calls, hardLimit = true)))
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output + result.stderr)
        assertEquals("review", fixture.stateOf("t-1"), result.output + result.stderr)
        assertEquals("2", calls.resolve("count").readLines().single())

        val cancel = received(calls, 1).single { it.method() == "session/cancel" }
        val cancelSessionId =
            cancel
                .getValue("params")
                .jsonObject
                .getValue("sessionId")
                .jsonPrimitive.content
        assertEquals("agy-sess-1", cancelSessionId)
        assertContains(promptText(calls, 2), "Work on t-1 kind=rotation BRIEF ### Goal and acceptance criteria")

        val rows = fixture.query("SELECT kind, data FROM events WHERE issue_id = 'default:t-1' ORDER BY seq")
        assertTrue(rows.none { it["kind"] == "turn_failed" }, "$rows")
        assertContains(rows.first { it["kind"] == "turn_completed" }["data"].orEmpty(), "\"interrupted\":\"hard_limit\"")

        val rotated = dataOf("session_rotated")
        assertContains(rotated, "\"reason\":\"hard_limit\"")
        assertContains(rotated, "\"occupancy_tokens\":\"300\"")

        val lines = ledger()
        assertEquals(2, lines.size, "$lines")
        assertContains(lines[0], "\"interrupted\":\"hard_limit\"")
        assertContains(lines[0], "\"tokens\":603")
        assertContains(lines[1], "\"peak_tokens\":50")
    }

    @Test
    fun `runnerPreflight checks agy binary reachability, major version, and models warnings`() {
        val binDir = root.resolve("fake-bin").createDirectories()
        val fakeAgy = binDir.resolve("agy")
        fakeAgy.writeText(
            """
            #!/bin/bash
            if [ "$1" = "--version" ]; then echo "2.0.0"; exit 0; fi
            exit 0
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(fakeAgy, PosixFilePermissions.fromString("rwxr-xr-x"))
        fixture.workflow.writeText(workflow(fakeAgy))
        val loaded = host().workflowLoader().load(fixture.workflow).getOrThrow()

        val wrongMajor = runnerPreflight(loaded, host().rawEnv)
        val wrongMajorError = assertNotNull(wrongMajor.error)
        assertContains(wrongMajorError, "not of the pinned major version 1")

        fakeAgy.writeText(
            """
            #!/bin/bash
            if [ "$1" = "--version" ]; then echo "1.5.16"; exit 0; fi
            if [ "$1" = "models" ]; then echo "gemini-3.8-flash-low  Flash"; exit 0; fi
            exit 0
            """.trimIndent() + "\n",
        )
        val modelWarn = runnerPreflight(loaded, host().rawEnv)
        assertNull(modelWarn.error)
        assertTrue(modelWarn.warnings.single().contains("was not listed by"), "${modelWarn.warnings}")
    }

    private fun workflow(agy: Path): String =
        """
        |---
        |tracker:
        |  kind: files
        |  active_states: [todo]
        |  required_labels: [ready-for-agent]
        |context:
        |  prompt_limit: 0.20
        |  hard_limit: 0.25
        |  windows:
        |    google/gemini-4-neon-high: 1000
        |workspace:
        |  root: ./workspaces
        |runner:
        |  kind: acp
        |runners:
        |  acp:
        |    command: "$agy acp"
        |    preset: agy
        |    model: gemini-4-neon-high
        |    read_timeout_ms: 20000
        |    turn_timeout_ms: 20000
        |retries:
        |  max_attempts: 1
        |---
        |Work on {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}{% if loop.session_brief %} BRIEF {{ loop.session_brief }}{% endif %}
        |
        """.trimMargin()

    private fun rotatingAgy(
        calls: Path,
        hardLimit: Boolean,
    ): Path {
        val dir = root.resolve("agy-bin-${if (hardLimit) "hard" else "soft"}").createDirectories()
        calls.createDirectories()
        val read = "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$calls/received-'\"\$n\"'.jsonl'"
        val turn1Steps =
            if (hardLimit) {
                listOf(
                    """printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"agy-sess-1","update":{"sessionUpdate":"usage_update","used":300,"size":1000}}}'""",
                    read,
                    """printf '%s\n' '{"jsonrpc":"2.0","id":4,"result":{"stopReason":"cancelled","usage":{"inputTokens":601,"cachedReadTokens":0,"cachedWriteTokens":0,"outputTokens":2,"totalTokens":603}}}'""",
                )
            } else {
                listOf(
                    """printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"agy-sess-1","update":{"sessionUpdate":"usage_update","used":220,"size":1000}}}'""",
                    """printf '%s\n' '{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn","usage":{"inputTokens":219,"cachedReadTokens":0,"cachedWriteTokens":0,"outputTokens":1,"totalTokens":220}}}'""",
                )
            }
        val script =
            listOf(
                "#!/bin/bash",
                "n=$(cat '$calls/count' 2>/dev/null || echo 0)",
                "n=$((n + 1))",
                "echo \"\$n\" > '$calls/count'",
                "pwd > '$calls/cwd-'\"\$n\"",
                read,
                """printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Antigravity ACP Server","version":"1.5.16"}}}'""",
                read,
                "if [ \"\$n\" = 1 ]; then",
                """printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"agy-sess-1"}}'""",
                read,
                """printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"modelId":"gemini-4-neon-high"}}'""",
                read,
            ) + turn1Steps +
                listOf(
                    "else",
                    """printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"agy-sess-2"}}'""",
                    read,
                    """printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"modelId":"gemini-4-neon-high"}}'""",
                    read,
                    """printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"agy-sess-2","update":{"sessionUpdate":"usage_update","used":50,"size":1000}}}'""",
                    "loop tool move review > /dev/null",
                    """printf '%s\n' '{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn","usage":{"inputTokens":49,"cachedReadTokens":0,"cachedWriteTokens":0,"outputTokens":1,"totalTokens":50}}}'""",
                    "fi",
                    "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$calls/received-'\"\$n\"'.jsonl'; done",
                    "exit 0",
                    "",
                )
        val file = dir.resolve("agy")
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

    private fun promptText(
        calls: Path,
        spawn: Int,
    ): String =
        received(calls, spawn)
            .first { it.method() == "session/prompt" }
            .getValue("params")
            .jsonObject
            .getValue("prompt")
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
}
