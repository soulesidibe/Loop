package loop.tracker.github

import loop.api.TrackerError
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The rate limit hold of one API origin and token (m4-48, bl-50): no request is sent before [heldUntil]. A request
 * takes a [ticket] before it is sent, which fails fast with [TrackerError.RateLimited] while the hold lasts. Its answer
 * then either [hold]s, which keeps the later of two holds, or [clear]s with its ticket, which removes nothing when a hold
 * was set after the ticket was taken: an answer to a request sent before a newer hold never ends that hold (bl-22).
 * Holds nothing secret, so [toString] shows only the time.
 */
class RateLimitHold(
    private val clock: Clock = Clock.System,
) {
    private var heldUntil: Instant? = null

    /** Counts the holds set, so a clear knows whether one came after its ticket. */
    private var generation = 0L

    /**
     * The ticket a request takes right before it is sent, to [clear] with later. Throws [TrackerError.RateLimited]
     * naming the end of the hold while it lasts: then nothing may be sent.
     */
    @Synchronized
    fun ticket(): Long {
        heldUntil?.let { if (clock.now() < it) throw TrackerError.RateLimited(it) }
        return generation
    }

    /** Holds every request until [until], or until the end of the current hold when that is later. */
    @Synchronized
    fun hold(until: Instant) {
        val current = heldUntil
        heldUntil = if (current != null && current > until) current else until
        generation++
    }

    /** Ends the hold, unless a hold was set after [ticket] was taken. */
    @Synchronized
    fun clear(ticket: Long) {
        if (ticket == generation) heldUntil = null
    }

    @Synchronized
    override fun toString(): String = "RateLimitHold(heldUntil=$heldUntil)"
}

/**
 * The GitHub clients of one `loop run` (m4-48, bl-50): one [GitHubClient] per API URL and token, and one
 * [RateLimitHold] per API origin (scheme, host and port) and token, shared by every client of that origin and token. The
 * run builds one and hands it to the GitHub trackers and to pull request delivery, whose CI wait polls through the same
 * client, so a request one of them spends holds the others, and it keeps them for the life of the process. Every client
 * sends through [transport] and reads the time from [clock]. Clients and holds are keyed by a SHA-256 of the token,
 * and [toString] names the origins only, never a token.
 */
class GitHubClients(
    private val transport: GitHubTransport = JdkGitHubTransport(),
    private val clock: Clock = Clock.System,
) {
    private val clients = ConcurrentHashMap<Pair<String, String>, GitHubClient>()

    private val holds = ConcurrentHashMap<Pair<String, String>, RateLimitHold>()

    /** The client of [apiUrl] and [token], built at the first call and the same one afterwards. */
    fun client(
        apiUrl: URI,
        token: String,
    ): GitHubClient {
        val digest = digest(token)
        return clients.computeIfAbsent(apiUrl.toString() to digest) {
            val hold = holds.computeIfAbsent(origin(apiUrl) to digest) { RateLimitHold(clock) }
            GitHubClient(apiUrl, token, transport, clock, hold)
        }
    }

    override fun toString(): String = "GitHubClients(origins=${holds.keys.map { it.first }.distinct().sorted()})"

    companion object {
        /** `scheme://host:port` of [url], lowercased, with the scheme's default port when none is written. */
        internal fun origin(url: URI): String {
            val scheme = url.scheme.orEmpty().lowercase()
            val port = if (url.port != -1) url.port else if (scheme == "http") HTTP_PORT else HTTPS_PORT
            return "$scheme://${url.host.orEmpty().lowercase()}:$port"
        }

        private fun digest(token: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
    }
}
