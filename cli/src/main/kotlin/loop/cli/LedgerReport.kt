package loop.cli

import loop.api.LedgerRow
import loop.api.ProjectId
import loop.core.merge.RiskReviewRow
import java.util.Locale
import kotlin.time.Instant

/**
 * A delivered issue of `loop report` (m4-38): its [name] as shown and every ledger row of it, in order, its merge risk
 * reviews included. A review's spend is part of the issue's cost, but the issue's [last] attempt is never a review.
 */
internal data class DeliveredIssue(
    val name: String,
    val rows: List<LedgerRow>,
) {
    /** The last row of the issue that is not a merge risk review, else its last row. */
    val last: LedgerRow? get() = rows.lastOrNull { !isReview(it) } ?: rows.lastOrNull()
}

/** True for the ledger row of a merge risk review (m2-28), which the risk reviewer writes, not an attempt. */
internal fun isReview(row: LedgerRow): Boolean = row.attempt.setup.runner == RiskReviewRow.RUNNER

/**
 * What `loop report` prints (m4-38, PLAN 6.8) for the window that starts at [start], typed as [label]: the [delivered]
 * issues, the reasons of the refuted verdicts in the window ([refutations], one per verdict) and the ledger rows of the
 * [attempts] that finished in it. A measure nobody reported prints as not reported, never zero, and stays out of every
 * average, which says how many attempts it rests on. Human touches print with the not reliable mark until bl-64.
 */
