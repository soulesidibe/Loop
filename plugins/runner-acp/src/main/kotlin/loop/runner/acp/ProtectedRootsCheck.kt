package loop.runner.acp

import loop.runner.process.ProtectedWorkspaceRoots
import java.io.BufferedInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Checks protected workspace customization roots (`.agents/`, `.agent/`, `_agents/`, `_agent/`)
 * against the attempt's host-provided [baseRef] before every session and every `session/prompt`
 * turn under `preset: agy`.
 *
 * Hardened against:
 * - git index tricks (`git rm --cached`, `assume-unchanged`, `skip-worktree`) by comparing every
 *   on-disk customization file directly against `git ls-tree -r -z <base>` using file mode and
 *   `git hash-object --no-filters` blob SHA,
 * - agent-controlled git clean filters, replace refs, grafts, hooks, and host-env leaks by passing
 *   `--no-replace-objects`, safe `-c` pins before every subcommand, a minimal environment with
 *   `GIT_TERMINAL_PROMPT=0` and `GIT_GRAFT_FILE=/dev/null`, and `--no-filters` (so LFS pointers,
 *   `ident`, `working-tree-encoding`, and smudge filters fail closed; exact `core.autocrlf=true`
 *   checkouts of LF base blobs are accepted via a strict, size-bounded, streamed JVM digest check),
 * - nested paths `agy` never loads (`docs/examples/.agents/...`), by inspecting only roots sitting
 *   directly in directories between `workspace` and `repoRoot`,
 * - macOS case-insensitivity, symlinked protected roots, symlink type changes (`120000`),
 *   unreadable directories (`UncheckedIOException`), missing base refs (fail closed after checking
 *   `main`, `master`, and `origin/HEAD`), and hung git subprocesses (separate daemon drain threads,
 *   alive-thread fail-closed check, and `destroyForcibly` on timeout).
 */
internal object ProtectedRootsCheck {
    private const val GIT_TIMEOUT_SECONDS = 15L
    private const val MODE_SYMLINK = "120000"
    private const val MODE_EXECUTABLE = "100755"
    private const val MODE_REGULAR = "100644"
    private const val SHA1_HEX_LENGTH = 40
    private const val SHA256_HEX_LENGTH = 64
    private const val STREAM_BUFFER_BYTES = 8_192
    private val ROOT_NAMES_LOWER: Set<String> = ProtectedWorkspaceRoots.ROOT_NAMES.map { it.lowercase(Locale.ROOT) }.toSet()
    private val KEPT_ENV: Set<String> = setOf("PATH", "HOME", "LANG", "LANGUAGE")

    /**
     * Safe git command-line pins matching `WorkspaceGit.PINS` + `WorkspaceGit.MORE_PINS`, placed
     * before every git subcommand so agent-writable `.git/config` or `refs/replace/` cannot alter
     * what Loop executes or reads.
     */
    internal val GIT_PINS: List<String> =
        listOf(
            "core.hooksPath=/dev/null",
            "core.fsmonitor=false",
            "commit.gpgsign=false",
            "core.commitGraph=false",
            "merge.verifySignatures=false",
            "gpg.program=",
            "gpg.ssh.program=",
            "gpg.x509.program=",
            "gc.auto=0",
            "maintenance.auto=false",
        ).flatMap { listOf("-c", it) } + "--no-replace-objects"

    internal fun minimalGitEnv(baseEnv: Map<String, String>): Map<String, String> =
        baseEnv.filterKeys { it in KEPT_ENV || it.startsWith("LC_") } +
            mapOf(
                "GIT_TERMINAL_PROMPT" to "0",
                "GIT_GRAFT_FILE" to "/dev/null",
            )

