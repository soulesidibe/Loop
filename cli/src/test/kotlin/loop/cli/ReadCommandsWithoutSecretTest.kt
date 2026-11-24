package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * bl-70: `loop status`, `loop trace` and `loop log` read the store and the log, so they work on a github workflow
 * whose token variable is unset, as in a shell that does not hold the token. `loop approve` leads to a delivery, so it
 * still refuses, naming the variable. The GitHub API is a loopback server that serves the recorded issues of
 * `octocat/Hello-World` and counts the requests it gets.
 */
class ReadCommandsWithoutSecretTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_bl70ReadToken0123456789abcdefghijk"

    private val recorded: Path =
        Path.of(System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"))

    /** The recorded open issues, by number. */
    private val openIssues: Map<String, JsonObject> =
        Json
            .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
            .jsonArray
            .associate { (it as JsonObject).getValue("number").toString() to it }

    /** Every request the server got, its method and path. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/") { exchange ->
                val path = exchange.requestURI.rawPath
                requests += "${exchange.requestMethod} $path"
                val number = Regex("/api/repos/octocat/Hello-World/issues/(\\d+)").matchEntire(path)?.groupValues?.get(1)
                val (status, body) =
                    openIssues[number]?.let { 200 to it.toString() }
                        ?: (404 to recorded.resolve("issue-not-found.json").readText())
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    private val unset = mapOf(TOKEN_VAR to null)

    /** A github workflow whose token is `$LOOP_BL70_GITHUB_TOKEN`, with one finished attempt of issue 26 and a log line. */
    private fun project() {
        fixture.workflow.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: github\n" +
                "  provider:\n" +
                "    repo: octocat/Hello-World\n" +
                "    token: \$$TOKEN_VAR\n" +
                "    api_url: $apiUrl\n" +
                "  required_labels: [ready-for-agent]\n" +
                "workspace:\n" +
                "  root: ./workspaces\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: \"${fixture.agentScript.toAbsolutePath()}\"\n" +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
        val started = fixture.attemptStarted("a-1", "26").copy(issueIdentifier = "Hello-World-26")
        fixture.seed(started, started.copy(kind = "attempt_finished", data = mapOf("outcome" to "succeeded", "turns" to "1")))
        fixture.humanLog.parent.createDirectories()
        fixture.humanLog.writeText("17:02:01  Hello-World-26  attempt 1 started\n")
    }

    @Test
    fun `loop status works with the token variable unset`() {
        project()

        val result = fixture.inProcess("status", fixture.workflow.toString(), env = unset)

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stdout, "Project ${fixture.projectId()}")
        assertFalse(TOKEN_VAR in result.stderr, result.toString())
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun `loop log works with the token variable unset`() {
        project()

        val result = fixture.inProcess("log", fixture.workflow.toString(), env = unset)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("17:02:01  Hello-World-26  attempt 1 started", result.stdout.trim())
        assertEquals("", result.stderr.trim(), result.toString())
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun `loop trace works with the token variable unset, reading the issue from the store and saying why`() {
        project()

        val result = fixture.inProcess("trace", "26", "--workflow", fixture.workflow.toString(), env = unset)

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines()
        assertEquals("Issue Hello-World-26", lines.first(), result.toString())
        assertTrue(lines.any { it.startsWith("Attempt 1 ") }, result.toString())
        assertContains(
            result.stderr,
            "loop trace: the tracker is not read, tracker.provider.token uses \$$TOKEN_VAR, which is not set. " +
                "The issue is looked up by its id in the store.",
        )
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun `loop trace with the token variable unset says when the store holds no attempt of the issue`() {
        project()

        val result = fixture.inProcess("trace", "99", "--workflow", fixture.workflow.toString(), env = unset)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("No attempt recorded for this issue in project ${fixture.projectId()}.", result.stdout.trim())
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun `loop trace still reads the issue from the tracker when the token is set`() {
        project()

        val result = fixture.inProcess("trace", "26", "--workflow", fixture.workflow.toString(), env = mapOf(TOKEN_VAR to token))

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(
            result.stdout
                .lines()
                .first()
                .startsWith("Issue Hello-World-26 \""),
            result.toString(),
        )
        assertEquals("", result.stderr.trim(), result.toString())
        assertTrue("GET /api/repos/octocat/Hello-World/issues/26" in requests.toList(), "${requests.toList()}")
        assertFalse(token in result.stdout || token in result.stderr, "the token leaked\n$result")
    }

    @Test
    fun `loop approve still refuses with the token variable unset, naming the variable`() {
        project()

        val result = fixture.inProcess("approve", "26", "--workflow", fixture.workflow.toString(), env = unset + mapOf("USER" to "soule"))

        assertEquals(1, result.exitCode, result.toString())
        assertContains(result.stderr, "loop approve: tracker.provider.token uses \$$TOKEN_VAR, which is not set")
        assertEquals(emptyList(), requests.toList())
        assertEquals(emptyList(), fixture.query("SELECT kind FROM events WHERE kind = 'approval_given'"))
    }

    private companion object {
        const val TOKEN_VAR = "LOOP_BL70_GITHUB_TOKEN"
    }
}
