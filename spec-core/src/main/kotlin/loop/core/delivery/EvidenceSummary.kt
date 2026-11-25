package loop.core.delivery

import kotlin.time.Duration.Companion.milliseconds

/**
 * The evidence summary Loop posts on an issue whose verified result waits for approval (m2-24, plan 6.5
 * `comment: evidence_summary`): the attempt, the workspace fingerprint, each check that passed with its exit
 * code, duration and evidence file, and that an operator approves it. A holdout check (m2-14) says only that it passed. Built
 * from the verification's facts, never from the agent's words. The caller redacts the text before it writes it.
 */
object EvidenceSummary {
    private const val HUMAN = "human"

    /** A command is shown by its first line, cut to this many characters. */
    const val COMMAND_MAX = 200

    /**
     * One check run that passed: its [index], [command], [exitCode], duration, whether it passed on its [rerun],
     * its [evidence] file, and whether it is a [holdout].
     */
    data class Check(
        val index: Int,
        val command: String,
        val exitCode: Int?,
        val durationMs: Long,
        val rerun: Boolean,
        val evidence: String?,
        val holdout: Boolean = false,
    )

    /**
     * The summary of the verified result of [attemptId] on [identifier], waiting to move to [to]. [notes] are lines on
     * the delivery shown before the approval line (m4-22), and [policy] names the delivery policy that waits, `auto`
     * when an attach delivery fell back to the approval.
     */
    fun text(
        identifier: String,
        attemptId: String,
        to: String,
        fingerprint: String,
        checks: List<Check>,
        notes: List<String> = emptyList(),
        policy: String = HUMAN,
    ): String =
        buildString {
            appendLine("Loop verified the result of attempt ${attemptId.take(SHORT_ID)}: every check passed.")
            appendLine()
            appendLine("Fingerprint: $fingerprint")
            appendLine("Checks:")
            lines(checks).forEach { appendLine(it) }
            appendLine()
            notes.forEach { appendLine(it) }
            // Addressed to the operator, with no command to run: an agent reads this comment on its issue (m2-24).
            appendLine("The delivery policy is $policy: $identifier moves to $to once an operator approves it.")
            append("`loop show $attemptId --evidence --check <n>` prints the evidence of check n.")
        }

    /** One line per check, as the summary and the pull request body show them (m2-25), stored with an approval wait. */
    fun lines(checks: List<Check>): List<String> = checks.map(::line)

    /**
     * The body of the pull request that delivers the verified result of [attemptId] on [identifier] (m2-25): the
     * verdict, the [fingerprint], each check line as [lines] wrote it, the number of [judgmentCalls] left to a human,
     * and the issue [link]. Built from the verification's facts, never from the agent's result file or words. The
     * caller redacts it.
     */
    fun pullRequestBody(
        identifier: String,
        attemptId: String?,
        fingerprint: String,
        checks: List<String>,
        judgmentCalls: Int,
        link: String,
    ): String =
        buildString {
            val attempt = attemptId?.let { " of attempt ${it.take(SHORT_ID)}" }.orEmpty()
            appendLine("Loop verified the result$attempt for $identifier: every check passed.")
            appendLine()
            appendLine("Verdict: verified")
            appendLine("Fingerprint: $fingerprint")
            appendLine("Checks:")
            checks.forEach { appendLine(it) }
            appendLine("Judgment calls left to a human: $judgmentCalls")
            appendLine()
            append(link)
        }

    private fun line(check: Check): String {
        if (check.holdout) return "- hidden check ${check.index}: passed"
        val command =
            check.command
                .lineSequence()
                .first()
                .take(COMMAND_MAX)
        val how = if (check.rerun) "passed on its rerun" else "passed"
        val exit = check.exitCode?.let { " with exit code $it" }.orEmpty()
        val evidence = check.evidence?.let { "evidence $it" } ?: "no evidence saved"
        return "- check ${check.index}, $command: $how$exit in ${check.durationMs.milliseconds}, $evidence"
    }

    private const val SHORT_ID = 8
}
