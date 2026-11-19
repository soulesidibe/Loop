package loop.core.process

import java.io.IOException
import java.io.InputStream

/**
 * Keeps the last [max] bytes written to it. Thread safe: one reader thread
 * writes while another takes the [text].
 */
class OutputTail(
    private val max: Int,
) {
    private val buffer = ByteArray(max * 2)
    private var size = 0
    private var total = 0L

    /** True once more than [max] bytes were written, so the start of the output was dropped. */
    @get:Synchronized
    val truncated: Boolean get() = total > max

    @Synchronized
    fun append(
        bytes: ByteArray,
        count: Int,
    ) {
        total += count
        if (count >= max) {
            System.arraycopy(bytes, count - max, buffer, 0, max)
            size = max
            return
        }
        if (size + count > buffer.size) {
            val keep = max - count
            System.arraycopy(buffer, size - keep, buffer, 0, keep)
            size = keep
        }
        System.arraycopy(bytes, 0, buffer, size, count)
        size += count
    }

    /**
     * The kept bytes as UTF-8, at most [max] bytes. When [truncated], the first partial line is
     * dropped, so a secret cut at the head leaves no fragment (m1-11); a tail with no newline
     * before its last byte is kept whole. A character cut at the start is replaced.
     */
    @Synchronized
    fun text(): String {
        var start = maxOf(0, size - max)
        if (truncated) {
            // A newline that ends the tail has no whole line after it: the tail is then kept whole.
            val newline = (start until size - 1).firstOrNull { buffer[it] == NEWLINE }
            if (newline != null) start = newline + 1
        }
        return String(buffer, start, size - start, Charsets.UTF_8)
    }

    /**
     * Reads [input] to its end into this tail, handing each chunk to [also] too; an [IOException]
     * (stream closed by a kill) ends the read.
     */
    fun drain(
        input: InputStream,
        also: (ByteArray, Int) -> Unit = { _, _ -> },
    ) {
        val chunk = ByteArray(CHUNK)
        try {
            input.use {
                while (true) {
                    val read = it.read(chunk)
                    if (read < 0) break
                    append(chunk, read)
                    also(chunk, read)
                }
            }
        } catch (ignored: IOException) {
            // The process was killed and its pipe closed.
        }
    }

    private companion object {
        const val CHUNK = 4096
        const val NEWLINE = '\n'.code.toByte()
    }
}
