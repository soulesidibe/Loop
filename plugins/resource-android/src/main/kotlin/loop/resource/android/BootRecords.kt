package loop.resource.android

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

/**
 * One emulator the provider booted (m4-28): the [avd] it runs on [serial], `emulator-<port>`, the [pid] of the
 * `emulator` process, 0 until it started, the [owner], the process of Loop that booted it, and the [attempt] whose
 * prepare booted it (bl-208), empty in a record written before.
 */
internal data class BootRecord(
    val avd: String,
    val serial: String,
    val pid: Long,
    val owner: String,
    val attempt: String = "",
)

/**
 * The emulators the provider booted, one file per AVD in `<dir>/booted/`, kept on disk so a later process of Loop
 * finds what a crashed one left, and shared by every process of the machine. [exclusive] holds a lock of the folder,
 * across threads and processes, around the choice of a port and the write of its record, so two boots never take one port.
 */
internal class BootRecords(
    private val dir: Path,
) {
    private val booted: Path = dir.resolve("booted")

    fun all(): List<BootRecord> =
        if (!booted.exists()) emptyList() else booted.listDirectoryEntries().filter { it.extension == "properties" }.mapNotNull(::read)

    fun of(avd: String): BootRecord? = file(avd).takeIf { it.exists() }?.let(::read)?.takeIf { it.avd == avd }

    fun write(record: BootRecord) {
        Files.createDirectories(booted)
        val properties =
            Properties().apply {
                setProperty("avd", record.avd)
                setProperty("serial", record.serial)
                setProperty("pid", record.pid.toString())
                setProperty("owner", record.owner)
                setProperty("attempt", record.attempt)
            }
        val temp = Files.createTempFile(booted, "record-", ".tmp")
        temp.outputStream().use { properties.store(it, null) }
        Files.move(temp, file(record.avd), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(avd: String) {
        file(avd).deleteIfExists()
    }

    fun <T> exclusive(block: () -> T): T =
        THREADS.withLock {
            Files.createDirectories(dir)
            FileChannel.open(dir.resolve("booted.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }

    /** The file of [avd]: its name made safe for a file name. */
    private fun file(avd: String): Path = booted.resolve(safeName(avd) + ".properties")

    private fun read(file: Path): BootRecord? =
        runCatching {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            BootRecord(
                avd = properties.getProperty("avd") ?: return null,
                serial = properties.getProperty("serial") ?: return null,
                pid = properties.getProperty("pid")?.toLongOrNull() ?: 0,
                owner = properties.getProperty("owner").orEmpty(),
                attempt = properties.getProperty("attempt").orEmpty(),
            )
        }.getOrNull()

    companion object {
        /** A file lock is held per process, so the threads of one process take this lock first. */
        private val THREADS = ReentrantLock()

        /** [name] with every character but letters, digits, dot, dash and underscore made an underscore. */
        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
