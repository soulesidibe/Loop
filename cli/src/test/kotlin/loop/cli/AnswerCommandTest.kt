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
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m4-52: `loop answer <issue> "<text>"` ends a `Waiting(ask)`. The answer is kept in the store, commented on the issue,
 * recorded as `question_answered` and a human touch, and the next attempt, in the same workspace, renders the question
 * and the answer as `loop.answer` with `loop.retry_kind` = `answer`. The agent of the round trip asks over the real
 * `loop tool mcp`, as in [AskToolTest]; the other tests seed the wait in the store.
 */
class AnswerCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val operator = mapOf("USER" to "soule")

    private val question =
        """{"kind":"product","question":"Should the export include archived items?",""" +
            """"options":["Include them","Leave them out"],"recommendation":"Leave them out."}"""

    /** The prompt the second attempt read on its stdin. */
    private val secondPrompt: Path by lazy { root.resolve("prompt-2.txt") }

    /** Loop mode with one turn per attempt, a check the agents never pass, and a template that shows the answer and the retry kind. */
    private fun loopMode(extra: String = "") {
        fixture.writeWorkflow(
            activeStates = listOf("todo", "in_progress"),
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f never.txt" }
                |$extra
                """.trimMargin(),
            prompt =
                "Work on {{ issue.identifier }}: {{ issue.title }}\n" +
                    "{% if loop.retry_kind %}Retry kind: {{ loop.retry_kind }}\n{% endif %}" +
                    "{% if loop.answer %}Your question and its answer:\n{{ loop.answer }}\n{% endif %}",
        )
    }

    /**
     * The agent: its first attempt writes a draft and asks [question] over `loop tool mcp`, the next one saves the prompt
     * it read on stdin to [secondPrompt] and ends.
     */
    private fun askingAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${D}LOOP_ATTEMPT_ID" >> '${fixture.agentCallsFile}'
            |if [ -f draft.txt ]; then
            |  cat > '$secondPrompt'
            |  exit 0
            |fi
            |echo draft > draft.txt
            |replies=$D(mktemp)
            |rpc=$D(mktemp -d)
            |mkfifo "${D}rpc/in"
            |loop tool mcp < "${D}rpc/in" > "${D}replies" &
            |exec 3> "${D}rpc/in"
            |printf '%s\n' '$INITIALIZE' '$INITIALIZED' >&3
            |printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask","arguments":%s}}\n' '$question' >&3
            |n=0
            |until grep -Eq '"id":2[,}]' "${D}replies"; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 9; sleep 0.1; done
            |exec 3>&-
            |wait
            |
            """.trimMargin(),
        )
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, it.toString()) }

    private fun answer(
        vararg args: String,
        env: Map<String, String?> = operator,
    ): LoopResult = fixture.inProcess("answer", *args, "--workflow", fixture.workflow.toString(), env = env)

    private fun project(): ProjectId = ProjectId(fixture.projectId())

    private fun store(block: suspend (SqliteEventStore) -> Unit) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { runBlocking { block(it) } }
    }

    /** [identifier] waits as `Waiting(ask)` under [issueId], as the store holds it since m4-31, with the question as posted. */
    private fun waitForAnswer(
        issueId: String = "default:t-1",
        identifier: String = "t-1",
        reason: WaitReason = WaitReason.Ask,
    ) = store {
        val data =
            if (reason == WaitReason.Ask) {
                mapOf("state" to "todo", "question_kind" to "product", "question" to "Question for a human (product): Archived items?")
            } else {
                mapOf("to" to "review")
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

    @Test
    fun `loop answer ends the wait and the next attempt's first prompt carries the question, the answer and retry kind answer`() {
        loopMode()
        fixture.issue("t-1")
        askingAgent()
        runOnce()
        assertEquals(listOf<String?>("ask"), waits().map { it["reason"] })

        val answered = answer("t-1", "Leave them out, only active items.")

        assertEquals(0, answered.exitCode, answered.toString())
        assertEquals("Answered t-1. Loop dispatches it again with the answer on its next tick.", answered.stdout.trim())
        assertContains(comments(), "Answer from soule: Leave them out, only active items.")

        runOnce()

        assertEquals(2, fixture.agentCalls())
        assertTrue(secondPrompt.exists(), "the second attempt did not run in the kept workspace")
        val prompt = secondPrompt.readText()
        assertContains(prompt, "Retry kind: answer")
        assertContains(prompt, "Question for a human (product): Should the export include archived items?")
        assertContains(prompt, "- Leave them out")
        assertContains(prompt, "Answer from soule: Leave them out, only active items.")
        assertEquals(emptyList(), waits(), "the answer ended the wait")
    }

    @Test
    fun `loop answer refuses to run inside an attempt and changes nothing`() {
        fixture.issue("t-1")
        waitForAnswer()
        val before = waits()

        val refused = answer("t-1", "Yes.", env = operator + mapOf("LOOP_ATTEMPT_ID" to "attempt-1"))

        assertEquals(5, refused.exitCode, refused.toString())
        assertEquals(
            "loop answer: refused inside a Loop attempt, only an operator answers a question. Nothing changed.",
            refused.stderr.trim(),
        )
        assertEquals(before, waits())
        assertEquals(emptyList(), events("question_answered"))
        assertEquals(emptyList(), events("human_touch"))
        assertTrue("Answer from" !in comments(), comments())
    }

    @Test
    fun `loop answer on an issue not waiting for an answer fails with its own code and changes nothing`() {
        fixture.issue("t-1")

        val idle = answer("t-1", "Yes.")

        assertEquals(4, idle.exitCode, idle.toString())
        assertEquals("loop answer: t-1 is not waiting for an answer. Nothing changed.", idle.stderr.trim())

        waitForAnswer(reason = WaitReason.Approval)
        val approval = answer("t-1", "Yes.")

        assertEquals(4, approval.exitCode, approval.toString())
        assertEquals("loop answer: t-1 is waiting for approval, not for an answer. Nothing changed.", approval.stderr.trim())
        assertEquals(listOf<String?>("approval"), waits().map { it["reason"] })
        assertTrue("answer" !in waits().single()["data"].orEmpty())
        assertEquals(emptyList(), events("question_answered"))
        assertTrue("Answer from" !in comments(), comments())
    }

    @Test
    fun `loop answer on an unknown issue fails with its own code and changes nothing`() {
        fixture.issue("t-1")
        waitForAnswer()
        val before = waits()

        val unknown = answer("t-9", "Yes.")

        assertEquals(2, unknown.exitCode, unknown.toString())
        assertContains(unknown.stderr, "loop answer: no issue \"t-9\" in ")
        assertEquals(before, waits())
        assertEquals(emptyList(), events("question_answered"))
    }

    @Test
    fun `a second answer and an empty answer change nothing`() {
        fixture.issue("t-1")
        waitForAnswer()
        assertEquals(0, answer("t-1", "Leave them out.").exitCode)
        val after = waits()

        val again = answer("t-1", "Include them.")
        val empty = answer("t-1", "   ")

        assertEquals(4, again.exitCode, again.toString())
        assertEquals(
            "loop answer: t-1 is already answered, Loop dispatches it with the first answer. Nothing changed.",
            again.stderr.trim(),
        )
        assertEquals(6, empty.exitCode, empty.toString())
        assertEquals("loop answer: the answer is empty. Nothing changed.", empty.stderr.trim())
        assertEquals(after, waits())
        assertEquals(1, events("question_answered").size)
    }

    @Test
    fun `a bare id finds the issue in the primary tracker and a namespaced id in its own tracker`() {
        val other = fixture.projectDir.resolve("other").createDirectories()
        fixture.writeWorkflow(extra = "trackers:\n  - id: local\n    kind: files\n    provider:\n      dir: ./other\n")
        // bl-221: x-1 is a real issue of the second tracker, so its comment lands there.
        val extra = fixture.issue("x-1").moveTo(other.resolve("x-1.md"))
        fixture.issue("t-1")
        waitForAnswer()
        waitForAnswer(issueId = "local:x-1", identifier = "x-1")

        val bare = answer("t-1", "Primary.")
        val namespaced = answer("local:x-1", "Extra.")
        val wrongTracker = answer("x-1", "Primary has no x-1.")

        assertEquals(0, bare.exitCode, bare.toString())
        assertEquals("Answered default:t-1. Loop dispatches it again with the answer on its next tick.", bare.stdout.trim())
        assertEquals(0, namespaced.exitCode, namespaced.toString())
        assertEquals("Answered local:x-1. Loop dispatches it again with the answer on its next tick.", namespaced.stdout.trim())
        assertEquals(2, wrongTracker.exitCode, wrongTracker.toString())
        val answers = waits().associate { it["issue_id"] to it["data"].orEmpty() }
        assertContains(answers.getValue("default:t-1"), "\"answer\":\"Primary.\"")
        assertContains(answers.getValue("local:x-1"), "\"answer\":\"Extra.\"")
        assertContains(comments(), "Answer from soule: Primary.")
        assertContains(extra.readText().substringAfter("## Comments"), "Answer from soule: Extra.")
        assertTrue("not commented" !in namespaced.stderr, namespaced.toString())
    }

    @Test
    fun `bl-221 an answer over the size limit is refused with its own code and changes nothing`() {
        fixture.issue("t-1")
        waitForAnswer()
        val before = waits()

        val long = answer("t-1", "a".repeat(4_001))

        assertEquals(7, long.exitCode, long.toString())
        assertEquals("loop answer: the answer is 4001 characters, over the limit of 4000. Nothing changed.", long.stderr.trim())
        assertEquals(before, waits())
        assertEquals(emptyList(), events("question_answered"))
        assertEquals(emptyList(), events("human_touch"))
        assertTrue("Answer from" !in comments(), comments())
        // An answer that starts with a dash is taken after `--`, as the docs say.
        val dash = fixture.inProcess("answer", "--workflow", fixture.workflow.toString(), "t-1", "--", "-1 is the answer.", env = operator)
        assertEquals(0, dash.exitCode, dash.toString())
        assertContains(comments(), "Answer from soule: -1 is the answer.")
    }

    @Test
    fun `question_answered and the human touch are recorded, and loop trace prints the event`() {
        fixture.issue("t-1")
        waitForAnswer()

        assertEquals(0, answer("t-1", "Leave them out.").exitCode)

        val answered = events("question_answered").single()
        assertContains(answered, "\"by\":\"soule\"")
        assertContains(answered, "\"chars\":\"15\"")
        assertTrue("Leave" !in answered, "the event never holds the answer: $answered")
        assertContains(events("human_touch").single(), "\"touch\":\"answer\"")
        val attempts = fixture.query("SELECT attempt_id FROM events WHERE kind = 'question_answered'").single()
        assertEquals(ATTEMPT, attempts["attempt_id"])

        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        val line = trace.stdout.lines().singleOrNull { "question_answered" in it } ?: fail("no question_answered line\n${trace.stdout}")
        assertContains(line, "by=soule")
        assertContains(line, "chars=15")
    }

    private companion object {
        const val D = "$"
        const val ATTEMPT = "attempt-asked"

        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
        const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    }
}
