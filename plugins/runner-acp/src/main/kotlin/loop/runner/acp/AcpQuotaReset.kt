package loop.runner.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * Reads when an exhausted Gemini quota resets from a rate limit error (bl-215). Google's 429 is `RESOURCE_EXHAUSTED`
 * with an `ErrorInfo` of reason `QUOTA_EXHAUSTED` whose `metadata` holds `model`, `quotaResetDelay` and
 * `quotaResetTimeStamp`, a `RetryInfo` with `retryDelay`, and the message `Your quota will reset after <duration>`.
 * `agy acp` may pass that body in the JSON-RPC error data, nested in a string field, or only its message, so the
 * error data is searched at any depth, a JSON body inside a string included.
 *
 * Only the reset time and a plain model id are read: nothing else of the error, such as an account id, an email or a
 * token, leaves this object.
 */
internal object AcpQuotaReset {
    /** The quota resets at [resetsAt], [retryAfter] from now; [model] is the model the error named, if any. */
    data class Reset(
        val retryAfter: Duration,
        val resetsAt: Instant,
        val model: String?,
    )

    private const val MAX_DEPTH = 16
    private const val MAX_EMBEDDED_CHARS = 1_000_000

    private const val UNIT_PATTERN = """(?:h|ms|m|s|us|µs|ns)"""
    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)($UNIT_PATTERN)""")
    private val MESSAGE_RESET =
        Regex("""quota will reset after\s+((?:\d+(?:\.\d+)?$UNIT_PATTERN)+)""", RegexOption.IGNORE_CASE)
    private val PLAIN_MODEL_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")

    private val UNIT_NANOS =
        mapOf(
            "h" to BigDecimal(3_600_000_000_000L),
            "m" to BigDecimal(60_000_000_000L),
            "s" to BigDecimal(1_000_000_000L),
            "ms" to BigDecimal(1_000_000L),
            "us" to BigDecimal(1_000L),
            "µs" to BigDecimal(1_000L),
            "ns" to BigDecimal.ONE,
        )

    /**
     * The quota reset of an error with [message] and [data], measured from [now]. The wait comes from the first valid
     * positive value of, in order: `quotaResetTimeStamp` minus [now], `quotaResetDelay`, `retryDelay`, all in [data] at
     * any depth, then `Your quota will reset after <duration>` in [message] or a string of [data]. A past timestamp or
     * an unparsable value is skipped. Null when none is found.
     */
    fun find(
        message: String,
        data: JsonElement?,
        now: Instant,
    ): Reset? {
        val scan = scan(data)
        val wait =
            scan.firstField("quotaResetTimeStamp") { untilTimestamp(it, now) }
                ?: scan.firstField("quotaResetDelay", ::parseDuration)
                ?: scan.firstField("retryDelay", ::parseDuration)
                ?: (sequenceOf(message) + scan.strings).firstNotNullOfOrNull(::messageWait)
                ?: return null
        val model =
            scan.objects
                .firstNotNullOfOrNull { (it["metadata"] as? JsonObject)?.string("model") }
                ?.takeIf { PLAIN_MODEL_ID.matches(it) }
        return Reset(retryAfter = wait, resetsAt = now + wait, model = model)
    }

    /**
     * True when [data] holds, at any depth, a Google error of status `RESOURCE_EXHAUSTED` or an `ErrorInfo` of reason
     * `QUOTA_EXHAUSTED`: structured fields, never free text.
     */
    fun isQuotaExhausted(data: JsonElement?): Boolean =
        scan(data).objects.any { it.string("status") == "RESOURCE_EXHAUSTED" || it.string("reason") == "QUOTA_EXHAUSTED" }

    /**
     * Parses a duration in Google's format, one or more number and unit pairs such as `4h23m27.716645894s`, `27s` or
     * `850ms` (units `h`, `m`, `s`, `ms`, `us`, `ns`). Null when [text] is anything else or not positive.
     */
    fun parseDuration(text: String): Duration? {
        if (text.isEmpty()) return null
        var nanos = BigDecimal.ZERO
        var position = 0
        while (position < text.length) {
            val part = DURATION_PART.matchAt(text, position) ?: return null
            nanos += BigDecimal(part.groupValues[1]) * UNIT_NANOS.getValue(part.groupValues[2])
            position = part.range.last + 1
        }
        val whole = nanos.setScale(0, RoundingMode.DOWN)
        if (whole.signum() <= 0 || whole > BigDecimal(Long.MAX_VALUE)) return null
        return whole.toLong().nanoseconds
    }

    private class Scan(
        val objects: List<JsonObject>,
        val strings: List<String>,
    ) {
        /** The first positive wait [read] gives from a string field [key] of any object, in document order. */
        fun firstField(
            key: String,
            read: (String) -> Duration?,
        ): Duration? = objects.firstNotNullOfOrNull { obj -> obj.string(key)?.let(read)?.takeIf { it.isPositive() } }
    }

    private fun untilTimestamp(
        text: String,
        now: Instant,
    ): Duration? = parseInstant(text)?.let { it - now }

    private fun messageWait(text: String): Duration? =
        MESSAGE_RESET
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.let(::parseDuration)

    /** Every object and string of [data] in document order, descending into a JSON body embedded in a string. */
    private fun scan(data: JsonElement?): Scan {
        val objects = mutableListOf<JsonObject>()
        val strings = mutableListOf<String>()

        fun walk(
            element: JsonElement,
            depth: Int,
        ) {
            if (depth > MAX_DEPTH) return
            when (element) {
                is JsonObject -> {
                    objects += element
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                is JsonPrimitive ->
                    if (element.isString) {
                        strings += element.content
                        embeddedJson(element.content)?.let { walk(it, depth + 1) }
                    }
            }
        }
        data?.let { walk(it, 0) }
        return Scan(objects, strings)
    }

    /** The JSON object or array inside [text], from its first `{` or `[` to its last `}` or `]`, if it parses. */
    private fun embeddedJson(text: String): JsonElement? {
        if (text.length > MAX_EMBEDDED_CHARS) return null
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }
            .getOrNull()
            ?.takeIf { it is JsonObject || it is JsonArray }
    }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
