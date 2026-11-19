package loop.core.events

import loop.api.RecordedEvent

/** The spend fields several kinds share: the attempt's tokens (m1-08), a spend budget (m2-20) and the no progress counter (m1-27). */
object Spend {
    const val ATTEMPT_TOKENS = "attempt_tokens"
    const val MAX_TOKENS = "max_tokens"
    const val BUDGET = "budget"
    const val USED = "used"
    const val LIMIT = "limit"
    const val NO_PROGRESS = "no_progress"

    /** Both the park reason and the event key of its limit. */
    const val MAX_CONTINUATIONS = "max_continuations"

    /** `tokens=<spent>/<cap>` in raw digits, or null when the event predates the token cap (m1-08). */
    internal fun traceTokens(data: Map<String, String>): String? {
        val spent = data[ATTEMPT_TOKENS] ?: return null
        return data[MAX_TOKENS]?.let { "tokens=$spent/$it" } ?: "tokens=$spent"
    }

    /** `budget=<name> used=<used>/<limit>` of a spend budget event or park (m2-20), or null without a budget name. */
    internal fun traceBudget(data: Map<String, String>): String? {
        val name = data[BUDGET] ?: return null
        return "budget=${TraceText.quote(name)} used=${data[USED] ?: "?"}/${data[LIMIT] ?: "?"}"
    }

    /** `no_progress=<count>/<limit>`, or null when the event predates the no progress counter (m1-27). */
    internal fun traceNoProgress(data: Map<String, String>): String? {
        val count = data[NO_PROGRESS] ?: return null
        return data[MAX_CONTINUATIONS]?.let { "no_progress=$count/$it" } ?: "no_progress=$count"
    }

    /** What a spend budget event says was used of its limit (m2-20): `8.1M of 10M tokens` or `$8.50 of $10.00`. */
    internal fun humanBudgetUse(data: Map<String, String>): String {
        val usd = data[BUDGET].orEmpty().endsWith("_usd")

        fun amount(key: String): String =
            if (usd) {
                data[key]?.toDoubleOrNull()?.let(HumanText::money) ?: "?"
            } else {
                data[key]?.toLongOrNull()?.let(HumanText::size) ?: "?"
            }
        return "${amount(USED)} of ${amount(LIMIT)}" + if (usd) "" else " tokens"
    }
}

/** The runner's latest usage report: absolute totals for the session so far. */
object Usage : EventDefinition("usage") {
    const val ATTEMPT_TOKENS = Spend.ATTEMPT_TOKENS
    const val MAX_TOKENS = Spend.MAX_TOKENS
    const val INPUT_TOKENS = "input_tokens"
    const val OUTPUT_TOKENS = "output_tokens"
    const val CACHE_READ_INPUT_TOKENS = "cache_read_input_tokens"
    const val CACHE_CREATION_INPUT_TOKENS = "cache_creation_input_tokens"
    const val TOTAL_COST_USD = "total_cost_usd"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(ATTEMPT_TOKENS to "1200", MAX_TOKENS to "100000", TOTAL_COST_USD to "0.12")

    /** A `usage` event recorded before m1-08 has no spend; the trace of such a run prints as it always did. */
    override fun showsInTimeline(event: RecordedEvent): Boolean = ATTEMPT_TOKENS in event.data

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { word(Spend.traceTokens(event.data)) }

    /** The attempt's tokens so far, null before m1-08. */
    fun attemptTokens(data: Map<String, String>): String? = data[ATTEMPT_TOKENS]

    /** The runner's reported cost so far, null when it reports none. */
    fun costUsd(data: Map<String, String>): Double? = data[TOTAL_COST_USD]?.toDoubleOrNull()
}

/** The token cap stopped the turn (m1-08); replaced by `budget_reached` in m2-20, still read in old traces. */
object TokenCapReached : EventDefinition("token_cap_reached") {
    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(Spend.ATTEMPT_TOKENS to "120000", Spend.MAX_TOKENS to "100000")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { word(Spend.traceTokens(event.data)) }
}

/** A token threshold the attempt crossed (m1-41). */
object TokenThreshold : EventDefinition("token_threshold") {
    const val TOKENS = "tokens"
    const val THRESHOLD = "threshold"
    const val COST_USD = "cost_usd"

    /** Only written for a mid turn estimate; a real usage report has none. */
    const val ESTIMATED = "estimated"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(TOKENS to "10200000", THRESHOLD to "10000000", COST_USD to "3.38")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(THRESHOLD, event.data[THRESHOLD])
        add(TOKENS, event.data[TOKENS])
        word("$ESTIMATED=${event.data[ESTIMATED] == "true"}")
        // No price table: a threshold crossed before any cost was reported has none, so none is printed.
        add(COST_USD, event.data[COST_USD])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? {
        val about = if (data[ESTIMATED] == "true") "about " else ""
        val step = data[THRESHOLD]?.toLongOrNull() ?: return null
        val cost = data[COST_USD]?.toDoubleOrNull()?.let { ", ${HumanText.money(it)} so far" }.orEmpty()
        return "passed $about${HumanText.size(step)} tokens$cost"
    }
}

/** The fields of a spend budget event (m2-20). */
abstract class BudgetEvent(
    name: String,
) : EventDefinition(name) {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(Spend.BUDGET to "per_attempt_tokens", Spend.USED to "8100000", Spend.LIMIT to "10000000")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { word(Spend.traceBudget(event.data)) }
}

/** The attempt reached 80 percent of a spend budget. */
object BudgetWarning : BudgetEvent("budget_warning") {
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "used ${Spend.humanBudgetUse(data)}, 80 percent of its ${data[Spend.BUDGET]} budget"
}

/** The attempt went past a spend budget, which stops the turn. */
object BudgetReached : BudgetEvent("budget_reached") {
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "passed its ${data[Spend.BUDGET]} budget, ${Spend.humanBudgetUse(data)}, the turn stops"
}
