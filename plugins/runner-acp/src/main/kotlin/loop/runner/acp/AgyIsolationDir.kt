package loop.runner.acp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
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
        }

    companion object {
        const val FALLBACK_ROOT_NAME: String = ".loop-agy"
        private const val SETTINGS_SUBDIR = "antigravity-cli"
        private const val SETTINGS_FILE = "settings.json"
        private val UNSAFE_SEGMENT = Regex("[^A-Za-z0-9._-]+")

        val SETTINGS_JSON: String =
            buildJsonObject {
                put("toolPermission", "strict")
                put("allowNonWorkspaceAccess", false)
            }.toString()

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

            val settingsPath = targetDir.resolve(SETTINGS_SUBDIR).resolve(SETTINGS_FILE)
            Files.createDirectories(settingsPath.parent)
            Files.writeString(settingsPath, SETTINGS_JSON, Charsets.UTF_8)
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
