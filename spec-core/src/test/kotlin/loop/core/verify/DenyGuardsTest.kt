package loop.core.verify

import loop.core.workflow.DenyGuard
import loop.core.workflow.TestLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** m2-13: the deny guards on old and new test file texts, Kotlin and Swift fixtures, no git. */
class DenyGuardsTest {
    private val all = DenyGuard.entries

    private fun find(
        vararg files: TestFile,
        deny: List<DenyGuard> = all,
        languages: List<TestLanguage> = TestLanguage.BUILT_IN,
        budget: Duration = DenyGuards.BUDGET,
    ): List<String> = DenyGuards.find(deny, languages, files.toList(), budget).map { it.toString() }

    private fun modified(
        path: String,
        old: String,
        new: String,
    ) = TestFile(oldPath = path, oldText = old, newPath = path, newText = new)

    private val calcTest =
        """
        |package calc
        |
        |import org.junit.jupiter.api.Test
        |import kotlin.test.assertEquals
        |
        |class CalcTest {
        |    @Test
        |    fun adds() {
        |        assertEquals(4, add(2, 2))
        |    }
        |
        |    @Test
        |    fun `divides by two`() {
        |        assertEquals(2, divide(4, 2))
        |        assertEquals(0, divide(0, 2))
        |    }
        |}
        |
        """.trimMargin()

    private val kotlinPath = "lib/src/test/kotlin/calc/CalcTest.kt"

    @Test
    fun `an unchanged test file breaks no guard`() {
        assertEquals(emptyList(), find(modified(kotlinPath, calcTest, calcTest + "\n// a note\n")))
    }

    // deleted_tests

    @Test
    fun `deleting a Kotlin test function is deleted_tests and names the test`() {
        val new = calcTest.replace(Regex("""(?s)\n    @Test\n    fun `divides by two`.*?\n    }\n"""), "\n")

        val findings = find(modified(kotlinPath, calcTest, new), deny = listOf(DenyGuard.DELETED_TESTS))

        assertEquals(listOf("deleted_tests: removed test `divides by two` from $kotlinPath"), findings)
    }

    @Test
    fun `deleting a test file that holds tests is deleted_tests, a deleted helper without tests is not`() {
        val helper = TestFile(oldPath = "lib/src/test/kotlin/calc/Fixtures.kt", oldText = "fun fixture() = 1\n")
        val test = TestFile(oldPath = kotlinPath, oldText = calcTest)

        val findings = find(helper, test, deny = listOf(DenyGuard.DELETED_TESTS))

        assertEquals(listOf("deleted_tests: deleted test file $kotlinPath (2 tests: adds, `divides by two`)"), findings)
    }

    @Test
    fun `a test moved to another test file is not a deletion, a test file moved out of the test files is`() {
        val moved =
            listOf(
                modified(kotlinPath, calcTest, calcTest.replace("@Test\n    fun adds()", "fun adds()")),
                TestFile(newPath = "lib/src/test/kotlin/calc/AddTest.kt", newText = "class AddTest {\n    @Test\n    fun adds() {}\n}\n"),
            )
        assertEquals(emptyList(), find(*moved.toTypedArray(), deny = listOf(DenyGuard.DELETED_TESTS)))

        val out = TestFile(oldPath = kotlinPath, oldText = calcTest, newPath = "lib/src/main/kotlin/calc/Calc.kt", newText = calcTest)
        assertEquals(
            listOf("deleted_tests: deleted test file $kotlinPath (2 tests: adds, `divides by two`)"),
            find(out, deny = listOf(DenyGuard.DELETED_TESTS)),
        )
    }

    @Test
    fun `commenting out a test counts as deleting it`() {
        val lines = calcTest.replace("    @Test\n    fun adds() {\n        assertEquals(4, add(2, 2))\n    }\n", "")
        val block =
            calcTest
                .replace(
                    "    @Test\n    fun adds()",
                    "    /*\n    @Test\n    fun adds()",
                ).replace("add(2, 2))\n    }\n", "add(2, 2))\n    }\n    */\n")
        val slashed = calcTest.replace("    @Test\n    fun adds()", "    // @Test\n    // fun adds()")

        listOf(lines, block, slashed).forEach { new ->
            assertEquals(
                listOf("deleted_tests: removed test adds from $kotlinPath"),
                find(modified(kotlinPath, calcTest, new), deny = listOf(DenyGuard.DELETED_TESTS)),
                new,
            )
        }
    }

