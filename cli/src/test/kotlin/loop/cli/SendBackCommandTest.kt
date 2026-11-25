package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m4-32: `loop send-back <issue> --reason "<text>"` ends a `Waiting(approval)`, approved or not. The reason is kept in
 * the store, commented on the issue, recorded as `sent_back` and a `send_back` human touch, and the next attempt, in the
 * same workspace and with no `max_attempts` slot, renders it as `loop.verify_findings` with `loop.retry_kind` =
 * `sent_back`. The round trips run the command runner; the other tests seed the wait in the store.
 */
class SendBackCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val operator = mapOf("USER" to "soule")

    /** The prompt each attempt read on its stdin, numbered from 1. */
    private fun prompt(n: Int): Path = root.resolve("prompt-$n.txt")

    /**
     * Loop mode with one turn per attempt, delivery under the human policy, a check the agent passes, [extra] front
     * matter lines, and a template that shows the verify findings and the retry kind.
     */
    private fun loopMode(extra: String = "") {
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    delivery: { policy: human }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                |$extra
                """.trimMargin(),
            prompt =
                "Work on {{ issue.identifier }}: {{ issue.title }}\n" +
                    "{% if loop.retry_kind %}Retry kind: {{ loop.retry_kind }}\n{% endif %}" +
                    "{% if loop.verify_findings %}Findings:\n{{ loop.verify_findings }}\n{% endif %}",
        )
    }

    /** The agent: saves the prompt it read to [prompt], counts its call in the workspace, writes hello.txt, submits. */
    private fun savingAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${D}LOOP_ATTEMPT_ID" >> '${fixture.agentCallsFile}'
            |echo call >> calls.txt
            |n=$D(wc -l < calls.txt | tr -d ' ')
            |cat > '$root/prompt-'"${D}n"'.txt'
            |echo hello > hello.txt
            |loop tool move review
            |
            """.trimMargin(),
        )
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, it.toString()) }

    private fun sendBack(
        vararg args: String,
        env: Map<String, String?> = operator,
    ): LoopResult = fixture.inProcess("send-back", *args, "--workflow", fixture.workflow.toString(), env = env)

    private fun project(): ProjectId = ProjectId(fixture.projectId())

    private fun store(block: suspend (SqliteEventStore) -> Unit) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { runBlocking { block(it) } }
    }

    /** [identifier] waits under [issueId] for [reason], with the data Loop stores for it. */
    private fun waitFor(
        reason: WaitReason = WaitReason.Approval,
        issueId: String = "default:t-1",
        identifier: String = "t-1",
    ) = store {
        val data =
            when (reason) {
                WaitReason.Approval -> mapOf("to" to "review", "fingerprint" to "git:abc:e:e")
                WaitReason.Merge -> mapOf("to" to "review", "phase" to "check", "round" to "1", "pull_request_number" to "7")
                WaitReason.Ask -> mapOf("state" to "todo", "question_kind" to "product", "question" to "Question?")
                else -> mapOf("to" to "review")
            }
        it
            .startWaiting(
                WaitingIssue(project(), issueId, identifier, reason, AttemptId(ATTEMPT), null, Clock.System.now(), data),
            ).getOrThrow()
    }

    private fun waits(): List<Map<String, String?>> = fixture.query("SELECT issue_id, reason, data FROM waiting_issues")

    private fun events(kind: String): List<String> =
        fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map { it["data"].orEmpty() }

    private fun comments(id: String = "t-1"): String = fixture.issueFile(id).readText().substringAfter("## Comments", "")

    /** Asserts [result] failed with [code] and [message], and that nothing changed: the waits, the events, the comments. */
    private fun assertRefused(
        result: LoopResult,
        code: Int,
        message: String,
        before: List<Map<String, String?>>,
    ) {
        assertEquals(code, result.exitCode, result.toString())
        assertEquals(message, result.stderr.trim())
        assertEquals(before, waits())
        assertEquals(emptyList(), events("sent_back"))
        assertEquals(emptyList(), events("human_touch"))
        assertTrue("Sent back by" !in comments(), comments())
    }

    @Test
    fun `loop send-back ends an approval wait, comments the reason, and the next prompt carries it as verify findings`() {
        loopMode()
        fixture.issue("t-1")
        savingAgent()
        runOnce()
        assertEquals(listOf<String?>("approval"), waits().map { it["reason"] })

        val sent = sendBack("t-1", "--reason", "The button label is wrong, use Save.")

        assertEquals(0, sent.exitCode, sent.toString())
        assertEquals("Sent back t-1. Loop dispatches it again with the reason on its next tick.", sent.stdout.trim())
        assertContains(comments(), "Sent back by soule: The button label is wrong, use Save.")

        runOnce()

        assertEquals(2, fixture.agentCalls())
        val second = prompt(2)
        assertTrue(second.exists(), "the second attempt did not run in the kept workspace")
        val text = second.readText()
        assertContains(text, "Retry kind: sent_back")
        assertContains(text, "Findings:\nSent back by soule: The button label is wrong, use Save.")
        assertTrue("Retry kind" !in prompt(1).readText(), prompt(1).readText())
        // The send back ended its wait; the second attempt's verified result waits for approval again, not sent back.
        val wait = waits().single()
        assertEquals("approval", wait["reason"])
        assertTrue("sent_back" !in wait["data"].orEmpty(), wait.toString())
    }

    @Test
    fun `an approved wait is sent back too, and is never delivered`() {
        loopMode()
        fixture.issue("t-1")
        savingAgent()
        runOnce()
        assertEquals(0, fixture.inProcess("approve", "t-1", "--workflow", fixture.workflow.toString(), env = operator).exitCode)

        val sent = sendBack("t-1", "--reason", "Wrong approach.")

        assertEquals(0, sent.exitCode, sent.toString())
        assertTrue("approved_at" !in waits().single()["data"].orEmpty(), "the send back drops the approval")
        runOnce()
        assertEquals("todo", fixture.stateOf("t-1"), "the approved result was not delivered")
        assertEquals(2, fixture.agentCalls())
        assertContains(prompt(2).readText(), "Sent back by soule: Wrong approach.")
    }

    @Test
    fun `a send back takes no max_attempts slot, an issue with no slot left is sent back and dispatches again`() {
        loopMode("retries:\n  max_attempts: 1")
        fixture.issue("t-1")
        savingAgent()
        runOnce()
        assertEquals(1, fixture.agentCalls(), "the one attempt of max_attempts: 1 is spent")

        assertEquals(0, sendBack("t-1", "--reason", "Again.").exitCode)
        runOnce()

        assertEquals(2, fixture.agentCalls())
        assertEquals(emptyList(), events("issue_parked"))
        assertEquals(emptyList(), fixture.query("SELECT 1 FROM parked_issues"))
        assertEquals(emptyList(), events("retry_scheduled"), "the dispatch is no retry")
        assertContains(prompt(2).readText(), "Retry kind: sent_back")
    }

    @Test
    fun `sent_back and the send_back human touch are recorded, the touch counted in the next ledger row, and loop trace prints it`() {
        loopMode()
        fixture.issue("t-1")
        savingAgent()
        runOnce()

        assertEquals(0, sendBack("t-1", "--reason", "Use Save.").exitCode)

        val sent = events("sent_back").single()
        assertContains(sent, "\"by\":\"soule\"")
        assertContains(sent, "\"reason\":\"Use Save.\"")
        assertContains(events("human_touch").single(), "\"touch\":\"send_back\"")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        val line = trace.stdout.lines().singleOrNull { "sent_back" in it } ?: fail("no sent_back line\n${trace.stdout}")
        assertContains(line, "by=soule")
        assertContains(line, "Use Save.")

        runOnce()

        val touches = fixture.query("SELECT human_touches FROM attempts ORDER BY seq").map { it["human_touches"] }
        assertEquals(listOf<String?>("0", "1"), touches, "the next ledger row counts the send back")
    }

    @Test
    fun `a bare id finds the issue in the primary tracker and a namespaced id in its own tracker`() {
        fixture.projectDir.resolve("other").createDirectories()
        fixture.writeWorkflow(extra = "trackers:\n  - id: local\n    kind: files\n    provider:\n      dir: ./other\n")
        fixture.issue("t-1")
        waitFor()
        waitFor(issueId = "local:x-1", identifier = "x-1")

        val bare = sendBack("t-1", "--reason", "Primary.")
        val namespaced = sendBack("local:x-1", "--reason", "Extra.")
        val wrongTracker = sendBack("x-1", "--reason", "Primary has no x-1.")

        assertEquals(0, bare.exitCode, bare.toString())
        assertEquals("Sent back default:t-1. Loop dispatches it again with the reason on its next tick.", bare.stdout.trim())
        assertEquals(0, namespaced.exitCode, namespaced.toString())
        assertEquals("Sent back local:x-1. Loop dispatches it again with the reason on its next tick.", namespaced.stdout.trim())
        assertEquals(2, wrongTracker.exitCode, wrongTracker.toString())
        val reasons = waits().associate { it["issue_id"] to it["data"].orEmpty() }
        assertContains(reasons.getValue("default:t-1"), "\"sent_back_reason\":\"Primary.\"")
        assertContains(reasons.getValue("local:x-1"), "\"sent_back_reason\":\"Extra.\"")
    }

    @Test
    fun `loop send-back refuses to run inside an attempt, names the right command and changes nothing`() {
        fixture.issue("t-1")
        waitFor()
        val before = waits()

        val refused = sendBack("t-1", "--reason", "No.", env = operator + mapOf("LOOP_ATTEMPT_ID" to "attempt-1"))

        assertRefused(
            refused,
            5,
            "loop send-back: refused inside a Loop attempt, only an operator sends an issue back, " +
                "an agent hands its issue off with loop tool move. Nothing changed.",
            before,
        )
    }

    @Test
    fun `loop send-back on an issue not waiting fails with its own code, names the right command and changes nothing`() {
        fixture.issue("t-1")

        val idle = sendBack("t-1", "--reason", "No.")

        assertRefused(
            idle,
            4,
            "loop send-back: t-1 is not waiting for approval, loop status shows where it stands. Nothing changed.",
            waits(),
        )
    }

    @Test
    fun `loop send-back on a Waiting(merge) fails with its own code, names the right command and changes nothing`() {
        fixture.issue("t-1")
        waitFor(WaitReason.Merge)
        val before = waits()

        val merging = sendBack("t-1", "--reason", "No.")

        assertRefused(
            merging,
            7,
            "loop send-back: t-1 is waiting for its merge, not for approval, loop status shows its phase, " +
                "and a refused merge waits for approval again, which loop send-back ends. Nothing changed.",
            before,
        )
    }

    @Test
    fun `loop send-back on a Waiting(ask) fails with its own code, names loop answer and changes nothing`() {
        fixture.issue("t-1")
        waitFor(WaitReason.Ask)
        val before = waits()

        val asking = sendBack("t-1", "--reason", "No.")

        assertRefused(
            asking,
            8,
            "loop send-back: t-1 is waiting for an answer, not for approval, answer it with loop answer. Nothing changed.",
            before,
        )
    }

    @Test
    fun `a second send back, an empty reason and an unknown issue change nothing`() {
        fixture.issue("t-1")
        waitFor()
        assertEquals(0, sendBack("t-1", "--reason", "First.").exitCode)
        val after = waits()

        val again = sendBack("t-1", "--reason", "Second.")
        val empty = sendBack("t-1", "--reason", "   ")
        val unknown = sendBack("t-9", "--reason", "Who?")

        assertEquals(4, again.exitCode, again.toString())
        assertEquals(
            "loop send-back: t-1 is already sent back, Loop dispatches it with the first reason. Nothing changed.",
            again.stderr.trim(),
        )
        assertEquals(6, empty.exitCode, empty.toString())
        assertEquals("loop send-back: the reason is empty. Nothing changed.", empty.stderr.trim())
        assertEquals(2, unknown.exitCode, unknown.toString())
        assertContains(unknown.stderr, "loop send-back: no issue \"t-9\" in ")
        assertEquals(after, waits())
        assertEquals(1, events("sent_back").size)
    }

    private companion object {
        const val D = "$"
        const val ATTEMPT = "attempt-verified"
    }
}
