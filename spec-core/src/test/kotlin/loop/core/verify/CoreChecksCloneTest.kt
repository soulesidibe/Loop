package loop.core.verify

import loop.core.workflow.CheckConfig
import loop.core.workspace.CloneState
import loop.core.workspace.WorkspaceClones
import loop.core.workspace.WorkspaceKey
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-254: in `workspace.git: clone`, the git of `diff_policy` and `changed_line_coverage` follows the same rule as
 * [WorkspaceGit] (bl-253): the layout check before every process, then `GIT_DIR`, `GIT_COMMON_DIR` and `GIT_WORK_TREE`
 * pinned to the clone. Each hostile layout is first proven live with git run as the checks run it today, then refused
 * or neutralized on a fresh fixture, the project unchanged. Real git, no user or system config. Each proof is printed
 * as a line starting with `PROOF`, kept in the test report.
 */
@OptIn(ExperimentalPathApi::class)
class CoreChecksCloneTest {
    private val folder: Path = createTempDirectory("loop-core-checks-clone").toRealPath()
    private val home: Path = Files.createDirectories(folder.resolve("home"))
    private val key = WorkspaceKey("t-1")
    private val hostEnv = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to home.toString(), "LANG" to "C")
    private val calc = "src/app/Calc.kt"

    private data class Ran(
        val code: Int,
        val out: String,
    )

    private fun exec(
        command: List<String>,
        env: Map<String, String>? = null,
    ): Ran {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    if (env != null) {
                        environment().clear()
                        environment().putAll(env)
                    } else {
                        environment().keys.removeIf { it.startsWith("GIT_") }
                        environment()["HOME"] = home.toString()
                    }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        return Ran(process.waitFor(), out.trim())
    }

    private fun sh(
        dir: Path,
        vararg args: String,
    ): String {
        val ran = exec(listOf("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
        check(ran.code == 0) { "git ${args.toList()} in $dir failed: ${ran.out}" }
        return ran.out
    }

    private fun proof(
        name: String,
        text: String,
    ) = println("PROOF $name: $text")

    private fun sha256(path: Path): String =
        if (Files.exists(path)) HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) else "none"

    private fun commit(
        dir: Path,
        file: String,
        text: String,
    ): String {
        Files.createDirectories(dir.resolve(file).parent)
        dir.resolve(file).writeText(text)
        sh(dir, "add", file)
        sh(dir, "commit", "-q", "-m", "change $file")
        return sh(dir, "rev-parse", "HEAD")
    }

    /** What of the project a check must leave alone. */
    private data class Snapshot(
        val refs: String,
        val files: Map<String, String>,
        val index: String,
        val status: String,
    )

    /**
     * A project with a branch `side`, an untracked file, and a clone made by Loop's own code that commits [calc] and
     * holds an untracked `clone-only.txt` and a coverage report. After the clone, the project's `main` moves on, so a
     * read of the project's refs shows.
     */
    private inner class Fixture(
        name: String,
        rootInside: Boolean = false,
    ) {
        val project: Path = Files.createDirectories(folder.resolve(name).resolve("project"))
        val root: Path =
            Files.createDirectories(if (rootInside) project.resolve(".ws") else folder.resolve(name).resolve("workspaces"))
        val workspace: Path = root.resolve(key.value)
        val clones = CloneRoots.of(CloneScope(root, project))
        val dotGit: Path get() = workspace.resolve(".git")
        val first: String
        val projectMain: String

        init {
            sh(project, "init", "-q", "--initial-branch=main")
            first = commit(project, "README.md", "A project.\n")
            if (rootInside) project.resolve(".gitignore").writeText(".ws/\n")
            val clonesGit = WorkspaceClones(project, WorkspaceGit(hostEnv, clones = clones))
            Files.createDirectories(workspace)
            clonesGit.create(workspace, key, null)
            assertIs<CloneState.Clone>(clonesGit.state(workspace))
            clonesGit.reuse(workspace, key)
            commit(workspace, calc, "package app\n\nfun a() = 1\n")
            workspace.resolve("clone-only.txt").writeText("the agent's untracked file\n")
            workspace.resolve("report.xml").writeText(report(listOf(3)))
            sh(workspace, "config", "core.excludesFile", "/dev/null")
            projectMain = commit(project, "later.txt", "the project moved on\n")
            project.resolve("untracked.txt").writeText("the operator's own file\n")
        }

        fun snapshot(): Snapshot {
            val files =
                Files.walk(project).use { walk ->
                    walk
                        .filter { Files.isRegularFile(it) }
                        .filter { !it.startsWith(project.resolve(".git")) && !it.startsWith(root) }
                        .toList()
                        .associate { project.relativize(it).toString() to sha256(it) }
                }
            return Snapshot(
                refs = sh(project, "for-each-ref"),
                files = files,
                index = sha256(project.resolve(".git/index")),
                status = sh(project, "--no-optional-locks", "status", "--porcelain", "--untracked-files=all"),
            )
        }

        /** Git in the clone exactly as the core checks start it today: their pins and environment, no check, no pin. */
        fun asChecksToday(vararg args: String): Ran {
            val command = listOf("git") + WorkspaceGit.PINS + listOf("-C", workspace.toString(), "--no-replace-objects") + args
            return exec(command, CoreChecks.gitEnvironment(hostEnv))
        }

        fun hostileCommondir() {
            dotGit.resolve("commondir").writeText("${project.resolve(".git")}\n")
        }

        fun hostileRefStorage() {
            val config = dotGit.resolve("config").toString()
            sh(folder, "config", "--file", config, "core.repositoryformatversion", "1")
            sh(folder, "config", "--file", config, "extensions.refStorage", "files://${project.resolve(".git")}")
        }

        fun diffPolicy(
            clones: CloneRoots = this.clones,
            env: Map<String, String> = hostEnv,
            startProcess: (ProcessBuilder) -> Process = { it.start() },
        ) = CoreChecks.diffPolicy(
            CheckConfig.DiffPolicy(listOf("clone-only.txt", "untracked.txt", "later.txt"), base = first),
            workspace,
            env,
            clones,
            startProcess,
        )

        fun coverage(
            clones: CloneRoots = this.clones,
            env: Map<String, String> = hostEnv,
            startProcess: (ProcessBuilder) -> Process = { it.start() },
        ) = CoreChecks.changedLineCoverage(
            CheckConfig.ChangedLineCoverage(listOf("report.xml"), 100.0, base = first),
            workspace,
            env,
            clones,
            startProcess,
        )

        /** Both checks end as could not run, their reason naming [named]. */
        fun assertBothRefuse(named: String): String {
            val policy = diffPolicy()
            val covered = coverage()
            assertContains(policy.couldNotRun.orEmpty(), named, message = policy.toString())
            assertContains(policy.couldNotRun.orEmpty(), "Loop runs no git in the clone")
            assertContains(covered.couldNotRun.orEmpty(), named, message = covered.toString())
            return "diff_policy: ${policy.couldNotRun}; changed_line_coverage: ${covered.couldNotRun}"
        }
    }

    /** A JaCoCo report covering [lines] of [calc]. */
    private fun report(lines: List<Int>): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           |<report name="fixture">
           |<package name="app"><class name="app/X" sourcefilename="Calc.kt"/><sourcefile name="Calc.kt">${lines.joinToString("") {
            """<line nr="$it" mi="0" ci="2" mb="0" cb="0"/>"""
        }}<counter type="INSTRUCTION" missed="0" covered="0"/></sourcefile></package>
           |<counter type="INSTRUCTION" missed="0" covered="0"/>
           |</report>
        """.trimMargin()

    @Test
    fun `a hostile commondir set after reuse makes both checks unable to run, naming it, the project unchanged`() {
        val live = Fixture("commondir-live")
        live.hostileCommondir()
        val read = live.asChecksToday("rev-parse", "refs/heads/main")
        assertEquals(live.projectMain, read.out)
        proof("commondir live", "the core checks' git in the clone read the project's main, ${read.out}")

        val fx = Fixture("commondir")
        val before = fx.snapshot()
        fx.hostileCommondir()
        val reasons = fx.assertBothRefuse(fx.dotGit.resolve("commondir").toString())
        assertEquals(before, fx.snapshot())
        proof("commondir guarded", reasons)
    }

    @Test
    fun `a hostile extensions refStorage set late makes both checks unable to run, naming the key, no project ref changes`() {
        val live = Fixture("refstorage-live")
        live.hostileRefStorage()
        val read = live.asChecksToday("rev-parse", "refs/heads/main")
        assertEquals(live.projectMain, read.out)
        proof("refStorage live", "the core checks' git in the clone read the project's main, ${read.out}")

        val fx = Fixture("refstorage")
        val before = fx.snapshot()
        fx.hostileRefStorage()
        val reasons = fx.assertBothRefuse("extensions.refStorage")
        assertEquals(before, fx.snapshot())
        proof("refStorage guarded", reasons)
    }

    @Test
    fun `a git file naming the project or a missing git makes both checks unable to run, project refs and index unchanged`() {
        val file = Fixture("gitdir-file")
        val before = file.snapshot()
        file.dotGit.deleteRecursively()
        file.dotGit.writeText("gitdir: ${file.project.resolve(".git")}\n")
        assertEquals(file.project.resolve(".git").toString(), file.asChecksToday("rev-parse", "--absolute-git-dir").out)
        proof("gitdir file live", "the core checks' git in the clone found the project's repository")
        val fileReasons = file.assertBothRefuse(file.dotGit.toString())
        assertEquals(before, file.snapshot())
        proof("gitdir file guarded", fileReasons)

        val missing = Fixture("git-missing", rootInside = true)
        val missingBefore = missing.snapshot()
        missing.dotGit.deleteRecursively()
        assertEquals(missing.project.resolve(".git").toString(), missing.asChecksToday("rev-parse", "--absolute-git-dir").out)
        proof("missing .git live", "the core checks' git in the clone walked up to the project's repository")
        val missingReasons = missing.assertBothRefuse(missing.dotGit.toString())
        assertEquals(missingBefore, missing.snapshot())
        proof("missing .git guarded", missingReasons)
    }

    @Test
    fun `a core worktree naming the project never lets the checks read the project's files`() {
        for (variant in listOf("config", "config.worktree")) {
            fun setWorktree(fx: Fixture) {
                if (variant == "config") {
                    sh(fx.workspace, "config", "core.worktree", fx.project.toString())
                } else {
                    sh(fx.workspace, "config", "extensions.worktreeConfig", "true")
                    sh(fx.workspace, "config", "--worktree", "core.worktree", fx.project.toString())
                }
            }

            val live = Fixture("worktree-$variant-live")
            setWorktree(live)
            val listed = live.asChecksToday("ls-files", "--others", "--exclude-standard").out
            assertContains(listed, "untracked.txt")
            assertFalse("clone-only.txt" in listed)
            proof("core.worktree $variant live", "the core checks' ls-files in the clone listed the project's files: ${listed.lines()}")

            val fx = Fixture("worktree-$variant")
            setWorktree(fx)
            val before = fx.snapshot()
            val policy = fx.diffPolicy()
            val covered = fx.coverage()
            assertNull(policy.couldNotRun, policy.toString())
            assertFalse(policy.passed)
            assertContains(policy.output, "added clone-only.txt (protected by clone-only.txt)")
            assertFalse("untracked.txt" in policy.output, policy.output)
            assertFalse("later.txt" in policy.output, policy.output)
            assertTrue(covered.passed, covered.toString())
            assertContains(covered.output, "1 of 1 changed lines covered")
            assertEquals(before, fx.snapshot())
            proof(
                "core.worktree $variant guarded",
                "diff_policy saw the clone's clone-only.txt, not the project's untracked.txt; coverage read $calc of the clone " +
                    "(${covered.output.trim().lines().last()}); project files, index and status unchanged",
            )
        }
    }

    @Test
    fun `every git of both checks in a clone gets the three pins over the caller's values, with lazy fetch off`() {
        val fx = Fixture("pins")
        val real = fx.workspace.toRealPath()
        val seen = mutableListOf<Map<String, String>>()
        val recording = { builder: ProcessBuilder ->
            seen += builder.environment().toMap()
            builder.start()
        }
        val hostile =
            hostEnv +
                mapOf(
                    WorkspaceGit.GIT_DIR to fx.project.resolve(".git").toString(),
                    WorkspaceGit.GIT_COMMON_DIR to fx.project.resolve(".git").toString(),
                    WorkspaceGit.GIT_WORK_TREE to fx.project.toString(),
                    WorkspaceGit.NO_LAZY_FETCH to "0",
                )

        val policy = fx.diffPolicy(env = hostile, startProcess = recording)
        val policyRuns = seen.size
        val covered = fx.coverage(env = hostile, startProcess = recording)

        assertNull(policy.couldNotRun, policy.toString())
        assertNull(covered.couldNotRun, covered.toString())
        assertTrue(policyRuns > 0 && seen.size > policyRuns, "both checks ran git: $policyRuns, ${seen.size}")
        for (env in seen) {
            assertEquals(real.resolve(".git").toString(), env[WorkspaceGit.GIT_DIR])
            assertEquals(real.resolve(".git").toString(), env[WorkspaceGit.GIT_COMMON_DIR])
            assertEquals(real.toString(), env[WorkspaceGit.GIT_WORK_TREE])
            assertEquals("1", env[WorkspaceGit.NO_LAZY_FETCH])
        }
        proof("pins", "${seen.size} git processes of diff_policy and changed_line_coverage, each pinned to $real")
    }

    @Test
    fun `hooks mode gets neither the check nor the pins`() {
        val fx = Fixture("hooks")
        fx.hostileCommondir()
        val seen = mutableListOf<Map<String, String>>()
        val recording = { builder: ProcessBuilder ->
            seen += builder.environment().toMap()
            builder.start()
        }

        val policy = fx.diffPolicy(clones = CloneRoots.NONE, startProcess = recording)
        val covered = fx.coverage(clones = CloneRoots.NONE, startProcess = recording)

        // No layout check: git runs in the workspace as before, whatever its .git holds.
        assertFalse("Loop runs no git" in policy.couldNotRun.orEmpty(), policy.toString())
        assertFalse("Loop runs no git" in covered.couldNotRun.orEmpty(), covered.toString())
        assertTrue(seen.isNotEmpty())
        for (env in seen) {
            assertNull(env[WorkspaceGit.GIT_DIR])
            assertNull(env[WorkspaceGit.GIT_COMMON_DIR])
            assertNull(env[WorkspaceGit.GIT_WORK_TREE])
            assertEquals("1", env[WorkspaceGit.NO_LAZY_FETCH])
        }
        // A workspace that is not one of the clone scope's folders is not pinned either, in clone mode.
        val outside = CloneRoots.of(CloneScope(folder.resolve("elsewhere"), fx.project))
        seen.clear()
        fx.diffPolicy(clones = outside, startProcess = recording)
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertNull(it[WorkspaceGit.GIT_DIR]) }
        assertNotEquals(0, seen.size)
    }
}
