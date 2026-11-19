package loop.core.kinds

/**
 * The sections of an issue description, shared by the readiness check (m2-11) and the acceptance criteria
 * (m2-09). A heading is a column 0 line of `#` to `######` and a blank, outside ``` and ~~~ fences; its
 * section runs to the next heading of the same or a higher level, so a deeper heading stays inside it.
 */
internal object MarkdownSections {
    class Heading(
        val line: Int,
        val level: Int,
        val text: String,
    )

    fun headings(lines: List<String>): List<Heading> {
        val found = mutableListOf<Heading>()
        var fence: Char? = null
        lines.forEachIndexed { index, line ->
            val marker = fenceMarker(line)
            when {
                fence != null -> if (marker == fence) fence = null
                marker != null -> fence = marker
                else ->
                    HEADING.matchEntire(line)?.let {
                        found += Heading(index, it.groupValues[1].length, it.groupValues[2].trim())
                    }
            }
        }
        return found
    }

    /** The lines under each heading named [name], trimmed and case insensitive, in order, heading excluded. */
    fun sections(
        lines: List<String>,
        name: String,
    ): List<IntRange> = sections(lines) { it.equals(name.trim(), ignoreCase = true) }

    /**
     * The lines under each heading whose text opens with [name], case insensitive, alone or followed by a
     * character that is not a letter or a digit, such as `Acceptance criteria:` or `Acceptance criteria (m2-09)`.
     */
    fun sectionsOpeningWith(
        lines: List<String>,
        name: String,
    ): List<IntRange> =
        sections(lines) { text ->
            text.startsWith(name.trim(), ignoreCase = true) && text.getOrNull(name.trim().length)?.isLetterOrDigit() != true
        }

    private fun sections(
        lines: List<String>,
        named: (String) -> Boolean,
    ): List<IntRange> {
        val headings = headings(lines)
        return headings.indices
            .filter { named(headings[it].text) }
            .map { i ->
                val heading = headings[i]
                val end = headings.drop(i + 1).firstOrNull { it.level <= heading.level }?.line ?: lines.size
                heading.line + 1 until end
            }
    }

    /** The fence character a line opens or closes a fence with, ``` or ~~~ at its start, else null. */
    fun fenceMarker(line: String): Char? = line.firstOrNull()?.takeIf { (it == '`' || it == '~') && line.startsWith("$it$it$it") }

    private val HEADING = Regex("""(#{1,6})[ \t]+(.*)""")
}
