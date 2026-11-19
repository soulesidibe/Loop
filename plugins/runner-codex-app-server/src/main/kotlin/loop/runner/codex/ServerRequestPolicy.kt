package loop.runner.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCError
import loop.runner.codex.protocol.JSONRPCErrorError
import loop.runner.codex.protocol.JSONRPCResponse
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * How the Codex runner answers the requests the app server sends during a turn (m3-20, spec 10.5, 17.5 items 10 to
 * 12): each one at once, by rule, so no request leaves a turn stalled. The thread asks for approvals with the policy
 * `untrusted`, see [StartupHandshake.APPROVAL_POLICY], and every request is answered whatever thread or turn it names,
 * so a subagent's requests follow the same rules.
 *
 * - `item/commandExecution/requestApproval`: `decline` when its `kind` is set to anything but `command`, the schema's
 *   default, since input written to a running shell (`writeStdin`) or a human checkpoint (`humanCommand`) is not a
 *   command the rules can read; when it has no `command` text, the parsed `commandActions` being for display only;
 *   when the command holds a [DeniedCommands] command or names the [holdout] folder, by its text or by the path of a
 *   parsed action. Else `accept`. `decline` lets the agent go on.
 * - `item/fileChange/requestApproval`: `decline` when a change of the item, its path or the path it moves to, or the
 *   root it asks to be granted, lies under the [holdout] folder, or when no change of the item was seen; else
 *   `accept`. A request names only its item, so the changes come from the item's `item/started` notification and
 *   `item/fileChange/patchUpdated`, which [observe] reads.
 * - `execCommandApproval` and `applyPatchApproval`, the older forms, follow the same rules with `approved`, or `denied`
 *   with a `rejection` text that gives the reason, which the pinned schema requires. An empty command is denied.
 * - `item/permissions/requestApproval`: granted nothing. `mcpServer/elicitation/request`: declined.
 * - `item/tool/requestUserInput`: not answered; the turn ends with `turn_input_required` (story 41), see
 *   [Answer.InputRequired].
 * - `item/tool/call`: a call of one of [tools], the client tools the thread declared (m3-22), with an object as its
 *   arguments is [Answer.CallTool], which the runner runs through `loop tool mcp`. Any other call, to a tool Loop does
 *   not declare, namespaced, or with arguments that are not an object, gets a failure result, `success: false` with a
 *   text naming the tool, and the turn goes on.
 * - Any other request: a JSON-RPC error, method not found, and the turn goes on.
 *
 * A request whose params do not have the shape its rule reads is declined or refused, never accepted.
 */
