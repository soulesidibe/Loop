package loop.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowDiffTest {
    private val ten = (1..10).joinToString("\n") { "line $it" } + "\n"

    @Test
    fun `identical texts give an empty diff`() {
        assertEquals("", WorkflowDiff.unified(ten, ten, "a", "b"))
    }

    @Test
    fun `one changed line gives one hunk with three lines of context`() {
        val new = ten.replace("line 5\n", "line five\n")

        assertEquals(
            """
            |--- a
            |+++ b
            |@@ -2,7 +2,7 @@
            | line 2
            | line 3
            | line 4
            |-line 5
            |+line five
            | line 6
            | line 7
            | line 8
            |
            """.trimMargin(),
            WorkflowDiff.unified(ten, new, "a", "b"),
        )
    }

    @Test
    fun `a removed first line and an added last line`() {
        val old = "a\nb\nc\n"
        val new = "b\nc\nd\n"

        assertEquals(
            """
            |--- old
            |+++ new
            |@@ -1,3 +1,3 @@
            |-a
            | b
            | c
            |+d
            |
            """.trimMargin(),
            WorkflowDiff.unified(old, new, "old", "new"),
        )
    }

    @Test
    fun `changes far apart give two hunks`() {
        val old = (1..20).joinToString("\n") { "l$it" }
        val new = old.replace("l2\n", "x2\n").replace("l19\n", "x19\n")

        assertEquals(
            """
            |--- a
            |+++ b
            |@@ -1,5 +1,5 @@
            | l1
            |-l2
            |+x2
            | l3
            | l4
            | l5
            |@@ -16,5 +16,5 @@
            | l16
            | l17
            | l18
            |-l19
            |+x19
            | l20
            |
            """.trimMargin(),
            WorkflowDiff.unified(old, new, "a", "b"),
        )
    }

    @Test
    fun `an empty old text is all additions`() {
        assertEquals(
            """
            |--- a
            |+++ b
            |@@ -0,0 +1,2 @@
            |+x
            |+y
            |
            """.trimMargin(),
            WorkflowDiff.unified("", "x\ny\n", "a", "b"),
        )
    }
}
