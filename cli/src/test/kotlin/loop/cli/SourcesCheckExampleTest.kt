package loop.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * m4-23, the example sources check of a research kind, `docs/examples/research/check-sources.sh`, run against a local
 * test server: it passes when every link of the report's sources section answers and fails on a link that does not,
 * a status of 400 or more or a port nobody listens on, and on a section with no link.
 */
class SourcesCheckExampleTest {
    @TempDir
    lateinit var root: Path

    private val script: Path =
        Path
            .of(System.getProperty("loop.rootWorkflow") ?: fail("system property loop.rootWorkflow is not set"))
            .parent
            .resolve("docs/examples/research/check-sources.sh")

    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val status =
                    when (exchange.requestURI.path) {
                        "/ok", "/second" -> 200
                        "/moved" -> 302.also { exchange.responseHeaders.add("Location", "/ok") }
                        else -> 404
                    }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

    private val base = "http://127.0.0.1:${server.address.port}"

    /** A port nobody listens on: bound once to get a free one, then closed. */
    private val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    @AfterEach
    fun stop() = server.stop(0)

    private data class Run(
        val exitCode: Int,
        val output: String,
    )

    private fun check(report: String): Run {
        root.resolve("report.md").writeText(report)
        val process =
            ProcessBuilder("sh", script.toString(), "report.md")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    // A proxy of the host must not stand between curl and the local server.
                    listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY").forEach(environment()::remove)
                    environment()["SOURCES_TIMEOUT"] = "5"
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) fail("the sources check never ended\n$output")
        return Run(process.exitValue(), output)
    }

    private fun report(sources: String) = "# Queues\n\n## Summary\n\nFine.\n\n## Sources\n\n$sources\n\n## Notes\n\n- $base/notes\n"

    @Test
    fun `every reachable link of the sources section passes, a redirect to a reachable page too, links of other sections are not read`() {
        val run = check(report("- [Docs]($base/ok).\n- <$base/second>\n- $base/moved, followed"))

        assertEquals(0, run.exitCode, run.output)
        assertContains(run.output, "reachable: $base/ok (200)")
        assertContains(run.output, "reachable: $base/second (200)")
        assertContains(run.output, "reachable: $base/moved (200)")
        assertFalse(run.output.contains("/notes"), run.output)
    }

    @Test
    fun `a link that answers 404 fails the check and is named`() {
        val run = check(report("- $base/ok\n- $base/gone"))

        assertEquals(1, run.exitCode, run.output)
        assertContains(run.output, "reachable: $base/ok (200)")
        assertContains(run.output, "unreachable: $base/gone (status 404)")
    }

    @Test
    fun `a link nobody answers fails the check and is named`() {
        val run = check(report("- http://127.0.0.1:$closedPort/paper"))

        assertEquals(1, run.exitCode, run.output)
        assertContains(run.output, "unreachable: http://127.0.0.1:$closedPort/paper (no answer, curl exit ")
    }

    @Test
    fun `a sources section with no link, or no sources section at all, fails the check`() {
        val empty = check(report("- A book on queues, 2019"))
        assertEquals(1, empty.exitCode, empty.output)
        assertContains(empty.output, "no link in the Sources section of report.md")

        val missing = check("# Queues\n\n## Summary\n\nFine.\n")
        assertEquals(1, missing.exitCode, missing.output)
        assertContains(missing.output, "no Sources section in report.md")
    }
}
