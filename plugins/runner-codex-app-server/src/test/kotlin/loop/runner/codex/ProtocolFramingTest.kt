package loop.runner.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** m3-17, 17.5 item 8: the app server's stdout is one JSON-RPC message per line, and a bad line is reported, never thrown. */
class ProtocolFramingTest {
    /** A real `initialize` and ephemeral `thread/start` exchange with codex 0.167.0, paths and ids scrubbed (m3-02). */
    private val real =
        javaClass
            .getResource("/real/initialize_and_thread_start.jsonl")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    @Test
    fun `17_5 every line of the real exchange decodes as a response or a notification`() {
        val frames = real.map(ProtocolFraming::decode)

        assertEquals(
            listOf("Response", "Notification", "Response", "Notification", "Notification", "Notification", "Notification"),
            frames.map { it::class.simpleName },
        )
        assertEquals(JsonPrimitive(1), assertIs<Frame.Response>(frames[0]).message.id)
        assertEquals("thread/started", assertIs<Frame.Notification>(frames[3]).message.method)
        frames.zip(real).forEach { (frame, line) -> assertEquals(line, frame.line) }
    }

    @Test
    fun `a server request and an error response decode with their ids`() {
        val request = ProtocolFraming.decode("""{"id":7,"method":"item/tool/call","params":{"tool":"move"}}""")
        val error = ProtocolFraming.decode("""{"id":"a","error":{"code":-32601,"message":"no such method"}}""")

        val call = assertIs<Frame.Request>(request).message
        assertEquals(JsonPrimitive(7), call.id)
        assertEquals("item/tool/call", call.method)
        val failed = assertIs<Frame.Error>(error).message
        assertEquals("a", failed.id.jsonPrimitive.content)
        assertEquals(-32601L, failed.error.code)
    }

    @Test
    fun `17_5 a malformed line is reported with a reason and never throws`() {
        val reasons =
            listOf(
                """{"id":1,"result":""",
                "codex warming up",
                "",
                "[1,2]",
                """{"jsonrpc":"2.0"}""",
                """{"id":1,"method":42}""",
                """{"id":{"nested":true},"result":{}}""",
            ).map { line -> assertIs<Frame.Malformed>(ProtocolFraming.decode(line), line).also { assertEquals(line, it.line) }.reason }

        assertEquals(
            listOf(
                "not JSON",
                "not JSON",
                "empty line",
                "not a JSON object",
                "not a JSON-RPC message",
                "invalid request",
                "invalid response",
            ),
            reasons,
        )
    }

    @Test
    fun `a reason never quotes the line, which may hold anything the server wrote`() {
        val line = """{"id":1,"method":"x","params":"sk-secret-value""""

        val malformed = assertIs<Frame.Malformed>(ProtocolFraming.decode(line))

        assertTrue("sk-secret-value" !in malformed.reason, malformed.reason)
    }
}
