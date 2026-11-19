package loop.runner.codex.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

/**
 * The JSON form of a Rust enum the app server serializes externally tagged (m3-02): a unit variant is a bare string,
 * `"never"`, and a variant with fields is an object with one key, its name, holding the fields,
 * `{"granular": {...}}`. The generated sealed interfaces of such enums, like `AskForApproval`, declare a nested
 * `Serializer` built on this class. A [fallback], when the schema has a catch all variant, holds any other value as
 * read. JSON only.
 */
abstract class ExternallyTaggedSerializer<T : Any>(
    serialName: String,
    private val units: Map<String, T>,
    private val wrappers: List<Wrapper<out T>>,
    private val fallback: Fallback<out T>?,
) : KSerializer<T> {
    /** A variant with fields: [key] holds the fields of [type], written by [serializer]. */
    class Wrapper<V : Any>(
        val key: String,
        val type: KClass<V>,
        val serializer: KSerializer<V>,
    ) {
        internal fun encode(
            json: Json,
            value: Any,
        ): JsonElement {
            @Suppress("UNCHECKED_CAST") // only called after type.isInstance(value)
            return json.encodeToJsonElement(serializer, value as V)
        }
    }

    /** The catch all variant [type]: [wrap] holds a value no other variant matches, [unwrap] gives it back. */
    class Fallback<V : Any>(
        val type: KClass<V>,
        val wrap: (JsonElement) -> V,
        val unwrap: (V) -> JsonElement,
    ) {
        internal fun encode(value: Any): JsonElement {
            @Suppress("UNCHECKED_CAST") // only called after type.isInstance(value)
            return unwrap(value as V)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(serialName, JsonElement.serializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("${descriptor.serialName} is JSON only")
        val unit = units.entries.firstOrNull { it.value == value }
        val wrapper = wrappers.firstOrNull { it.type.isInstance(value) }
        val element =
            when {
                unit != null -> JsonPrimitive(unit.key)
                wrapper != null -> JsonObject(mapOf(wrapper.key to wrapper.encode(json.json, value)))
                fallback != null && fallback.type.isInstance(value) -> fallback.encode(value)
                else -> throw SerializationException("${descriptor.serialName} has no variant for $value")
            }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("${descriptor.serialName} is JSON only")
        val element = json.decodeJsonElement()
        val value: T? =
            when (element) {
                is JsonPrimitive -> if (element.isString) units[element.content] else null
                is JsonObject ->
                    element.entries.singleOrNull()?.let { (key, fields) ->
                        wrappers
                            .firstOrNull { it.key == key }
                            ?.let { json.json.decodeFromJsonElement(it.serializer, fields) }
                    }
                else -> null
            }
        return value ?: fallback?.wrap?.invoke(element)
            ?: throw SerializationException("${descriptor.serialName} has no variant for $element")
    }
}
