package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.fail

/**
 * m4-14: the GitHub tracker of a two tracker test, on the answers recorded from `octocat/Hello-World`, served by a
 * loopback server. Its one open issue is 26, labelled [label] and by the repository's owner, so it is a candidate of a
 * tracker whose `required_labels` is [label]. A label add or removal changes the labels it shows, so a handoff move
 * takes it out of the active set. Every write is kept in [writes], with its body, so a test sees what reached GitHub.
 */
class RecordedGitHubIssue(
    private val label: String = "gh-ready",
) : AutoCloseable {
    private val recorded: Path =
        Path.of(System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"))

    private val issue: JsonObject =
        Json
            .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("number").jsonPrimitive.content == "26" }

    private val labels: MutableSet<String> = Collections.synchronizedSet(linkedSetOf(label))

    /** A write the server got: its method, its path and its body. */
    data class Write(
        val method: String,
        val path: String,
        val body: String,
    )

    /** Every write the server got, in order. */
    val writes: MutableList<Write> = Collections.synchronizedList(mutableListOf())

    private val repo = "/api/repos/octocat/Hello-World"

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/") { exchange ->
                val path = exchange.requestURI.rawPath
                val method = exchange.requestMethod
                val (status, body) =
                    if (method == "GET") {
                        answer(path, exchange.requestURI.rawQuery.orEmpty())
                    } else {
                        val sent = exchange.requestBody.readBytes().decodeToString()
                        writes += Write(method, path, sent)
                        answerWrite(method, path, sent)
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

    /** The `api_url` of the tracker's `provider`. */
    val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    /** The `trackers:` entry of a GitHub tracker `gh` on this server, its token read from `$GITHUB_TOKEN`. */
    val trackersYaml: String
        get() =
            "trackers:\n" +
                "  - id: gh\n" +
                "    kind: github\n" +
                "    provider:\n" +
                "      repo: octocat/Hello-World\n" +
                "      token: \$GITHUB_TOKEN\n" +
                "      api_url: $apiUrl\n" +
                "    required_labels: [$label]\n"

    /** The paths of the writes the server got, with their method. */
    val writePaths: List<String> get() = writes.toList().map { "${it.method} ${it.path}" }

    private fun answer(
        path: String,
        query: String,
    ): Pair<Int, String> =
        when {
            path == "$repo/issues" && "state=open" in query -> 200 to JsonArray(listOf(labelled())).toString()
            path == "$repo/issues" -> 200 to JsonArray(emptyList()).toString()
            path == "$repo/issues/26" -> 200 to labelled().toString()
            path == "$repo/issues/26/comments" -> 200 to JsonArray(emptyList()).toString()
            path == repo -> 200 to recorded.resolve("repository.json").readText()
            else -> 404 to recorded.resolve("issue-not-found.json").readText()
        }

    /** Issue 26 with its current labels, no comment, by the repository's owner, so its own labels decide. */
    private fun labelled(): JsonObject =
        JsonObject(
            issue +
                mapOf(
                    "labels" to JsonArray(labels.toList().map { JsonObject(mapOf("name" to JsonPrimitive(it))) }),
                    "comments" to JsonPrimitive(0),
                    "author_association" to JsonPrimitive("OWNER"),
                ),
        )

    /** The recorded answer to a write on issue 26, the labels changed as asked; any other issue is not found. */
    private fun answerWrite(
        method: String,
        path: String,
        body: String,
    ): Pair<Int, String> {
        val name =
            when {
                method == "POST" && path == "$repo/issues/26/labels" -> {
                    Json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("labels")
                        .jsonArray
                        .forEach { labels += it.jsonPrimitive.content }
                    "add-labels-26"
                }
                method == "DELETE" && path.startsWith("$repo/issues/26/labels/") -> {
                    labels -= path.substringAfterLast('/')
                    "remove-label-26"
                }
                method == "POST" && path == "$repo/issues/26/comments" -> "create-comment-26"
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

    override fun close() {
        server.stop(0)
    }
}
