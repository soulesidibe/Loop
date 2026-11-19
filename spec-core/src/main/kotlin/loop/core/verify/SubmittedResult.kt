package loop.core.verify

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The result an agent submits (m2-15, PLAN section 7), as written to `.loop/result.json` by a runner without
 * tools. Only what Loop acts on is kept: the [status], the [summary] (the question of `needs_info`, the reason
 * of `blocked` and `failed`) and the [acceptance] proofs. The other section 7 fields are validated for their
 * shape only; the saved file keeps them whole.
 */
data class SubmittedResult(
    val status: Status,
    val summary: String?,
    val acceptance: List<Acceptance>,
) {
    enum class Status(
        val value: String,
    ) {
        Done("done"),
        Blocked("blocked"),
        NeedsInfo("needs_info"),
        Failed("failed"),
    }

    /** One acceptance criterion and its proof: `check`, `review` or `human`, with an evidence file or test name. */
    data class Acceptance(
        val criterion: String,
        val proof: String,
        val ref: String?,
    )

    /** Why a result file is not a valid result; its message is the validation error Loop traces. */
    class Invalid(
        message: String,
    ) : Exception(message)

    companion object {
        private val DELIVERABLE_TYPES = listOf("pull_request", "files", "report", "external_action")
        private val PROOFS = listOf("check", "review", "human")
        private val DELIVERABLE_STRINGS = listOf("branch", "pr_url", "action_plan")

        /**
         * Parses and validates [text]: a JSON object whose `status` is one of the four, with a non blank `summary`
         * for every status but `done`. A present field must have its section 7 type; unknown keys are ignored.
         * The failure is an [Invalid] whose message names the first field at fault.
         */
        fun parse(text: String): Result<SubmittedResult> =
            try {
                Result.success(read(text))
            } catch (e: Invalid) {
                Result.failure(e)
            }

        private fun read(text: String): SubmittedResult {
            val element =
                try {
                    Json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    val detail =
                        e.message
                            ?.lineSequence()
                            ?.firstOrNull()
                            ?.trim()
                    throw Invalid("the result is not valid JSON" + detail?.let { ": $it" }.orEmpty())
                }
            val root = element as? JsonObject ?: throw Invalid("the result is not a JSON object")
            val statusText = string(root, "status", "status") ?: throw Invalid("status is required")
            val status =
                Status.entries.firstOrNull { it.value == statusText }
                    ?: throw Invalid("status must be one of ${Status.entries.joinToString { it.value }}, not \"$statusText\"")
            val summary = string(root, "summary", "summary")
            if (status != Status.Done && summary.isNullOrBlank()) throw Invalid("summary is required when status is ${status.value}")
            deliverable(root["deliverable"])
            val acceptance = acceptance(root["acceptance"])
            list(root, "residual_human_steps")
            list(root, "followups")
            string(root, "notes_for_next_attempt", "notes_for_next_attempt")
            return SubmittedResult(status, summary, acceptance)
        }

        private fun deliverable(element: JsonElement?) {
            if (element == null || element is JsonNull) return
            val deliverable = element as? JsonObject ?: throw Invalid("deliverable must be an object")
            string(deliverable, "type", "deliverable.type")?.let { type ->
                if (type !in DELIVERABLE_TYPES) {
                    throw Invalid("deliverable.type must be one of ${DELIVERABLE_TYPES.joinToString()}, not \"$type\"")
                }
            }
            DELIVERABLE_STRINGS.forEach { key -> string(deliverable, key, "deliverable.$key") }
            val paths = deliverable["paths"]
            if (paths != null && paths !is JsonNull) {
                val ok = paths is JsonArray && paths.all { it is JsonPrimitive && it.isString }
                if (!ok) throw Invalid("deliverable.paths must be a list of strings")
            }
        }

        private fun acceptance(element: JsonElement?): List<Acceptance> {
            if (element == null || element is JsonNull) return emptyList()
            val rows = element as? JsonArray ?: throw Invalid("acceptance must be a list")
            return rows.mapIndexed { index, row ->
                val name = "acceptance[$index]"
                val item = row as? JsonObject ?: throw Invalid("$name must be an object")
                val criterion = string(item, "criterion", "$name.criterion") ?: throw Invalid("$name.criterion is required")
                val proof = string(item, "proof", "$name.proof") ?: throw Invalid("$name.proof is required")
                if (proof !in PROOFS) throw Invalid("$name.proof must be one of ${PROOFS.joinToString()}, not \"$proof\"")
                Acceptance(criterion, proof, string(item, "ref", "$name.ref"))
            }
        }

        /** The string at [key], null when absent or null; [name] is the field as the error names it. */
        private fun string(
            obj: JsonObject,
            key: String,
            name: String,
        ): String? {
            val value = obj[key] ?: return null
            if (value is JsonNull) return null
            if (value !is JsonPrimitive || !value.isString) throw Invalid("$name must be a string")
            return value.content
        }

        private fun list(
            obj: JsonObject,
            key: String,
        ) {
            val value = obj[key] ?: return
            if (value !is JsonNull && value !is JsonArray) throw Invalid("$key must be a list")
        }
    }
}
