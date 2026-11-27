package loop.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * bl-254: the clone layout check, run over the same layouts by spec-core's `CloneLayout` (bl-253) and by the ACP
 * runner's copy of it, which cannot depend on spec-core. Each layout expects one exact reason, so the two give the
 * same refusals, and a change to one without the other fails here. Real git, no user or system config.
 */
public abstract class CloneLayoutContract {
    @TempDir
    public lateinit var folder: Path

    /** The layout check under test: why the `.git` of [workspace] is refused, null when it passes. */
    protected abstract fun refusal(
        workspace: Path,
        refStorage: (Path) -> List<String>,
    ): String?

    /** The variables that pin git to the clone [realWorkspace] once its layout passed. */
    protected abstract fun pins(realWorkspace: Path): Map<String, String>

    @Test
    public fun `the pins put GIT_DIR and GIT_COMMON_DIR at the clone's git and GIT_WORK_TREE at the clone`() {
        clone()
        val real = workspace.toRealPath()
        val dotGit = real.resolve(".git").toString()
        assertEquals(mapOf("GIT_DIR" to dotGit, "GIT_COMMON_DIR" to dotGit, "GIT_WORK_TREE" to real.toString()), pins(real))
    }

    private val workspace: Path by lazy { Files.createDirectories(folder.toRealPath().resolve("workspaces/t-1")) }
    private val dotGit: Path by lazy { workspace.resolve(".git") }
    private val outside: Path by lazy { Files.createDirectories(folder.toRealPath().resolve("project")) }

    /** A clone: `git init` with no template, so its `.git` is git's own. */
    private fun clone(): Path {
        git(workspace, "init", "-q", "--template=", "--initial-branch=main")
        return dotGit
    }

    private fun check(): String? = refusal(workspace, ::refStorage)

    @Test
    public fun `a clone of its own passes`() {
        clone()
        assertNull(check())
    }

    @Test
    public fun `a workspace that is a symbolic link`() {
        val real = Files.createDirectories(folder.toRealPath().resolve("elsewhere"))
        git(real, "init", "-q", "--template=")
        Files.createDirectories(workspace.parent)
        Files.delete(workspace)
        Files.createSymbolicLink(workspace, real)
        assertEquals("the workspace $workspace is a symbolic link", check())
    }

    @Test
    public fun `a missing git`() {
        assertEquals("$dotGit is missing, the clone has no repository of its own", check())
    }

    @Test
    public fun `a git that is a symbolic link`() {
        git(outside, "init", "-q", "--template=")
        Files.createSymbolicLink(dotGit, outside.resolve(".git"))
        assertEquals("$dotGit is a symbolic link, not the clone's own folder", check())
    }

    @Test
    public fun `a git file naming another repository`() {
        Files.writeString(dotGit, "gitdir: ${outside.resolve(".git")}\n")
        assertEquals("$dotGit is not a folder, the workspace is a worktree or a submodule, not a clone", check())
    }

    @Test
    public fun `a commondir entry`() {
        clone()
        val commondir = dotGit.resolve("commondir")
        Files.writeString(commondir, "${outside.resolve(".git")}\n")
        assertEquals("$commondir is there, which points git at another repository's refs and config", check())
    }

    @Test
    public fun `a symbolic link at the top level`() {
        clone()
        val link = dotGit.resolve("packed-refs")
        Files.createSymbolicLink(link, outside.resolve("packed-refs"))
        assertEquals("$link is a symbolic link, which git writes through", check())
    }

    @Test
    public fun `a hard link at the top level`() {
        clone()
        val target = Files.writeString(outside.resolve("MERGE_MSG"), "the operator's file\n")
        val link = dotGit.resolve("MERGE_MSG")
        Files.createLink(link, target)
        assertEquals("$link is hard linked (2 links), which git can rewrite in place", check())
    }

    @Test
    public fun `a symbolic link under refs`() {
        clone()
        val link = Files.createDirectories(dotGit.resolve("refs/heads")).resolve("evil")
        Files.createSymbolicLink(link, outside.resolve("main"))
        assertEquals("$link is a symbolic link, which git writes through", check())
    }

    @Test
    public fun `a hard link under refs is allowed, git replaces a ref file and never rewrites it in place`() {
        clone()
        val target = Files.writeString(outside.resolve("ref"), "0".repeat(40) + "\n")
        Files.createLink(Files.createDirectories(dotGit.resolve("refs/heads")).resolve("side"), target)
        assertNull(check())
    }

    @Test
    public fun `a hard link under logs`() {
        clone()
        val target = Files.writeString(outside.resolve("log"), "a reflog\n")
        val link = Files.createDirectories(dotGit.resolve("logs")).resolve("HEAD")
        Files.createLink(link, target)
        assertEquals("$link is hard linked (2 links), which git can rewrite in place", check())
    }

    @Test
    public fun `a symbolic link under objects is allowed`() {
        clone()
        val pack = Files.createDirectories(outside.resolve("pack"))
        Files.createSymbolicLink(Files.createDirectories(dotGit.resolve("objects")).resolve("extra"), pack)
        assertNull(check())
    }

    @Test
    public fun `an extensions refStorage naming a path`() {
        clone()
        val config = dotGit.resolve("config")
        git(folder, "config", "--file", config.toString(), "core.repositoryformatversion", "1")
        git(folder, "config", "--file", config.toString(), "extensions.refStorage", "files://${outside.resolve(".git")}")
        assertEquals(
            "extensions.refStorage in $config is \"files://${outside.resolve(".git")}\", which sends the clone's refs to another path",
            check(),
        )
    }

    @Test
    public fun `an extensions refStorage naming a format is allowed`() {
        clone()
        val config = dotGit.resolve("config")
        git(folder, "config", "--file", config.toString(), "extensions.refStorage", "files")
        assertNull(check())
    }

    @Test
    public fun `a config that is not a regular file`() {
        clone()
        val config = dotGit.resolve("config")
        Files.delete(config)
        Files.createDirectories(config)
        assertEquals("$config is not a regular file", check())
    }

    /** Every `extensions.refStorage` value of [config], read from [folder] with `--no-includes`. */
    private fun refStorage(config: Path): List<String> {
        val (code, out) = run(folder, "config", "--file", config.toString(), "--no-includes", "--get-all", "extensions.refstorage")
        return if (code == 0) out.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    private fun git(
        dir: Path,
        vararg args: String,
    ) {
        val (code, out) = run(dir, *args)
        check(code == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun run(
        dir: Path,
        vararg args: String,
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }
}
