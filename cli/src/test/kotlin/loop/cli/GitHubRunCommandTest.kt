package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * bl-23: `loop run --once` with `tracker.kind: github`, end to end through the installed launcher.
 * The GitHub API is a loopback server that serves the responses recorded from `octocat/Hello-World`
 * by the github tracker tests, so the run never reaches the network.
 */
class GitHubRunCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_bl23RunToken0123456789abcdefghijkl"

    private val recorded: Path =
        Path.of(
            System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"),
        )

    /** The three open issues of the recorded first page, 26, 27 and 28, by number. */
    private val openIssues: Map<String, JsonObject> =
        Json
            .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
            .jsonArray
            .associate { (it as JsonObject).getValue("number").toString() to it }

    /** Every request the server got: its method, path and query, and its Authorization header. */
    private val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** Every write the server got (m2-23): its method and path, and its body. */
    private val writes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /**
     * When true, the open issues carry the label `ready-for-agent`, the required label of [writeWorkflow] (m2-23).
     * bl-46: preflight refuses an empty `tracker.required_labels`, so the issues are routed unless a test says not.
     */
    private var routed = true

    /**
     * The `author_association` a routed issue gets. The recorded issues are by `NONE`, an author the default
     * `tracker.provider.trusted_associations` does not trust (bl-157), so a routed issue is by `OWNER` unless a test
     * says otherwise.
     */
    private var author = "OWNER"

    /** The recorded answer to a label add, when set; the recorded add by default. */
    private var addLabelsAnswer: String = "add-labels-26"

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/") { exchange ->
                val target = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
                requests += "${exchange.requestMethod} $target" to exchange.requestHeaders.getFirst("Authorization")
                val (status, body) =
                    if (exchange.requestMethod == "GET") {
                        answer(exchange.requestURI.rawPath, exchange.requestURI.rawQuery.orEmpty())
                    } else {
                        writes += "${exchange.requestMethod} $target" to exchange.requestBody.readBytes().decodeToString()
                        answerWrite(exchange.requestMethod, exchange.requestURI.rawPath)
                    }
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                // The recorded rate limit headers, without the recorded link to page 2 on api.github.com.
                exchange.responseHeaders.add("x-ratelimit-limit", "60")
                exchange.responseHeaders.add("x-ratelimit-remaining", "56")
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

    /**
     * The recorded answer to a GET under `/api`: the open list, an empty closed list, one issue, its comments
     * (bl-06 completes an issue with them before each dispatch) or the repository.
     */
    private fun answer(
        path: String,
        query: String,
    ): Pair<Int, String> {
        val repo = "/api/repos/octocat/Hello-World"
        val issue = Regex("$repo/issues/(\\d+)").matchEntire(path)?.groupValues?.get(1)
        val comments = Regex("$repo/issues/(\\d+)/comments").matchEntire(path)?.groupValues?.get(1)
        return when {
            path == "$repo/issues" && "state=open" in query && routed -> 200 to JsonArray(openIssues.values.map(::routedIssue)).toString()
            path == "$repo/issues" && "state=open" in query -> 200 to recorded.resolve("open-issues-page-1.json").readText()
            path == "$repo/issues" -> 200 to JsonArray(emptyList()).toString()
            issue != null && issue in openIssues && routed -> 200 to routedIssue(openIssues.getValue(issue)).toString()
            issue != null && issue in openIssues -> 200 to openIssues.getValue(issue).toString()
            comments == "26" -> 200 to recorded.resolve("issue-comments-26.json").readText()
            comments != null && comments in openIssues -> 200 to JsonArray(emptyList()).toString()
            path == repo -> 200 to recorded.resolve("repository.json").readText()
            else -> 404 to recorded.resolve("issue-not-found.json").readText()
        }
    }

    /**
     * [issue] with the label `ready-for-agent`, no comment and [author] as its author's association, so it is eligible
     * and needs no comments request.
     */
    private fun routedIssue(issue: JsonObject): JsonObject =
        JsonObject(
            issue +
                mapOf(
                    "labels" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("ready-for-agent"))))),
                    "comments" to JsonPrimitive(0),
                    "author_association" to JsonPrimitive(author),
                ),
        )

    /** The recorded answer to a write (m2-23): a label add or removal, a comment or a state patch of issue 26. */
    private fun answerWrite(
        method: String,
        path: String,
    ): Pair<Int, String> {
        val issue = "/api/repos/octocat/Hello-World/issues/26"
        val name =
            when {
                method == "POST" && path == "$issue/labels" -> addLabelsAnswer
                method == "DELETE" && path.startsWith("$issue/labels/") -> "remove-label-26"
                method == "POST" && path == "$issue/comments" -> "create-comment-26"
                method == "PATCH" && path == issue -> "update-issue-26"
                else -> "issue-not-found"
            }
        val status =
            recorded
                .resolve("$name.headers")
                .readLines()
                .first()
                .substringAfter(':')
                .trim()
                .toInt()
        return status to recorded.resolve("$name.json").readText()
    }

    private val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    /** An agent that leaves `hello.txt` and records whether it could see the token variable, then exits 0. */
    private fun writeAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |echo "${'$'}LOOP_ISSUE_ID ${'$'}{GITHUB_TOKEN:-unset}" >> "${fixture.agentCallsFile}"
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun writeWorkflow(
        requiredLabels: String = "  required_labels: [ready-for-agent]\n",
        states: String = "",
    ) {
        // A running loop reads WORKFLOW.md on every tick, so the new text goes to a sibling file first and replaces
        // the workflow in one rename: a plain write truncates the file first, and a tick could read it empty.
        val next = fixture.workflow.resolveSibling("WORKFLOW.md.next")
        next.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: github\n" +
                "  provider:\n" +
                "    repo: octocat/Hello-World\n" +
                "    token: \$GITHUB_TOKEN\n" +
                "    api_url: $apiUrl\n" +
                states +
                requiredLabels +
                "agent:\n" +
                "  max_concurrent_agents: 1\n" +
                "  max_turns: 1\n" +
                "workspace:\n" +
                "  root: ./workspaces\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: \"${fixture.agentScript.toAbsolutePath()}\"\n" +
                "polling:\n" +
                "  interval_ms: 1000\n" +
                "---\n" +
                "Work on {{ issue.identifier }}: {{ issue.title }}\n",
        )
        Files.move(next, fixture.workflow, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Test
    fun `run once with the github tracker dispatches one recorded issue to the command runner`() {
        writeAgent()
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        // The oldest of the three open issues goes first, the one slot is then taken.
        assertEquals(listOf("26 unset"), fixture.agentCallsFile.readLines(), result.toString())
        val hello = fixture.workspace("Hello-World-26").resolve("hello.txt")
        assertTrue(hello.isRegularFile(), "no hello.txt in the workspace\n$result")
        assertEquals("hello from Hello-World-26", hello.readText().trim())
        assertTrue("Hello-World-26  succeeded  open" in result.stdout, result.toString())
        assertTrue("1 attempt, 1 succeeded, 0 failed." in result.stdout, result.toString())
        val dispatched = fixture.query("SELECT issue_id FROM events WHERE kind = 'attempt_started'")
        assertEquals(listOf("default:26"), dispatched.map { it["issue_id"] })
        // Every request went to the loopback server with the token from the environment.
        val sent = requests.toList()
        assertTrue(sent.any { it.first.startsWith("GET /api/repos/octocat/Hello-World/issues?state=open") }, "$sent")
        assertTrue(sent.all { it.first.startsWith("GET ") && it.second == "Bearer $token" }, "$sent")
        assertFalse(token in result.stdout || token in result.stderr, "the token leaked\n$result")
    }

    @Test
    fun `bl-157 a routed issue by an author outside trusted_associations is skipped with its reason and starts no attempt`() {
        author = "NONE"
        writeAgent()
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(0, fixture.agentCalls(), result.toString())
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size)
        val skipped = fixture.query("SELECT issue_id, data FROM events WHERE kind = 'candidate_skipped' ORDER BY issue_id")
        assertEquals(listOf("default:26", "default:27", "default:28"), skipped.map { it["issue_id"] }, "$skipped")
        for (event in skipped) {
            val data = Json.parseToJsonElement(event["data"].orEmpty()) as JsonObject
            assertEquals(JsonPrimitive("untrusted_author (NONE)"), data["reason"], "$event")
        }
        val trace = fixture.loop("trace", "26", "--workflow", fixture.workflow.toString(), env = mapOf("GITHUB_TOKEN" to token))
        assertEquals(0, trace.exitCode, trace.toString())
        assertTrue("untrusted_author (NONE)" in trace.stdout, trace.stdout)
    }

    @Test
    fun `run once with an unset token variable stops startup naming the variable and sends no request`() {
        writeAgent()
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to null))

        assertEquals(1, result.exitCode, result.toString())
        assertTrue("tracker.provider.token uses \$GITHUB_TOKEN, which is not set" in result.stderr, result.toString())
        assertEquals(emptyList(), requests.toList())
        assertEquals(0, fixture.agentCalls())
    }

    @Test
    fun `the token stays out of the tool config snapshot an agent reads`() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |cat "${'$'}LOOP_TOOL_CONFIG" >> "${fixture.agentCallsFile}"
            |
            """.trimMargin(),
        )
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        val snapshot = Json.parseToJsonElement(fixture.agentCallsFile.readText()) as JsonObject
        assertEquals(JsonPrimitive("github"), snapshot["tracker_kind"])
        assertFalse(token in snapshot.toString(), "$snapshot")
        assertFalse("GITHUB_TOKEN" in snapshot.toString(), "$snapshot")
    }

    /** An agent that records whether it could see the token, then queues `loop tool` calls, as Loop's prompt asks. */
    private fun writeToolAgent(vararg calls: String) {
        fixture.agentScript.writeText(
            "#!/bin/sh\nset -e\necho \"\${GITHUB_TOKEN:-unset}\" >> \"${fixture.agentCallsFile}\"\n" +
                calls.joinToString("") { "$it\n" },
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    @Test
    fun `m2-23 an agent's move to review and its comment reach GitHub as label writes and a comment, the token nowhere else`() {
        writeToolAgent("loop tool move review", "loop tool comment \"Ready for review\"")
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf("unset"), fixture.agentCallsFile.readLines(), "the agent never sees the token")
        val issue = "/api/repos/octocat/Hello-World/issues/26"
        assertEquals(
            listOf(
                "POST $issue/labels" to """{"labels":["needs-review"]}""",
                "DELETE $issue/labels/ready-for-agent" to "",
                "POST $issue/comments" to """{"body":"Ready for review"}""",
            ),
            writes.toList(),
        )
        assertTrue(requests.toList().all { it.second == "Bearer $token" }, "${requests.toList()}")
        val moved = fixture.query("SELECT data FROM events WHERE kind = 'issue_moved'").single()["data"].orEmpty()
        assertTrue("\"to\":\"review\"" in moved, moved)
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'issue_commented'").size)
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'tool_request_failed'").size)
        // The token is in no event, no trace and no output.
        val events = fixture.query("SELECT kind, data FROM events").joinToString("\n")
        assertFalse(token in events, events)
        val trace = fixture.loop("trace", "26", "--workflow", fixture.workflow.toString(), env = mapOf("GITHUB_TOKEN" to token))
        assertEquals(0, trace.exitCode, trace.toString())
        assertTrue("issue_moved" in trace.stdout, trace.stdout)
        assertFalse(token in trace.stdout || token in trace.stderr, trace.toString())
        assertFalse(token in result.stdout || token in result.stderr, result.toString())
    }

    @Test
    fun `m2-23 a write GitHub refuses is recorded as tool_request_failed with its status, sent once`() {
        addLabelsAnswer = "write-forbidden"
        writeToolAgent("loop tool move review")
        writeWorkflow()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf("POST /api/repos/octocat/Hello-World/issues/26/labels"), writes.map { it.first }, "never retried")
        val failed = fixture.query("SELECT data FROM events WHERE kind = 'tool_request_failed'").single()["data"].orEmpty()
        val data = Json.parseToJsonElement(failed) as JsonObject
        assertEquals(JsonPrimitive("move"), data["request"])
        assertEquals(JsonPrimitive("review"), data["to"])
        assertEquals(JsonPrimitive("The tracker answered 403: Resource not accessible by personal access token"), data["reason"])
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'issue_moved'").size)
        assertFalse(token in fixture.query("SELECT data FROM events").joinToString())
    }

    @Test
    fun `bl-46 run once with an empty required_labels stops startup before any request`() {
        writeAgent()
        writeWorkflow(requiredLabels = "")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

        assertEquals(1, result.exitCode, result.toString())
        assertTrue(
            "tracker.required_labels is empty, so every move to review or needs_info would be refused" in result.stderr,
            result.toString(),
        )
        assertEquals(emptyList(), requests.toList())
        assertEquals(0, fixture.agentCalls())
    }

    @Test
    fun `bl-46 a reload that empties required_labels or lists a handoff state is rejected and the last good workflow stays`() {
        // The issues do not carry ready-for-agent, so no attempt starts: the run only polls and reloads.
        routed = false
        writeAgent()
        writeWorkflow()
        val good = hash(fixture.workflow.readText())
        val running = fixture.start("run", fixture.workflow.toString(), env = mapOf("GITHUB_TOKEN" to token))
        try {
            await("the first poll", running) { requests.any { it.first.startsWith("GET /api/repos/octocat/Hello-World/issues?") } }
            writeWorkflow(requiredLabels = "")
            val empty = hash(fixture.workflow.readText())
            await("the first rejected edit", running) { reloadFailures().size == 1 }
            writeWorkflow(states = "  active_states: [open, review]\n")
            val handoff = hash(fixture.workflow.readText())
            await("the second rejected edit", running) { reloadFailures().size == 2 }
            fixture.loop("stop", fixture.workflow.toString(), env = mapOf("GITHUB_TOKEN" to token))
            val run = running.await(30)

            val (first, second) = reloadFailures()
            assertEquals(empty, first["config_version"], run.toString())
            assertEquals(good, first["active_version"])
            assertEquals("invalid_config", first["error"])
            assertTrue(
                first.getValue("message").startsWith("tracker.required_labels is empty, so every move to review or needs_info"),
                first.toString(),
            )
            assertEquals(handoff, second["config_version"])
            assertEquals(good, second["active_version"])
            assertEquals("invalid_config", second["error"])
            assertTrue(
                second.getValue("message").startsWith("tracker.active_states has \"review\", a handoff state of the github tracker"),
                second.toString(),
            )
            assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'config_reloaded'").size)
            assertTrue("WORKFLOW.md edit rejected, config ${good.take(8)} stays active" in fixture.humanLog.readText())
            assertEquals(0, fixture.agentCalls())
        } finally {
            running.process.descendants().forEach { it.destroyForcibly() }
            running.process.destroyForcibly()
        }
    }

    /** The `config_reload_failed` events of the run, oldest first, each as its data fields. */
    private fun reloadFailures(): List<Map<String, String>> =
        if (!fixture.storeFile.exists()) {
            emptyList()
        } else {
            fixture
                .query("SELECT data FROM events WHERE kind = 'config_reload_failed' ORDER BY seq")
                .map { row -> (Json.parseToJsonElement(row["data"] ?: "{}") as JsonObject).mapValues { it.value.jsonPrimitive.content } }
        }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun await(
        what: String,
        running: LoopFixture.RunningLoop,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fixture.loop("stop", fixture.workflow.toString(), env = mapOf("GITHUB_TOKEN" to token))
        fail("$what never happened\n${running.await(30)}")
    }
}