internal class ServerRequestPolicy(
    private val workspace: Path,
    private val holdout: HoldoutFolder?,
    /** The client tools the thread declared (m3-22), Loop's tools by name; empty when it declared none. */
    private val tools: List<String> = emptyList(),
) {
    /** What the runner does with one request. */
    sealed interface Answer {
        /** Write [line], the answer, and log [note], a `loop:` line that never quotes the request. */
        data class Write(
            val line: String,
            val note: String,
        ) : Answer

        /** Answer nothing and end the turn with `turn_input_required` and [reason]. */
        data class InputRequired(
            val reason: String,
        ) : Answer

        /**
         * Run the declared client tool [tool] with [arguments] (m3-22), then answer the request [id] with
         * [toolResult] of its reply.
         */
        data class CallTool(
            val id: JsonPrimitive,
            val tool: String,
            val arguments: JsonObject,
        ) : Answer
    }

    // The paths each file change item touches, by item id, from its notifications.
    private val fileChanges = mutableMapOf<String, MutableList<String>>()

    /** Reads a notification for what the rules need: the changes of file change items. */
    fun observe(notification: Frame.Notification) {
        val params = notification.message.params as? JsonObject ?: return
        when (notification.message.method) {
            ITEM_STARTED -> {
                val item = params["item"] as? JsonObject ?: return
                if (item.string("type") != "fileChange") return
                val id = item.string("id") ?: return
                fileChanges.getOrPut(id) { mutableListOf() } += changePaths(item["changes"])
            }
            PATCH_UPDATED -> {
                val id = params.string("itemId") ?: return
                fileChanges.getOrPut(id) { mutableListOf() } += changePaths(params["changes"])
            }
        }
    }

    /** The answer to [request]. */
    fun answer(request: Frame.Request): Answer {
        val id = request.message.id
        val params = request.message.params as? JsonObject
        return when (val method = request.message.method) {
            COMMAND_APPROVAL -> {
                val refusal = if (params == null) UNREADABLE else commandApprovalRefusal(params)
                decide(id, "command approval request", refusal, JsonPrimitive(ACCEPT)) { JsonPrimitive(DECLINE) }
            }
            FILE_CHANGE_APPROVAL -> {
                val refusal = if (params == null) UNREADABLE else fileChangeRefusal(params)
                decide(id, "file change approval request", refusal, JsonPrimitive(ACCEPT)) { JsonPrimitive(DECLINE) }
            }
            EXEC_APPROVAL -> {
                val command = (params?.get("command") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                val refusal =
                    when {
                        command == null -> UNREADABLE
                        command.joinToString(" ").isBlank() -> NO_COMMAND
                        else -> commandRefusal(listOf(command.joinToString(" ")), emptyList(), cwdOf(params))
                    }
                decide(id, "exec approval request", refusal, JsonPrimitive(APPROVED), ::denied)
            }
            PATCH_APPROVAL -> {
                val changes = params?.get("fileChanges") as? JsonObject
                val refusal = if (changes == null) UNREADABLE else patchRefusal(changes, params)
                decide(id, "patch approval request", refusal, JsonPrimitive(APPROVED), ::denied)
            }
            PERMISSIONS_APPROVAL ->
                Answer.Write(
                    result(id, buildJsonObject { put("permissions", JsonObject(emptyMap())) }),
                    "${LOOP}granted nothing to the permissions request $id",
                )
            ELICITATION ->
                Answer.Write(result(id, buildJsonObject { put("action", "decline") }), "${LOOP}declined the MCP elicitation request $id")
            USER_INPUT -> Answer.InputRequired(USER_INPUT)
            DYNAMIC_TOOL_CALL -> toolCall(id, params)
            else ->
                Answer.Write(
                    error(id, METHOD_NOT_FOUND, "loop does not handle the server request $method"),
                    "${LOOP}refused the server request $id, a method Loop does not handle",
                )
        }
    }

    /**
     * A dynamic tool call: [Answer.CallTool] for a declared tool whose arguments are an object; else a failure result at
     * once, and the turn goes on. A namespaced call is never one of Loop's tools, which have no namespace.
     */
    private fun toolCall(
        id: JsonPrimitive,
        params: JsonObject?,
    ): Answer {
        val tool = params?.string("tool") ?: "unnamed"
        val namespaced = params?.get("namespace")?.takeUnless { it is JsonNull } != null
        if (tool !in tools || namespaced) {
            val text =
                if (tools.isEmpty()) {
                    "Loop serves no dynamic tool on this thread, so the tool $tool is not supported. Go on without it."
                } else {
                    "Loop serves only ${tools.dropLast(1).joinToString(", ")} and ${tools.last()} on this thread, " +
                        "so the tool $tool is not supported. Nothing queued."
                }
            return Answer.Write(
                toolResult(id, false, text),
                "${LOOP}unsupported dynamic tool call, request $id, answered with a failure result",
            )
        }
        val arguments =
            params?.get("arguments") as? JsonObject
                ?: return Answer.Write(
                    toolResult(id, false, "The arguments of $tool must be a JSON object. Nothing queued."),
                    "${LOOP}refused the Loop tool call request $id, its arguments are not an object",
                )
        return Answer.CallTool(id, tool, arguments)
    }

    /** The answer to the dynamic tool call [id]: a `DynamicToolCallResponse` with one text, [text], and [success]. */
    fun toolResult(
        id: JsonPrimitive,
        success: Boolean,
        text: String,
    ): String {
        val content =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "inputText")
                        put("text", text)
                    },
                )
            }
        return result(
            id,
            buildJsonObject {
                put("success", success)
                put("contentItems", content)
            },
        )
    }

    /** Why a command approval request is refused, null when it is not, see [ServerRequestPolicy]. */
    private fun commandApprovalRefusal(params: JsonObject): String? {
        // An absent or null kind is the schema's default, a plain command.
        val kind = params["kind"]?.takeUnless { it is JsonNull }
        if (kind != null && (kind as? JsonPrimitive)?.takeIf { it.isString }?.content != COMMAND_KIND) return OTHER_KIND
        val command = params.string("command")?.takeIf { it.isNotBlank() } ?: return NO_COMMAND
        return commandRefusal(listOf(command) + actions(params).mapNotNull { it.string("command") }, commandPaths(params), cwdOf(params))
    }

    /** Why a command is refused, null when it is not: [texts] are its command lines, [paths] the paths its actions read. */
    private fun commandRefusal(
        texts: List<String>,
        paths: List<String>,
        cwd: Path,
    ): String? =
        when {
            texts.any { DeniedCommands.isDenied(it) } -> DENIED_COMMAND
            holdout != null && (texts.any { holdout.names(it, cwd) } || paths.any { holdout.covers(it, cwd) }) -> HOLDOUT
            else -> null
        }

    private fun fileChangeRefusal(params: JsonObject): String? {
        val item = params.string("itemId") ?: return UNREADABLE
        val paths = fileChanges[item]?.takeIf { it.isNotEmpty() } ?: return UNSEEN
        val grant = params.string("grantRoot")
        return if (holdout != null && (paths + listOfNotNull(grant)).any { holdout.covers(it, workspace) }) HOLDOUT else null
    }

    private fun patchRefusal(
        changes: JsonObject,
        params: JsonObject,
    ): String? {
        val moves = changes.values.mapNotNull { (it as? JsonObject)?.string("move_path") }
        val paths = changes.keys + moves + listOfNotNull(params.string("grantRoot"))
        val cwd = cwdOf(params)
        return if (holdout != null && paths.any { holdout.covers(it, cwd) }) HOLDOUT else null
    }

    /** The answer to the approval request [id], [yes] when [refusal] is null, else [no] of the refusal. */
    private fun decide(
        id: JsonPrimitive,
        what: String,
        refusal: String?,
        yes: JsonElement,
        no: (String) -> JsonElement,
    ): Answer.Write {
        val decision = if (refusal == null) yes else no(refusal)
        val note = if (refusal == null) "${LOOP}accepted the $what $id" else "${LOOP}declined the $what $id: $refusal"
        return Answer.Write(result(id, buildJsonObject { put("decision", decision) }), note)
    }

    /** The older `denied` decision, which carries the reason as its `rejection`. */
    private fun denied(refusal: String): JsonElement =
        buildJsonObject { put(DENIED, buildJsonObject { put("rejection", "Loop refused this request: $refusal.") }) }

    private fun commandPaths(params: JsonObject): List<String> = actions(params).mapNotNull { it.string("path") }

    private fun actions(params: JsonObject): List<JsonObject> =
        (params["commandActions"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** The `cwd` of a request, the workspace when it has none or it is not a path. */
    private fun cwdOf(params: JsonObject?): Path {
        val cwd = params?.string("cwd") ?: return workspace
        return try {
            Path.of(cwd).takeIf { it.isAbsolute } ?: workspace.resolve(cwd)
        } catch (e: InvalidPathException) {
            workspace
        }
    }

    /** The paths of a list of file changes, each its `path` and the `move_path` of its kind. */
    private fun changePaths(changes: JsonElement?): List<String> =
        (changes as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { change ->
            listOfNotNull(change.string("path"), (change["kind"] as? JsonObject)?.string("move_path"))
        }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun result(
        id: JsonPrimitive,
        result: JsonObject,
    ): String = CodexJson.format.encodeToString(JSONRPCResponse.serializer(), JSONRPCResponse(id, result)) + "\n"

    private fun error(
        id: JsonPrimitive,
        code: Long,
        message: String,
    ): String =
        CodexJson.format.encodeToString(JSONRPCError.serializer(), JSONRPCError(JSONRPCErrorError(code = code, message = message), id)) +
            "\n"

    private companion object {
        const val LOOP = "loop: "

        const val COMMAND_APPROVAL = "item/commandExecution/requestApproval"
        const val FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval"
        const val EXEC_APPROVAL = "execCommandApproval"
        const val PATCH_APPROVAL = "applyPatchApproval"
        const val PERMISSIONS_APPROVAL = "item/permissions/requestApproval"
        const val ELICITATION = "mcpServer/elicitation/request"
        const val USER_INPUT = "item/tool/requestUserInput"
        const val DYNAMIC_TOOL_CALL = "item/tool/call"
        const val ITEM_STARTED = "item/started"
        const val PATCH_UPDATED = "item/fileChange/patchUpdated"

        const val ACCEPT = "accept"
        const val DECLINE = "decline"
        const val APPROVED = "approved"
        const val DENIED = "denied"

        /** The `kind` of a plain command approval, the schema's default. */
        const val COMMAND_KIND = "command"

        /** The JSON-RPC code of a method the receiver does not have. */
        const val METHOD_NOT_FOUND = -32601L

        const val DENIED_COMMAND = "it runs a denied command (git push, gh, a history rewrite or an operator command)"
        const val HOLDOUT = "it touches the holdout folder"
        const val UNSEEN = "no change of its item was seen, so its paths are unknown"
        const val UNREADABLE = "its params are not the shape the rule reads"
        const val NO_COMMAND = "it has no command, so the rules cannot read what would run"
        const val OTHER_KIND = "its kind is not a plain command, so the rules cannot read what would run"
    }
}
