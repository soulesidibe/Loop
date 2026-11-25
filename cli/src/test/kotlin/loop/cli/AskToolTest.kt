package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m4-31: `ask`, a question a human must answer, end to end. The agents are scripts that speak JSON-RPC to the real
 * `loop tool mcp` over stdio, as in [ToolMcpCommandTest], so the call runs without a model. The question is queued,
 * answered `pending`, and when the turn ends Loop posts it on the issue, ends the attempt with no verification, keeps
 * the workspace and the issue waits as `Waiting(ask)`, notified once on the channel of m4-30.
 */
class AskToolTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the hook appends each notice it read on stdin. */
    private val notices: Path by lazy { root.resolve("notices.jsonl") }

    /** The tool and the reply of each call the agent made, one line each. */
    private val transcript: Path by lazy { root.resolve("transcript.txt") }

    private val question =
        """{"kind":"product","question":"Should the export include archived items?",""" +
            """"options":["Include them","Leave them out"],"recommendation":"Leave them out, the issue lists active items only."}"""

    /**
     * Loop mode with a check the agents here never pass, so a verification would fail the attempt, the hook that
     * records each notice, and [extra] lines.
     */
    private fun loopMode(extra: String = "") {
        fixture.writeWorkflow(
            activeStates = listOf("todo", "in_progress"),
            extra =
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f never.txt" }
                |hooks:
                |  on_human_needed: "cat >> '$notices'; echo >> '$notices'"
                |$extra
                """.trimMargin(),
        )
        fixture.issue("t-1")
    }

    /**
     * Writes the agent: [before] runs first, then [body] runs with a `loop tool mcp` session open on stdio, through
     * `call TOOL ARGUMENTS`, which waits for the reply and appends the tool and the reply to [transcript]; `finish`
     * closes the session.
     */
    private fun agent(
        body: String,
        before: String = "",
    ) {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${D}LOOP_ATTEMPT_ID" >> '${fixture.agentCallsFile}'
            |$before
            |replies=$D(mktemp)
            |rpc=$D(mktemp -d)
            |mkfifo "${D}rpc/in"
            |loop tool mcp < "${D}rpc/in" > "${D}replies" &
            |exec 3> "${D}rpc/in"
            |printf '%s\n' '$INITIALIZE' '$INITIALIZED' >&3
            |id=1
            |call() {
            |  id=$D((id + 1))
            |  printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}\n' "${D}id" "${D}1" "${D}2" >&3
            |  n=0
            |  until grep -Eq "\"id\":${D}id[,}]" "${D}replies"; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 9; sleep 0.1; done
            |  echo "${D}1 $D(grep -E "\"id\":${D}id[,}]" "${D}replies")" >> '$transcript'
            |}
            |finish() { exec 3>&-; wait; }
            |$body
            |
            """.trimMargin(),
        )
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, "$it\n${transcriptText()}") }

    private fun transcriptText(): String = if (transcript.exists()) transcript.readText() else "(no transcript)"

    /** The JSON object each `ask` call answered, in order. */
    private fun askAnswers(): List<JsonObject> =
        transcript.readLines().filter { it.startsWith("ask ") }.map { line ->
            val reply = Json.parseToJsonElement(line.removePrefix("ask ")).jsonObject
            val result = reply.getValue("result").jsonObject
            val text = result.getValue("content") as JsonArray
            val answer =
                Json
                    .parseToJsonElement(
                        text
                            .first()
                            .jsonObject
                            .getValue("text")
                            .jsonPrimitive.content,
                    ).jsonObject
            JsonObject(answer + ("isError" to (result["isError"] ?: JsonPrimitive(false))))
        }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun events(kind: String): List<String> =
        fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map { it["data"].orEmpty() }

    private fun hookRuns(): List<JsonObject> =
        if (!notices.exists()) {
            emptyList()
        } else {
            notices.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        }

    private fun waitReasons(): List<String?> = fixture.query("SELECT reason FROM waiting_issues").map { it["reason"] }

    private fun moveIssue(to: String) {
        val file = fixture.issueFile("t-1")
        file.writeText(file.readText().replace(Regex("(?m)^state: .*$"), "state: $to"))
    }

    @Test
    fun `an ask over loop tool mcp ends the attempt unverified, comments the question, notifies once and waits as Waiting(ask)`() {
        loopMode()
        agent(
            """
            |echo draft > draft.txt
            |call ask '$question'
            |finish
            """.trimMargin(),
        )

        val run = runOnce()

        val answer = askAnswers().single()
        assertEquals("pending", answer.text("status"), answer.toString())
        assertContains(answer.text("message").orEmpty(), "End your turn now")
        assertEquals(listOf<String?>("ask"), waitReasons())
        val finished = events("attempt_finished").single()
        assertContains(finished, "\"exit_reason\":\"waiting_ask\"")
        assertEquals(emptyList(), events("verification_started"), run.toString())
        assertEquals(emptyList(), events("check_finished"))
        assertTrue(fixture.workspace("t-1").resolve("draft.txt").exists(), "the workspace is kept")
        assertEquals("todo", fixture.stateOf("t-1"))

        val comments = fixture.issueFile("t-1").readText().substringAfter("## Comments")
        assertContains(comments, "Question for a human (product): Should the export include archived items?")
        assertContains(comments, "Options:")
        assertContains(comments, "- Include them")
        assertContains(comments, "- Leave them out")
        assertContains(comments, "Recommendation: Leave them out, the issue lists active items only.")

        val notice = hookRuns().single()
        assertEquals("question", notice.text("event"))
        assertEquals("t-1", notice.text("identifier"))
        assertEquals("loop answer t-1", notice.text("command"))
        assertEquals(1, events("human_notified").size)
        assertEquals(1, run.stderr.lines().count { it.startsWith("loop run: t-1 needs you, question: ") }, run.toString())

        // A later tick leaves the waiting issue alone and does not notify again.
        runOnce()
        assertEquals(1, fixture.agentCalls())
        assertEquals(1, hookRuns().size)

        val status = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, status.exitCode, status.toString())
        val line = status.stdout.lines().singleOrNull { it.startsWith("waiting") } ?: fail("no waiting line\n$status")
        assertContains(line, "t-1")
        assertContains(line, "reason=ask")
    }

    @Test
    fun `an ask takes no max_attempts slot and does not count toward max_continuations`() {
        loopMode("agent:\n  max_retry_backoff_ms: 1000\nretries:\n  max_attempts: 2\n  max_continuations: 1")
        val failed = root.resolve("failed-once")
        agent(
            """
            |call ask '$question'
            |finish
            """.trimMargin(),
            before = "if [ ! -f '$failed' ]; then touch '$failed'; exit 1; fi",
        )

        runOnce()
        assertEquals("1", fixture.query("SELECT failures FROM retry_states").single()["failures"])
        val dueAt = Instant.parse(fixture.query("SELECT due_at FROM retry_states").single()["due_at"]!!)
        while (Clock.System.now() <= dueAt) Thread.sleep(POLL_MS)

        runOnce()

        assertEquals(2, fixture.agentCalls(), transcriptText())
        assertEquals(listOf<String?>("ask"), waitReasons())
        assertEquals(emptyList(), fixture.query("SELECT * FROM parked_issues"))
        assertEquals(emptyList(), events("issue_parked"))
        // The failure of the first attempt is still the one counted: the ask neither counted nor reset it.
        val counters = fixture.query("SELECT attempt, failures, no_progress FROM retry_states").single()
        assertEquals("1", counters["failures"], counters.toString())
        assertEquals("0", counters["no_progress"], counters.toString())
        assertEquals(null, counters["attempt"], "no continuation is queued behind the ask: $counters")
    }

    @Test
    fun `a human move of the issue ends the ask wait and the issue is dispatched again`() {
        loopMode()
        val asked = root.resolve("asked-once")
        agent(
            """
            |touch '$asked'
            |call ask '$question'
            |finish
            """.trimMargin(),
            before = "if [ -f '$asked' ]; then exit 1; fi",
        )
        runOnce()
        assertEquals(listOf<String?>("ask"), waitReasons())

        moveIssue("in_progress")
        runOnce()

        assertEquals(emptyList(), waitReasons())
        val touched = events("human_touch").single()
        assertContains(touched, "\"touch\":\"move\"")
        assertContains(touched, "\"from_state\":\"todo\"")
        assertContains(touched, "\"to_state\":\"in_progress\"")
        assertEquals(2, fixture.agentCalls(), transcriptText())
    }

    @Test
    fun `a second ask in one attempt is a structured failure and only the first question is posted`() {
        loopMode()
        agent(
            """
            |call ask '$question'
            |call ask '{"kind":"design","question":"Which colour for the button?"}'
            |finish
            """.trimMargin(),
        )

        runOnce()

        val answers = askAnswers()
        assertEquals(listOf("pending", "error"), answers.map { it.text("status") }, answers.toString())
        val second = answers[1]
        assertEquals("already_asked", second.text("error"))
        assertEquals("true", second.text("isError"))
        assertContains(second.text("message").orEmpty(), "Nothing queued.")
        val comments = fixture.issueFile("t-1").readText().substringAfter("## Comments")
        assertContains(comments, "Should the export include archived items?")
        assertTrue("Which colour" !in comments, comments)
        assertEquals(1, events("question_asked").size)
        assertEquals(listOf<String?>("ask"), waitReasons())
    }

    @Test
    fun `question_asked is recorded with its kind and options and printed by loop trace`() {
        loopMode()
        agent(
            """
            |call ask '$question'
            |finish
            """.trimMargin(),
        )

        runOnce()

        val asked = events("question_asked").single()
        assertContains(asked, "\"question_kind\":\"product\"")
        assertContains(asked, "\"options\":\"2\"")
        assertTrue("archived" !in asked, "the event never holds the question text: $asked")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        val line = trace.stdout.lines().singleOrNull { "question_asked" in it } ?: fail("no question_asked line\n${trace.stdout}")
        assertContains(line, "question_kind=product")
        assertContains(line, "options=2")
    }

    private companion object {
        const val D = "$"
        const val POLL_MS = 100L

        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
        const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    }
}
