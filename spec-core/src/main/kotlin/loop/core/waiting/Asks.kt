package loop.core.waiting

/**
 * m4-31: what Loop writes for an agent's `ask`, a question a human must answer. In M4 every question goes to a human,
 * whatever its kind; routing engineering, design and product questions to manager agents is M6. The wait itself is
 * [Wait.Ask].
 */
object Asks {
    /** The kinds of a question, as the `ask` tool takes them (PLAN 6.10). */
    val KINDS: List<String> = listOf("engineering", "design", "product", "human")

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
