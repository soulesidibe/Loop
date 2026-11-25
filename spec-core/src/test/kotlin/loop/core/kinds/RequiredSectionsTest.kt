package loop.core.kinds

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m4-23, the required sections of a report (`verify.require_sections`), a pure function read with the heading reader of
 * the readiness check: a section is present when a heading names it, trimmed and case insensitive, and holds at least
 * one non blank line before the next heading of the same or a higher level.
 */
class RequiredSectionsTest {
    private val report =
        """
        |# Report on queues
        |
        |## Summary
        |
        |Queues are fine.
        |
        |## Sources
        |
        |- https://example.com/queues
        """.trimMargin()

    @Test
    fun `a report holding every required section misses none`() {
        assertEquals(emptyList(), RequiredSections.missing(report, listOf("Summary", "Sources")))
    }

    @Test
    fun `each missing section is named, in config order`() {
        assertEquals(
            listOf("Method", "Limits"),
            RequiredSections.missing(report, listOf("Method", "Summary", "Limits", "Sources")),
        )
    }

    @Test
    fun `a heading of any level names a section, trimmed and case insensitive`() {
        val text = "### summary  \nshort\n# SOURCES\n- a link\n"

        assertEquals(emptyList(), RequiredSections.missing(text, listOf(" Summary ", "Sources")))
    }

    @Test
    fun `an empty section counts as missing, a deeper heading with text keeps it present`() {
        val text = "## Summary\n\n## Sources\n\n### Primary\n- a link\n## Notes\n   \n"

        assertEquals(listOf("Summary", "Notes"), RequiredSections.missing(text, listOf("Summary", "Sources", "Notes")))
    }

    @Test
    fun `a heading inside a fence or not at column 0 names no section`() {
        val text = "```\n## Summary\ntext\n```\n ## Sources\n- a link\n"

        assertEquals(listOf("Summary", "Sources"), RequiredSections.missing(text, listOf("Summary", "Sources")))
    }

    @Test
    fun `a heading that only opens with the name is another section`() {
        assertEquals(listOf("Sources"), RequiredSections.missing("## Sources cited\n- a link\n", listOf("Sources")))
    }

    @Test
    fun `no required section misses nothing, an empty text misses every one`() {
        assertEquals(emptyList(), RequiredSections.missing("", emptyList()))
        assertEquals(listOf("Summary"), RequiredSections.missing("", listOf("Summary")))
    }
}
