package loop.core.orchestrator

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import loop.api.AgentSession
import loop.api.LedgerTurn
import loop.api.RunnerEvent
import loop.core.events.StopGraceExpired
import kotlin.time.Instant

/**
 * The stop of an attempt's agent session and what it reads (m4-04, story 104, bl-152). Every stop, at a rotation or at
 * the attempt's end, gives the session a report callback: the [usage] of a wake the session read while it stopped goes
 * to the attempt's spend, the budgets and the ledger, and a stop grace that ended with the runtime still there goes to
 * [graceExpired]. A stop that throws is given to [failed], after what it reported, so its usage still counts.
 */
internal class StopUsage(
    private val usage: suspend (RunnerEvent.Usage) -> Unit,
    private val graceExpired: suspend (RunnerEvent.StopGraceExpired) -> Unit,
    private val failed: suspend (Exception) -> Unit,
) {
    /**
     * Stops [session] whatever the attempt's end, even a cancelled one, and routes its reports. Gives the failure's
     * description, null when the session stopped.
     */
    suspend fun stop(session: AgentSession): String? =
        withContext(NonCancellable) {
            try {
                session.stop { report ->
                    when (report) {
                        is RunnerEvent.Usage -> usage(report)
                        is RunnerEvent.StopGraceExpired -> graceExpired(report)
                        else -> Unit
                    }
                }
                null
            } catch (e: Exception) {
                // Under NonCancellable this coroutine cannot be cancelled, so a CancellationException is the session's
                // own, never the attempt's cancel: it is a failure like any other. A genuine outer cancel still
                // propagates, withContext rethrows it once this block returns.
                failed(e)
                e.message ?: e::class.simpleName ?: "unknown error"
            }
        }

    /** What follows the stop of a rotation. */
    enum class End {
        /** A budget the stop's usage went past ends the attempt on that budget. */
        Budget,

        /** The stop failed: no second session starts beside one that may still live. */
        StopFailed,

        /** The new session starts. */
        Next,
    }

    companion object {
        /** The `outcome` of the ledger line that holds what a stop read when no turn follows it. */
        const val STOP = "stop"

        /**
         * What follows the stop of a rotation: a budget passed, by the stop's usage or before, wins over a failed stop
         * ([stopFailure]), whose `session_stop_failed` the stop recorded already.
         */
        fun rotationEnd(
            breached: Boolean,
            stopFailure: String?,
        ): End =
            when {
                breached -> End.Budget
                stopFailure != null -> End.StopFailed
                else -> End.Next
            }

        /**
         * Whether a budget the usage of the attempt's last stop went past ([breached]) ends the attempt on that budget,
         * in place of the end it reached. An end that already [parks] the issue keeps its own reason, and an end that
         * [waitsApproval], a verified result waiting for a human or an agent's question (m4-31), keeps its wait. A failed stop at the attempt's end
         * changes nothing.
         */
        fun budgetWins(
            breached: Boolean,
            parks: Boolean,
            waitsApproval: Boolean,
        ): Boolean = breached && !parks && !waitsApproval

        /**
         * The `stop_grace_expired` data of [event]: the grace, whether a wake was running, how many of its messages
         * counted, and the attempt's usage known after them, [attemptTokens] and [costUsd], each left out when unknown.
         */
        fun graceData(
            event: RunnerEvent.StopGraceExpired,
            attemptTokens: Long?,
            costUsd: Double?,
        ): Map<String, String> =
            buildMap {
                put(StopGraceExpired.GRACE_MS, event.graceMs.toString())
                put(StopGraceExpired.WAKE, event.wake.toString())
                put(StopGraceExpired.WAKE_MESSAGES, event.wakeMessages.toString())
                attemptTokens?.let { put(StopGraceExpired.ATTEMPT_TOKENS, it.toString()) }
                costUsd?.let { put(StopGraceExpired.TOTAL_COST_USD, it.toString()) }
            }

        /**
         * The ledger line of the usage a stop read that no turn line holds, null when [meter] holds none or no turn line
         * was written ([last]). A copy of [last] with [STOP] as its outcome, the stop's share of the tokens and the
         * cost, the attempt's totals and the wall time since [last]: the turn lines of the attempt then sum to its total.
         * It is neither interrupted nor a wake turn.
         */
        fun line(
            last: LedgerTurn?,
            meter: LedgerMeter,
            now: Instant,
        ): LedgerTurn? {
            if (last == null || !meter.holdsStopUsage) return null
            meter.startTurn()
            return last.copy(
                ts = now,
                outcome = STOP,
                tokens = meter.turnTokens(),
                attemptTokens = meter.tokens,
                costUsd = meter.turnCostUsd(),
                attemptCostUsd = meter.costUsd,
                wallMs = (now - last.ts).inWholeMilliseconds.coerceAtLeast(0),
                interrupted = null,
                wake = false,
            )
        }
    }
}
