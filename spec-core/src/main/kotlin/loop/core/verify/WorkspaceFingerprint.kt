package loop.core.verify

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.relativeTo

/** Computes the fingerprint of a workspace (m2-05); the seam the orchestrator calls before a verification's checks. */
fun interface Fingerprinter {
    /** The fingerprint of [workspace] now, or the failure that kept it from being computed. */
    fun of(workspace: Path): Result<String>

    /**
     * The files of [workspace] that a commit does not hold (m2-25), named in the finding of a work tree refuted for
     * them. None by default, for a fingerprinter that cannot tell.
     */
    fun uncommitted(workspace: Path): Result<UncommittedFiles> = Result.success(UncommittedFiles(emptyList(), emptyList()))

    /**
     * Puts the work tree of [workspace] back to its commit (m2-25): the tracked files a check rewrote, and the untracked
     * files a check created, so a later verification does not refute the agent for them. Ignored files and the result
     * file stay. Nothing by default, for a fingerprinter that cannot tell.
     */
    fun restore(workspace: Path): Result<Unit> = Result.success(Unit)
}

/**
 * The files of a git work tree that its commit does not hold (m2-25), each list sorted by path: the [changed] tracked
 * files, staged or not, and the [untracked] files git does not ignore. The result file is in neither.
 */
data class UncommittedFiles(
    val changed: List<String>,
    val untracked: List<String>,
)

/**
 * The workspace fingerprint (m2-05, PLAN 6.4): the state a verification ran on, so two verifications
 * of the same state give the same value and one changed file changes it.
 *
 * - When the workspace is the top of a git work tree: `git:<HEAD>:<diff>:<untracked>`. `<HEAD>` is the
 *   commit, or `none` before the first one. `<diff>` hashes `git diff HEAD --binary`, staged and
 *   unstaged changes alike, so staging a file changes nothing. `<untracked>` hashes the path and the
 *   content of every untracked file git does not ignore, in path order. Ignored files, such as build
 *   output, are not workspace state.
 * - Otherwise, a folder that is not a repository or only a folder inside one: `tree:<hash>` of the path
 *   and content of every file under it, in path order.
 *
 * The result file `.loop/result.json` at the top of the workspace is left out of both (m2-15): it is what the
 * agent tells Loop, not the work. Every other file under `.loop/` counts. Each hash is sha256, cut to its first
 * 16 hex characters.
 * A symlink counts by its target, never followed.
 */
