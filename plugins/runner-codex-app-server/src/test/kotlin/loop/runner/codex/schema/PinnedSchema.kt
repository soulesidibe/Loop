package loop.runner.codex.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Checks JSON against a definition of the pinned schema bundle (m3-18), so a test can prove that what Loop writes is
 * valid for the pinned app server. It reads the draft-07 keywords the bundle uses: `$ref`, `type`, `enum`,
 * `properties`, `required`, `additionalProperties`, `items`, `allOf`, `anyOf`, `oneOf`, `minLength` and `minimum`.
 * `format`, `default`, `title` and `description` only annotate.
 *
 * Stricter than draft-07 on one point: a member that an object schema's `properties` does not declare is a problem
 * when the schema says nothing of `additionalProperties`. The app server ignores such a member, so writing one would
 * be a silent mistake, a misspelled policy field for example. A schema that has `oneOf` or `anyOf` beside its own
 * `properties`, as `ThreadItem` and `McpServerElicitationRequestParams` do (m3-20), declares its members in both: the
 * check is made by its options, each of which counts the members the schema around it declares as its own.
 */
class PinnedSchema(
    private val root: JsonObject,
) {
    /** The problems of [value] against the definition [definition], such as `ClientRequest` or `v2/ThreadStartParams`; empty when valid. */
    fun problems(
        definition: String,
        value: JsonElement,
    ): List<String> = check(resolve("#/definitions/$definition"), value, "$")

    private fun resolve(ref: String): JsonElement {
        require(ref.startsWith("#/")) { "only local references are read: $ref" }
        return ref.removePrefix("#/").split("/").fold(root as JsonElement) { element, key ->
            element.jsonObject[key] ?: error("the schema has no $ref")
        }
    }

    private fun check(
        schema: JsonElement,
        value: JsonElement,
        path: String,
        inherited: Set<String> = emptySet(),
    ): List<String> {
        if (schema is JsonPrimitive) return if (schema.boolean) emptyList() else listOf("$path is not allowed")
        val rules = schema.jsonObject
        val problems = mutableListOf<String>()
        rules["\$ref"]?.let { problems += check(resolve(it.jsonPrimitive.content), value, path) }
        rules["type"]?.let { type ->
            val types = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (types.none { isOfType(it, value) }) return problems + "$path is not of type ${types.joinToString(" or ")}"
        }
        rules["enum"]?.let { if (value !in it.jsonArray) problems += "$path is not one of ${it.jsonArray}" }
        rules["minLength"]?.let { min ->
            if (value is JsonPrimitive && value.isString && value.content.length < min.jsonPrimitive.int) problems += "$path is too short"
        }
        rules["minimum"]?.let { min ->
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (number != null && number < min.jsonPrimitive.doubleOrNull!!) problems += "$path is below $min"
        }
        // The options of a schema that declares members of its own count those as theirs.
        val declared = inherited + rules["properties"]?.jsonObject?.keys.orEmpty()
        rules["allOf"]?.jsonArray?.forEach { problems += check(it, value, path, declared) }
        rules["anyOf"]?.jsonArray?.let { options ->
            if (options.none { check(it, value, path, declared).isEmpty() }) problems += "$path matches no option of anyOf"
        }
        rules["oneOf"]?.jsonArray?.let { options ->
            val matching = options.count { check(it, value, path, declared).isEmpty() }
            if (matching != 1) problems += "$path matches $matching options of oneOf, not exactly one"
        }
        if (value is JsonObject) problems += checkMembers(rules, value, path, inherited)
        if (value is JsonArray) {
            rules["items"]?.let { items -> value.forEachIndexed { index, item -> problems += check(items, item, "$path[$index]") } }
        }
        return problems
    }

    private fun checkMembers(
        rules: JsonObject,
        value: JsonObject,
        path: String,
        inherited: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val properties = rules["properties"]?.jsonObject
        rules["required"]?.jsonArray?.forEach { name ->
            if (name.jsonPrimitive.content !in value) problems += "$path.${name.jsonPrimitive.content} is required"
        }
        value.forEach { (name, member) ->
            val declared = properties?.get(name)
            val additional = rules["additionalProperties"]
            when {
                declared != null -> problems += check(declared, member, "$path.$name")
                additional != null -> problems += check(additional, member, "$path.$name")
                properties != null && name !in inherited && "oneOf" !in rules && "anyOf" !in rules ->
                    problems += "$path.$name is not a member the schema declares"
            }
        }
        return problems
    }

    private fun isOfType(
        type: String,
        value: JsonElement,
    ): Boolean {
        val primitive = value as? JsonPrimitive
        val bare = primitive != null && primitive !is JsonNull && !primitive.isString
        return when (type) {
            "null" -> value is JsonNull
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> primitive != null && primitive.isString
            "boolean" -> bare && primitive.content in setOf("true", "false")
            "integer" -> bare && primitive.longOrNull != null
            "number" -> bare && primitive.doubleOrNull != null
            else -> error("the schema names an unknown type $type")
        }
    }

    companion object {
        /** The bundle the build generated the types from, `schema/codex_app_server_protocol.schemas.json`. */
        fun load(): PinnedSchema =
            PinnedSchema(Json.parseToJsonElement(Path.of(System.getProperty("loop.codexSchema")).readText()).jsonObject)
    }
}