    private val swiftTest =
        """
        |import XCTest
        |import Testing
        |
        |final class CalcTests: XCTestCase {
        |    func testAdds() throws {
        |        XCTAssertEqual(add(2, 2), 4)
        |    }
        |
        |    func testDivides() throws {
        |        XCTAssertEqual(divide(4, 2), 2)
        |    }
        |
        |    private func helper() {}
        |}
        |
        |@Test(.tags(.fast)) func subtracts() {
        |    #expect(subtract(4, 2) == 2)
        |}
        |
        """.trimMargin()

    private val swiftPath = "CalcTests/CalcTests.swift"

    @Test
    fun `deleting an XCTest or a Swift Testing test is deleted_tests, a deleted Swift test file too`() {
        val noXc = swiftTest.replace("    func testDivides() throws {\n        XCTAssertEqual(divide(4, 2), 2)\n    }\n", "")
        val noSwiftTesting = swiftTest.substringBefore("@Test(")

        assertEquals(
            listOf("deleted_tests: removed test testDivides from $swiftPath"),
            find(modified(swiftPath, swiftTest, noXc), deny = listOf(DenyGuard.DELETED_TESTS)),
        )
        assertEquals(
            listOf("deleted_tests: removed test subtracts from $swiftPath"),
            find(modified(swiftPath, swiftTest, noSwiftTesting), deny = listOf(DenyGuard.DELETED_TESTS)),
        )
        assertEquals(
            listOf("deleted_tests: deleted test file $swiftPath (3 tests: testAdds, testDivides, subtracts)"),
            find(TestFile(oldPath = swiftPath, oldText = swiftTest), deny = listOf(DenyGuard.DELETED_TESTS)),
        )
    }

    @Test
    fun `files outside the test globs are not read`() {
        val main = "lib/src/main/kotlin/calc/Calc.kt"

        assertEquals(emptyList(), find(TestFile(oldPath = main, oldText = calcTest)))
    }

    // added_skips

    @Test
    fun `adding @Disabled, @Ignore or XCTSkip is added_skips and names the line`() {
        val disabled = calcTest.replace("    @Test\n    fun adds()", "    @Disabled(\"flaky\")\n    @Test\n    fun adds()")
        val ignore = calcTest.replace("    @Test\n    fun adds()", "    @org.junit.Ignore\n    @Test\n    fun adds()")
        val skipped = swiftTest.replace("func testAdds() throws {\n", "func testAdds() throws {\n        throw XCTSkip(\"later\")\n")
        val skipIf = swiftTest.replace("func testAdds() throws {\n", "func testAdds() throws {\n        try XCTSkipIf(true)\n")
        val disabledSwiftTesting = swiftTest.replace("@Test(.tags(.fast))", "@Test(.disabled(\"later\"))")

        val only = listOf(DenyGuard.ADDED_SKIPS)
        assertEquals(
            listOf("added_skips: added a skip to $kotlinPath: @Disabled(\"flaky\")"),
            find(modified(kotlinPath, calcTest, disabled), deny = only),
        )
        assertEquals(
            listOf("added_skips: added a skip to $kotlinPath: @org.junit.Ignore"),
            find(modified(kotlinPath, calcTest, ignore), deny = only),
        )
        assertEquals(
            listOf("added_skips: added a skip to $swiftPath: throw XCTSkip(\"later\")"),
            find(modified(swiftPath, swiftTest, skipped), deny = only),
        )
        assertEquals(
            listOf("added_skips: added a skip to $swiftPath: try XCTSkipIf(true)"),
            find(modified(swiftPath, swiftTest, skipIf), deny = only),
        )
        assertEquals(
            listOf("added_skips: added a skip to $swiftPath: @Test(.disabled(\"later\")) func subtracts() {"),
            find(modified(swiftPath, swiftTest, disabledSwiftTesting), deny = only),
        )
    }

    @Test
    fun `a skip that was already there, or moved between test files, is not added, a new skipped test file is`() {
        val old = calcTest.replace("    @Test\n    fun adds()", "    @Disabled\n    @Test\n    fun adds()")
        val only = listOf(DenyGuard.ADDED_SKIPS)
        assertEquals(emptyList(), find(modified(kotlinPath, old, old.replace("4, add", "5, add")), deny = only))

        val other = "lib/src/test/kotlin/calc/OtherTest.kt"
        val moved = arrayOf(modified(kotlinPath, old, calcTest), TestFile(newPath = other, newText = "    @Disabled\n"))
        assertEquals(emptyList(), find(*moved, deny = only))

        assertEquals(
            listOf("added_skips: added a skip to $other: @Ignore"),
            find(TestFile(newPath = other, newText = "class OtherTest {\n    @Ignore\n    @Test\n    fun x() {}\n}\n"), deny = only),
        )
    }

    // weaker_assertions

