package loop.tracker.github

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** One HTTP response as the adapter reads it. Header names are lowercased. */
data class GitHubResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    /** The first value of the header [name], compared case insensitively; null when absent. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/**
 * Sends one request to the GitHub API: a read with no [body], or a write with a JSON [body] (m2-23). A non success
 * status is a [GitHubResponse], not an error; a server that cannot be reached, or does not answer in time, throws
 * [IOException].
 */
fun interface GitHubTransport {
    suspend fun send(
        method: String,
        url: URI,
        headers: Map<String, String>,
        body: String?,
    ): GitHubResponse
}

/** Sends one GET request, see [GitHubTransport.send]. */
suspend fun GitHubTransport.get(
    url: URI,
    headers: Map<String, String>,
): GitHubResponse = send("GET", url, headers, null)

/**
 * The [GitHubTransport] on the JDK's [HttpClient]. Redirects are not followed, so a moved issue
 * reads as a 301 and the token is never sent to another address. [timeout] bounds each request.
 */
class JdkGitHubTransport(
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(timeout.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) : GitHubTransport {
    override suspend fun send(
        method: String,
        url: URI,
        headers: Map<String, String>,
        body: String?,
    ): GitHubResponse {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(url)
                .timeout(timeout.toJavaDuration())
                .method(method, publisher)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        val response =
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: HttpTimeoutException) {
                throw IOException("GitHub did not answer within $timeout", e)
            }
        return GitHubResponse(
            status = response.statusCode(),
            headers = response.headers().map().mapKeys { it.key.lowercase() },
            body = response.body(),
        )
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
    }
}
