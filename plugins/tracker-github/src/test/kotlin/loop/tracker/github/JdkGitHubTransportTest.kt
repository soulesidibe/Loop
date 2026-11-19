package loop.tracker.github

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The JDK transport against a server on the loopback address only, never the internet. */
class JdkGitHubTransportTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val seen = mutableMapOf<String, String?>()

    private val base get() = "http://${server.address.hostString}:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    private fun serve(
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
        delayMs: Long = 0,
    ) {
        server.createContext("/") { exchange ->
            for (name in listOf("Authorization", "Accept", "X-GitHub-Api-Version")) seen[name] = exchange.requestHeaders.getFirst(name)
            Thread.sleep(delayMs)
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @Test
    fun `a response keeps its status, its body and its headers by lowercase name, a 404 included`() =
        runTest {
            serve(404, """{"message":"Not Found"}""", "X-RateLimit-Remaining" to "41", "Link" to "<x>; rel=\"next\"")

            val response =
                JdkGitHubTransport().get(
                    URI("$base/repos/o/r/issues/1"),
                    mapOf(
                        "Authorization" to "Bearer t",
                        "Accept" to "application/vnd.github+json",
                    ),
                )

            assertEquals(404, response.status)
            assertEquals("""{"message":"Not Found"}""", response.body)
            assertEquals("41", response.header("X-RateLimit-Remaining"))
            assertEquals("<x>; rel=\"next\"", response.headers["link"]?.single())
            assertEquals("Bearer t", seen["Authorization"])
            assertEquals("application/vnd.github+json", seen["Accept"])
        }

    @Test
    fun `m2-23 a write sends its method, its json body and its content type`() =
        runTest {
            val received = mutableListOf<String>()
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                received +=
                    "${exchange.requestMethod} ${exchange.requestURI.rawPath} ${exchange.requestHeaders.getFirst("Content-Type")} $body"
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
            val json = mapOf("Content-Type" to "application/json")

            for (method in listOf("POST", "PATCH")) {
                assertEquals(200, JdkGitHubTransport().send(method, URI("$base/repos/o/r/issues/1"), json, """{"state":"closed"}""").status)
            }
            assertEquals(200, JdkGitHubTransport().send("DELETE", URI("$base/repos/o/r/issues/1/labels/a%20b"), emptyMap(), null).status)

            assertEquals(
                listOf(
                    """POST /repos/o/r/issues/1 application/json {"state":"closed"}""",
                    """PATCH /repos/o/r/issues/1 application/json {"state":"closed"}""",
                    "DELETE /repos/o/r/issues/1/labels/a%20b null ",
                ),
                received,
            )
        }

    @Test
    fun `a redirect is returned, not followed`() =
        runTest {
            serve(301, "", "Location" to "http://192.0.2.1/elsewhere")

            assertEquals(301, JdkGitHubTransport().get(URI("$base/repos/o/r/issues/1"), emptyMap()).status)
        }

    @Test
    fun `a server that does not answer in time is an io error`() =
        runTest {
            serve(200, "[]", delayMs = 2_000)

            assertFailsWith<IOException> { JdkGitHubTransport(timeout = 200.milliseconds).get(URI("$base/x"), emptyMap()) }
        }

    @Test
    fun `a closed port is an io error`() =
        runTest {
            serve(200, "[]")
            val url = URI("$base/x")
            server.stop(0)

            assertFailsWith<IOException> { JdkGitHubTransport(timeout = 2.seconds).get(url, emptyMap()) }
        }
}
