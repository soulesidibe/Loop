package loop.core.kinds

import loop.core.workflow.ReadyConfig

/**
 * The readiness check of a kind (m2-11, plan 6.2), run in code before dispatch. A section is present when
 * the description has a column 0 heading (`#` to `######`, outside ``` and ~~~ fences) whose text is the
 * section name, trimmed and case insensitive, followed by at least one non blank line before the next
 * heading of the same or a higher level. An empty section counts as missing.
 */
object Readiness {
    /** The sections of [ready] that [description] lacks, in config order; empty when the issue is ready. */
    fun missingSections(
        description: String?,
        ready: ReadyConfig,
    ): List<String> {
        val lines = description.orEmpty().lines()
        return ready.requireSections.filter { name ->
            MarkdownSections.sections(lines, name).none { section -> section.any { lines[it].isNotBlank() } }
        }
    }
}
