package loop.core.kinds

/**
 * The required sections of a markdown text (m4-23), shared by the readiness check of an issue description (m2-11) and
 * the report check of `verify.require_sections`. A section is present when the text has a column 0 heading (`#` to
 * `######`, outside ``` and ~~~ fences) whose text is the section name, trimmed and case insensitive, followed by at
 * least one non blank line before the next heading of the same or a higher level. An empty section counts as missing.
 */
object RequiredSections {
    /** The [names] that [text] lacks, in the order given; empty when every one is present. A pure function. */
    fun missing(
        text: String,
        names: List<String>,
    ): List<String> {
        val lines = text.lines()
        return names.filter { name ->
            MarkdownSections.sections(lines, name).none { section -> section.any { lines[it].isNotBlank() } }
        }
    }
}
