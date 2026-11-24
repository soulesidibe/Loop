package loop.runner.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import loop.api.RunnerEvent
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Classifies structured ACP JSON-RPC errors into [RunnerEvent.TurnFailed.Category.RunnerUnavailable]
 * or ordinary failures (`null`), aligned with `RunnerEvent.kt` (`bl-39`, `bl-141`) and `m4-08`.
 *
 * Only structured JSON-RPC error responses are passed here, never raw agent stdout/stderr text or
 * process exit codes.
 */
internal object AcpErrors {
    data class Classification(
        val category: RunnerEvent.TurnFailed.Category?,
        val retryAfter: Duration? = null,
    )

    private const val INVALID_PARAMS_CODE = -32602L

    private val UNAVAILABLE_HTTP_STATUSES = setOf(401L, 403L, 429L, 503L, 529L)

    private val MODEL_REFUSAL_MARKERS =
        listOf(
            "unknown model",
            "model_not_found",
            "modelnotfound",
            "model not found",
            "unsupported model",
        )

    private val AUTH_AND_ELIGIBILITY_MARKERS =
        listOf(
            "ineligibletiererror",
            "unauthenticated",
            "authentication required",
            "auth_required",
            "expired login",
            "login expired",
            "token expired",
            "invalid credentials",
            "not logged in",
            "access denied",
            "permission_denied",
            "http 401",
            "http 403",
            "status 401",
            "status 403",
            "401 unauthorized",
            "403 forbidden",
        )

    private val RATE_LIMIT_AND_OVERLOAD_MARKERS =
        listOf(
            "rate_limit",
            "rate limit",
            "too many requests",
            "quota exceeded",
            "quota_exceeded",
            "resource_exhausted",
            "overloaded",
            "capacity",
            "http 429",
            "status 429",
            "429 too many requests",
            "503 service unavailable",
            "529 overloaded",
        )

    private val NETWORK_TRANSPORT_MARKERS =
        listOf(
            "econnrefused",
            "econnreset",
            "etimedout",
            "enotfound",
            "eai_again",
            "ehostunreach",
            "enetunreach",
            "connection refused",
            "connection reset",
            "network error",
            "network is unreachable",
            "socket hang up",
            "fetch failed",
            "unable to connect",
            "dns lookup failed",
            "tls handshake",
        )

    /**
     * Classifies a structured JSON-RPC error response for [method]. Returns `RunnerUnavailable` only
     * for authentication/eligibility/access failures, rate limit/quota/overload, model refusals, or
     * runtime-reported network transport failures with no HTTP status.
     */
    fun classify(
        method: String?,
        code: Long,
        message: String,
        data: JsonElement? = null,
    ): Classification {
        val dataObj = data as? JsonObject
        val httpStatus = extractHttpStatus(dataObj)
        val combined = buildSearchText(message, dataObj)

        val isModelRefusal =
            (method == AcpHandshake.METHOD_SESSION_SET_MODEL && code == INVALID_PARAMS_CODE) ||
                MODEL_REFUSAL_MARKERS.any { it in combined }
        if (isModelRefusal) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        if (httpStatus != null && httpStatus in UNAVAILABLE_HTTP_STATUSES) {
            return Classification(
                category = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
                retryAfter = extractRetryAfter(dataObj),
            )
        }

        val isAuthOrEligibility = AUTH_AND_ELIGIBILITY_MARKERS.any { it in combined }
        if (isAuthOrEligibility) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        val isRateLimitOrOverload = RATE_LIMIT_AND_OVERLOAD_MARKERS.any { it in combined }
        if (isRateLimitOrOverload) {
            return Classification(
                category = RunnerEvent.TurnFailed.Category.RunnerUnavailable,
                retryAfter = extractRetryAfter(dataObj),
            )
        }

        // m4-08: a transport failure with no HTTP status at all is RunnerUnavailable; one with an HTTP
        // status outside the unavailability set reached the server and is an ordinary failure.
        if (httpStatus == null && NETWORK_TRANSPORT_MARKERS.any { it in combined }) {
            return Classification(RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        }

        return Classification(category = null)
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
