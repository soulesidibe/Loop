package loop.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m3-12, story 91 (plan 8.4): progress between rotations is a new commit on the issue's branch since the previous
 * rotation, or a verification with fewer failing checks than the previous one. Two rotations in a row without it park
 * the issue with `split_issue`. bl-136: a working tree changed since the previous rotation is progress too.
 */
class RotationProgressTest {
    @Test
    fun `a rotation with the same head and no verification is without progress, and the count goes on`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        assertEquals(2, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `a new commit since the previous rotation is progress and resets the count`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        assertEquals(0, progress.rotation(head = "c2", tree = "t1"))
        assertEquals(1, progress.rotation(head = "c2", tree = "t1"))
    }

    @Test
    fun `a verification with fewer failing checks than the previous one is progress`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")
        progress.verified(failing = 3)

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        progress.verified(failing = 2)
        assertEquals(0, progress.rotation(head = "c1", tree = "t1"))
        // As many failing checks as the previous verification is no progress.
        progress.verified(failing = 2)
        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `the first verification compares with nothing and a worse one is no progress`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")
        progress.verified(failing = 1)
        progress.verified(failing = 2)

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `an improvement before a worse verification of the same session still counts`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")
        progress.verified(failing = 3)
        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        progress.verified(failing = 1)
        progress.verified(failing = 4)

        assertEquals(0, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `a head Loop could not read counts as progress, so it never parks on a fact it did not read`() {
        val unknownStart = RotationProgress(startHead = null, startTree = "t1")
        assertEquals(0, unknownStart.rotation(head = "c1", tree = "t1"))
        assertEquals(1, unknownStart.rotation(head = "c1", tree = "t1"))

        val unknownNow = RotationProgress(startHead = "c1", startTree = "t1")
        assertEquals(1, unknownNow.rotation(head = "c1", tree = "t1"))
        assertEquals(0, unknownNow.rotation(head = null, tree = "t1"))
    }

    @Test
    fun `bl-136 a working tree changed at the same head is progress and resets the count`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        assertEquals(0, progress.rotation(head = "c1", tree = "t2"))
        // The next rotation compares with the tree of this one.
        assertEquals(1, progress.rotation(head = "c1", tree = "t2"))
    }

    @Test
    fun `bl-136 the first rotation compares the working tree with the one at the attempt's first prompt`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t0")

        assertEquals(0, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `bl-136 an unchanged working tree and head still count a rotation without progress`() {
        val progress = RotationProgress(startHead = "c1", startTree = "t1")

        assertEquals(1, progress.rotation(head = "c1", tree = "t1"))
        assertEquals(2, progress.rotation(head = "c1", tree = "t1"))
    }

    @Test
    fun `bl-136 a working tree Loop could not read counts as progress`() {
        val unknownStart = RotationProgress(startHead = "c1", startTree = null)
        assertEquals(0, unknownStart.rotation(head = "c1", tree = "t1"))
        assertEquals(1, unknownStart.rotation(head = "c1", tree = "t1"))

        val unknownNow = RotationProgress(startHead = "c1", startTree = "t1")
        assertEquals(1, unknownNow.rotation(head = "c1", tree = "t1"))
        assertEquals(0, unknownNow.rotation(head = "c1", tree = null))
    }
}