internal class LedgerReport(
    private val projectId: ProjectId,
    private val start: Instant,
    private val label: String,
    private val delivered: List<DeliveredIssue>,
    private val refutations: List<String>,
    private val attempts: List<LedgerRow>,
) {
    fun render(): String {
        val since = "${Instant.fromEpochSeconds(start.epochSeconds)} ($label)"
        val sections = listOf(deliveredLines(since), refutationLines(), comparisonLines())
        return (listOf("Report of project ${projectId.value} since $since") + sections.flatMap { listOf("") + it })
            .joinToString("\n")
    }

    private fun deliveredLines(since: String): List<String> {
        if (delivered.isEmpty()) return listOf("Nothing delivered since $since.")
        val byKind =
            delivered
                .groupingBy { "${kindOf(it)}, ${trackerOf(it)}" }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { "  ${it.key}: ${it.value}" }
        val untouched = delivered.count { issue -> issue.rows.sumOf { it.humanTouches } == 0 }
        return listOf("Delivered issues: ${delivered.size}") + byKind +
            "Delivered with zero human touches: $untouched of ${delivered.size} $NOT_RELIABLE" +
            costSummary() + delivered.map { "  ${it.name}, ${kindOf(it)}, ${trackerOf(it)}: ${issueCost(it)}" }
    }

    /** The average cost of the delivered issues whose every attempt reported a cost, the others left out. */
    private fun costSummary(): String {
        val full = delivered.filter { issue -> issue.rows.isNotEmpty() && issue.rows.all { it.attempt.costUsd != null } }
        if (full.isEmpty()) return "Cost per delivered issue: $NOT_REPORTED, no delivered issue has a reported cost"
        val total = full.sumOf { issue -> issue.rows.sumOf { it.attempt.costUsd!! } }
        val rows = full.flatMap { it.rows }
        val issues = count(full.size, "issue")
        val over = if (rows.any(::isReview)) "$issues, ${rowsText(rows)}" else "$issues and ${rowsText(rows)}"
        val left = delivered.size - full.size
        val leftOut = if (left == 0) "" else ", ${count(left, "issue")} left out, ${if (left == 1) "its" else "their"} cost $NOT_REPORTED"
        return "Cost per delivered issue: ${usd(total / full.size)} on average over $over$leftOut"
    }

    private fun issueCost(issue: DeliveredIssue): String {
        val rows = issue.rows
        if (rows.isEmpty()) return "$NOT_REPORTED, no attempt recorded"
        val reported = rows.mapNotNull { it.attempt.costUsd }
        return when (reported.size) {
            0 -> "$NOT_REPORTED, ${rowsText(rows)}"
            rows.size -> "${usd(reported.sum())} over ${rowsText(rows)}"
            else -> "${usd(reported.sum())} over ${reported.size} of ${rowsText(rows)}, ${rows.size - reported.size} $NOT_REPORTED"
        }
    }

    private fun refutationLines(): List<String> {
        if (refutations.isEmpty()) return listOf("Refutation reasons: none")
        val reasons =
            refutations
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { "  ${it.key}: ${it.value}" }
        return listOf("Refutation reasons: ${count(refutations.size, "refuted verdict")}") + reasons
    }

    /**
     * The attempts of the window by runner, model and template, each with its verdicts, the delivered issues whose last
     * attempt ran with it, and its average cost per attempt over the attempts that reported one.
     */
    private fun comparisonLines(): List<String> {
        val lastSetups = delivered.mapNotNull { issue -> issue.last?.let(::setupOf) }
        val groups = (attempts.map(::setupOf) + lastSetups).distinct().sortedWith(SETUP_ORDER)
        if (groups.isEmpty()) return listOf("Comparison by runner, model and template: no attempt finished since the start of the window")
        val lines =
            groups.map { setup ->
                val rows = attempts.filter { setupOf(it) == setup }
                val verified = rows.count { it.attempt.verdict == VERIFIED }
                val refuted = rows.count { it.attempt.verdict == REFUTED }
                val deliveredCount = lastSetups.count { it == setup }
                val model = setup.model ?: "model not recorded"
                "  ${setup.runner}, $model, template ${setup.template.take(TEMPLATE_CHARS)}: ${count(rows.size, "attempt")}, " +
                    "$verified verified, $refuted refuted, $deliveredCount delivered, ${attemptCost(rows)}"
            }
        return listOf("Comparison by runner, model and template, attempts finished since the start of the window:") + lines
    }

    private fun attemptCost(rows: List<LedgerRow>): String {
        val reported = rows.mapNotNull { it.attempt.costUsd }
        if (reported.isEmpty()) return "cost $NOT_REPORTED"
        val missing = rows.size - reported.size
        val note = if (missing == 0) "" else ", $missing $NOT_REPORTED"
        return "${usd(reported.average())} per attempt on average over ${count(reported.size, "attempt")}$note"
    }

    private data class Setup(
        val runner: String,
        val model: String?,
        val template: String,
    )

    private companion object {
        const val NOT_REPORTED = "not reported"
        const val NOT_RELIABLE = "(not reliable, see bl-64)"
        const val VERIFIED = "verified"
        const val REFUTED = "refuted"
        const val TEMPLATE_CHARS = 8
        val SETUP_ORDER: Comparator<Setup> =
            compareBy<Setup> { it.runner }.thenBy(nullsLast()) { it.model }.thenBy { it.template }

        fun setupOf(row: LedgerRow): Setup = row.attempt.setup.let { Setup(it.runner, it.model, it.templateVersion) }

        fun kindOf(issue: DeliveredIssue): String =
            issue.last
                ?.attempt
                ?.setup
                ?.kind ?: "kind not recorded"

        fun trackerOf(issue: DeliveredIssue): String =
            issue.last
                ?.attempt
                ?.setup
                ?.tracker ?: "tracker not recorded"

        /** How many attempts [rows] hold, then how many merge risk reviews when there are any. */
        fun rowsText(rows: List<LedgerRow>): String {
            val reviews = rows.count(::isReview)
            val attempts = count(rows.size - reviews, "attempt")
            return if (reviews == 0) attempts else "$attempts and ${count(reviews, "merge risk review")}"
        }

        fun usd(amount: Double): String = String.format(Locale.ROOT, "%.2f usd", amount)

        fun count(
            n: Int,
            noun: String,
        ): String = if (n == 1) "1 $noun" else "$n ${noun}s"
    }
}
