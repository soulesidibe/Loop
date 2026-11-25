package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-30, end to end with the command runner: a park and an approval wait each print one line on the `loop run` terminal
 * and run `hooks.on_human_needed` once with the notice as JSON on stdin. The same wait never notifies twice, across ticks
 * or a restart, a new park after `loop resume` notifies again, and a failing or hanging hook records `hook_failed` while
 * the run goes on.
 */
class HumanNoticeTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the hook appends what it read on stdin, one notice per line. */
    private val notices: Path by lazy { root.resolve("notices.jsonl") }

    /** The hook that writes its stdin to [notices], one line per run. */
    private val recordingHook: String get() = "cat >> '$notices'; echo >> '$notices'"

    private fun hooks(
        hook: String = recordingHook,
        timeoutMs: Long? = null,
    ): String =
        "hooks:\n  on_human_needed: \"$hook\"" + timeoutMs?.let { "\n  timeout_ms: $it" }.orEmpty()

    /** A failing agent and `retries.max_attempts: 1`, so the first failure parks the issue, with [hooks]. */
    private fun parkOnFirstFailure(hooks: String = hooks()) {
        fixture.useFailingAgent()
        fixture.writeWorkflow(extra = "retries:\n  max_attempts: 1\n$hooks")
        fixture.issue("t-1")
    }

    /** Loop mode with a check the default agent passes, under the human delivery policy, with [hooks]. */
    private fun approvalWait(hooks: String = hooks()) {
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
                |$hooks
                """.trimMargin(),
        )
        fixture.issue("t-1")
    }

    private fun runOnce(): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once").also { assertEquals(0, it.exitCode, it.toString()) }

    /** The notices the hook read, one JSON object each. */
    private fun hookRuns(): List<JsonObject> =
        if (!notices.exists()) {
            emptyList()
        } else {
            notices.readText().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        }

    /** The notice lines the terminal channel printed on stderr. */
    private fun terminalLines(result: LoopResult): List<String> = result.stderr.lines().filter { it.startsWith("loop run: ") && " needs you, " in it }

    private fun events(kind: String): List<String> = fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map { it["data"].orEmpty() }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    @Test
    fun `a park prints one terminal line and runs the hook once with the notice JSON on stdin`() {
        parkOnFirstFailure()

        val run = runOnce()

        val line = terminalLines(run).single()
        assertTrue(line.startsWith("loop run: t-1 needs you, parked: max_attempts"), line)
        assertTrue(line.endsWith(". Next: loop resume t-1"), line)
        val notice = hookRuns().single()
        assertEquals("default:t-1", notice.text("issue_id"))
        assertEquals("t-1", notice.text("identifier"))
        assertEquals("parked", notice.text("event"))
        assertTrue(notice.text("reason").orEmpty().startsWith("max_attempts"), notice.toString())
        assertEquals("loop resume t-1", notice.text("command"))
        assertEquals(null, notice.text("url"))
        assertTrue(notice.text("at").orEmpty().isNotEmpty(), notice.toString())
        assertContains(events("human_notified").single(), "\"event\":\"parked\"")
        assertContains(events("hook_finished").single(), "\"hook\":\"on_human_needed\"")
        assertEquals(emptyList(), events("hook_failed"))
    }

    @Test
    fun `an approval wait prints one terminal line and runs the hook once with the notice JSON on stdin`() {
        approvalWait()

        val run = runOnce()

        assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"])
        val line = terminalLines(run).single()
        assertTrue(line.startsWith("loop run: t-1 needs you, approval_waiting: "), line)
        assertTrue(line.endsWith(". Next: loop approve t-1"), line)
        val notice = hookRuns().single()
        assertEquals("approval_waiting", notice.text("event"))
        assertEquals("t-1", notice.text("identifier"))
        assertEquals("loop approve t-1", notice.text("command"))
        assertContains(events("human_notified").single(), "\"channel\":\"terminal\"")
    }

    @Test
    fun `the same approval wait does not notify again across ticks or across a restart of loop run`() {
        approvalWait()
        fixture.workflow.writeText(fixture.workflow.readText().replace("interval_ms: 1000", "interval_ms: $POLL_INTERVAL_MS"))
        runOnce()
        assertEquals(1, hookRuns().size)

        // A restart into a long running loop, which keeps the issue waiting across many ticks.
        val logged = fixture.humanLog.readText().length
        val running = fixture.start("run", fixture.workflow.toString())
        val ticks =
            try {
                val deadline = System.nanoTime() + WAIT_NANOS
                while (fixture.humanLog.readText().length == logged && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
                Thread.sleep(POLLS * POLL_INTERVAL_MS)
                running
            } finally {
                running.process.destroy()
            }.await(30)
        // And one more restart.
        val again = runOnce()

        assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"])
        assertEquals(emptyList(), terminalLines(ticks), ticks.toString())
        assertEquals(emptyList(), terminalLines(again), again.toString())
        assertEquals(1, hookRuns().size, hookRuns().toString())
        assertEquals(1, events("human_notified").size)
    }

    @Test
    fun `a new park after loop resume notifies again, and a restart in between does not`() {
        parkOnFirstFailure()
        runOnce()
        val restart = runOnce()
        assertEquals(emptyList(), terminalLines(restart), restart.toString())
        assertEquals(1, hookRuns().size)

        val resume = fixture.inProcess("resume", "t-1", cwd = fixture.projectDir)
        assertEquals(0, resume.exitCode, resume.toString())
        val second = runOnce()

        assertEquals(1, terminalLines(second).size, second.toString())
        assertEquals(listOf("parked", "parked"), hookRuns().map { it.text("event") })
        assertEquals(2, events("human_notified").size)
    }

    @Test
    fun `a failing hook records hook_failed and the run goes on`() {
        parkOnFirstFailure(hooks("echo nope; exit 3"))

        val run = runOnce()

        assertEquals(1, terminalLines(run).size, run.toString())
        assertEquals(1, fixture.query("SELECT issue_id FROM parked_issues").size)
        val failed = events("hook_failed").single()
        assertContains(failed, "\"hook\":\"on_human_needed\"")
        assertContains(failed, "\"exit_code\":\"3\"")
        assertContains(failed, "\"timed_out\":\"false\"")
        assertContains(run.stdout, "1 attempt")
    }

    @Test
    fun `a hanging hook is stopped at the hooks timeout, records hook_failed and the run goes on`() {
        parkOnFirstFailure(hooks("sleep 60", timeoutMs = 500))
        val started = System.nanoTime()

        val run = runOnce()

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(50), "the run waited for the hanging hook")
        assertEquals(1, terminalLines(run).size, run.toString())
        val failed = events("hook_failed").single()
        assertContains(failed, "\"timed_out\":\"true\"")
        assertContains(failed, "timed out after 500 ms")
        assertContains(run.stdout, "1 attempt")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val POLLS = 15
        const val POLL_MS = 50L
        val WAIT_NANOS = TimeUnit.SECONDS.toNanos(40)
    }
}
