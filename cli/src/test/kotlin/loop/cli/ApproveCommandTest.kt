package loop.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.waiting.WaitKeys
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m2-24, end to end with the command runner: under `delivery: { policy: human }` a verified result posts an
 * evidence summary on the issue and waits as `Waiting(approval)`, `loop approve` releases it with a human touch,
 * and Loop delivers it on its next tick, here the move to the handoff state of the files tracker.
 */
class ApproveCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val approver = mapOf("USER" to "soule")

    private val githubToken = mapOf("GITHUB_TOKEN" to "ghp_m429Token0123456789abcdefghijklmnopq")

    /** The variable every attempt env holds and an operator shell never does (m2-24). */
    private val inAttempt = mapOf("LOOP_ATTEMPT_ID" to "attempt-1")

    /** Loop mode with a check the default agent passes (it writes hello.txt), delivery as [delivery]. */
    private fun loopMode(delivery: String? = "{ policy: human }") {
        val line = delivery?.let { "\n|    delivery: $it" }.orEmpty()
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:$line
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, it.toString()) }

    private fun approve(issue: String = "t-1"): LoopResult =
        fixture.inProcess("approve", issue, "--workflow", fixture.workflow.toString(), env = approver)

    private fun attempts(): Int = fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_started'").size

    private fun waits(): List<Map<String, String?>> = fixture.query("SELECT identifier, reason, attempt_id, data FROM waiting_issues")

    private fun events(kind: String): List<Map<String, String?>> =
        fixture.query("SELECT attempt_id, data FROM events WHERE kind = '$kind' ORDER BY seq")

    private fun trace(id: String): List<String> {
        val trace = fixture.inProcess("trace", id, "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        return trace.stdout.lines()
    }

    /**
     * A GitHub API for octocat/Hello-World that serves its recorded issue 26 and answers 404 for any other issue, or,
     * when not [readable], 500 for every request. [paths] holds the paths it was asked for.
     */
    private class GitHubApi(
        issue: JsonObject,
        readable: Boolean,
    ) : AutoCloseable {
        val paths = mutableListOf<String>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/api/") { exchange ->
                    val path = exchange.requestURI.rawPath
                    synchronized(paths) { paths += path }
                    val (status, body) =
                        when {
                            !readable -> 500 to """{"message":"Server Error"}"""
                            path == "/api/repos/octocat/Hello-World/issues/26" -> 200 to issue.toString()
                            "/issues/" in path -> 404 to """{"message":"Not Found"}"""
                            else -> 200 to "[]"
                        }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        val url: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/api"

        override fun close() = server.stop(0)
    }

    /** The GitHub API of [GitHubApi], with issue 26 read from the recorded fixtures. */
    private fun github(readable: Boolean = true): GitHubApi {
        val recorded = Path.of(System.getProperty("loop.githubRecorded") ?: fail("system property loop.githubRecorded is not set"))
        val issue =
            Json
                .parseToJsonElement(recorded.resolve("open-issues-page-1.json").readText())
                .jsonArray
                .map { it as JsonObject }
                .first { it["number"].toString() == "26" }
        return GitHubApi(issue, readable)
    }

    /**
     * A github workflow on octocat/Hello-World at [apiUrl], delivery under policy human, and with [merge] the merge of
     * the pull request then the move to `closed`. [extraKinds] are `kinds:` lines written after `code` (m4-18).
     */
    private fun githubWorkflow(
        apiUrl: String,
        merge: Boolean = false,
        extraKinds: String = "",
    ) {
        val pullRequest =
            if (merge) {
                "      pull_request:\n" +
                    "        merge: { method: squash, ci: none }\n" +
                    "      on_delivered: { move_to: closed }\n"
            } else {
                ""
            }
        fixture.workflow.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: github\n" +
                "  provider:\n" +
                "    repo: octocat/Hello-World\n" +
                "    token: \$GITHUB_TOKEN\n" +
                "    api_url: \"$apiUrl\"\n" +
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
                pullRequest +
                "    verify:\n" +
                "      checks:\n" +
                "        - { type: command, run: \"test -f hello.txt\" }\n" +
                extraKinds +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
    }

    /**
     * Issue 26 of the github tracker waits for approval to move to review, as the store holds it since m4-12, under the
     * [kind] it was dispatched under when set (m4-18).
     */
    private fun waitOnGitHub(kind: String? = null) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use {
            val wait =
                WaitingIssue(
                    ProjectId(fixture.projectId()),
                    "default:26",
                    "Hello-World-26",
                    WaitReason.Approval,
                    AttemptId("a-1"),
                    null,
                    Clock.System.now(),
                    mapOf(WaitKeys.TO to "review") + listOfNotNull(kind?.let { WaitKeys.KIND to it }),
                )
            runBlocking { it.startWaiting(wait) }.getOrThrow()
        }
    }

    private fun approveOnGitHub(issue: String): LoopResult =
        fixture.inProcess("approve", issue, "--workflow", fixture.workflow.toString(), env = approver + githubToken)

    @Test
    fun `a verified result under policy human posts an evidence summary and waits for approval, never dispatched again`() {
        val file = fixture.issue("t-1")
        loopMode()

        val first = runOnce()

        assertEquals("todo", fixture.stateOf("t-1"), first.toString())
        val attempt = events("attempt_started").single()["attempt_id"]!!
        val wait = waits().single()
        assertEquals("t-1", wait["identifier"])
        assertEquals("approval", wait["reason"])
        assertEquals(attempt, wait["attempt_id"])
        assertContains(wait["data"].orEmpty(), "\"to\":\"review\"")
        // The evidence summary is on the issue, written by loop, built from the verification's facts.
        val text = file.readText()
        assertContains(text, "Loop verified the result of attempt ${attempt.take(8)}: every check passed.")
        assertContains(text, "- check 0, test -f hello.txt: passed with exit code 0 in ")
        assertContains(text, "evidence $attempt.evidence-1.log")
        assertContains(text, "The delivery policy is human: t-1 moves to review once an operator approves it.")
        assertFalse("loop approve" in text, text)
        assertContains(events("issue_commented").single()["data"].orEmpty(), "\"reason\":\"evidence_summary\"")
        assertEquals(emptyList(), events("issue_moved"))
        assertContains(events("approval_requested").single()["data"].orEmpty(), "\"to\":\"review\"")
        // The attempt ends at once, with no continuation queued behind the wait.
        assertContains(events("attempt_finished").single()["data"].orEmpty(), "\"exit_reason\":\"waiting_approval\"")
        assertEquals(0, fixture.query("SELECT 1 FROM retry_states WHERE attempt IS NOT NULL").size)

        // A later tick leaves the waiting issue alone, though it is still in todo.
        runOnce()
        assertEquals(1, attempts())
        assertEquals("todo", fixture.stateOf("t-1"))

        val status = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, status.exitCode, status.toString())
        val line = status.stdout.lines().singleOrNull { it.startsWith("waiting") } ?: fail("no waiting line\n$status")
        assertContains(line, "reason=approval")
        assertTrue(trace("t-1").any { "approval_requested" in it && "to=review" in it })
    }

    @Test
    fun `a done result file reaches Waiting(approval) like the intercepted move`() {
        fixture.issue("t-1")
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello" > hello.txt
            |mkdir -p .loop
            |cat > .loop/result.json <<'JSON'
            |{ "status": "done", "deliverable": { "type": "files", "paths": ["hello.txt"] }, "summary": "Wrote hello.txt." }
            |JSON
            |
            """.trimMargin(),
        )
        loopMode()

        runOnce()

        assertEquals("todo", fixture.stateOf("t-1"))
        assertEquals("approval", waits().single()["reason"])
        assertContains(fixture.issueFile("t-1").readText(), "t-1 moves to review once an operator approves it.")
        assertContains(events("attempt_finished").single()["data"].orEmpty(), "\"exit_reason\":\"waiting_approval\"")
    }

    @Test
    fun `delivery is human when the kind does not set it, and auto moves at once`() {
        fixture.issue("t-1")
        loopMode(delivery = null)

        runOnce()

        assertEquals("todo", fixture.stateOf("t-1"))
        assertEquals("approval", waits().single()["reason"])

        fixture.issue("t-2")
        loopMode(delivery = "{ policy: auto, auto_merge: { enabled: true } }")
        runOnce()
        assertEquals("review", fixture.stateOf("t-2"))
        assertEquals(listOf("t-1"), waits().map { it["identifier"] })
    }

    @Test
    fun `loop approve releases the waiting issue with a human touch and the next tick delivers it`() {
        fixture.issue("t-1")
        loopMode()
        runOnce()
        val attempt = waits().single()["attempt_id"]!!

        val approved = approve()

        assertEquals(0, approved.exitCode, approved.toString())
        assertTrue(
            Regex("""^Approved t-1, waiting since \S+Z\. Loop moves it to review on its next tick\.$""").matches(approved.stdout.trim()),
            approved.toString(),
        )
        val given = events("approval_given").single()
        assertEquals(attempt, given["attempt_id"])
        assertContains(given["data"].orEmpty(), "\"by\":\"soule\"")
        assertContains(events("human_touch").single()["data"].orEmpty(), "\"touch\":\"approve\"")
        // The approval is in the store until Loop delivers: nothing moved yet.
        assertContains(waits().single()["data"].orEmpty(), "\"approved_by\":\"soule\"")
        assertEquals("todo", fixture.stateOf("t-1"))

        // A second approval changes nothing and records no second touch.
        val again = approve()
        assertEquals(0, again.exitCode, again.toString())
        assertEquals("t-1 is already approved, Loop moves it to review on its next tick. Nothing changed.", again.stdout.trim())
        assertEquals(1, events("approval_given").size)
        assertEquals(1, events("human_touch").size)

        // A new process, so the approval also survives a restart: it delivers on its first tick.
        runOnce()

        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(emptyList(), waits())
        assertEquals(1, attempts())
        val moved = events("issue_moved").single()
        assertEquals(attempt, moved["attempt_id"])
        assertContains(moved["data"].orEmpty(), "\"approved\":\"true\"")
        assertContains(moved["data"].orEmpty(), "\"by\":\"host\"")
        val lines = trace("t-1")
        assertTrue(lines.any { "approval_given" in it && "by=soule" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "issue_moved" in it && "after approval" in it }, lines.joinToString("\n"))
        assertContains(fixture.inProcess("log", fixture.workflow.toString()).stdout, "approved by soule")
    }

    @Test
    fun `approving an issue that is not waiting for approval fails with a clear message and changes nothing`() {
        fixture.issue("t-1")
        loopMode()

        val notWaiting = approve()
        assertEquals(4, notWaiting.exitCode, notWaiting.toString())
        assertEquals("loop approve: t-1 is not waiting for approval. Nothing changed.", notWaiting.stderr.trim())

        val unknown = approve("t-9")
        assertEquals(2, unknown.exitCode, unknown.toString())
        assertContains(unknown.stderr, "loop approve: no issue \"t-9\" in the tracker at ")

        // A wait for its checks is not an approval.
        val store = SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow()
        store.use {
            val wait =
                WaitingIssue(ProjectId(fixture.projectId()), "t-1", "t-1", WaitReason.Verify, AttemptId("a-1"), null, Clock.System.now())
            runBlocking { it.startWaiting(wait) }.getOrThrow()
        }
        val verifying = approve()
        assertEquals(4, verifying.exitCode, verifying.toString())
        assertEquals("loop approve: t-1 is waiting for verify, not for approval. Nothing changed.", verifying.stderr.trim())
        assertEquals(emptyList(), events("approval_given"))
        assertEquals(emptyList(), events("human_touch"))
        assertEquals("verify", waits().single()["reason"])
    }

    @Test
    fun `bl-65 approve loads a github workflow with the host's tracker kinds and reads the tracker`() {
        github().use { api ->
            githubWorkflow(api.url)

            val result = approveOnGitHub("26")

            assertFalse("not supported" in result.stderr, result.toString())
            assertEquals(4, result.exitCode, result.toString())
            assertContains(result.stderr, "is not waiting for approval. Nothing changed.")
            assertNotNull(api.paths.firstOrNull { it.endsWith("/issues/26") }, "the tracker was never read: ${api.paths}")
        }
    }

    @Test
    fun `m4-29 a github issue waiting for approval is approved through to approval_given`() {
        github().use { api ->
            githubWorkflow(api.url)
            waitOnGitHub()

            val approved = approveOnGitHub("26")

            assertEquals(0, approved.exitCode, approved.toString())
            assertTrue(
                Regex("""^Approved Hello-World-26, waiting since \S+Z\. Loop moves it to review on its next tick\.$""")
                    .matches(approved.stdout.trim()),
                approved.toString(),
            )
            assertNotNull(api.paths.firstOrNull { it.endsWith("/issues/26") }, "the tracker was never read: ${api.paths}")
            val given = events("approval_given").single()
            assertEquals("a-1", given["attempt_id"])
            assertContains(given["data"].orEmpty(), "\"by\":\"soule\"")
            assertContains(given["data"].orEmpty(), "\"to\":\"review\"")
            assertContains(events("human_touch").single()["data"].orEmpty(), "\"touch\":\"approve\"")
            assertContains(waits().single()["data"].orEmpty(), "\"approved_by\":\"soule\"")
        }
    }

    @Test
    fun `m4-29 under merge loop approve names the merge check, the merge and on_delivered move_to`() {
        github().use { api ->
            githubWorkflow(api.url, merge = true)
            waitOnGitHub()

            val approved = approveOnGitHub("26")

            assertEquals(0, approved.exitCode, approved.toString())
            assertTrue(
                Regex(
                    """^Approved Hello-World-26, waiting since \S+Z\. Loop delivers its pull request on its next tick, """ +
                        """runs the merge check and merges it, then moves it to closed\.$""",
                ).matches(approved.stdout.trim()),
                approved.toString(),
            )
            assertFalse("to review" in approved.stdout, approved.toString())
        }
    }

    /** m4-18: a second kind that takes every issue of the github tracker, `default`, and delivers by a plain move. */
    private val quickKind =
        "  quick:\n" +
            "    match: { tracker: default }\n" +
            "    verify:\n" +
            "      checks: []\n"

    @Test
    fun `m4-18 loop approve follows the kind the wait was dispatched under, not the one the issue matches now`() {
        github().use { api ->
            githubWorkflow(api.url, merge = true, extraKinds = quickKind)
            waitOnGitHub(kind = "code")

            val approved = approveOnGitHub("26")

            assertEquals(0, approved.exitCode, approved.toString())
            assertContains(approved.stdout, "runs the merge check and merges it, then moves it to closed.")
        }
    }

    @Test
    fun `m4-18 loop approve warns when the kind of the wait left the workflow and still approves`() {
        github().use { api ->
            githubWorkflow(api.url, merge = true)
            waitOnGitHub(kind = "content")

            val approved = approveOnGitHub("26")

            assertEquals(0, approved.exitCode, approved.toString())
            assertContains(
                approved.stderr,
                "loop approve: the kind content the issue was dispatched under is no longer in the workflow, " +
                    "Loop delivers it once the kind is back.",
            )
            assertEquals(1, events("approval_given").size)
        }
    }

    @Test
    fun `m4-29 under merge the already approved message names the merge check, the merge and on_delivered move_to`() {
        github().use { api ->
            githubWorkflow(api.url, merge = true)
            waitOnGitHub()
            assertEquals(0, approveOnGitHub("26").exitCode)

            val again = approveOnGitHub("26")

            assertEquals(0, again.exitCode, again.toString())
            assertEquals(
                "Hello-World-26 is already approved, Loop delivers its pull request on its next tick, runs the merge check " +
                    "and merges it, then moves it to closed. Nothing changed.",
                again.stdout.trim(),
            )
            assertEquals(1, events("approval_given").size)
        }
    }

    @Test
    fun `m4-29 under merge approval_given records the state the issue ends in`() {
        github().use { api ->
            githubWorkflow(api.url, merge = true)
            waitOnGitHub()

            assertEquals(0, approveOnGitHub("26").exitCode)

            val given = events("approval_given").single()["data"].orEmpty()
            assertContains(given, "\"to\":\"closed\"")
            assertFalse("\"to\":\"review\"" in given, given)
            // The wait itself still names the handoff state, which the merge never uses.
            assertContains(waits().single()["data"].orEmpty(), "\"to\":\"review\"")
        }
    }

    @Test
    fun `m4-29 a tracker read that fails names the github tracker, and approve finds the waiting issue by its id`() {
        github(readable = false).use { api ->
            githubWorkflow(api.url)
            waitOnGitHub()

            val approved = approveOnGitHub("26")

            assertEquals(0, approved.exitCode, approved.toString())
            assertContains(approved.stderr, "loop approve: cannot read \"26\" in the github tracker of octocat/Hello-World: ")
            assertContains(approved.stderr, "Looking for it among the waiting issues.")
            assertFalse("the tracker at" in approved.stderr, approved.toString())
            assertTrue(approved.stdout.startsWith("Approved Hello-World-26, waiting since "), approved.toString())
            assertEquals(1, events("approval_given").size)
        }
    }

    @Test
    fun `m4-29 with the github tracker failing approve finds the waiting issue by its identifier`() {
        github(readable = false).use { api ->
            githubWorkflow(api.url)
            waitOnGitHub()

            val approved = approveOnGitHub("Hello-World-26")

            // The github tracker asks GitHub for issue numbers only, so the identifier finds nothing there.
            assertEquals(0, approved.exitCode, approved.toString())
            assertFalse("no issue" in approved.stderr, approved.toString())
            assertTrue(approved.stdout.startsWith("Approved Hello-World-26, waiting since "), approved.toString())
            assertEquals(1, events("approval_given").size)
        }
    }

    @Test
    fun `m4-29 an issue missing from the github tracker is named with the github tracker`() {
        github().use { api ->
            githubWorkflow(api.url)
            waitOnGitHub()

            val unknown = approveOnGitHub("99")

            assertEquals(2, unknown.exitCode, unknown.toString())
            assertEquals("loop approve: no issue \"99\" in the github tracker of octocat/Hello-World.", unknown.stderr.trim())
            assertEquals(emptyList(), events("approval_given"))
        }
    }

    @Test
    fun `m2-26 a merge wait shows reason=merge in loop status and is not an approval`() {
        fixture.issue("t-1")
        loopMode()
        val store = SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow()
        store.use {
            val wait =
                WaitingIssue(
                    ProjectId(fixture.projectId()),
                    "t-1",
                    "t-1",
                    WaitReason.Merge,
                    AttemptId("a-1"),
                    null,
                    Clock.System.now(),
                    mapOf(WaitKeys.PHASE to WaitKeys.PHASE_CHECK, WaitKeys.PULL_REQUEST_NUMBER to "7"),
                )
            runBlocking { it.startWaiting(wait) }.getOrThrow()
        }

        val status = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, status.exitCode, status.toString())
        val line = status.stdout.lines().singleOrNull { it.startsWith("waiting") } ?: fail("no waiting line\n$status")
        assertContains(line, "reason=merge")

        val merging = approve()
        assertEquals(4, merging.exitCode, merging.toString())
        assertEquals("loop approve: t-1 is waiting for merge, not for approval. Nothing changed.", merging.stderr.trim())
        assertEquals("merge", waits().single()["reason"])
    }

    @Test
    fun `approve refuses to run inside an attempt and changes nothing in the store`() {
        fixture.issue("t-1")
        loopMode()
        runOnce()
        val before = waits().single()

        val refused = fixture.loop("approve", "t-1", "--workflow", fixture.workflow.toString(), env = approver + inAttempt)

        assertEquals(5, refused.exitCode, refused.toString())
        assertEquals(
            "loop approve: refused inside a Loop attempt, only an operator approves a delivery. Nothing changed.",
            refused.stderr.trim(),
        )
        assertEquals("", refused.stdout.trim())
        assertEquals(before, waits().single())
        assertFalse("approved_by" in before["data"].orEmpty())
        assertEquals(emptyList(), events("approval_given"))
        assertEquals(emptyList(), events("human_touch"))

        // The same command from an operator shell, without the variable, still approves.
        assertEquals(0, approve().exitCode)
    }

    @Test
    fun `an issue a human moves out of the active states while it waits stops waiting, the human change wins`() {
        val file = fixture.issue("t-1")
        loopMode()
        runOnce()
        assertEquals("approval", waits().single()["reason"])

        file.writeText(file.readText().replace("state: todo", "state: done"))
        runOnce()

        assertEquals(emptyList(), waits())
        assertEquals("done", fixture.stateOf("t-1"))
        assertContains(events("human_touch").single()["data"].orEmpty(), "\"touch\":\"move\"")
        val late = approve()
        assertEquals(4, late.exitCode, late.toString())
        assertContains(late.stderr, "t-1 is not waiting for approval")
    }

    @Test
    fun `a malformed issue file ends no approval wait, approved or not, and the issue is delivered once it parses again`() {
        val file = fixture.issue("t-1")
        loopMode()
        runOnce()
        val good = file.readText()
        // Broken front matter, as a tick could read mid save: the by-state read skips the file.
        val broken = good.lines().filterNot { it.startsWith("title:") }.joinToString("\n")

        file.writeText(broken)
        val malformed = runOnce()

        assertEquals("approval", waits().single()["reason"], malformed.toString())
        assertEquals(emptyList(), events("human_touch"))
        val kept = malformed.stderr.lines().single { "event=waiting outcome=failed" in it }
        assertContains(kept, "cannot read the issue, it keeps waiting")
        assertEquals(1, attempts())

        file.writeText(good)
        runOnce()
        assertEquals("approval", waits().single()["reason"])
        assertEquals(1, attempts())
        assertEquals("todo", fixture.stateOf("t-1"))

        assertEquals(0, approve().exitCode)
        file.writeText(broken)
        val approvedMalformed = runOnce()

        val wait = waits().single()
        assertEquals("approval", wait["reason"], approvedMalformed.toString())
        assertContains(wait["data"].orEmpty(), "\"approved_by\":\"soule\"")
        assertEquals(
            listOf("approve"),
            events("human_touch").map { Regex("\"touch\":\"(\\w+)\"").find(it["data"].orEmpty())?.groupValues?.get(1) },
        )
        assertEquals(emptyList(), events("issue_moved"))

        file.writeText(good)
        runOnce()

        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(emptyList(), waits())
        assertEquals(1, attempts())
        assertContains(events("issue_moved").single()["data"].orEmpty(), "\"approved\":\"true\"")
    }

    @Test
    fun `the wait survives a restart and a running loop delivers an approval given from another terminal`() {
        fixture.issue("t-1")
        loopMode()
        fixture.workflow.writeText(fixture.workflow.readText().replace("interval_ms: 1000", "interval_ms: $POLL_INTERVAL_MS"))
        runOnce()

        // The restart: a long running loop keeps the issue waiting across many polls.
        val logged = fixture.humanLog.readText().length
        val run = fixture.start("run", fixture.workflow.toString())
        try {
            // The new run's start line in the human log, then many polls.
            val deadline = System.nanoTime() + WAIT_NANOS
            while (fixture.humanLog.readText().length == logged && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            Thread.sleep(POLLS * POLL_INTERVAL_MS)
            assertEquals(1, attempts(), run.stdoutSoFar())
            assertEquals("todo", fixture.stateOf("t-1"))
            assertEquals("approval", waits().single()["reason"])

            val approved = approve()
            assertEquals(0, approved.exitCode, approved.toString())

            awaitState("t-1", "review") ?: fail("the approved issue was never delivered\n${run.stdoutSoFar()}")
            assertEquals(emptyList(), awaitEmpty("SELECT * FROM waiting_issues"))
            assertEquals(1, attempts())
        } finally {
            run.process.destroy()
            if (!run.process.waitFor(30, TimeUnit.SECONDS)) run.process.destroyForcibly()
        }
    }

    private fun awaitEmpty(sql: String): List<Map<String, String?>> {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (fixture.query(sql).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return fixture.query(sql)
    }

    private fun awaitState(
        id: String,
        state: String,
    ): String? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (fixture.stateOf(id) != state && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return fixture.stateOf(id).takeIf { it == state }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val POLLS = 15
        const val POLL_MS = 50L
        val WAIT_NANOS = TimeUnit.SECONDS.toNanos(40)
    }
}
