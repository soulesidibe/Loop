package loop.runner.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.runner.codex.protocol.CodexCliVersion
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCResponse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * bl-128, after m3-02: the startup refuses an app server whose codex major version is not the pinned one, read from the
 * `userAgent` of the `initialize` answer, `<originator>/<version> (...)`.
 */
class StartupHandshakeVersionTest {
    @TempDir
    lateinit var workspace: Path

    private fun handshake(pinned: String = CodexCliVersion.PINNED): StartupHandshake =
        StartupHandshake(workspace, "test-build", pinnedVersion = pinned).also { it.start("Work on c-01") }

    /** The real `initialize` answer of codex 0.167.0 with [userAgent] in place of its own. */
    private fun initializeAnswer(userAgent: String? = null): Frame {
        val real = FakeAppServer.initializeResult().jsonObject
        val result =
            buildJsonObject {
                real.forEach { (key, value) -> put(key, value) }
                if (userAgent != null) put("userAgent", userAgent)
            }
        val line = CodexJson.format.encodeToString(JSONRPCResponse.serializer(), JSONRPCResponse(JsonPrimitive(1), result))
        return ProtocolFraming.decode(line)
    }

    private fun methods(step: StartupHandshake.Step): List<String?> =
        assertIs<StartupHandshake.Step.Write>(step).lines.map {
            CodexJson.format
                .parseToJsonElement(it)
                .jsonObject["method"]
                ?.jsonPrimitive
                ?.content
        }

    @Test
    fun `the recorded answer of the pinned codex starts the thread`() {
        assertEquals(listOf("initialized", "thread/start"), methods(handshake().read(initializeAnswer())))
    }

    @Test
    fun `another minor or patch version of the pinned major starts the thread`() {
        listOf(
            "loop/0.167.0 (Mac OS; arm64) unknown (loop; 0.0.0)",
            "loop/0.169.2 (Ubuntu 24.4.0; x86_64) xterm (loop; 1.2.0)",
            "loop/0.200.0-alpha.3",
        ).forEach { userAgent ->
            assertEquals(listOf("initialized", "thread/start"), methods(handshake("0.167.0").read(initializeAnswer(userAgent))), userAgent)
        }
    }

    @Test
    fun `another major version fails the start naming the found and the pinned versions`() {
        val step = handshake("0.167.0").read(initializeAnswer("loop/1.0.0 (Mac OS; arm64) unknown (loop; 0.0.0)"))

        val failed = assertIs<StartupHandshake.Step.Failed>(step)
        assertEquals(
            "response_error: codex 1.0.0 is not of the pinned major version 0 (codex-cli 0.167.0)",
            failed.failure.reason,
        )
    }

    @Test
    fun `a user agent with no readable version fails the start and quotes nothing of it`() {
        listOf("", "codex", "loop/ (Mac OS)", "loop/latest (Mac OS)", "loop/1 (Mac OS)", "loop/1.0.0garbage").forEach { userAgent ->
            val failed = assertIs<StartupHandshake.Step.Failed>(handshake().read(initializeAnswer(userAgent)), userAgent)
            assertEquals("response_error: the initialize response names no codex version in its userAgent", failed.failure.reason)
        }
    }

    @Test
    fun `nothing more is written once the version is refused`() {
        val handshake = handshake("0.167.0")
        assertIs<StartupHandshake.Step.Failed>(handshake.read(initializeAnswer("loop/2.1.0")))

        assertEquals(null, handshake.pendingMethod)
    }
}
