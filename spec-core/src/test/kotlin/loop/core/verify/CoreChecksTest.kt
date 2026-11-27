package loop.core.verify

import kotlinx.coroutines.runBlocking
import loop.core.workflow.CheckConfig
import loop.core.workflow.DenyGuard
import loop.core.workflow.TestLanguage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m2-12: the core check types on fixture workspaces, real files and real git. */
class CoreChecksTest {
    private val workspace: Path = createTempDirectory("loop-core-check").toRealPath()
    private val path = System.getenv("PATH") ?: "/usr/bin:/bin"
    private val runner = CoreCheckRunner()

    /** The user's home for git and the check when a test sets one, else none, so no user config applies to the check. */
    private var home: Path? = null

    /**
     * The check's environment: no system config (a CI runner's git lfs setup must not decide a test), and no user
     * config unless a test sets a [home].
     */
    private fun env(): Map<String, String> =
        mapOf("PATH" to path, "GIT_CONFIG_NOSYSTEM" to "1") + (home?.let { mapOf("HOME" to it.toString()) } ?: emptyMap())

    private fun run(check: CheckConfig): CheckResult = runBlocking { runner.run(check, workspace, env()) }

    private fun write(
        name: String,
        text: String,
    ) {
        workspace.resolve(name).also { it.parent.createDirectories() }.writeText(text)
    }

    // file_exists

    @Test
    fun `file_exists passes on a file or a folder in the workspace`() {
        write("docs/usage.md", "# Usage\n")

        val file = run(CheckConfig.FileExists("docs/usage.md"))
        val folder = run(CheckConfig.FileExists("docs"))

        assertTrue(file.passed, file.toString())
        assertEquals(0, file.exitCode)
        assertContains(file.output, "docs/usage.md exists")
        assertTrue(folder.passed, folder.toString())
    }

    @Test
    fun `file_exists fails on a missing path and names it`() {
        val result = run(CheckConfig.FileExists("docs/usage.md"))

        assertFalse(result.passed)
        assertEquals(1, result.exitCode)
        assertNull(result.startError)
        assertContains(result.output, "docs/usage.md does not exist")
    }

    @Test
    fun `file_exists fails on a symlink that leaves the workspace`() {
        val outside = createTempDirectory("loop-outside").resolve("secret.txt").also { it.writeText("x") }
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside)

        val result = run(CheckConfig.FileExists("link.txt"))

