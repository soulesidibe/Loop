package loop.runner.acp

import loop.runner.process.ProtectedWorkspaceRoots
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Checks protected workspace customization roots (`.agents/`, `.agent/`, `_agents/`, `_agent/`)
 * against the attempt's host-provided [baseRef] before every session and every `session/prompt`
 * turn under `preset: agy`.
 *
 * Hardened against macOS case-insensitivity, symlinked roots (`ln -s build/x .agents`), type
 * changes (`T` in `--diff-filter=ACMRT`), gitignored untracked files, missing base refs (fail
 * closed), and hung git subprocesses (separate reader threads + `destroyForcibly` on timeout).
 */
internal object ProtectedRootsCheck {
    private const val GIT_TIMEOUT_SECONDS = 15L
    private val ROOT_NAMES_LOWER: Set<String> = ProtectedWorkspaceRoots.ROOT_NAMES.map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * Returns an ordinary failure reason naming the offending customization path when any protected
     * workspace root is a symlink or any customization file under a protected root was added,
     * modified, or type-changed relative to [baseRef], or null when clean.
     */
    fun refusal(
        workspace: Path,
        baseRef: String?,
        gitTimeoutSeconds: Long = GIT_TIMEOUT_SECONDS,
    ): String? {
        val normalizedWorkspace = workspace.toAbsolutePath().normalize()
        val repoRoot = findGitRoot(normalizedWorkspace)
        val scanBase = repoRoot ?: normalizedWorkspace
        val dirsToInspect = ancestorDirs(normalizedWorkspace, scanBase)

        val matchedRoots = mutableListOf<Path>()
        for (dir in dirsToInspect) {
            val entries =
                try {
                    Files.list(dir).use { it.toList() }
                } catch (e: IOException) {
                    return "protected_workspace_root: could not list $dir: ${e.message}"
                }
            for (entry in entries.sortedBy { it.fileName.toString().lowercase(Locale.ROOT) }) {
                val nameLower = entry.fileName.toString().lowercase(Locale.ROOT)
                if (nameLower !in ROOT_NAMES_LOWER) continue
                val rel = scanBase.relativize(entry).toString().replace('\\', '/')
                if (Files.isSymbolicLink(entry)) {
                    return "protected_workspace_root: protected workspace root $rel is a symbolic link"
                }
                if (Files.isDirectory(entry)) {
                    matchedRoots.add(entry)
                }
            }
        }

        if (repoRoot == null) {
            return scanOnDiskCustomizations(scanBase, matchedRoots)
                .firstOrNull()
                ?.let { "protected_workspace_root: added or modified workspace customization file $it" }
        }

        val hasHead = gitSingleLine(repoRoot, listOf("rev-parse", "--verify", "HEAD"), gitTimeoutSeconds) != null
        if (!hasHead) {
            // Brand-new 0-commit git repository: no base exists yet, so refuse any customization file present.
            val diskCustom = scanOnDiskCustomizations(repoRoot, matchedRoots)
            if (diskCustom.isNotEmpty()) {
                return "protected_workspace_root: added or modified workspace customization file ${diskCustom.first()}"
            }
            val staged =
                gitNullSeparated(repoRoot, listOf("ls-files", "--cached", "-z"), gitTimeoutSeconds)
                    ?: return "protected_workspace_root: could not list staged files in protected workspace roots"
            val stagedOffending = staged.sorted().firstOrNull { isProtectedOrCustomization(it) }
            return stagedOffending?.let { "protected_workspace_root: added or modified workspace customization file $it" }
        }

        val base =
            resolveBaseCommit(repoRoot, baseRef, gitTimeoutSeconds)
                ?: return "protected_workspace_root: could not resolve base commit${baseRef?.let { " for $it" } ?: ""}"

        // Include T so replacing a tracked hooks.json with a symlink is caught; omit case-sensitive pathspecs.
        val diffPaths =
            gitNullSeparated(repoRoot, listOf("diff", "--name-only", "-z", "--diff-filter=ACMRT", base), gitTimeoutSeconds)
                ?: return "protected_workspace_root: could not diff protected workspace roots against base commit"
        val diffOffending =
            diffPaths
                .distinct()
                .sorted()
                .firstOrNull { isProtectedOrCustomization(it) }
        if (diffOffending != null) {
            return "protected_workspace_root: added or modified workspace customization file $diffOffending"
        }

        if (matchedRoots.isEmpty()) return null

        val onDiskCustom = scanOnDiskCustomizations(repoRoot, matchedRoots)
        if (onDiskCustom.isEmpty()) return null

        // Compare every on-disk customization file against the exact tracked tree at base (case-insensitively).
        val baseTree =
            gitNullSeparated(repoRoot, listOf("ls-tree", "-r", "--name-only", "-z", base), gitTimeoutSeconds)
                ?: return "protected_workspace_root: could not read base tree for protected workspace roots"
        val trackedLower = baseTree.map { it.replace('\\', '/').lowercase(Locale.ROOT) }.toSet()

        val untrackedOrCaseVariant =
            onDiskCustom.firstOrNull { rel ->
                rel.lowercase(Locale.ROOT) !in trackedLower
            }
        return untrackedOrCaseVariant?.let {
            "protected_workspace_root: added or modified workspace customization file $it"
        }
    }

