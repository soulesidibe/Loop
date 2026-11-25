package loop.core.waiting

import loop.core.events.EventDefinition
import loop.core.events.IssueCommented
import loop.core.events.QuestionAsked
import loop.core.events.Tracker
import kotlin.coroutines.cancellation.CancellationException

/**
 * m4-31: what Loop writes for an agent's `ask`, a question a human must answer. In M4 every question goes to a human,
 * whatever its kind; routing engineering, design and product questions to manager agents is M6. The wait itself is
 * [Wait.Ask].
 */
object Asks {
    /** The kinds of a question, as the `ask` tool takes them (PLAN 6.10). */
    val KINDS: List<String> = listOf("engineering", "design", "product", "human")

    /** Why a second `ask` of one attempt fails: the first question already ends it. */
    const val SECOND_ASK = "ask was already called in this attempt: Loop posts one question per attempt"

    /** One question of an `ask` call: its [kind], the [text], the [options] the agent sees and its [recommendation]. */
    data class Question(
        val kind: String,
        val text: String,
        val options: List<String>,
        val recommendation: String?,
    )

    /**
     * Why a queued `ask` cannot apply at the drain, null when it can: a wait needs Loop mode ([loopMode]), an attempt
     * asks once ([asked], a wait already started), and a result of the same turn ([submitted]) already ends it.
     */
    fun refusal(
        loopMode: Boolean,
        asked: Boolean,
        submitted: Boolean,
    ): String? =
        when {
            !loopMode -> "ask needs Loop mode, a kinds: key in WORKFLOW.md"
            asked -> SECOND_ASK
            submitted -> "the attempt submitted a result in this turn, so it cannot ask a question too"
            else -> null
        }

    /**
     * Applies one queued `ask` host side at the drain: posts its [comment], [redact]ed, through [post], records
     * `issue_commented` and `question_asked` through [record], then saves [wait] through [save], which also tells the
     * human. The reason it failed, null once the wait is saved. A comment that cannot be posted records and saves
     * nothing, so the issue does not wait for a question nobody can read.
     */
    suspend fun start(
        question: Question,
        wait: Wait.Ask,
        redact: (String) -> String,
        post: suspend (String) -> Result<Unit>,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
        save: suspend (Wait.Ask) -> Unit,
    ): String? {
        val text = redact(comment(question.kind, question.text, question.options, question.recommendation)).trim()
        val failure =
            try {
                post(text).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure != null) return failure.message ?: failure::class.simpleName ?: "unknown error"
        val chars = text.length.toString()
        record(IssueCommented, mapOf(Tracker.CHARS to chars, Tracker.BY to Tracker.BY_HOST, Tracker.REASON to IssueCommented.QUESTION))
        val options = question.options.count { it.isNotBlank() }
        record(
            QuestionAsked,
            mapOf(
                QuestionAsked.QUESTION_KIND to question.kind,
                QuestionAsked.OPTIONS to options.toString(),
                Tracker.CHARS to chars,
            ),
        )
        save(wait)
        return null
    }

    /**
     * The comment Loop posts on the issue for a question of [kind]: the [question], then the [options] the agent sees and
     * the one it recommends, each left out when blank. Not redacted here: the caller redacts the whole text.
     */
    fun comment(
        kind: String,
        question: String,
        options: List<String>,
        recommendation: String?,
    ): String {
        val listed = options.map { it.trim() }.filter { it.isNotEmpty() }
        val recommended = recommendation?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Question for a human ($kind): ${question.trim()}")
            if (listed.isNotEmpty()) append("\n\nOptions:\n").append(listed.joinToString("\n") { "- $it" })
            if (recommended != null) append("\n\nRecommendation: $recommended")
        }
    }

    /** The reason of the `question` notice of a question of [kind]. */
    fun noticeReason(kind: String): String {
        val article = if (kind.firstOrNull()?.lowercaseChar() in VOWELS) "an" else "a"
        return "the agent asks $article $kind question"
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
}
