package loop.core.verify

import kotlinx.coroutines.runBlocking
import loop.core.workflow.CheckConfig
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bl-117: the `changed_line_coverage` check on fixture workspaces, real git and JaCoCo XML reports. */
class ChangedLineCoverageTest {
    private val workspace: Path = createTempDirectory("loop-coverage-check").toRealPath()
    private val runner = CoreCheckRunner()
    private val env = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"), "GIT_CONFIG_NOSYSTEM" to "1")

    private val report = "build/reports/kover/report.xml"

    private fun check(
        minPercent: Double,
        reports: List<String> = listOf("**/build/reports/kover/report.xml"),
        base: String = "main",
    ): CheckResult = runBlocking { runner.run(CheckConfig.ChangedLineCoverage(reports, minPercent, base), workspace, env) }

    private fun write(
        name: String,
        text: String,
    ) {
        workspace.resolve(name).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .apply { environment()["GIT_CONFIG_NOSYSTEM"] = "1" }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output
    }

    private fun commitAll(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    private val calc = "src/main/kotlin/app/Calc.kt"

    /** A repository whose `main` holds a two line [calc], and a work branch checked out from it. */
    private fun repo() {
        git("init", "-q", "--initial-branch=main")
        write(".gitignore", "build/\n")
        write(calc, "package app\n\n")
        commitAll("first")
        git("checkout", "-q", "-b", "loop/t-1")
    }

    /** Appends lines 3 to 6 to [calc]. */
    private fun addFourLines() = write(calc, "package app\n\nfun a() = 1\nfun b() = 2\nfun c() = 3\nfun d() = 4\n")

    /** One `<line>` element: [mi] missed and [ci] covered instructions. */
    private fun line(
        nr: Int,
        mi: Int,
        ci: Int,
    ) = """<line nr="$nr" mi="$mi" ci="$ci" mb="0" cb="0"/>"""

    /** A JaCoCo XML report, DOCTYPE included like JaCoCo and Kover write it, with one source file per entry of [files]. */
    private fun jacoco(vararg files: Triple<String, String, List<String>>): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           |<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
           |<report name="fixture">
           |<sessioninfo id="s" start="1" dump="2"/>
           |${files.joinToString("\n") { (pkg, name, lines) ->
            """<package name="$pkg"><class name="$pkg/X" sourcefilename="$name"/><sourcefile name="$name">${lines.joinToString("")}""" +
                """<counter type="INSTRUCTION" missed="0" covered="0"/></sourcefile></package>"""
        }}
           |<counter type="INSTRUCTION" missed="0" covered="0"/>
           |</report>
        """.trimMargin()

    private fun calcReport(vararg lines: String) = jacoco(Triple("app", "Calc.kt", lines.toList()))

    @Test
    fun `changed lines covered below the floor fail with the uncovered lines in the output`() {
        repo()
        addFourLines()
        write(report, calcReport(line(1, 0, 0), line(3, 0, 3), line(4, 0, 2), line(5, 3, 0), line(6, 2, 0)))

        val result = check(minPercent = 75.0)

        assertFalse(result.passed, result.toString())
        assertEquals(1, result.exitCode)
        assertTrue(result.refutes)
        assertContains(result.output, "$calc: 5-6")
        assertContains(result.output, "2 of 4 changed lines covered, 50%")
        assertContains(result.output, "below the floor of 75%")
    }

    @Test
    fun `changed lines covered at or above the floor pass`() {
        repo()
        addFourLines()
        write(report, calcReport(line(3, 0, 3), line(4, 1, 2), line(5, 3, 0), line(6, 2, 0)))

        val atFloor = check(minPercent = 50.0)
        val belowRate = check(minPercent = 50.1)
        write(report, calcReport(line(3, 0, 3), line(4, 1, 2), line(5, 0, 1), line(6, 2, 0)))
        val above = check(minPercent = 50.0)

        assertTrue(atFloor.passed, atFloor.toString())
        assertContains(atFloor.output, "2 of 4 changed lines covered, 50%, at or above the floor of 50%")
        assertFalse(belowRate.passed, belowRate.toString())
        assertTrue(above.passed, above.toString())
        assertContains(above.output, "3 of 4 changed lines covered, 75%")
    }

    @Test
    fun `no countable changed line passes`() {
        repo()
        write(calc, "package app\n\n// a comment\n")
        write("README.md", "# readme\n")
        write(report, calcReport(line(5, 1, 0)))

        val result = check(minPercent = 100.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "no changed line is listed with instructions in a coverage report")
    }

    @Test
    fun `lines a report does not list or lists without instructions do not count`() {
        repo()
        addFourLines()
        // Line 3 covered, line 4 missed, line 5 not listed, line 6 listed with no instruction.
        write(report, calcReport(line(3, 0, 3), line(4, 2, 0), line(6, 0, 0)))

        val result = check(minPercent = 50.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "1 of 2 changed lines covered, 50%")
    }

    @Test
    fun `a changed file no report lists does not count and is named`() {
        repo()
        addFourLines()
        write("src/main/kotlin/app/Other.kt", "package app\n\nfun o() = 0\n")
        write(report, calcReport(line(3, 0, 1), line(4, 0, 1), line(5, 0, 1), line(6, 0, 1)))

        val result = check(minPercent = 100.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "4 of 4 changed lines covered, 100%")
        assertContains(result.output, "1 changed file is in no coverage report: src/main/kotlin/app/Other.kt")
    }

    @Test
    fun `a changed file whose folder does not match its package is matched by its package`() {
        repo()
        val tool = "src/main/kotlin/misplaced/Tool.kt"
        write(tool, "@file:JvmName(\"ToolKt\")\n\npackage app.tools\n\nfun t() = 1\nfun u() = 2\n")
        write(report, jacoco(Triple("app/tools", "Tool.kt", listOf(line(5, 2, 0), line(6, 2, 0)))))

        val untracked = check(minPercent = 50.0)
        commitAll("tool")
        val committed = check(minPercent = 50.0)

        for (result in listOf(untracked, committed)) {
            assertFalse(result.passed, result.toString())
            assertContains(result.output, "$tool: 5-6")
            assertContains(result.output, "0 of 2 changed lines covered, 0%")
        }
    }

    @Test
    fun `a diff attribute that marks sources binary does not hide their changed lines`() {
        repo()
        workspace.resolve(".git/info").createDirectories()
        write(".git/info/attributes", "*.kt -diff\n")
        addFourLines()
        write(report, calcReport(line(3, 1, 0), line(4, 1, 0), line(5, 1, 0), line(6, 1, 0)))

        val result = check(minPercent = 50.0)

        assertFalse(result.passed, result.toString())
        assertContains(result.output, "$calc: 3-6")
    }

    @Test
    fun `a binary file in the diff, carriage returns and NUL bytes included, is read as text`() {
        repo()
        addFourLines()
        val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0x0D, 'x'.code.toByte(), 0, 0x0A, 0x0D)
        workspace.resolve("logo.png").toFile().writeBytes(bytes)
        commitAll("logo")
        write(report, calcReport(line(3, 0, 1), line(4, 0, 1), line(5, 0, 1), line(6, 0, 1)))

        val result = check(minPercent = 100.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "4 of 4 changed lines covered, 100%")
    }

    @Test
    fun `a missing report means the check could not run`() {
        repo()
        addFourLines()

        val result = check(minPercent = 0.0)

        assertFalse(result.passed)
        assertNull(result.exitCode)
        assertNotNull(result.couldNotRun, result.toString())
        assertContains(result.startError!!, "no coverage report matches **/build/reports/kover/report.xml")
    }

    @Test
    fun `an unparseable report means the check could not run`() {
        repo()
        addFourLines()
        write(report, "<report><package name=\"app\">")
        write("cli/$report", "not xml at all")

        val result = check(minPercent = 0.0)

        assertNull(result.exitCode, result.toString())
        assertContains(result.startError!!, "cannot parse the coverage report")
    }

    @Test
    fun `a report that is not JaCoCo XML means the check could not run`() {
        repo()
        addFourLines()
        write(report, "<coverage line-rate=\"1\"/>")

        val result = check(minPercent = 0.0)

        assertNull(result.exitCode, result.toString())
        assertContains(result.startError!!, "is not a JaCoCo XML report")
    }

    @Test
    fun `the reports of several modules are merged, a line covered in one report is covered`() {
        repo()
        write("core/src/main/kotlin/app/core/Core.kt", "package app.core\n\nfun c1() = 1\nfun c2() = 2\n")
        write("cli/src/main/kotlin/app/cli/Main.kt", "package app.cli\n\nfun m1() = 1\nfun m2() = 2\n")
        write("core/$report", jacoco(Triple("app/core", "Core.kt", listOf(line(3, 0, 1), line(4, 1, 0)))))
        write("cli/$report", jacoco(Triple("app/cli", "Main.kt", listOf(line(3, 1, 0), line(4, 1, 0)))))
        // An aggregated report that covers the line the core report missed.
        write("build/reports/kover/report.xml", jacoco(Triple("app/core", "Core.kt", listOf(line(3, 1, 0), line(4, 0, 1)))))

        val result = check(minPercent = 50.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "2 of 4 changed lines covered, 50%")
        assertContains(result.output, "3 coverage reports")
        val strict = check(minPercent = 75.0)
        assertContains(strict.output, "cli/src/main/kotlin/app/cli/Main.kt: 3-4")
    }

    @Test
    fun `committed, uncommitted and untracked changes all count`() {
        repo()
        write(calc, "package app\n\nfun a() = 1\n")
        commitAll("committed line")
        write(calc, "package app\n\nfun a() = 1\nfun b() = 2\n")
        write("src/main/kotlin/app/New.kt", "package app\n\nfun n() = 0\n")
        write(
            report,
            jacoco(
                Triple("app", "Calc.kt", listOf(line(3, 1, 0), line(4, 0, 1))),
                Triple("app", "New.kt", listOf(line(3, 1, 0))),
            ),
        )

        val result = check(minPercent = 100.0)

        assertFalse(result.passed, result.toString())
        assertContains(result.output, "1 of 3 changed lines covered, 33.3%")
        assertContains(result.output, "$calc: 3")
        assertContains(result.output, "src/main/kotlin/app/New.kt: 3")
    }

    @Test
    fun `only lines changed since the merge base with the base count`() {
        repo()
        write(calc, "package app\n\nfun a() = 1\n")
        commitAll("on the work branch")
        git("checkout", "-q", "main")
        write("src/main/kotlin/app/Base.kt", "package app\n\nfun base() = 0\n")
        commitAll("moved on main")
        git("checkout", "-q", "loop/t-1")
        git("merge", "-q", "--no-edit", "main")
        write(
            report,
            jacoco(Triple("app", "Calc.kt", listOf(line(3, 0, 1))), Triple("app", "Base.kt", listOf(line(3, 1, 0)))),
        )

        val result = check(minPercent = 100.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "1 of 1 changed lines covered, 100%")
    }

    @Test
    fun `the uncovered lines are capped in the output`() {
        repo()
        val files = (1..30).map { "src/main/kotlin/app/F$it.kt" }
        files.forEach { write(it, "package app\n\nfun f() = 0\n") }
        write(report, jacoco(*(1..30).map { Triple("app", "F$it.kt", listOf(line(3, 1, 0))) }.toTypedArray()))

        val result = check(minPercent = 1.0)

        assertFalse(result.passed, result.toString())
        assertContains(result.output, "and 15 more files")
        assertTrue(result.output.lines().size <= VerifyFindings.OUTPUT_LINES, result.output)
        assertContains(result.output.lines().last { it.isNotBlank() }, "0 of 30 changed lines covered, 0%")
    }

    @Test
    fun `a path git quotes and an added line that reads like a diff header are read right`() {
        repo()
        val cafe = "src/main/kotlin/app/Café.kt"
        write(cafe, "package app\n")
        commitAll("café")
        write(cafe, "package app\n++ not a header\n--- nor this\n")
        write(report, jacoco(Triple("app", "Café.kt", listOf(line(2, 1, 0), line(3, 0, 1)))))

        val result = check(minPercent = 100.0)

        assertFalse(result.passed, result.toString())
        assertContains(result.output, "$cafe: 2")
        assertContains(result.output, "1 of 2 changed lines covered, 50%")
    }

    @Test
    fun `a floor of 0 passes whatever the coverage`() {
        repo()
        addFourLines()
        write(report, calcReport(line(3, 1, 0)))

        val result = check(minPercent = 0.0)

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "0 of 1 changed lines covered, 0%, at or above the floor of 0%")
    }

    @Test
    fun `a workspace that is not a git work tree means the check could not run`() {
        write(report, calcReport(line(3, 0, 1)))

        val result = check(minPercent = 0.0)

        assertNull(result.exitCode, result.toString())
        assertContains(result.startError!!, "the workspace is not the top of a git work tree")
    }

    @Test
    fun `a base without a merge base means the check could not run`() {
        repo()
        addFourLines()
        write(report, calcReport(line(3, 0, 1)))

        val result = check(minPercent = 0.0, base = "nowhere")

        assertNull(result.exitCode, result.toString())
        assertContains(result.startError!!, "no merge base with nowhere and HEAD")
    }
}
