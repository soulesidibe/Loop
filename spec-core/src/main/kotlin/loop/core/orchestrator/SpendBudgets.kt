package loop.core.orchestrator

import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.LedgerRow
import loop.api.ProjectId
import loop.core.workflow.BudgetsConfig
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import loop.core.events.Spend as SpendKeys

/** Where a budget measures spend (m2-20): one attempt, every attempt of the issue, or of the project in 24 hours. */
internal enum class BudgetScope {
    ATTEMPT,
    ISSUE,
    DAY,
}

/** One spend budget of `budgets:` (m2-20), named by its workflow [key]. [usd] is true for a dollar budget. */
internal enum class Budget(
    val key: String,
    val scope: BudgetScope,
    val usd: Boolean,
) {
    PER_ATTEMPT_TOKENS("per_attempt_tokens", BudgetScope.ATTEMPT, usd = false),
    PER_ATTEMPT_USD("per_attempt_usd", BudgetScope.ATTEMPT, usd = true),
    PER_ISSUE_TOKENS("per_issue_tokens", BudgetScope.ISSUE, usd = false),
    PER_ISSUE_USD("per_issue_usd", BudgetScope.ISSUE, usd = true),
    PER_DAY_TOKENS("per_day_tokens", BudgetScope.DAY, usd = false),
    PER_DAY_USD("per_day_usd", BudgetScope.DAY, usd = true),
    ;

    /** This budget's limit in [config], null when it is not set. */
    fun limitIn(config: BudgetsConfig): Number? =
        when (this) {
            PER_ATTEMPT_TOKENS -> config.perAttemptTokens
            PER_ATTEMPT_USD -> config.perAttemptUsd
            PER_ISSUE_TOKENS -> config.perIssueTokens
            PER_ISSUE_USD -> config.perIssueUsd
            PER_DAY_TOKENS -> config.perDayTokens
            PER_DAY_USD -> config.perDayUsd
        }
}

/**
 * Tokens (the m1-08 total, cache reads included) and dollars spent. A runner that reports no cost spends no
 * dollars: an unknown cost never meets a dollar budget.
 */
internal data class Spend(
    val tokens: Long = 0,
    val usd: Double = 0.0,
) {
    operator fun plus(other: Spend): Spend = Spend(TokenMeter.totalOf(tokens, other.tokens), usd + other.usd)
}

/** A [budget] with what was [used] of its [limit], both as printed in events and logs. */
internal data class BudgetLevel(
    val budget: Budget,
    val used: String,
    val limit: String,
) {
    /** The data of `budget_warning`, `budget_reached` and the budget park. */
    fun data(): Map<String, String> = mapOf(SpendKeys.BUDGET to budget.key, SpendKeys.USED to used, SpendKeys.LIMIT to limit)
}

/** What one [BudgetMeter.check] found: the budgets newly past 80 percent, and the first budget past its limit. */
internal data class BudgetCheck(
    val warnings: List<BudgetLevel>,
    val breach: BudgetLevel?,
)

/**
 * The spend budgets of one attempt (m2-20), read once when it starts. A budget warns once its spend reaches 80
 * percent of its limit, once for the attempt, and is breached when its spend is above the limit: exactly the limit
 * is allowed, like the m1-08 token cap it replaces.
 */
internal class BudgetMeter(
    config: BudgetsConfig,
) {
    private val limits: Map<Budget, Number> = Budget.entries.mapNotNull { b -> b.limitIn(config)?.let { b to it } }.toMap()
    private val warned = mutableSetOf<Budget>()

    /** True when a budget measures the issue's earlier attempts, which the caller reads from the ledger. */
    val readsIssue: Boolean = limits.keys.any { it.scope == BudgetScope.ISSUE }

    /** True when a budget measures the project's last 24 hours, which the caller reads from the ledger. */
    val readsDay: Boolean = limits.keys.any { it.scope == BudgetScope.DAY }

    /** True when any budget is set. */
    val any: Boolean = limits.isNotEmpty()

    /** Checks every budget against the spend of its scope: [attempt], [issue] and [day] are each a scope's total. */
    fun check(
        attempt: Spend,
        issue: Spend,
        day: Spend,
    ): BudgetCheck {
        val warnings = mutableListOf<BudgetLevel>()
        var breach: BudgetLevel? = null
        for ((budget, limit) in limits) {
            val spend =
                when (budget.scope) {
                    BudgetScope.ATTEMPT -> attempt
                    BudgetScope.ISSUE -> issue
                    BudgetScope.DAY -> day
                }
            val used: Number = if (budget.usd) spend.usd else spend.tokens
            val level = BudgetLevel(budget, print(used), print(limit))
            val over: Boolean
            val warn: Boolean
            if (budget.usd) {
                over = used.toDouble() > limit.toDouble()
                warn = used.toDouble() >= limit.toDouble() * WARN_AT
            } else {
                over = used.toLong() > limit.toLong()
                warn = used.toLong().toDouble() >= limit.toLong().toDouble() * WARN_AT
            }
            if (warn && warned.add(budget)) warnings += level
            if (over && breach == null) breach = level
        }
        return BudgetCheck(warnings, breach)
    }

    private fun print(value: Number): String =
        when (value) {
            is Double -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            else -> value.toString()
        }

    private companion object {
        /** The share of a budget at which the attempt warns. */
        const val WARN_AT = 0.8
    }
}

