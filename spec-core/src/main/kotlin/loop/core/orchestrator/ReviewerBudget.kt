package loop.core.orchestrator

import loop.core.workflow.WorkflowFile

/**
 * The spend budget check of the merge risk reviewer (m2-28), which the auto merge gate calls before the reviewer runs.
 * It reads the spend through the one [SpendReading] (bl-84), so the merge module carries no spend reading of its own.
 */
internal class ReviewerBudget(
    private val spendReading: SpendReading,
) {
    /**
     * Why no reviewer may spend now: a spend budget of the issue or of the project's day already past its limit, the
     * attempts running beside the gate counted from memory, or a ledger that cannot be read for them, which fails
     * closed. Null when it may.
     */
    suspend fun passed(
        workflow: WorkflowFile,
        issueId: String,
    ): String? {
        val budgets = BudgetMeter(workflow.config.budgets)
        if (!budgets.readsIssue && !budgets.readsDay) return null
        val read = spendReading.read(issueId.takeIf { budgets.readsIssue }, budgets.readsDay)
        if (listOfNotNull(read.issue, read.day).any { it.ledger == null }) return "the ledger cannot be read for the spend budgets"
        return budgets
            .check(Spend(), read.issue?.total() ?: Spend(), read.day?.total() ?: Spend())
            .breach
            ?.budget
            ?.key
            ?.let { "the spend budget $it is used up" }
    }
}
