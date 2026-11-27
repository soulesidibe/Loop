package loop.core.workspace

import loop.core.verify.LazyFetchTrap
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
        input: Path? = null,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply { input?.let { redirectInput(it.toFile()) } }
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

    private fun packFiles(packs: Path): Set<String> =
        if (Files.isDirectory(packs)) Files.list(packs).use { list -> list.map { it.fileName.toString() }.toList().toSet() } else emptySet()

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
    fun `a base written in the clone is kept in the project too, so a rewritten base and a gc leave the clone working`() {
        create(base = first)
        git("checkout", "-q", "-b", "rewritten")
        val base = commit("base.txt", "base\n")
        git("checkout", "-q", "main")
        git("update-ref", "refs/loop/base/main", base)
        clones.writeBase(workspace, key, "main", base)
        git("merge", "-q", "--no-edit", "refs/loop/base/main", dir = workspace)
        assertEquals(base, git("rev-parse", "refs/loop/workspace-base/t-1"))

        git("update-ref", "refs/loop/base/main", first)
        git("branch", "-q", "-D", "rewritten")
        git("reflog", "expire", "--expire=now", "--all")
        git("gc", "-q", "--prune=now")

        git("log", "-1", dir = workspace)
        git("fsck", "--connectivity-only", dir = workspace)
        clones.dropKeep(key)
        assertEquals("", git("for-each-ref", "refs/loop/workspace/", "refs/loop/workspace-base/"))
    }

    @Test
    fun `a new clone is built aside and moved into place, a leftover of a killed build is cleared first`() {
        Files.createDirectories(workspace)
        val leftover = Files.createDirectories(root.resolve(".loop-clone-t-1"))
        leftover.resolve("junk").writeText("half made\n")

        clones.create(workspace, key, first)

        assertFalse(leftover.exists(), "the leftover of a killed build is gone")
        assertIs<CloneState.Clone>(clones.state(workspace))
        assertEquals(listOf("t-1"), Files.list(root).use { list -> list.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a folder with files but no git is not cloned into, and nothing is written in the project`() {
        Files.createDirectories(workspace).resolve("notes.txt").writeText("operator notes\n")

        val error = assertFailsWith<IOException> { clones.create(workspace, key, first) }

        assertContains(error.message.orEmpty(), "not empty")
        assertEquals("operator notes\n", workspace.resolve("notes.txt").readText())
        assertEquals("", git("for-each-ref", "refs/loop/"))
    }

    @Test
    fun `reuse removes a remote, so a push by name cannot reach the project`() {
        create(base = first)
        git("remote", "add", "origin", project.toString(), dir = workspace)

        clones.reuse(workspace, key)

        assertEquals("", git("remote", dir = workspace))
    }

    @Test
    fun `reuse of a clone with no loop branch fails with a named reason`() {
        // What a kill between the clone and the checkout left before the build moved aside.
        git("clone", "-q", "--shared", "--no-checkout", project.toString(), workspace.toString(), dir = root)

        val error = assertFailsWith<IOException> { clones.reuse(workspace, key) }

        assertContains(error.message.orEmpty(), "has no branch loop/t-1")
    }

    @Test
    fun `reuse writes back a missing keep ref at the first commit of the branch the project has`() {
        create(base = first)
        commit("agent.txt", "agent\n", dir = workspace)
        clones.dropKeep(key)

        clones.reuse(workspace, key)

        assertEquals(first, git("rev-parse", "refs/loop/workspace/t-1"))
    }

    @Test
    fun `the base ref written in the clone lets the agent check and merge it, and moving it changes nothing in the project`() {
        create(base = first)
        commit("agent.txt", "agent\n", dir = workspace)
        val base = commit("base.txt", "base\n")
        git("update-ref", "refs/loop/base/main", base)

        clones.writeBase(workspace, key, "main", base)

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
    fun `an import on a project with history lands a pack of the new objects only`() {
        // Send back of PR #98: without haves the clone packed every object it reaches, borrowed history included.
        repeat(HISTORY) { commit("file-$it.txt", "line $it\n".repeat(200)) }
        create(base = "main")
        val work = commit("agent.txt", "agent\n", dir = workspace)
        val packs = project.resolve(".git/objects/pack")
        val before = packFiles(packs)

        clones.import(workspace, work, "loop/t-1")

        val landed = packFiles(packs) - before
        val indexes = landed.filter { it.endsWith(".idx") }
        assertEquals(1, indexes.size, "one pack landed: $landed")
        val objects = git("show-index", input = packs.resolve(indexes.single())).lines().filter { it.isNotBlank() }
        // The commit, its tree and its blob, plus at most the delta base git appends to complete a thin pack.
        assertTrue(objects.size <= NEW_OBJECTS_MAX, "the new objects only, not the ${HISTORY * 3} of the history: $objects")
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
    }

    @Test
    fun `an import seeds the base refs of the project as haves, so a merged base is not copied again`() {
        create(base = first)
        repeat(HISTORY) { commit("base-$it.txt", "base $it\n".repeat(200)) }
        val base = git("rev-parse", "HEAD")
        git("update-ref", "refs/loop/base/main", base)
        clones.writeBase(workspace, key, "main", base)
        git("merge", "-q", "--no-edit", "refs/loop/base/main", dir = workspace)
        val work = commit("agent.txt", "agent\n", dir = workspace)
        val packs = project.resolve(".git/objects/pack")
        val before = packFiles(packs)

        clones.import(workspace, work, "loop/t-1")

        val indexes = (packFiles(packs) - before).filter { it.endsWith(".idx") }
        val objects = indexes.flatMap { git("show-index", input = packs.resolve(it)).lines().filter { line -> line.isNotBlank() } }
        assertTrue(objects.size <= NEW_OBJECTS_MAX, "the agent's objects only, not the ${HISTORY * 3} of the merged base: $objects")
    }

    @Test
    fun `an import of a commit the clone does not have fails and writes nothing`() {
        create(base = first)

        assertFailsWith<IOException> { clones.import(workspace, "ab".repeat(20), "loop/t-1") }
        assertFailsWith<IOException> { clones.import(workspace, "not-a-commit", "loop/t-1") }

        assertEquals("", git("for-each-ref", "refs/heads/loop/"))
    }

    @Test
    fun `an import checks every object it fetches, so a malformed commit of the clone never reaches the project`() {
        create(base = first)
        val tree = git("rev-parse", "HEAD^{tree}", dir = workspace)
        val body = folder.resolve("bad-commit.txt").also { it.writeText("tree $tree\nparent $first\n\nno author or committer\n") }
        val bad = git("hash-object", "--literally", "-t", "commit", "-w", body.toString(), dir = workspace)

        val error = assertFailsWith<IOException> { clones.import(workspace, bad, "loop/t-1") }

        assertContains(error.message.orEmpty(), "fetch $bad from the clone")
        assertNotEquals("commit", git("cat-file", "-t", bad, ok = false), "the malformed commit is not in the project")
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

        clones.writeBase(workspace, key, "main", first)
        clones.import(workspace, work, "loop/t-1")
        assertIs<CloneState.Clone>(clones.state(workspace))
        WorkspaceGit(hostEnv).run(workspace, "status", "--porcelain")

        assertFalse(marker.exists(), "a hook or the fsmonitor ran: ${if (marker.exists()) marker.readText() else ""}")
        // The same command without Loop's pins runs the hook, so the test proves the pins.
        git("update-ref", "refs/loop/probe", first, dir = workspace)
        assertTrue(marker.exists(), "the hook is live without the pins")
    }

    /** A git that reports [version] for `git version` and runs the real git for everything else. */
    private fun reporting(version: String): WorkspaceGit {
        val real =
            ProcessBuilder("sh", "-c", "command -v git")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        val fake = folder.resolve("git-${version.replace(Regex("[^0-9A-Za-z]"), "_")}")
        fake.writeText(
            "#!/bin/sh\nfor last; do :; done\nif [ \"\$last\" = version ]; then echo '$version'; exit 0; fi\nexec '$real' \"\$@\"\n",
        )
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"))
        return WorkspaceGit(hostEnv, git = fake.toString())
    }

    @Test
    fun `bl-246 a git older than the minimum fails the creation and the reuse of a clone, naming the version found and the minimum`() {
        val old = WorkspaceClones(project, reporting("git version 2.44.0"))
        Files.createDirectories(workspace)

        val created = assertFailsWith<IOException> { old.create(workspace, key, first) }

        assertContains(created.message.orEmpty(), "2.44.0")
        assertContains(created.message.orEmpty(), "2.45.0")
        assertIs<CloneState.Absent>(clones.state(workspace))
        assertEquals("", git("for-each-ref", "refs/loop/"), "nothing is written in the project")
        create(base = first)
        val reused = assertFailsWith<IOException> { old.reuse(workspace, key) }
        assertContains(reused.message.orEmpty(), "found git 2.44.0")
    }

    @Test
    fun `bl-246 a newer git with a vendor suffix passes, and worktree mode never checks the version`() {
        Files.createDirectories(workspace)

        WorkspaceClones(project, reporting("git version 2.54.0 (Apple Git-157)")).create(workspace, key, first)

        assertEquals("loop/t-1", git("symbolic-ref", "--short", "HEAD", dir = workspace))
        // The fingerprint and the merge check of a worktree workspace run on an older git as before.
        assertEquals(first, reporting("git version 2.30.0").head(project))
    }

    @Test
    fun `bl-246 a promisor the agent configures in its clone never runs from the reuse, which fails with a named reason`() {
        create(base = first)
        val trap = LazyFetchTrap(workspace, folder.resolve("lazy-fetch-ran"), "refs/heads/loop/t-1").arm()
        trap.proveLive()
        // The same reuse without the variable runs the agent's program.
        runCatching { WorkspaceClones(project, LazyFetchTrap.unguarded(hostEnv)).reuse(workspace, key) }
        assertTrue(trap.marker.exists(), "the reuse does not fetch unguarded, so the test proves nothing")
        Files.delete(trap.marker)

        val error = assertFailsWith<IOException> { clones.reuse(workspace, key) }

        assertFalse(trap.marker.exists(), "the reuse ran the agent's program")
        assertContains(error.message.orEmpty(), "has no branch loop/t-1")
    }

    @Test
    fun `bl-246 a promisor the agent configures in its clone never runs from the import`() {
        create(base = first)
        val work = commit("agent.txt", "agent\n", dir = workspace)
        val trap = LazyFetchTrap(workspace, folder.resolve("lazy-fetch-ran"), "refs/heads/other").arm()
        trap.proveLive()

        clones.import(workspace, work, "loop/t-1")

        // The fetch's upload-pack turns lazy fetch off for itself (git-upload-pack(1)) and the project's own git does
        // not read the clone's config, so no command of the import fetches even unguarded; the environment test covers it.
        assertFalse(trap.marker.exists(), "the import ran the agent's program")
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
    }

    @Test
    fun `bl-246 a clone is made while the project's main checkout is on loop of the key, with a warning naming it`() {
        val branched = commit("work.txt", "work\n")
        git("checkout", "-q", "-b", "loop/t-1")
        git("branch", "-f", "main", first)

        create(base = first)

        assertEquals("refs/heads/loop/t-1", git("symbolic-ref", "HEAD", dir = workspace))
        assertEquals(branched, git("rev-parse", "HEAD", dir = workspace))
        assertEquals("work\n", workspace.resolve("work.txt").readText())
        assertEquals("", git("status", "--porcelain", dir = workspace))
        val warning = clones.checkedOutWarning(key).orEmpty()
        assertContains(warning, "loop/t-1")
        assertContains(warning, project.toString())
    }

    @Test
    fun `bl-246 a clone is made while a linked worktree of the project is on loop of the key, with a warning naming it`() {
        val linked = folder.resolve("linked")
        git("worktree", "add", "-q", "-b", "loop/t-1", linked.toString())
        val branched = commit("work.txt", "work\n", dir = linked)

        create(base = first)

        assertEquals("refs/heads/loop/t-1", git("symbolic-ref", "HEAD", dir = workspace))
        assertEquals(branched, git("rev-parse", "HEAD", dir = workspace))
        assertTrue(workspace.resolve("work.txt").exists())
        val warning = clones.checkedOutWarning(key).orEmpty()
        assertContains(warning, "loop/t-1")
        assertContains(warning, linked.toString())
        git("worktree", "remove", "--force", linked.toString())
        assertEquals(null, clones.checkedOutWarning(key), "no warning once no worktree has the branch")
    }

    @Test
    fun `bl-246 an import never moves loop of the key while the project has it checked out, and works once it switched away`() {
        git("checkout", "-q", "-b", "loop/t-1")
        create(base = first)
        val work = commit("agent.txt", "agent\n", dir = workspace)

        val error = assertFailsWith<IOException> { clones.import(workspace, work, "loop/t-1") }

        assertContains(error.message.orEmpty(), "loop/t-1")
        assertContains(error.message.orEmpty(), project.toString())
        assertEquals(first, git("rev-parse", "refs/heads/loop/t-1"))
        assertEquals("", git("status", "--porcelain"))
        git("checkout", "-q", "main")
        clones.import(workspace, work, "loop/t-1")
        assertEquals(work, git("rev-parse", "refs/heads/loop/t-1"))
    }

    private companion object {
        const val HISTORY = 30

        /** A commit, its tree, its blob and one delta base `index-pack --fix-thin` may append. */
        const val NEW_OBJECTS_MAX = 4
    }
}
