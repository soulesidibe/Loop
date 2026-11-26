package loop.core.workspace

import loop.core.verify.WorkspaceGit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * bl-28: a clone workspace is a `git clone --shared` of the project with its own refs, config, hooks and info. Every
 * test runs real git in a temporary folder, with no user or system config.
 */
class WorkspaceClonesTest {
    private val folder: Path = createTempDirectory("loop-clones").toRealPath()
    private val home: Path = Files.createDirectories(folder.resolve("home"))
    private val project: Path = Files.createDirectories(folder.resolve("project"))
    private val root: Path = Files.createDirectories(folder.resolve("workspaces"))
    private val key = WorkspaceKey("t-1")
    private val workspace: Path = root.resolve("t-1")

    private val hostEnv =
        mapOf(
            "PATH" to System.getenv("PATH").orEmpty(),
            "HOME" to home.toString(),
            "LANG" to "C",
        )

    private val clones = WorkspaceClones(project, WorkspaceGit(hostEnv))

    private fun git(
        vararg args: String,
        dir: Path = project,
        ok: Boolean = true,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["HOME"] = home.toString()
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (ok) check(code == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private fun commit(
        file: String,
        text: String,
        dir: Path = project,
    ): String {
        dir.resolve(file).writeText(text)
        git("add", file, dir = dir)
        git("commit", "-q", "-m", "change $file", dir = dir)
        return git("rev-parse", "HEAD", dir = dir)
    }

    private val first: String

    init {
        git("init", "-q", "--initial-branch=main")
        first = commit("README.md", "A project.\n")
    }

    private fun create(base: String? = null) {
        Files.createDirectories(workspace)
        clones.create(workspace, key, base)
    }

    @Test
    fun `a new clone starts on loop of its key at the base, with no origin, its files checked out and a keep ref`() {
        val second = commit("second.txt", "two\n")

        create(base = first)

        assertEquals("loop/t-1", git("symbolic-ref", "--short", "HEAD", dir = workspace))
        assertEquals(first, git("rev-parse", "HEAD", dir = workspace))
        assertEquals("", git("remote", dir = workspace), "no origin remote")
        assertTrue(workspace.resolve("README.md").exists())
        assertFalse(workspace.resolve("second.txt").exists())
        assertEquals(first, git("rev-parse", "refs/loop/workspace/t-1"))
        assertNotEquals(second, first)
        assertIs<CloneState.Clone>(clones.state(workspace))
    }

    @Test
    fun `a new clone starts at the project's loop branch when it exists, whatever the base`() {
        val branched = commit("work.txt", "work\n")
        git("branch", "loop/t-1", branched)
        git("reset", "-q", "--hard", first)

        create(base = first)

        assertEquals(branched, git("rev-parse", "HEAD", dir = workspace))
        assertEquals("loop/t-1", git("symbolic-ref", "--short", "HEAD", dir = workspace))
    }

    @Test
    fun `without a base a new clone starts at the project's HEAD`() {
        create(base = null)

        assertEquals(first, git("rev-parse", "HEAD", dir = workspace))
    }

    @Test
    fun `a base that is not a commit of the project fails the creation`() {
        val error = assertFailsWith<IOException> { create(base = "no-such-branch") }

        assertContains(error.message!!, "no-such-branch")
    }

    @Test
    fun `the project's user name and email are copied when set, and absent when not`() {
        create()
        assertEquals("", git("config", "--local", "--get", "user.name", dir = workspace, ok = false))

        git("config", "user.name", "Operator Name")
        git("config", "user.email", "operator@example.com")
        val other = WorkspaceKey("t-2")
        val second = Files.createDirectories(root.resolve("t-2"))
        clones.create(second, other, null)

        assertEquals("Operator Name", git("config", "--local", "--get", "user.name", dir = second))
        assertEquals("operator@example.com", git("config", "--local", "--get", "user.email", dir = second))
    }

    @Test
    fun `the state tells an absent git, a clone of the project and a foreign one apart`() {
        Files.createDirectories(workspace)
        assertIs<CloneState.Absent>(clones.state(workspace))

        clones.create(workspace, key, null)
        assertIs<CloneState.Clone>(clones.state(workspace))

        val worktree = root.resolve("wt")
        git("worktree", "add", "-q", "-b", "wt", worktree.toString())
        assertContains(assertIs<CloneState.Foreign>(clones.state(worktree)).reason, "is not a folder")

        val other = Files.createDirectories(root.resolve("other"))
        git("init", "-q", dir = other)
        assertContains(assertIs<CloneState.Foreign>(clones.state(other)).reason, "does not borrow the objects of")
    }

    @Test
    fun `the keep ref keeps the borrowed objects through a gc in the project, and dropping it deletes it`() {
        git("checkout", "-q", "-b", "start")
        val start = commit("start.txt", "start\n")
        git("checkout", "-q", "main")
        create(base = "start")
        assertEquals(start, git("rev-parse", "HEAD", dir = workspace))

        git("branch", "-q", "-D", "start")
        git("reflog", "expire", "--expire=now", "--all")
        git("gc", "-q", "--prune=now")

        assertEquals(start, git("log", "-1", "--format=%H", dir = workspace))
        clones.dropKeep(key)
        assertEquals("", git("for-each-ref", "refs/loop/workspace/"))
        clones.dropKeep(key)
    }

    @Test
    fun `the base ref written in the clone lets the agent check and merge it, and moving it changes nothing in the project`() {
        create(base = first)
        commit("agent.txt", "agent\n", dir = workspace)
        val base = commit("base.txt", "base\n")
        git("update-ref", "refs/loop/base/main", base)

        clones.writeBase(workspace, "main", base)

        assertEquals(base, git("rev-parse", "refs/loop/base/main", dir = workspace))
        git("merge-base", "--is-ancestor", "refs/loop/base/main", "HEAD", dir = workspace, ok = false)
        git("merge", "-q", "--no-edit", "refs/loop/base/main", dir = workspace)
        git("merge-base", "--is-ancestor", "refs/loop/base/main", "HEAD", dir = workspace)
        git("update-ref", "refs/loop/base/main", first, dir = workspace)
        assertEquals(base, git("rev-parse", "refs/loop/base/main"), "the project's base ref is its own")
    }

    @Test
    fun `an import brings a commit of the clone into the project and writes the loop branch there at it`() {
        create(base = first)
        val work = commit("agent.txt", "agent\n", dir = workspace)
        assertNotEquals("commit", git("cat-file", "-t", work, ok = false), "the project has no agent commit before the import")

        clones.import(workspace, work, "loop/t-1")

        assertEquals("commit", git("cat-file", "-t", work))
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
        clones.import(workspace, work, "loop/t-1")
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
    }

    @Test
    fun `an import of a commit the clone does not have fails and writes nothing`() {
        create(base = first)

        assertFailsWith<IOException> { clones.import(workspace, "ab".repeat(20), "loop/t-1") }
        assertFailsWith<IOException> { clones.import(workspace, "not-a-commit", "loop/t-1") }

        assertEquals("", git("for-each-ref", "refs/heads/loop/"))
    }

    @Test
    fun `an fsmonitor or hooks path the agent sets in the clone's config never runs in a git command Loop runs there`() {
        create(base = first)
        val marker = folder.resolve("hook-ran")
        val hooks = Files.createDirectories(folder.resolve("agent-hooks"))
        val script = "#!/bin/sh\necho ran >> '$marker'\n"
        for (name in listOf("reference-transaction", "post-checkout", "pre-commit", "post-merge")) {
            hooks.resolve(name).writeText(script)
            Files.setPosixFilePermissions(hooks.resolve(name), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val monitor = folder.resolve("fsmonitor.sh").also { it.writeText(script) }
        Files.setPosixFilePermissions(monitor, PosixFilePermissions.fromString("rwxr-xr-x"))
        git("config", "core.hooksPath", hooks.toString(), dir = workspace)
        git("config", "core.fsmonitor", monitor.toString(), dir = workspace)
        val work = commit("agent.txt", "agent\n", dir = workspace)
        Files.deleteIfExists(marker)

        clones.writeBase(workspace, "main", first)
        clones.import(workspace, work, "loop/t-1")
        assertIs<CloneState.Clone>(clones.state(workspace))
        WorkspaceGit(hostEnv).run(workspace, "status", "--porcelain")

        assertFalse(marker.exists(), "a hook or the fsmonitor ran: ${if (marker.exists()) marker.readText() else ""}")
        // The same command without Loop's pins runs the hook, so the test proves the pins.
        git("update-ref", "refs/loop/probe", first, dir = workspace)
        assertTrue(marker.exists(), "the hook is live without the pins")
    }
}
