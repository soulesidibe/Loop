package loop.runner.acp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.runner.process.ProcessGroupLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AcpFramingTest {
    @Test
    fun `decodes valid JSON-RPC 2_0 request, notification, response, and error frames`() {
        val request =
            AcpFraming.decode(
                """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"toolCall":{"name":"read_file"}}}""",
            )
        val req = assertIs<AcpFrame.Request>(request)
        assertEquals(JsonPrimitive(7), req.id)
        assertEquals("session/request_permission", req.method)

        val notification =
            AcpFraming.decode(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1"}}""",
            )
        val notif = assertIs<AcpFrame.Notification>(notification)
        assertEquals("session/update", notif.method)

        val response = AcpFraming.decode("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        val resp = assertIs<AcpFrame.Response>(response)
        assertEquals(JsonPrimitive(1), resp.id)

        val error =
            AcpFraming.decode(
                """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Unknown model ID"}}""",
            )
        val err = assertIs<AcpFrame.Error>(error)
        assertEquals(JsonPrimitive(3), err.id)
        assertEquals(-32602L, err.code)
        assertEquals("Unknown model ID", err.message)
    }

    @Test
    fun `rejects malformed lines with fixed reasons that never quote the raw line`() {
        val secretPayload = "super-secret-line-content"
        val cases =
            listOf(
                "" to "empty line",
                "   " to "empty line",
                "{$secretPayload" to "not JSON",
                "[\"$secretPayload\"]" to "not a JSON object",
                """{"jsonrpc":"1.0","id":1,"result":{"x":"$secretPayload"}}""" to "unsupported jsonrpc version",
                """{"jsonrpc":"2.0","id":1,"result":{},"error":{"code":-1,"message":"$secretPayload"}}""" to
                    "both result and error present",
                """{"jsonrpc":"2.0","id":true,"method":"$secretPayload"}""" to "invalid request id",
                """{"jsonrpc":"2.0","id":1,"error":{"code":"bad","message":"$secretPayload"}}""" to "invalid error code",
                """{"jsonrpc":"2.0","foo":"$secretPayload"}""" to "not a JSON-RPC message",
            )

        for ((line, expectedReason) in cases) {
            val malformed = assertIs<AcpFrame.Malformed>(AcpFraming.decode(line), line)
            assertEquals(expectedReason, malformed.reason, line)
            assertFalse(secretPayload in malformed.reason, malformed.reason)
        }
    }

    @Test
    fun `rejects lines longer than ProcessGroupLauncher MAX_LINE_BYTES`() {
        assertEquals(ProcessGroupLauncher.MAX_LINE_BYTES, AcpFraming.MAX_LINE_BYTES)
        val oversized = "a".repeat(AcpFraming.MAX_LINE_BYTES + 1)

        val malformed = assertIs<AcpFrame.Malformed>(AcpFraming.decode(oversized))
        assertEquals("line longer than 10 MB", malformed.reason)
    }

    @Test
    fun `encodes JSON-RPC 2_0 request, notification, response, and error lines`() {
        val reqLine = AcpFraming.request(1, "initialize", buildJsonObject { put("protocolVersion", 1) })
        assertEquals('\n', reqLine.last())
        val decodedReq = assertIs<AcpFrame.Request>(AcpFraming.decode(reqLine.trimEnd('\n')))
        assertEquals(JsonPrimitive(1), decodedReq.id)
        assertEquals("initialize", decodedReq.method)

        val errLine = AcpFraming.errorResponse(JsonPrimitive(2), -32601, "method not found")
        val decodedErr = assertIs<AcpFrame.Error>(AcpFraming.decode(errLine.trimEnd('\n')))
        assertEquals(-32601L, decodedErr.code)
        assertEquals("method not found", decodedErr.message)
    }

    @Test
    fun `bl-240 the echo of an error response omits its data and keeps jsonrpc, id, code and message`() {
        val data = """{"metadata":{"email":"someone@example.com","consumer":"projects/735190462281"}}"""
        val line = """{"jsonrpc":"2.0","id":4,"error":{"code":-32000,"message":"Resource exhausted","data":$data}}"""

        val echo = AcpFraming.echo(AcpFraming.decode(line))

        assertEquals(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32000,"message":"Resource exhausted","data":"[error data omitted, ${data.length} chars]"}}""",
            echo,
        )
    }

    @Test
    fun `bl-240 members of an error response besides code and message are omitted too`() {
        val line =
            """{"jsonrpc":"2.0","id":null,"account":"735190462281","error":{"code":429,"message":"quota","status":"someone@example.com"}}"""

        val echo = AcpFraming.echo(AcpFraming.decode(line))

        assertEquals(
            """{"jsonrpc":"2.0","id":null,"error":{"code":429,"message":"quota","data":"[error data omitted, 35 chars]"}}""",
            echo,
        )
    }

    @Test
    fun `bl-240 every other line is echoed as read, byte for byte`() {
        val lines =
            listOf(
                """{ "jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s1"} }""",
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""",
                """{"jsonrpc":"2.0","id":2, "error":{"code":-32601,"message":"method not found"}}""",
                """{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"no data","data":null}}""",
                """not json {"error":{"data":"someone@example.com"}}""",
                """{"jsonrpc":"2.0","id":5,"error":{"code":"bad","message":"x","data":{"email":"e"}}}""",
                "",
            )

        lines.forEach { line -> assertEquals(line, AcpFraming.echo(AcpFraming.decode(line))) }
    }
}
