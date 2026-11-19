package loop.core.orchestrator

import loop.api.RunnerEvent

/**
 * The tokens one attempt has used (m1-08), measured against its spend budgets (m2-20). A runner reports absolute
 * totals for its agent session so far, and the session id changes every turn, so the meter keeps only the latest
 * total: a report replaces the last one and is never added to it.
 */
internal class TokenMeter {
    /** Input, output, cache read and cache creation tokens of the latest report; 0 before any. */
    var total: Long = 0
        private set

    fun record(usage: RunnerEvent.Usage) {
        total = totalOf(usage.inputTokens, usage.outputTokens, usage.cacheReadInputTokens, usage.cacheCreationInputTokens)
    }

    companion object {
        /**
         * The sum of token [parts]. Each part is clamped at 0 and the sum saturates, so a bad report can
         * only raise the total: a budget fails closed.
         */
        fun totalOf(vararg parts: Long): Long = parts.fold(0L) { sum, part -> saturatingAdd(sum, part.coerceAtLeast(0)) }

        private fun saturatingAdd(
            a: Long,
            b: Long,
        ): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}
