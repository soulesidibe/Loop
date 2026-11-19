package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.core.workflow.WorkflowLoader
import loop.tracker.github.GitSmartHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * m2-25, end to end with the command runner and the worktree hooks: with `delivery.pull_request` a verified result is
 * pushed to `loop/<key>` of a temporary bare repository, served over git's smart HTTP protocol by the same loopback
 * server as the fake GitHub API (`http://127.0.0.1:<port>/acme/widgets.git`), a pull request is opened there, its URL
 * is commented on the issue and the issue moves to its handoff state. Every git process runs in the test's temporary
 * folders, and the push goes to the loopback server only, so nothing ever reaches a real remote.
 */
class PullRequestDeliveryCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m225DeliveryToken0123456789abcdefgh"

    /** The env of every run: the delivery token, and git with no user or system config and no prompt. */
    private val env =
        mapOf(
            "LOOP_PR_TOKEN" to token,
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
            "USER" to "soule",
        )

    /** Every request the server got: its method, path and decoded query, and its Authorization header. */
    private val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** The JSON body of every write the server got, by its method and path. */
    private val writes: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())

    /** The pull requests of the fake repository, by head (`acme:loop/<key>`), all open. */
    private val open: MutableMap<String, JsonObject> = Collections.synchronizedMap(mutableMapOf())

    /** The bare repository behind `/acme/widgets.git` of the server, created by [setUp]. */
    private val bare: Path get() = root.resolve("remote.git")

    private val gitServer by lazy { GitSmartHttp(bare, password = token) }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/acme/widgets.git/") { exchange -> gitServer.handle(exchange) }
            createContext("/api/") { exchange ->
                val path = exchange.requestURI.path
                val query = exchange.requestURI.query.orEmpty()
                requests += "${exchange.requestMethod} $path?$query" to exchange.requestHeaders.getFirst("Authorization")
                val written = exchange.requestBody.readBytes().decodeToString()
                val (status, body) = answer(exchange.requestMethod, path, query, written)
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

    private fun answer(
        method: String,
        path: String,
        query: String,
        written: String,
    ): Pair<Int, String> {
        val pulls = "/api/repos/acme/widgets/pulls"
        if (method != "GET") writes += "$method $path" to Json.parseToJsonElement(written).jsonObject
        return when {
            method == "GET" && path == pulls -> {
                val head = query.split("&").firstOrNull { it.startsWith("head=") }?.removePrefix("head=")
                200 to JsonArray(listOfNotNull(open[head])).toString()
            }
            method == "POST" && path == pulls -> {
                val number = open.size + 1
                val head =
                    Json
                        .parseToJsonElement(written)
                        .jsonObject
                        .getValue("head")
                        .jsonPrimitive.content
                val pull = pullRequest(number, head)
                open["acme:$head"] = pull
                201 to pull.toString()
            }
            method == "PATCH" && path.startsWith("$pulls/") -> {
                val number = path.substringAfterLast('/').toInt()
                200 to open.values.single { it.getValue("number").jsonPrimitive.content == number.toString() }.toString()
            }
            // m2-26: the base tip and the merge, the base read from the bare repository behind the server.
            method == "GET" && path == "/api/repos/acme/widgets/git/ref/heads/main" -> {
                val sha = bareMain() ?: return 404 to """{"message":"Not Found"}"""
                200 to """{"ref":"refs/heads/main","object":{"sha":"$sha","type":"commit"}}"""
            }
            method == "PUT" && path.startsWith("$pulls/") && path.endsWith("/merge") -> {
                val number = path.removeSuffix("/merge").substringAfterLast('/')
                val (head, pull) =
                    open.entries.single {
                        it.value
                            .getValue("number")
                            .jsonPrimitive.content == number
                    }
                val sha =
                    Json
                        .parseToJsonElement(written)
                        .jsonObject
                        .getValue("sha")
                        .jsonPrimitive.content
                val merged =
                    JsonObject(
                        pull +
                            mapOf(
                                "state" to JsonPrimitive("closed"),
                                "merged_at" to JsonPrimitive("2026-11-14T10:00:00Z"),
                                "head" to
                                    JsonObject(
                                        mapOf("ref" to JsonPrimitive(head.removePrefix("acme:")), "sha" to JsonPrimitive(sha)),
                                    ),
                            ),
                    )
                open[head] = merged
                200 to """{"sha":"${"ab".repeat(20)}","merged":true,"message":"Pull Request successfully merged"}"""
            }
            else -> 404 to """{"message":"Not Found"}"""
        }
    }

    private fun pullRequest(
        number: Int,
        head: String,
    ): JsonObject =
        JsonObject(
            mapOf(
                "number" to JsonPrimitive(number),
                "html_url" to JsonPrimitive("https://github.com/acme/widgets/pull/$number"),
                "state" to JsonPrimitive("open"),
                "head" to JsonObject(mapOf("ref" to JsonPrimitive(head))),
                "base" to JsonObject(mapOf("ref" to JsonPrimitive("main"))),
            ),
        )

    private val apiUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

    /** Where the agent leaves what it could see, outside the project. */
    private val seen: Path by lazy { Files.createDirectories(root.resolve("seen")) }

    /**
     * An agent that commits a change to hello.txt on its branch, keeps a copy of its env and of its tool config
     * snapshot outside the project, then submits a result file whose summary must never reach the pull request.
     */
    private fun writeAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ATTEMPT_ID" >> hello.txt
            |git add hello.txt
            |git -c user.name="Loop agent" -c user.email=agent@example.com commit -q -m "work on ${'$'}LOOP_ISSUE_IDENTIFIER"
            |env > "$seen/env-${'$'}LOOP_ATTEMPT_ID.txt"
            |cp "${'$'}LOOP_TOOL_CONFIG" "$seen/tool-config-${'$'}LOOP_ATTEMPT_ID.json"
            |mkdir -p .loop
            |echo '{ "status": "done", "summary": "AGENT WORDS, trust this pull request" }' > .loop/result.json
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** Loop mode with pull request delivery under [policy], then the worktree hooks; [remote] adds the bare remote. */
    private fun setUp(
        policy: String,
        remote: Boolean = true,
        merge: Boolean = false,
    ): TestRepo {
        fixture.issue("t-1")
        writeAgent()
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery:
                |      policy: $policy
                |      auto_merge: { enabled: ${policy == "auto"} }
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}LOOP_PR_TOKEN
                |        api_url: "$apiUrl"${if (merge) MERGE_CONFIG else ""}
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )
        val repo = fixture.useGitWorktrees()
        repo.git("init", "--quiet", "--bare", bare.toString(), cwd = root)
        // Only the preflight reads the remote; the push goes to the explicit URL of acme/widgets on the server.
        if (remote) repo.git("remote", "add", "origin", bare.toString())
        return repo
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once", env = env).also {
            assertEquals(0, it.exitCode, it.toString())
            results += it
        }

    private val results = mutableListOf<LoopResult>()

    private fun approve() {
        val approved = fixture.loop("approve", "t-1", "--workflow", fixture.workflow.toString(), env = env)
        assertEquals(0, approved.exitCode, approved.toString())
        results += approved
    }

    /** The commit of `loop/t-1` on the bare remote, null when the branch is not there. */
    private fun remoteBranch(repo: TestRepo): String? =
        repo
            .gitResult("--git-dir", bare.toString(), "rev-parse", "--verify", "-q", "refs/heads/loop/t-1", cwd = root)
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()

    private fun events(kind: String): List<String> =
        fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map {
            it["data"].orEmpty()
        }

    private fun posts(): List<JsonObject> = writes.filter { it.first == "POST /api/repos/acme/widgets/pulls" }.map { it.second }

    private fun patches(): List<JsonObject> = writes.filter { it.first.startsWith("PATCH ") }.map { it.second }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `under human, once approved, the verified commit is pushed, one pull request opened, its URL commented, the issue moved`() {
        val repo = setUp("human")

        runOnce()

        assertEquals("todo", fixture.stateOf("t-1"), "it waits for approval")
        assertEquals(null, remoteBranch(repo), "nothing is pushed before the approval")
        assertEquals(emptyList(), posts())

        approve()
        // A new process: the delivery after a restart builds the full body from the wait.
        runOnce()

        val verified = repo.git("rev-parse", "loop/t-1")
        assertEquals(verified, remoteBranch(repo))
        assertTrue(
            gitServer.requests.any { it.first.startsWith("POST /acme/widgets.git/git-receive-pack") && it.second != null },
            gitServer.requests.toString(),
        )
        val post = posts().single()
        assertEquals("t-1: Issue t-1", post.text("title"))
        assertEquals("loop/t-1", post.text("head"))
        assertEquals("main", post.text("base"))
        val body = post.text("body")
        assertContains(body, "Verdict: verified")
        assertContains(body, Regex("""Fingerprint: git:$verified:e3b0c44298fc1c14:e3b0c44298fc1c14"""))
        assertContains(body, Regex("""- check 0, test -f hello\.txt: passed with exit code 0 in \S+, evidence \S+\.evidence-1\.log"""))
        assertContains(body, "Judgment calls left to a human: 0")
        assertTrue(body.endsWith("Refs t-1"), body)
        assertFalse("AGENT WORDS" in body, "nothing from the agent's result file")
        assertTrue(requests.all { it.second == "Bearer $token" }, requests.toString())
        assertContains(
            fixture.issueFile("t-1").readText(),
            "Loop opened pull request #1 for this issue: https://github.com/acme/widgets/pull/1",
        )
        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(1, events("branch_pushed").size)
        assertContains(events("branch_pushed").single(), "\"sha\":\"$verified\"")
        assertContains(events("pull_request_opened").single(), "\"number\":\"1\"")
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM waiting_issues"))

        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString(), env = env).also { results += it }
        assertTrue(trace.stdout.lines().any { "branch_pushed" in it && "branch=loop/t-1" in it }, trace.stdout)
        assertTrue(trace.stdout.lines().any { "pull_request_opened" in it && "number=1" in it }, trace.stdout)
        val log = fixture.loop("log", fixture.workflow.toString(), env = env).also { results += it }
        assertContains(log.stdout, "opened pull request #1, https://github.com/acme/widgets/pull/1")

        // A second delivery of the same issue updates the open pull request: a human sends it back, the agent works again.
        val file = fixture.issueFile("t-1")
        file.writeText(file.readText().replace("state: review", "state: todo"))
        runOnce()
        approve()
        runOnce()

        val second = repo.git("rev-parse", "loop/t-1")
        assertNotEquals(verified, second)
        assertEquals(second, remoteBranch(repo), "the push is a fast forward of the same branch")
        assertEquals(1, posts().size, "no second pull request")
        val patch = patches().single()
        assertContains(patch.text("body"), "Fingerprint: git:$second:")
        assertContains(fixture.issueFile("t-1").readText(), "Loop updated pull request #1 for this issue")
        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(1, events("pull_request_updated").size)

        assertTokenAbsent(repo)
    }

    @Test
    fun `under auto the verified commit is delivered at once, in the same run`() {
        val repo = setUp("auto")

        runOnce()

        assertEquals(repo.git("rev-parse", "loop/t-1"), remoteBranch(repo))
        assertEquals(1, posts().size)
        assertContains(fixture.issueFile("t-1").readText(), "Loop opened pull request #1 for this issue")
        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM waiting_issues"))
        assertTokenAbsent(repo)
    }

    @Test
    fun `a missing remote fails the preflight and nothing is dispatched`() {
        setUp("auto", remote = false)

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = env)

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "kinds.code.delivery.pull_request.remote is \"origin\", which is not a remote of the repository at")
        assertEquals("todo", fixture.stateOf("t-1"))
        assertEquals(0L, Files.list(seen).use { it.count() }, "the agent never ran")
    }

    @Test
    fun `an unset token variable fails the preflight, naming the variable only`() {
        setUp("auto")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = env + ("LOOP_PR_TOKEN" to null))

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "kinds.code.delivery.pull_request.token uses \$LOOP_PR_TOKEN, which is not set")
        assertEquals("todo", fixture.stateOf("t-1"))
    }

    @Test
    fun `the remote check of the preflight runs git with a minimal environment, without the token or the Anthropic keys`() {
        setUp("auto")
        val seenEnv = root.resolve("remote-check-env.txt")
        val git = root.resolve("bin").also { Files.createDirectories(it) }.resolve("git")
        git.writeText("#!/bin/sh\nenv > '$seenEnv'\nexec git \"$@\"\n")
        Files.setPosixFilePermissions(git, PosixFilePermissions.fromString("rwxr-xr-x"))
        val host =
            mapOf(
                "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "HOME" to root.resolve("home").toString(),
                "LANG" to "C.UTF-8",
                "LOOP_PR_TOKEN" to token,
                "ANTHROPIC_API_KEY" to "sk-ant-m225-api-key-0123456789",
                "ANTHROPIC_AUTH_TOKEN" to "sk-ant-m225-auth-token-0123456789",
                "GIT_DIR" to root.resolve("elsewhere").toString(),
            )
        val workflow = WorkflowLoader(env = host, home = root).load(fixture.workflow).getOrThrow()

        assertEquals(null, deliveryProblem(workflow, host, git.toString()))

        val seen = seenEnv.readText()
        for (absent in listOf(token, "LOOP_PR_TOKEN", "sk-ant-m225", "ANTHROPIC", "GIT_DIR")) {
            assertFalse(absent in seen, "$absent reached the git of the remote check:\n$seen")
        }
        assertContains(seen, "LANG=C.UTF-8")
        assertContains(seen, "GIT_TERMINAL_PROMPT=0")
    }

    /** The commit of `main` on the bare remote, null when it has none. */
    private fun bareMain(): String? {
        val process =
            ProcessBuilder("git", "--git-dir", bare.toString(), "rev-parse", "--verify", "-q", "refs/heads/main")
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                }.start()
        val out =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        return out.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
    }

    @Test
    fun `m2-26 with merge, once approved, the base is fetched, merged and rechecked, the pull request merged and the issue done`() {
        val repo = setUp("human", merge = true)
        val forkPoint = repo.git("rev-parse", "HEAD")

        // The remote has no main yet, so the attempt's own fetch fails and the branch starts from local main (bl-59).
        runOnce()
        // The remote's main is now ahead of the project's: someone else's commit landed there since the branch started.
        val remoteMain = advanceRemoteMain(repo)
        assertEquals(forkPoint, repo.git("rev-parse", "main"))
        approve()
        // A new process: it delivers, waits as Waiting(merge), then runs the merge check and merges in the same tick.
        runOnce()

        val merged = repo.git("rev-parse", "refs/heads/loop/t-1")
        val parents = repo.git("log", "-1", "--format=%P", merged).split(' ')
        assertEquals(2, parents.size, "a merge commit")
        assertEquals(remoteMain, parents[1])
        assertEquals(remoteMain, repo.git("rev-parse", "refs/loop/base/main"))
        assertEquals(forkPoint, repo.git("rev-parse", "main"), "local main is never used or moved")
        assertEquals(merged, remoteBranch(repo), "the merged head is pushed")
        assertEquals(1, posts().size)
        val merge = writes.single { it.first == "PUT /api/repos/acme/widgets/pulls/1/merge" }.second
        assertEquals(merged, merge.text("sha"))
        assertEquals("merge", merge.text("merge_method"))
        assertEquals("done", fixture.stateOf("t-1"))
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM waiting_issues"))
        assertContains(events("base_fetched").single(), "\"sha\":\"$remoteMain\"")
        assertContains(events("merge_check_finished").single(), "\"outcome\":\"merged\"")
        assertContains(events("pull_request_merged").single(), "\"sha\":\"$merged\"")
        assertEquals(listOf("{\"touch\":\"approve\"}"), events("human_touch"), "the approval is the only human touch")
        assertFalse(fixture.workspace("t-1").exists(), "the workspace is removed, the local branch stays")

        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString(), env = env).also { results += it }
        assertTrue(trace.stdout.lines().any { "merge_check_finished" in it && "outcome=merged" in it }, trace.stdout)
        assertTrue(trace.stdout.lines().any { "pull_request_merged" in it && "number=1" in it }, trace.stdout)
        val log = fixture.loop("log", fixture.workflow.toString(), env = env).also { results += it }
        assertContains(log.stdout, "merged pull request #1 at ${merged.take(12)} with merge")
        assertContains(log.stdout, "fetched main at ${remoteMain.take(12)}")

        assertTokenAbsent(repo)
    }

    /** Puts a commit of someone else on `main` of the bare remote, so the remote's `main` is ahead of the project's; that commit. */
    private fun advanceRemoteMain(repo: TestRepo): String {
        repo.git("checkout", "--quiet", "-b", "upstream")
        repo.dir.resolve("other.txt").writeText("someone else's work\n")
        repo.git("add", "other.txt")
        repo.git("-c", "user.name=Other", "-c", "user.email=other@example.com", "commit", "--quiet", "-m", "other work")
        val remoteMain = repo.git("rev-parse", "HEAD")
        repo.git("checkout", "--quiet", "main")
        repo.git("--git-dir", bare.toString(), "fetch", "--quiet", repo.dir.toString(), "upstream:main", cwd = root)
        repo.git("branch", "--quiet", "-D", "upstream")
        return remoteMain
    }

    /** Every local branch of the project and its commit, one per line, except Loop's own `loop/<key>` branches. */
    private fun operatorBranches(repo: TestRepo): List<String> =
        repo.git("for-each-ref", "--format=%(refname) %(objectname)", "refs/heads").lines().filterNot { it.startsWith("refs/heads/loop/") }

    @Test
    fun `bl-59 a new branch starts from the base Loop fetched before the hooks, and no local branch of the project moves`() {
        val repo = setUp("human")
        val forkPoint = repo.git("rev-parse", "HEAD")
        val remoteMain = advanceRemoteMain(repo)
        repo.git("branch", "--quiet", "feature")
        val before = operatorBranches(repo)

        runOnce()

        val branch = repo.git("rev-parse", "refs/heads/loop/t-1")
        assertEquals(remoteMain, repo.git("log", "-1", "--format=%P", branch), "the agent's commit sits on the fetched base")
        assertEquals(remoteMain, repo.git("rev-parse", "refs/loop/base/main"))
        assertEquals(forkPoint, repo.git("rev-parse", "main"), "local main is never moved")
        assertEquals(before, operatorBranches(repo), "no local branch of the project moved")
        assertContains(events("base_fetched").single(), "\"sha\":\"$remoteMain\"")
        val agentEnvFile = Files.list(seen).use { files -> files.filter { it.name.startsWith("env-") }.toList() }.single()
        val agentEnv = agentEnvFile.readText()
        assertFalse("LOOP_BASE_REF" in agentEnv, "only the hooks get LOOP_BASE_REF")
        assertTokenAbsent(repo)
    }

    @Test
    fun `bl-59 a failed fetch starts the branch from the last fetched base ref with a warning, the attempt still runs`() {
        val repo = setUp("human")
        val earlier = repo.git("rev-parse", "HEAD")
        repo.git("update-ref", "refs/loop/base/main", earlier)
        // The remote has no main to fetch, so the fetch fails.

        runOnce()

        val branch = repo.git("rev-parse", "refs/heads/loop/t-1")
        assertEquals(earlier, repo.git("log", "-1", "--format=%P", branch))
        val warning = events("base_fetch_failed").single()
        assertContains(warning, "\"sha\":\"$earlier\"")
        assertEquals(emptyList(), events("base_fetched"))
        assertEquals("todo", fixture.stateOf("t-1"), "the attempt ran and waits for approval")
        val log = fixture.loop("log", fixture.workflow.toString(), env = env).also { results += it }
        val sentence = "could not fetch main before the attempt, the hooks start from the last fetched main at ${earlier.take(12)}"
        assertContains(log.stdout, sentence)
        assertTokenAbsent(repo)
    }

    /**
     * The token is in no event, trace, log, run output, attempt file, evidence, agent env, tool config snapshot or git
     * config; the agent env does not even name its variable.
     */
    private fun assertTokenAbsent(repo: TestRepo) {
        val texts = mutableMapOf<String, String>()
        fixture.query("SELECT kind, data FROM events").forEachIndexed { index, row ->
            texts["event $index ${row["kind"]}"] =
                row["data"].orEmpty()
        }
        results.forEachIndexed { index, result -> texts["run $index"] = result.stdout + result.stderr }
        texts["human log"] = fixture.humanLog.readText()
        Files.walk(fixture.loopHome).use { paths ->
            paths
                .filter {
                    it.isRegularFile() &&
                        !it.name.endsWith(
                            ".db",
                        ) &&
                        !it.name.endsWith("-wal") &&
                        !it.name.endsWith("-shm")
                }.forEach { file ->
                    texts[file.toString()] =
                        if (file.name.endsWith(
                                ".gz",
                            )
                        ) {
                            GZIPInputStream(Files.newInputStream(file)).use { it.readBytes().decodeToString() }
                        } else {
                            file.readText()
                        }
                }
        }
        Files.list(seen).use { paths -> paths.forEach { texts[it.toString()] = it.readText() } }
        texts["project git config"] = repo.dir.resolve(".git/config").readText()
        texts["remote git config"] = bare.resolve("config").readText()

        assertTrue(Files.list(seen).use { paths -> paths.anyMatch { it.name.startsWith("tool-config-") } }, "the agent saw its tool config")
        assertTrue(texts.keys.any { it.startsWith("event") } && texts.keys.any { ".evidence-" in it }, texts.keys.toString())
        val leaks = texts.filterValues { token in it }.keys
        assertEquals(emptySet(), leaks, "the token leaked")
        val named = texts.filterKeys { "env-" in it }.filterValues { "LOOP_PR_TOKEN" in it }.keys
        assertEquals(emptySet(), named, "the agent env holds the token variable")
    }

    private companion object {
        /** m2-26: the merge settings under `pull_request`, then the move once merged. */
        const val MERGE_CONFIG = "\n        merge: { method: merge, ci: none }\n      on_delivered: { move_to: done }"
    }
}