    @Test
    fun `removing assertions from a test without adding any is weaker_assertions`() {
        val new = calcTest.replace("        assertEquals(0, divide(0, 2))\n", "")
        val swiftNew = swiftTest.replace("        XCTAssertEqual(divide(4, 2), 2)\n", "        _ = divide(4, 2)\n")
        val only = listOf(DenyGuard.WEAKER_ASSERTIONS)

        assertEquals(
            listOf("weaker_assertions: removed 1 assertion from $kotlinPath and added none: assertEquals(0, divide(0, 2))"),
            find(modified(kotlinPath, calcTest, new), deny = only),
        )
        assertEquals(
            listOf("weaker_assertions: removed 1 assertion from $swiftPath and added none: XCTAssertEqual(divide(4, 2), 2)"),
            find(modified(swiftPath, swiftTest, swiftNew), deny = only),
        )
    }

    @Test
    fun `a commented out assertion is removed, a changed or moved assertion is not`() {
        val only = listOf(DenyGuard.WEAKER_ASSERTIONS)
        val commented = calcTest.replace("        assertEquals(0, divide(0, 2))", "        // assertEquals(0, divide(0, 2))")
        assertEquals(1, find(modified(kotlinPath, calcTest, commented), deny = only).size)

        val changed = calcTest.replace("assertEquals(0, divide(0, 2))", "assertEquals(0, divide(0, 3))")
        assertEquals(emptyList(), find(modified(kotlinPath, calcTest, changed), deny = only))

        val other = "lib/src/test/kotlin/calc/DivideTest.kt"
        val moved =
            arrayOf(
                modified(kotlinPath, calcTest, calcTest.replace("        assertEquals(0, divide(0, 2))\n", "")),
                TestFile(newPath = other, newText = "        assertEquals(0, divide(0, 2))\n"),
            )
        assertEquals(emptyList(), find(*moved, deny = only))
    }

    @Test
    fun `removing several assertions lists the first ones and counts them all`() {
        val new = calcTest.replace(Regex("""\n\s*assertEquals\([^\n]*"""), "")

        val finding = find(modified(kotlinPath, calcTest, new), deny = listOf(DenyGuard.WEAKER_ASSERTIONS)).single()

        assertTrue(finding.startsWith("weaker_assertions: removed 3 assertions from $kotlinPath and added none: "), finding)
        assertTrue("assertEquals(4, add(2, 2))" in finding, finding)
    }

    // configured languages

    @Test
    fun `a configured language brings its own patterns, and the guards it has no pattern for stay off`() {
        val python =
            TestLanguage(
                name = "python",
                files = listOf("tests/**/*.py"),
                test = """^\s*def (test_\w+)""",
                skip = """@pytest\.mark\.skip""",
                lineComment = "#",
            )
        val old = "def test_adds():\n    assert add(2, 2) == 4\n\ndef test_subtracts():\n    assert sub(4, 2) == 2\n"
        val new = "@pytest.mark.skip\ndef test_adds():\n    pass\n\n# def test_subtracts():\n#     assert sub(4, 2) == 2\n"

        val findings = find(modified("tests/test_calc.py", old, new), languages = TestLanguage.BUILT_IN + python)

        assertEquals(
            listOf(
                "deleted_tests: removed test test_subtracts from tests/test_calc.py",
                "added_skips: added a skip to tests/test_calc.py: @pytest.mark.skip",
            ),
            findings,
        )
    }

    // renamed test files

    @Test
    fun `a renamed test file with edited assertion lines pairs its old and new path`() {
        val renamed = "lib/src/test/kotlin/calc/CalculatorTest.kt"
        val edited = calcTest.replace("assertEquals(0, divide(0, 2))", "assertEquals(0, divide(0, 5))")
        val gutted = calcTest.replace("        assertEquals(0, divide(0, 2))\n", "")
        val only = listOf(DenyGuard.WEAKER_ASSERTIONS)

        assertEquals(emptyList(), find(TestFile(kotlinPath, calcTest, renamed, edited), deny = only))
        assertEquals(
            listOf(
                "weaker_assertions: removed 1 assertion from $kotlinPath (renamed to $renamed) and added none: assertEquals(0, divide(0, 2))",
            ),
            find(TestFile(kotlinPath, calcTest, renamed, gutted), deny = only),
        )
    }

    // bounded work: no recursion per character, a line cap and a time budget

    /** [calcTest] with a parameterized test whose `@CsvSource` text block holds [rows] rows, about 7 bytes each. */
    private fun csvTest(rows: Int): String =
        calcTest.replace(
            "    @Test\n    fun adds()",
            "    @ParameterizedTest\n    @CsvSource(textBlock = \"\"\"\n" + "        1, 2\n".repeat(rows) +
                "    \"\"\")\n    fun csv(a: Int, b: Int) {}\n\n    @Test\n    fun adds()",
        )

