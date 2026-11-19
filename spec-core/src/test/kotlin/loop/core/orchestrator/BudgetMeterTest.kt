package loop.core.orchestrator

import loop.core.workflow.BudgetsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The spend budgets of one attempt (m2-20): a warning at 80 percent once per budget, a breach past 100 percent. */
class BudgetMeterTest {
    private val none = Spend()

    @Test
    fun `no budget set warns and breaches nothing`() {
        val meter = BudgetMeter(BudgetsConfig())

        val check = meter.check(Spend(Long.MAX_VALUE, 1e12), Spend(Long.MAX_VALUE, 1e12), Spend(Long.MAX_VALUE, 1e12))

        assertEquals(emptyList(), check.warnings)
        assertNull(check.breach)
        assertFalse(meter.readsIssue)
        assertFalse(meter.readsDay)
    }

    @Test
    fun `crossing 80 percent warns once per budget and attempt`() {
        val meter = BudgetMeter(BudgetsConfig(perAttemptTokens = 1_000, perAttemptUsd = 10.0))

        assertEquals(emptyList(), meter.check(Spend(799, 7.99), none, none).warnings)
        val first = meter.check(Spend(800, 1.0), none, none)
        val second = meter.check(Spend(900, 8.0), none, none)
        val third = meter.check(Spend(950, 9.0), none, none)

        assertEquals(listOf(BudgetLevel(Budget.PER_ATTEMPT_TOKENS, "800", "1000")), first.warnings)
        assertEquals(listOf(BudgetLevel(Budget.PER_ATTEMPT_USD, "8", "10")), second.warnings)
        assertEquals(emptyList(), third.warnings)
        assertNull(third.breach)
    }

    @Test
    fun `exactly the limit is allowed and one more breaches`() {
        val meter = BudgetMeter(BudgetsConfig(perAttemptTokens = 1_000))

        assertNull(meter.check(Spend(1_000, 0.0), none, none).breach)
        assertEquals(BudgetLevel(Budget.PER_ATTEMPT_TOKENS, "1001", "1000"), meter.check(Spend(1_001, 0.0), none, none).breach)
    }

    @Test
    fun `a report that jumps past 100 percent warns and breaches together`() {
        val meter = BudgetMeter(BudgetsConfig(perIssueUsd = 25.0))

        val check = meter.check(none, Spend(0, 30.5), none)

        assertEquals(listOf(BudgetLevel(Budget.PER_ISSUE_USD, "30.5", "25")), check.warnings)
        assertEquals(BudgetLevel(Budget.PER_ISSUE_USD, "30.5", "25"), check.breach)
    }

    @Test
    fun `each scope is measured with its own spend`() {
        val meter = BudgetMeter(BudgetsConfig(perAttemptTokens = 1_000, perIssueTokens = 1_000, perDayTokens = 1_000))

        val check = meter.check(Spend(100, 0.0), Spend(850, 0.0), Spend(1_200, 0.0))

        assertEquals(listOf(Budget.PER_ISSUE_TOKENS, Budget.PER_DAY_TOKENS), check.warnings.map { it.budget })
        assertEquals(Budget.PER_DAY_TOKENS, check.breach?.budget)
        assertTrue(meter.readsIssue)
        assertTrue(meter.readsDay)
    }

    @Test
    fun `the budget keys are the workflow keys`() {
        assertEquals(
            listOf("per_attempt_tokens", "per_attempt_usd", "per_issue_tokens", "per_issue_usd", "per_day_tokens", "per_day_usd"),
            Budget.entries.map { it.key },
        )
    }

    @Test
    fun `dollars are printed plain, without an exponent or trailing zeros`() {
        val meter = BudgetMeter(BudgetsConfig(perAttemptUsd = 0.001))

        assertEquals(BudgetLevel(Budget.PER_ATTEMPT_USD, "0.0025", "0.001"), meter.check(Spend(0, 0.0025), none, none).breach)
    }
}
