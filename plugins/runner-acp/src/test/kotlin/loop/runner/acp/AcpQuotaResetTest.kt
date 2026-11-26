package loop.runner.acp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * bl-215: an exhausted Gemini quota gives the hold the quota's reset time. The bodies follow the 429 that `agy`'s own
 * CLI logged on 2026-10-19: `RESOURCE_EXHAUSTED` with an `ErrorInfo` of reason `QUOTA_EXHAUSTED` and a `RetryInfo`.
 */
class AcpQuotaResetTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private fun logged429(
        resetTimeStamp: String? = RESET_AT.toString(),
        resetDelay: String? = RESET_DELAY_TEXT,
        retryDelay: String? = "15807.716645894s",
        model: String? = MODEL,
        message: String = QUOTA_MESSAGE,
    ): String {
        val metadata =
            buildList {
                add("\"uiMessage\":\"true\"")
                add("\"consumer\":\"projects/$ACCOUNT_ID\"")
                add("\"email\":\"$EMAIL\"")
                model?.let { add("\"model\":\"$it\"") }
                resetDelay?.let { add("\"quotaResetDelay\":\"$it\"") }
                resetTimeStamp?.let { add("\"quotaResetTimeStamp\":\"$it\"") }
            }.joinToString(",")
        val retryInfo =
            retryDelay?.let { ",{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"$it\"}" }.orEmpty()
        return """
            {"error":{"code":429,"message":"$message","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"QUOTA_EXHAUSTED",
            "domain":"cloudcode-pa.googleapis.com","metadata":{$metadata}}$retryInfo]}}
            """.trimIndent().replace("\n", "")
    }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun classify(
        message: String,
        data: JsonElement?,
    ) = AcpErrors.classify(AcpHandshake.METHOD_SESSION_PROMPT, -32000, message, data, now = NOW)

    @Test
    fun `details in the error data give the reset timestamp minus now, and the model`() {
        val result = classify("Resource exhausted", parse(logged429()))

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, result.category)
        assertEquals(RESET_DELAY, result.retryAfter)
        val reset = assertNotNull(result.quotaReset)
        assertEquals(RESET_AT, reset.resetsAt)
        assertEquals(MODEL, reset.model)
    }

    @Test
    fun `the body nested in a string field is read too`() {
        val data =
            buildJsonObject {
                put("error", "got status: 429 Too Many Requests. ${logged429()}")
            }

        val result = classify("Resource exhausted", data)

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, result.category)
        assertEquals(RESET_DELAY, result.retryAfter)
        assertEquals(MODEL, result.quotaReset?.model)
    }

    @Test
    fun `the body as an array in a string, as Google sends it, is read too`() {
        val data = buildJsonObject { put("details", "[${logged429()}]") }

        assertEquals(RESET_DELAY, classify("Resource exhausted", data).retryAfter)
    }

    @Test
    fun `the message text only gives the duration it names`() {
        val result = classify("429 Too Many Requests: $QUOTA_MESSAGE", null)

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, result.category)
        assertEquals(4.hours + 23.minutes + 27.seconds, result.retryAfter)
        val reset = assertNotNull(result.quotaReset)
        assertEquals(NOW + 4.hours + 23.minutes + 27.seconds, reset.resetsAt)
        assertNull(reset.model)
    }

    @Test
    fun `a past timestamp is ignored and the delay is read next`() {
        val result = classify("Resource exhausted", parse(logged429(resetTimeStamp = "2026-10-19T10:00:00Z")))

        assertEquals(RESET_DELAY, result.retryAfter)
    }

    @Test
    fun `with no reset field the message inside the body is read last`() {
        val data = parse(logged429(resetTimeStamp = "2026-10-19T10:00:00Z", resetDelay = null, retryDelay = null))

        assertEquals(4.hours + 23.minutes + 27.seconds, classify("Resource exhausted", data).retryAfter)
    }

    @Test
    fun `a past timestamp with nothing else keeps today's behavior`() {
        val data =
            parse(
                logged429(
                    resetTimeStamp = "2026-10-19T10:00:00Z",
                    resetDelay = null,
                    retryDelay = null,
                    message = NO_RESET_MESSAGE,
                ),
            )

        val result = classify("Resource exhausted", data)

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, result.category)
        assertNull(result.retryAfter)
        assertNull(result.quotaReset)
    }

    @Test
    fun `an unparsable timestamp or delay is ignored and the RetryInfo delay is read next`() {
        val data = parse(logged429(resetTimeStamp = "tomorrow", resetDelay = "soon"))

        assertEquals(15807.seconds + 716_645_894.nanoseconds, classify("Resource exhausted", data).retryAfter)
    }

    @Test
    fun `no reset at all keeps today's behavior`() {
        val data = parse(logged429(resetTimeStamp = null, resetDelay = null, retryDelay = null, message = NO_RESET_MESSAGE))

        val result = classify("Resource exhausted", data)

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, result.category)
        assertNull(result.retryAfter)
        assertNull(result.quotaReset)
        assertNull(classify("429 Too Many Requests", null).retryAfter)
    }

    @Test
    fun `an explicit retry key still comes first`() {
        val data = parse(logged429()) as JsonObject
        val withKey = JsonObject(data + mapOf("retryAfterSeconds" to Json.parseToJsonElement("30")))

        assertEquals(30.seconds, classify("Too many requests", withKey).retryAfter)
    }

    @Test
    fun `a quota reset is never read from an error that is not unavailability`() {
        val result = classify("Internal error. Your quota will reset after 4h.", null)

        assertNull(result.category)
        assertNull(result.retryAfter)
        assertNull(result.quotaReset)
    }

    @Test
    fun `a model that is not a plain id is dropped`() {
        val result = classify("Resource exhausted", parse(logged429(model = EMAIL)))

        assertEquals(RESET_DELAY, result.retryAfter)
        assertNull(result.quotaReset?.model)
    }

    @Test
    fun `Google durations parse, anything else is null`() {
        assertEquals(4.hours + 23.minutes + 27_700.milliseconds, AcpQuotaReset.parseDuration("4h23m27.7s"))
        assertEquals(27.seconds, AcpQuotaReset.parseDuration("27s"))
        assertEquals(850.milliseconds, AcpQuotaReset.parseDuration("850ms"))
        assertEquals(RESET_DELAY, AcpQuotaReset.parseDuration(RESET_DELAY_TEXT))
        assertEquals(15807.seconds + 716_645_894.nanoseconds, AcpQuotaReset.parseDuration("15807.716645894s"))
        assertEquals(1.minutes + 500.nanoseconds, AcpQuotaReset.parseDuration("1m500ns"))
        assertEquals(3_000.nanoseconds, AcpQuotaReset.parseDuration("3us"))
        listOf("", "soon", "4", "-5s", "0s", "4h 23m", "4x", "s", "1.s").forEach {
            assertNull(AcpQuotaReset.parseDuration(it), it)
        }
    }

    @Test
    fun `a prompt error with the quota body emits rate_limit and holds until the reset`() {
        val errorLine =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 4)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32000)
                        put("message", "Resource exhausted")
                        put("data", parse(logged429(model = null)))
                    },
                )
            }.toString()
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-quota")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-quota")
                awaitClient()
                raw(errorLine)
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertEquals(RESET_DELAY, failed.retryAfter)
        assertTrue("the quota resets at $RESET_AT" in failed.reason, failed.reason)
        val rateLimit = events.filterIsInstance<RunnerEvent.RateLimit>().single()
        assertEquals("gemini", rateLimit.limitId)
        assertEquals(FakeAcpServer.DEFAULT_MODEL, rateLimit.limitName)
        assertEquals("quota_exhausted", rateLimit.reached)
        assertNull(rateLimit.primary)
        assertNull(rateLimit.secondary)
        assertEquals(failed.turnId, rateLimit.turnId)
        assertTrue(events.indexOf(rateLimit) < events.indexOf(failed))
        // What the quota adds names no account; the echo of the line is bl-240's test below.
        listOf(rateLimit, failed).forEach { event ->
            val text = event.toString()
            assertFalse(EMAIL in text || ACCOUNT_ID in text, text)
        }
    }

    @Test
    fun `bl-240 the echo of the quota error omits its data, the frame still gives rate_limit and the reason`() {
        val errorLine =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 4)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32000)
                        put("message", "Resource exhausted")
                        put("data", parse(logged429(model = null)))
                    },
                )
            }.toString()
        val notification = """{ "jsonrpc": "2.0", "method": "loop/unknown", "params": {"note": "kept as read"} }"""
        val undecodable = """not json {"error":{"data":"kept as read"}}"""
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-quota")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-quota")
                awaitClient()
                raw(notification)
                raw(undecodable)
                raw(errorLine)
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val outputs = events.filterIsInstance<RunnerEvent.Output>()
        outputs.forEach { output -> assertFalse(EMAIL in output.line || ACCOUNT_ID in output.line, output.line) }
        val echoed = outputs.single { "Resource exhausted" in it.line }
        assertEquals(RunnerEvent.Output.Stream.Stdout, echoed.stream)
        assertTrue("\"code\":-32000" in echoed.line, echoed.line)
        assertTrue("[error data omitted, " in echoed.line, echoed.line)
        assertTrue(outputs.any { it.stream == RunnerEvent.Output.Stream.Stdout && it.line == notification })
        assertTrue(outputs.any { it.stream == RunnerEvent.Output.Stream.Stdout && it.line == undecodable })
        // The decoded frame keeps the data, so bl-215's telemetry reads the reset and the model as before.
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertEquals(RESET_DELAY, failed.retryAfter)
        assertTrue("the quota resets at $RESET_AT" in failed.reason, failed.reason)
        val rateLimit = events.filterIsInstance<RunnerEvent.RateLimit>().single()
        assertEquals("gemini", rateLimit.limitId)
        assertEquals("quota_exhausted", rateLimit.reached)
    }

    @Test
    fun `a rate limit error with no reset emits no rate_limit event`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-plain")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-plain")
                awaitClient()
                error(id = 4, code = -32000, message = "429 Too Many Requests: quota exceeded")
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertNull(failed.retryAfter)
        assertEquals("turn_failed: 429 Too Many Requests: quota exceeded (code -32000)", failed.reason)
        assertTrue(events.none { it is RunnerEvent.RateLimit })
    }

    private fun agyRunner(server: FakeAcpServer): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = server.agyCommand,
                    preset = AcpSettings.PRESET_AGY,
                    model = FakeAcpServer.DEFAULT_MODEL,
                    readTimeout = FakeAcpServer.READ_TIMEOUT,
                ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)::launch,
            clock = FixedClock(NOW),
        )

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "bl-215",
                    title = "Quota reset test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )

    private companion object {
        const val MODEL = "gemini-4-neon-high"
        const val EMAIL = "someone@example.com"
        const val ACCOUNT_ID = "735190462281"
        const val RESET_DELAY_TEXT = "4h23m27.716645894s"
        const val QUOTA_MESSAGE =
            "You have exhausted your capacity on this model. Your quota will reset after 4h23m27s."
        const val NO_RESET_MESSAGE = "Resource has been exhausted (e.g. check quota)."
        val RESET_AT: Instant = Instant.parse("2026-10-19T19:45:12Z")
        val RESET_DELAY: Duration = 4.hours + 23.minutes + 27.seconds + 716_645_894.nanoseconds
        val NOW: Instant = RESET_AT - RESET_DELAY
    }
}
