package loop.runner.acp

import loop.runner.process.ProtectedWorkspaceRoots
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Checks protected workspace customization roots (`.agents/`, `.agent/`, `_agents/`, `_agent/`)
 * against the attempt's base commit before every session and every `session/prompt` turn under
 * `preset: agy`.
 *
 * Any added or modified `hooks.json`, `mcp_config.json`, `skills.json`, `rules.json`, `plugins.json`,
 * or file under `plugins/`, `rules/`, or `hooks/` inside a protected root fails the turn as an
 * ordinary failure naming the path.
 */
internal object ProtectedRootsCheck {
    private const val LOOP_BASE_REF = "LOOP_BASE_REF"
    private const val GIT_TIMEOUT_SECONDS = 15L

    /**
     * Returns an ordinary failure reason naming the offending customization path when any protected
     * workspace customization file was added or modified relative to the attempt's base commit, or
     * null when clean.
     */
    fun refusal(
        workspace: Path,
        env: Map<String, String>,
    ): String? {
        val repoRoot = findGitRoot(workspace)
        if (repoRoot == null) {
            return scanNonGitWorkspace(workspace)
        }
        val pathspecs = gitPathspecs(workspace, repoRoot)
        if (pathspecs.isEmpty()) return null

        val base = resolveBaseCommit(repoRoot, env)
        val changed =
            if (base != null) {
                val diff =
                    gitNullSeparated(
                        repoRoot,
                        listOf("diff", "--name-only", "-z", "--diff-filter=ACMR", base, "--") + pathspecs,
                    )
                        ?: return "protected_workspace_root: could not diff protected workspace roots against base commit"
                diff
            } else {
                // Repository with no commits yet: check staged files too.
                gitNullSeparated(repoRoot, listOf("ls-files", "--cached", "-z", "--") + pathspecs)
                    ?: return "protected_workspace_root: could not list staged files in protected workspace roots"
            }

        // Omit --exclude-standard so gitignored files under .agents/ (like hooks.json) are still caught.
        val untracked =
            gitNullSeparated(repoRoot, listOf("ls-files", "--others", "-z", "--") + pathspecs)
                ?: return "protected_workspace_root: could not list untracked files in protected workspace roots"

        val offending =
            (changed + untracked)
                .distinct()
                .sorted()
                .firstOrNull { ProtectedWorkspaceRoots.isCustomizationPath(it) }

        return offending?.let { "protected_workspace_root: added or modified workspace customization file $it" }
    }

    private fun scanNonGitWorkspace(workspace: Path): String? {
        val normalized = workspace.toAbsolutePath().normalize()
        for (root in ProtectedWorkspaceRoots.rootsOf(normalized)) {
            if (!Files.isDirectory(root)) continue
            val offending =
                try {
                    Files.walk(root).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) || Files.isSymbolicLink(it) }
                            .map { normalized.relativize(it).toString().replace('\\', '/') }
                            .sorted()
                            .filter { ProtectedWorkspaceRoots.isCustomizationPath(it) }
                            .findFirst()
                            .orElse(null)
                    }
                } catch (e: IOException) {
                    return "protected_workspace_root: could not scan $root: ${e.message}"
                }
            if (offending != null) {
                return "protected_workspace_root: added or modified workspace customization file $offending"
            }
        }
        return null
    }

    private fun findGitRoot(workspace: Path): Path? {
        var dir: Path? = workspace.toAbsolutePath().normalize()
        while (dir != null) {
            if (dir.resolve(".git").exists()) return dir
            dir = dir.parent
        }
        return null
    }

    private fun gitPathspecs(
        workspace: Path,
        repoRoot: Path,
    ): List<String> {
        val normalizedRepo = repoRoot.toAbsolutePath().normalize()
        return ProtectedWorkspaceRoots
            .rootsOf(workspace)
            .mapNotNull { root ->
                val normalized = root.toAbsolutePath().normalize()
                if (normalized.startsWith(normalizedRepo)) {
                    val rel = normalizedRepo.relativize(normalized).toString().replace('\\', '/')
                    rel.takeIf { it.isNotEmpty() && !it.startsWith("..") }
                } else {
                    null
                }
            }.distinct()
    }

    private fun resolveBaseCommit(
        repoRoot: Path,
        env: Map<String, String>,
    ): String? {
        val baseRef = env[LOOP_BASE_REF]?.takeIf { it.isNotBlank() }
        if (baseRef != null) {
            gitSingleLine(repoRoot, listOf("merge-base", baseRef, "HEAD"))?.let { return it }
            gitSingleLine(repoRoot, listOf("rev-parse", "--verify", "$baseRef^{commit}"))?.let { return it }
        }
        gitSingleLine(repoRoot, listOf("merge-base", "main", "HEAD"))?.let { return it }
        return gitSingleLine(repoRoot, listOf("rev-parse", "--verify", "HEAD"))
    }

    private fun gitSingleLine(
        repoRoot: Path,
        args: List<String>,
    ): String? {
        val output = runGit(repoRoot, args) ?: return null
        return output.trim().takeIf { it.isNotEmpty() }
    }

    private fun gitNullSeparated(
        repoRoot: Path,
        args: List<String>,
    ): List<String>? {
        val output = runGit(repoRoot, args) ?: return null
        if (output.isEmpty()) return emptyList()
        return output.split('\u0000').filter { it.isNotEmpty() }
    }

    private fun runGit(
        repoRoot: Path,
        args: List<String>,
    ): String? =
        try {
            val process =
                ProcessBuilder(listOf("git") + args)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
                    .start()
            process.outputStream.close()
            val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            process.errorStream.readAllBytes()
            if (process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                stdout
            } else {
                null
            }
        } catch (e: IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
}
