package loop.core.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m1-11: a cut tail drops its first partial line, so a secret cut at the head leaves no fragment. */
class OutputTailTest {
    private fun OutputTail.write(text: String) {
        val bytes = text.toByteArray()
        append(bytes, bytes.size)
    }

    @Test
    fun `a cut tail drops its first partial line`() {
        val tail = OutputTail(16)

        tail.write("token s3cr3tvalue\nlast line\n")

        assertEquals("last line\n", tail.text())
    }

    @Test
    fun `a cut tail with no newline is kept whole and reported as truncated`() {
        val tail = OutputTail(16)

        tail.write("x".repeat(40))

        assertEquals("x".repeat(16), tail.text())
        assertTrue(tail.truncated)
    }

    @Test
    fun `a tail of exactly the limit is not truncated and keeps its first line`() {
        val tail = OutputTail(16)

        tail.write("first\nsecond\nab\n")

        assertEquals("first\nsecond\nab\n", tail.text())
        assertFalse(tail.truncated)
    }

    @Test
    fun `a cut tail whose only newline is its last byte is kept whole`() {
        val tail = OutputTail(16)

        tail.write("x".repeat(26) + "\n")

        assertEquals("x".repeat(15) + "\n", tail.text())
    }
}
