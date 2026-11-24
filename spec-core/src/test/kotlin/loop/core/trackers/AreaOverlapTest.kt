package loop.core.trackers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** m4-17, bl-10: two area entries overlap when they are equal or one is a parent folder of the other, on whole segments. */
class AreaOverlapTest {
    private data class Case(
        val a: String,
        val b: String,
        val overlaps: Boolean,
    )

    private val table =
        listOf(
            // Equal, after normalizing.
            Case("spec-core", "spec-core", true),
            Case("spec-core/", "./spec-core", true),
            Case("spec-core//src/./main", "spec-core/src/main/", true),
            Case("docs/spec-decisions.md", "docs/spec-decisions.md", true),
            // A parent folder, either way round.
            Case("spec-core", "spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt", true),
            Case("plugins/tracker-files/src/main/kotlin/loop/tracker/files/FilesTracker.kt", "plugins/tracker-files", true),
            Case("./plugins/", "plugins/tracker-github/", true),
            // Whole segments only: a shared prefix is no parent.
            Case("spec-core", "spec-core-x", false),
            Case("spec-core-x/src", "spec-core", false),
            Case("docs/spec", "docs/spec-decisions.md", false),
            Case("plugins/tracker", "plugins/tracker-files", false),
            // Siblings and strangers.
            Case("spec-core/src/main", "spec-core/src/test", false),
            Case("docs", "cli", false),
            // Compared case folded and in NFC, so the lock never fails open on a case insensitive file system.
            Case("Docs", "docs", true),
            Case("Spec-Core/SRC", "spec-core", true),
            Case("caf\u00e9", "cafe\u0301/menu", true),
            Case("spec-core / src", "spec-core/src/main", true),
            // A malformed entry overlaps nothing.
            Case("spec-core/*", "spec-core", false),
            Case("..", "spec-core", false),
            Case("", "spec-core", false),
        )

    @Test
    fun `the overlap of two entries follows the table, both ways round`() {
        for (case in table) {
            assertEquals(case.overlaps, AreaOverlap.overlaps(case.a, case.b), "${case.a} and ${case.b}")
            assertEquals(case.overlaps, AreaOverlap.overlaps(case.b, case.a), "${case.b} and ${case.a}")
        }
    }

    @Test
    fun `two areas overlap on their first pair of overlapping entries, and an empty area overlaps nothing`() {
        assertEquals("docs" to "docs/agents", AreaOverlap.first(listOf("cli", "docs"), listOf("spec-core", "docs/agents")))
        assertNull(AreaOverlap.first(listOf("cli", "docs"), listOf("spec-core", "plugins")))
        assertNull(AreaOverlap.first(emptyList(), listOf("spec-core")))
        assertNull(AreaOverlap.first(listOf("spec-core"), emptyList()))
    }
}
