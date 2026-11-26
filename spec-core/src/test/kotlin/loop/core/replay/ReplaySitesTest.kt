package loop.core.replay

import loop.core.verify.WorkspaceFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** m4-41: where `loop replay --verify` runs the checks again, on real git and real files, never in the workspace. */
class ReplaySitesTest {
    private val root: Path = createTempDirectory("loop-replay-sites").toRealPath()
    private val project: Path = root.resolve("project").createDirectories()
    private val workspace: Path = root.resolve("workspaces/t-1")
    private val temp: Path = root.resolve("tmp").createDirectories()
    private val warnings = mutableListOf<String>()
    private val sites = ReplaySites(tempParent = { Files.createTempDirectory(temp, "replay-") }, warn = { warnings += it })

    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output
    }

    /** A project repository on main, and the workspace as its worktree on loop/t-1 with one commit of the agent. */
    private fun gitWorkspace(): String {
        git(project, "init", "-q", "-b", "main")
        project.resolve(".gitignore").writeText("build/\n")
        project.resolve("a.txt").writeText("one\n")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "first")
        workspace.parent.createDirectories()
        git(project, "worktree", "add", "-q", "-b", "loop/t-1", workspace.toString())
        workspace.resolve("hello.txt").writeText("hello\n")
        git(workspace, "add", ".")
        git(workspace, "commit", "-q", "-m", "agent")
        return git(workspace, "rev-parse", "HEAD").trim()
    }

    private fun fingerprint(dir: Path): String = WorkspaceFingerprint.of(dir).getOrThrow()

    /** Every file under [dir] but git's own state, with its content. */
    private fun files(dir: Path): Map<String, String> =
        Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && !dir.relativize(it).toString().startsWith(".git") }
                .toList()
                .associate { dir.relativize(it).toString() to it.readText() }
        }

    private fun worktrees(): String = git(project, "worktree", "list", "--porcelain")

    @Test
    fun `a kept git workspace at the recorded state is copied with its own index, and the copy goes away on close`() {
        gitWorkspace()
        workspace.resolve("a.txt").writeText("changed\n")
        workspace.resolve("new.txt").writeText("untracked\n")
        workspace
            .resolve("build")
            .createDirectories()
            .resolve("out.txt")
            .writeText("ignored\n")
        val recorded = fingerprint(workspace)
        val before = files(workspace)
        val listed = worktrees()

        val placed = sites.place(recorded, Result.success(workspace), project)

        val site = assertIs<ReplaySite.WorkspaceCopy>(assertIs<Placement.Ready>(placed).site)
        site.use {
            assertEquals(workspace, site.workspace)
            assertFalse(site.dir.startsWith(workspace), "never the workspace itself")
            assertEquals(recorded, fingerprint(site.dir))
            assertEquals(before, files(site.dir), "every file, the ignored ones too")
            // What a check does in the copy stays in the copy, its index included.
            site.dir.resolve("hello.txt").writeText("rewritten\n")
            git(site.dir, "add", ".")
        }

        assertFalse(site.dir.exists(), "the copy is removed")
        assertEquals(listed, worktrees(), "its worktree is removed")
        assertEquals(before, files(workspace))
        assertEquals(recorded, fingerprint(workspace), "the workspace is left as it was")
    }

    @Test
    fun `a gone workspace with a clean git fingerprint is rebuilt as a worktree at its commit, removed on close`() {
        val head = gitWorkspace()
        val recorded = fingerprint(workspace)
        git(project, "worktree", "remove", "--force", workspace.toString())
        val listed = worktrees()

        val placed = sites.place(recorded, Result.success(null), project)

        val site = assertIs<ReplaySite.Worktree>(assertIs<Placement.Ready>(placed).site)
        site.use {
            assertEquals(head, site.head)
            assertEquals(project, site.repository)
            assertEquals(recorded, fingerprint(site.dir))
            assertEquals("hello\n", site.dir.resolve("hello.txt").readText())
        }

        assertFalse(site.dir.exists(), "the worktree folder is removed")
        assertEquals(listed, worktrees(), "the worktree is removed from the project repository")
        assertEquals("the workspace is gone", site.state)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a changed workspace whose clean git fingerprint is still in the repository is rebuilt, not copied`() {
        val head = gitWorkspace()
        val recorded = fingerprint(workspace)
        workspace.resolve("hello.txt").writeText("changed after the verdict\n")

        val site = assertIs<ReplaySite.Worktree>(assertIs<Placement.Ready>(sites.place(recorded, Result.success(workspace), project)).site)
        site.use {
            assertEquals(head, site.head)
            assertEquals("hello\n", site.dir.resolve("hello.txt").readText())
        }
        assertEquals("changed after the verdict\n", workspace.resolve("hello.txt").readText())
        assertEquals("the workspace changed since the verdict", site.state)
    }

    @Test
    fun `a workspace with an absolute link into itself is never copied, so a clean fingerprint is rebuilt instead`() {
        val head = gitWorkspace()
        val recorded = fingerprint(workspace)
        // Untracked would change the fingerprint, so the link is committed.
        Files.createSymbolicLink(workspace.resolve("inside"), workspace.resolve("hello.txt"))
        Files.createSymbolicLink(workspace.resolve("relative"), Path.of("hello.txt"))
        git(workspace, "add", ".")
        git(workspace, "commit", "-q", "-m", "links")
        val linked = fingerprint(workspace)
        val newHead = git(workspace, "rev-parse", "HEAD").trim()

        val site = assertIs<ReplaySite.Worktree>(assertIs<Placement.Ready>(sites.place(linked, Result.success(workspace), project)).site)
        site.use {
            assertEquals(newHead, site.head)
            assertContains(site.state, "cannot copy the workspace")
            assertContains(site.state, "links to an absolute path inside the workspace: inside")
            assertFalse("relative" in site.state, site.state)
        }
        assertTrue(head != newHead && recorded != linked)
    }

    @Test
    fun `a plain folder with an absolute link into itself is refused, never copied`() {
        workspace.createDirectories().resolve("report.md").writeText("# Report\n")
        Files.createSymbolicLink(workspace.resolve("inside"), workspace.resolve("report.md"))
        val recorded = WorkspaceFingerprint.tree(workspace).getOrThrow()

        val refused = assertIs<Placement.Refused>(sites.place(recorded, Result.success(workspace), project))

        assertContains(refused.reason, "links to an absolute path inside the workspace: inside")
        assertEquals(recorded, refused.current)
        assertTrue(Files.list(temp).use { it.count() } == 0L, "nothing is left behind")
    }

    @Test
    fun `what cannot be removed on close is reported with git worktree prune as the remedy`() {
        gitWorkspace()
        val recorded = fingerprint(workspace)
        val site = assertIs<ReplaySite.WorkspaceCopy>(assertIs<Placement.Ready>(sites.place(recorded, Result.success(workspace), project)).site)
        val locked = site.dir.resolve("locked").createDirectories()
        locked.resolve("f.txt").writeText("kept\n")
        locked.toFile().setWritable(false)
        try {
            site.close()

            assertTrue(warnings.any { "git worktree prune" in it && site.dir.toString() in it }, warnings.toString())
            assertTrue(warnings.any { "cannot remove the temporary folder ${site.dir.parent}" in it }, warnings.toString())
        } finally {
            locked.toFile().setWritable(true)
        }
    }

    @Test
    fun `a changed workspace whose fingerprint holds uncommitted changes cannot be rebuilt and names both fingerprints`() {
        gitWorkspace()
        workspace.resolve("new.txt").writeText("untracked\n")
        val recorded = fingerprint(workspace)
        workspace.resolve("new.txt").writeText("changed\n")
        val current = fingerprint(workspace)

        val refused = assertIs<Placement.Refused>(sites.place(recorded, Result.success(workspace), project))

        assertEquals(recorded, refused.recorded)
        assertEquals(current, refused.current)
        assertContains(refused.reason, "changed since the verdict")
        assertContains(refused.reason, "uncommitted")
        assertTrue(Files.list(temp).use { it.count() } == 0L, "nothing is left behind")
    }

    @Test
    fun `a gone workspace whose fingerprint holds uncommitted changes cannot be rebuilt`() {
        gitWorkspace()
        workspace.resolve("new.txt").writeText("untracked\n")
        val recorded = fingerprint(workspace)
        git(project, "worktree", "remove", "--force", workspace.toString())

        val refused = assertIs<Placement.Refused>(sites.place(recorded, Result.success(null), project))

        assertEquals(ReplaySites.GONE, refused.current)
        assertContains(refused.reason, "the workspace is gone")
    }

    @Test
    fun `a clean git fingerprint whose commit is not in the project repository cannot be rebuilt`() {
        gitWorkspace()
        val missing = "0123456789abcdef0123456789abcdef01234567"
        val recorded = "git:$missing:${WorkspaceFingerprint.EMPTY_HASH}:${WorkspaceFingerprint.EMPTY_HASH}"

        val refused = assertIs<Placement.Refused>(sites.place(recorded, Result.success(null), project))

        assertContains(refused.reason, "the commit $missing is not in the project repository")
    }

    @Test
    fun `a kept plain folder at its tree fingerprint is copied, without its top git entry`() {
        workspace.createDirectories().resolve("report.md").writeText("# Report\n")
        workspace.resolve(".git").writeText("gitdir: /somewhere\n")
        val recorded = WorkspaceFingerprint.tree(workspace).getOrThrow()

        val site =
            assertIs<ReplaySite.WorkspaceCopy>(assertIs<Placement.Ready>(sites.place(recorded, Result.success(workspace), project)).site)
        site.use {
            assertEquals(recorded, WorkspaceFingerprint.tree(site.dir).getOrThrow())
            assertFalse(site.dir.resolve(".git").exists())
        }
        assertFalse(site.dir.exists())
        assertTrue(workspace.resolve(".git").exists())
    }

    @Test
    fun `a tree fingerprint whose folder is gone or changed cannot be rebuilt`() {
        workspace.createDirectories().resolve("report.md").writeText("# Report\n")
        val recorded = WorkspaceFingerprint.tree(workspace).getOrThrow()

        val gone = assertIs<Placement.Refused>(sites.place(recorded, Result.success(null), project))
        assertEquals(recorded, gone.recorded)
        assertEquals(ReplaySites.GONE, gone.current)
        assertContains(gone.reason, "the workspace is gone")

        workspace.resolve("report.md").writeText("# Another report\n")
        val changed = assertIs<Placement.Refused>(sites.place(recorded, Result.success(workspace), project))
        assertEquals(WorkspaceFingerprint.tree(workspace).getOrThrow(), changed.current)
        assertContains(changed.reason, "changed since the verdict")
    }

    @Test
    fun `an unknown recorded fingerprint cannot be replayed`() {
        val refused = sites.place("unknown", Result.success(null), project)
        if (refused !is Placement.Refused) fail("expected a refusal, got $refused")
        assertContains(refused.reason, "no fingerprint")
    }
}
