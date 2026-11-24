package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * m4-13, story 113: `loop run --once` with a `files` primary tracker `local` and an extra GitHub tracker `gh` on the
 * answers recorded from `octocat/Hello-World`, served by a loopback server. Each tracker is polled with its own states
 * and `required_labels`, their candidates are dispatched in the spec's order, a handoff move removes the labels of the
 * issue's own tracker, ledger rows name the tracker, and a tracker that fails its poll does not stop the other.
 */
class MultiTrackerRunCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m413RunToken0123456789abcdefghijkl"

    private val recorded: Path =
        Path.of(System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"))

    /** The three open issues of the recorded first page, 26, 27 and 28, by number. */
    private val openIssues: Map<String, JsonObject> =
        Json
            .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
            .jsonArray
            .associate { (it as JsonObject).getValue("number").toString() to it }

    /** The labels each GitHub issue carries: 26 and 28 the `gh` tracker's label, 27 only the primary tracker's. */
    private val labels = mapOf("26" to "gh-ready", "27" to "ready-for-agent", "28" to "gh-ready")

    /** When set, the list of open issues answers this status, so the `gh` tracker fails its poll. */
    private var listFailure: Int? = null

    /** Every write the server got: its method and path. */
    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/") { exchange ->
                val path = exchange.requestURI.rawPath
                val (status, body) =
                    if (exchange.requestMethod == "GET") {
                        answer(path, exchange.requestURI.rawQuery.orEmpty())
                    } else {
                        writes += "${exchange.requestMethod} $path"
                        answerWrite(exchange.requestMethod, path)
                    }
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
        path: String,
        query: String,
    ): Pair<Int, String> {
        val issue = Regex("$repo/issues/(\\d+)").matchEntire(path)?.groupValues?.get(1)
        val comments = Regex("$repo/issues/(\\d+)/comments").matchEntire(path)?.groupValues?.get(1)
        return when {
            path == "$repo/issues" && "state=open" in query ->
                listFailure?.let { it to """{"message":"Bad credentials"}""" }
                    ?: (200 to JsonArray(openIssues.values.map(::labelled)).toString())
            path == "$repo/issues" -> 200 to JsonArray(emptyList()).toString()
            issue != null && issue in openIssues -> 200 to labelled(openIssues.getValue(issue)).toString()
            comments != null -> 200 to JsonArray(emptyList()).toString()
            path == repo -> 200 to recorded.resolve("repository.json").readText()
            else -> 404 to recorded.resolve("issue-not-found.json").readText()
        }
    }

    /** [issue] with its label of [labels], no comment, by the repository's owner, so its own labels decide. */
    private fun labelled(issue: JsonObject): JsonObject =
        JsonObject(
            issue +
                mapOf(
                    "labels" to
                        JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive(labels.getValue(issue.getValue("number").toString())))))),
                    "comments" to JsonPrimitive(0),
                    "author_association" to JsonPrimitive("OWNER"),
                ),
        )

    /** The recorded answer to a label add or removal of any issue. */
    private fun answerWrite(
        method: String,
        path: String,
    ): Pair<Int, String> {
        val name =
            when {
                method == "POST" && path.endsWith("/labels") -> "add-labels-26"
                method == "DELETE" && "/labels/" in path -> "remove-label-26"
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

    private val ledgerFile: Path get() = fixture.loopHome.resolve("ledger").resolve("${fixture.projectId()}.jsonl")

    /** An agent that records its issue's tracker and identifier, then moves it to review, as Loop's prompt asks. */
    private fun writeAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${'$'}LOOP_TRACKER_ID ${'$'}LOOP_ISSUE_IDENTIFIER" >> "${fixture.agentCallsFile}"
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun writeWorkflow(maxAgents: Int) {
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
                "  max_concurrent_agents: $maxAgents\n" +
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

    /** A `files` issue in `todo` with [labels], and a `priority` line only when [priority] is set. */
    private fun fileIssue(
        id: String,
        labels: String,
        priority: Int?,
    ) {
        fixture.issueFile(id).writeText(
            "---\nid: $id\ntitle: \"Issue $id\"\nstate: todo\n" +
                (priority?.let { "priority: $it\n" } ?: "") +
                "labels: [$labels]\nblocked_by: []\n---\n\n## What\n\nWrite hello.txt.\n\n## Comments\n",
        )
    }

    private fun run() = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to token))

    @Test
    fun `candidates of both trackers, each filtered by its own labels, are dispatched in the spec's order`() {
        writeAgent()
        writeWorkflow(maxAgents = 3)
        // l-1 has a priority and goes first. GitHub issues have none and a creation date, so 26 and 28 come before
        // l-3, which has neither, and l-3 finds no slot. l-2 and 27 carry only the other tracker's label.
        fileIssue("l-1", "ready-for-agent", priority = 1)
        fileIssue("l-2", "gh-ready", priority = 1)
        fileIssue("l-3", "ready-for-agent", priority = null)

        val result = run()

        assertEquals(0, result.exitCode, result.toString())
        val started = fixture.query("SELECT issue_id FROM events WHERE kind = 'attempt_started' ORDER BY seq").map { it["issue_id"] }
        assertEquals(setOf("local:l-1", "gh:26", "gh:28"), started.toSet(), result.toString())
        assertEquals("local:l-1", started.first(), "the first slot goes to the issue with a priority")
        val skipped = fixture.query("SELECT issue_id, data FROM events WHERE kind = 'candidate_skipped'")
        assertEquals(listOf("local:l-3"), skipped.map { it["issue_id"] }, "$skipped")
        assertTrue("no_available_slots" in skipped.single()["data"].orEmpty(), "$skipped")
        assertEquals(
            setOf("local l-1", "gh Hello-World-26", "gh Hello-World-28"),
            fixture.agentCallsFile.readLines().toSet(),
        )
        // A handoff of a GitHub issue removes the gh tracker's label, never the primary tracker's.
        val removed = writes.filter { it.startsWith("DELETE") }
        assertEquals(
            setOf("DELETE $repo/issues/26/labels/gh-ready", "DELETE $repo/issues/28/labels/gh-ready"),
            removed.toSet(),
            "${writes.toList()}",
        )
        assertEquals("review", fixture.stateOf("l-1"))
        assertEquals("todo", fixture.stateOf("l-2"))
        // Ledger rows and lines carry the tracker id in their own field, beside the namespaced issue id.
        val rows = fixture.query("SELECT issue_id, tracker FROM attempts").associate { it["issue_id"] to it["tracker"] }
        assertEquals<Map<String?, String?>>(mapOf("local:l-1" to "local", "gh:26" to "gh", "gh:28" to "gh"), rows)
        val lines = ledgerFile.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(lines.isNotEmpty(), "no ledger line")
        lines.forEach { line ->
            val issueId = line.getValue("issue_id").jsonPrimitive.content
            assertEquals(issueId.substringBefore(':'), line.getValue("tracker").jsonPrimitive.content, "$line")
        }
    }

    @Test
    fun `a tracker that fails its poll is logged by its id and the other tracker's candidates still dispatch`() {
        listFailure = 401
        writeAgent()
        writeWorkflow(maxAgents = 3)
        fileIssue("l-1", "ready-for-agent", priority = 1)

        val result = run()

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf("local l-1"), fixture.agentCallsFile.readLines(), result.toString())
        assertEquals("review", fixture.stateOf("l-1"))
        assertTrue(
            result.stderr.lines().any { "event=poll" in it && "outcome=failed" in it && "tracker=gh" in it },
            result.toString(),
        )
    }
}
