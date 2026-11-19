package loop.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The input schema of `submit_result`: the PLAN section 7 result, field for field, as `.loop/result.json` holds it
 * (m2-15). Only `status` is required here; a `summary` for every status but `done` is checked by the handler, which
 * reads the same rules as the result file.
 */
object SubmitResultSchema {
    val required: List<String> = listOf("status")

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
        items: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("items", items)
            put("description", description)
        }

    private fun obj(
        description: String?,
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.toMap()))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            description?.let { put("description", it) }
        }
}
