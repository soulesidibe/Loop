package loop.tracker.github.real

import kotlinx.coroutines.runBlocking
import loop.tracker.github.FakeGitHub
import loop.tracker.github.FakeGitHub.Companion.issue
import loop.tracker.github.FakeGitHub.Companion.issueUrl
import loop.tracker.github.FakeGitHub.Companion.json
import loop.tracker.github.FakeGitHub.Companion.repoUrl
import loop.tracker.github.FakeGitHub.Companion.response
import loop.tracker.github.GitHubTrackerSettings
import loop.tracker.github.GitHubTransport
import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * bl-150: the cleanup of the GitHub real smoke test against a fake GitHub, for the writes whose response is lost after
 * GitHub applied them, and for a close read that lags.
 */
class GitHubRealRunTest {
    private val id = "loop-real-0123abcd"
    private val ready = "$id-ready"
    private val repo = repoUrl()
    private val lookup = "$repo/issues?labels=$id&state=all&per_page=100"
    private val github =
        FakeGitHub().apply {
            on("POST", "$repo/labels", response(201, "{}"))
            on("DELETE", "$repo/labels/$id", response(204, ""))
            on("DELETE", "$repo/labels/$ready", response(204, ""))
        }
    private val run =
        GitHubRealRun(
            id,
            GitHubTrackerSettings(
                owner = "octocat",
                repo = "Hello-World",
                token = "t",
                apiUrl = URI(FakeGitHub.API),
                scopeLabels = listOf(id),
            ),
            github,
            pause = Duration.ZERO,
        )

    private fun lost(): Nothing = throw IOException("request timed out")

    private fun exerciseFails() {
        val failure = runCatching { runBlocking { run.exercise(ready) } }.exceptionOrNull()
        assertTrue(failure != null, "the exercise should fail")
    }

    @Test
    fun `bl-150 an issue created with a lost response is found by its label and closed before the labels go`() {
        github.writeFallback =
            { method, url, _ -> if (method == "POST" && url.toString() == "$repo/issues") lost() else response(404, "{}") }
        // GitHub's list lags the create once, then shows the orphan.
        github.on(lookup, json(emptyList()), json(listOf(issue(42, state = "open", labels = listOf(id, ready)))))
        github.on("PATCH", issueUrl(42), response(200, issue(42, state = "closed").toString()))
        exerciseFails()

        val failures = runBlocking { run.cleanUp() }

        assertEquals(emptyList(), failures, run.steps.joinToString("\n"))
        val writes = github.writes.map { "${it.method} ${it.url}" }
        val patch = writes.indexOf("PATCH ${issueUrl(42)}")
        assertTrue(patch >= 0, "the orphan is closed: $writes")
        assertTrue(writes.indexOf("DELETE $repo/labels/$id") > patch, "the labels go after the close: $writes")
        assertTrue(writes.indexOf("DELETE $repo/labels/$ready") > patch, "the labels go after the close: $writes")
        assertTrue(run.steps.any { "#42" in it }, run.steps.joinToString("\n"))
    }

    @Test
    fun `bl-150 a closed issue found by its label is not closed again`() {
        github.writeFallback =
            { method, url, _ -> if (method == "POST" && url.toString() == "$repo/issues") lost() else response(404, "{}") }
        github.on(lookup, json(listOf(issue(42, state = "closed", labels = listOf(id, ready)))))
        exerciseFails()

        val failures = runBlocking { run.cleanUp() }

        assertEquals(emptyList(), failures, run.steps.joinToString("\n"))
        assertTrue(github.writes.none { it.method == "PATCH" }, "${github.writes}")
    }

    @Test
    fun `bl-150 a failed lookup of the orphan keeps the labels, so the issue can still be found by hand`() {
        github.writeFallback =
            { method, url, _ -> if (method == "POST" && url.toString() == "$repo/issues") lost() else response(404, "{}") }
        github.on(lookup, response(500, """{"message":"boom"}"""))
        exerciseFails()

        val failures = runBlocking { run.cleanUp() }

        assertEquals(1, failures.size, run.steps.joinToString("\n"))
        assertTrue(github.writes.none { it.method == "DELETE" }, "${github.writes}")
        val left = run.leftBehind()
        assertTrue("labelled $id" in left && ready in left, left)
    }

    @Test
    fun `bl-150 a label created with a lost response is still deleted`() {
        // The first label POST reaches GitHub, which creates the label, but its response is lost.
        var posts = 0
        val transport =
            GitHubTransport { method, url, headers, body ->
                val response = github.send(method, url, headers, body)
                if (method == "POST" && url.toString() == "$repo/labels" && posts++ == 0) lost()
                response
            }
        val run =
            GitHubRealRun(
                id,
                GitHubTrackerSettings(
                    owner = "octocat",
                    repo = "Hello-World",
                    token = "t",
                    apiUrl = URI(FakeGitHub.API),
                    scopeLabels = listOf(id),
                ),
                transport,
                pause = Duration.ZERO,
            )
        assertTrue(runCatching { runBlocking { run.exercise(ready) } }.isFailure)

        val failures = runBlocking { run.cleanUp() }

        assertEquals(emptyList(), failures, run.steps.joinToString("\n"))
        assertTrue(github.writes.any { it.method == "DELETE" && it.url == "$repo/labels/$id" }, "${github.writes}")
        assertTrue(github.requests.none { it.method == "GET" }, "no issue was asked for, so none is looked up: ${github.requests}")
    }

    @Test
    fun `bl-150 the cleanup reads the closed state from the close response, not from a read that can lag`() {
        github.on("POST", "$repo/issues", response(201, issue(42, labels = listOf(id, ready)).toString()))
        // The tracker's first read fails, so the exercise stops with the issue open.
        github.fallback = { response(500, """{"message":"boom"}""") }
        github.on("PATCH", issueUrl(42), response(200, issue(42, state = "closed").toString()))
        // A lagging read would still say open.
        github.on(issueUrl(42), response(200, issue(42, state = "open").toString()))
        exerciseFails()
        val before = github.requests.size

        val failures = runBlocking { run.cleanUp() }

        assertEquals(emptyList(), failures, run.steps.joinToString("\n"))
        assertTrue(github.requests.drop(before).none { it.method == "GET" }, "${github.requests.drop(before)}")
    }

    @Test
    fun `bl-150 a close response that does not read closed fails the cleanup`() {
        github.on("POST", "$repo/issues", response(201, issue(42, labels = listOf(id, ready)).toString()))
        github.fallback = { response(500, """{"message":"boom"}""") }
        github.on("PATCH", issueUrl(42), response(200, issue(42, state = "open").toString()))
        exerciseFails()

        val failures = runBlocking { run.cleanUp() }

        assertEquals(1, failures.size, run.steps.joinToString("\n"))
        assertTrue("#42 is open" in failures.single().message.orEmpty(), "${failures.single()}")
        assertTrue("#42" in run.leftBehind(), run.leftBehind())
    }
}
