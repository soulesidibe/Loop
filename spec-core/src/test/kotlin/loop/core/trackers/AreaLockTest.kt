package loop.core.trackers

import loop.api.Issue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * m4-17, bl-10: the area lock of the dispatch. A candidate whose area overlaps the area of a claimed issue, or of an
 * earlier candidate of the same tick given a slot, is skipped with `area_busy`, keeping its place.
 */
class AreaLockTest {
    private fun issue(
        identifier: String,
        vararg area: String,
    ) = Issue(
        id = "id-$identifier",
        identifier = identifier,
        title = identifier,
        state = "todo",
        priority = 2,
        labels = emptyList(),
        blockedBy = emptyList(),
        description = null,
        createdAt = null,
        area = area.toList(),
    )

    private val skips = mutableMapOf<Issue, String>()

    private fun skipped(): Map<String, String> = skips.mapKeys { it.key.identifier }

    @Test
    fun `a candidate overlapping a claimed issue is skipped naming the holder and its entry, the others pass in order`() {
        val holder = issue("h-1", "spec-core")
        val busy = issue("c-1", "spec-core/src/main")
        val free = issue("c-2", "docs")
        val prefix = issue("c-3", "spec-core-x")

        val admitted = AreaLock().admit(listOf(busy, free, prefix), setOf(holder.id), listOf(holder), skips)

        assertEquals(listOf(free, prefix), admitted)
        assertEquals(mapOf("c-1" to "area_busy h-1 (spec-core)"), skipped())
    }

    @Test
    fun `an issue with no area never blocks and is never blocked`() {
        val noArea = issue("h-1")
        val holder = issue("h-2", "spec-core")
        val candidate = issue("c-1")
        val other = issue("c-2", "cli")

        val admitted = AreaLock().admit(listOf(candidate, other), setOf(noArea.id, holder.id), listOf(noArea, holder), skips)

        assertEquals(listOf(candidate, other), admitted)
        assertEquals(emptyMap(), skipped())
    }

    @Test
    fun `fresh candidates of one area are all admitted, a later one is held only by an earlier one given a slot`() {
        val first = issue("c-1", "spec-core")
        val second = issue("c-2", "spec-core/src")
        val third = issue("c-3", "cli")
        val lock = AreaLock()

        // The admit leaves the choice to the slot plan, so a candidate given no slot holds nothing.
        assertEquals(listOf(first, second, third), lock.admit(listOf(first, second, third), emptySet(), emptyList(), skips))
        assertEquals(emptyMap(), skipped())

        // In the plan, a candidate is held by a launch planned before it, never by one left without a slot.
        assertEquals("area_busy c-1 (spec-core)", lock.heldBy(second, listOf(first)))
        assertNull(lock.heldBy(second, emptyList()))
        assertNull(lock.heldBy(third, listOf(first, second)))
        assertNull(lock.heldBy(issue("c-4"), listOf(first)), "an issue with no area is never held")
        assertNull(lock.heldBy(issue("c-5", "spec-core"), listOf(issue("c-6"))), "nor held by one with no area")
    }

    @Test
    fun `two due retries of one area never block each other for good, the first in order runs`() {
        val first = issue("r-1", "spec-core")
        val second = issue("r-2", "spec-core")

        val admitted = AreaLock().admit(listOf(first, second), setOf(first.id, second.id), listOf(first, second), skips)

        assertEquals(listOf(first), admitted)
        assertEquals(mapOf("r-2" to "area_busy r-1 (spec-core)"), skipped())
    }

    @Test
    fun `a queued retry holds its area against a new candidate placed before it`() {
        val candidate = issue("c-1", "spec-core")
        val retry = issue("r-1", "spec-core/src")

        val admitted = AreaLock().admit(listOf(candidate, retry), setOf(retry.id), listOf(candidate, retry), skips)

        assertEquals(listOf(retry), admitted)
        assertEquals(mapOf("c-1" to "area_busy r-1 (spec-core/src)"), skipped())
    }

    @Test
    fun `a holder's area is the latest read, kept from an earlier tick while it is claimed and forgotten once released`() {
        val lock = AreaLock()
        val candidate = issue("c-1", "docs")

        // The holder declared `docs` at its dispatch, then its tracker's poll fails: the area it was read with holds.
        lock.admit(emptyList(), setOf("id-h-1"), listOf(issue("h-1", "docs")), skips)
        assertEquals(emptyList(), lock.admit(listOf(candidate), setOf("id-h-1"), emptyList(), skips))
        assertEquals(mapOf("c-1" to "area_busy h-1 (docs)"), skipped())

        // A later read of the holder changes its area: the latest read decides.
        skips.clear()
        assertEquals(listOf(candidate), lock.admit(listOf(candidate), setOf("id-h-1"), listOf(issue("h-1", "cli")), skips))

        // Released, its area is forgotten: claimed again with no read of it, it holds nothing.
        lock.admit(emptyList(), emptySet(), emptyList(), skips)
        assertEquals(listOf(candidate), lock.admit(listOf(candidate), setOf("id-h-1"), emptyList(), skips))
        assertEquals(emptyMap(), skipped())
    }
}
