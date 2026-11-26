package loop.runner.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import loop.runner.process.DeniedCommands
import loop.runner.process.HoldoutFolder
import loop.runner.process.ProtectedWorkspaceRoots
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * Fail-closed handler for agent-initiated JSON-RPC requests in ACP sessions (`session/request_permission`,
 * human-input prompts, and unsupported methods).
 *
 * Uses the shared matchers from `plugins/runner-process` ([DeniedCommands], [HoldoutFolder], and
 * [ProtectedWorkspaceRoots]). This is a guardrail, not an OS sandbox.
 */
internal class AcpPermissionPolicy(
    private val workspace: Path,
    private val holdout: HoldoutFolder?,
    private val protectedRoots: ProtectedWorkspaceRoots,
    private val strictMcp: Boolean,
    private val launcher: String? = null,
    /** Internal Loop directories (isolated `--gemini-dir`, attempt folder) that the agent must never modify. */
    private val protectedWriteDirs: () -> List<HoldoutFolder> = { emptyList() },
) {
    sealed interface Answer {
        /** Send [line] to the agent stdin and log [note] as a `loop:` stderr line (never quoting request data). */
        data class Write(
            val line: String,
            val note: String,
        ) : Answer

        /** Do not answer; kill the session and end the turn at once with [loop.api.RunnerEvent.TurnInputRequired]. */
        data class InputRequired(
            val reason: String,
        ) : Answer
    }

    /** Evaluates one agent-initiated [request]. */
    fun answer(request: AcpFrame.Request): Answer {
        val method = request.method
        if (isHumanInputMethod(method)) {
            return Answer.InputRequired(method)
        }
        if (method != AcpHandshake.METHOD_REQUEST_PERMISSION) {
            return Answer.Write(
                line = AcpFraming.errorResponse(request.id, METHOD_NOT_FOUND, "unsupported client method: $method"),
                note = "rejected unsupported server request $method (${request.id})",
            )
        }

        val params = request.params as? JsonObject
        if (params == null) {
            return decline(request.id, null, UNREADABLE)
        }
        val toolCall = params["toolCall"] as? JsonObject
        if (toolCall == null) {
            return decline(request.id, params, UNREADABLE)
        }

        val name = extractToolName(toolCall)?.lowercase(Locale.ROOT)
        val kind = toolCall.string("kind")?.lowercase(Locale.ROOT)
        if (name != null && name in HUMAN_INPUT_TOOLS) {
            return Answer.InputRequired("human input requested by tool $name")
        }

        val args = toolCall["args"] as? JsonObject
        val rawInput = toolCall["rawInput"] as? JsonObject

        val refusal =
            try {
                val locations = extractLocationPaths(toolCall["locations"])
                val cwd = extractCwd(rawInput, args)
                when {
                    isMcpTool(name) -> evaluateMcp(name!!)
                    isCommandTool(name, kind) -> evaluateCommand(rawInput, args, locations, cwd)
                    isWriteTool(name, kind) -> evaluateFileWrite(rawInput, args, locations, cwd)
                    isReadTool(name, kind) -> evaluateFileRead(rawInput, args, locations, cwd)
                    kind == "think" -> null
                    else -> UNSUPPORTED_TOOL
                }
            } catch (e: UnreadablePath) {
                e.reason
            }

        return if (refusal == null) {
            allow(request.id, params, name ?: kind ?: "tool")
        } else {
            decline(request.id, params, refusal)
        }
    }

    private fun evaluateMcp(name: String): String? {
        if (name.startsWith("mcp_loop_") || name.startsWith("mcp__loop__")) return null
        return if (strictMcp) NON_LOOP_MCP else null
    }

    private fun evaluateCommand(
        rawInput: JsonObject?,
        args: JsonObject?,
        locations: List<String>,
        cwd: Path,
    ): String? {
        val commands = (extractCommands(rawInput) + extractCommands(args)).distinct()
        if (commands.isEmpty()) return NO_COMMAND
        val internalDirs = protectedWriteDirs()
        return when {
            commands.any { DeniedCommands.isDenied(it, launcher) } -> DENIED_COMMAND
            holdout != null && (commands.any { holdout.names(it, cwd) } || locations.any { holdout.covers(it, cwd) }) -> HOLDOUT
            commands.any { protectedRoots.names(it, cwd) } || locations.any { protectedRoots.covers(it, cwd) } -> PROTECTED_ROOT
            internalDirs.any { dir -> commands.any { dir.names(it, cwd) } || locations.any { dir.covers(it, cwd) } } -> INTERNAL_LOOP_DIR
            else -> null
        }
    }

    private fun evaluateFileWrite(
        rawInput: JsonObject?,
        args: JsonObject?,
        locations: List<String>,
        cwd: Path,
    ): String? {
        val paths = (locations + extractInputPaths(rawInput) + extractInputPaths(args)).distinct()
        if (paths.isEmpty()) return NO_PATH
        val internalDirs = protectedWriteDirs()
        return when {
            holdout != null && paths.any { holdout.covers(it, cwd) } -> HOLDOUT
            paths.any { protectedRoots.covers(it, cwd) } -> PROTECTED_ROOT
            internalDirs.any { dir -> paths.any { dir.covers(it, cwd) } } -> INTERNAL_LOOP_DIR
            else -> null
        }
    }

    private fun evaluateFileRead(
        rawInput: JsonObject?,
        args: JsonObject?,
        locations: List<String>,
        cwd: Path,
    ): String? {
        val paths = (locations + extractInputPaths(rawInput) + extractInputPaths(args)).distinct()
        if (paths.isEmpty()) return NO_PATH
        return if (holdout != null && paths.any { holdout.covers(it, cwd) }) HOLDOUT else null
    }

    private fun allow(
        id: JsonElement,
        params: JsonObject,
        label: String,
    ): Answer.Write {
        val optionId = selectAllowOptionId(params["options"])
        if (optionId == null) {
            return decline(id, params, NO_ALLOW_OPTION)
        }
        return Answer.Write(
            line = selectedResponse(id, optionId),
            note = "allowed permission request $id ($label)",
        )
    }

    private fun decline(
        id: JsonElement,
        params: JsonObject?,
        reason: String,
    ): Answer.Write {
        val rejectOptionId = selectRejectOptionId(params?.get("options"))
        val line =
            if (rejectOptionId != null) {
                selectedResponse(id, rejectOptionId)
            } else {
                cancelledResponse(id)
            }
        return Answer.Write(
            line = line,
            note = "declined permission request $id: $reason",
        )
    }

    private fun selectedResponse(
        id: JsonElement,
        optionId: String,
    ): String =
        AcpFraming.response(
            id = id,
            result =
                buildJsonObject {
                    put(
                        "outcome",
                        buildJsonObject {
                            put("outcome", "selected")
                            put("optionId", optionId)
                        },
                    )
                },
        )

    private fun cancelledResponse(id: JsonElement): String =
        AcpFraming.response(
            id = id,
            result =
                buildJsonObject {
                    put(
                        "outcome",
                        buildJsonObject {
                            put("outcome", "cancelled")
                        },
                    )
                },
        )

    private fun selectAllowOptionId(optionsElement: JsonElement?): String? {
        val options = (optionsElement as? JsonArray)?.filterIsInstance<JsonObject>()
        if (options.isNullOrEmpty()) return DEFAULT_ALLOW_OPTION
        // Only accept allow_once so later calls of the same tool class never bypass Loop's policy.
        options.firstOrNull { it.string("kind") == "allow_once" }?.string("optionId")?.let { return it }
        return options
            .firstOrNull { it.string("kind") == null && it.string("optionId") == "allow_once" }
            ?.string("optionId")
    }

    private fun selectRejectOptionId(optionsElement: JsonElement?): String? {
        val options = (optionsElement as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        options.firstOrNull { it.string("kind") == "reject_once" }?.string("optionId")?.let { return it }
        options.firstOrNull { it.string("optionId") == "reject_once" }?.string("optionId")?.let { return it }
        options.firstOrNull { it.string("optionId") in setOf("reject", "deny") }?.string("optionId")?.let { return it }
        return options.firstOrNull { it.string("kind")?.startsWith("reject") == true }?.string("optionId")
    }

    private fun extractToolName(toolCall: JsonObject): String? {
        toolCall.string("name")?.takeIf { it.isNotBlank() }?.let { return it }
        val meta = toolCall["_meta"] as? JsonObject
        return meta?.string("name")?.takeIf { it.isNotBlank() }
    }

    private fun extractCommands(obj: JsonObject?): List<String> {
        if (obj == null) return emptyList()
        val result = mutableListOf<String>()
        for (key in COMMAND_KEYS) {
            when (val value = obj[key]) {
                is JsonPrimitive ->
                    value
                        .takeIf { it.isString }
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { result += it }
                is JsonArray -> {
                    val joined =
                        value
                            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                            .joinToString(" ")
                            .trim()
                    if (joined.isNotEmpty()) result += joined
                }
                else -> Unit
            }
        }
        return result
    }

    private fun extractLocationPaths(element: JsonElement?): List<String> =
        (element as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .flatMap { loc ->
                listOfNotNull(
                    loc.string("path")?.takeIf { it.isNotBlank() },
                    loc.string("uri")?.takeIf { it.isNotBlank() },
                ).map(::normalizePath)
            }

    private fun extractInputPaths(obj: JsonObject?): List<String> {
        if (obj == null) return emptyList()
        val paths = mutableListOf<String>()
        for (key in PATH_KEYS) {
            when (val value = obj[key]) {
                is JsonPrimitive ->
                    value
                        .takeIf { it.isString }
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { paths += normalizePath(it) }
                is JsonArray ->
                    value.forEach { item ->
                        (item as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { paths += normalizePath(it) }
                    }
                else -> Unit
            }
        }
        return paths
    }

    private fun extractCwd(
        rawInput: JsonObject?,
        args: JsonObject?,
    ): Path {
        val given =
            CWD_KEYS.firstNotNullOfOrNull { key -> rawInput?.string(key)?.takeIf { it.isNotBlank() } }
                ?: CWD_KEYS.firstNotNullOfOrNull { key -> args?.string(key)?.takeIf { it.isNotBlank() } }
                ?: return workspace
        val raw = normalizePath(given)
        return try {
            val path = Path.of(raw)
            if (path.isAbsolute) path else workspace.resolve(path)
        } catch (e: InvalidPathException) {
            workspace
        }
    }

    private fun isHumanInputMethod(method: String): Boolean {
        val lower = method.lowercase(Locale.ROOT)
        return lower in HUMAN_INPUT_METHODS ||
            "request_user_input" in lower ||
            "requestuserinput" in lower ||
            "ask_user" in lower ||
            "askuser" in lower
    }

    private fun isMcpTool(name: String?): Boolean = name != null && (name.startsWith("mcp_") || name.startsWith("mcp__"))

    private fun isCommandTool(
        name: String?,
        kind: String?,
    ): Boolean = name in COMMAND_TOOLS || kind == "execute"

    private fun isWriteTool(
        name: String?,
        kind: String?,
    ): Boolean = name in WRITE_TOOLS || kind in WRITE_KINDS

    private fun isReadTool(
        name: String?,
        kind: String?,
    ): Boolean = name in READ_TOOLS || kind in READ_KINDS

    /** Thrown by [normalizePath] for a path value the rules cannot read; the request is declined with [reason]. */
    private class UnreadablePath(
        val reason: String,
    ) : Exception(reason)

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    companion object {
        private const val METHOD_NOT_FOUND = -32601L
        private const val DEFAULT_ALLOW_OPTION = "allow_once"

        private val COMMAND_TOOLS = setOf("run_command", "bash", "shell", "execute_command", "terminal")
        private val WRITE_TOOLS =
            setOf(
                "write_file",
                "edit_file",
                "replace",
                "multi_edit",
                "delete_file",
                "move_file",
                "create_file",
                "patch_file",
                "apply_patch",
                "write_to_file",
                "replace_file_content",
                "multi_replace_file_content",
            )
        private val READ_TOOLS =
            setOf(
                "read_file",
                "view_file",
                "list_dir",
                "find_by_name",
                "grep_search",
                "glob",
                "search_files",
                "read_many_files",
                "list_files",
            )
        private val WRITE_KINDS = setOf("edit", "delete", "move")
        private val READ_KINDS = setOf("read", "search")

        private val HUMAN_INPUT_METHODS = setOf("session/request_user_input", "user/input", "elicitation/create")
        private val HUMAN_INPUT_TOOLS = setOf("ask_question", "ask_user", "ask_user_question", "request_user_input")

        private val COMMAND_KEYS = listOf("CommandLine", "commandLine", "command", "cmd")
        private val CWD_KEYS = listOf("Cwd", "cwd", "workingDirectory", "workdir")
        private val PATH_KEYS =
            listOf(
                "Path",
                "path",
                "file_path",
                "filePath",
                "TargetFile",
                "target_file",
                "Destination",
                "destination",
                "Source",
                "source",
                "DirectoryPath",
                "directory_path",
                "SearchPath",
                "search_path",
                "SearchDirectory",
                "dir",
                "directory",
                "root",
                "paths",
            )

        /** A URI scheme of two letters or more, so a Windows drive letter or `README.md:10` is never read as one. */
        private val URI_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]+):/""")
        private val FILE_SCHEME = Regex("""^(file):""", RegexOption.IGNORE_CASE)

        /**
         * The path [raw] names, read the same way wherever the request carries it (`locations[].path`,
         * `locations[].uri`, a `rawInput` or `args` path key, a working directory key). A `file:` URI (`file:///x`,
         * `file://localhost/x`, `file:/x`, any case of the scheme) becomes its absolute path with percent encoding
         * decoded. Any other URI scheme, a `file:` URI naming another host, no path or broken percent encoding throws
         * [UnreadablePath], so the request is declined and never read as a workspace relative path. A plain path is
         * returned unchanged.
         */
        internal fun normalizePath(raw: String): String {
            val scheme = (FILE_SCHEME.find(raw) ?: URI_SCHEME.find(raw))?.groupValues?.get(1) ?: return raw
            if (!scheme.equals("file", ignoreCase = true)) {
                val safe = scheme.lowercase(Locale.ROOT)
                throw UnreadablePath("it names a path by the URI scheme $safe, which the rules do not read")
            }
            val rest = raw.substring(scheme.length + 1)
            val encodedPath =
                if (rest.startsWith("//")) {
                    val afterSlashes = rest.substring(2)
                    val slash = afterSlashes.indexOf('/')
                    val host = if (slash < 0) afterSlashes else afterSlashes.substring(0, slash)
                    if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) {
                        throw UnreadablePath("it names a file URI host other than localhost, which the rules do not read")
                    }
                    if (slash < 0) throw UnreadablePath("it names a file URI with no path")
                    afterSlashes.substring(slash)
                } else {
                    rest
                }
            val decoded = percentDecode(encodedPath) ?: throw UnreadablePath("it names a file URI with broken percent encoding")
            if (!decoded.startsWith("/") || '\u0000' in decoded) throw UnreadablePath("it names a file URI with no absolute path")
            return decoded
        }

        /** Decodes `%XX` escapes as UTF-8 bytes, or null when an escape is broken or the bytes are not UTF-8. */
        private fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                if (text[i] == '%') {
                    if (i + 2 >= text.length) return null
                    val hi = Character.digit(text[i + 1], 16)
                    val lo = Character.digit(text[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    val next = text.indexOf('%', i).let { if (it < 0) text.length else it }
                    bytes.write(text.substring(i, next).toByteArray(Charsets.UTF_8))
                    i = next
                }
            }
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }

        const val UNREADABLE: String = "its params are not the shape the rule reads"
        const val NO_COMMAND: String = "it has no command, so the rules cannot read what would run"
        const val NO_PATH: String = "it has no path, so the rules cannot read what it touches"
        const val DENIED_COMMAND: String = "it runs a denied command (git push, gh, a history rewrite or an operator command)"
        const val HOLDOUT: String = "it touches the holdout folder"
        const val PROTECTED_ROOT: String = "it targets a protected workspace customization root"
        const val INTERNAL_LOOP_DIR: String = "it targets an internal Loop config or attempt directory"
        const val NON_LOOP_MCP: String = "strict_mcp declines non-Loop MCP tools"
        const val UNSUPPORTED_TOOL: String = "the tool is not allowed by Loop's fail-closed permission policy"
        const val NO_ALLOW_OPTION: String = "no allow option was offered"
    }
}
