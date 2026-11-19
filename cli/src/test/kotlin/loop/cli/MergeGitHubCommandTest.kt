package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.verify.WorkspaceFingerprint
import loop.core.waiting.WaitKeys
import loop.store.SqliteEventStore
import loop.tracker.github.GitSmartHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * m2-26, end to end on the `github` tracker: a merge wait whose pull request GitHub already merged at the stored head,
 * and whose issue `Closes #n` already closed, is delivered by the next `loop run --once` with no error and no human
 * touch. The issue is already in `on_delivered.move_to`, so nothing is written to it; the workspace is removed and the
 * local branch stays. bl-32: an agent that deletes a test and moves local `main` to its head is still refuted, since
 * `diff_policy` is judged against the base fetched host side, and nothing is merged.
 */
class MergeGitHubCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m226MergeToken0123456789abcdefghijk"

    private val env =
        mapOf(
            "GITHUB_TOKEN" to token,
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
        )

    /** The head of the merged pull request, set once the branch exists. */
    @Volatile
    private var mergedHead: String = ""

    /** Every request the server got: its method, path and query. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The bare repository behind `/acme/widgets.git` of the server, the remote the base is fetched from (bl-32). */
    private val bare: Path get() = root.resolve("remote.git")

    private val gitServer by lazy { GitSmartHttp(bare, password = token) }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/acme/widgets.git/") { exchange -> gitServer.handle(exchange) }
            createContext("/api/") { exchange ->
                val path = exchange.requestURI.rawPath
                val query = exchange.requestURI.rawQuery.orEmpty()
                requests += "${exchange.requestMethod} $path?$query"
                val (status, body) = if (exchange.requestMethod == "GET") answer(path, query) else 404 to """{"message":"Not Found"}"""
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.responseHeaders.add("x-ratelimit-remaining", "4999")
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

    /** No open issue, issue 7 closed by `Closes #7`, and pull request 1 of `loop/widgets-7` merged at [mergedHead]. */
    private fun answer(
        path: String,
        query: String,
    ): Pair<Int, String> {
        val repo = "/api/repos/acme/widgets"
        return when {
            path == "$repo/issues" -> 200 to "[]"
            path == "$repo/issues/7" ->
                200 to
                    """{"number":7,"title":"Add hello","state":"closed","labels":[{"name":"ready-for-agent"}],""" +
                    """"body":"Say hello.","comments":0,"created_at":"2026-11-14T08:00:00Z","node_id":"I_7"}"""
            path == "$repo/pulls" && "head=acme%3Aloop%2Fwidgets-7" in query ->
                200 to
                    """[{"number":1,"html_url":"https://github.com/acme/widgets/pull/1","state":"closed",""" +
                    """"merged_at":"2026-11-14T10:00:00Z","head":{"ref":"loop/widgets-7","sha":"$mergedHead"},""" +
                    """"base":{"ref":"main"}}]"""
            path == repo -> 200 to """{"full_name":"acme/widgets"}"""
            else -> 404 to """{"message":"Not Found"}"""
        }
    }

    private val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    private fun writeWorkflow() {
        fixture.workflow.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: github\n" +
                "  provider:\n" +
                "    repo: acme/widgets\n" +
                "    token: \$GITHUB_TOKEN\n" +
                "    api_url: \"$apiUrl\"\n" +
                "  required_labels: [ready-for-agent]\n" +
                "agent:\n" +
                "  max_turns: 1\n" +
                "workspace:\n" +
                "  root: ./workspaces\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: \"${fixture.agentScript.toAbsolutePath()}\"\n" +
                "kinds:\n" +
                "  code:\n" +
                "    delivery:\n" +
                "      policy: human\n" +
                "      pull_request:\n" +
                "        merge: { method: squash, ci: none }\n" +
                "      on_delivered: { move_to: closed }\n" +
                "    verify:\n" +
                "      checks:\n" +
                "        - { type: command, run: \"test -f hello.txt\" }\n" +
                "polling:\n" +
                "  interval_ms: 1000\n" +
                "---\n" +
                "Work on {{ issue.identifier }}: {{ issue.title }}\n",
        )
    }

    @Test
    fun `m2-26 an issue Closes n already closed is delivered with no error and no human touch`() {
        writeWorkflow()
        val repo = fixture.useGitWorktrees()
        repo.git("init", "--quiet", "--bare", root.resolve("remote.git").toString(), cwd = root)
        repo.git("remote", "add", "origin", root.resolve("remote.git").toString())
        val workspace = fixture.workspace("widgets-7")
        repo.git("worktree", "add", "--quiet", "-b", "loop/widgets-7", workspace.toString())
        workspace.resolve("hello.txt").writeText("hello\n")
        repo.git("add", "hello.txt", cwd = workspace)
        repo.commit("work on widgets-7", cwd = workspace)
        mergedHead = repo.git("rev-parse", "HEAD", cwd = workspace)
        val clean = "git:$mergedHead:${WorkspaceFingerprint.EMPTY_HASH}:${WorkspaceFingerprint.EMPTY_HASH}"
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            val wait =
                WaitingIssue(
                    ProjectId(fixture.projectId()),
                    "7",
                    "widgets-7",
                    WaitReason.Merge,
                    AttemptId("a-1"),
                    null,
                    Clock.System.now(),
                    mapOf(
                        WaitKeys.TO to "review",
                        WaitKeys.FINGERPRINT to clean,
                        WaitingIssue.APPROVED_BY to "soule",
                        WaitingIssue.APPROVED_AT to "2026-11-14T09:00:00Z",
                        WaitKeys.PULL_REQUEST to "https://github.com/acme/widgets/pull/1",
                        WaitKeys.PULL_REQUEST_NUMBER to "1",
                        WaitKeys.PHASE to WaitKeys.PHASE_MERGE,
                        WaitKeys.BASE_SHA to mergedHead,
                        WaitKeys.VERIFIED_FINGERPRINT to clean,
                        WaitKeys.MERGED_HEAD to mergedHead,
                        WaitKeys.ROUND to "1",
                    ),
                )
            runBlocking { store.startWaiting(wait) }.getOrThrow()
        }

        val run = fixture.loop("run", fixture.workflow.toString(), "--once", env = env)

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM waiting_issues"), run.toString())
        assertEquals(emptyList(), fixture.query("SELECT data FROM events WHERE kind = 'human_touch'"))
        assertEquals(emptyList(), fixture.query("SELECT data FROM events WHERE kind = 'delivery_failed'"))
        assertTrue(requests.none { !it.startsWith("GET ") }, "nothing is written, the issue is already closed: $requests")
        assertTrue(requests.any { it.startsWith("GET /api/repos/acme/widgets/pulls?") }, "the pull request is looked up first")
        assertFalse(workspace.exists(), "the workspace is removed")
        assertTrue(repo.hasBranch("loop/widgets-7"), "the local branch stays")
        assertFalse(run.stdout.contains(token) || run.stderr.contains(token))
    }

    private val playground: Path =
        Path.of(
            checkNotNull(javaClass.getResource("/playground/deleted-test")) { "no playground in the test resources" }.toURI(),
        )

    @Test
    fun `bl-32 a patch that deletes a test and moves local main to HEAD is refuted against the fetched base, nothing merged`() {
        val project = playground.resolve("project")
        Files.walk(project).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val target = fixture.projectDir.resolve(project.relativize(file).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.copy(playground.resolve("issue.md"), fixture.issueFile("pg-1"))
        val patch = root.resolve("shortcut.patch")
        Files.copy(playground.resolve("shortcut.patch"), patch)
        // The scripted shortcut: delete the failing test, commit, then move local main to the branch head, which
        // empties a diff against main. `git -C .` gets past a deny rule that matches `git update-ref` as a prefix.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |git apply "$patch"
            |git -c user.name=Agent -c user.email=agent@example.com commit -qam "make the calculator tests pass"
            |git -C . update-ref refs/heads/main HEAD
            |loop tool move review
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery:
                |      policy: human
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}GITHUB_TOKEN
                |        api_url: "$apiUrl"
                |        merge: { method: merge, ci: none }
                |      on_delivered: { move_to: done }
                |    verify:
                |      checks:
                |        - { type: command, run: "sh run-tests.sh" }
                |      guards:
                |        deny: [deleted_tests, added_skips, weaker_assertions]
                """.trimMargin(),
        )
        val repo = fixture.useGitWorktrees()
        val fork = repo.git("rev-parse", "main")
        repo.git("init", "--quiet", "--bare", bare.toString(), cwd = root)
        repo.git("--git-dir", bare.toString(), "fetch", "--quiet", repo.dir.toString(), "main:main", cwd = root)
        repo.git("remote", "add", "origin", bare.toString())

        val run = fixture.loop("run", fixture.workflow.toString(), "--once", env = env)

        assertEquals(0, run.exitCode, run.toString())
        val head = repo.git("rev-parse", "refs/heads/loop/pg-1")
        assertEquals(head, repo.git("rev-parse", "main"), "the agent moved local main to its head")
        // One fetch before the attempt's hooks (bl-59), one for the verification's diff_policy (bl-32), both at the fork.
        val fetched = fixture.query("SELECT data FROM events WHERE kind = 'base_fetched'").map { it["data"].orEmpty() }
        assertEquals(2, fetched.size, fetched.toString())
        fetched.forEach { assertContains(it, "\"sha\":\"$fork\"") }
        assertEquals(emptyList(), fixture.query("SELECT data FROM events WHERE kind = 'diff_base_unpinned'"))
        val verdict = fixture.query("SELECT data, attempt_id FROM events WHERE kind = 'verdict'").single()
        assertContains(verdict["data"].orEmpty(), "\"verdict\":\"refuted\"")
        val evidence = fixture.loop("show", verdict["attempt_id"]!!, "--evidence", "--workflow", fixture.workflow.toString(), env = env)
        assertEquals(0, evidence.exitCode, evidence.toString())
        assertContains(evidence.stdout, "diff_policy base=$fork deny=deleted_tests,added_skips,weaker_assertions")
        assertContains(evidence.stdout, "deleted_tests: removed test `divides by zero` from src/test/kotlin/calc/CalcTest.kt")
        // The pull request is never opened, so it is never merged, and the issue goes back to its agent.
        assertTrue(requests.none { "/pulls" in it }, "no pull request is opened or merged: $requests")
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM waiting_issues"))
        val attempt = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").single()["data"].orEmpty()
        assertContains(attempt, "\"reason\":\"refuted\"")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'retry_scheduled'").size, "the issue goes back to its agent")
        assertEquals("todo", fixture.stateOf("pg-1"))
        assertFalse(run.stdout.contains(token) || run.stderr.contains(token))
    }
}
