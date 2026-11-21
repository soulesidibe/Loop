package loop.tracker.github

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.TrackerError
import java.io.IOException
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One token's requests to the GitHub REST API, shared by [GitHubTracker] and [GitHubPullRequests] (m2-25). Every
 * request carries the token and GitHub's headers, a write its JSON body too, and is sent once, never retried here.
 * After a rate limit answer, or a success that spends the last request, no request is sent until GitHub's reset
 * time, and every request fails fast with [TrackerError.RateLimited]. [toString] never shows the token.
 */
class GitHubClient(
    /** The API root, such as `https://api.github.com`, with no trailing `/`. */
    val apiUrl: URI,
    token: String,
    private val transport: GitHubTransport = JdkGitHubTransport(),
    private val clock: Clock = Clock.System,
) {
    /** No request is sent before this time, set by a rate limit; null when requests may go. */
    @Volatile
    private var heldUntil: Instant? = null

    private val headers =
        mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to API_VERSION,
            "User-Agent" to USER_AGENT,
        )

    private val writeHeaders = headers + ("Content-Type" to "application/json")

    /**
     * Sends one request, once: a write with its JSON [body] is never retried here. The response on a success, null
     * when the status is in [absent]. Fails with [TrackerError.Unavailable] when GitHub cannot be reached, with
     * [TrackerError.RateLimited] while requests are held and on a rate limit answer, with [TrackerError.Status] on any
     * other non success.
     */
    suspend fun send(
        method: String,
        url: URI,
        absent: Set<Int>,
        body: String? = null,
    ): GitHubResponse? = exchange(method, url, absent, body).takeIf { it.status in SUCCESS }

    /**
     * Sends one request like [send], but returns the response when its status is a success or in [expected], so the
     * caller reads the body of an expected failure, such as a 422 that names its reason (m2-25).
     */
    suspend fun exchange(
        method: String,
        url: URI,
        expected: Set<Int>,
        body: String? = null,
    ): GitHubResponse {
        heldUntil?.let { if (clock.now() < it) throw TrackerError.RateLimited(it) }
        val response =
            try {
                transport.send(method, url, if (body == null) headers else writeHeaders, body)
            } catch (e: IOException) {
                throw TrackerError.Unavailable(e)
            }
        val remaining = response.header("x-ratelimit-remaining")?.trim()
        val reset =
            response
                .header("x-ratelimit-reset")
                ?.trim()
                ?.toLongOrNull()
                ?.let(Instant::fromEpochSeconds)
        heldUntil = if (remaining == "0") reset else null
        if (response.status in SUCCESS) return response
        if (response.status in expected) return response
        if (response.status == 429 || (response.status == 403 && (remaining == "0" || response.header("retry-after") != null))) {
            val retryAfter =
                response
                    .header("retry-after")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { clock.now() + it.seconds }
            val until = retryAfter ?: reset?.takeIf { remaining == "0" } ?: (clock.now() + SECONDARY_LIMIT_WAIT)
            heldUntil = until
            throw TrackerError.RateLimited(until)
        }
        throw TrackerError.Status(response.status, detail(response.body))
    }

    override fun toString(): String = "GitHubClient(apiUrl=$apiUrl)"

    companion object {
        const val API_VERSION: String = "2022-11-28"

        const val USER_AGENT: String = "loop"

        private val SUCCESS = 200..299

        /** GitHub asks to wait at least a minute after a secondary rate limit that names no time. */
        private val SECONDARY_LIMIT_WAIT = 60.seconds

        private const val MAX_DETAIL = 200

        /** [body] as JSON; a body that is not JSON fails with [TrackerError.Malformed] naming [url]. */
        fun parse(
            body: String,
            url: URI,
        ): JsonElement =
            try {
                Json.parseToJsonElement(body)
            } catch (e: SerializationException) {
                throw TrackerError.Malformed(url.toString(), e)
            }

        /**
         * GitHub's `message` and every `errors[].message` of an error body, joined with `: `, else the body's first
         * line, kept short. A 422 names its cause only in `errors` (bl-68).
         */
        fun detail(body: String): String {
            val json = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            val messages =
                listOfNotNull(json?.string("message")) +
                    (json?.get("errors") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("message") }
            val text =
                messages.joinToString(": ").ifEmpty { null }
                    ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?: "no message"
            return short(text)
        }

        /** [text] cut at 200 characters, with `...` when cut. */
        fun short(text: String): String = if (text.length > MAX_DETAIL) text.take(MAX_DETAIL) + "..." else text

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