/** What an attempt running in this process spent so far, and the issue it works on (m2-20). */
internal data class LiveSpend(
    val issueId: String,
    val spend: Spend,
)

/**
 * The spend of one budget scope, the issue or the day (bl-84). [ledger] is what its ledger rows count, the rows of
 * the attempts still running left out; null when the read failed, with its [failure]. [running] is what each attempt
 * still running in this process spent, its row not written yet, in the order of the live map.
 */
internal data class ScopeSpend(
    val ledger: Spend?,
    private val running: List<Spend>,
    val failure: Throwable? = null,
) {
    /** What the attempts still running spent. */
    val live: Spend get() = running.fold(Spend()) { sum, spend -> sum + spend }

    /** The scope's total: [ledger], this read's unless the caller keeps another, plus each running attempt's spend. */
    fun total(ledger: Spend? = this.ledger): Spend = running.fold(ledger ?: Spend()) { sum, spend -> sum + spend }
}

/** The scopes one [SpendReading.read] was asked for: the issue's and the day's, null when not asked. */
internal data class SpendRead(
    val issue: ScopeSpend?,
    val day: ScopeSpend?,
)

/**
 * The one reading of what was spent against the issue and day budgets (m2-20, bl-84), for an attempt's budgets and the
 * auto merge gate alike: the ledger rows of the finished attempts, and the [live] spend of the attempts still running
 * in this process, whose rows are written only when they end. [BudgetMeter] judges the spend; this only reads it.
 *
 * The live map is read once, before the ledger, and the ledger rows of the attempts in it are left out: an attempt
 * that ends between the two reads, its row written, then removed from the live map, is counted once, from memory.
 */
internal class SpendReading(
    private val attemptLedger: AttemptLedger,
    private val projectId: ProjectId,
    private val clock: Clock,
    private val live: () -> Map<AttemptId, LiveSpend>,
) {
    /**
     * Reads the scopes asked: the issue [issueId] when not null, the project's last 24 hours when [day]. Each scope is
     * read on its own and fails on its own. [own] is the calling attempt with its current spend, which replaces what
     * the live map holds of it.
     */
    suspend fun read(
        issueId: String?,
        day: Boolean,
        own: Pair<AttemptId, LiveSpend>? = null,
    ): SpendRead {
        val running = live() + listOfNotNull(own)
        val issue =
            issueId?.let { id ->
                scope(running.keys, running.values.filter { it.issueId == id }) { attemptLedger.attemptRows(projectId, id) }
            }
        val last24Hours =
            if (day) scope(running.keys, running.values.toList()) { attemptLedger.attemptRowsSince(projectId, clock.now() - DAY) } else null
        return SpendRead(issue, last24Hours)
    }

    private suspend fun scope(
        runningIds: Set<AttemptId>,
        running: List<LiveSpend>,
        read: suspend () -> Result<List<LedgerRow>>,
    ): ScopeSpend {
        val spends = running.map { it.spend }
        val rows =
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return rows.fold(
            onSuccess = { ScopeSpend(spendOf(it, runningIds), spends) },
            onFailure = { ScopeSpend(null, spends, it) },
        )
    }

    companion object {
        /** The window of the day budgets (m2-20): the last 24 hours, rolling. */
        val DAY = 24.hours
    }
}

/** The spend of the ledger [rows], leaving out the attempts still [running], which are counted apart. */
internal fun spendOf(
    rows: List<LedgerRow>,
    running: Set<AttemptId>,
): Spend =
    rows
        .filter { it.attempt.attemptId !in running }
        .fold(Spend()) { sum, row -> sum + Spend(row.attempt.tokens?.total ?: 0, row.attempt.costUsd ?: 0.0) }
