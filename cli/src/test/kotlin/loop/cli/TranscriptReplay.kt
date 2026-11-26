package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A stand in for Claude Code that replays a recorded stream-json transcript (m2-16), for tests with no model call.
 * Every line is printed as recorded, except that each tool call the transcript makes is run for real and its
 * recorded result line carries the live result instead: `Bash` runs its command with `sh -c` in the working folder,
 * and `mcp__<server>__<tool>` is called on the server of that name in the `--mcp-config` file, spawned from its
 * `command`, `args` and `env` and spoken to in newline delimited JSON-RPC, as Claude Code does. A tool call the
 * result line lists in its `permission_denials` is not run, as Claude Code refused it, and keeps its recorded result.
 *
 * Arguments: the transcript, a folder where it copies its argv and the MCP config it was given, then the argv
 * Loop passes to `claude`. The first user message line on stdin is read and dropped, then the transcript is printed
 * and the process exits.
 */
fun main(args: Array<String>) {
    val transcript = Path.of(args[0])
    val record = Path.of(args[1])
    val argv = args.drop(2)
    // Two attempts of one run may replay at once into the same record folder: each file is replaced atomically, since
    // concurrent `Files.copy` with REPLACE_EXISTING deletes then creates the target and one of them can fail.
    TranscriptReplay.replaceAtomically(record.resolve("argv")) { it.writeText(argv.joinToString("\n")) }
    val mcpConfig =
        argv
            .zipWithNext()
            .firstOrNull { it.first == "--mcp-config" }
            ?.second
            ?.let { Path.of(it) }
    mcpConfig?.let { config ->
        TranscriptReplay.replaceAtomically(record.resolve("mcp.json")) { Files.copy(config, it, StandardCopyOption.REPLACE_EXISTING) }
    }
    // The first user message line; Claude Code with `--input-format stream-json` keeps stdin open (m3-07).
    readlnOrNull()
    val servers =
        mcpConfig?.let {
            Json
                .parseToJsonElement(it.readText())
                .jsonObject
                .getValue("mcpServers")
                .jsonObject
        }
    val live = mutableMapOf<String, TranscriptReplay.ToolResult>()
    val lines = transcript.readLines().filter { it.isNotBlank() }
    val denied = TranscriptReplay.deniedIds(lines.map { Json.parseToJsonElement(it).jsonObject })
    lines.forEach { line ->
        val message = Json.parseToJsonElement(line).jsonObject
        when (message["type"]?.jsonPrimitive?.content) {
            "assistant" -> {
                println(line)
                TranscriptReplay
                    .toolUses(message)
                    .filterNot { it.id in denied }
                    .forEach { use -> live[use.id] = TranscriptReplay.run(use, servers) }
            }
            "user" -> println(TranscriptReplay.withLiveResults(message, live)?.toString() ?: line)
            else -> println(line)
        }
        System.out.flush()
    }
}

object TranscriptReplay {
    /** Writes [target] through [write] into a temporary file of its own folder, then moves it over [target] atomically. */
    fun replaceAtomically(
        target: Path,
        write: (Path) -> Unit,
    ) {
        val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        try {
            write(temp)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    )

    data class ToolResult(
        val content: JsonElement,
        val isError: Boolean,
    )

    /**
     * Writes a fake `claude` executable in [dir] that replays [transcript] with this class's `main`, on this JVM's
     * java and classpath, recording into [record].
     */
    fun fakeClaude(
        dir: Path,
        transcript: Path,
        record: Path,
    ): Path {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = System.getProperty("java.class.path")
        val script = dir.resolve("claude")
        script.writeText(
            "#!/bin/sh\nexec '$java' -cp '$classpath' loop.cli.TranscriptReplayKt '$transcript' '$record' \"$@\"\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /** The ids of the tool calls the `result` line among [messages] lists in its `permission_denials`. */
    fun deniedIds(messages: List<JsonObject>): Set<String> =
        messages
            .filter { it["type"]?.jsonPrimitive?.content == "result" }
            .flatMap { (it["permission_denials"] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.get("tool_use_id")?.jsonPrimitive?.content }
            .toSet()

    fun toolUses(message: JsonObject): List<ToolUse> =
        message
            .getValue("message")
            .jsonObject["content"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "tool_use" }
            .map {
                ToolUse(
                    it.getValue("id").jsonPrimitive.content,
                    it.getValue("name").jsonPrimitive.content,
                    it.getValue("input").jsonObject,
                )
            }

    /** The user line with each tool result the replay ran replaced by its live result, null when it ran none. */
    fun withLiveResults(
        message: JsonObject,
        live: Map<String, ToolResult>,
    ): JsonObject? {
        val inner = message.getValue("message").jsonObject
        val content = inner["content"] as? JsonArray ?: return null
        var replaced = false
        val updated =
            content.map { block ->
                val obj = block.jsonObject
                val result = obj["tool_use_id"]?.jsonPrimitive?.content?.let { live[it] }
                if (obj["type"]?.jsonPrimitive?.content != "tool_result" || result == null) {
                    block
                } else {
                    replaced = true
                    JsonObject(obj + mapOf("content" to result.content, "is_error" to JsonPrimitive(result.isError)))
                }
            }
        if (!replaced) return null
        return JsonObject(message + ("message" to JsonObject(inner + ("content" to JsonArray(updated)))))
    }

    fun run(
        use: ToolUse,
        servers: JsonObject?,
    ): ToolResult =
        when {
            use.name == "Bash" ->
                bash(
                    use.input
                        .getValue("command")
                        .jsonPrimitive.content,
                )
            use.name.startsWith(MCP_PREFIX) -> {
                val (server, tool) = use.name.removePrefix(MCP_PREFIX).split("__", limit = 2)
                val config = servers?.get(server)?.jsonObject ?: error("no MCP server $server in the --mcp-config file")
                callTool(config, tool, use.input)
            }
            else -> error("the replay cannot run the tool ${use.name}")
        }

    private fun bash(command: String): ToolResult {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        return ToolResult(JsonPrimitive(output), code != 0)
    }

    /** Spawns the stdio server [config], runs the MCP handshake, calls [tool] with [arguments] and closes its stdin. */
    private fun callTool(
        config: JsonObject,
        tool: String,
        arguments: JsonObject,
    ): ToolResult {
        val command =
            listOf(config.getValue("command").jsonPrimitive.content) +
                config["args"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)
        config["env"]?.jsonObject?.forEach { (name, value) -> builder.environment()[name] = value.jsonPrimitive.content }
        val process = builder.start()
        val stdin = process.outputStream.bufferedWriter()
        val stdout = process.inputStream.bufferedReader()

        fun send(message: JsonObject) {
            stdin.write(message.toString() + "\n")
            stdin.flush()
        }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "claude-code")
                        put("version", "replay")
                    }
                }
            },
        )
        response(stdout, 1)
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
        )
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", tool)
                    put("arguments", arguments)
                }
            },
        )
        val result = response(stdout, 2).getValue("result").jsonObject
        stdin.close()
        if (!process.waitFor(SERVER_EXIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        return ToolResult(result.getValue("content"), result["isError"]?.jsonPrimitive?.boolean == true)
    }

    private fun response(
        stdout: BufferedReader,
        id: Int,
    ): JsonObject =
        generateSequence { stdout.readLine() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == id.toString() }
            ?: error("the MCP server closed its output before answering request $id")

    private const val MCP_PREFIX = "mcp__"
    private const val SERVER_EXIT_SECONDS = 30L
}