    @Test
    fun `a 5 KB @CsvSource or a long run of annotations does not overflow the stack`() {
        val csv = csvTest(rows = 2_000)
        val gone = csv.replace("    @Test\n    fun adds()", "    fun adds()")
        val annotations = calcTest.replace("    @Test\n    fun adds()", "    @Test\n".repeat(5_000) + "    fun adds()")

        assertEquals(
            listOf("deleted_tests: removed test adds from $kotlinPath"),
            find(modified(kotlinPath, csv, gone), deny = listOf(DenyGuard.DELETED_TESTS)),
        )
        assertEquals(emptyList(), find(modified(kotlinPath, csv, csv + "\n"), deny = listOf(DenyGuard.DELETED_TESTS)))
        assertEquals(emptyList(), find(modified(kotlinPath, annotations, annotations + "\n"), deny = listOf(DenyGuard.DELETED_TESTS)))
    }

    @Test
    fun `the built in patterns do not recurse per character on long or adversarial input`() {
        // Each overflows the stack with the patterns of the first attempt, measured at 4,000 repeats with a 2 MiB stack.
        val inputs =
            listOf(
                "@Test(" + "x".repeat(16_000) + ")\nfun a() {}",
                "@Test @CsvSource(" + "(a)".repeat(4_000) + ") fun a() {}",
                "@Test ".repeat(4_000) + "fun a() {}",
                "@Test\n" + "@Suppress(\"x\")\n".repeat(4_000) + "fun a() {}",
            )
        TestLanguage.BUILT_IN.forEach { language ->
            listOfNotNull(language.test, language.skip, language.assertion).forEach { pattern ->
                val regex = Regex(pattern, RegexOption.MULTILINE)
                inputs.forEach { input -> regex.findAll(input).count() }
            }
        }
    }

    @Test
    fun `a changed line over the line cap means the guards could not run`() {
        val long = "        val s = \"" + "a".repeat(240_000) + "\"\n"
        val new = calcTest.replace("    fun adds() {\n", "    fun adds() {\n$long")

        val error = assertFailsWith<DenyGuardsLimit> { find(modified(kotlinPath, calcTest, new)) }

        assertEquals(
            "a changed line of $kotlinPath has 240010 characters, over the ${DenyGuards.LINE_MAX} the deny guards read",
            error.message,
        )
    }

    @Test
    fun `an unchanged long adversarial line is read up to the cap, quickly`() {
        val kotlinLine = "        val s = \"" + "assert<".repeat(35_000) + "\"\n"
        val swiftLine = "    let s = \"" + "@Test(".repeat(40_000) + "\"\n"
        val kotlinOld = calcTest.replace("    fun adds() {\n", "    fun adds() {\n$kotlinLine")
        val swiftOld = swiftTest.replace("    func testAdds() throws {\n", "    func testAdds() throws {\n$swiftLine")

        val took =
            measureTime {
                val findings =
                    find(
                        modified(kotlinPath, kotlinOld, kotlinOld.replace("        assertEquals(0, divide(0, 2))\n", "")),
                        modified(swiftPath, swiftOld, swiftOld.replace("        XCTAssertEqual(divide(4, 2), 2)\n", "")),
                    )
                assertEquals(2, findings.size, findings.toString())
                assertTrue(findings.all { it.startsWith("weaker_assertions: ") }, findings.toString())
            }

        assertTrue(took < 2.seconds, "took $took")
    }

    @Test
    fun `the guard pass ends at its time budget`() {
        val slow =
            TestLanguage(name = "slow", files = listOf("tests/**/*.txt"), assertion = """(.*a){20}$""")
        val line = "a".repeat(40) + "b"

        val took =
            measureTime {
                val error =
                    assertFailsWith<DenyGuardsLimit> {
                        find(
                            modified("tests/slow.txt", "c\n", "c\n$line\n"),
                            languages = listOf(slow),
                            budget = 200.milliseconds,
                        )
                    }
                assertEquals("the deny guards did not finish within 0.2 s", error.message)
            }

        assertTrue(took < 5.seconds, "took $took")
    }

    @Test
    fun `a second pass started from the first pass's start shares its budget`() {
        val started = System.nanoTime() - 11.seconds.inWholeNanoseconds
        val removed = modified(kotlinPath, calcTest, calcTest.replace("        assertEquals(4, add(2, 2))\n", ""))

        val error = assertFailsWith<DenyGuardsLimit> { DenyGuards.find(all, TestLanguage.BUILT_IN, listOf(removed), startNanos = started) }

        assertEquals("the deny guards did not finish within 10 s", error.message)
    }
}
