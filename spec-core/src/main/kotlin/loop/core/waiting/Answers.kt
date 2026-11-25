package loop.core.waiting

/**
 * m4-52: what `loop answer` writes for the answer to an agent's question, and what the attempt after it is shown. The
 * answer is kept in the ask wait ([Wait.Ask.answer]); an answered wait no longer holds its issue, which dispatches again
 * in its kept workspace, and the attempt that renders the answer ends the wait.
 */
object Answers {
    /** Why [wait] cannot take an answer, null when it can: only an ask wait not answered yet takes one. */
    fun refusal(wait: Wait): String? =
        when {
            wait !is Wait.Ask -> "is waiting for ${wait.reason.wire}, not for an answer"
            wait.answered -> "is already answered, Loop dispatches it with the first answer"
            else -> null
        }

    /** The comment `loop answer` posts on the issue: [answer], trimmed, given by [by]. Not redacted here. */
    fun comment(
        by: String,
        answer: String,
    ): String = "Answer from $by: ${answer.trim()}"

    /**
     * `loop.answer` of the attempt after the answer: the question as Loop posted it, then the answer as [comment] words
     * it. The answer alone for a wait stored before m4-52, which holds no question; null when [wait] is not answered.
     */
    fun prompt(wait: Wait.Ask): String? {
        val answer = wait.answer?.takeIf { wait.answered } ?: return null
        val answered = comment(wait.answeredBy.orEmpty().ifBlank { UNKNOWN_HUMAN }, answer)
        val question = wait.question?.trim()?.takeIf { it.isNotEmpty() } ?: return answered
        return "$question\n\n$answered"
    }

    /** Who answered when the wait does not say. */
    private const val UNKNOWN_HUMAN = "a human"
}
