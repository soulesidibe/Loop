package loop.core.ledger

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.LedgerTurn
import loop.api.Redactor
import loop.api.TurnLedger
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes each turn of the ledger as one JSON object on one line (m2-19), handed to [append]; the cli appends it
 * to `<LOOP_HOME>/ledger/<project_id>.jsonl`. Every key is always there: a measure nobody reported is `null`,
 * never `0`. String values go through [redactor] before the encoding, so a marker can never split a value.
 */
class JsonlTurnLedger(
    private val append: (String) -> Unit,
    private val redactor: Redactor,
) : TurnLedger {
    override fun append(turn: LedgerTurn): Result<Unit> =
        try {
            append(encode(turn))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun encode(turn: LedgerTurn): String {
        val setup = turn.setup
        val tokens = turn.tokens
        val fields =
            linkedMapOf(
                "ts" to text(turn.ts.toString()),
                "project_id" to text(turn.projectId.value),
                "run_id" to text(turn.runId?.value),
                "issue_id" to text(turn.issueId),
                "issue_identifier" to text(turn.identifier),
                "attempt_id" to text(turn.attemptId.value),
                "session_id" to text(turn.sessionId?.value),
                "turn_id" to text(turn.turnId?.value),
                "turn" to JsonPrimitive(turn.turn),
                "outcome" to text(turn.outcome),
                "tracker" to text(setup.tracker),
                "kind" to text(setup.kind),
                "runner" to text(setup.runner),
                "model" to text(setup.model),
                "template_version" to text(setup.templateVersion),
                "config_version" to text(setup.configVersion),
                "input_tokens" to JsonPrimitive(tokens?.input),
                "output_tokens" to JsonPrimitive(tokens?.output),
                "cache_read_input_tokens" to JsonPrimitive(tokens?.cacheRead),
                "cache_creation_input_tokens" to JsonPrimitive(tokens?.cacheCreation),
                "tokens" to JsonPrimitive(tokens?.total),
                "attempt_tokens" to JsonPrimitive(turn.attemptTokens?.total),
                "cost_usd" to JsonPrimitive(turn.costUsd),
                "attempt_cost_usd" to JsonPrimitive(turn.attemptCostUsd),
                "wall_ms" to JsonPrimitive(turn.wallMs),
                "thread_id" to text(turn.threadId),
                "peak_tokens" to JsonPrimitive(turn.peak?.tokens),
                "window" to JsonPrimitive(turn.peak?.window),
                "peak_fraction" to JsonPrimitive(turn.peak?.fraction?.let(::fourDecimals)),
                "rotations" to JsonPrimitive(turn.rotations),
                "interrupted" to text(turn.interrupted),
            )
        return JsonObject(fields).toString()
    }

    /** [value] rounded to 4 decimals, as the `fraction` of a `context_usage` event. */
    private fun fourDecimals(value: Double): Double = BigDecimal.valueOf(value).setScale(FRACTION_SCALE, RoundingMode.HALF_UP).toDouble()

    private fun text(value: String?): JsonElement = value?.let { JsonPrimitive(redactor.redact(it)) } ?: JsonNull

    private companion object {
        const val FRACTION_SCALE = 4
    }
}
