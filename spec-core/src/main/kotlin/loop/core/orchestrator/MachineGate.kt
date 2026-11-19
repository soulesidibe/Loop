package loop.core.orchestrator

import loop.api.AttemptId
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.machine.Machine
import loop.core.machine.MachineConfig
import loop.core.machine.MachineLimits
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** How a machine slot claim went (m3-16): no cap is set, the slot is held by [Held.holder], or none was free. */
internal sealed interface SlotClaim {
    data object NoCap : SlotClaim

    data class Held(
        val holder: String,
    ) : SlotClaim

    data object Refused : SlotClaim
}

/**
 * One process's side of the machine limits (m3-16): the limits read with each tick's preflight, the machine slots its
 * attempts and merge checks claim and release, and the machine day budget that holds new dispatches. The orchestrator
 * calls it from its single threaded loop; every machine wide count lives in the shared store, never here.
 */
internal class MachineGate(
    private val machine: Machine,
    private val log: KeyValueLog,
    private val clock: Clock,
    private val newId: () -> String,
) {
    /** The limits of this tick; null without a machine config. */
    private var limits: MachineLimits? = null

    /** Slots whose release failed, released again at the next tick, so none stays held for the life of the process. */
    private val unreleased = mutableSetOf<String>()

    /** The spend of the finished attempts of every project read last, kept when a read fails. */
    private var finished: Spend? = null

    /** True while the machine day budget holds dispatch, so the log says once when it starts and once when it lifts. */
    private var holding = false

    /**
     * Reads the machine config for this tick: null when it is usable, else the message naming the malformed key, which
     * fails the preflight. Read on every tick, so a fixed or changed file counts from the next one.
     */
    fun readLimits(): String? {
        val read =
            try {
                machine.limits()
            } catch (e: Exception) {
                Result.failure(e)
            }
        return read.fold(
            onSuccess = {
                limits = it
                null
            },
            onFailure = { it.message ?: "the machine config cannot be read" },
        )
    }

    /** Releases again the slots a failed release left; called at the start of a tick. */
    suspend fun retryReleases(context: LogContext) {
        unreleased.toList().forEach { release(it, context) }
    }

    /**
     * Claims a machine slot when the machine config sets a cap, in one transaction of the shared store, before the
     * launch it guards. A store failure is a refusal, never a launch: the cap holds even when the store does not answer.
     */
    suspend fun claim(context: LogContext): SlotClaim {
        val cap = limits?.maxConcurrentAgents ?: return SlotClaim.NoCap
        val holder = newId()
        val claimed =
            try {
                machine.claimSlot(holder, cap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        claimed.exceptionOrNull()?.let { error ->
            log.log(LogLevel.WARN, MACHINE_SLOT, LogOutcome.FAILED, context, reason = error.message ?: error::class.simpleName)
        }
        return if (claimed.getOrDefault(false)) SlotClaim.Held(holder) else SlotClaim.Refused
    }

    /** Deletes the slot of [holder] at the end of its attempt or merge check; a failure is retried at the next tick. */
    suspend fun release(
        holder: String,
        context: LogContext,
    ) {
        val error =
            try {
                machine.releaseSlot(holder).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (error == null) {
            unreleased -= holder
            return
        }
        unreleased += holder
        log.log(LogLevel.WARN, MACHINE_SLOT, LogOutcome.FAILED, context, reason = "release: ${error.message ?: error::class.simpleName}")
    }

    /**
     * True when the machine day budget holds new dispatches: the attempts of every project that finished in the last
     * 24 hours, plus [live], the attempts running in this process, spent more than `machine.per_day_usd` or
     * `machine.per_day_tokens`; exactly the limit is allowed, like the project budgets. The live spend of other
     * processes is not in the store until their attempts end, an overshoot that is accepted. It parks nothing and
     * cuts no running attempt, and lifts by itself once the window has room.
     */
    suspend fun budgetHolds(
        live: Map<AttemptId, LiveSpend>,
        context: LogContext,
    ): Boolean {
        val usdLimit = limits?.perDayUsd
        val tokenLimit = limits?.perDayTokens
        if (usdLimit == null && tokenLimit == null) {
            lift(context)
            return false
        }
        val read =
            try {
                machine.attemptRowsSince(clock.now() - SpendReading.DAY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        read.fold(
            onSuccess = { rows -> finished = spendOf(rows, live.keys) },
            onFailure = { log.log(LogLevel.WARN, MACHINE_BUDGET, LogOutcome.FAILED, context, reason = it.message ?: it::class.simpleName) },
        )
        val spend = live.values.fold(finished ?: Spend()) { sum, attempt -> sum + attempt.spend }
        val crossed =
            when {
                usdLimit != null && spend.usd > usdLimit -> Triple(MachineConfig.PER_DAY_USD, print(spend.usd), print(usdLimit))
                tokenLimit != null && spend.tokens > tokenLimit -> Triple(MachineConfig.PER_DAY_TOKENS, "${spend.tokens}", "$tokenLimit")
                else -> null
            }
        if (crossed == null) {
            lift(context)
            return false
        }
        if (!holding) {
            holding = true
            val (budget, used, limit) = crossed
            log.log(
                LogLevel.WARN,
                MACHINE_BUDGET,
                LogOutcome.REACHED,
                context,
                mapOf("budget" to budget, "used" to used, "limit" to limit),
                "no new dispatch until the last 24 hours of the machine have room",
            )
        }
        return true
    }

    private fun lift(context: LogContext) {
        if (!holding) return
        holding = false
        log.log(LogLevel.INFO, MACHINE_BUDGET, LogOutcome.RELEASED, context)
    }

    private fun print(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        /** The skip reason, the retry error and the log event of a dispatch the machine day budget holds. */
        const val MACHINE_BUDGET = "machine_budget"

        /** The skip reason of a candidate that found no machine slot. */
        const val NO_MACHINE_SLOT = "no_machine_slot"

        /** The retry error of a due retry that found no machine slot. */
        const val NO_MACHINE_SLOTS = "no available machine slots"

        private const val MACHINE_SLOT = "machine_slot"
    }
}
