package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m2-15, end to end with the command runner: an agent without tools ends its attempt by writing
 * `.loop/result.json` (PLAN section 7), and Loop acts on its status.
 */
class ResultFileCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Loop mode with one command check [run], one turn per attempt, and [extra] front matter lines. */
    private fun loopMode(
        run: String = "test -f hello.txt",
        extra: String = "",
    ) = fixture.writeWorkflow(
        extra =
            """
            |agent:
            |  max_turns: 1
            |retries:
            |  max_attempts: 1
            |  max_continuations: 1
            |kinds:
            |  code:
            |    delivery: { policy: auto, auto_merge: { enabled: true } }
            |    verify:
            |      checks:
            |        - { type: command, run: "$run" }
            |$extra
            """.trimMargin(),
    )

    /** An agent that writes hello.txt, then [result] to `.loop/result.json`, and never calls `loop tool`. */
    private fun resultAgent(result: String) {
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |mkdir -p .loop
            |cat > .loop/result.json <<'JSON'
            |$result
            |JSON
            |
            """.trimMargin(),
        )
    }

    private fun run(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, it.toString()) }

    private fun events(kind: String): List<Map<String, String?>> =
        fixture.query("SELECT attempt_id, issue_identifier, data FROM events WHERE kind = '$kind' ORDER BY seq")

    private fun trace(id: String): List<String> {
        val trace = fixture.loop("trace", id, "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        return trace.stdout.lines()
    }

    private fun savedResult(attemptId: String): Path = fixture.loopHome.resolve("attempts/${fixture.projectId()}/$attemptId.result-1.json")

    private val doneResult =
        """
        |{
        |  "status": "done",
        |  "deliverable": { "type": "files", "paths": ["hello.txt"] },
        |  "summary": "Wrote hello.txt.",
        |  "acceptance": [ { "criterion": "hello.txt exists", "proof": "check", "ref": "test -f hello.txt" } ]
        |}
        """.trimMargin()

    @Test
    fun `a done result starts verification exactly like the intercepted move`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        // t-1 submits through the result file, t-2 through the intercepted move.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |if [ "${'$'}LOOP_ISSUE_IDENTIFIER" = t-1 ]; then
            |  mkdir -p .loop
            |  cat > .loop/result.json <<'JSON'
            |$doneResult
            |JSON
            |else
            |  loop tool move review
            |fi
            |
            """.trimMargin(),
        )
        loopMode()

        val result = run()

        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals("review", fixture.stateOf("t-2"), result.toString())
        val verification = listOf("result_submitted", "check_started", "check_finished", "verdict", "issue_moved")

        fun kindsOf(id: String) =
            fixture
                .query("SELECT kind FROM events WHERE issue_identifier = '$id' ORDER BY seq")
                .mapNotNull { it["kind"] }
                .filter { it in verification }
        assertEquals(verification, kindsOf("t-1"))
        assertEquals(kindsOf("t-2"), kindsOf("t-1"))
        val submitted = events("result_submitted").single { it["issue_identifier"] == "t-1" }
        val data = submitted["data"].orEmpty()
        assertContains(data, "\"to\":\"review\"")
        assertContains(data, "\"status\":\"done\"")
        assertContains(data, "\"source\":\"file\"")
        assertContains(data, "\"acceptance\":\"1\"")
        val verdict = events("verdict").single { it["issue_identifier"] == "t-1" }
        assertContains(verdict["data"].orEmpty(), "\"verdict\":\"verified\"")
        val moved = events("issue_moved").single { it["issue_identifier"] == "t-1" }
        assertContains(moved["data"].orEmpty(), "\"verified\":\"true\"")
        val lines = trace("t-1")
        assertTrue(
            lines.any {
                "result_submitted" in it && "to=review" in it && "status=done" in it && "from .loop/result.json" in it
            },
            lines.joinToString("\n"),
        )
        assertTrue(lines.any { "issue_moved" in it && "by host after checks" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `a done result reruns a failing check like the intercepted move (m2-08)`() {
        fixture.issue("t-1")
        resultAgent(doneResult)
        // Fails on its first run only: the first run leaves a marker outside the workspace.
        val marker = root.resolve("ran-once")
        loopMode("test -f $marker || { touch $marker; exit 1; }")

        val result = run()

        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val finished = events("check_finished").map { it["data"].orEmpty() }
        assertEquals(2, finished.size)
        assertContains(finished[1], "\"rerun\":\"true\"")
        assertContains(events("verdict").single()["data"].orEmpty(), "\"verdict\":\"verified\"")
    }

    /** Checks that t-1 parked with `unverified` for [reason] and its attempt ended `unverified`. */
    private fun assertParkedUnverified(reason: String) {
        assertEquals("todo", fixture.stateOf("t-1"), "never delivered")
        val verdict = events("verdict").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"unverified\"")
        assertContains(verdict, reason)
        assertEquals(
            listOf("unverified"),
            fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 't-1'").map { it["reason"] },
        )
        assertContains(events("attempt_finished").single()["data"].orEmpty(), "\"reason\":\"unverified\"")
        assertTrue(trace("t-1").any { "issue_parked" in it && "reason=unverified" in it })
    }

    @Test
    fun `a done result whose check cannot run parks the issue unverified like the intercepted move (m2-08)`() {
        fixture.issue("t-1")
        resultAgent(doneResult)
        loopMode("no-such-command-m2-15")

        run()

        assertParkedUnverified("check 0 could not run: command not found, exit code 127")
        assertEquals(1, events("check_started").size, "not rerun")
    }

    @Test
    fun `an unverified verdict wins over a blocked result in the same drain (m2-08)`() {
        fixture.issue("t-1")
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |loop tool move review
            |mkdir -p .loop
            |echo '{ "status": "blocked", "summary": "No device." }' > .loop/result.json
            |
            """.trimMargin(),
        )
        loopMode("no-such-command-m2-15")

        run()

        assertParkedUnverified("check 0 could not run")
        assertContains(events("result_submitted").last()["data"].orEmpty(), "\"status\":\"blocked\"")
    }

    @Test
    fun `a done result reaches diff_policy and the core checks like the intercepted move (m2-12)`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        fixture.projectDir
            .resolve("guarded")
            .toFile()
            .mkdirs()
        fixture.projectDir
            .resolve("guarded/data.txt")
            .toFile()
            .writeText("keep me\n")
        // Both commit an edit of a protected file; t-1 submits through the result file, t-2 through the move.
        val git = "git -c user.name=Agent -c user.email=agent@example.com"
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |echo changed >> guarded/data.txt
            |$git commit -qam edit
            |if [ "${'$'}LOOP_ISSUE_IDENTIFIER" = t-1 ]; then
            |  mkdir -p .loop
            |  echo '{ "status": "done", "summary": "Edited the data." }' > .loop/result.json
            |else
            |  loop tool move review
            |fi
            |
            """.trimMargin(),
        )
        loopMode("true", extra = "      guards:\n        protect: [guarded/]")
        fixture.useGitWorktrees()

        val result = run()

        fun checksOf(id: String) =
            fixture
                .query(
                    "SELECT kind, data FROM events WHERE issue_identifier = '$id' AND kind IN ('check_finished', 'verdict') ORDER BY seq",
                ).map { row ->
                    val data = row["data"].orEmpty()
                    val command = Regex("\"command\":\"([^\"]*)\"").find(data)?.groupValues?.get(1)
                    val exit = Regex("\"exit_code\":\"([^\"]*)\"").find(data)?.groupValues?.get(1)
                    val verdict = Regex("\"verdict\":\"([^\"]*)\"").find(data)?.groupValues?.get(1)
                    listOfNotNull(row["kind"], command, exit, verdict).joinToString(" ")
                }
        val expected =
            listOf(
                "check_finished true 0",
                "check_finished diff_policy base=main protect=guarded/ 1",
                "check_finished diff_policy base=main protect=guarded/ 1",
                "verdict refuted",
            )
        assertEquals(expected, checksOf("t-1"), result.toString())
        assertEquals(checksOf("t-2"), checksOf("t-1"))
        assertEquals("todo", fixture.stateOf("t-1"), result.toString())
    }

    @Test
    fun `the result and its acceptance proofs are saved beside the evidence and the file leaves the workspace`() {
        fixture.issue("t-1")
        resultAgent(doneResult)
        loopMode()

        run()

        val attemptId = checkNotNull(events("verdict").single()["attempt_id"])
        val saved = savedResult(attemptId)
        assertTrue(saved.exists(), "the result is saved at $saved")
        val text = saved.readText()
        assertContains(text, "\"criterion\": \"hello.txt exists\"")
        assertContains(text, "\"proof\": \"check\"")
        assertTrue(fixture.loopHome.resolve("attempts/${fixture.projectId()}/$attemptId.evidence-1.log").exists())
        assertContains(events("result_submitted").single()["data"].orEmpty(), "\"result\":\"$attemptId.result-1.json\"")
        assertFalse(fixture.workspace("t-1").resolve(".loop/result.json").exists(), "a result is taken once")
    }

    @Test
    fun `a needs_info result comments the question and moves the issue to needs_info, unverified`() {
        val file = fixture.issue("t-1")
        resultAgent("""{ "status": "needs_info", "summary": "Should the greeting be in French or in English?" }""")
        loopMode()

        val result = run()

        assertEquals("needs_info", fixture.stateOf("t-1"), result.toString())
        assertContains(file.readText(), "Should the greeting be in French or in English?")
        assertEquals(0, events("check_started").size, "a question is never verified")
        assertContains(events("result_submitted").single()["data"].orEmpty(), "\"status\":\"needs_info\"")
        val finished = events("attempt_finished").single()["data"].orEmpty()
        assertContains(finished, "\"outcome\":\"succeeded\"")
        assertTrue(trace("t-1").any { "issue_moved" in it && "to=needs_info" in it })
    }

    @Test
    fun `a blocked result fails the attempt with the agent's reason traced`() {
        fixture.issue("t-1")
        resultAgent("""{ "status": "blocked", "summary": "No Android device is connected." }""")
        loopMode()

        val result = run()

        assertEquals("todo", fixture.stateOf("t-1"), result.toString())
        assertEquals(0, events("check_started").size)
        val finished = events("attempt_finished").single()["data"].orEmpty()
        assertContains(finished, "\"outcome\":\"failed\"")
        assertContains(finished, "\"reason\":\"result_blocked\"")
        assertContains(finished, "\"error\":\"No Android device is connected.\"")
        val parked = events("issue_parked").single()["data"].orEmpty()
        assertContains(parked, "\"error\":\"result_blocked\"")
        val lines = trace("t-1")
        assertTrue(
            lines.any { "result_submitted" in it && "status=blocked" in it && "reason=\"No Android device is connected.\"" in it },
            lines.joinToString("\n"),
        )
        assertTrue(lines.any { "Failed" in it && "reason=result_blocked" in it }, lines.joinToString("\n"))
        assertContains(fixture.humanLog.readText(), "the agent submitted a blocked result")
    }

    @Test
    fun `a failed result fails the attempt with the agent's reason traced`() {
        fixture.issue("t-1")
        resultAgent("""{ "status": "failed", "summary": "The build tool is missing." }""")
        loopMode()

        val result = run()

        assertEquals("todo", fixture.stateOf("t-1"), result.toString())
        val finished = events("attempt_finished").single()["data"].orEmpty()
        assertContains(finished, "\"reason\":\"result_failed\"")
        assertContains(finished, "\"error\":\"The build tool is missing.\"")
        assertContains(fixture.humanLog.readText(), "the agent submitted a failed result")
    }

    @Test
    fun `an invalid result is a failed attempt with the validation error traced, never a crash`() {
        fixture.issue("t-1")
        resultAgent("""{ "status": "finished" }""")
        loopMode()

        val result = run()

        assertEquals("todo", fixture.stateOf("t-1"), result.toString())
        assertEquals(0, events("check_started").size)
        val error = "status must be one of done, blocked, needs_info, failed, not \\\"finished\\\""
        val invalid = events("result_invalid").single()
        assertContains(invalid["data"].orEmpty(), error)
        val finished = events("attempt_finished").single()["data"].orEmpty()
        assertContains(finished, "\"reason\":\"invalid_result\"")
        assertContains(finished, error)
        assertTrue(savedResult(checkNotNull(invalid["attempt_id"])).exists(), "an invalid result is saved too")
        assertTrue(trace("t-1").any { "result_invalid" in it && "status must be one of" in it })
        assertContains(fixture.humanLog.readText(), "the agent submitted an invalid result")
    }

    @Test
    fun `a result file that is not json fails the attempt the same way`() {
        fixture.issue("t-1")
        resultAgent("done, I think")
        loopMode()

        run()

        val finished = events("attempt_finished").single()["data"].orEmpty()
        assertContains(finished, "\"reason\":\"invalid_result\"")
        assertContains(finished, "the result is not valid JSON")
    }

    @Test
    fun `a result file left by a failed turn is removed at the next attempt start, never submitted`() {
        fixture.issue("t-1")
        val failedOnce = root.resolve("failed-once")
        // The first turn writes a done result and fails; the retry's turn writes nothing.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |if [ ! -e '$failedOnce' ]; then
            |  touch '$failedOnce'
            |  mkdir -p .loop
            |  cat > .loop/result.json <<'JSON'
            |$doneResult
            |JSON
            |  exit 1
            |fi
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |  max_retry_backoff_ms: 1000
                |retries:
                |  max_attempts: 2
                |  max_continuations: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )

        run()
        assertTrue(fixture.workspace("t-1").resolve(".loop/result.json").exists(), "a failed turn does not drain, the file stays")
        val dueAt = Instant.parse(fixture.query("SELECT due_at FROM retry_states").single()["due_at"]!!)
        while (Clock.System.now() <= dueAt) Thread.sleep(POLL_MS)
        val second = run()

        assertEquals(2, events("attempt_started").size, second.toString())
        assertEquals("todo", fixture.stateOf("t-1"), second.toString())
        assertEquals(0, events("result_submitted").size, "a stale result is never submitted")
        assertEquals(0, events("check_started").size)
        val discarded = events("result_discarded").single()
        assertEquals(events("attempt_started").last()["attempt_id"], discarded["attempt_id"], "removed at the start of the retry")
        assertContains(discarded["data"].orEmpty(), "\"reason\":\"left by an earlier turn\"")
        assertFalse(fixture.workspace("t-1").resolve(".loop/result.json").exists())
        assertTrue(trace("t-1").any { "result_discarded" in it }, "the removal is traced")
        assertContains(fixture.humanLog.readText(), "removed a result file left by an earlier turn")
    }

    @Test
    fun `a result file in symphony mode is left alone`() {
        fixture.issue("t-1")
        resultAgent("""{ "status": "blocked", "summary": "x" }""")

        run()

        assertEquals(0, events("result_submitted").size + events("result_invalid").size)
        assertContains(events("attempt_finished").single()["data"].orEmpty(), "\"outcome\":\"succeeded\"")
        assertTrue(fixture.workspace("t-1").resolve(".loop/result.json").exists())
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
