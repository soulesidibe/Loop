package loop.core.replay

import loop.core.verify.Fingerprinter
import loop.core.verify.WorkspaceFingerprint
import loop.core.verify.WorkspaceGit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * Where `loop replay --verify` runs the checks again (m4-41): a folder at the recorded fingerprint that is never the
 * workspace itself. [close] removes it, its git worktree included.
 */
sealed interface ReplaySite : AutoCloseable {
    /** The folder the checks run in. */
    val dir: Path

    /** A copy of the kept [workspace], at the recorded fingerprint. */
    class WorkspaceCopy(
        val workspace: Path,
        override val dir: Path,
        private val cleanup: () -> Unit,
    ) : ReplaySite {
        override fun close() = cleanup()
    }

    /** A temporary git worktree of [repository] at the recorded commit [head], the workspace being gone or changed. */
    class Worktree(
        val repository: Path,
        val head: String,
        override val dir: Path,
        private val cleanup: () -> Unit,
    ) : ReplaySite {
        override fun close() = cleanup()
    }
}

/** What [ReplaySites.place] found: a [Ready] site, or why the recorded state cannot be found or rebuilt. */
sealed interface Placement {
    data class Ready(
        val site: ReplaySite,
    ) : Placement

    /** The [recorded] state is not there: the workspace is at [current], or [ReplaySites.GONE]. */
    data class Refused(
        val reason: String,
        val recorded: String,
        val current: String,
    ) : Placement
}

/**
 * Finds or rebuilds the state a verdict recorded (m4-41), in this order:
 * - the kept workspace while its fingerprint still equals the recorded one, copied into a temporary folder. A `git:`
 *   workspace is copied onto a detached worktree of its own repository at the recorded commit, so the copy has its own
 *   index and HEAD and nothing a check does reaches the workspace's. A `tree:` workspace is copied as a folder. The top
 *   `.git` entry is never copied, the fingerprint leaves it out.
 * - else, for a `git:` fingerprint with no uncommitted part, a detached worktree of the project repository at its commit.
 * - else nothing: the workspace is gone or changed and the recorded state cannot be rebuilt.
 *
 * A site is ready only when its own fingerprint equals the recorded one, so a verdict is never given on another state.
 */
