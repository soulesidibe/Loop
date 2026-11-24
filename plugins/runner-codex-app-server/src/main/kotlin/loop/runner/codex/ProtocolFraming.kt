package loop.runner.codex

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCError
import loop.runner.codex.protocol.JSONRPCNotification
import loop.runner.codex.protocol.JSONRPCRequest
import loop.runner.codex.protocol.JSONRPCResponse

/**
 * One line of the app server's stdout, as [ProtocolFraming] read it (m3-17). [line] is the line as written, without
 * its line break. The message types are the JSON-RPC envelope of the pinned schema; their `params` and `result` stay
 * [JsonElement], read against the method's own generated type by whoever handles the method.
 */
sealed interface Frame {
    val line: String

    /** A request from the server, which waits for the client's response, an approval or a tool call for example. */
    data class Request(
        override val line: String,
        val message: JSONRPCRequest,
    ) : Frame

    /** A notification from the server, which needs no response. */
    data class Notification(
        override val line: String,
        val message: JSONRPCNotification,
    ) : Frame

    /** The result of a request the client sent. */
    data class Response(
        override val line: String,
        val message: JSONRPCResponse,
    ) : Frame

    /** The error answer to a request the client sent. */
    data class Error(
        override val line: String,
        val message: JSONRPCError,
    ) : Frame

    /**
     * A line that is not a message of the protocol. [reason] says why in a fixed phrase and never quotes the line, which
     * may hold anything the server wrote. The reader logs it and goes on: a bad line never ends a turn by itself.
     */
    data class Malformed(
        override val line: String,
        val reason: String,
    ) : Frame
}

/**
 * The framing of the pinned codex app server protocol on stdio (m3-17, 17.5 item 8): one JSON object per line on
 * stdout, with no `jsonrpc` member, checked on the real server in m3-02. A line break inside a message is always
 * escaped, so a line is a whole message once its line break arrives, however the bytes were split across reads.
 *
 * The envelope is told apart by its members, as the schema's untagged `JSONRPCMessage` union defines it: `method`
 * and `id` make a request, `method` alone a notification, `id` and `result` a response, `id` and `error` an error.
 * Members the schema does not know are ignored, as [CodexJson.format] does everywhere.
 *
 * A line longer than [MAX_LINE_BYTES] in UTF-8 is malformed whatever it holds (bl-128). The launcher already drops such
 * a line, so this holds for a line that did not come through it.
 */
object ProtocolFraming {
    /** The longest line, 10 MB, the max line size of spec 10.1. */
    const val MAX_LINE_BYTES: Int = 10 * 1024 * 1024

    /** Reads one stdout [line]; never throws. */
    fun decode(line: String): Frame {
        if (line.isBlank()) return Frame.Malformed(line, "empty line")
        if (utf8Length(line) > MAX_LINE_BYTES) return Frame.Malformed(line, "line longer than 10 MB")
        val element =
            try {
                CodexJson.format.parseToJsonElement(line)
            } catch (e: SerializationException) {
                return Frame.Malformed(line, "not JSON")
            }
        if (element !is JsonObject) return Frame.Malformed(line, "not a JSON object")
        val hasId = "id" in element
        return when {
            "method" in element && hasId -> read(line, element, JSONRPCRequest.serializer(), "request") { Frame.Request(line, it) }
            "method" in element -> read(line, element, JSONRPCNotification.serializer(), "notification") { Frame.Notification(line, it) }
            hasId && "result" in element -> read(line, element, JSONRPCResponse.serializer(), "response") { Frame.Response(line, it) }
            hasId && "error" in element -> read(line, element, JSONRPCError.serializer(), "error response") { Frame.Error(line, it) }
            else -> Frame.Malformed(line, "not a JSON-RPC message")
        }
    }

    /** The UTF-8 length of [line], counted only when it can pass the maximum: a UTF-16 unit takes at most 3 bytes. */
    private fun utf8Length(line: String): Int =
        when {
            line.length > MAX_LINE_BYTES -> line.length
            line.length <= MAX_LINE_BYTES / 3 -> line.length
            else -> line.toByteArray(Charsets.UTF_8).size
        }

    private fun <T> read(
        line: String,
        element: JsonObject,
        serializer: KSerializer<T>,
        kind: String,
        frame: (T) -> Frame,
    ): Frame =
        try {
            frame(CodexJson.format.decodeFromJsonElement(serializer, element))
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is a wrong JSON type for a member.
            Frame.Malformed(line, "invalid $kind")
        }
}
