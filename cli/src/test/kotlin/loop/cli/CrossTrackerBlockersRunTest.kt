package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * m4-16, story 116: `loop run --once` with a `files` primary tracker `local` and an extra GitHub tracker `gh` on the
 * answers recorded from `octocat/Hello-World`, served by a loopback server. A `files` issue whose description says
 * `Blocked by gh:26` is skipped with `blocked_cross_tracker` while issue 26 is open and dispatched once it reads
 * closed; a blocker of a tracker the workflow does not have keeps its issue blocked; a blocker named by two issues is
 * read once per tick.
 */
class CrossTrackerBlockersRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m416RunToken0123456789abcdefghijkl"

    private val recorded: Path =
        Path.of(System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"))

    /** The three open issues of the recorded first page, 26, 27 and 28, by number, none a candidate of `gh`. */
    private val recordedIssues: Map<String, JsonObject> =
        Json
            .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
            .jsonArray
            .associate { (it as JsonObject).getValue("number").toString() to it }

    /** The GitHub issues that read closed. */
    private val closed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** When set, every by id read of a GitHub issue answers this status. */
    private var byIdFailure: Int? = null

    /** The path of every GET of one GitHub issue, in order. */
    private val issueReads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/") { exchange ->
                val (status, body) = answer(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery.orEmpty())
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
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

    private val repo = "/api/repos/octocat/Hello-World"

    private fun answer(
        method: String,
        path: String,
        query: String,
    ): Pair<Int, String> {
        if (method != "GET") return 404 to recorded.resolve("issue-not-found.json").readText()
        val issue = Regex("$repo/issues/(\\d+)").matchEntire(path)?.groupValues?.get(1)
        if (issue != null) issueReads += path
        return when {
            path == "$repo/issues" && "state=open" in query ->
                200 to JsonArray(recordedIssues.filterKeys { it !in closed }.values.map(::current)).toString()
            path == "$repo/issues" -> 200 to JsonArray(emptyList()).toString()
            issue != null && byIdFailure != null -> byIdFailure!! to """{"message":"Server Error"}"""
            issue != null && issue in recordedIssues -> 200 to current(recordedIssues.getValue(issue)).toString()
            path == repo -> 200 to recorded.resolve("repository.json").readText()
            else -> 404 to recorded.resolve("issue-not-found.json").readText()
        }
    }

    /** [issue] as it reads now: closed when in [closed], with a label that is not the `gh` tracker's, by the owner. */
    private fun current(issue: JsonObject): JsonObject {
        val number = issue.getValue("number").toString()
        return JsonObject(
            issue +
                mapOf(
                    "state" to JsonPrimitive(if (number in closed) "closed" else "open"),
                    "labels" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("not-for-loop"))))),
                    "comments" to JsonPrimitive(0),
                    "author_association" to JsonPrimitive("OWNER"),
                ),
        )
    }

    private val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    /** An agent that records its issue's identifier, then moves it to review, as Loop's prompt asks. */
    private fun writeAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${'$'}LOOP_ISSUE_IDENTIFIER" >> "${fixture.agentCallsFile}"
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun writeWorkflow() {
        fixture.workflow.writeText(
            "---\n" +
                "tracker:\n" +
                "  id: local\n" +
                "  kind: files\n" +
                "  active_states: [todo]\n" +
                "  required_labels: [ready-for-agent]\n" +
                "trackers:\n" +
                "  - id: gh\n" +
                "    kind: github\n" +
                "    provider:\n" +
                "      repo: octocat/Hello-World\n" +
                "      token: \$GITHUB_TOKEN\n" +
                "      api_url: $apiUrl\n" +
                "    required_labels: [gh-ready]\n" +
                "agent:\n" +
                "  max_concurrent_agents: 3\n" +
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
    }

    /** A `files` issue in `todo`, ready for an agent, whose `## What` section holds [what]. */
    private fun fileIssue(
        id: String,
        what: String,
    ) {
        fixture.issueFile(id).writeText(
            "---\nid: $id\ntitle: \"Issue $id\"\nstate: todo\npriority: 1\n" +
                "labels: [ready-for-agent]\nblocked_by: []\n---\n\n## What\n\n$what\n\n## Comments\n",
        )
    }

    private fun run() = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

    private fun skips(): Map<String?, List<String?>> =
        fixture
            .query("SELECT issue_id, data FROM events WHERE kind = 'candidate_skipped' ORDER BY seq")
            .groupBy({ it["issue_id"] }, { it["data"] })

    @Test
    fun `a files issue blocked by an open GitHub issue is skipped, and dispatched once the blocker reads closed`() {
        writeAgent()
        writeWorkflow()
        fileIssue("l-1", "Write hello.txt.\n\nBlocked by gh:26")
        fileIssue("l-2", "Write bye.txt.\n\nBlocked by gh:26, gh:28")
        fileIssue("l-3", "Write prose.txt.\n\nBlocked by bl-54 so the two never run side by side.")

        val blocked = run()

        assertEquals(0, blocked.exitCode, blocked.toString())
        assertEquals(listOf("l-3"), fixture.agentCallsFile.readLines(), blocked.toString())
        val skipped = skips()
        assertEquals(setOf("local:l-1", "local:l-2"), skipped.keys, "$skipped")
        assertTrue(
            skipped.getValue("local:l-1").single().orEmpty().contains("blocked_cross_tracker gh:26 (open)"),
            "$skipped",
        )
        assertTrue(
            skipped.getValue("local:l-2").single().orEmpty().contains("blocked_cross_tracker gh:26 (open), gh:28 (open)"),
            "$skipped",
        )
        // One tick: issue 26, named by two issues, is read once, and 28 once.
        assertEquals(listOf("$repo/issues/26", "$repo/issues/28"), issueReads.sorted(), "${issueReads.toList()}")
        assertEquals("todo", fixture.stateOf("l-1"))

        closed += "26"
        issueReads.clear()
        val released = run()

        assertEquals(0, released.exitCode, released.toString())
        assertEquals(listOf("l-3", "l-1"), fixture.agentCallsFile.readLines(), released.toString())
        assertEquals("review", fixture.stateOf("l-1"))
        assertEquals("todo", fixture.stateOf("l-2"), "gh:28 is still open")
        assertEquals(listOf("$repo/issues/26", "$repo/issues/28"), issueReads.sorted(), "${issueReads.toList()}")
    }

    @Test
    fun `a blocker of a tracker the workflow does not have keeps the issue blocked, naming the tracker`() {
        writeAgent()
        writeWorkflow()
        fileIssue("l-1", "Blocked by lin:MKT-12")

        val result = run()

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(!Files.exists(fixture.agentCallsFile) || fixture.agentCallsFile.readLines().isEmpty(), result.toString())
        val skipped = skips()
        assertTrue(
            skipped.getValue("local:l-1").single().orEmpty().contains("blocked_cross_tracker lin:MKT-12 (unknown tracker lin)"),
            "$skipped",
        )
        assertEquals(emptyList(), issueReads.toList(), "an unknown tracker is never read")
    }

    @Test
    fun `a blocker GitHub cannot read keeps the issue blocked with the reason`() {
        byIdFailure = 500
        writeAgent()
        writeWorkflow()
        fileIssue("l-1", "Blocked by gh:26")

        val result = run()

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(!Files.exists(fixture.agentCallsFile) || fixture.agentCallsFile.readLines().isEmpty(), result.toString())
        val reason = skips().getValue("local:l-1").single().orEmpty()
        assertTrue("blocked_cross_tracker gh:26 (unreadable: " in reason && "500" in reason, reason)
        assertEquals("todo", fixture.stateOf("l-1"))
    }
}
