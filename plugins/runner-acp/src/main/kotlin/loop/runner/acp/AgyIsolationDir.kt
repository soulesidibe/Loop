package loop.runner.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID

/**
 * Isolated `--gemini-dir` directory under `$HOME` for `preset: agy` with `strict_mcp: true`.
 *
 * `agy acp --gemini-dir` requires a path relative to `$HOME` (absolute paths fail with `must not be
 * absolute`). When `LOOP_HOME` lies under `$HOME`, the directory is `$LOOP_HOME/agy/<attempt>-<id>`; otherwise it is
 * `$HOME/.loop-agy/<attempt>-<id>`. Neither lies in the attempt directory, which Loop deletes when the attempt ends.
 * It contains `antigravity-cli/settings.json` with `{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`.
 *
 * The folder holds `agy`'s session store, so since bl-213 it outlives its session: once the session reported its
 * runtime session id ([recordSession]), [keep] marks it kept when the session stops, and a respawned `agy acp` or a
 * later attempt of the same issue ([findKept]) can `session/load` that id. A kept folder is removed when a newer session
 * of its issue is kept ([removeKept], since bl-230 at that session's stop, never when a session opens), when its
 * workspace is gone, which Loop does when the issue reaches a terminal state, or when its owner process is dead
 * ([sweepStale], at the runner's start and at every launch and resume). A folder whose session never reported an id
 * is deleted when the session stops.
 *
 * Directories are populated under a hidden `.tmp-<id>` staging folder and atomically moved into place after writing
 * both `settings.json` and `.loop-pid`, so a concurrent [sweepStale] never races with directory creation. The root
 * folders are kept in place across sessions so concurrent spawns never race with their removal.
 */
internal class AgyIsolationDir private constructor(
    /** Absolute path of the created directory on disk. */
    val path: Path,
    /** Path relative to `$HOME`, passed to `--gemini-dir`. */
    val relativePath: String,
) {
    /** Who a folder serves: the issue a later attempt resumes, and the workspace whose removal ends the folder. */
    data class Owner(
        val projectId: String,
        val issueId: String,
        val workspace: Path,
    )

    /** Recursively removes [path]. Tolerates concurrent removal (`NoSuchFileException`). Safe to call twice. */
    fun delete(): String? = deleteTree(path, "could not remove isolated agy directory $path")

    /** Records that the live session of [owner] in this folder is [runtimeSessionId], the id a `session/load` takes. */
    fun recordSession(
        owner: Owner,
        runtimeSessionId: String,
    ) = writeRecord(path, Record(owner.projectId, owner.issueId, owner.workspace.toString(), runtimeSessionId, STATE_LIVE))

    /**
     * Marks the folder kept when its session stops, so a later session can load it. False when its session reported no
     * runtime session id, or the record cannot be written: the folder is then of no use and the caller deletes it.
     */
    fun keep(): Boolean {
        val record = readRecord(path) ?: return false
        return runCatching { writeRecord(path, record.copy(state = STATE_KEPT)) }.isSuccess
    }

    /**
     * Checks the folder before `agy` loads a session from it, then rewrites `antigravity-cli/settings.json` with the
     * strict settings. Returns why the folder is refused, or null. Fails closed on anything `agy` and Loop do not write
     * there: its root, `config/` and `antigravity-cli/` may each hold only the entries of their allow list, each of its
     * type ([ROOT_ENTRIES], [CONFIG_ENTRIES], [SETTINGS_ENTRIES]); `antigravity-cli/mcp/` only the cache of Loop's own
     * MCP server; `config/mcp_config.json` no MCP server, since `agy` starts those at launch, outside the permission
     * policy; and nothing under `config/` and `antigravity-cli/` may be a symbolic link. So `config/hooks.json`, which
     * `agy` would run, is refused.
     */
    fun checkBeforeLoad(): String? {
        val refusal =
            checkEntries(path, "", ROOT_ENTRIES)
                ?: checkEntries(path.resolve(CONFIG_SUBDIR), "$CONFIG_SUBDIR/", CONFIG_ENTRIES)
                ?: checkEntries(path.resolve(SETTINGS_SUBDIR), "$SETTINGS_SUBDIR/", SETTINGS_ENTRIES)
                ?: checkEntries(
                    path.resolve(SETTINGS_SUBDIR).resolve(MCP_CACHE_SUBDIR),
                    "$SETTINGS_SUBDIR/$MCP_CACHE_SUBDIR/",
                    MCP_CACHE_ENTRIES,
                )
                ?: checkNoLinks(CONFIG_SUBDIR)
                ?: checkNoLinks(SETTINGS_SUBDIR)
                ?: checkMcpConfig()
        if (refusal != null) return refusal
        return try {
            val settings = path.resolve(SETTINGS_SUBDIR).resolve(SETTINGS_FILE)
            Files.createDirectories(settings.parent)
            Files.deleteIfExists(settings)
            Files.writeString(settings, SETTINGS_JSON, Charsets.UTF_8)
            null
        } catch (e: IOException) {
            "the strict settings cannot be written to the isolated agy folder $path: ${e.message}"
        }
    }

    /**
     * Checks that every entry of [dir], shown as [prefix] plus its name, is in [allowed] with its type, a directory when
     * true, a regular file when false, never a symbolic link. A missing [dir] holds nothing.
     */
    private fun checkEntries(
        dir: Path,
        prefix: String,
        allowed: Map<String, Boolean>,
    ): String? {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return null
        val entries =
            try {
                Files.list(dir).use { stream -> stream.toList() }
            } catch (e: IOException) {
                return "the isolated agy folder $path cannot be listed: ${e.message}"
            } catch (e: UncheckedIOException) {
                return "the isolated agy folder $path cannot be listed: ${e.cause?.message ?: e.message}"
            }
        for (entry in entries.sortedBy { it.fileName.toString() }) {
            val name = entry.fileName.toString()
            val isDirectory = allowed[name] ?: return unexpected("$prefix$name")
            val expected =
                if (isDirectory) {
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                } else {
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                }
            if (!expected) {
                return if (Files.isSymbolicLink(entry)) {
                    unexpected("$prefix$name as a symbolic link")
                } else {
                    unexpected("$prefix$name of another type")
                }
            }
        }
        return null
    }

    /** Refuses a symbolic link at any depth under the folder's [subdir], such as one in a transcript folder. */
    private fun checkNoLinks(subdir: String): String? {
        val root = path.resolve(subdir)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isSymbolicLink(it) }
                    .findFirst()
                    .orElse(null)
                    ?.let { link -> unexpected("${relativeTo(path, link)} as a symbolic link") }
            }
        } catch (e: IOException) {
            "the isolated agy folder $path cannot be listed: ${e.message}"
        } catch (e: UncheckedIOException) {
            "the isolated agy folder $path cannot be listed: ${e.cause?.message ?: e.message}"
        }
    }

    /**
     * Refuses a `config/mcp_config.json` that names an MCP server: `agy` writes it empty, and under `strict_mcp` Loop's
     * own server comes only from `session/new` and `session/load`. A file that is not a JSON object is refused too.
     */
    private fun checkMcpConfig(): String? {
        val file = path.resolve(CONFIG_SUBDIR).resolve(MCP_CONFIG_FILE)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val text =
            try {
                Files.readString(file, Charsets.UTF_8)
            } catch (e: IOException) {
                return "the isolated agy folder $path cannot be read: ${e.message}"
            }
        if (text.isBlank()) return null
        val servers =
            runCatching { (AcpFraming.json.parseToJsonElement(text) as JsonObject)[MCP_SERVERS_KEY] }
                .getOrElse { return unexpected("$CONFIG_SUBDIR/$MCP_CONFIG_FILE that is not a JSON object") }
        val empty = servers == null || servers is JsonNull || (servers as? JsonObject)?.isEmpty() == true
        return if (empty) null else unexpected("$CONFIG_SUBDIR/$MCP_CONFIG_FILE naming an MCP server")
    }

    /** What a folder's [RECORD_FILE_NAME] holds. */
    @Serializable
    private data class Record(
        val projectId: String,
        val issueId: String,
        val workspace: String,
        val runtimeSessionId: String,
        val state: String,
    )

    companion object {
        const val FALLBACK_ROOT_NAME: String = ".loop-agy"

        /** The root folder under `LOOP_HOME`, beside `attempts/`. */
        const val LOOP_HOME_ROOT_NAME: String = "agy"
        private const val STAGING_PREFIX = ".tmp-"
        private const val STAGING_GRACE_SECONDS = 60L
        private const val MAX_CREATE_ATTEMPTS = 3
        private const val PID_FILE_NAME = ".loop-pid"
        private const val RECORD_FILE_NAME = ".loop-session.json"
        private const val SETTINGS_SUBDIR = "antigravity-cli"
        private const val CONFIG_SUBDIR = "config"
        private const val SETTINGS_FILE = "settings.json"
        private const val MCP_CONFIG_FILE = "mcp_config.json"
        private const val MCP_SERVERS_KEY = "mcpServers"
        private const val MCP_CACHE_SUBDIR = "mcp"
        private const val STATE_LIVE = "live"
        private const val STATE_KEPT = "kept"
        private val UNSAFE_SEGMENT = Regex("[^A-Za-z0-9._-]+")

        /** What the folder's root may hold, each name to whether it is a directory: what `agy` and Loop write. */
        private val ROOT_ENTRIES: Map<String, Boolean> =
            mapOf(SETTINGS_SUBDIR to true, CONFIG_SUBDIR to true, PID_FILE_NAME to false, RECORD_FILE_NAME to false)

        /**
         * What `config/` may hold: the files and folder `agy` 1.5.18 wrote there in a real session (bl-230), its plugin
         * switches (`config.json`), an empty `mcp_config.json`, its migration marker and an empty `projects/`. Not
         * `hooks.json`, `skills.json`, `GEMINI.md`, `plugins/`, `skills/` or `sidecars/`, which `agy` loads from its
         * global config but never writes in an isolated folder.
         */
        private val CONFIG_ENTRIES: Map<String, Boolean> =
            mapOf("config.json" to false, MCP_CONFIG_FILE to false, ".migrated" to false, "projects" to true)

        /**
         * What `antigravity-cli/` may hold: `agy`'s session store and the state it keeps beside it. The entries a real
         * `agy acp` 1.5.18 session wrote (bl-230): its transcripts (`brain/`, `conversations/`, `annotations/`), its
         * summaries database, its logs, caches, built in skills and plugins (`builtin/`), its `agentapi` launcher
         * (`bin/`), its daemon, terminal and crash folders, its state files, and `settings.json`, which Loop rewrites
         * before every load. Also what its store grows in a longer session, the database's write ahead files, `implicit/`,
         * `knowledge/`, `presence/`, `updater/`, `last_check.timestamp`, and `mcp/`, the tool cache of the session's MCP
         * servers, checked apart. Not what only the interactive `agy` writes: `keybindings.json`, `history.jsonl`,
         * `cli.log` and `plugin_data/`.
         */
        private val SETTINGS_ENTRIES: Map<String, Boolean> =
            mapOf(
                SETTINGS_FILE to false,
                "annotations" to true,
                "bin" to true,
                "brain" to true,
                "builtin" to true,
                "cache" to true,
                "conversations" to true,
                "crashes" to true,
                "daemon" to true,
                "implicit" to true,
                "knowledge" to true,
                "log" to true,
                MCP_CACHE_SUBDIR to true,
                "persistent_terminals" to true,
                "presence" to true,
                "updater" to true,
                "conversation_summaries.db" to false,
                "conversation_summaries.db-journal" to false,
                "conversation_summaries.db-shm" to false,
                "conversation_summaries.db-wal" to false,
                "installation_id" to false,
                "jetbox_summaries_proto.pb" to false,
                "jetski_state.pbtxt" to false,
                "last_check.timestamp" to false,
            )

        /** What `antigravity-cli/mcp/` may hold: the tool cache of Loop's own server, the only one under `strict_mcp`. */
        private val MCP_CACHE_ENTRIES: Map<String, Boolean> = mapOf("loop" to true)

        val SETTINGS_JSON: String =
            buildJsonObject {
                put("toolPermission", "strict")
                put("allowNonWorkspaceAccess", false)
            }.toString()

        /**
         * Removes stale directories under the roots ([roots]): any directory whose `.loop-pid` file is missing or names
         * a PID that is no longer alive, and any kept directory whose recorded workspace is gone. Staging directories
         * (`.tmp-*`) younger than [stagingGraceSeconds] are skipped so a concurrent [create] call is never interrupted
         * mid-creation; staging directories older than [stagingGraceSeconds] are removed regardless of their PID so a
         * crashed create in a live JVM cannot leak indefinitely. Leaves the roots themselves in place.
         */
        fun sweepStale(
            homeDir: Path,
            loopHome: Path? = null,
            now: Instant = Instant.now(),
            stagingGraceSeconds: Long = STAGING_GRACE_SECONDS,
            isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
        ): List<String> {
            val errors = mutableListOf<String>()
            for (root in roots(homeDir, loopHome)) {
                val children = listChildren(root, errors) ?: continue
                for (child in children) {
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                    val name = child.fileName?.toString().orEmpty()
                    if (name.startsWith(STAGING_PREFIX)) {
                        val mtime = runCatching { Files.getLastModifiedTime(child).toInstant() }.getOrNull()
                        if (mtime == null || mtime.isAfter(now.minusSeconds(stagingGraceSeconds))) continue
                        deleteTree(child, "could not remove stale agy staging directory $child")?.let { errors += it }
                        continue
                    }
                    val pid = readPid(child)
                    if (pid != null && isPidAlive(pid)) {
                        val record = readRecord(child)
                        val workspaceGone =
                            record?.state == STATE_KEPT &&
                                runCatching { !Files.exists(Path.of(record.workspace)) }.getOrDefault(false)
                        if (!workspaceGone) continue
                    }
                    deleteTree(child, "could not remove stale agy directory $child")?.let { errors += it }
                }
            }
            return errors
        }

        /**
         * The kept folder of [runtimeSessionId] for [issueId] of [projectId], claimed as live for [ownerPid], or null
         * when none is kept. Loop resumes only within one run, so the folder's owner is this process.
         */
        fun findKept(
            homeDir: Path,
            loopHome: Path?,
            projectId: String,
            issueId: String,
            runtimeSessionId: String,
            ownerPid: Long = ProcessHandle.current().pid(),
        ): AgyIsolationDir? {
            val resolvedHome = canonicalizeBase(homeDir)
            for (root in roots(homeDir, loopHome)) {
                val children = listChildren(root, mutableListOf()) ?: continue
                for (child in children) {
                    if (child.fileName.toString().startsWith(STAGING_PREFIX)) continue
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                    val record = readRecord(child) ?: continue
                    if (record.state != STATE_KEPT || record.runtimeSessionId != runtimeSessionId) continue
                    if (record.projectId != projectId || record.issueId != issueId) continue
                    // The pid file is replaced in one move, so a concurrent sweep never sees the folder without one.
                    val claimed =
                        runCatching {
                            writeAtomically(child, PID_FILE_NAME, ownerPid.toString())
                            writeRecord(child, record.copy(state = STATE_LIVE))
                        }
                    if (claimed.isFailure) return null
                    return AgyIsolationDir(child, relativeTo(resolvedHome, child))
                }
            }
            return null
        }

        /**
         * Removes every kept folder of [issueId] of [projectId] but [except]: the session of [except] stopped and was
         * kept, so it is now the last session of the issue, and Loop only ever resumes the last one, so the others can
         * never be loaded again. A session that opens removes nothing, so a folder a hold resume still needs survives
         * any session of the issue until a newer one is kept. Returns the removal failures.
         */
        fun removeKept(
            homeDir: Path,
            loopHome: Path?,
            projectId: String,
            issueId: String,
            except: Path? = null,
        ): List<String> {
            val errors = mutableListOf<String>()
            for (root in roots(homeDir, loopHome)) {
                val children = listChildren(root, errors) ?: continue
                for (child in children) {
                    if (child.fileName.toString().startsWith(STAGING_PREFIX)) continue
                    if (except != null && child == except) continue
                    val record = readRecord(child) ?: continue
                    if (record.state != STATE_KEPT || record.projectId != projectId || record.issueId != issueId) continue
                    deleteTree(child, "could not remove superseded agy directory $child")?.let { errors += it }
                }
            }
            return errors
        }

        /**
         * Creates an isolated directory under [homeDir] (under [loopHome] when inside [homeDir]) and writes
         * `antigravity-cli/settings.json` and `.loop-pid` with [ownerPid].
         */
        fun create(
            homeDir: Path,
            loopHome: Path?,
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
            val parent = creationRoot(resolvedHome, loopHome)
            val targetDir = parent.resolve("${safeSegment(attemptId)}-$uniqueId")
            val relativePath = relativeTo(resolvedHome, targetDir)
            require(relativePath.isNotEmpty() && !relativePath.startsWith("..")) {
                "isolated agy directory must be relative under HOME: $relativePath"
            }

            var lastNoSuchFile: NoSuchFileException? = null
            for (attempt in 1..MAX_CREATE_ATTEMPTS) {
                val stagingDir = parent.resolve("$STAGING_PREFIX$uniqueId-${UUID.randomUUID().toString().take(8)}")
                var moved = false
                try {
                    val settingsPath = stagingDir.resolve(SETTINGS_SUBDIR).resolve(SETTINGS_FILE)
                    Files.createDirectories(settingsPath.parent)
                    Files.writeString(settingsPath, SETTINGS_JSON, Charsets.UTF_8)
                    Files.writeString(stagingDir.resolve(PID_FILE_NAME), ownerPid.toString(), Charsets.UTF_8)
                    try {
                        Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE)
                    } catch (ignored: AtomicMoveNotSupportedException) {
                        Files.move(stagingDir, targetDir)
                    }
                    moved = true
                    return AgyIsolationDir(targetDir, relativePath)
                } catch (e: NoSuchFileException) {
                    lastNoSuchFile = e
                    if (!moved) cleanupStaging(stagingDir, e)
                } catch (t: Throwable) {
                    if (!moved) cleanupStaging(stagingDir, t)
                    throw t
                }
            }
            throw checkNotNull(lastNoSuchFile)
        }

        /** `$LOOP_HOME/agy` when `LOOP_HOME` lies under (and is not) `$HOME`, else `$HOME/.loop-agy`. */
        private fun creationRoot(
            resolvedHome: Path,
            loopHome: Path?,
        ): Path {
            val resolvedLoopHome = loopHome?.let(::canonicalizeBase)
            return if (resolvedLoopHome != null && resolvedLoopHome.startsWith(resolvedHome) && resolvedLoopHome != resolvedHome) {
                resolvedLoopHome.resolve(LOOP_HOME_ROOT_NAME)
            } else {
                resolvedHome.resolve(FALLBACK_ROOT_NAME)
            }
        }

        /** Every root a folder may live in: `$HOME/.loop-agy`, and `$LOOP_HOME/agy` when `LOOP_HOME` is under `$HOME`. */
        private fun roots(
            homeDir: Path,
            loopHome: Path?,
        ): List<Path> {
            val resolvedHome = canonicalizeBase(homeDir)
            return listOf(resolvedHome.resolve(FALLBACK_ROOT_NAME), creationRoot(resolvedHome, loopHome)).distinct()
        }

        private fun listChildren(
            root: Path,
            errors: MutableList<String>,
        ): List<Path>? {
            if (!Files.isDirectory(root)) return null
            return try {
                Files.list(root).use { it.toList() }
            } catch (ignored: NoSuchFileException) {
                null
            } catch (e: IOException) {
                errors += "could not list $root: ${e.message}"
                null
            } catch (e: UncheckedIOException) {
                if (e.cause !is NoSuchFileException) errors += "could not list $root: ${e.cause?.message ?: e.message}"
                null
            }
        }

        private fun readPid(dir: Path): Long? =
            runCatching {
                val pidFile = dir.resolve(PID_FILE_NAME)
                if (Files.isRegularFile(pidFile, LinkOption.NOFOLLOW_LINKS)) {
                    Files.readString(pidFile, Charsets.UTF_8).trim().toLongOrNull()
                } else {
                    null
                }
            }.getOrNull()

        private fun readRecord(dir: Path): Record? =
            runCatching {
                val file = dir.resolve(RECORD_FILE_NAME)
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
                AcpFraming.json.decodeFromString(Record.serializer(), Files.readString(file, Charsets.UTF_8))
            }.getOrNull()

        /** Writes [record] beside a temporary file and moves it over [RECORD_FILE_NAME], so a reader never sees half of it. */
        private fun writeRecord(
            dir: Path,
            record: Record,
        ) = writeAtomically(dir, RECORD_FILE_NAME, AcpFraming.json.encodeToString(Record.serializer(), record))

        /** Writes [text] to a temporary file in [dir] and moves it over [name] in one step, replacing any file there. */
        private fun writeAtomically(
            dir: Path,
            name: String,
            text: String,
        ) {
            val file = dir.resolve(name)
            val temp = dir.resolve("$name.${UUID.randomUUID().toString().take(8)}")
            try {
                Files.writeString(temp, text, Charsets.UTF_8)
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (ignored: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        private fun unexpected(what: String): String = "the isolated agy folder holds $what, which Loop did not expect"

        private fun relativeTo(
            resolvedHome: Path,
            target: Path,
        ): String {
            val rel = resolvedHome.relativize(target)
            return (0 until rel.nameCount).joinToString("/") { rel.getName(it).toString() }
        }

        private fun cleanupStaging(
            stagingDir: Path,
            primary: Throwable,
        ) {
            try {
                if (Files.exists(stagingDir)) {
                    Files.walk(stagingDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            } catch (ignored: NoSuchFileException) {
                // Already removed.
            } catch (e: UncheckedIOException) {
                if (e.cause !is NoSuchFileException) primary.addSuppressed(e)
            } catch (t: Throwable) {
                primary.addSuppressed(t)
            }
        }

        private fun deleteTree(
            dir: Path,
            prefix: String,
        ): String? =
            try {
                if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(dir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
                null
            } catch (ignored: NoSuchFileException) {
                null
            } catch (e: IOException) {
                "$prefix: ${e.message}"
            } catch (e: UncheckedIOException) {
                if (e.cause is NoSuchFileException) null else "$prefix: ${e.cause?.message ?: e.message}"
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
