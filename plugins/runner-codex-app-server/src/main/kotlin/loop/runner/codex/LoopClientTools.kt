package loop.runner.codex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Loop's tools as the client tools of a Codex thread (m3-22, 17.5 items 15 and 16): `submit_result`, `move` and
 * `comment`, the tools `loop tool mcp` serves the Claude Code runner, with the same arguments. [specs] is the
 * `dynamicTools` list of `thread/start`, each entry a `DynamicToolSpec` of the pinned schema.
 *
 * Only the workflow's tracker shows: [trackerKind] and [definedStates] come from the attempt's tool config snapshot
 * (m1-23), so `move` accepts exactly the states that tracker defines, as an `enum`, and the descriptions name it. A
 * call is not run here: the runner hands it to `loop tool mcp`, see [LoopToolCall].
 */
class LoopClientTools(
    val trackerKind: String,
    val definedStates: List<String>,
) {
    /** The `dynamicTools` of `thread/start`: [SUBMIT_RESULT], [MOVE] and [COMMENT], in that order. */
    val specs: JsonArray
        get() =
            buildJsonArray {
                add(function(SUBMIT_RESULT, SUBMIT_RESULT_DESCRIPTION, objectSchema(SubmitResultArguments.properties, listOf("status"))))
                add(function(MOVE, moveDescription(), objectSchema(moveProperties(), listOf(STATE))))
                add(function(COMMENT, COMMENT_DESCRIPTION, objectSchema(commentProperties(), listOf(TEXT))))
            }

    private fun moveDescription(): String =
        "Ask Loop to move your issue to another state of its $trackerKind tracker, like loop tool move. Loop queues the move " +
            "and applies it when this turn ends. In Loop mode, end your attempt with submit_result: move is for needs_info, " +
            "and for runs without Loop mode. Call it once per move."

    private fun moveProperties(): JsonObject =
        buildJsonObject {
            putJsonObject(STATE) {
                put("type", "string")
                put("enum", JsonArray(definedStates.map(::JsonPrimitive)))
                put("description", "The $trackerKind tracker state to move the issue to, one of: ${definedStates.joinToString(", ")}.")
            }
        }

    private fun commentProperties(): JsonObject =
        buildJsonObject {
            putJsonObject(TEXT) {
                put("type", "string")
                put("description", "The comment to add, for example a question for a human.")
            }
        }

    /** What an attempt env gives the runner. */
    sealed interface Loaded {
        /** Not an attempt Loop started: no launcher or no snapshot. */
        data object None : Loaded

        /** The snapshot cannot be read, for [reason]. */
        data class Unreadable(
            val reason: String,
        ) : Loaded

        /** The tools, and the [launcher] that runs their calls. */
        data class Ready(
            val tools: LoopClientTools,
            val launcher: Path,
        ) : Loaded
    }

    companion object {
        const val SUBMIT_RESULT = "submit_result"
        const val MOVE = "move"
        const val COMMENT = "comment"

        /** The names of the tools, in the order of [specs]. */
        val NAMES: List<String> = listOf(SUBMIT_RESULT, MOVE, COMMENT)

        /** `move`'s one argument, as `loop tool mcp` reads it. */
        const val STATE = "state"

        /** `comment`'s one argument, as `loop tool mcp` reads it. */
        const val TEXT = "text"

        /** The env name of the `loop` launcher, which runs `loop tool mcp`. */
        const val ENV_LAUNCHER = "LOOP_LAUNCHER"

        /** The env name of the attempt's tool config snapshot. */
        const val ENV_TOOL_CONFIG = "LOOP_TOOL_CONFIG"

        /** The snapshot format this build reads, the one `loop run` writes (m1-23). */
        private const val SNAPSHOT_VERSION = "1"

        private const val SUBMIT_RESULT_DESCRIPTION =
            "Submit the result of your attempt, in Loop's result shape. Loop applies it when this turn ends, " +
                "like .loop/result.json. status done: Loop runs its checks and moves the issue to review only if they pass. " +
                "status needs_info: summary is your question. status blocked or failed: summary is the reason, and the attempt " +
                "ends as a failure. Call it once, and never move the issue yourself."

        private const val COMMENT_DESCRIPTION =
            "Ask Loop to add a comment to your issue, like loop tool comment, for example a question for a human. " +
                "Loop queues the comment, redacted, and adds it when this turn ends. Call it once per comment."

        /**
         * The tools of the attempt whose env is [env]: [Loaded.None] without `LOOP_LAUNCHER` or `LOOP_TOOL_CONFIG`, as
         * the Claude Code runner writes no MCP config then; else the tools of the snapshot, or why it cannot be read.
         */
        fun load(env: Map<String, String>): Loaded {
            val launcher = env[ENV_LAUNCHER]?.takeIf { it.isNotBlank() } ?: return Loaded.None
            val config = env[ENV_TOOL_CONFIG]?.takeIf { it.isNotBlank() } ?: return Loaded.None
            return try {
                Loaded.Ready(fromSnapshot(Path.of(config).readText()), Path.of(launcher))
            } catch (e: IOException) {
                Loaded.Unreadable("the file cannot be read")
            } catch (e: InvalidPathException) {
                Loaded.Unreadable("its path is not valid")
            } catch (e: SnapshotException) {
                Loaded.Unreadable(e.message.orEmpty())
            }
        }

        /** The tools of a tool config snapshot's [text]; throws [SnapshotException] when it is not a snapshot this build reads. */
        private fun fromSnapshot(text: String): LoopClientTools {
            val json =
                try {
                    Json.parseToJsonElement(text) as? JsonObject
                } catch (e: SerializationException) {
                    null
                } ?: throw SnapshotException("it is not a JSON object")
            val version = json.string("version") ?: throw SnapshotException("it has no version")
            if (version != SNAPSHOT_VERSION) throw SnapshotException("it has version $version, this build reads $SNAPSHOT_VERSION")
            val kind = json.string("tracker_kind") ?: throw SnapshotException("it has no tracker_kind")
            val states =
                (json["defined_states"] as? JsonArray)
                    ?.map {
                        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                            ?: throw SnapshotException("a defined state is not a string")
                    }?.takeIf { it.isNotEmpty() } ?: throw SnapshotException("it has no defined_states")
            return LoopClientTools(kind, states)
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content

        private fun function(
            name: String,
            description: String,
            inputSchema: JsonObject,
        ): JsonObject =
            buildJsonObject {
                put("type", "function")
                put("name", name)
                put("description", description)
                put("inputSchema", inputSchema)
            }

        private fun objectSchema(
            properties: JsonObject,
            required: List<String>,
        ): JsonObject =
            buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
    }

    private class SnapshotException(
        message: String,
    ) : Exception(message)
}

/**
 * The arguments of `submit_result`: the PLAN section 7 result, field for field, as `.loop/result.json` holds it
 * (m2-15), the input schema `loop tool mcp` gives the tool (`SubmitResultSchema` in the `mcp` module, which this module
 * cannot depend on without a build script change). The cli test `CodexClientToolsRunTest` keeps the two equal.
 */
internal object SubmitResultArguments {
    val properties: JsonObject =
        buildJsonObject {
            put("status", enum("Whether the work is done, or why it stopped.", "done", "blocked", "needs_info", "failed"))
            put(
                "deliverable",
                obj(
                    "What the work produced.",
                    "type" to enum("The kind of deliverable.", "pull_request", "files", "report", "external_action"),
                    "branch" to nullable("string", "The branch that holds the work."),
                    "pr_url" to nullable("string", "The pull request, when there is one."),
                    "paths" to array("The files the work produced.", type("string")),
                    "action_plan" to nullable("string", "For an external action: the target, the exact content and the undo step."),
                ),
            )
            put(
                "summary",
                described(
                    "string",
                    "One or two sentences. Required unless status is done: the question of needs_info, the reason of blocked and failed.",
                ),
            )
            put(
                "acceptance",
                array(
                    "One row per acceptance criterion of the issue.",
                    obj(
                        null,
                        "criterion" to described("string", "The criterion, as the issue writes it."),
                        "proof" to enum("How it is proven.", "check", "review", "human"),
                        "ref" to nullable("string", "The evidence file or the test name."),
                        required = listOf("criterion", "proof"),
                    ),
                ),
            )
            put("residual_human_steps", array("What a human still has to do.", JsonObject(emptyMap())))
            put("followups", array("Work found on the way that belongs to another issue.", JsonObject(emptyMap())))
            put("notes_for_next_attempt", described("string", "What a next attempt should know."))
        }

    private fun type(name: String): JsonObject = buildJsonObject { put("type", name) }

    private fun described(
        type: String,
        description: String,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("description", description)
        }

    private fun nullable(
        type: String,
        description: String,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null"))))
            put("description", description)
        }

    private fun enum(
        description: String,
        vararg values: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(values.map(::JsonPrimitive)))
            put("description", description)
        }

    private fun array(
        description: String,
        items: JsonElement,
    ): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("items", items)
            put("description", description)
        }

    private fun obj(
        description: String?,
        vararg properties: Pair<String, JsonElement>,
        required: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.toMap()))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            if (description != null) put("description", description)
        }
}
