package loop.core.orchestrator

import loop.api.LedgerPeak
import loop.api.RunnerEvent
import loop.core.events.ContextUsage
import java.util.Locale

/**
 * The context occupancy of one agent session (plan 8.3, m3-08): the latest [RunnerEvent.ContextUsage] report and the
 * peak of them all. A report replaces the latest, never adds to it. A new session starts a new meter. Its latest report
 * decides a rotation at the soft limit (m3-11).
 */
internal class OccupancyMeter {
    private var latest: Long? = null
    private var peak: Long = 0

    /** The `provider/model` of the latest report, null when the runner named none. */
    var model: String? = null
        private set

    fun record(report: RunnerEvent.ContextUsage) {
        val tokens = report.occupancyTokens.coerceAtLeast(0)
        latest = tokens
        peak = maxOf(peak, tokens)
        model = report.model
    }

    /** The session's peak for the ledger (m3-09) with the window `context.windows` gives the model, null before any report. */
    fun peak(windows: Map<String, Long>): LedgerPeak? = latest?.let { LedgerPeak(peak, model?.let { windows[it] }?.takeIf { it > 0 }) }

    /**
     * The data of the `context_usage` event with the window `context.windows` gives the model, from [windows]: the
     * latest and peak tokens, the model, and the window and the fraction only when the window is known. Null before
     * any report.
     */
    fun data(windows: Map<String, Long>): Map<String, String>? {
        val tokens = latest ?: return null
        val window = model?.let { windows[it] }
        return buildMap {
            put(ContextUsage.LATEST_TOKENS, tokens.toString())
            put(ContextUsage.PEAK_TOKENS, peak.toString())
            model?.let { put(ContextUsage.MODEL, it) }
            if (window != null && window > 0) {
                put(ContextUsage.WINDOW, window.toString())
                put(ContextUsage.FRACTION, String.format(Locale.ROOT, "%.4f", tokens.toDouble() / window))
            }
        }
    }

    /**
     * The soft limit check of a rotation (m3-11): the latest report and its model's window from [windows] when the latest
     * is at or above [limit] times the window, null otherwise, and null before any report or without a known window.
     */
    fun crossing(
        windows: Map<String, Long>,
        limit: Double,
    ): Crossing? {
        val tokens = latest ?: return null
        val window = model?.let { windows[it] }?.takeIf { it > 0 } ?: return null
        return if (tokens.toDouble() >= limit * window) Crossing(tokens, window) else null
    }

    /** The occupancy in [tokens] that crossed a limit of the [window]. */
    data class Crossing(
        val tokens: Long,
        val window: Long,
    ) {
        val fraction: Double get() = tokens.toDouble() / window

        /** [fraction] with 4 decimals, as `context_usage` writes it. */
        fun fractionText(): String = String.format(Locale.ROOT, "%.4f", fraction)
    }
}
