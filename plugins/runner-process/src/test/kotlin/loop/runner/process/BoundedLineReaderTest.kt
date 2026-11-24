package loop.runner.process

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** bl-128: lines are read like `BufferedReader.readLine`, but a line longer than the maximum is dropped, never buffered whole. */
class BoundedLineReaderTest {
    private fun readAll(
        input: InputStream,
        maxBytes: Int,
    ): List<BoundedLineReader.Read> {
        val reader = BoundedLineReader(input, maxBytes)
        return generateSequence { reader.next() }.toList()
    }

    private fun readAll(
        text: String,
        maxBytes: Int = 64,
    ): List<BoundedLineReader.Read> = readAll(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), maxBytes)

    private fun lines(vararg texts: String): List<BoundedLineReader.Read> = texts.map { BoundedLineReader.Read.Line(it) }

    @Test
    fun `lines end at LF, CR or CRLF, as readLine ends them, and a last line needs no line break`() {
        assertEquals(lines("a", "b", "c", "", "d"), readAll("a\nb\rc\r\n\nd"))
        assertEquals(lines("a"), readAll("a\n"))
        assertEquals(emptyList(), readAll(""))
    }

    @Test
    fun `utf-8 text is decoded whole, also when the line spans several reads`() {
        val text = "é".repeat(40_000) + "\n" + "日本\n"

        val read = readAll(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 1_000_000)

        assertEquals(lines("é".repeat(40_000), "日本"), read)
    }

    @Test
    fun `a CRLF split between two reads makes one line break`() {
        val read = readAll(OneByteAtATime("a\r\nb\n".toByteArray()), 64)

        assertEquals(lines("a", "b"), read)
    }

    @Test
    fun `a line of exactly the maximum is kept, one byte more is dropped with its size, and reading goes on`() {
        val max = 16
        val text = "x".repeat(max) + "\n" + "y".repeat(max + 1) + "\n" + "after\n" + "z".repeat(max * 1000)

        val read = readAll(text, max)

        assertEquals(
            listOf(
                BoundedLineReader.Read.Line("x".repeat(max)),
                BoundedLineReader.Read.TooLong(max + 1L),
                BoundedLineReader.Read.Line("after"),
                BoundedLineReader.Read.TooLong(max * 1000L),
            ),
            read,
        )
    }

    @Test
    fun `the maximum counts bytes, not characters`() {
        // Four characters of two bytes each.
        assertEquals(listOf(BoundedLineReader.Read.TooLong(8)), readAll("éééé\n", maxBytes = 7))
        assertEquals(lines("éééé"), readAll("éééé\n", maxBytes = 8))
    }

    @Test
    fun `the default maximum is 10 MB, the protocol's line limit`() {
        val max = 10 * 1024 * 1024
        assertEquals(max, ProcessGroupLauncher.MAX_LINE_BYTES)
        val text = "a".repeat(max) + "\n" + "b".repeat(max + 1) + "\nend\n"

        val read = readAll(ByteArrayInputStream(text.toByteArray()), ProcessGroupLauncher.MAX_LINE_BYTES)

        assertEquals(
            listOf(BoundedLineReader.Read.Line("a".repeat(max)), BoundedLineReader.Read.TooLong(max + 1L), BoundedLineReader.Read.Line("end")),
            read,
        )
    }

    /** A stream that gives one byte per read, so every line break falls between two reads. */
    private class OneByteAtATime(
        bytes: ByteArray,
    ) : InputStream() {
        private val input = ByteArrayInputStream(bytes)

        override fun read(): Int = input.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = if (len == 0) 0 else input.read(b, off, 1)
    }
}
