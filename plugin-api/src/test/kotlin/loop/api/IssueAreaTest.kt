package loop.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** m4-17, bl-10: the entries of an issue's area, repository relative paths, normalized or refused. */
class IssueAreaTest {
    @Test
    fun `an entry is trimmed, loses its dot and empty segments and its trailing slash`() {
        val cases =
            mapOf(
                "spec-core" to "spec-core",
                " spec-core/ " to "spec-core",
                "./spec-core" to "spec-core",
                "spec-core//src/./main/" to "spec-core/src/main",
                "docs/spec-decisions.md" to "docs/spec-decisions.md",
                "Docs" to "Docs",
                "spec-core / src" to "spec-core/src",
                // NFC, so a decomposed accent reads like the composed one.
                "cafe\u0301/menu" to "caf\u00e9/menu",
            )
        cases.forEach { (entry, normalized) -> assertEquals(normalized, IssueArea.normalize(entry), entry) }
    }

    @Test
    fun `an entry that is no repository relative path is refused`() {
        listOf(
            "",
            "   ",
            ".",
            "./",
            "/spec-core",
            "../spec-core",
            "spec-core/../cli",
            "spec-core/*",
            "**/Orchestrator.kt",
            "spec-core/?.kt",
            "spec-core/[ab]",
            "spec-core/{a,b}",
            "spec-core\\src",
            "spec-core\tsrc",
            "`spec-core`",
            // A space inside a segment: a prose line such as `Area: the orchestrator` is no path list.
            "a folder/with spaces",
            "the orchestrator",
            "spec\u00a0core",
            // An invisible format character, such as a zero width space.
            "spec-core\u200b",
        ).forEach { assertNull(IssueArea.normalize(it), "\"$it\"") }
    }

    @Test
    fun `a list is normalized in the order written, each entry once, and refused whole when one entry is malformed`() {
        assertEquals(listOf("spec-core", "docs"), IssueArea.parse(listOf("spec-core/", "docs", "./spec-core")))
        assertEquals(emptyList(), IssueArea.parse(emptyList()))
        assertNull(IssueArea.parse(listOf("spec-core", "/abs")))
    }

    @Test
    fun `an area holds at most the capped number of entries`() {
        val capped = List(IssueArea.MAX_ENTRIES) { "dir-$it" }

        assertEquals(capped, IssueArea.parse(capped))
        assertNull(IssueArea.parse(capped + "one-more"))
    }

    @Test
    fun `an issue has no area by default`() {
        val issue = Issue("1", "a-1", "t", "todo", null, emptyList(), emptyList(), null, null)

        assertEquals(emptyList(), issue.area)
    }
}
