package loop.cli

import loop.api.ProjectId
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** The human readable run log of [projectId] (m1-39): `<LOOP_HOME>/logs/<project_id>.log`. */
internal fun humanLogFile(
    loopHome: Path,
    projectId: ProjectId,
): Path = loopHome.resolve(LOGS_DIR).resolve("${projectId.value}.log")

/** The ledger's turn lines of [projectId] (m2-19): `<LOOP_HOME>/ledger/<project_id>.jsonl`, one JSON object per line. */
internal fun ledgerFile(
    loopHome: Path,
    projectId: ProjectId,
): Path = loopHome.resolve(LEDGER_DIR).resolve("${projectId.value}.jsonl")

/**
 * Appends each line to [file] (the human log, and the ledger's turn lines since m2-19), creating its folder with mode 0700 and the file with mode 0600, never
 * through a symlink. A failed write is dropped and never thrown: the first one goes to [onFirstFailure],
 * later ones are silent, so a broken log costs the run one warning. Each write opens the file again, so
 * a file removed while the run goes on comes back.
 */
internal class HumanLogFile(
    private val file: Path,
    private val onFirstFailure: (Exception) -> Unit,
) : (String) -> Unit {
    private var failed = false

    @Synchronized
    override fun invoke(line: String) {
        try {
            val folder = file.parent
            if (!Files.isDirectory(folder)) Files.createDirectories(folder, OWNER_ONLY_FOLDER)
            Files.newByteChannel(file, APPEND_OPTIONS, OWNER_ONLY_FILE).use { channel ->
                val bytes = ByteBuffer.wrap("$line\n".toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
        } catch (e: Exception) {
            if (!failed) {
                failed = true
                onFirstFailure(e)
            }
        }
    }

    private companion object {
        val APPEND_OPTIONS: Set<OpenOption> =
            setOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val OWNER_ONLY_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val OWNER_ONLY_FOLDER = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    }
}

private const val LOGS_DIR = "logs"
private const val LEDGER_DIR = "ledger"
