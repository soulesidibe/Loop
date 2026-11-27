package loop.runner.acp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * bl-254: a copy of spec-core's `loop.core.verify.CloneLayout` (bl-253), since this plugin does not depend on spec-core,
 * as [ProtectedRootsCheck.minimalGitEnv] copies `WorkspaceGit.minimal`. Keep the two in step. The layout Loop requires
 * of the `.git` of a clone workspace before any git it runs there: git finds the repository, the ref store and the
 * work tree from files in that folder, which the agent owns, and some of them send Loop's git to the operator's
 * project: a `commondir` entry, a symbolic link at the top level or under `refs/` or `logs/`, a hard linked file at the
 * top level or under `logs/`, and an `extensions.refStorage` naming a path. Links under `objects/` are allowed.
 */
internal object CloneLayoutCopy {
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