        assertFalse(result.passed)
        assertContains(result.output, "link.txt resolves outside the workspace")
    }

    // file_matches

    @Test
    fun `file_matches passes when the pattern is found, lines anchored with ^ and $`() {
        write("docs/usage.md", "# Title\n\n## Usage\nRun it.\n")

        val result = run(CheckConfig.FileMatches("docs/usage.md", "^## Usage$"))

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "docs/usage.md matches /^## Usage$/ at line 3")
    }

    @Test
    fun `file_matches fails when the pattern is not found`() {
        write("docs/usage.md", "# Title\n")

        val result = run(CheckConfig.FileMatches("docs/usage.md", "^## Usage$"))

        assertFalse(result.passed)
        assertEquals(1, result.exitCode)
        assertContains(result.output, "docs/usage.md has no match for /^## Usage$/")
    }

    @Test
    fun `file_matches fails on a missing file or a folder`() {
        workspace.resolve("docs").createDirectories()

        val missing = run(CheckConfig.FileMatches("docs/usage.md", "x"))
        val folder = run(CheckConfig.FileMatches("docs", "x"))

        assertFalse(missing.passed)
        assertContains(missing.output, "docs/usage.md does not exist")
        assertFalse(folder.passed)
        assertContains(folder.output, "docs is a folder, not a file")
    }

    // diff_policy

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .apply {
                    home?.let { home ->
                        environment().keys.removeIf { it.startsWith("GIT_") || it == "XDG_CONFIG_HOME" }
                        environment()["HOME"] = home.toString()
                    }
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output
    }

    /** A repository whose `main` holds a protected `scripts/` and `WORKFLOW.md`, and a work branch checked out from it. */
    private fun repo() {
        git("init", "-q", "--initial-branch=main")
        write(".gitignore", "build/\n")
        write("WORKFLOW.md", "workflow\n")
        write("scripts/release.sh", "echo release\n")
        write("src/app.kt", "fun main() {}\n")
        git("add", ".")
        git("commit", "-q", "-m", "first")
        git("checkout", "-q", "-b", "loop/t-1")
    }

    private fun commitAll(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    private val protect = listOf("scripts/", "WORKFLOW.md", "**/src/test/**")

    private fun diffPolicy(base: String = "main") = run(CheckConfig.DiffPolicy(protect, base))

    @Test
    fun `diff_policy passes when the diff against the merge base touches no protected path`() {
        repo()
        write("src/app.kt", "fun main() { println() }\n")
        write("build/out.txt", "ignored\n")
        commitAll("work")
        write("src/more.kt", "untracked\n")

        val result = diffPolicy()

        assertTrue(result.passed, result.toString())
        assertContains(result.output, "touches no protected path")
    }

    @Test
    fun `diff_policy refutes a committed edit of a protected path and names it`() {
        repo()
        write("scripts/release.sh", "echo skipped\n")
        commitAll("work")

        val result = diffPolicy()

        assertFalse(result.passed)
        assertEquals(1, result.exitCode)
        assertNull(result.startError)
        assertContains(result.output, "modified scripts/release.sh (protected by scripts/)")
    }

    @Test
    fun `round 2 of PR #75, guards protect matches case insensitively, as a case insensitive checkout would`() {
        repo()
        write("lib/SRC/Test/AppTest.kt", "class AppTest\n")
        commitAll("work")

        val result = diffPolicy()

        assertFalse(result.passed)
        assertContains(result.output, "added lib/SRC/Test/AppTest.kt (protected by **/src/test/**)")
    }

    @Test
    fun `diff_policy counts uncommitted, staged and untracked changes`() {
        repo()
        write("WORKFLOW.md", "changed\n")
        write("scripts/new.sh", "new\n")
        write("lib/src/test/AppTest.kt", "staged\n")
        git("add", "lib/src/test/AppTest.kt")

        val result = diffPolicy()

        assertFalse(result.passed)
        assertContains(result.output, "modified WORKFLOW.md (protected by WORKFLOW.md)")
        assertContains(result.output, "added scripts/new.sh (protected by scripts/)")
        assertContains(result.output, "added lib/src/test/AppTest.kt (protected by **/src/test/**)")
    }

    @Test
    fun `diff_policy refutes deletions and renames out of or into a protected path`() {
        repo()
        write("scripts/other.sh", "echo other, long enough to be found as a rename\n")
        write("notes/plan.md", "a plan that is long enough to be found as a rename\n")
        git("checkout", "-q", "main")
        commitAll("more on main")
        git("checkout", "-q", "loop/t-1")
        git("merge", "-q", "main")
        workspace.resolve("tools").createDirectories()
        git("rm", "-q", "WORKFLOW.md")
        git("mv", "scripts/other.sh", "tools/other.sh")
        git("mv", "notes/plan.md", "scripts/plan.md")
        commitAll("work")

        val result = diffPolicy()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "deleted WORKFLOW.md (protected by WORKFLOW.md)")
        assertContains(result.output, "renamed scripts/other.sh to tools/other.sh (protected by scripts/)")
        assertContains(result.output, "renamed notes/plan.md to scripts/plan.md (protected by scripts/)")
    }

    @Test
    fun `diff_policy diffs against the merge base, so a protected change on main since then is not the attempt's`() {
        repo()
        write("src/app.kt", "fun main() { work() }\n")
        commitAll("work")
        git("checkout", "-q", "main")
        write("scripts/release.sh", "echo changed on main\n")
        commitAll("main moved")
        git("checkout", "-q", "loop/t-1")

        val result = diffPolicy()

        assertTrue(result.passed, result.output)
    }

    @Test
    fun `diff_policy uses the configured base`() {
        repo()
        git("branch", "trunk", "main")
        workspace.resolve("scripts/release.sh").deleteExisting()
        commitAll("work")

        val result = diffPolicy(base = "trunk")

        assertFalse(result.passed)
        assertContains(result.output, "The diff against the merge base with trunk")
        assertContains(result.output, "deleted scripts/release.sh")
    }

    @Test
    fun `diff_policy could not run without a git work tree or a merge base`() {
        val notGit = diffPolicy()
        repo()
        val noBase = diffPolicy(base = "nope")

        assertNotNull(notGit.startError, notGit.toString())
        assertContains(notGit.startError, "not the top of a git work tree")
        assertFalse(notGit.passed)
        assertNotNull(noBase.startError, noBase.toString())
        assertContains(noBase.startError, "no merge base with nope")
    }

    @Test
    fun `diff_policy against a commit id ignores a moved base branch`() {
        repo()
        val fetched = git("rev-parse", "main").trim()
        workspace.resolve("scripts/release.sh").deleteExisting()
        commitAll("work")
        // bl-32: the agent moves the base branch to its own head, which empties a diff against `main`.
        git("-C", ".", "update-ref", "refs/heads/main", "HEAD")

        val byName = diffPolicy()
        val byCommit = diffPolicy(base = fetched)

        assertTrue(byName.passed, byName.output)
        assertFalse(byCommit.passed, byCommit.output)
        assertContains(byCommit.output, "The diff against the merge base with $fetched")
        assertContains(byCommit.output, "deleted scripts/release.sh (protected by scripts/)")
    }

    @Test
    fun `diff_policy runs every git command with the commit graph off`() {
        repo()
        write("scripts/release.sh", "echo changed\n")
        commitAll("work")
        // A trace2 target in the user config logs the command line of every git the check starts.
        val trace = useHome().resolve("trace.log")
        home!!.resolve(".gitconfig").writeText("[trace2]\n\tnormalTarget = $trace\n")

        val result = deny(protect)

        assertFalse(result.passed, result.output)
        val started = trace.readText().lines().filter { " start " in it && " -C " in it }
        assertTrue(started.isNotEmpty(), trace.readText())
        started.forEach { line -> assertContains(line, "-c core.commitGraph=false") }
    }

    @Test
    fun `bl-246 the git of the core checks runs with lazy fetch off, whatever the check's environment says`() {
        val env = CoreChecks.gitEnvironment(mapOf("PATH" to path, "GIT_NO_LAZY_FETCH" to "0", "GIT_DIR" to "/elsewhere"))

        assertEquals("1", env["GIT_NO_LAZY_FETCH"])
        assertNull(env["GIT_DIR"])
        assertEquals(path, env["PATH"])
    }

    @Test
    fun `bl-246 a promisor the agent configures never runs from diff_policy or changed_line_coverage`() {
        repo()
        write("src/app.kt", "fun main() { println() }\n")
        commitAll("work")
        val trap = LazyFetchTrap(workspace, workspace.resolveSibling("${workspace.fileName}-lazy-fetch-ran"), "refs/heads/loop/t-1").arm()
        trap.proveLive()

        val policy = diffPolicy()
        val coverage = run(CheckConfig.ChangedLineCoverage(listOf("report.xml"), 80.0, "main"))

        assertFalse(trap.marker.exists(), "a check ran the agent's program")
        assertFalse(policy.passed, policy.output)
        assertFalse(coverage.passed, coverage.output)
    }

    // deny guards (m2-13)

    private val kotlinTest = "lib/src/test/kotlin/calc/CalcTest.kt"
    private val swiftTest = "CalcTests/CalcTests.swift"

    private val kotlinText =
        """
        |class CalcTest {
        |    @Test
        |    fun adds() {
        |        assertEquals(4, add(2, 2))
        |    }
        |
        |    @Test
        |    fun divides() {
        |        assertEquals(2, divide(4, 2))
        |        assertEquals(0, divide(0, 2))
        |    }
        |}
        |
        """.trimMargin()

    private val swiftText =
        """
        |import XCTest
        |
        |final class CalcTests: XCTestCase {
        |    func testAdds() throws {
        |        XCTAssertEqual(add(2, 2), 4)
        |    }
        |
        |    func testDivides() throws {
        |        XCTAssertEqual(divide(4, 2), 2)
        |        XCTAssertEqual(divide(0, 2), 0)
        |    }
        |}
        |
        """.trimMargin()

    /** [repo] plus a Kotlin and a Swift test file on `main`. */
    private fun testRepo() {
        repo()
        git("checkout", "-q", "main")
        write(kotlinTest, kotlinText)
        write(swiftTest, swiftText)
        commitAll("tests")
        git("checkout", "-q", "loop/t-1")
        git("merge", "-q", "main")
    }

    private fun deny(protect: List<String> = emptyList()) = run(CheckConfig.DiffPolicy(protect, "main", DenyGuard.entries))

    private fun edit(
        name: String,
        change: (String) -> String,
    ) {
        val file = workspace.resolve(name)
        file.writeText(change(file.readText()))
    }

    @Test
    fun `deny guards pass on work that keeps every test, skip free, with its assertions`() {
        testRepo()
        write("src/app.kt", "fun main() { println() }\n")
        edit(
            kotlinTest,
        ) { it.replace("    }\n}\n", "    }\n\n    @Test\n    fun subtracts() {\n        assertEquals(1, sub(2, 1))\n    }\n}\n") }
        commitAll("work")

        val result = deny()

        assertTrue(result.passed, result.output)
        assertContains(result.output, "breaks no deny guard (deleted_tests, added_skips, weaker_assertions)")
    }

    @Test
    fun `a committed Kotlin or Swift test function deletion is refuted with deleted_tests`() {
        testRepo()
        edit(kotlinTest) { it.replace(Regex("""(?s)\n    @Test\n    fun divides.*?\n    }\n"""), "\n") }
        edit(swiftTest) { it.replace(Regex("""(?s)\n    func testDivides.*?\n    }\n"""), "\n") }
        commitAll("drop the failing tests")

        val result = deny()

        assertFalse(result.passed, result.output)
        assertEquals(1, result.exitCode)
        assertNull(result.startError)
        assertContains(result.output, "The diff against the merge base with main")
        assertContains(result.output, "deleted_tests: removed test divides from $kotlinTest")
        assertContains(result.output, "deleted_tests: removed test testDivides from $swiftTest")
    }

    @Test
    fun `a deleted Kotlin or Swift test file, committed or not, is refuted with deleted_tests`() {
        testRepo()
        git("rm", "-q", kotlinTest)
        commitAll("drop")
        workspace.resolve(swiftTest).deleteExisting()

        val result = deny()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "deleted_tests: deleted test file $kotlinTest (2 tests: adds, divides)")
        assertContains(result.output, "deleted_tests: deleted test file $swiftTest (2 tests: testAdds, testDivides)")
    }

    @Test
    fun `an added @Disabled, @Ignore or XCTSkip, committed, staged or not, is refuted with added_skips`() {
        testRepo()
        edit(kotlinTest) { it.replace("    @Test\n    fun adds()", "    @Disabled\n    @Test\n    fun adds()") }
        commitAll("skip")
        edit(kotlinTest) { it.replace("    @Test\n    fun divides()", "    @Ignore(\"later\")\n    @Test\n    fun divides()") }
        git("add", kotlinTest)
        edit(swiftTest) { it.replace("func testDivides() throws {\n", "func testDivides() throws {\n        throw XCTSkip(\"later\")\n") }

        val result = deny()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "added_skips: added a skip to $kotlinTest: @Disabled\n")
        assertContains(result.output, "added_skips: added a skip to $kotlinTest: @Ignore(\"later\")")
        assertContains(result.output, "added_skips: added a skip to $swiftTest: throw XCTSkip(\"later\")")
    }

    @Test
    fun `removed assertions with none added are refuted with weaker_assertions`() {
        testRepo()
        edit(kotlinTest) { it.replace("        assertEquals(0, divide(0, 2))\n", "") }
        edit(swiftTest) { it.replace("        XCTAssertEqual(divide(0, 2), 0)\n", "        _ = divide(0, 2)\n") }

        val result = deny()

        assertFalse(result.passed, result.output)
        assertContains(
            result.output,
            "weaker_assertions: removed 1 assertion from $kotlinTest and added none: assertEquals(0, divide(0, 2))",
        )
        assertContains(
            result.output,
            "weaker_assertions: removed 1 assertion from $swiftTest and added none: XCTAssertEqual(divide(0, 2), 0)",
        )
    }

    @Test
    fun `a new untracked test file with a skip is refuted, an ignored one is never delivered and not read`() {
        testRepo()
        write(".gitignore", "build/\n")
        write("lib/src/test/kotlin/calc/NewTest.kt", "class NewTest {\n    @Disabled\n    @Test\n    fun x() {}\n}\n")
        write("build/src/test/kotlin/GenTest.kt", "class GenTest {\n    @Disabled\n    @Test\n    fun x() {}\n}\n")

        val result = deny()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "added_skips: added a skip to lib/src/test/kotlin/calc/NewTest.kt: @Disabled")
        assertFalse("GenTest" in result.output, result.output)
    }

    @Test
    fun `protected paths and deny guards are reported together, one heading each`() {
        testRepo()
        write("scripts/release.sh", "echo skipped\n")
        git("rm", "-q", kotlinTest)

        val result = deny(protect = listOf("scripts/"))

        assertFalse(result.passed, result.output)
        assertContains(result.output, "touches 1 protected path:\nmodified scripts/release.sh (protected by scripts/)\n")
        assertContains(result.output, "breaks 2 deny guards, deleted_tests and weaker_assertions:\n")
        assertContains(result.output, "deleted_tests: deleted test file $kotlinTest")
    }

    // the diff_policy bypasses of the m2-12 review, closed in m2-13

    @Test
    fun `a protected file hidden by an ignore rule or info exclude is still found`() {
        repo()
        write(".git/info/exclude", "scripts/hidden.sh\n")
        write("scripts/hidden.sh", "echo hidden\n")
        write(".gitignore", "build/\nWORKFLOW.local\n")
        write("build/out.txt", "build output, not protected\n")

        val result = diffPolicy()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "added and ignored scripts/hidden.sh (protected by scripts/)")
        assertFalse("build/out.txt" in result.output, result.output)
    }

    @Test
    fun `a nested repository covers every path under it`() {
        repo()
        write("scripts/vendor/tool.sh", "echo nested\n")
        ProcessBuilder("git", "init", "-q").directory(workspace.resolve("scripts/vendor").toFile()).start().waitFor()
        write("other/repo/file.txt", "unprotected\n")
        ProcessBuilder("git", "init", "-q").directory(workspace.resolve("other/repo").toFile()).start().waitFor()

        val result = diffPolicy()

        assertFalse(result.passed, result.output)
        assertContains(result.output, "added nested repository scripts/vendor/ (protected by scripts/)")
        assertFalse("other/repo" in result.output, result.output)
    }

    @Test
    fun `an edit hidden by assume-unchanged or by skip-worktree on a present file means the check could not run`() {
        repo()
        git("update-index", "--assume-unchanged", "scripts/release.sh")
        write("scripts/release.sh", "echo hidden edit\n")

        val assumed = diffPolicy()

        assertNotNull(assumed.startError, assumed.toString())
        assertContains(
            assumed.startError,
            "git cannot see the edits of 1 path marked assume-unchanged or skip-worktree: scripts/release.sh",
        )

        git("update-index", "--no-assume-unchanged", "scripts/release.sh")
        git("checkout", "--", "scripts/release.sh")
        git("update-index", "--skip-worktree", "WORKFLOW.md")
        write("WORKFLOW.md", "hidden edit\n")

        val skipped = diffPolicy()

        assertNotNull(skipped.startError, skipped.toString())
        assertContains(skipped.startError, "WORKFLOW.md")
    }

    @Test
    fun `a sparse checkout outside the protected and test paths passes, a protected one could not run`() {
        testRepo()
        git("checkout", "-q", "main")
        write(".loop/issues/t-1.md", "an issue\n")
        commitAll("issues")
        git("checkout", "-q", "loop/t-1")
        git("merge", "-q", "main")
        git("sparse-checkout", "set", "--no-cone", "/*", "!/.loop/issues/")

        val sparse = deny(protect = listOf("scripts/"))

        assertTrue(sparse.passed, sparse.toString())

        val protectedSparse = deny(protect = listOf(".loop/"))

        assertNull(protectedSparse.exitCode, protectedSparse.toString())
        assertNotNull(protectedSparse.startError, protectedSparse.toString())
        assertContains(protectedSparse.startError, "skip-worktree keeps 1 protected or test path out of the working tree")
        assertContains(protectedSparse.startError, ".loop/issues/t-1.md")
    }

    @Test
    fun `a protected or test path flagged skip-worktree and deleted means the check could not run`() {
        testRepo()
        git("update-index", "--skip-worktree", "scripts/release.sh")
        workspace.resolve("scripts/release.sh").deleteExisting()
        assertEquals("", gitSeesChanged())

        val protectedPath = diffPolicy()

        assertNull(protectedPath.exitCode, protectedPath.toString())
        assertNotNull(protectedPath.startError, protectedPath.toString())
        assertContains(
            protectedPath.startError,
            "skip-worktree keeps 1 protected or test path out of the working tree, so git cannot see it changed or deleted: " +
                "scripts/release.sh",
        )

        git("update-index", "--no-skip-worktree", "scripts/release.sh")
        git("checkout", "--", "scripts/release.sh")
        git("update-index", "--skip-worktree", kotlinTest)
        workspace.resolve(kotlinTest).deleteExisting()
        assertEquals("", gitSeesChanged())

        val testPath = deny()

        assertNull(testPath.exitCode, testPath.toString())
        assertNotNull(testPath.startError, testPath.toString())
        assertContains(testPath.startError, "so git cannot see it changed or deleted: $kotlinTest")
    }

    // the review of #55 (m2-13)

    @Test
    fun `a guard that throws, even a StackOverflowError, means the check could not run`() {
        testRepo()
        val deep = TestLanguage(name = "deep", files = listOf("data/"), test = """(?:a|b|\n)*c""")
        write("data/deep.txt", ("a".repeat(1_000) + "\n").repeat(200))

        val result = run(CheckConfig.DiffPolicy(emptyList(), "main", DenyGuard.entries, TestLanguage.BUILT_IN + deep))

        assertNull(result.exitCode, result.toString())
        assertNotNull(result.startError, result.toString())
        assertContains(result.startError, "StackOverflowError")
    }

    @Test
    fun `a long changed line in a test file means the check could not run, quickly`() {
        testRepo()
        edit(kotlinTest) { it.replace("    fun adds() {\n", "    fun adds() {\n        val s = \"" + "assert<".repeat(35_000) + "\"\n") }

        val result = deny()

        assertNull(result.exitCode, result.toString())
        assertNotNull(result.startError, result.toString())
        assertContains(result.startError, "a changed line of $kotlinTest has 245010 characters")
        assertTrue(result.durationMs < 5_000, result.toString())
    }

    @Test
    fun `a renamed test file with edited assertions is not weaker_assertions`() {
        testRepo()
        git("mv", kotlinTest, "lib/src/test/kotlin/calc/CalculatorTest.kt")
        edit("lib/src/test/kotlin/calc/CalculatorTest.kt") { it.replace("divide(0, 2))", "divide(0, 7))") }
        commitAll("rename")

        val result = deny()

        assertTrue(result.passed, result.toString())
    }

    @Test
    fun `an ignored nested repository is not walked, a protected folder still matches it`() {
        repo()
        write(".gitignore", "build/\n.build/\nscripts/cache/\n")
        commitAll("ignore")
        write(".build/checkouts/dep/Package.swift", "// a dependency\n")
        ProcessBuilder("git", "init", "-q").directory(workspace.resolve(".build/checkouts/dep").toFile()).start().waitFor()
        val packages = CheckConfig.DiffPolicy(listOf("**/Package.swift"))

        val ignored = run(packages)

        assertTrue(ignored.passed, ignored.toString())

        write("scripts/cache/tool/run.sh", "echo nested\n")
        ProcessBuilder("git", "init", "-q").directory(workspace.resolve("scripts/cache/tool").toFile()).start().waitFor()

        val folder = diffPolicy()

        assertFalse(folder.passed, folder.toString())
        assertContains(folder.output, "added and ignored nested repository scripts/cache/tool/ (protected by scripts/)")
    }

    // the recheck of 095941a (m2-13): an agent runs as the same user, so a git setting outside the workspace counts

    /** Makes [home] the user's home for the test's git and for the check, so its `.gitconfig` and attributes apply. */
    private fun useHome(): Path = createTempDirectory("loop-home").toRealPath().also { home = it }

    /** The paths git itself reads as changed against `main`, to show that a bypass really hides the edit. */
    private fun gitSeesChanged(): String = git("diff", "--name-only", "main", "--").trim()

    /** A gutted [kotlinTest]: its `divides` test and both of its assertions are gone. */
    private fun gutKotlinTest() = edit(kotlinTest) { it.replace(Regex("""(?s)\n    @Test\n    fun divides.*?\n    }\n"""), "\n") }

    private fun assertFilterCouldNotRun(
        result: CheckResult,
        filter: String,
    ) {
        assertNull(result.exitCode, result.toString())
        assertNotNull(result.startError, result.toString())
        assertContains(result.startError, "a git filter can hide the edits of 1 protected or test path: $kotlinTest (filter=$filter)")
    }

    @Test
    fun `a filter in the repository config with info attributes on a test file means the check could not run`() {
        testRepo()
        useHome()
        git("config", "filter.h.clean", "git cat-file blob HEAD:%f")
        write(".git/info/attributes", "*.kt filter=h\n")
        gutKotlinTest()
        assertEquals("", gitSeesChanged())

        assertFilterCouldNotRun(deny(), "h")
    }

    @Test
    fun `a filter in the user config with info attributes on a test file means the check could not run`() {
        testRepo()
        useHome().resolve(".gitconfig").writeText("[filter \"h\"]\n\tclean = git cat-file blob HEAD:%f\n")
        write(".git/info/attributes", "*.kt filter=h\n")
        gutKotlinTest()
        assertEquals("", gitSeesChanged())

        assertFilterCouldNotRun(deny(), "h")
    }

    @Test
    fun `a filter in the user config and the user attributes, nothing in the repository, means the check could not run`() {
        testRepo()
        val home = useHome()
        home.resolve(".gitconfig").writeText("[filter \"h\"]\n\tclean = git cat-file blob HEAD:%f\n")
        home.resolve(".config/git").createDirectories()
        home.resolve(".config/git/attributes").writeText("*.kt filter=h\n")
        gutKotlinTest()
        assertEquals("", gitSeesChanged())

        assertFilterCouldNotRun(deny(), "h")
    }

    @Test
    fun `a filter in the user config with a committed gitattributes means the check could not run, for protect too`() {
        testRepo()
        useHome().resolve(".gitconfig").writeText("[filter \"h\"]\n\tclean = git cat-file blob HEAD:%f\n")
        write(".gitattributes", "*.kt filter=h\n")
        commitAll("attributes")
        gutKotlinTest()
        assertEquals(".gitattributes", gitSeesChanged())

        assertFilterCouldNotRun(deny(), "h")
        assertFilterCouldNotRun(run(CheckConfig.DiffPolicy(listOf("lib/"))), "h")
    }

    @Test
    fun `a redefined lfs filter in the user config on a test file means the check could not run`() {
        testRepo()
        useHome().resolve(".gitconfig").writeText("[filter \"lfs\"]\n\tclean = git cat-file blob HEAD:%f\n\trequired = true\n")
        write(".git/info/attributes", "*.kt filter=lfs\n")
        gutKotlinTest()
        assertEquals("", gitSeesChanged())

        assertFilterCouldNotRun(deny(), "lfs")
    }

    @Test
    fun `a same size edit with its mtime restored under core trustctime false means the check could not run`() {
        testRepo()
        useHome().resolve(".gitconfig").writeText("[core]\n\ttrustctime = false\n")
        val file = workspace.resolve(kotlinTest)
        val old = FileTime.fromMillis(1_600_000_000_000L)
        Files.setLastModifiedTime(file, old)
        git("update-index", "--refresh")
        val before = Files.size(file)
        val assertion = "assertEquals(0, divide(0, 2))"
        edit(kotlinTest) { it.replace(assertion, "println(0 + divide(0, 2))".padEnd(assertion.length)) }
        assertEquals(before, Files.size(file))
        Files.setLastModifiedTime(file, old)
        assertEquals("", gitSeesChanged())
        assertTrue(git("ls-files", "-v", kotlinTest).startsWith("H "))

        val result = deny()

        assertNull(result.exitCode, result.toString())
        assertNotNull(result.startError, result.toString())
        assertContains(
            result.startError,
            "git reads 1 protected or test path as unchanged, but its content differs from the merge base: $kotlinTest",
        )
    }

    @Test
    fun `a stock lfs setup in the repository config on other paths passes, on a test path it could not run`() {
        testRepo()
        useHome()
        git("config", "filter.lfs.clean", "git-lfs clean -- %f")
        git("config", "filter.lfs.smudge", "git-lfs smudge -- %f")
        git("config", "filter.lfs.process", "git-lfs filter-process")
        git("config", "filter.lfs.required", "true")
        write(".gitattributes", "*.png filter=lfs diff=lfs merge=lfs -text\n*.zip filter=lfs diff=lfs merge=lfs -text\n")
        write("src/app.kt", "fun main() { println() }\n")
        edit(
            kotlinTest,
        ) { it.replace("    }\n}\n", "    }\n\n    @Test\n    fun subtracts() {\n        assertEquals(1, sub(2, 1))\n    }\n}\n") }
        commitAll("work")

        val stock = deny(protect = listOf("scripts/", "WORKFLOW.md"))

        assertTrue(stock.passed, stock.toString())

        write(".git/info/attributes", "*.kt filter=lfs\n")

        assertFilterCouldNotRun(deny(), "lfs")
    }

    @Test
    fun `a test file checked out with CRLF line ends and otherwise unchanged passes`() {
        testRepo()
        useHome()
        git("config", "core.autocrlf", "true")
        workspace.resolve(kotlinTest).deleteExisting()
        git("checkout", "--", kotlinTest)
        assertTrue("\r\n" in workspace.resolve(kotlinTest).readText())
        assertTrue(git("hash-object", "--no-filters", kotlinTest) != git("rev-parse", "main:$kotlinTest"))
        assertEquals("", gitSeesChanged())

        val result = deny(protect = listOf("lib/"))

        assertTrue(result.passed, result.toString())
    }

    @Test
    fun `the content comparison hashes like git in a sha256 repository`() {
        git("init", "-q", "--initial-branch=main", "--object-format=sha256")
        write(kotlinTest, kotlinText)
        write("scripts/release.sh", "echo release\n")
        commitAll("first")
        git("checkout", "-q", "-b", "loop/t-1")
        write("src/app.kt", "fun main() {}\n")
        commitAll("work")

        val result = deny(protect = listOf("scripts/"))

        assertTrue(result.passed, result.toString())
    }

    // the recheck of 9b46b19 (m2-13): replace refs, sparse deletes, committed then restored edits

    @Test
    fun `a replace ref made with git replace that swaps the merge base for HEAD's tree is not honoured`() {
        testRepo()
        useHome()
        gutKotlinTest()
        commitAll("gut")
        val mergeBase = git("merge-base", "main", "HEAD").trim()
        val fake = git("commit-tree", "-m", "x", "HEAD^{tree}").trim()
        git("replace", mergeBase, fake)
        assertEquals("", gitSeesChanged())

        val result = deny(protect = listOf("lib/"))

        assertFalse(result.passed, result.toString())
        assertNull(result.startError, result.toString())
        assertContains(result.output, "modified $kotlinTest (protected by lib/)")
        assertContains(result.output, "deleted_tests: removed test divides from $kotlinTest")
    }

    @Test
    fun `a replace ref written as a file, with no git command, is not honoured`() {
        testRepo()
        useHome()
        gutKotlinTest()
        commitAll("gut")
        val baseTree = git("rev-parse", "main^{tree}").trim()
        val headTree = git("rev-parse", "HEAD^{tree}").trim()
        write(".git/refs/replace/$baseTree", "$headTree\n")
        assertEquals("", gitSeesChanged())

        val result = deny()

        assertFalse(result.passed, result.toString())
        assertNull(result.startError, result.toString())
        assertContains(result.output, "deleted_tests: removed test divides from $kotlinTest")
    }

    @Test
    fun `a grafts file that makes HEAD the merge base is not honoured`() {
        testRepo()
        useHome()
        gutKotlinTest()
        commitAll("gut")
        git("checkout", "-q", "main")
        write("notes.md", "main moved on\n")
        commitAll("main moved")
        git("checkout", "-q", "loop/t-1")
        write(".git/info/grafts", git("rev-parse", "main").trim() + " " + git("rev-parse", "HEAD").trim() + "\n")
        val grafted = git("-c", "advice.graftFileDeprecated=false", "merge-base", "main", "HEAD").trim()
        assertEquals(git("rev-parse", "HEAD").trim(), grafted)

        val result = deny()

        assertFalse(result.passed, result.toString())
        assertNull(result.startError, result.toString())
        assertContains(result.output, "deleted_tests: removed test divides from $kotlinTest")
    }

    @Test
    fun `a committed protected edit with the working tree restored is refuted`() {
        repo()
        val original = workspace.resolve("scripts/release.sh").readText()
        write("scripts/release.sh", "echo skipped\n")
        commitAll("work")
        write("scripts/release.sh", original)
        assertEquals("", gitSeesChanged())

        val result = diffPolicy()

        assertFalse(result.passed, result.toString())
        assertNull(result.startError, result.toString())
        assertContains(result.output, "modified scripts/release.sh in HEAD (protected by scripts/)")
    }

    @Test
    fun `a committed mode change of a protected path, with the working tree file unchanged, is refuted`() {
        repo()
        git("update-index", "--chmod=+x", "scripts/release.sh")
        git("commit", "-q", "-m", "chmod")
        assertFalse(Files.isExecutable(workspace.resolve("scripts/release.sh")))
        assertEquals("", gitSeesChanged())

        val result = diffPolicy()

        assertFalse(result.passed, result.toString())
        assertContains(result.output, "modified scripts/release.sh in HEAD (protected by scripts/)")
    }

    @Test
    fun `a committed test deletion with the working tree restored is refuted with deleted_tests`() {
        testRepo()
        gutKotlinTest()
        commitAll("gut")
        write(kotlinTest, kotlinText)
        assertEquals("", gitSeesChanged())

        val result = deny()

        assertFalse(result.passed, result.toString())
        assertNull(result.startError, result.toString())
        assertContains(result.output, "deleted_tests: removed test divides from $kotlinTest (in HEAD)")
        assertContains(result.output, "weaker_assertions: removed 2 assertions from $kotlinTest and added none")
    }

    @Test
    fun `a process filter in the user config on a test file means the check could not run, like a clean one`() {
        testRepo()
        useHome().resolve(".gitconfig").writeText("[filter \"h\"]\n\tprocess = my-filter-process\n")
        write(".git/info/attributes", "*.kt filter=h\n")
        gutKotlinTest()

        assertFilterCouldNotRun(deny(), "h")
    }

    // globs

    @Test
    fun `protected path globs are anchored at the workspace root`() {
        fun matches(
            glob: String,
            path: String,
        ) = ProtectedPaths(listOf(glob)).match(path) != null

        assertTrue(matches("WORKFLOW.md", "WORKFLOW.md"))
        assertFalse(matches("WORKFLOW.md", "docs/WORKFLOW.md"))
        assertFalse(matches("WORKFLOW.md", "workflow.md"), "exact case by default, for the languages' test files")
        assertTrue(ProtectedPaths(listOf("WORKFLOW.md"), ignoreCase = true).match("workflow.md") != null)
        assertTrue(ProtectedPaths(listOf("scripts/"), ignoreCase = true).match("Scripts/a.sh") != null)
        assertTrue(ProtectedPaths(listOf("**/ÉTÉ.md"), ignoreCase = true).match("a/été.md") != null, "unicode case")
        assertTrue(matches("scripts/", "scripts/a/b.sh"))
        assertFalse(matches("scripts/", "scripts.txt"))
        assertFalse(matches("scripts/", "lib/scripts/a.sh"))
        assertTrue(matches("scripts", "scripts/a.sh"))
        assertTrue(matches("*.gradle.kts", "build.gradle.kts"))
        assertFalse(matches("*.gradle.kts", "cli/build.gradle.kts"))
        assertTrue(matches("**/*.gradle.kts", "cli/build.gradle.kts"))
        assertTrue(matches("**/*.gradle.kts", "build.gradle.kts"))
        assertTrue(matches("**/src/test/**", "src/test/A.kt"))
        assertTrue(matches("**/src/test/**", "cli/src/test/kotlin/A.kt"))
        assertFalse(matches("**/src/test/**", "cli/src/main/A.kt"))
        assertTrue(matches("docs/adr/*.md", "docs/adr/0001.md"))
        assertFalse(matches("docs/adr/*.md", "docs/adr/old/0001.md"))
        assertTrue(matches("a?c.txt", "abc.txt"))
        assertFalse(matches("a.c", "abc"))
    }
}
