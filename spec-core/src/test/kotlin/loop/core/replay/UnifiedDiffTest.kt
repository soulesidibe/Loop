package loop.core.replay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** m4-40: the unified diff `loop replay --prompt` prints, original first. */
class UnifiedDiffTest {
    @Test
    fun `equal texts have no diff`() {
        assertNull(UnifiedDiff.of("a\nb\n", "a\nb\n", "old", "new"))
    }

    @Test
    fun `one changed line shows with three lines of context around it`() {
        val original = (1..10).joinToString("\n") { "line $it" }
        val revised = original.replace("line 5", "line five")

        val diff = UnifiedDiff.of(original, revised, "recorded", "render")

        assertEquals(
            """
            |--- recorded
            |+++ render
            |@@ -2,7 +2,7 @@
            | line 2
            | line 3
            | line 4
            |-line 5
            |+line five
            | line 6
            | line 7
            | line 8
            """.trimMargin(),
            diff,
        )
    }

    @Test
    fun `changes far apart make two hunks, an added and a removed line count right`() {
        val original = (1..20).joinToString("\n") { "l$it" }
        val revised =
            (1..20)
                .flatMap {
                    when (it) {
                        2 -> listOf("l2", "added")
                        18 -> emptyList()
                        else -> listOf("l$it")
                    }
                }.joinToString("\n")

        val diff = UnifiedDiff.of(original, revised, "a", "b")

        assertEquals(
            """
            |--- a
            |+++ b
            |@@ -1,5 +1,6 @@
            | l1
            | l2
            |+added
            | l3
            | l4
            | l5
            |@@ -15,6 +16,5 @@
            | l15
            | l16
            | l17
            |-l18
            | l19
            | l20
            """.trimMargin(),
            diff,
        )
    }

    @Test
    fun `an empty original is all added, with a zero start`() {
        assertEquals("--- a\n+++ b\n@@ -0,0 +1,2 @@\n+x\n+y", UnifiedDiff.of("", "x\ny", "a", "b"))
    }

    @Test
    fun `a missing final line break is a difference`() {
        assertEquals(
            "--- a\n+++ b\n@@ -1,2 +1,1 @@\n x\n-",
            UnifiedDiff.of("x\n", "x", "a", "b"),
        )
    }

    @Test
    fun `the script of random texts rebuilds both and is as short as the longest common subsequence allows`() {
        val random = Random(40)
        repeat(500) {
            val a = List(random.nextInt(0, 12)) { "abc"[random.nextInt(3)].toString() }
            val b = List(random.nextInt(0, 12)) { "abc"[random.nextInt(3)].toString() }
            val script = UnifiedDiff.script(a.joinToString("\n"), b.joinToString("\n"))
            val rebuiltA = script.filter { it[0] != '+' }.map { it.drop(1) }
            val rebuiltB = script.filter { it[0] != '-' }.map { it.drop(1) }
            val wantA = if (a.isEmpty()) emptyList() else a.joinToString("\n").split('\n')
            val wantB = if (b.isEmpty()) emptyList() else b.joinToString("\n").split('\n')
            assertEquals(wantA, rebuiltA, "$a -> $b")
            assertEquals(wantB, rebuiltB, "$a -> $b")
            assertEquals(lcs(wantA, wantB), script.count { it[0] == ' ' }, "$a -> $b")
        }
    }

    private fun lcs(
        a: List<String>,
        b: List<String>,
    ): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        return table[0][0]
    }
}
