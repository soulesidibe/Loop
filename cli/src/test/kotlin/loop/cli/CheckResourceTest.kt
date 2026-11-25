package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.tracker.github.GitSmartHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-26: a `command` check that declares `resource: <kind>` holds a resource of that kind while it runs, for the
 * verification and for the merge check: Loop takes a lock of the kind in queue order, the provider prepares it, the
 * check gets what the prepare returned as `LOOP_RESOURCE_<KEY>` env, and the lock goes after the check. A check that
 * waits in the queue is never a failed check. A `resource:` kind no entry of `resources:` declares fails the preflight.
 * `loop run` runs in process so the fake provider of the test classpath serves its `fake-device` kind.
 */
class CheckResourceTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root.resolve("b")) }

    /** Resource ids only this test uses, since the fake provider's calls are shared by the tests of the JVM. */
    private val first = "res-${UUID.randomUUID()}"
    private val second = "res-${UUID.randomUUID()}"

    /** Where each check writes a line when it starts and when it ends, outside the project. */
    private val log: Path by lazy { root.resolve("checks.log") }

    /** The file the checks that hold a resource wait for, created once a third check waits in the queue. */
    private val go: Path by lazy { root.resolve("go") }

    @Test
    fun `two verifications each get their own resource as LOOP_RESOURCE env, a third waits in the queue without a failed check`() {
        listOf("t-1", "t-2", "t-3").forEach { fixture.issue(it) }
        val check = checkScript()
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_concurrent_agents: 3
                |  max_turns: 1
                |retries:
                |  max_attempts: 1
                |resources:
                |  - { kind: fake-device, id: $first }
                |  - { kind: fake-device, id: $second }
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "sh $check", resource: fake-device, timeout_ms: 60000 }
                """.trimMargin(),
        )
        // The two checks that hold the resources wait for `go`, created a while after a third check is queued.
        var queuedAt: Long? = null
        val opener =
            thread {
                val deadline = System.nanoTime() + 60_000_000_000L
                // The store does not exist until the run opens it.
                while (System.nanoTime() < deadline && runCatching { queued() }.getOrDefault(emptyList()).isEmpty()) Thread.sleep(50)
                queuedAt = System.nanoTime()
                Thread.sleep(1_500)
                go.toFile().createNewFile()
            }

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())
        opener.join()

        assertEquals(0, run.exitCode, run.toString())
        val resourceEvents =
            fixture.query(
                "SELECT kind, issue_identifier, attempt_id, data FROM events WHERE kind LIKE 'resource_%' ORDER BY seq",
            )
        assertTrue(queuedAt != null && queued().isNotEmpty(), "a third check waited in the queue: ${log.readLines()}\n$resourceEvents")
        listOf("t-1", "t-2", "t-3").forEach { assertEquals("review", fixture.stateOf(it), "$it\n$run") }
        val lines = log.readLines()
        val starts = lines.filter { it.startsWith("start ") }
        assertEquals(3, starts.size, "$lines")
        val serials = starts.map { it.split(' ')[2] }
        assertNotEquals(serials[0], serials[1], "the first two checks hold their own resource: $lines")
        assertEquals(setOf("fake-$first", "fake-$second"), serials.take(2).toSet(), "$lines")
        assertTrue(lines.indexOf(starts[2]) > lines.indexOfFirst { it.startsWith("end ") }, "the third ran after one released: $lines")

        val waiting = queued().single()
        assertEquals("held", waiting["reason"], "$waiting")
        val finished = fixture.query("SELECT issue_identifier, data FROM events WHERE kind = 'check_finished' ORDER BY seq")
        assertEquals(3, finished.size, "one check per attempt, no rerun and no failure: $finished")
        assertTrue(finished.all { "\"exit_code\":\"0\"" in it["data"].orEmpty() && "rerun" !in it["data"].orEmpty() }, "$finished")
        assertEquals(3, fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size, "no attempt was spent on the wait")
        // The wait is not part of the check: the third check took less time than it waited in the queue.
        val third = waiting.getValue("issue")
        val waited = grantedAt(third) - queuedAtOf(third)
        val took = finished.single { it["issue_identifier"] == third }["data"].orEmpty().let { duration(it) }
        assertTrue(waited.inWholeMilliseconds > took, "waited $waited, the check took $took ms")
        assertEquals(emptyList(), fixture.query("SELECT kind FROM resource_locks"), "each check released its resource")
        assertEquals(emptyList(), fixture.query("SELECT kind FROM resource_queue"))
        val ops = FakeResourceProvider.calls.filter { it.id == first || it.id == second }.map { it.op }
        assertEquals(3, ops.count { it == "prepare" }, "$ops")
        assertEquals(3, ops.count { it == "release" }, "$ops")
    }

    @Test
    fun `preflight refuses a resource kind that no entry of resources declares`() {
        fixture.issue("t-1")
        fixture.writeWorkflow(
            extra =
                """
                |resources:
                |  - { kind: fake-device, id: $first }
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |        - { type: command, run: "true", resource: avd }
                """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertNotEquals(0, run.exitCode, run.toString())
        assertContains(run.stderr, "kinds.code.verify.checks[1].resource is \"avd\", which no entry of resources declares")
        assertEquals(0, fixture.agentCalls(), "nothing was dispatched")
    }

    @Test
    fun `the merge check gets a resource the same way`() {
        fixture.issue("t-1")
        val seen = root.resolve("merge-check.log")
        val repo = mergeSetUp("echo \"${'$'}LOOP_ISSUE_IDENTIFIER ${'$'}LOOP_RESOURCE_SERIAL\" >> $seen")

        runOnce()
        // The remote's main moves ahead, so the merge check merges it and runs the checks again.
        advanceRemoteMain(repo)
        approve()
        runOnce()

        assertEquals("done", fixture.stateOf("t-1"), results.joinToString("\n"))
        assertEquals(listOf("t-1 fake-$first", "t-1 fake-$first"), seen.readLines(), "the verification, then the merge check")
        val mergeCheck = fixture.query("SELECT data FROM events WHERE kind = 'merge_check_finished'").single()["data"].orEmpty()
        assertContains(mergeCheck, "\"outcome\":\"merged\"")
        assertEquals(2, fixture.query("SELECT 1 FROM events WHERE kind = 'resource_granted'").size)
        assertEquals(2, fixture.query("SELECT 1 FROM events WHERE kind = 'resource_released'").size)
        assertEquals(emptyList(), fixture.query("SELECT kind FROM resource_locks"))
        assertEquals(listOf("prepare", "release", "check", "prepare", "release", "check"), calls(first))
        assertTrue(repo.git("rev-parse", "refs/loop/base/main").isNotEmpty())
    }

    /** A check that logs its start with its resource, waits for [go] while it is one of the first two, then logs its end. */
    private fun checkScript(): Path {
        val script = root.resolve("check.sh")
        script.writeText(
            """
            |echo "start ${'$'}LOOP_ISSUE_IDENTIFIER ${'$'}LOOP_RESOURCE_SERIAL" >> $log
            |n=0
            |until [ -f $go ]; do n=${'$'}((n + 1)); [ ${'$'}n -lt 900 ] || exit 3; sleep 0.1; done
            |echo "end ${'$'}LOOP_ISSUE_IDENTIFIER ${'$'}LOOP_RESOURCE_SERIAL" >> $log
            |
            """.trimMargin(),
        )
        return script
    }

    /** Each `resource_queued` event: the issue, its resource ids and its reason. */
    private fun queued(): List<Map<String, String>> =
        fixture.query("SELECT issue_identifier, data FROM events WHERE kind = 'resource_queued' ORDER BY seq").map { row ->
            val data = Json.parseToJsonElement(row["data"].orEmpty()).jsonObject
            mapOf(
                "issue" to row["issue_identifier"].orEmpty(),
                "reason" to data["reason"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

    private fun queuedAtOf(issue: String): Instant = eventTs("resource_queued", issue)

    private fun grantedAt(issue: String): Instant = eventTs("resource_granted", issue)

    private fun eventTs(
        kind: String,
        issue: String,
    ): Instant =
        Instant.parse(
            fixture
                .query("SELECT ts FROM events WHERE kind = '$kind' AND issue_identifier = '$issue' ORDER BY seq")
                .first()["ts"]
                .orEmpty(),
        )

    private fun duration(data: String): Long =
        Json
            .parseToJsonElement(data)
            .jsonObject
            .getValue("duration_ms")
            .jsonPrimitive.content
            .toLong()

    private fun calls(id: String): List<String> = FakeResourceProvider.calls.filter { it.id == id }.map { it.op }

    // The merge check: a pull request delivered to a fake GitHub API and a bare repository served on loopback.

    private val token = "ghp_m426CheckResourceToken0123456789abcd"

    private val env =
        mapOf(
            "LOOP_PR_TOKEN" to token,
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
            "USER" to "soule",
        )

    private val bare: Path get() = root.resolve("remote.git")

    private val gitServer by lazy { GitSmartHttp(bare, password = token) }

    /** The pull requests of the fake repository, by head (`acme:loop/<key>`). */
    private val open: MutableMap<String, JsonObject> = Collections.synchronizedMap(mutableMapOf())

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/acme/widgets.git/") { exchange -> gitServer.handle(exchange) }
            createContext("/api/") { exchange ->
                val (status, body) =
                    answer(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestURI.query.orEmpty(),
                        exchange.requestBody.readBytes().decodeToString(),
                    )
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.responseHeaders.add("x-ratelimit-remaining", "4999")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    private var serverStarted = false

    @AfterEach
    fun stopServer() {
        if (serverStarted) server.stop(0)
    }

    private fun answer(
        method: String,
        path: String,
        query: String,
        written: String,
    ): Pair<Int, String> {
        val pulls = "/api/repos/acme/widgets/pulls"
        return when {
            method == "GET" && path == pulls -> {
                val head = query.split("&").firstOrNull { it.startsWith("head=") }?.removePrefix("head=")
                200 to JsonArray(listOfNotNull(open[head])).toString()
            }
            method == "POST" && path == pulls -> {
                val head = Json.parseToJsonElement(written).jsonObject.text("head")
                val pull = pullRequest(open.size + 1, head)
                open["acme:$head"] = pull
                201 to pull.toString()
            }
            method == "PATCH" && path.startsWith("$pulls/") -> {
                val number = path.substringAfterLast('/')
                200 to open.values.single { it.text("number") == number }.toString()
            }
            method == "GET" && path == "/api/repos/acme/widgets/git/ref/heads/main" -> {
                val sha = bareMain() ?: return 404 to """{"message":"Not Found"}"""
                200 to """{"ref":"refs/heads/main","object":{"sha":"$sha","type":"commit"}}"""
            }
            method == "PUT" && path.startsWith("$pulls/") && path.endsWith("/merge") -> {
                val number = path.removeSuffix("/merge").substringAfterLast('/')
                val (head, pull) = open.entries.single { it.value.text("number") == number }
                val sha = Json.parseToJsonElement(written).jsonObject.text("sha")
                open[head] =
                    JsonObject(
                        pull +
                            mapOf(
                                "state" to JsonPrimitive("closed"),
                                "merged_at" to JsonPrimitive("2026-11-25T10:00:00Z"),
                                "head" to
                                    JsonObject(
                                        mapOf("ref" to JsonPrimitive(head.removePrefix("acme:")), "sha" to JsonPrimitive(sha)),
                                    ),
                            ),
                    )
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

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

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

    /**
     * Loop mode with pull request delivery under `human` and the merge check, one `fake-device` resource, the worktree
     * hooks, and a bare remote whose `main` is the project's; the one check runs [checkRun] holding the resource.
     */
    private fun mergeSetUp(checkRun: String): TestRepo {
        serverStarted = true
        val apiUrl = "http://${server.address.address.hostAddress}:${server.address.port}/api"
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ATTEMPT_ID" >> hello.txt
            |git add hello.txt
            |git -c user.name="Loop agent" -c user.email=agent@example.com commit -q -m "work on ${'$'}LOOP_ISSUE_IDENTIFIER"
            |mkdir -p .loop
            |echo '{ "status": "done", "summary": "work done" }' > .loop/result.json
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
        val check = root.resolve("merge-check.sh")
        check.writeText("$checkRun\n")
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |resources:
                |  - { kind: fake-device, id: $first }
                |kinds:
                |  code:
                |    delivery:
                |      policy: human
                |      auto_merge: { enabled: false }
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}LOOP_PR_TOKEN
                |        api_url: "$apiUrl"
                |        merge: { method: merge, ci: none }
                |      on_delivered: { move_to: done }
                |    verify:
                |      checks:
                |        - { type: command, run: "sh $check", resource: fake-device }
                """.trimMargin(),
        )
        val repo = fixture.useGitWorktrees()
        repo.git("init", "--quiet", "--bare", bare.toString(), cwd = root)
        repo.git("remote", "add", "origin", bare.toString())
        repo.git("push", "--quiet", "origin", "main")
        return repo
    }

    /** Puts a commit of someone else on `main` of the bare remote, ahead of the project's `main`. */
    private fun advanceRemoteMain(repo: TestRepo) {
        repo.git("checkout", "--quiet", "-b", "upstream")
        repo.dir.resolve("other.txt").writeText("someone else's work\n")
        repo.git("add", "other.txt")
        repo.git("-c", "user.name=Other", "-c", "user.email=other@example.com", "commit", "--quiet", "-m", "other work")
        repo.git("checkout", "--quiet", "main")
        repo.git("--git-dir", bare.toString(), "fetch", "--quiet", repo.dir.toString(), "upstream:main", cwd = root)
        repo.git("branch", "--quiet", "-D", "upstream")
    }

    private val results = mutableListOf<LoopResult>()

    private fun runOnce() {
        val run = fixture.inProcess("run", fixture.workflow.toString(), "--once", env = env)
        results += run
        assertEquals(0, run.exitCode, run.toString())
    }

    private fun approve() {
        val approved = fixture.inProcess("approve", "t-1", "--workflow", fixture.workflow.toString(), env = env)
        results += approved
        assertEquals(0, approved.exitCode, approved.toString())
    }
}