open class WorkspaceFingerprinter(
    private val workspaceGit: WorkspaceGit = WorkspaceGit(),
) : Fingerprinter {
    override fun of(workspace: Path): Result<String> =
        try {
            val root = workspace.toRealPath()
            Result.success(if (isGitTop(root)) git(root) else "tree:${tree(root)}")
        } catch (e: Exception) {
            // Files.walk throws UncheckedIOException, a process start IOException: both mean no fingerprint.
            Result.failure(e)
        }

    /**
     * The changed and untracked files of the git work tree at [workspace] (m2-25), as the `<diff>` and `<untracked>`
     * parts of its fingerprint see them; a failure when it is not the top of a git work tree or git fails.
     */
    override fun uncommitted(workspace: Path): Result<UncommittedFiles> =
        try {
            val root = workspace.toRealPath()
            if (!isGitTop(root)) throw IOException("$root is not the top of a git work tree")
            val hasHead = gitText(root, "rev-parse", "--verify", "-q", "HEAD").first == 0
            val changed =
                if (hasHead) {
                    names(root, "diff", "HEAD", "--name-only", "-z", *DIFF_OPTIONS, *PATHS)
                } else {
                    names(root, "diff", "--cached", "--name-only", "-z", *DIFF_OPTIONS, *PATHS) +
                        names(root, "diff", "--name-only", "-z", *DIFF_OPTIONS, *PATHS)
                }
            val untracked = names(root, "ls-files", "--others", "--exclude-standard", "-z", *PATHS)
            Result.success(UncommittedFiles(changed.distinct().sorted(), untracked.distinct().sorted()))
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Puts the git work tree at [workspace] back to its `HEAD` (m2-25): staged and unstaged changes to tracked files
     * are undone, then the untracked files git does not ignore are deleted, the result file aside, and so are the
     * folders their deletion left empty. A nested repository stays. A failure when it is not the top of a git work
     * tree with a commit, or git fails.
     */
    override fun restore(workspace: Path): Result<Unit> =
        try {
            val root = workspace.toRealPath()
            if (!isGitTop(root)) throw IOException("$root is not the top of a git work tree")
            if (gitText(root, "rev-parse", "--verify", "-q", "HEAD").first != 0) throw IOException("$root has no commit")
            gitInto(root, digest(), "restore", "--source=HEAD", "--staged", "--worktree", "--quiet", *PATHS)
            names(root, "ls-files", "--others", "--exclude-standard", "-z", *PATHS)
                .filterNot { it.endsWith("/") }
                .map { root.resolve(it).normalize() }
                .filter { it.startsWith(root) && it != root }
                .forEach { file ->
                    Files.deleteIfExists(file)
                    var dir = file.parent
                    while (dir != null && dir != root && dir.startsWith(root) && isEmptyFolder(dir)) {
                        Files.delete(dir)
                        dir = dir.parent
                    }
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun isEmptyFolder(dir: Path): Boolean =
        Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && Files.list(dir).use { !it.findFirst().isPresent }

    /** The NUL separated paths git prints for [args] in [root]. */
    private fun names(
        root: Path,
        vararg args: String,
    ): List<String> {
        val output = digest(keep = true)
        gitInto(root, output, *args)
        return String(output.stored, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    }

    private fun isGitTop(root: Path): Boolean {
        val (code, output) = gitText(root, "rev-parse", "--show-toplevel")
        if (code != 0) return false
        return runCatching { Path.of(output.trim()).toRealPath() == root }.getOrDefault(false)
    }

    private fun git(root: Path): String {
        val (headCode, headOutput) = gitText(root, "rev-parse", "--verify", "-q", "HEAD")
        val head = if (headCode == 0) headOutput.trim() else null
        val diff = digest()
        if (head != null) {
            gitInto(root, diff, "diff", "HEAD", *DIFF_OPTIONS, *PATHS)
        } else {
            // No commit yet: the staged files against nothing, then the unstaged changes against the index.
            gitInto(root, diff, "diff", "--cached", *DIFF_OPTIONS, *PATHS)
            gitInto(root, diff, "diff", *DIFF_OPTIONS, *PATHS)
        }
        val names = digest(keep = true)
        gitInto(root, names, "ls-files", "--others", "--exclude-standard", "-z", *PATHS)
        val untracked = digest()
        String(names.stored, Charsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .sorted()
            .forEach { name -> entry(untracked, name, root.resolve(name)) }
        return "git:${head ?: NO_HEAD}:${hex(diff)}:${hex(untracked)}"
    }

    private fun tree(root: Path): String {
        val hash = digest()
        val files =
            Files.walk(root).use { stream ->
                stream
                    .filter { it != root && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { !it.startsWith(root.resolve(ResultFile.PATH)) }
                    .map { it.relativeTo(root).joinToString("/") { part -> part.name } to it }
                    .toList()
            }
        files.sortedBy { it.first }.forEach { (name, path) -> entry(hash, name, path) }
        return hex(hash)
    }

    /** One file in a hash: its kind, its path and its content, each length prefixed so no two lists collide. */
    private fun entry(
        hash: Hash,
        name: String,
        path: Path,
    ) {
        val (kind, content) =
            when {
                Files.isSymbolicLink(path) -> "link" to path.readSymbolicLink().toString().toByteArray()
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> "file" to null
                // A nested repository or another special entry counts by its name.
                else -> "other" to ByteArray(0)
            }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        hash.update("$kind ${nameBytes.size}\n".toByteArray())
        hash.update(nameBytes)
        if (content != null) {
            hash.update("\n${content.size}\n".toByteArray())
            hash.update(content)
        } else {
            hash.update("\n${Files.size(path)}\n".toByteArray())
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { hash.updateFrom(it) }
        }
    }

    private fun gitText(
        root: Path,
        vararg args: String,
    ): Pair<Int, String> {
        val hash = digest(keep = true)
        val code = runGit(root, args.toList()) { hash.updateFrom(it) }
        return code to String(hash.stored, Charsets.UTF_8)
    }

    private fun gitInto(
        root: Path,
        hash: Hash,
        vararg args: String,
    ) {
        val code = runGit(root, args.toList()) { hash.updateFrom(it) }
        if (code != 0) throw IOException("git ${args.first()} exited with code $code")
    }

    /**
     * Runs git in [root] through [workspaceGit] (m2-25, m2-26): only `PATH`, `HOME` and the locale of the host env, so
     * no `GIT_` variable can point it at another repository and a command the work tree's config names, such as a
     * filter, never sees a token or an Anthropic key; no hook, no fsmonitor, and a timeout.
     */
    private fun runGit(
        root: Path,
        args: List<String>,
        read: (InputStream) -> Unit,
    ): Int =
        // A read only look: never take the index lock to refresh it.
        workspaceGit.stream(root, args, mapOf("GIT_OPTIONAL_LOCKS" to "0"), read = read)

    private fun digest(keep: Boolean = false): Hash = Hash(keep)

    private fun hex(hash: Hash): String = hash.digest().joinToString("") { "%02x".format(it) }.take(HEX_CHARS)

    /** A sha256 digest that can also keep the bytes it saw, for the short git outputs read as text. */
    private class Hash(
        private val keep: Boolean,
    ) {
        private val sha = MessageDigest.getInstance("SHA-256")
        private val kept = java.io.ByteArrayOutputStream()

        val stored: ByteArray get() = kept.toByteArray()

        fun update(bytes: ByteArray) {
            sha.update(bytes)
            if (keep) kept.write(bytes)
        }

        fun updateFrom(input: InputStream) {
            val chunk = ByteArray(CHUNK)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                sha.update(chunk, 0, read)
                if (keep) kept.write(chunk, 0, read)
            }
        }

        fun digest(): ByteArray = sha.digest()
    }

    private companion object {
        val DIFF_OPTIONS = arrayOf("--binary", "--no-ext-diff", "--no-textconv", "--no-color", "--no-renames")

        /** Every path of the work tree but the result file (m2-15), as a literal path so no glob widens it. */
        val PATHS = arrayOf("--", ".", ":(exclude,literal)${ResultFile.PATH}")
        const val NO_HEAD = "none"

        const val HEX_CHARS = 16
        const val CHUNK = 8192
    }
}

/**
 * The workspace fingerprint with the host's git and environment, the one the orchestrator uses unless told otherwise,
 * and the helpers that read a fingerprint.
 */
object WorkspaceFingerprint : WorkspaceFingerprinter() {
    /** The hash of nothing, sha256 cut like every hash here: the `<diff>` and `<untracked>` of a clean work tree. */
    const val EMPTY_HASH: String = "e3b0c44298fc1c14"

    /**
     * The commit a `git:` [fingerprint] names when it also says the work tree was clean, nothing uncommitted and no
     * untracked file (m2-25): what a push of that commit carries is exactly what was verified. Null otherwise, for a
     * `tree:` fingerprint, a work tree with no commit or one with changes.
     */
    fun committedHead(fingerprint: String): String? {
        val parts = fingerprint.split(':')
        if (parts.size != GIT_PARTS || parts[0] != "git") return null
        val (_, head, diff, untracked) = parts
        if (!COMMIT.matches(head) || diff != EMPTY_HASH || untracked != EMPTY_HASH) return null
        return head
    }

    private val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    private const val GIT_PARTS = 4
}
