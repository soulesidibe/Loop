package loop.core.verify

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The result file of a runner without tools (m2-15, PLAN section 7): `.loop/result.json` in the workspace.
 * Loop takes it once: it reads the text, then deletes the file, so a later turn never submits it again.
 */
object ResultFile {
    const val PATH = ".loop/result.json"

    /** The largest result file Loop reads; a result is a few hundred bytes. */
    const val MAX_BYTES = 1L shl 20

    /**
     * Takes the result file of [workspace]: null when there is none, else its text, or a failure whose message
     * says why it cannot be read (a symlink, not a regular file, larger than [MAX_BYTES]). The file is deleted in
     * every case it exists, never through a symlink; a failed delete is a failure too, so a file Loop cannot take
     * once is never taken twice in silence.
     */
    fun take(workspace: Path): Result<String>? {
        val file = workspace.resolve(PATH)
        val attributes =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return null
            }
        val read =
            when {
                attributes.isSymbolicLink -> Result.failure(SubmittedResult.Invalid("$PATH is a symlink"))
                !attributes.isRegularFile -> Result.failure(SubmittedResult.Invalid("$PATH is not a regular file"))
                attributes.size() > MAX_BYTES -> Result.failure(SubmittedResult.Invalid("$PATH is larger than 1 MiB"))
                else -> runCatching { readBounded(file) }
            }
        return try {
            if (!attributes.isDirectory) Files.deleteIfExists(file)
            read
        } catch (e: Exception) {
            Result.failure(SubmittedResult.Invalid("cannot remove $PATH: ${e.message ?: e::class.simpleName}"))
        }
    }

    /**
     * Removes a result file left in [workspace] by an earlier turn, never through a symlink, without reading it:
     * true when one was removed, false when there is none or the path is a folder, which [take] reports as invalid.
     * A failed delete throws.
     */
    fun discard(workspace: Path): Boolean {
        val file = workspace.resolve(PATH)
        val attributes =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return false
            }
        if (attributes.isDirectory) return false
        return Files.deleteIfExists(file)
    }

    private fun readBounded(file: Path): String {
        val bytes =
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                input.readNBytes((MAX_BYTES + 1).toInt())
            }
        if (bytes.size > MAX_BYTES) throw SubmittedResult.Invalid("$PATH is larger than 1 MiB")
        return bytes.toString(Charsets.UTF_8)
    }
}
