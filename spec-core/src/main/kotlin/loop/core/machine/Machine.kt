package loop.core.machine

import loop.api.LedgerRow
import kotlin.time.Instant

/**
 * What one Loop process sees of the machine it shares with the others (m3-16, ADR 0001): the machine limits, the
 * machine slots and the ledger rows of every project, all coordinated through the shared store, never in memory.
 */
interface Machine {
    /** The machine limits now, read on every tick: null without a machine config, a [MachineConfigError] when it is malformed. */
    fun limits(): Result<MachineLimits?>

    /**
     * Claims one machine slot for [holder], an attempt or a merge check of this process, in one transaction: true when
     * fewer than [cap] live slots were held on the machine and the slot is now written, false when none is free.
     */
    suspend fun claimSlot(
        holder: String,
        cap: Int,
    ): Result<Boolean>

    /** Deletes the slot of [holder], at the end of its attempt or merge check. */
    suspend fun releaseSlot(holder: String): Result<Unit>

    /** The ledger rows of every project on the machine that finished after [since]. */
    suspend fun attemptRowsSince(since: Instant): Result<List<LedgerRow>>

    companion object {
        /** No machine config and no shared store: no machine limit, as before m3-16. */
        val NONE: Machine =
            object : Machine {
                override fun limits(): Result<MachineLimits?> = Result.success(null)

                override suspend fun claimSlot(
                    holder: String,
                    cap: Int,
                ): Result<Boolean> = Result.success(true)

                override suspend fun releaseSlot(holder: String): Result<Unit> = Result.success(Unit)

                override suspend fun attemptRowsSince(since: Instant): Result<List<LedgerRow>> = Result.success(emptyList())
            }
    }
}
