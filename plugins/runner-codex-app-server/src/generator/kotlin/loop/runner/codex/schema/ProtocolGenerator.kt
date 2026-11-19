package loop.runner.codex.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The package of the definitions at the schema's top level; the `v2` definitions go in its `v2` subpackage. */
const val BASE_PACKAGE = "loop.runner.codex.protocol"

/**
 * Writes the Kotlin types of the codex app server protocol (m3-02). Arguments: the schema file, the `codex-version`
 * file written next to it, the codex version pinned in `gradle/libs.versions.toml`, the output directory. Fails when
 * the schema was written by another codex version than the pinned one.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: <schema file> <codex-version file> <pinned codex version> <output directory>" }
    val (schemaFile, versionFile, pinned, outDir) = args
    val recorded = Path.of(versionFile).readText().trim()
    require(recorded == pinned) {
        "the schema was written by codex $recorded, gradle/libs.versions.toml pins $pinned: " +
            "run plugins/runner-codex-app-server/schema/regenerate.sh"
    }
    val schema = Json.parseToJsonElement(Path.of(schemaFile).readText()).jsonObject
    val protocol = ProtocolGenerator(schema, pinned).generate()
    val out = Path.of(outDir)
    out.toFile().deleteRecursively()
    for (file in protocol.files) {
        val target = out.resolve(file.path)
        target.parent.createDirectories()
        target.writeText(file.content)
    }
}

/** One generated Kotlin source file, [path] relative to the source root. */
class GeneratedFile(
    val path: String,
    val content: String,
)

/**
 * The generated sources, and the schema places left untyped as `JsonElement`, each as `<fully qualified place>
 * (<reason>)`.
 */
class GeneratedProtocol(
    val files: List<GeneratedFile>,
    val untyped: List<String>,
)

/**
 * Maps the app server JSON schema (draft 07, as `codex app-server generate-json-schema` writes it) to kotlinx
 * serialization types, every type name fully qualified so no generated name can shadow another:
 *
 * - an object with properties is a data class, a property outside `required` nullable with a null default, and an
 *   object with no properties a data object;
 * - a string `enum` is an enum class;
 * - a `oneOf` whose variants all carry a one value string `enum` on the same required key is a sealed interface with
 *   that key as its class discriminator, the properties around the `oneOf` repeated in each variant;
 * - a `oneOf` or `anyOf` of string enums and one key objects (a Rust enum, externally tagged) is a sealed interface
 *   with an [loop.runner.codex.protocol.ExternallyTaggedSerializer], a variant wrapping an object a data class, one
 *   wrapping anything else a value class, and a variant with a type but no shape, like `{"type": ["string",
 *   "object"]}`, the `Unknown` value class every other value reads as;
 * - `anyOf` with null is a nullable type, `allOf` of one schema is that schema, a string, integer, number or boolean
 *   is the Kotlin type, `additionalProperties` alone is a map;
 * - anything else is `JsonElement`, recorded in [GeneratedProtocol.untyped].
 */