    private fun scanOnDiskCustomizations(
        base: Path,
        roots: List<Path>,
    ): List<String> {
        val found = mutableListOf<String>()
        for (root in roots) {
            try {
                Files.walk(root).use { stream ->
                    stream.forEach { path ->
                        if (path == root) return@forEach
                        if (!Files.isRegularFile(path) && !Files.isSymbolicLink(path)) return@forEach
                        val rel = base.relativize(path).toString().replace('\\', '/')
                        if (ProtectedWorkspaceRoots.isCustomizationPath(rel)) {
                            found += rel
                        }
                    }
                }
            } catch (e: IOException) {
                found += base.relativize(root).toString().replace('\\', '/')
            }
        }
        return found.distinct().sorted()
    }

    private fun isProtectedOrCustomization(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (ProtectedWorkspaceRoots.isCustomizationPath(normalized)) return true
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        return segments.size == 1 && segments[0].lowercase(Locale.ROOT) in ROOT_NAMES_LOWER
    }

    private fun ancestorDirs(
        workspace: Path,
        stopAt: Path,
    ): List<Path> {
        val dirs = mutableListOf<Path>()
        var current: Path? = workspace
        while (current != null) {
            dirs.add(current)
            if (current == stopAt) break
            current = current.parent
        }
        return dirs
    }

    private fun findGitRoot(workspace: Path): Path? {
        var dir: Path? = workspace
        while (dir != null) {
            if (dir.resolve(".git").exists()) return dir
            dir = dir.parent
        }
        return null
    }

    private fun resolveBaseCommit(
        repoRoot: Path,
        baseRef: String?,
        timeoutSeconds: Long,
    ): String? {
        val trimmed = baseRef?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) {
            gitSingleLine(repoRoot, listOf("merge-base", trimmed, "HEAD"), timeoutSeconds)?.let { return it }
            return gitSingleLine(repoRoot, listOf("rev-parse", "--verify", "$trimmed^{commit}"), timeoutSeconds)
        }
        gitSingleLine(repoRoot, listOf("merge-base", "main", "HEAD"), timeoutSeconds)?.let { return it }
        return gitSingleLine(repoRoot, listOf("merge-base", "master", "HEAD"), timeoutSeconds)
    }

    private fun gitSingleLine(
        repoRoot: Path,
        args: List<String>,
        timeoutSeconds: Long,
    ): String? {
        val output = runGit(repoRoot, args, timeoutSeconds) ?: return null
        return output.trim().takeIf { it.isNotEmpty() }
    }

    private fun gitNullSeparated(
        repoRoot: Path,
        args: List<String>,
        timeoutSeconds: Long,
    ): List<String>? {
        val output = runGit(repoRoot, args, timeoutSeconds) ?: return null
        if (output.isEmpty()) return emptyList()
        return output.split('\u0000').filter { it.isNotEmpty() }
    }

    /**
     * Runs `git` with separate daemon threads draining stdout and stderr before [Process.waitFor]
     * so a hung git command always honors [timeoutSeconds] and is killed with [Process.destroyForcibly].
     */
    internal fun runGit(
        repoRoot: Path,
        args: List<String>,
        timeoutSeconds: Long = GIT_TIMEOUT_SECONDS,
        gitCommand: String = "git",
    ): String? =
        try {
            val process =
                ProcessBuilder(listOf(gitCommand) + args)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
                    .start()
            process.outputStream.close()
            var stdout = ""
            val outThread =
                Thread {
                    stdout = runCatching { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                }.apply {
                    isDaemon = true
                    start()
                }
            val errThread =
                Thread {
                    runCatching { process.errorStream.readAllBytes() }
                }.apply {
                    isDaemon = true
                    start()
                }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                null
            } else {
                outThread.join(1_000)
                errThread.join(1_000)
                if (process.exitValue() == 0) stdout else null
            }
        } catch (e: IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
}