class ReplaySites(
    private val fingerprinter: Fingerprinter = WorkspaceFingerprint,
    private val git: WorkspaceGit = WorkspaceGit(),
    private val tempParent: () -> Path = { Files.createTempDirectory("loop-replay-") },
) {
    /**
     * The site of the [recorded] fingerprint: from the [workspace] where the attempt ran, null when it is gone, or from
     * the git repository of [projectDir], the folder of `WORKFLOW.md`.
     */
    fun place(
        recorded: String,
        workspace: Result<Path?>,
        projectDir: Path,
    ): Placement {
        val tree = recorded.startsWith(TREE)
        val located =
            workspace.getOrElse { error ->
                return Placement.Refused("cannot locate the workspace: ${describe(error)}", recorded, UNKNOWN)
            }
        val kept = located?.takeIf { Files.isDirectory(it) }
        val current =
            when {
                kept == null -> GONE
                tree -> fingerprinter.tree(kept).getOrElse { UNKNOWN }
                else -> fingerprinter.of(kept).getOrElse { UNKNOWN }
            }
        if (!tree && !recorded.startsWith(GIT)) return Placement.Refused("the verdict recorded no fingerprint to replay", recorded, current)
        val head = WorkspaceFingerprint.committedHead(recorded)
        var state = if (kept == null) "the workspace is gone" else "the workspace changed since the verdict"
        if (kept != null && current == recorded) {
            when (val copied = copy(recorded, kept, tree)) {
                is Placement.Ready -> return copied
                is Placement.Refused -> if (head == null) return copied.copy(current = current) else state = copied.reason
            }
        }
        if (head == null) {
            val what = if (tree) "a tree: fingerprint" else "a state with uncommitted changes"
            return Placement.Refused("$state, and $what cannot be rebuilt", recorded, current)
        }
        return rebuild(recorded, head, projectDir, state, current)
    }

    /** A copy of [workspace], at [recorded], or why it could not be made. */
    private fun copy(
        recorded: String,
        workspace: Path,
        tree: Boolean,
    ): Placement {
        val (parent, source) =
            try {
                tempParent().toRealPath() to workspace.toRealPath()
            } catch (e: Exception) {
                return Placement.Refused("cannot copy the workspace: ${describe(e)}", recorded, UNKNOWN)
            }
        val dir = parent.resolve(SITE)
        var registered = false
        val cleanup = { removeSite(source.takeIf { registered }, parent, dir) }
        return try {
            if (tree) {
                Files.createDirectories(dir)
            } else {
                val head = recorded.split(':')[1]
                if (!COMMIT.matches(head)) throw IOException("a work tree with no commit cannot be copied")
                add(source, head, dir)
                registered = true
                Files.list(dir).use { entries -> entries.filter { it.name != GIT_DIR }.toList() }.forEach(::deleteAll)
            }
            copyTree(source, dir)
            val copied = (if (tree) fingerprinter.tree(dir) else fingerprinter.of(dir)).getOrThrow()
            if (copied != recorded) throw IOException("the copy of the workspace is at $copied")
            Placement.Ready(ReplaySite.WorkspaceCopy(workspace, dir, cleanup))
        } catch (e: Exception) {
            cleanup()
            Placement.Refused("cannot copy the workspace: ${describe(e)}", recorded, UNKNOWN)
        }
    }

    /** A detached worktree of the repository of [projectDir] at [head], or why it cannot be made. */
    private fun rebuild(
        recorded: String,
        head: String,
        projectDir: Path,
        state: String,
        current: String,
    ): Placement {
        val refused = { why: String -> Placement.Refused("$state, and $why", recorded, current) }
        val (repository, parent) =
            try {
                val top = git.run(projectDir, "rev-parse", "--show-toplevel")
                if (top.code != 0) return refused("the project folder is not in a git repository")
                val repository = Path.of(top.stdout.trim()).toRealPath()
                if (!git.hasCommit(repository, head)) return refused("the commit $head is not in the project repository")
                repository to tempParent().toRealPath()
            } catch (e: Exception) {
                return refused("cannot rebuild it: ${describe(e)}")
            }
        val dir = parent.resolve(SITE)
        var registered = false
        val cleanup = { removeSite(repository.takeIf { registered }, parent, dir) }
        return try {
            add(repository, head, dir)
            registered = true
            val rebuilt = fingerprinter.of(dir).getOrThrow()
            if (rebuilt != recorded) throw IOException("the worktree at $head is at $rebuilt")
            Placement.Ready(ReplaySite.Worktree(repository, head, dir, cleanup))
        } catch (e: Exception) {
            cleanup()
            refused("cannot rebuild it: ${describe(e)}")
        }
    }

    /**
     * Removes the site in [dir], its worktree from [repository] first when it has one, then its temporary [parent]. A
     * failure is left behind, never thrown: the verdict stands, and `git worktree prune` forgets a worktree whose folder is gone.
     */
    private fun removeSite(
        repository: Path?,
        parent: Path,
        dir: Path,
    ) {
        if (repository != null) runCatching { git.run(repository, "worktree", "remove", "--force", "--force", dir.toString()) }
        runCatching { deleteAll(parent) }
    }

    /** Adds a detached worktree of the repository of [repository] at [head] in [dir]. */
    private fun add(
        repository: Path,
        head: String,
        dir: Path,
    ) {
        val added = git.run(repository, "worktree", "add", "--detach", "--quiet", dir.toString(), head)
        if (added.code != 0) throw IOException("git worktree add failed: ${added.firstLine()}")
    }

    /** Copies every entry under [from] into [to] but the top `.git`, links as links, with their attributes. */
    private fun copyTree(
        from: Path,
        to: Path,
    ) {
        Files.walk(from).use { stream ->
            stream
                .filter { it != from && !it.startsWith(from.resolve(GIT_DIR)) }
                .forEach { entry ->
                    val target = to.resolve(from.relativize(entry).toString())
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else {
                        Files.copy(entry, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
        }
    }

    companion object {
        /** The current fingerprint of a workspace that is gone. */
        const val GONE = "none, the workspace is gone"

        /** The current fingerprint of a workspace that cannot be read. */
        const val UNKNOWN = "unknown"

        private const val GIT = "git:"
        private const val TREE = "tree:"
        private const val GIT_DIR = ".git"
        private const val SITE = "workspace"
        private val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        private fun deleteAll(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(path).use { it.toList() }.forEach(::deleteAll)
            }
            Files.delete(path)
        }

        private fun describe(error: Throwable): String = error.message ?: error::class.simpleName ?: "unknown error"
    }
}
