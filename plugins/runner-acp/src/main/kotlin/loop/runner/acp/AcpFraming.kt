package loop.runner.acp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import loop.runner.process.ProcessGroupLauncher

/**
 * One JSON-RPC 2.0 frame decoded from an ACP agent's stdout line.
 */
sealed interface AcpFrame {
    val line: String

    /** A JSON-RPC 2.0 request from the agent, expecting a response with the same [id]. */
    data class Request(
        override val line: String,
        val id: JsonElement,
        val method: String,
        val params: JsonElement?,
    ) : AcpFrame

    /** A JSON-RPC 2.0 notification from the agent, expecting no response. */
    data class Notification(
        override val line: String,
        val method: String,
        val params: JsonElement?,
    ) : AcpFrame

    /** A JSON-RPC 2.0 success response to a client request [id]. */
    data class Response(
        override val line: String,
        val id: JsonElement,
        val result: JsonElement,
    ) : AcpFrame

    /** A JSON-RPC 2.0 error response to a client request [id]. */
    data class Error(
        override val line: String,
        val id: JsonElement?,
        val code: Long,
        val message: String,
        val data: JsonElement? = null,
    ) : AcpFrame

    /**
     * A stdout line that is not a valid JSON-RPC 2.0 message. [reason] is a fixed phrase and never
     * quotes the raw line.
     */
    data class Malformed(
        override val line: String,
        val reason: String,
    ) : AcpFrame
}

/**
 * JSON-RPC 2.0 framing over newline delimited JSON on stdio, bounded by
 * [ProcessGroupLauncher.MAX_LINE_BYTES] (10 MB).
 */
object AcpFraming {
    const val JSONRPC_VERSION: String = "2.0"

    /** The maximum line size in UTF-8 bytes, matching [ProcessGroupLauncher.MAX_LINE_BYTES]. */
    const val MAX_LINE_BYTES: Int = ProcessGroupLauncher.MAX_LINE_BYTES

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Decodes one stdout [line] into an [AcpFrame]; never throws. */
    fun decode(line: String): AcpFrame {
        if (line.isBlank()) return AcpFrame.Malformed(line, "empty line")
        if (utf8Length(line) > MAX_LINE_BYTES) return AcpFrame.Malformed(line, "line longer than 10 MB")
        val element =
            try {
                json.parseToJsonElement(line)
            } catch (e: SerializationException) {
                return AcpFrame.Malformed(line, "not JSON")
            }
        if (element !is JsonObject) return AcpFrame.Malformed(line, "not a JSON object")

        val versionElement = element["jsonrpc"]
        if (versionElement != null) {
            val version = (versionElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (version != JSONRPC_VERSION) return AcpFrame.Malformed(line, "unsupported jsonrpc version")
        }

        val hasMethod = "method" in element
        val idElement = element["id"]
        val hasId = idElement != null && idElement !is JsonNull

        if (hasMethod) {
            val method =
                (element["method"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: return AcpFrame.Malformed(line, "invalid method")
            val params = element["params"]
            return if (hasId) {
                if (!isValidId(idElement)) return AcpFrame.Malformed(line, "invalid request id")
                AcpFrame.Request(line, idElement, method, params)
            } else {
                AcpFrame.Notification(line, method, params)
            }
        }

        val hasResult = "result" in element
        val hasError = "error" in element
        if (hasResult && hasError) return AcpFrame.Malformed(line, "both result and error present")

        if (hasResult) {
            if (!hasId || !isValidId(idElement)) return AcpFrame.Malformed(line, "invalid response id")
            return AcpFrame.Response(line, idElement, element.getValue("result"))
        }

        if (hasError) {
            if (idElement != null && idElement !is JsonNull && !isValidId(idElement)) {
                return AcpFrame.Malformed(line, "invalid error response id")
            }
            val errorObj = element["error"] as? JsonObject ?: return AcpFrame.Malformed(line, "invalid error object")
            val code =
                (errorObj["code"] as? JsonPrimitive)
                    ?.takeUnless { it.isString }
                    ?.longOrNull
                    ?: return AcpFrame.Malformed(line, "invalid error code")
            val message =
                (errorObj["message"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?: return AcpFrame.Malformed(line, "invalid error message")
            val id = idElement?.takeUnless { it is JsonNull }
            return AcpFrame.Error(line, id, code, message, errorObj["data"])
        }

        return AcpFrame.Malformed(line, "not a JSON-RPC message")
    }

    /**
     * The text runner output echoes for the stdout line of [frame] (bl-240). An error response's `data`, and any member
     * besides `code` and `message` in its error or besides `jsonrpc`, `id` and `error` in its envelope, can hold a
     * provider's error body with account fields, so it is echoed as `jsonrpc` and `id` (as the line had them), `code`,
     * `message` and a `data` marker, `[error data omitted, N chars]`, N the length of the omitted values as compact JSON.
     * Every other line, an undecodable one included, is echoed as read. The frame itself keeps the data.
     */
    fun echo(frame: AcpFrame): String {
        if (frame !is AcpFrame.Error) return frame.line
        val envelope = json.parseToJsonElement(frame.line) as JsonObject
        val error = envelope.getValue("error") as JsonObject
        // A null member carries nothing to omit.
        val omitted =
            (envelope.filterKeys { it !in ENVELOPE_KEPT }.values + error.filterKeys { it !in ERROR_KEPT }.values)
                .filterNot { it is JsonNull }
        if (omitted.isEmpty()) return frame.line
        return buildJsonObject {
            envelope["jsonrpc"]?.let { put("jsonrpc", it) }
            envelope["id"]?.let { put("id", it) }
            put(
                "error",
                buildJsonObject {
                    put("code", error.getValue("code"))
                    put("message", error.getValue("message"))
                    put("data", "[error data omitted, ${omitted.sumOf { it.toString().length }} chars]")
                },
            )
        }.toString()
    }

    /** Encodes a JSON-RPC 2.0 request line, terminated with `\n`. */
    fun request(
        id: Long,
        method: String,
        params: JsonObject,
    ): String =
        buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString() + "\n"

    /** Encodes a JSON-RPC 2.0 notification line, terminated with `\n`. */
    fun notification(
        method: String,
        params: JsonObject,
    ): String =
        buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("method", method)
            put("params", params)
        }.toString() + "\n"

    /** Encodes a JSON-RPC 2.0 success response line, terminated with `\n`. */
    fun response(
        id: JsonElement,
        result: JsonElement,
    ): String =
        buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put("result", result)
        }.toString() + "\n"

    /** Encodes a JSON-RPC 2.0 error response line, terminated with `\n`. */
    fun errorResponse(
        id: JsonElement,
        code: Long,
        message: String,
    ): String =
        buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString() + "\n"

    private val ENVELOPE_KEPT = setOf("jsonrpc", "id", "error")
    private val ERROR_KEPT = setOf("code", "message")

    private fun isValidId(id: JsonElement): Boolean {
        val primitive = id as? JsonPrimitive ?: return false
        return primitive.isString || primitive.longOrNull != null
    }

    private fun utf8Length(line: String): Int =
        when {
            line.length > MAX_LINE_BYTES -> line.length
            line.length <= MAX_LINE_BYTES / 3 -> line.length
            else -> line.toByteArray(Charsets.UTF_8).size
        }
}
