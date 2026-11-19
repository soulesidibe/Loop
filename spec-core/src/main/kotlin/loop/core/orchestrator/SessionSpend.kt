package loop.core.orchestrator

import loop.api.RunnerEvent

/**
 * The spend of an attempt across its agent sessions (m3-11). A runner reports usage absolute for its session, so the
 * new session of a rotation starts at 0. This adds the last totals of every closed session to each report, so what the
 * token meter, the ledger, the budgets and the thresholds read stays absolute for the attempt and never drops.
 */
internal class SessionSpend {
    /** The last totals of the closed sessions, summed; a cost only when one of them reported one. */
    private var carried = Totals(0, 0, 0, 0, null)

    /** The latest report of the current session, null before any. */
    private var latest: Totals? = null

    /** A [RunnerEvent.Usage] of the current session, made absolute for the attempt. */
    fun usage(event: RunnerEvent.Usage): RunnerEvent.Usage {
        val own =
            Totals(event.inputTokens, event.outputTokens, event.cacheReadInputTokens, event.cacheCreationInputTokens, event.totalCostUsd)
        latest = Totals(own.input, own.output, own.cacheRead, own.cacheCreation, own.cost ?: latest?.cost)
        return event.copy(
            inputTokens = sum(carried.input, own.input),
            outputTokens = sum(carried.output, own.output),
            cacheReadInputTokens = sum(carried.cacheRead, own.cacheRead),
            cacheCreationInputTokens = sum(carried.cacheCreation, own.cacheCreation),
            totalCostUsd = own.cost?.let { (carried.cost ?: 0.0) + it },
        )
    }

    /** A [RunnerEvent.UsageEstimate] of the current session, made absolute for the attempt like [usage]. */
    fun estimate(event: RunnerEvent.UsageEstimate): RunnerEvent.UsageEstimate =
        event.copy(
            inputTokens = sum(carried.input, event.inputTokens),
            outputTokens = sum(carried.output, event.outputTokens),
            cacheReadInputTokens = sum(carried.cacheRead, event.cacheReadInputTokens),
            cacheCreationInputTokens = sum(carried.cacheCreation, event.cacheCreationInputTokens),
        )

    /** The current session ended: its last totals are carried into every later report. */
    fun closeSession() {
        val last = latest ?: return
        carried =
            Totals(
                sum(carried.input, last.input),
                sum(carried.output, last.output),
                sum(carried.cacheRead, last.cacheRead),
                sum(carried.cacheCreation, last.cacheCreation),
                last.cost?.let { (carried.cost ?: 0.0) + it } ?: carried.cost,
            )
        latest = null
    }

    private fun sum(
        carried: Long,
        own: Long,
    ): Long = TokenMeter.totalOf(carried, own)

    private data class Totals(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheCreation: Long,
        val cost: Double?,
    )
}
