package loop.core.log

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class KeyValueLogTest {
    private val lines = mutableListOf<String>()
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-11-06T19:02:11Z")
        }
    private val log = KeyValueLog(out = { lines += it }, clock = clock, redactor = Redactor.NONE)

    private val fullContext =
        LogContext(
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            issueId = "i1",
            issueIdentifier = "m1-02",
            attemptId = AttemptId("a1"),
            sessionId = SessionId("s1"),
            turnId = TurnId("t1"),
        )

    @Test
    fun `a line starts with ts level event outcome`() {
        log.log(LogLevel.INFO, "poll", LogOutcome.IDLE)

        assertEquals("ts=2026-11-06T19:02:11Z level=info event=poll outcome=idle", lines.single())
    }

    @Test
    fun `ids follow the outcome in the fixed order`() {
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, fullContext)

        assertEquals(
            "ts=2026-11-06T19:02:11Z level=info event=attempt outcome=started " +
                "run_id=r1 project_id=p1 issue_id=i1 issue_identifier=m1-02 attempt_id=a1 session_id=s1 turn_id=t1",
            lines.single(),
        )
    }

    @Test
    fun `absent ids are omitted`() {
        log.log(LogLevel.INFO, "poll", LogOutcome.IDLE, LogContext(projectId = ProjectId("p1"), issueIdentifier = "m1-02"))

        assertEquals(
            "ts=2026-11-06T19:02:11Z level=info event=poll outcome=idle project_id=p1 issue_identifier=m1-02",
            lines.single(),
        )
    }

    @Test
    fun `event keys follow the ids in the order given`() {
        log.log(
            LogLevel.INFO,
            "poll",
            LogOutcome.IDLE,
            LogContext(projectId = ProjectId("p1")),
            fields = linkedMapOf("candidates" to 0, "active" to "todo"),
        )

        assertTrue(lines.single().endsWith("project_id=p1 candidates=0 active=todo"), lines.single())
    }

    @Test
    fun `null event keys are omitted`() {
        log.log(LogLevel.INFO, "poll", LogOutcome.IDLE, fields = linkedMapOf("candidates" to 0, "missing" to null))

        assertTrue(lines.single().endsWith("outcome=idle candidates=0"), lines.single())
    }

    @Test
    fun `reason comes last`() {
        log.log(
            LogLevel.ERROR,
            "attempt",
            LogOutcome.FAILED,
            fullContext,
            fields = linkedMapOf("exit_code" to 1),
            reason = "exit_code=1",
        )

        assertTrue(lines.single().endsWith("turn_id=t1 exit_code=1 reason=\"exit_code=1\""), lines.single())
    }

    @Test
    fun `a value with a space is quoted`() {
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, fields = linkedMapOf("title" to "walking skeleton"))

        assertTrue(lines.single().endsWith("title=\"walking skeleton\""), lines.single())
    }

    @Test
    fun `a value with a quote is quoted and the quote escaped`() {
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, fields = linkedMapOf("title" to "say \"hi\""))

        assertTrue(lines.single().endsWith("title=\"say \\\"hi\\\"\""), lines.single())
    }

    @Test
    fun `an empty value is quoted`() {
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, fields = linkedMapOf("title" to ""))

        assertTrue(lines.single().endsWith("title=\"\""), lines.single())
    }

    @Test
    fun `a value with a newline stays on one line`() {
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, fields = linkedMapOf("title" to "a\nb"))

        assertEquals(1, lines.single().lines().size)
        assertTrue(lines.single().endsWith("title=\"a\\nb\""), lines.single())
    }

    @Test
    fun `a multi line reason keeps only its first line`() {
        log.log(LogLevel.ERROR, "attempt", LogOutcome.FAILED, reason = "boom\n\tat Foo.bar(Foo.kt:1)")

        assertTrue(lines.single().endsWith("outcome=failed reason=boom"), lines.single())
    }

    @Test
    fun `a long reason is cut to 200 characters`() {
        log.log(LogLevel.ERROR, "attempt", LogOutcome.FAILED, reason = "x".repeat(500))

        assertTrue(lines.single().endsWith("reason=" + "x".repeat(200)), lines.single())
    }

    @Test
    fun `a sink that throws does not throw to the caller`() {
        log.guardSink("sqlite") { error("disk full") }

        assertEquals(
            "ts=2026-11-06T19:02:11Z level=warn event=sink outcome=failed sink=sqlite reason=\"disk full\"",
            lines.single(),
        )
    }

    @Test
    fun `a sink failure line carries the ids of its context`() {
        log.guardSink("sqlite", LogContext(projectId = ProjectId("p1"))) { error("disk full") }

        assertTrue(lines.single().contains("outcome=failed project_id=p1 sink=sqlite"), lines.single())
    }

    @Test
    fun `a sink that succeeds logs nothing`() {
        var written = false

        log.guardSink("sqlite") { written = true }

        assertTrue(written)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun `a secret in a field or past the reason cut is never printed, even in part`() {
        val secret = "s3cr3tvalue0123456789"
        val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
        val redacting = KeyValueLog(out = { lines += it }, clock = clock, redactor = redactor)

        redacting.log(
            LogLevel.ERROR,
            "attempt",
            LogOutcome.FAILED,
            fields = mapOf("command" to "curl -H $secret"),
            reason = "x".repeat(190) + secret,
        )

        val line = lines.single()
        assertFalse(line.contains(secret.take(8)), line)
        assertTrue(line.contains("command=\"curl -H [redacted:LOOP_TRACKER_TOKEN]\""), line)
    }

    @Test
    fun `a throwing redactor never stops logging and never prints the raw text`() {
        val broken = KeyValueLog(out = { lines += it }, clock = clock, redactor = { error("redactor bug") })

        broken.guardSink("sqlite") { error("disk full s3cr3t") }
        broken.log(LogLevel.INFO, "hook", LogOutcome.COMPLETED, fields = mapOf("hook" to "s3cr3t"))

        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines[0].endsWith("sink=[redacted] reason=[redacted]"), lines[0])
        assertTrue(lines[1].endsWith("hook=[redacted]"), lines[1])
    }

    @Test
    fun `a redactor that overflows its stack never stops logging`() {
        val broken = KeyValueLog(out = { lines += it }, clock = clock, redactor = { throw StackOverflowError() })

        broken.log(LogLevel.INFO, "hook", LogOutcome.COMPLETED, fields = mapOf("hook" to "s3cr3t"))

        assertTrue(lines.single().endsWith("hook=[redacted]"), lines.single())
    }
}
