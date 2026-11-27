package loop.core.verify

import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkspaceGitMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * bl-253: the clone workspaces of the workflow in effect, for [WorkspaceGit]: the folder that holds them
 * ([workspaceRoot]) and the project folder ([project]), which is never one of them.
 */
data class CloneScope(
    val workspaceRoot: Path,
    val project: Path,
) {
    /**
     * True when [root] is a clone workspace: a folder whose parent is [workspaceRoot], real paths compared, and not
     * [project]. A subfolder of a workspace, such as a nested repository, is not one.
     */
    fun covers(root: Path): Boolean {
        val dir = root.toAbsolutePath().normalize()
        val parent = dir.parent ?: return false
        if (real(parent) != real(workspaceRoot)) return false
        return real(dir) != real(project)
    }

    companion object {
        /** The scope of [workflow], null when it does not clone (`workspace.git: hooks`). */
        fun of(workflow: WorkflowFile): CloneScope? =
            if (workflow.workspaceGit == WorkspaceGitMode.Clone) {
                CloneScope(workflow.workspaceRoot, workflow.path.toAbsolutePath().parent)
            } else {
                null
            }

        private fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
    }
}

/**
 * bl-253: tells [WorkspaceGit] which roots are clone workspaces now. The mode comes from the workflow, never from what
 * a `.git` looks like, so a clone whose `.git` became a file or went missing is still checked, and refused.
 */
fun interface CloneRoots {
    /** The scope in effect, null in `hooks` mode. */
    fun current(): CloneScope?

    companion object {
        /** `hooks` mode: no root is a clone workspace. */
        val NONE: CloneRoots = CloneRoots { null }

        /** Always [scope]. */
        fun of(scope: CloneScope?): CloneRoots = CloneRoots { scope }
    }
}

/**
 * bl-253: the layout Loop requires of the `.git` of a clone workspace before any git it runs there. Git finds the
 * repository, the ref store and the work tree from files in that folder, which the agent owns, and some of them send
 * Loop's git to the operator's project: a `commondir` entry, a symbolic link at the top level or under `refs/` or
 * `logs/` (git writes through it), a hard linked file at the top level or under `logs/` (git rewrites some of those in
 * place, such as `MERGE_MSG`), and an `extensions.refStorage` naming a path, which moves every ref read and write
 * there whatever `GIT_DIR` says. Links under `objects/` are allowed: they only add objects the clone sees.
 */
object CloneLayout {
    /**
     * Why the `.git` of [workspace] is not a layout Loop runs git in, naming the path or the key, null when it is.
     * [refStorage] reads every `extensions.refStorage` value of a config file, run outside the workspace.
     */
    fun refusal(
        workspace: Path,
        refStorage: (Path) -> List<String>,
    ): String? {
        if (Files.isSymbolicLink(workspace)) return "the workspace $workspace is a symbolic link"
        val dotGit = workspace.resolve(".git")
        val attributes = attributes(dotGit) ?: return "$dotGit is missing, the clone has no repository of its own"
        if (attributes.isSymbolicLink) return "$dotGit is a symbolic link, not the clone's own folder"
        if (!attributes.isDirectory) return "$dotGit is not a folder, the workspace is a worktree or a submodule, not a clone"
        val commondir = dotGit.resolve("commondir")
        if (Files.exists(commondir, LinkOption.NOFOLLOW_LINKS)) {
            return "$commondir is there, which points git at another repository's refs and config"
        }
        val top = Files.list(dotGit).use { it.toList() }.sortedBy { it.fileName.toString() }
        top.firstNotNullOfOrNull { entry -> linked(entry, hardLinks = true) }?.let { return it }
        for (dir in listOf("refs", "logs")) {
            val folder = dotGit.resolve(dir)
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue
            Files
                .walk(folder)
                .use { walk ->
                    walk.sorted().toList().firstNotNullOfOrNull { entry -> linked(entry, hardLinks = dir == "logs") }
                }?.let { return it }
        }
        val config = dotGit.resolve("config")
        if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) return "$config is not a regular file"
            refStorage(config).firstOrNull { "://" in it }?.let { value ->
                return "extensions.refStorage in $config is \"${value.take(VALUE_MAX)}\", which sends the clone's refs to another path"
            }
        }
        return null
    }

    /** Why [entry] is refused: a symbolic link, or, with [hardLinks], a regular file with more than one link. */
    private fun linked(
        entry: Path,
        hardLinks: Boolean,
    ): String? {
        val attributes = attributes(entry) ?: return null
        if (attributes.isSymbolicLink) return "$entry is a symbolic link, which git writes through"
        if (!hardLinks || !attributes.isRegularFile) return null
        val links =
            try {
                Files.getAttribute(entry, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
            } catch (e: UnsupportedOperationException) {
                return "cannot read the link count of $entry: ${e.message}"
            } catch (e: IOException) {
                return "cannot read the link count of $entry: ${e.message}"
            }
        return if (links > 1) "$entry is hard linked ($links links), which git can rewrite in place" else null
    }

    private fun attributes(path: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            null
        }

    private const val VALUE_MAX = 200
}
