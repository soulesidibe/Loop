package loop.core.workspace

import kotlinx.coroutines.runBlocking
import loop.core.verify.WorkspaceGit
import loop.core.workflow.HooksConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * bl-28: with clones, [WorkspaceManager.prepare] makes a new workspace a clone of the project before `after_create`,
 * reuses a clone as it is, refuses a foreign `.git`, and removal deletes the keep ref. Real git, no user config.
 */
class WorkspaceManagerCloneTest {
    private val folder: Path = createTempDirectory("loop-manager-clone").toRealPath()
    private val home: Path = Files.createDirectories(folder.resolve("home"))
    private val project: Path = Files.createDirectories(folder.resolve("project"))
    private val root: Path = folder.resolve("workspaces")
    private val key = WorkspaceKey("t-1")

    private val hostEnv = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to home.toString(), "LANG" to "C")
    private val clones = WorkspaceClones(project, WorkspaceGit(hostEnv))

    /** What `after_create` saw: whether the workspace already had its `.git`. */
    private val sawGit = mutableListOf<Boolean>()

    private val runner =
        HookRunner { hook, _, cwd, _, timeoutMs ->
            sawGit += cwd.resolve(".git").exists()
            HookResult(hook, 0, timedOut = false, timeoutMs = timeoutMs, durationMs = 1, output = "")
        }

    private val manager = WorkspaceManager(Workspaces(root), HooksConfig(afterCreate = "populate"), runner, clones)

    private fun git(
        vararg args: String,
        dir: Path = project,
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
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private val first: String

    init {
        git("init", "-q", "--initial-branch=main")
        project.resolve("README.md").writeText("A project.\n")
        git("add", ".")
        git("commit", "-q", "-m", "first")
        first = git("rev-parse", "HEAD")
    }

    @Test
    fun `a new workspace is a clone before after_create runs, with the base ref written`() {
        val workspace = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart(first, CloneBase("main", first))) }.getOrThrow()

        assertEquals(listOf(true), sawGit)
        assertEquals(first, git("rev-parse", "HEAD", dir = workspace.path))
        assertEquals(first, git("rev-parse", "refs/loop/base/main", dir = workspace.path))
        assertEquals(first, git("rev-parse", "refs/loop/workspace/t-1"))
    }

    @Test
    fun `a reused clone keeps its commits and its branch, never reset, and gets the new base ref`() {
        val workspace = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart(first)) }.getOrThrow()
        workspace.path.resolve("work.txt").writeText("work\n")
        git("add", "work.txt", dir = workspace.path)
        git("commit", "-q", "-m", "work", dir = workspace.path)
        val work = git("rev-parse", "HEAD", dir = workspace.path)
        workspace.path.resolve("draft.txt").writeText("uncommitted\n")
        project.resolve("base.txt").writeText("base\n")
        git("add", "base.txt")
        git("commit", "-q", "-m", "base")
        val base = git("rev-parse", "HEAD")

        val reused = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart(base, CloneBase("main", base))) }.getOrThrow()

        assertFalse(reused.createdNow)
        assertEquals(work, git("rev-parse", "HEAD", dir = reused.path))
        assertEquals("loop/t-1", git("symbolic-ref", "--short", "HEAD", dir = reused.path))
        assertTrue(reused.path.resolve("draft.txt").exists(), "nothing is cleaned")
        assertEquals(base, git("rev-parse", "refs/loop/base/main", dir = reused.path))
        assertEquals(listOf(true), sawGit, "after_create runs once")
    }

    @Test
    fun `a reused folder with a foreign git fails with a named reason and is kept`() {
        val folder = Files.createDirectories(root.resolve("t-1"))
        git("init", "-q", dir = folder)

        val error = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart(first)) }.exceptionOrNull()

        val clone = assertIs<WorkspaceError.Clone>(error)
        assertContains(clone.message!!, "is not a clone of the project")
        assertTrue(folder.resolve(".git").exists(), "a folder Loop did not create now is never deleted")
        assertEquals(emptyList(), sawGit)
    }

    @Test
    fun `a failed clone removes the new folder and runs no hook`() {
        val error = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart("no-such-base")) }.exceptionOrNull()

        assertIs<WorkspaceError.Clone>(error)
        assertFalse(root.resolve("t-1").exists())
        assertEquals("", git("for-each-ref", "refs/loop/workspace/"))
        assertEquals(emptyList(), sawGit)
    }

    @Test
    fun `removal deletes the folder and the keep ref, and the imported branch stays`() {
        val workspace = runBlocking { manager.prepare(key, emptyMap(), clone = CloneStart(first)) }.getOrThrow()
        workspace.path.resolve("work.txt").writeText("work\n")
        git("add", "work.txt", dir = workspace.path)
        git("commit", "-q", "-m", "work", dir = workspace.path)
        val work = git("rev-parse", "HEAD", dir = workspace.path)
        clones.import(workspace.path, work, "loop/t-1")

        val removed = runBlocking { manager.remove(key, emptyMap()) }.getOrThrow()

        assertTrue(removed)
        assertFalse(workspace.path.exists())
        assertEquals("", git("for-each-ref", "refs/loop/workspace/"))
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
    }

    @Test
    fun `without clones nothing changes, a populate empty manager never clones`() {
        val plain = manager.withoutWorkspaceHooks()

        val workspace = runBlocking { plain.prepare(key, emptyMap(), clone = CloneStart(first)) }.getOrThrow()

        assertFalse(workspace.path.resolve(".git").exists())
    }
}
