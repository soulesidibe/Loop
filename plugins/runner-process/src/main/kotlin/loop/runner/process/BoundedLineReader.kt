package loop.runner.process

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads [input] line by line as `BufferedReader.readLine` does, a line ending at LF, CR or CRLF and decoded as UTF-8,
 * but keeps at most [maxBytes] bytes of a line (bl-128): a longer line is read to its line break and dropped, so
 * memory stays bounded whatever a process writes. LF and CR never occur inside a UTF-8 sequence, so splitting the
 * bytes is safe. Not thread safe, one reader per stream.
 */
internal class BoundedLineReader(
    private val input: InputStream,
    private val maxBytes: Int,
) {
    /** What [next] read. */
    sealed interface Read {
        /** A line of at most the maximum, without its line break. */
        data class Line(
            val text: String,
        ) : Read

        /** A line of [bytes] bytes, more than the maximum, dropped. */
        data class TooLong(
            val bytes: Long,
        ) : Read
    }

    private val buffer = ByteArray(BUFFER_BYTES)
    private var start = 0
    private var end = 0

    // The last line ended with CR: an LF right after it belongs to that line break.
    private var skipLf = false
    private val line = ByteArrayOutputStream()

    /** The next line, or null at the end of the stream. Blocks; an [java.io.IOException] of the stream propagates. */
    fun next(): Read? {
        line.reset()
        var length = 0L
        while (true) {
            if (start == end) {
                val count = input.read(buffer)
                if (count < 0) return if (length == 0L) null else read(length)
                start = 0
                end = count
                continue
            }
            if (skipLf) {
                skipLf = false
                if (buffer[start] == LF) {
                    start++
                    continue
                }
            }
            var index = start
            while (index < end && buffer[index] != LF && buffer[index] != CR) index++
            val room = maxBytes - line.size()
            if (room > 0) line.write(buffer, start, minOf(room, index - start))
            length += index - start
            if (index < end) {
                skipLf = buffer[index] == CR
                start = index + 1
                return read(length)
            }
            start = end
        }
    }

    private fun read(length: Long): Read = if (length > maxBytes) Read.TooLong(length) else Read.Line(line.toString(Charsets.UTF_8))

    private companion object {
        const val BUFFER_BYTES = 8192
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}
