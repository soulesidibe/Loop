package loop.runner.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import loop.api.RunnerEvent
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Classifies structured ACP JSON-RPC errors into [RunnerEvent.TurnFailed.Category.RunnerUnavailable]
 * or ordinary failures (`null`), aligned with `RunnerEvent.kt` (`bl-39`, `bl-141`) and `m4-08`.
 *
 * Matches only structured JSON-RPC codes, structured HTTP statuses, explicit error class or enum
 * identifiers, and whole-token POSIX/Node transport errno codes (with no HTTP status), never loose
 * free-text phrases or raw agent stdout/stderr lines.
 */
internal object AcpErrors {
    data class Classification(
        val category: RunnerEvent.TurnFailed.Category?,
        val retryAfter: Duration? = null,
        val quotaReset: AcpQuotaReset.Reset? = null,
    )

    private const val INVALID_PARAMS_CODE = -32602L

    private val UNAVAILABLE_HTTP_STATUSES = setOf(401L, 403L, 429L, 503L, 529L)

    private val STRUCTURED_MODEL_CODES =
        setOf(
            "model_not_found",
            "modelnotfound",
            "unknown_model",
            "unsupported_model",
        )

    private val STRUCTURED_AUTH_CODES =
        setOf(
            "ineligibletiererror",
            "unauthenticated",
            "auth_required",
            "authentication_required",
            "invalid_credentials",
            "token_expired",
        )

    private val STRUCTURED_RATE_LIMIT_CODES =
        setOf(
            "rate_limit",
            "rate_limited",
            "rate_limit_exceeded",
            "quota_exceeded",
            "resource_exhausted",
            "overloaded_error",
        )

    private val HTTP_STATUS_LINE_PATTERNS =
        listOf(
            Regex("""\bhttp\s+401\b"""),
            Regex("""\bhttp\s+403\b"""),
            Regex("""\bhttp\s+429\b"""),
            Regex("""\bhttp\s+503\b"""),
            Regex("""\bhttp\s+529\b"""),
            Regex("""\b401\s+unauthorized\b"""),
            Regex("""\b403\s+forbidden\b"""),
            Regex("""\b429\s+too\s+many\s+requests\b"""),
            Regex("""\b503\s+service\s+unavailable\b"""),
            Regex("""\b529\s+overloaded\b"""),
        )

    private val ERRNO_TOKEN =
        Regex("""\b(econnrefused|econnreset|etimedout|enotfound|eai_again|ehostunreach|enetunreach)\b""")

    private val IDENTIFIER_TOKEN = Regex("""[a-z0-9_]+""")

    /**
     * Classifies a structured JSON-RPC error response for [method]. Returns `RunnerUnavailable` only
     * for structured model refusals, authentication/eligibility codes or 401/403 statuses, rate limit
     * or overload codes or 429/503/529 statuses (a Google `RESOURCE_EXHAUSTED` or `QUOTA_EXHAUSTED`
     * error at any depth of [data] included), or POSIX/Node transport errno codes with no HTTP status.
     * A rate limit or status classification carries its wait, a quota reset measured from [now].
     */
    fun classify(
        method: String?,
        code: Long,
        message: String,
        data: JsonElement? = null,
        now: Instant = Clock.System.now(),
    ): Classification {
        val dataObj = data as? JsonObject
        val httpStatus = extractHttpStatus(dataObj)
        val combined = buildSearchText(message, dataObj)
        val tokens = IDENTIFIER_TOKEN.findAll(combined).map { it.value }.toSet()

        val isModelRefusal =
            (method == AcpHandshake.METHOD_SESSION_SET_MODEL && code == INVALID_PARAMS_CODE) ||
                "unknown model id" in combined ||
                tokens.any { it in STRUCTURED_MODEL_CODES }
        if (isModelRefusal) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        if (httpStatus != null && httpStatus in UNAVAILABLE_HTTP_STATUSES) {
            return unavailableWithWait(message, data, now)
        }

        if (HTTP_STATUS_LINE_PATTERNS.any { it.containsMatchIn(combined) }) {
            return unavailableWithWait(message, data, now)
        }

        if (tokens.any { it in STRUCTURED_AUTH_CODES }) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        if (tokens.any { it in STRUCTURED_RATE_LIMIT_CODES } || AcpQuotaReset.isQuotaExhausted(data)) {
            return unavailableWithWait(message, data, now)
        }

        // m4-08: a transport failure with no HTTP status at all is RunnerUnavailable; one with an HTTP
        // status outside the unavailability set reached the server and is an ordinary failure.
        if (httpStatus == null && ERRNO_TOKEN.containsMatchIn(combined)) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        return Classification(category = null)
    }

    /**
     * A `RunnerUnavailable` that may carry a wait: an explicit retry key of the error data first, else the quota reset
     * [AcpQuotaReset] finds (bl-215), else none.
     */
    private fun unavailableWithWait(
        message: String,
        data: JsonElement?,
        now: Instant,
    ): Classification {
        val unavailable = RunnerEvent.TurnFailed.Category.RunnerUnavailable
        extractRetryAfter(data as? JsonObject)?.let { return Classification(unavailable, retryAfter = it) }
        val reset = AcpQuotaReset.find(message, data, now)
        return Classification(unavailable, retryAfter = reset?.retryAfter, quotaReset = reset)
    }

    private fun extractHttpStatus(data: JsonObject?): Long? {
        if (data == null) return null
        for (key in listOf("httpStatus", "httpStatusCode", "http_status", "status", "statusCode")) {
            val value = (data[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (value != null && value in 100L..599L) return value
        }
        return null
    }

    private fun extractRetryAfter(data: JsonObject?): Duration? {
        if (data == null) return null
        for (key in listOf("retryAfterMs", "retry_after_ms")) {
            val ms = (data[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (ms != null && ms > 0L) return ms.milliseconds
        }
        for (key in listOf("retryAfterSeconds", "retry_after_seconds", "retryAfter", "retry_after")) {
            val secs = (data[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (secs != null && secs > 0.0) return secs.seconds
        }
        return null
    }

    private fun buildSearchText(
        message: String,
        data: JsonObject?,
    ): String {
        val parts = mutableListOf(message)
        if (data != null) {
            for (key in listOf("type", "errorType", "code", "reason", "error", "message", "details")) {
                (data[key] as? JsonPrimitive)?.contentOrNull?.let { parts += it }
            }
        }
        return parts.joinToString(" ").lowercase(Locale.ROOT)
    }
}
