package loop.runner.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one call of a Loop client tool (m3-22, 17.5 item 16) through `loop tool mcp`, the server the Claude Code runner
 * configures (m2-16, m2-17): [launcher] `tool mcp` in the workspace with the agent env, the MCP handshake
 * (`initialize`, `notifications/initialized`), one `tools/call`, then stdin closes and the server ends. So a call gets
 * exactly what an MCP call gets: the same validation, the same request queued in the attempt directory and applied by
 * Loop's tracker adapter at the drain when the turn ends (m1-23), the same events and the same reply. The resource
 * tools (m4-51) are answered by `loop tool mcp` inside the call instead, from the store (m4-25). The runner holds no
 * credential and writes nothing to the tracker.
 *
 * The reply is [Reply.text], the text of the tool result, and [Reply.success], false when the result is a tool error,
 * a refusal of `loop tool mcp` that queued nothing. A server that cannot start, exits before answering, answers with a
 * JSON-RPC error or does not answer within [timeout] is a failure too, whose text says why; its stderr, `loop tool`'s
 * refusal, is quoted cut to [MAX_STDERR_CHARS].
 */
internal class LoopToolCall(
    private val launcher: Path,
    /** Sent as the MCP `clientInfo.version`. */
    private val loopVersion: String,
    private val timeout: Duration = TIMEOUT,
) {
    /** What the tool answers the session. */
    data class Reply(
        val success: Boolean,
        val text: String,
    )

    /** Calls [tool] with [arguments] in [workspace], `loop tool mcp` started with exactly [env]. */
    suspend fun call(
        tool: String,
        arguments: JsonObject,
        workspace: Path,
        env: Map<String, String>,
    ): Reply =
        runInterruptible(Dispatchers.IO) {
            val builder = ProcessBuilder(launcher.toString(), "tool", "mcp").directory(workspace.toFile())
            builder.environment().clear()
            builder.environment().putAll(env)
            val process =
                try {
                    builder.start()
                } catch (e: IOException) {
                    return@runInterruptible failure(tool, "loop tool mcp could not start (${e.message ?: e.javaClass.simpleName})")
                }
            val stderr = StringBuilder()
            val stderrReader =
                thread(isDaemon = true, name = "loop-tool-mcp-stderr") {
                    try {
                        process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
                    } catch (e: IOException) {
                        // The process was killed; what was read is kept.
                    }
                }
            val exchange = CompletableFuture<Reply>()
            thread(isDaemon = true, name = "loop-tool-mcp") {
                exchange.complete(
                    try {
                        exchange(process, tool, arguments) {
                            stderrReader.join(STDERR_JOIN_MS)
                            synchronized(stderr) { stderr.toString() }
                        }
                    } catch (e: IOException) {
                        failure(tool, "the connection to loop tool mcp failed (${e.message ?: e.javaClass.simpleName})")
                    } catch (e: SerializationException) {
                        failure(tool, "loop tool mcp wrote a line that is not JSON-RPC")
                    } catch (e: IllegalArgumentException) {
                        failure(tool, "loop tool mcp wrote a line that is not JSON-RPC")
                    },
                )
            }
            try {
                exchange.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                failure(tool, "loop tool mcp did not answer within ${timeout.inWholeSeconds} s. Check the issue before you call it again")
            } catch (e: ExecutionException) {
                failure(tool, "loop tool mcp failed (${e.cause?.javaClass?.simpleName})")
            } finally {
                stop(process)
            }
        }

    /** The MCP exchange on [process]; [stderr] gives what it wrote on stderr once it ended. */
    private fun exchange(
        process: Process,
        tool: String,
        arguments: JsonObject,
        stderr: () -> String,
    ): Reply {
        val stdin = process.outputStream.bufferedWriter()
        val stdout = process.inputStream.bufferedReader()

        fun send(message: JsonObject) {
            stdin.write(message.toString() + "\n")
            stdin.flush()
        }

        fun gone(): Reply {
            val code = if (process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) process.exitValue().toString() else "unknown"
            val said = lastLine(stderr())?.let { ": $it" } ?: ""
            return failure(tool, "loop tool mcp exited $code before answering$said")
        }
        try {
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", INITIALIZE_ID)
                    put("method", "initialize")
                    putJsonObject("params") {
                        put("protocolVersion", MCP_PROTOCOL_VERSION)
                        putJsonObject("capabilities") {}
                        putJsonObject("clientInfo") {
                            put("name", CLIENT_NAME)
                            put("version", loopVersion)
                        }
                    }
                },
            )
        } catch (e: IOException) {
            // The server is gone before it read anything, as `loop tool mcp` exits 4 outside an attempt.
            return gone()
        }
        val initialized = response(stdout, INITIALIZE_ID) ?: return gone()
        initialized["error"]?.let { return failure(tool, "loop tool mcp refused the MCP handshake: ${errorMessage(it)}") }
        try {
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                },
            )
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", CALL_ID)
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", tool)
                        put("arguments", arguments)
                    }
                },
            )
        } catch (e: IOException) {
            return gone()
        }
        val answer = response(stdout, CALL_ID) ?: return gone()
        stdin.close()
        answer["error"]?.let { return failure(tool, "loop tool mcp refused the call: ${errorMessage(it)}") }
        val result = answer["result"] as? JsonObject ?: return failure(tool, "loop tool mcp answered with no result")
        val texts =
            (result["content"] as? JsonArray)
                .orEmpty()
                .mapNotNull { item -> (item as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text") }
        val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
        return Reply(success = !isError, text = texts.joinToString("\n").ifEmpty { if (isError) "The call failed." else "Done." })
    }

    /** The answer to request [id], other lines passed over; null when stdout ends first. */
    private fun response(
        stdout: BufferedReader,
        id: Int,
    ): JsonObject? {
        while (true) {
            val line = stdout.readLine() ?: return null
            if (line.isBlank()) continue
            val message = Json.parseToJsonElement(line) as? JsonObject ?: continue
            if ((message["id"] as? JsonPrimitive)?.content == id.toString()) return message
        }
    }

    /** Closes stdin, gives the server [EXIT_WAIT_SECONDS] to end, then kills it and what it started. */
    private fun stop(process: Process) {
        try {
            process.outputStream.close()
        } catch (e: IOException) {
            // Already closed.
        }
        if (!process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun errorMessage(error: JsonElement): String = (error as? JsonObject)?.string("message")?.let { cut(it) } ?: "no message"

    private fun lastLine(text: String): String? =
        text
            .lines()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.let(::cut)

    private fun cut(text: String): String = if (text.length <= MAX_STDERR_CHARS) text else text.take(MAX_STDERR_CHARS) + "..."

    private fun failure(
        tool: String,
        reason: String,
    ): Reply = Reply(success = false, text = "Loop could not run $tool: ${reason.trimEnd('.')}.")

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** How long a call may take, `loop tool mcp`'s start included. */
        val TIMEOUT: Duration = 60.seconds

        /** The MCP protocol version the client asks for, the one Claude Code's replay uses against `loop tool mcp`. */
        const val MCP_PROTOCOL_VERSION = "2025-06-18"

        /** The `clientInfo.name` of the MCP handshake. */
        const val CLIENT_NAME = "loop-codex-runner"

        /** The longest stderr text quoted in a failure. */
        const val MAX_STDERR_CHARS = 300

        private const val INITIALIZE_ID = 1
        private const val CALL_ID = 2
        private const val EXIT_WAIT_SECONDS = 5L
        private const val STDERR_JOIN_MS = 2000L
    }
}