    internal data class GitResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    )

    private data class OnDiskCustomization(
        val path: Path,
        val relPath: String,
    )

    private data class TreeEntry(
        val mode: String,
        val sha: String,
    )

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
                } catch (e: UncheckedIOException) {
                    return "protected_workspace_root: could not list $dir: ${e.cause?.message ?: e.message}"
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
            val scanResult = scanOnDiskCustomizations(scanBase, matchedRoots)
            scanResult.error?.let { return it }
            return scanResult.files
                .firstOrNull()
                ?.let { "protected_workspace_root: added or modified workspace customization file ${it.relPath}" }
        }

        val hasHead = gitOk(repoRoot, listOf("rev-parse", "--verify", "HEAD"), gitTimeoutSeconds) != null
        if (!hasHead) {
            // Brand-new 0-commit git repository: no base exists yet, so refuse any customization file present.
            val scanResult = scanOnDiskCustomizations(repoRoot, matchedRoots)
            scanResult.error?.let { return it }
            if (scanResult.files.isNotEmpty()) {
                return "protected_workspace_root: added or modified workspace customization file ${scanResult.files.first().relPath}"
            }
            val staged = runGit(repoRoot, listOf("ls-files", "--cached", "-z"), gitTimeoutSeconds)
            if (staged == null || staged.exitCode != 0) {
                return "protected_workspace_root: could not list staged files in protected workspace roots${stderrSuffix(staged)}"
            }
            val ancestorPrefixes = ancestorRelPrefixes(repoRoot, dirsToInspect)
            val stagedOffending =
                splitNullSeparated(staged.stdout)
                    .sorted()
                    .firstOrNull { isDirectProtectedCustomization(it, ancestorPrefixes) }
            return stagedOffending?.let { "protected_workspace_root: added or modified workspace customization file $it" }
        }

        val (base, baseError) = resolveBaseCommit(repoRoot, baseRef, gitTimeoutSeconds)
        if (base == null) {
            return baseError
        }

        if (matchedRoots.isEmpty()) return null

        val scanResult = scanOnDiskCustomizations(repoRoot, matchedRoots)
        scanResult.error?.let { return it }
        if (scanResult.files.isEmpty()) return null

        val treeResult = runGit(repoRoot, listOf("ls-tree", "-r", "-z", base), gitTimeoutSeconds)
        if (treeResult == null || treeResult.exitCode != 0) {
            return "protected_workspace_root: could not read base tree for protected workspace roots${stderrSuffix(treeResult)}"
        }
        val baseEntries = parseLsTree(treeResult.stdout)

        for (file in scanResult.files) {
            val baseEntry = baseEntries[file.relPath]
            if (baseEntry == null) {
                return "protected_workspace_root: added or modified workspace customization file ${file.relPath}"
            }
            val (onDiskMode, onDiskSha, hashError) = hashOnDisk(repoRoot, file, gitTimeoutSeconds)
            if (hashError != null) {
                return hashError
            }
            if (onDiskMode != baseEntry.mode) {
                return "protected_workspace_root: added or modified workspace customization file ${file.relPath}"
            }
            if (onDiskSha != baseEntry.sha) {
                val crlfMatch =
                    !Files.isSymbolicLink(file.path) &&
                        matchesStrictCrlfNormalizedBlob(repoRoot, file.path, baseEntry.sha, gitTimeoutSeconds)
                if (!crlfMatch) {
                    return "protected_workspace_root: added or modified workspace customization file ${file.relPath}"
                }
            }
        }

        return null
    }

    private data class ScanResult(
        val files: List<OnDiskCustomization>,
        val error: String?,
    )

    private fun scanOnDiskCustomizations(
        base: Path,
        roots: List<Path>,
    ): ScanResult {
        val found = mutableListOf<OnDiskCustomization>()
        for (root in roots) {
            val rootRel = base.relativize(root).toString().replace('\\', '/')
            try {
                Files.walk(root).use { stream ->
                    stream.forEach { path ->
                        if (path == root) return@forEach
                        if (!Files.isRegularFile(path) && !Files.isSymbolicLink(path)) return@forEach
                        val rel = base.relativize(path).toString().replace('\\', '/')
                        if (ProtectedWorkspaceRoots.isCustomizationPath(rel)) {
                            found += OnDiskCustomization(path, rel)
                        }
                    }
                }
            } catch (e: IOException) {
                return ScanResult(emptyList(), "protected_workspace_root: could not scan $rootRel: ${e.message}")
            } catch (e: UncheckedIOException) {
                return ScanResult(
                    emptyList(),
                    "protected_workspace_root: could not scan $rootRel: ${e.cause?.message ?: e.message}",
                )
            }
        }
        return ScanResult(found.distinctBy { it.relPath }.sortedBy { it.relPath }, error = null)
    }

    private data class DiskHash(
        val mode: String?,
        val sha: String?,
        val error: String?,
    )

    private fun hashOnDisk(
        repoRoot: Path,
        file: OnDiskCustomization,
        timeoutSeconds: Long,
    ): DiskHash {
        if (Files.isSymbolicLink(file.path)) {
            val targetBytes =
                try {
                    Files.readSymbolicLink(file.path).toString().toByteArray(Charsets.UTF_8)
                } catch (e: IOException) {
                    return DiskHash(null, null, "protected_workspace_root: could not read symbolic link ${file.relPath}: ${e.message}")
                }
            val result =
                runGit(
                    repoRoot,
                    listOf("hash-object", "--no-filters", "--stdin"),
                    timeoutSeconds,
                    stdinBytes = targetBytes,
                )
            if (result == null || result.exitCode != 0) {
                return DiskHash(
                    null,
                    null,
                    "protected_workspace_root: could not hash ${file.relPath}${stderrSuffix(result)}",
                )
            }
            return DiskHash(MODE_SYMLINK, result.stdout.trim(), error = null)
        }

        val mode = if (Files.isExecutable(file.path)) MODE_EXECUTABLE else MODE_REGULAR
        val result =
            runGit(
                repoRoot,
                listOf("hash-object", "--no-filters", "--", file.path.toAbsolutePath().toString()),
                timeoutSeconds,
            )
        if (result == null || result.exitCode != 0) {
            return DiskHash(
                null,
                null,
                "protected_workspace_root: could not hash ${file.relPath}${stderrSuffix(result)}",
            )
        }
        return DiskHash(mode, result.stdout.trim(), error = null)
    }

    /**
     * Accepts an on-disk regular file whose raw `--no-filters` hash differed from [baseSha] only when
     * it is an exact CRLF checkout of an LF-only base blob:
     * - `baseSize < diskSize <= 2 * baseSize` (prevents unbounded reads of huge agent-written files),
     * - every `\n` on disk is preceded by `\r` and no lone `\r` appears anywhere on disk,
     * - streaming the CRLF-to-LF normalized bytes produces length `baseSize` and a git blob digest
     *   equal to [baseSha] (which also proves the base blob contained zero `\r` bytes).
     */
    private fun matchesStrictCrlfNormalizedBlob(
        repoRoot: Path,
        filePath: Path,
        baseSha: String,
        timeoutSeconds: Long,
    ): Boolean {
        val algorithm =
            when (baseSha.length) {
                SHA1_HEX_LENGTH -> "SHA-1"
                SHA256_HEX_LENGTH -> "SHA-256"
                else -> return false
            }
        val baseSize =
            gitOk(repoRoot, listOf("cat-file", "-s", baseSha), timeoutSeconds)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return false
        val diskSize =
            try {
                Files.size(filePath)
            } catch (e: IOException) {
                return false
            }
        if (diskSize <= baseSize || (diskSize - baseSize) > baseSize) {
            return false
        }

        val digest = MessageDigest.getInstance(algorithm)
        digest.update("blob $baseSize\u0000".toByteArray(Charsets.US_ASCII))

        var normalizedLength = 0L
        var pendingCr = false
        var sawCrlf = false
        val buf = ByteArray(STREAM_BUFFER_BYTES)

        try {
            BufferedInputStream(Files.newInputStream(filePath), STREAM_BUFFER_BYTES).use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    for (i in 0 until read) {
                        val b = buf[i].toInt() and 0xFF
                        if (pendingCr) {
                            if (b == '\n'.code) {
                                pendingCr = false
                                sawCrlf = true
                                digest.update('\n'.code.toByte())
                                normalizedLength++
                            } else {
                                return false
                            }
                        } else {
                            when (b) {
                                '\r'.code -> pendingCr = true
                                '\n'.code -> return false
                                else -> {
                                    digest.update(buf[i])
                                    normalizedLength++
                                }
                            }
                        }
                        if (normalizedLength > baseSize) return false
                    }
                }
            }
        } catch (e: IOException) {
            return false
        }

        if (pendingCr || !sawCrlf || normalizedLength != baseSize) return false
        val computedHex = HexFormat.of().formatHex(digest.digest())
        return computedHex == baseSha.lowercase(Locale.ROOT)
    }

    private fun parseLsTree(output: String): Map<String, TreeEntry> {
        if (output.isEmpty()) return emptyMap()
        val entries = LinkedHashMap<String, TreeEntry>()
        for (record in splitNullSeparated(output)) {
            val tab = record.indexOf('\t')
            if (tab <= 0 || tab + 1 >= record.length) continue
            val meta = record.substring(0, tab).split(' ')
            if (meta.size < 3) continue
            val mode = meta[0]
            val sha = meta[2]
            val path = record.substring(tab + 1).replace('\\', '/')
            entries[path] = TreeEntry(mode = mode, sha = sha)
        }
        return entries
    }

    private fun ancestorRelPrefixes(
        repoRoot: Path,
        dirsToInspect: List<Path>,
    ): Set<String> =
        dirsToInspect
            .map { dir ->
                val rel = repoRoot.relativize(dir).toString().replace('\\', '/')
                if (rel.isEmpty() || rel == ".") "" else "$rel/"
            }.toSet()

    private fun isDirectProtectedCustomization(
        path: String,
        ancestorPrefixes: Set<String>,
    ): Boolean {
        val normalized = path.replace('\\', '/')
        for (prefix in ancestorPrefixes) {
            if (!normalized.startsWith(prefix)) continue
            val remainder = normalized.removePrefix(prefix)
            val firstSegment = remainder.substringBefore('/').lowercase(Locale.ROOT)
            if (firstSegment in ROOT_NAMES_LOWER && ProtectedWorkspaceRoots.isCustomizationPath(remainder)) {
                return true
            }
        }
        return false
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
    ): Pair<String?, String?> {
        val trimmed = baseRef?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) {
            gitOk(repoRoot, listOf("merge-base", trimmed, "HEAD"), timeoutSeconds)?.let { return it to null }
            val verify = runGit(repoRoot, listOf("rev-parse", "--verify", "$trimmed^{commit}"), timeoutSeconds)
            if (verify != null && verify.exitCode == 0 && verify.stdout.trim().isNotEmpty()) {
                return verify.stdout.trim() to null
            }
            return null to "protected_workspace_root: could not resolve base commit for $trimmed${stderrSuffix(verify)}"
        }
        for (candidate in DEFAULT_BASE_CANDIDATES) {
            gitOk(repoRoot, listOf("merge-base", candidate, "HEAD"), timeoutSeconds)?.let { return it to null }
        }
        return null to
            "protected_workspace_root: could not resolve base commit (checked main, master, and origin/HEAD): " +
            "configure kinds.code.guards.base or kinds.code.delivery.pull_request.base"
    }

    private fun gitOk(
        repoRoot: Path,
        args: List<String>,
        timeoutSeconds: Long,
    ): String? {
        val result = runGit(repoRoot, args, timeoutSeconds) ?: return null
        if (result.exitCode != 0) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    private fun splitNullSeparated(output: String): List<String> =
        if (output.isEmpty()) emptyList() else output.split('\u0000').filter { it.isNotEmpty() }

    private fun stderrSuffix(result: GitResult?): String {
        if (result == null) return ": git timed out or did not finish cleanly"
        val msg =
            result.stderr
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(200)
        return if (msg.isNotEmpty()) ": $msg" else " (exit ${result.exitCode})"
    }

    /**
     * Runs `git` with [GIT_PINS] before the subcommand, a minimal environment ([minimalGitEnv]), and
     * separate daemon threads draining stdout and stderr before [Process.waitFor] so a hung git
     * command always honors [timeoutSeconds] and is killed with [Process.destroyForcibly].
     * Returns null when the command times out, fails to launch, or a drain thread stays alive past join.
     */
    internal fun runGit(
        repoRoot: Path,
        args: List<String>,
        timeoutSeconds: Long = GIT_TIMEOUT_SECONDS,
        gitCommand: String = "git",
        stdinBytes: ByteArray? = null,
        baseEnv: Map<String, String> = System.getenv(),
    ): GitResult? =
        try {
            val builder =
                ProcessBuilder(listOf(gitCommand) + GIT_PINS + args)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
            builder.environment().apply {
                clear()
                putAll(minimalGitEnv(baseEnv))
            }
            val process = builder.start()
            if (stdinBytes != null) {
                process.outputStream.use { it.write(stdinBytes) }
            } else {
                process.outputStream.close()
            }
            var stdout = ""
            var stderr = ""
            val outThread =
                Thread {
                    stdout = runCatching { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                }.apply {
                    isDaemon = true
                    start()
                }
            val errThread =
                Thread {
                    stderr = runCatching { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")
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
                if (outThread.isAlive || errThread.isAlive) {
                    null
                } else {
                    GitResult(stdout = stdout, stderr = stderr, exitCode = process.exitValue())
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    private val DEFAULT_BASE_CANDIDATES = listOf("main", "master", "origin/HEAD")
}