class ProtocolGenerator(
    private val schema: JsonObject,
    private val codexVersion: String,
) {
    private val untyped = mutableListOf<String>()

    fun generate(): GeneratedProtocol {
        val definitions = schema.obj("definitions") ?: error("the schema has no definitions")
        val files = mutableListOf<GeneratedFile>()
        for ((name, definition) in definitions) {
            if (name != "v2") files += file(BASE_PACKAGE, name, definition.jsonObject)
        }
        definitions.obj("v2")?.forEach { (name, definition) ->
            files += file("$BASE_PACKAGE.v2", name, definition.jsonObject)
        }
        files += versionFile()
        return GeneratedProtocol(files, untyped.toList())
    }

    private fun file(
        pkg: String,
        name: String,
        definition: JsonObject,
    ): GeneratedFile {
        require(IDENTIFIER.matches(name)) { "the definition $name is not a Kotlin identifier" }
        val lines = declaration(pkg, name, definition)
        val optIn = lines.any { it.contains(DISCRIMINATOR) }
        val content =
            buildString {
                appendLine(header())
                if (optIn) appendLine("@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)\n")
                appendLine("package $pkg")
                appendLine()
                lines.forEach { appendLine(it.trimEnd()) }
            }
        return GeneratedFile("${pkg.replace('.', '/')}/$name.kt", content)
    }

    private fun header(): String =
        "// Generated from plugins/runner-codex-app-server/schema/codex_app_server_protocol.schemas.json, " +
            "written by codex $codexVersion.\n// Do not edit: see plugins/runner-codex-app-server/schema/README.md.\n"

    private fun versionFile(): GeneratedFile =
        GeneratedFile(
            "${BASE_PACKAGE.replace('.', '/')}/CodexCliVersion.kt",
            """
            |${header()}
            |package $BASE_PACKAGE
            |
            |/** The codex CLI version the protocol types were generated from, `codex-cli` in `gradle/libs.versions.toml`. */
            |object CodexCliVersion {
            |    const val PINNED: String = ${literal(codexVersion)}
            |}
            |
            """.trimMargin(),
        )

    // Top level definitions.

    private fun declaration(
        pkg: String,
        name: String,
        definition: JsonObject,
    ): List<String> {
        val fqn = "$pkg.$name"
        val description = definition.str("description")
        val variants = definition.arr("oneOf") ?: definition.arr("anyOf")
        if (variants != null) {
            val tag = commonTag(variants)
            return when {
                tag != null -> taggedUnion(name, fqn, definition, variants, tag)
                variants.all { isStringEnum(it) } ->
                    enumClass(name, variants.flatMap { variant -> enumEntries(variant.jsonObject) }, description)
                variants.all { isStringEnum(it) || isWrapper(it) || isCatchAll(it) } &&
                    variants.count { isCatchAll(it) } <= 1 ->
                    externallyTagged(name, fqn, variants, description)
                else -> typeAlias(name, fqn, definition, description)
            }
        }
        if (isStringEnum(definition)) return enumClass(name, enumEntries(definition), description)
        val properties = definition.obj("properties")
        if (definition.types() == listOf("object") || properties != null) {
            val additional = definition["additionalProperties"]
            return when {
                !properties.isNullOrEmpty() ->
                    dataClass(name, fqn, properties, definition.required(), description)
                additional is JsonObject -> typeAlias(name, fqn, definition, description)
                else -> dataClass(name, fqn, JsonObject(emptyMap()), emptySet(), description)
            }
        }
        return typeAlias(name, fqn, definition, description)
    }

    private fun typeAlias(
        name: String,
        fqn: String,
        definition: JsonObject,
        description: String?,
    ): List<String> {
        val scope = Scope(fqn, name)
        val recorded = untyped.size
        var type = type(definition, scope, name)
        // The alias itself is the untyped place, not a member of it.
        for (i in recorded until untyped.size) untyped[i] = untyped[i].replace("$fqn.$name (", "$fqn (")
        if (scope.nested.isNotEmpty()) {
            // An alias cannot hold the inline types the definition needs.
            untyped.removeAll { it.startsWith("$fqn.") }
            untyped += "$fqn (inline types in an alias)"
            type = JSON_ELEMENT
        }
        return kdoc(description) + "typealias $name = $type"
    }

    // Classes.

    private fun dataClass(
        name: String,
        fqn: String,
        properties: JsonObject,
        required: Set<String>,
        description: String?,
        serialName: String? = null,
        supertype: String? = null,
        exclude: String? = null,
    ): List<String> {
        val scope = Scope(fqn, name)
        val parameters = mutableListOf<String>()
        val names = mutableSetOf<String>()
        for ((key, propertySchema) in properties) {
            if (key == exclude) continue
            val propertyName = unique(propertyName(key), names)
            val propertyType = type(propertySchema, scope, pascal(key))
            val type = if (key in required) propertyType else nullable(propertyType)
            parameters += kdoc((propertySchema as? JsonObject)?.str("description"))
            if (propertyName != key) parameters += "@kotlinx.serialization.SerialName(${literal(key)})"
            parameters += "val ${escape(propertyName)}: $type${if (type.endsWith("?")) " = null" else ""},"
        }
        val lines = kdoc(description).toMutableList()
        lines += "@kotlinx.serialization.Serializable"
        if (serialName != null) lines += "@kotlinx.serialization.SerialName(${literal(serialName)})"
        val inheritance = supertype?.let { " : $it" } ?: ""
        val body = if (scope.nested.isEmpty()) "" else " {"
        if (parameters.isEmpty()) {
            lines += "data object $name$inheritance$body"
        } else {
            lines += "data class $name("
            lines += indent(parameters)
            lines += ")$inheritance$body"
        }
        if (scope.nested.isNotEmpty()) {
            lines += indent(scope.nested.flatMapIndexed { i, nested -> if (i == 0) nested else listOf("") + nested })
            lines += "}"
        }
        return lines
    }

    private fun taggedUnion(
        name: String,
        fqn: String,
        definition: JsonObject,
        variants: JsonArray,
        tag: String,
    ): List<String> {
        val scope = Scope(fqn, name)
        val shared = definition.obj("properties") ?: JsonObject(emptyMap())
        val sharedRequired = definition.required()
        val body = mutableListOf<String>()
        for (variant in variants.map { it.jsonObject }) {
            val properties = variant.obj("properties")!!
            val value = properties.obj(tag)!!.enumValues().single()
            val variantName = scope.reserve(pascal(value))
            if (body.isNotEmpty()) body += ""
            body +=
                dataClass(
                    variantName,
                    "$fqn.$variantName",
                    JsonObject(shared + properties),
                    sharedRequired + variant.required(),
                    variant.str("description"),
                    serialName = value,
                    supertype = fqn,
                    exclude = tag,
                )
        }
        return kdoc(definition.str("description")) +
            listOf("@kotlinx.serialization.Serializable", "@$DISCRIMINATOR(${literal(tag)})", "sealed interface $name {") +
            indent(body) + "}"
    }

    private fun externallyTagged(
        name: String,
        fqn: String,
        variants: JsonArray,
        description: String?,
    ): List<String> {
        val scope = Scope(fqn, name)
        val serializer = scope.reserve("Serializer")
        val body = mutableListOf<String>()
        val units = mutableListOf<String>()
        val wrappers = mutableListOf<String>()
        var fallback = "null"
        for (variant in variants.map { it.jsonObject }) {
            if (isCatchAll(variant)) {
                val unknownName = scope.reserve("Unknown")
                val doc = "A value no other variant matches, kept as read."
                body += valueClass(unknownName, "$fqn.$unknownName", null, doc, fqn, serializable = false)
                fallback = "$FALLBACK($fqn.$unknownName::class, { $fqn.$unknownName(it) }, { it.value })"
            } else if (isStringEnum(variant)) {
                for ((value, doc) in enumEntries(variant)) {
                    val unitName = scope.reserve(pascal(value))
                    body += kdoc(doc) + "data object $unitName : $fqn"
                    units += "${literal(value)} to $fqn.$unitName,"
                }
            } else {
                val (key, fields) = variant.obj("properties")!!.entries.single()
                val inner = fields.jsonObject
                val wrapperName = scope.reserve(pascal(key))
                val doc = variant.str("description") ?: inner.str("description")
                body +=
                    if (inner.types() == listOf("object") && inner.keys.none { it in COMPOSITIONS }) {
                        val properties = inner.obj("properties") ?: JsonObject(emptyMap())
                        dataClass(wrapperName, "$fqn.$wrapperName", properties, inner.required(), doc, supertype = fqn)
                    } else {
                        valueClass(wrapperName, "$fqn.$wrapperName", inner, doc, fqn, serializable = true)
                    }
                wrappers += "$WRAPPER(${literal(key)}, $fqn.$wrapperName::class, $fqn.$wrapperName.serializer()),"
            }
        }
        body += "object $serializer : loop.runner.codex.protocol.ExternallyTaggedSerializer<$fqn>("
        body += "    ${literal(fqn)},"
        body += "    mapOf<kotlin.String, $fqn>("
        body += indent(indent(units))
        body += "    ),"
        body += "    listOf<$WRAPPER<out $fqn>>("
        body += indent(indent(wrappers))
        body += "    ),"
        body += "    $fallback,"
        body += ")"
        return kdoc(description) +
            listOf("@kotlinx.serialization.Serializable(with = $fqn.$serializer::class)", "sealed interface $name {") +
            indent(body) + "}"
    }

    /** A value class of [schema], or of `JsonElement` when null. */
    private fun valueClass(
        name: String,
        fqn: String,
        schema: JsonElement?,
        description: String?,
        supertype: String,
        serializable: Boolean,
    ): List<String> {
        val scope = Scope(fqn, name)
        val type = schema?.let { type(it, scope, "Value") } ?: JSON_ELEMENT
        val lines = kdoc(description).toMutableList()
        if (serializable) lines += "@kotlinx.serialization.Serializable"
        lines += "@kotlin.jvm.JvmInline"
        lines += "value class $name("
        lines += "    val value: $type,"
        lines += ") : $supertype${if (scope.nested.isEmpty()) "" else " {"}"
        if (scope.nested.isNotEmpty()) {
            lines += indent(scope.nested.flatMapIndexed { i, nested -> if (i == 0) nested else listOf("") + nested })
            lines += "}"
        }
        return lines
    }

    private fun enumClass(
        name: String,
        entries: List<Pair<String, String?>>,
        description: String?,
    ): List<String> {
        val names = mutableSetOf<String>()
        val body =
            entries.flatMap { (value, doc) ->
                kdoc(doc) +
                    "@kotlinx.serialization.SerialName(${literal(value)})" +
                    "${unique(constantName(value), names)},"
            }
        return kdoc(description) + listOf("@kotlinx.serialization.Serializable", "enum class $name {") + indent(body) + "}"
    }

    // Types.

    /** The Kotlin type of [schema], declaring any inline enum or object it needs in [scope], named after [hint]. */
    private fun type(
        schema: JsonElement,
        scope: Scope,
        hint: String,
    ): String {
        if (schema !is JsonObject) return JSON_ELEMENT
        schema.str("\$ref")?.let { return reference(it) }
        schema.arr("allOf")?.let { parts ->
            return if (parts.size == 1) type(parts[0], scope, hint) else untyped(scope, hint, "allOf")
        }
        schema.arr("anyOf")?.let { members ->
            val present = members.filterNot { isNull(it) }
            val nullable = present.size < members.size
            return when {
                present.size == 1 && nullable -> nullable(type(present[0], scope, hint))
                present.all { isPrimitive(it) } -> if (nullable) "$JSON_PRIMITIVE?" else JSON_PRIMITIVE
                else -> untyped(scope, hint, "anyOf")
            }
        }
        if (schema.containsKey("oneOf")) return untyped(scope, hint, "inline oneOf")
        val types = schema.types()
        val present = types - "null"
        val type =
            when {
                schema.containsKey("enum") -> inlineEnum(schema, scope, hint)
                present.isEmpty() -> JSON_ELEMENT
                present.size > 1 -> untyped(scope, hint, "several types")
                else ->
                    when (present.single()) {
                        "string" -> "kotlin.String"
                        "integer" -> if (schema.str("format") in INT_FORMATS) "kotlin.Int" else "kotlin.Long"
                        "number" -> "kotlin.Double"
                        "boolean" -> "kotlin.Boolean"
                        "array" -> {
                            val items = schema["items"]?.let { type(it, scope, "${hint}Item") } ?: JSON_ELEMENT
                            "kotlin.collections.List<$items>"
                        }
                        "object" -> inlineObject(schema, scope, hint)
                        else -> untyped(scope, hint, "type ${present.single()}")
                    }
            }
        return if ("null" in types || (present.isEmpty() && types.isNotEmpty())) nullable(type) else type
    }

    private fun inlineEnum(
        schema: JsonObject,
        scope: Scope,
        hint: String,
    ): String {
        if (!isStringEnum(schema)) return untyped(scope, hint, "enum that is not of strings")
        val name = scope.reserve(hint)
        scope.nested += enumClass(name, enumEntries(schema), null)
        return "${scope.fqn}.$name"
    }

    private fun inlineObject(
        schema: JsonObject,
        scope: Scope,
        hint: String,
    ): String {
        val properties = schema.obj("properties")
        val additional = schema["additionalProperties"]
        return when {
            !properties.isNullOrEmpty() -> {
                val name = scope.reserve(hint)
                scope.nested += dataClass(name, "${scope.fqn}.$name", properties, schema.required(), null)
                "${scope.fqn}.$name"
            }
            additional is JsonObject -> "kotlin.collections.Map<kotlin.String, ${type(additional, scope, "${hint}Value")}>"
            else -> JSON_OBJECT
        }
    }

    private fun reference(ref: String): String =
        when {
            ref.startsWith("#/definitions/v2/") -> "$BASE_PACKAGE.v2.${ref.removePrefix("#/definitions/v2/")}"
            ref.startsWith("#/definitions/") -> "$BASE_PACKAGE.${ref.removePrefix("#/definitions/")}"
            else -> error("unsupported reference $ref")
        }

    private fun untyped(
        scope: Scope,
        hint: String,
        reason: String,
    ): String {
        untyped += "${scope.fqn}.$hint ($reason)"
        return JSON_ELEMENT
    }

    // Schema shapes.

    /** The required key every variant tags with a one value string enum, distinct per variant, or null. */
    private fun commonTag(variants: JsonArray): String? {
        if (variants.isEmpty()) return null
        val candidates =
            variants.map { variant ->
                val obj = variant as? JsonObject ?: return null
                val properties = obj.obj("properties") ?: return null
                properties.keys.filter { key ->
                    key in obj.required() && (properties[key] as? JsonObject)?.enumValues()?.size == 1
                }
            }
        val shared = candidates.reduce { acc, keys -> acc.intersect(keys.toSet()).toList() }
        return shared
            .sortedBy { TAG_PREFERENCE.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .firstOrNull { tag ->
                val values =
                    variants.map {
                        it.jsonObject
                            .obj("properties")!!
                            .obj(tag)!!
                            .enumValues()
                            .single()
                    }
                values.toSet().size == values.size
            }
    }

    private fun isStringEnum(schema: JsonElement): Boolean {
        val obj = schema as? JsonObject ?: return false
        val values = obj.arr("enum") ?: return false
        return values.all { it is JsonPrimitive && it.isString }
    }

    /** A one key object, required, the form of a Rust enum variant with fields. */
    private fun isWrapper(schema: JsonElement): Boolean {
        val obj = schema as? JsonObject ?: return false
        val properties = obj.obj("properties") ?: return false
        return obj.types() == listOf("object") &&
            properties.size == 1 &&
            properties.values.single() is JsonObject &&
            obj.required() == properties.keys
    }

    /** A variant with a type and nothing else, like `{"type": ["string", "object"]}`: any other value. */
    private fun isCatchAll(schema: JsonElement): Boolean {
        val obj = schema as? JsonObject ?: return false
        return obj.types().isNotEmpty() && obj.keys.all { it in setOf("type", "description", "title") }
    }

    private fun isNull(schema: JsonElement): Boolean = (schema as? JsonObject)?.types() == listOf("null")

    private fun isPrimitive(schema: JsonElement): Boolean {
        val types = (schema as? JsonObject)?.types() ?: return false
        return types.size == 1 && types.single() in PRIMITIVES && schema.keys.none { it in COMPOSITIONS }
    }

    private fun enumEntries(schema: JsonObject): List<Pair<String, String?>> {
        val values = schema.enumValues()
        val doc = schema.str("description")
        return values.map { it to if (values.size == 1) doc else null }
    }

    /** Names declared inside the class of [fqn], whose simple name is [owner]. */
    private class Scope(
        val fqn: String,
        private val owner: String,
    ) {
        val nested = mutableListOf<List<String>>()
        private val names = mutableSetOf<String>()

        fun reserve(base: String): String {
            val start = if (base == owner) "${base}Value" else base
            return unique(start, names)
        }
    }

    private companion object {
        const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
        const val JSON_PRIMITIVE = "kotlinx.serialization.json.JsonPrimitive"
        const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
        const val DISCRIMINATOR = "kotlinx.serialization.json.JsonClassDiscriminator"
        const val WRAPPER = "loop.runner.codex.protocol.ExternallyTaggedSerializer.Wrapper"
        const val FALLBACK = "loop.runner.codex.protocol.ExternallyTaggedSerializer.Fallback"
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val INT_FORMATS = setOf("int8", "int16", "int32", "uint8", "uint16")
        val PRIMITIVES = setOf("string", "integer", "number", "boolean")
        val COMPOSITIONS = setOf("oneOf", "anyOf", "allOf", "\$ref", "enum")
        val TAG_PREFERENCE = listOf("type", "method", "kind", "mode")
    }
}

// Naming.

private val KEYWORDS =
    setOf(
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
    )

/** The words of an identifier written in any case: `on-request`, `acceptForSession`, `item_id`, `HTTPServer`. */
fun words(text: String): List<String> =
    text
        .replace(Regex("[^A-Za-z0-9]+"), " ")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

fun pascal(text: String): String {
    val name = words(text).joinToString("") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    return when {
        name.isEmpty() -> "Value"
        name.first().isDigit() -> "V$name"
        else -> name
    }
}

fun propertyName(text: String): String {
    val name = pascal(text).replaceFirstChar { it.lowercase() }
    return if (name.first().isDigit()) "v$name" else name
}

fun constantName(text: String): String {
    val name = words(text).joinToString("_") { it.uppercase() }
    return when {
        name.isEmpty() -> "EMPTY"
        name.first().isDigit() -> "V_$name"
        else -> name
    }
}

private fun unique(
    base: String,
    taken: MutableSet<String>,
): String {
    var name = base
    var suffix = 2
    while (!taken.add(name)) name = "$base${suffix++}"
    return name
}

private fun escape(name: String): String = if (name in KEYWORDS) "`$name`" else name

/** A Kotlin string literal of [text]. */
fun literal(text: String): String =
    buildString {
        append('"')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

/** A KDoc block of [description], with the comment delimiters inside it defused. */
fun kdoc(description: String?): List<String> {
    if (description.isNullOrBlank()) return emptyList()
    val body =
        description
            .trim()
            .replace("*/", "*&#47;")
            .replace("/*", "&#47;*")
            .lines()
            .map { if (it.isBlank()) " *" else " * ${it.trimEnd()}" }
    return listOf("/**") + body + " */"
}

private fun indent(lines: List<String>): List<String> = lines.map { if (it.isEmpty()) it else "    $it" }

private fun nullable(type: String): String = if (type.endsWith("?")) type else "$type?"

// Schema access.

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.required(): Set<String> = arr("required")?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()

private fun JsonObject.enumValues(): List<String> = arr("enum")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun JsonObject.types(): List<String> =
    when (val type = this["type"]) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }
