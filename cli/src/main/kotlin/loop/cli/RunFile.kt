package loop.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import loop.api.ProjectId
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/** What a live `loop run` writes in its run file (m1-09), so `loop stop` can find and check it. */
@Serializable
internal data class RunRecord(
    val pid: Long,
    /** The process start instant, ISO 8601, null when the platform does not tell; guards against pid reuse. */
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("run_id") val runId: String,
    val workflow: String,
) {
    /** True unless both start instants, the recorded one and [handle]'s, are known and differ. */
    fun startMatches(handle: ProcessHandle): Boolean {
        val recorded = startedAt ?: return true
        return handle
            .info()
            .startInstant()
            .map { it.toString() == recorded }
            .orElse(true)
    }

    companion object {
        /** The record of the current process for [runId] and [workflow]. */
        fun ofCurrentProcess(
            runId: String,
            workflow: Path,
        ): RunRecord {
            val self = ProcessHandle.current()
            return RunRecord(
                pid = self.pid(),
                startedAt =
                    self
                        .info()
                        .startInstant()
                        .map { it.toString() }
                        .orElse(null),
                runId = runId,
                workflow = workflow.toString(),
            )
        }
    }
}

/**
 * The files of one project's run under `<loopHome>/runs/` (m1-09, ADR 0001 one process per
 * project): the run file, locked by the live run for its whole life, and the stop marker
 * `loop stop` creates before its SIGTERM, so the run can say its attempts ended on a stop request.
 */
internal class RunFiles(
    loopHome: Path,
    projectId: ProjectId,
) {
    val dir: Path = dirOf(loopHome)
    val runFile: Path = dir.resolve("${projectId.value}.json")
    val stopMarker: Path = dir.resolve("${projectId.value}.stop")

    /** True while a `loop stop` asked this run to stop. */
    fun stopRequested(): Boolean = Files.exists(stopMarker)

    fun readRecord(): Result<RunRecord> = runCatching { JSON.decodeFromString(RunRecord.serializer(), Files.readString(runFile)) }

    /** How [acquire] went: the run holds the run file, or another live run does. */
    sealed interface Acquired {
        data class Held(
            val lock: RunLock,
        ) : Acquired

        /** Another process holds the lock; [record] is null when its run file cannot be read yet. */
        data class Busy(
            val record: RunRecord?,
        ) : Acquired
    }

    /**
     * Takes the run file for [record]: a failed `tryLock` means another live run of this project.
     * The file is only truncated once the lock is held, so a refused run never wipes the live one's
     * record. A stop marker left by an earlier run is removed, so it cannot name this run's end.
     */
    fun acquire(record: RunRecord): Acquired {
        Files.createDirectories(dir)
        repeat(ACQUIRE_TRIES - 1) { tryAcquire(record)?.let { return it } }
        return tryAcquire(record) ?: throw IOException("the run file kept being replaced while Loop locked it")
    }

    /**
     * One [acquire] try, null when the path may no longer name the locked file: a `loop stop` removed
     * it as stale between the open and the lock, so the lock would be on an orphan and the try is
     * redone. The check compares file keys by `stat` only: opening and closing a second descriptor
     * of the file in this process could drop the POSIX lock.
     */
    private fun tryAcquire(record: RunRecord): Acquired? {
        val before = fileKey()
        val channel = FileChannel.open(runFile, CREATE, READ, WRITE)
        try {
            val opened = fileKey()
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                return Acquired.Busy(readRecord().getOrNull())
            }
            val locked = fileKey()
            if (locked == null || locked != opened || (before != null && before != locked)) {
                channel.close()
                return null
            }
            Files.deleteIfExists(stopMarker)
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(JSON.encodeToString(RunRecord.serializer(), record).toByteArray()), 0)
            channel.force(true)
            return Acquired.Held(RunLock(this, channel))
        } catch (e: IOException) {
            channel.close()
            throw e
        }
    }

    /** The file key (device and inode) the run file path names now, null when there is no file. */
    private fun fileKey(): Any? =
        try {
            Files.readAttributes(runFile, BasicFileAttributes::class.java).fileKey()
        } catch (e: NoSuchFileException) {
            null
        }

    /** What `loop stop` found: no run file, a stale one (now removed), a live run, or a run file it cannot read. */
    sealed interface Probe {
        data object Nothing : Probe

        /** [record] is null when the stale file could not be read. */
        data class Stale(
            val record: RunRecord?,
        ) : Probe

        data class Live(
            val record: RunRecord,
        ) : Probe

        data class Unreadable(
            val error: Throwable,
        ) : Probe
    }

    /**
     * Looks at the run file without disturbing a live run: when its own `tryLock` succeeds no
     * process holds the file (the OS drops the lock of a crashed run), so the file is stale and
     * is removed with any stop marker.
     */
    fun probe(): Probe {
        val channel =
            try {
                FileChannel.open(runFile, READ, WRITE)
            } catch (e: NoSuchFileException) {
                return Probe.Nothing
            }
        channel.use {
            val lock = channel.tryLock()
            if (lock != null) {
                val record = readRecord()
                Files.deleteIfExists(runFile)
                Files.deleteIfExists(stopMarker)
                return Probe.Stale(record.getOrNull())
            }
            // A live run truncates then writes its record: an empty file may be that window, so read once more.
            val record =
                readRecord().recoverCatching { error ->
                    if (Files.size(runFile) > 0) throw error
                    Thread.sleep(EMPTY_RETRY_MS)
                    readRecord().getOrThrow()
                }
            return record.fold({ Probe.Live(it) }, { Probe.Unreadable(it) })
        }
    }

    /** What `loop status` found without the lock: no run file, a live run, a run file whose process is gone, or an unreadable one. */
    sealed interface Peek {
        data object Nothing : Peek

        data class Live(
            val record: RunRecord,
        ) : Peek

        data class Gone(
            val record: RunRecord,
        ) : Peek

        data class Unreadable(
            val error: Throwable,
        ) : Peek
    }

    /**
     * Looks at the run file without locking or changing it (m1-14): the run is live when the process the
     * record names is alive and started when the record says. Unlike [probe], a stale file is left in place,
     * and a `loop run` starting at the same moment never finds the file locked by this look.
     */
    fun peek(): Peek {
        if (!Files.exists(runFile)) return Peek.Nothing
        // A live run truncates then writes its record: an empty or half written file may be that window, so read once more.
        val record =
            readRecord().recoverCatching {
                Thread.sleep(EMPTY_RETRY_MS)
                readRecord().getOrThrow()
            }
        val found =
            record.getOrElse { error ->
                return if (error is NoSuchFileException || !Files.exists(runFile)) Peek.Nothing else Peek.Unreadable(error)
            }
        val live =
            ProcessHandle
                .of(found.pid)
                .filter { it.isAlive && found.startMatches(it) }
                .isPresent
        return if (live) Peek.Live(found) else Peek.Gone(found)
    }

    companion object {
        /** The run folder under [loopHome], `runs/`. */
        fun dirOf(loopHome: Path): Path = loopHome.resolve(RUNS_DIR)

        /**
         * The run files of every project under [loopHome], one per `runs/<projectId>.json`, in name
         * order (m1-12). Stop markers and other files are skipped; no `runs/` folder means none.
         */
        fun all(loopHome: Path): List<RunFiles> {
            val dir = dirOf(loopHome)
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.list(dir).use { paths ->
                paths
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(RUN_FILE_SUFFIX) && it.length > RUN_FILE_SUFFIX.length }
                    .sorted()
                    .map { RunFiles(loopHome, ProjectId(it.removeSuffix(RUN_FILE_SUFFIX))) }
                    .toList()
            }
        }

        private const val RUN_FILE_SUFFIX = ".json"
        private const val RUNS_DIR = "runs"
        private const val ACQUIRE_TRIES = 3
        private const val EMPTY_RETRY_MS = 100L
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * An I/O failure as the end of a sentence: a [FileSystemException] message is often the bare path,
 * so its file and reason are spelled out.
 */
internal fun IOException.ioSentence(): String =
    when (this) {
        is FileAlreadyExistsException -> "$file already exists and is not a folder"
        is FileSystemException -> listOfNotNull(file, reason ?: this::class.simpleName).joinToString(": ")
        else -> sentence()
    }

/** Why the run's attempts were canceled: `loop stop` created the stop marker, or another signal came. */
internal const val STOP_REQUESTED = "stop_requested"
internal const val SIGNAL = "signal"
internal const val INTERNAL_ERROR = "internal_error"

/** The run file held by the live run; [release] deletes it and the stop marker, then drops the lock. */
internal class RunLock(
    private val files: RunFiles,
    private val channel: FileChannel,
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        try {
            Files.deleteIfExists(files.runFile)
            Files.deleteIfExists(files.stopMarker)
        } finally {
            // Closing the channel releases the lock.
            channel.close()
        }
    }
}
