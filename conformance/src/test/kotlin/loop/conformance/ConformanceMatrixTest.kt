package loop.conformance

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bl-154: the conformance test matrix of docs/spec-decisions.md names only test
 * files and test functions that exist, so a renamed or deleted test fails the build
 * instead of waiting for the next audit.
 */
class ConformanceMatrixTest {
    private val root: Path = createTempDirectory("loop-conformance-matrix")

    private fun testFile(
        path: String,
        vararg names: String,
    ) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(
            names.joinToString("\n", prefix = "class T {\n", postfix = "\n}\n") { "    @Test\n    fun `$it`() {}" },
        )
    }

    private fun matrix(vararg rows: String): String =
        """
        |# Spec decisions
        |
        |## Conformance test matrix
        |
        || Spec item | Covered by | Issue | Status |
        ||---|---|---|---|
        |${rows.joinToString("\n")}
        |
        |## Another section
        |
        |`a/src/test/kotlin/NotInTheMatrixTest.kt` (`not checked`)
        """.trimMargin()

    @Test
    fun `the current matrix names only paths and tests that exist`() {
        val doc = Path.of(System.getProperty("loop.specDecisions"))
        val repo = Path.of(System.getProperty("loop.repoRoot"))

        val result = ConformanceMatrix.check(doc.readText(), repo)

        assertEquals(emptyList(), result.problems)
        assertTrue(result.paths >= 100, "only ${result.paths} paths checked")
        assertTrue(result.names >= 100, "only ${result.names} test names checked")
    }

    @Test
    fun `a matrix with one broken path and one broken name reports both`() {
        testFile("a/src/test/kotlin/OneTest.kt", "first test", "second test")
        val doc =
            matrix(
                "| Item 1 | `a/src/test/kotlin/OneTest.kt` (`first test`; `second test`) | m1-01 | Covered |",
                "| Item 2 | `a/src/test/kotlin/GoneTest.kt` | m1-02 | Covered |",
                "| Item 3 | `a/src/test/kotlin/OneTest.kt` (`renamed test`) | m1-03 | Covered |",
            )

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(
            listOf(
                "line 8: `a/src/test/kotlin/GoneTest.kt` is not a file",
                "line 9: `a/src/test/kotlin/OneTest.kt` has no test named like `renamed test`",
            ),
            result.problems,
        )
        assertEquals(3, result.paths)
        assertEquals(3, result.names)
    }

    @Test
    fun `a shortened name, a name prefix and a bare file name of the row pass`() {
        testFile("a/src/test/kotlin/OneTest.kt", "usage accumulates across resumes, the cost is the latest")
        testFile("a/src/test/kotlin/TwoTest.kt", "17_5 item 14 a command approval maps to its answer")
        val doc =
            matrix(
                "| Item 1 | `a/src/test/kotlin/OneTest.kt` (`usage accumulates across resumes`), " +
                    "`TwoTest.kt` (the `17_5 item 14` tests) | m1-01 | Covered |",
            )

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(emptyList(), result.problems)
        assertEquals(2, result.paths)
        assertEquals(2, result.names)
    }

    @Test
    fun `a bare file name missing from the folder of the row's last path is reported with its resolved path`() {
        testFile("a/src/test/kotlin/OneTest.kt", "first test")
        val doc = matrix("| Item 1 | `a/src/test/kotlin/OneTest.kt`, `GoneTest.kt` | m1-01 | Covered |")

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(listOf("line 7: `GoneTest.kt` (a/src/test/kotlin/GoneTest.kt) is not a file"), result.problems)
    }

    @Test
    fun `a name in parentheses with nested parentheses is still checked`() {
        testFile("a/src/test/kotlin/OneTest.kt", "first test")
        val doc =
            matrix("| Item 1 | `a/src/test/kotlin/OneTest.kt` (on virtual time (fake clock): `first test`; `gone`) | m1-01 | Covered |")

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(listOf("line 7: `a/src/test/kotlin/OneTest.kt` has no test named like `gone`"), result.problems)
    }

    @Test
    fun `a name with parentheses inside its backticks is checked whole`() {
        testFile("a/src/test/kotlin/OneTest.kt", "a run (once) ends")
        val doc = matrix("| Item 1 | `a/src/test/kotlin/OneTest.kt` (`a run (once) ends`; `a run (twice)`) | m1-01 | Covered |")

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(listOf("line 7: `a/src/test/kotlin/OneTest.kt` has no test named like `a run (twice)`"), result.problems)
    }

    @Test
    fun `parentheses that are not closed after a path are a problem`() {
        testFile("a/src/test/kotlin/OneTest.kt", "first test")
        val doc = matrix("| Item 1 | `a/src/test/kotlin/OneTest.kt` (`first test`; | m1-01 | Covered |")

        val result = ConformanceMatrix.check(doc, root)

        assertEquals(listOf("line 7: the parentheses after `a/src/test/kotlin/OneTest.kt` are not closed"), result.problems)
    }

    @Test
    fun `a document without the matrix section is a problem`() {
        val result = ConformanceMatrix.check("# Spec decisions\n\nNo matrix here.\n", root)

        assertEquals(listOf("no `## Conformance test matrix` section"), result.problems)
    }
}
