package loop.core.process

/**
 * Keeps the whole output written to it, up to [max] bytes, for a check's evidence (m2-05). Past [max]
 * the oldest chunks are dropped, so the tail, where a failure usually shows, is what stays. Memory
 * grows with the output, never past about [max] plus one chunk. Thread safe like [OutputTail].
 */
class OutputCapture(
    private val max: Int,
) {
    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0L
    private var total = 0L

    /** True once more than [max] bytes were written, so the start of the output was dropped. */
    @get:Synchronized
    val truncated: Boolean get() = total > max

    @Synchronized
    fun append(
        bytes: ByteArray,
        count: Int,
    ) {
        if (count <= 0) return
        chunks.addLast(bytes.copyOf(count))
        size += count
        total += count
        // Drop whole chunks while what is left still holds at least max bytes.
        while (size - chunks.first().size >= max) size -= chunks.removeFirst().size
    }

    /**
     * The kept bytes as UTF-8, at most [max] bytes. When [truncated], the first partial line is dropped,
     * as [OutputTail.text] does, so a secret cut at the head leaves no fragment; a tail with no newline
     * before its last byte is kept whole.
     */
    @Synchronized
    fun text(): String {
        val all = ByteArray(size.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, all, offset, chunk.size)
            offset += chunk.size
        }
        var start = maxOf(0, all.size - max)
        if (truncated) {
            val newline = (start until all.size - 1).firstOrNull { all[it] == NEWLINE }
            if (newline != null) start = newline + 1
        }
        return String(all, start, all.size - start, Charsets.UTF_8)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
