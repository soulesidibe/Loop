package loop.core.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m2-05: the whole output of a check, for its evidence, with a cap that keeps the tail. */
class OutputCaptureTest {
    private fun OutputCapture.write(text: String) {
        val bytes = text.toByteArray()
        append(bytes, bytes.size)
    }

    @Test
    fun `under the cap every byte is kept, across many writes`() {
        val capture = OutputCapture(1024)

        repeat(50) { capture.write("line $it\n") }

        assertFalse(capture.truncated)
        assertEquals((0 until 50).joinToString("") { "line $it\n" }, capture.text())
    }

    @Test
    fun `over the cap the head is dropped up to a whole line`() {
        val capture = OutputCapture(16)

        capture.write("token s3cr3tvalue\n")
        capture.write("last line\n")

        assertTrue(capture.truncated)
        assertEquals("last line\n", capture.text())
    }

    @Test
    fun `a tail with no newline is kept whole`() {
        val capture = OutputCapture(16)

        capture.write("x".repeat(40))

        assertTrue(capture.truncated)
        assertEquals("x".repeat(16), capture.text())
    }
}
