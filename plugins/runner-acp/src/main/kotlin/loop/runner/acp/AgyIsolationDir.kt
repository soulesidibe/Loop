package loop.runner.acp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID

/**
 * Isolated per-session `--gemini-dir` directory under `$HOME` for `preset: agy` with `strict_mcp: true`.
 *
 * `agy acp --gemini-dir` requires a path relative to `$HOME` (absolute paths fail with `must not be
 * absolute`). When `LOOP_HOME` lies under `$HOME`, the directory lives under
 * `$LOOP_HOME/attempts/<project>/<attempt>/agy-gemini-<id>`; otherwise it lives under
 * `$HOME/.loop-agy/<attempt>-<id>`. Contains `antigravity-cli/settings.json` with
 * `{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`, and is deleted when the session stops.
 *
 * Fallback directories are populated under a hidden `.tmp-<id>` staging folder and atomically moved
 * into place after writing both `settings.json` and `.loop-pid`, so a concurrent `sweepStale` never
 * races with directory creation.
 */
internal class AgyIsolationDir private constructor(
    /** Absolute path of the created directory on disk. */
    val path: Path,
    /** Path relative to `$HOME`, passed to `--gemini-dir`. */
    val relativePath: String,
    /** Optional fallback parent (`$HOME/.loop-agy`) to remove if empty after cleanup. */
    private val cleanupEmptyParent: Path?,
) {
    /** Recursively removes [path] and, if empty, any fallback `.loop-agy` parent. Safe to call twice. */
    fun delete(): String? =
        try {
            if (Files.exists(path)) {
                Files.walk(path).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            if (cleanupEmptyParent != null && Files.isDirectory(cleanupEmptyParent)) {
                try {
                    Files.deleteIfExists(cleanupEmptyParent)
                } catch (ignored: DirectoryNotEmptyException) {
                    // Another session is still using .loop-agy.
                }
            }
            null
        } catch (e: IOException) {
            "could not remove isolated agy directory $path: ${e.message}"
        } catch (e: UncheckedIOException) {
            "could not remove isolated agy directory $path: ${e.cause?.message ?: e.message}"
        }

    companion object {
        const val FALLBACK_ROOT_NAME: String = ".loop-agy"
        private const val STAGING_PREFIX = ".tmp-"
        private const val STAGING_GRACE_SECONDS = 60L
        private const val PID_FILE_NAME = ".loop-pid"
        private const val SETTINGS_SUBDIR = "antigravity-cli"
        private const val SETTINGS_FILE = "settings.json"
        private val UNSAFE_SEGMENT = Regex("[^A-Za-z0-9._-]+")

        val SETTINGS_JSON: String =
            buildJsonObject {
                put("toolPermission", "strict")
                put("allowNonWorkspaceAccess", false)
            }.toString()

        /**
         * Removes stale directories under `$HOME/.loop-agy/` left behind by crashed JVM runs (any
         * subdirectory whose `.loop-pid` file is missing or names a PID that is no longer alive).
         * Staging directories (`.tmp-*`) younger than [stagingGraceSeconds] are skipped so a
         * concurrent [create] call is never interrupted mid-creation.
         */
        fun sweepStale(
            homeDir: Path,
            now: Instant = Instant.now(),
            stagingGraceSeconds: Long = STAGING_GRACE_SECONDS,
            isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
        ): List<String> {
            val fallbackRoot = canonicalizeBase(homeDir).resolve(FALLBACK_ROOT_NAME)
            if (!Files.isDirectory(fallbackRoot)) return emptyList()
            val errors = mutableListOf<String>()
            val children =
                try {
                    Files.list(fallbackRoot).use { it.toList() }
                } catch (e: IOException) {
                    return listOf("could not list $fallbackRoot: ${e.message}")
                } catch (e: UncheckedIOException) {
                    return listOf("could not list $fallbackRoot: ${e.cause?.message ?: e.message}")
                }
            for (child in children) {
                if (!Files.isDirectory(child)) continue
                val name = child.fileName?.toString().orEmpty()
                if (name.startsWith(STAGING_PREFIX)) {
                    val mtime = runCatching { Files.getLastModifiedTime(child).toInstant() }.getOrNull()
                    if (mtime == null || mtime.isAfter(now.minusSeconds(stagingGraceSeconds))) continue
                }
                val pid =
                    runCatching {
                        val pidFile = child.resolve(PID_FILE_NAME)
                        if (Files.isRegularFile(pidFile)) Files.readString(pidFile, Charsets.UTF_8).trim().toLongOrNull() else null
                    }.getOrNull()
                if (pid != null && isPidAlive(pid)) continue
                try {
                    Files.walk(child).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                } catch (e: IOException) {
                    errors += "could not remove stale agy directory $child: ${e.message}"
                } catch (e: UncheckedIOException) {
                    errors += "could not remove stale agy directory $child: ${e.cause?.message ?: e.message}"
                }
            }
            try {
                Files.deleteIfExists(fallbackRoot)
            } catch (ignored: DirectoryNotEmptyException) {
                // Active sessions still exist.
            } catch (ignored: IOException) {
                // Best effort parent cleanup.
            }
            return errors
        }

        /**
         * Creates an isolated directory under [homeDir] (preferring [loopHome] when inside [homeDir])
         * and writes `antigravity-cli/settings.json`.
         */
        fun create(
            homeDir: Path,
            loopHome: Path?,
            projectId: String,
            attemptId: String,
            uniqueId: String =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8),
            ownerPid: Long = ProcessHandle.current().pid(),
        ): AgyIsolationDir {
            val resolvedHome = canonicalizeBase(homeDir)
            val resolvedLoopHome = loopHome?.let(::canonicalizeBase)
            val underLoopHome =
                resolvedLoopHome != null &&
                    resolvedLoopHome.startsWith(resolvedHome) &&
                    resolvedLoopHome != resolvedHome

            val projectSegment = safeSegment(projectId)
            val attemptSegment = safeSegment(attemptId)
            val fallbackParent = if (underLoopHome) null else resolvedHome.resolve(FALLBACK_ROOT_NAME)
            val targetDir =
                if (underLoopHome) {
                    resolvedLoopHome
                        .resolve("attempts")
                        .resolve(projectSegment)
                        .resolve(attemptSegment)
                        .resolve("agy-gemini-$uniqueId")
                } else {
                    fallbackParent!!.resolve("$attemptSegment-$uniqueId")
                }

            val rel = resolvedHome.relativize(targetDir)
            val relativePath = (0 until rel.nameCount).joinToString("/") { rel.getName(it).toString() }
            require(relativePath.isNotEmpty() && !rel.isAbsolute && !relativePath.startsWith("..")) {
                "isolated agy directory must be relative under HOME: $relativePath"
            }

            if (fallbackParent != null) {
                val stagingDir = fallbackParent.resolve("$STAGING_PREFIX$uniqueId-${UUID.randomUUID().toString().take(6)}")
                val settingsPath = stagingDir.resolve(SETTINGS_SUBDIR).resolve(SETTINGS_FILE)
                Files.createDirectories(settingsPath.parent)
                Files.writeString(settingsPath, SETTINGS_JSON, Charsets.UTF_8)
                Files.writeString(stagingDir.resolve(PID_FILE_NAME), ownerPid.toString(), Charsets.UTF_8)
                try {
                    Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE)
                } catch (ignored: AtomicMoveNotSupportedException) {
                    Files.move(stagingDir, targetDir)
                }
            } else {
                val settingsPath = targetDir.resolve(SETTINGS_SUBDIR).resolve(SETTINGS_FILE)
                Files.createDirectories(settingsPath.parent)
                Files.writeString(settingsPath, SETTINGS_JSON, Charsets.UTF_8)
            }
            return AgyIsolationDir(targetDir, relativePath, fallbackParent)
        }

        private fun safeSegment(raw: String): String =
            raw
                .trim()
                .replace(UNSAFE_SEGMENT, "_")
                .trim('.')
                .ifEmpty { "default" }

        /**
         * Resolves symlinks on the deepest existing ancestor of [path] so symlinked temp directories
         * (such as `/var/folders` vs `/private/var/folders` on macOS) compare consistently.
         */
        private fun canonicalizeBase(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            var existing: Path? = normalized
            val tail = mutableListOf<Path>()
            while (existing != null && !Files.exists(existing)) {
                existing.fileName?.let { tail.add(0, it) }
                existing = existing.parent
            }
            var base =
                if (existing != null) {
                    runCatching { existing.toRealPath() }.getOrDefault(existing)
                } else {
                    normalized
                }
            for (part in tail) {
                base = base.resolve(part)
            }
            return base.normalize()
        }
    }
}
