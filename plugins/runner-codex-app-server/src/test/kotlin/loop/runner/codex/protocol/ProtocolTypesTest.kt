package loop.runner.codex.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import loop.runner.codex.protocol.v2.AskForApproval
import loop.runner.codex.protocol.v2.CodexErrorInfo
import loop.runner.codex.protocol.v2.MultiAgentMode
import loop.runner.codex.protocol.v2.SandboxPolicy
import loop.runner.codex.protocol.v2.SessionSource
import loop.runner.codex.protocol.v2.ThreadStartResponse
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The generated protocol types against lines of the pinned codex app server (m3-02). */
class ProtocolTypesTest {
    private val json = CodexJson.format

    /** A real `initialize` and ephemeral `thread/start` exchange with codex 0.167.0, paths and ids scrubbed. */
    private val real =
        javaClass
            .getResource("/real/initialize_and_thread_start.jsonl")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    @Test
    fun `the real responses decode into the generated response types`() {
        val initialize = json.decodeFromJsonElement(InitializeResponse.serializer(), result(real[0]))
        assertEquals("macos", initialize.platformOs)

        val thread = json.decodeFromJsonElement(ThreadStartResponse.serializer(), result(real[2]))
        assertEquals("/workspace", thread.cwd)
        assertEquals(AskForApproval.OnRequest, thread.approvalPolicy)
        assertEquals(SandboxPolicy.ReadOnly(networkAccess = false), thread.sandbox)
        assertEquals(thread.thread.id, thread.thread.sessionId)
    }

    @Test
    fun `the real notifications decode into server notification variants`() {
        val notifications = real.filter { it.startsWith("{\"method\"") }
        val decoded = notifications.map { json.decodeFromString(ServerNotification.serializer(), it) }
        assertIs<ServerNotification.RemoteControlStatusChanged>(decoded[0])
        val started = assertIs<ServerNotification.ThreadStarted>(decoded[1])
        assertEquals("/workspace", started.params.thread.cwd)
        assertIs<ServerNotification.McpServerStartupStatusUpdated>(decoded[2])
        assertIs<ServerNotification.AccountUpdated>(decoded[3])
        val ready = assertIs<ServerNotification.McpServerStartupStatusUpdated>(decoded[4])
        assertEquals("codex_apps", ready.params.name)
    }

    @Test
    fun `a client request writes its method through the base type`() {
        val request =
            ClientRequest.Initialize(
                id = JsonPrimitive(1),
                params = InitializeParams(clientInfo = ClientInfo(name = "loop", version = "1")),
            )
        val written = json.encodeToJsonElement(ClientRequest.serializer(), request)
        val expected =
            """{"method":"initialize","id":1,"params":{"clientInfo":{"name":"loop","version":"1"}}}"""
        assertEquals(json.parseToJsonElement(expected), written)
        assertEquals(request, json.decodeFromJsonElement(ClientRequest.serializer(), written))
    }

    @Test
    fun `an externally tagged enum reads and writes its string and object forms`() {
        assertEquals(AskForApproval.Never, json.decodeFromString(AskForApproval.serializer(), "\"never\""))
        assertEquals("\"on-request\"", json.encodeToString(AskForApproval.serializer(), AskForApproval.OnRequest))

        val granular =
            AskForApproval.Granular(mcpElicitations = false, rules = true, sandboxApproval = true)
        val written = json.encodeToString(AskForApproval.serializer(), granular)
        assertEquals("""{"granular":{"mcp_elicitations":false,"rules":true,"sandbox_approval":true}}""", written)
        assertEquals(granular, json.decodeFromString(AskForApproval.serializer(), written))
    }

    @Test
    fun `a variant wrapping a plain value is a value class`() {
        // The real thread/start response carries "multiAgentMode": "explicitRequestOnly", a field its 0.167.0 schema
        // does not declare, ignored there.
        assertEquals(MultiAgentMode.ExplicitRequestOnly, json.decodeFromString(MultiAgentMode.serializer(), "\"explicitRequestOnly\""))
        val custom = json.decodeFromString(SessionSource.serializer(), """{"custom":"loop"}""")
        assertEquals(SessionSource.Custom("loop"), custom)
        assertEquals("""{"custom":"loop"}""", json.encodeToString(SessionSource.serializer(), custom))
    }

    @Test
    fun `an error kind the schema does not know is kept as Unknown`() {
        val known = json.decodeFromString(CodexErrorInfo.serializer(), """{"httpConnectionFailed":{"httpStatusCode":502}}""")
        assertEquals(CodexErrorInfo.HttpConnectionFailed(httpStatusCode = 502), known)
        assertEquals(CodexErrorInfo.UsageLimitExceeded, json.decodeFromString(CodexErrorInfo.serializer(), "\"usageLimitExceeded\""))

        val unknown = json.decodeFromString(CodexErrorInfo.serializer(), "\"somethingNew\"")
        assertEquals(CodexErrorInfo.Unknown(JsonPrimitive("somethingNew")), unknown)
        assertEquals("\"somethingNew\"", json.encodeToString(CodexErrorInfo.serializer(), unknown))
    }

    @Test
    fun `the pinned version is the catalog's and the schema's`() {
        assertEquals(System.getProperty("loop.codexVersion"), CodexCliVersion.PINNED)
        assertEquals(Path.of(System.getProperty("loop.codexSchemaVersion")).readText().trim(), CodexCliVersion.PINNED)
    }

    private fun result(line: String): JsonObject = json.parseToJsonElement(line).jsonObject["result"]!!.jsonObject
}
