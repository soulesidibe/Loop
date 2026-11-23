package loop.core.ledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import loop.api.AttemptId
import loop.api.LedgerPeak
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.LedgerTurn
import loop.api.ProjectId
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-19: the ledger line of a turn is one JSON object per line, every key present, null when nothing was reported.
 * m3-11: it carries the attempt's rotations so far.
 */
class JsonlTurnLedgerTest {
    private val turn =
        LedgerTurn(
            ts = Instant.parse("2026-11-12T10:00:01Z"),
            projectId = ProjectId("p1"),
            runId = RunId("r1"),
            issueId = "id-1",
            identifier = "m2-19",
            attemptId = AttemptId("a1"),
            sessionId = SessionId("th1-tu2"),
            turnId = TurnId("tu2"),
            turn = 2,
            outcome = "completed",
            setup = LedgerSetup("files", "code", "claude-code", "claude-opus-5-5", "t1", "c1"),
            tokens = LedgerTokens(10, 2, 300, 40),
            attemptTokens = LedgerTokens(20, 4, 600, 80),
            costUsd = 0.5,
            attemptCostUsd = 1.25,
            wallMs = 1_500,
            threadId = "th1",
            peak = LedgerPeak(300_000, 1_000_000),
            rotations = 1,
        )

    @Test
    fun `a turn is one line holding every field`() {
        val written = mutableListOf<String>()

        JsonlTurnLedger(written::add, SecretRedactor()).append(turn).getOrThrow()

        val line = written.single()
        assertTrue('\n' !in line, line)
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            mapOf(
                "ts" to JsonPrimitive("2026-11-12T10:00:01Z"),
                "project_id" to JsonPrimitive("p1"),
                "run_id" to JsonPrimitive("r1"),
                "issue_id" to JsonPrimitive("id-1"),
                "issue_identifier" to JsonPrimitive("m2-19"),
                "attempt_id" to JsonPrimitive("a1"),
                "session_id" to JsonPrimitive("th1-tu2"),
                "turn_id" to JsonPrimitive("tu2"),
                "turn" to JsonPrimitive(2),
                "outcome" to JsonPrimitive("completed"),
                "tracker" to JsonPrimitive("files"),
                "kind" to JsonPrimitive("code"),
                "runner" to JsonPrimitive("claude-code"),
                "model" to JsonPrimitive("claude-opus-5-5"),
                "template_version" to JsonPrimitive("t1"),
                "config_version" to JsonPrimitive("c1"),
                "input_tokens" to JsonPrimitive(10),
                "output_tokens" to JsonPrimitive(2),
                "cache_read_input_tokens" to JsonPrimitive(300),
                "cache_creation_input_tokens" to JsonPrimitive(40),
                "tokens" to JsonPrimitive(352),
                "attempt_tokens" to JsonPrimitive(704),
                "cost_usd" to JsonPrimitive(0.5),
                "attempt_cost_usd" to JsonPrimitive(1.25),
                "wall_ms" to JsonPrimitive(1_500),
                "thread_id" to JsonPrimitive("th1"),
                "peak_tokens" to JsonPrimitive(300_000),
                "window" to JsonPrimitive(1_000_000),
                "peak_fraction" to JsonPrimitive(0.3),
                "rotations" to JsonPrimitive(1),
                "interrupted" to JsonNull,
                "wake" to JsonPrimitive(false),
            ),
            json.toMap(),
        )
    }

    @Test
    fun `what the runner did not report is null, never zero`() {
        val written = mutableListOf<String>()
        val bare =
            turn.copy(
                tokens = null,
                attemptTokens = null,
                costUsd = null,
                attemptCostUsd = null,
                setup = turn.setup.copy(kind = null, model = null, configVersion = null),
                peak = null,
            )

        JsonlTurnLedger(written::add, SecretRedactor()).append(bare).getOrThrow()

        val json = Json.parseToJsonElement(written.single()).jsonObject
        val nulls =
            listOf(
                "kind",
                "model",
                "config_version",
                "input_tokens",
                "output_tokens",
                "cache_read_input_tokens",
                "cache_creation_input_tokens",
                "tokens",
                "attempt_tokens",
                "cost_usd",
                "attempt_cost_usd",
                "peak_tokens",
                "window",
                "peak_fraction",
            )
        nulls.forEach { assertEquals(JsonNull, json[it], it) }
    }

    @Test
    fun `m3-09 a peak with no known window has its tokens and a null window and fraction`() {
        val written = mutableListOf<String>()

        JsonlTurnLedger(written::add, SecretRedactor()).append(turn.copy(peak = LedgerPeak(40_000, null))).getOrThrow()

        val json = Json.parseToJsonElement(written.single()).jsonObject
        assertEquals(JsonPrimitive(40_000), json["peak_tokens"])
        assertEquals(JsonNull, json["window"])
        assertEquals(JsonNull, json["peak_fraction"])
    }

    @Test
    fun `m3-09 the peak fraction keeps 4 decimals`() {
        val written = mutableListOf<String>()

        JsonlTurnLedger(written::add, SecretRedactor()).append(turn.copy(peak = LedgerPeak(43_302, 200_000))).getOrThrow()

        assertEquals(JsonPrimitive(0.2165), Json.parseToJsonElement(written.single()).jsonObject["peak_fraction"])
    }

    @Test
    fun `m3-11 the line carries the attempt's rotations so far, 0 before any`() {
        val written = mutableListOf<String>()
        val ledger = JsonlTurnLedger(written::add, SecretRedactor())

        ledger.append(turn.copy(rotations = 0)).getOrThrow()
        ledger.append(turn.copy(rotations = 2)).getOrThrow()

        assertEquals(listOf(JsonPrimitive(0), JsonPrimitive(2)), written.map { Json.parseToJsonElement(it).jsonObject["rotations"] })
    }

    @Test
    fun `m3-12 the line of a turn interrupted at the hard limit says so, null otherwise`() {
        val written = mutableListOf<String>()
        val ledger = JsonlTurnLedger(written::add, SecretRedactor())

        ledger.append(turn.copy(interrupted = "hard_limit")).getOrThrow()
        ledger.append(turn).getOrThrow()

        assertEquals(
            listOf(JsonPrimitive("hard_limit"), JsonNull),
            written.map { Json.parseToJsonElement(it).jsonObject["interrupted"] },
        )
    }

    @Test
    fun `m4-03 the line of a wake turn says wake true, any other turn false`() {
        val written = mutableListOf<String>()
        val ledger = JsonlTurnLedger(written::add, SecretRedactor())

        ledger.append(turn.copy(wake = true)).getOrThrow()
        ledger.append(turn).getOrThrow()

        assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), written.map { Json.parseToJsonElement(it).jsonObject["wake"] })
    }

    @Test
    fun `string values are redacted before they are written`() {
        val written = mutableListOf<String>()
        val redactor = SecretRedactor().also { it.learn(mapOf("TOKEN" to "s3cr3t-value")) }

        JsonlTurnLedger(written::add, redactor).append(turn.copy(identifier = "m2-s3cr3t-value")).getOrThrow()

        assertTrue("s3cr3t-value" !in written.single(), written.single())
    }

    @Test
    fun `a failed append is a failed result, never thrown`() {
        val result = JsonlTurnLedger({ error("disk full") }, SecretRedactor()).append(turn)

        assertEquals("disk full", result.exceptionOrNull()?.message)
    }
}
