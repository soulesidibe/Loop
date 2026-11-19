package loop.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RedactorTest {
    @Test
    fun `a learned value is replaced by its name marker`() {
        val redactor = SecretRedactor()
        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to "s3cr3t-value"))

        assertEquals("token is [redacted:LOOP_TRACKER_TOKEN] here", redactor.redact("token is s3cr3t-value here"))
    }

    @Test
    fun `the longest value wins when one value contains another`() {
        val redactor = SecretRedactor()
        redactor.learn(linkedMapOf("SHORT" to "abcd", "LONG" to "abcdefgh"))

        assertEquals("x [redacted:LONG] y", redactor.redact("x abcdefgh y"))
    }

    @Test
    fun `two names with one value map to the first name in sort order`() {
        val redactor = SecretRedactor()
        redactor.learn(linkedMapOf("A_TOKEN" to "same-value", "B_TOKEN" to "same-value"))

        assertEquals("[redacted:A_TOKEN]", redactor.redact("same-value"))
    }

    @Test
    fun `a blank value or one under 4 characters is never learned`() {
        val redactor = SecretRedactor()
        redactor.learn(mapOf("EMPTY" to "", "BLANK" to "   ", "SHORT" to "abc"))

        assertEquals("abc and a b c", redactor.redact("abc and a b c"))
    }

    @Test
    fun `a value learned once is never forgotten when its name gets a new value`() {
        val redactor = SecretRedactor()
        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to "first-value"))
        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to "second-value"))

        assertEquals(
            "[redacted:LOOP_TRACKER_TOKEN] [redacted:LOOP_TRACKER_TOKEN]",
            redactor.redact("first-value second-value"),
        )
    }

    @Test
    fun `learning nothing new keeps the compiled snapshot`() {
        val redactor = SecretRedactor()
        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to "s3cr3t-value"))
        val before = redactor.snapshotForTest()

        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to "s3cr3t-value", "SHORT" to "abc"))

        assertSame(before, redactor.snapshotForTest())
    }

    @Test
    fun `a value with a quote or a backslash is also redacted in its json escaped form`() {
        val redactor = SecretRedactor()
        redactor.learn(mapOf("LOOP_TRACKER_TOKEN" to """ab"cd\ef"""))

        assertEquals(
            """{"line":"x [redacted:LOOP_TRACKER_TOKEN] y"} raw [redacted:LOOP_TRACKER_TOKEN]""",
            redactor.redact("""{"line":"x ab\"cd\\ef y"} raw ab"cd\ef"""),
        )
    }

    @Test
    fun `an internal failure returns the fallback marker and never the text`() {
        // A recursive pattern on a long input overflows the regex engine's stack.
        val redactor = SecretRedactor(listOf(TokenPattern("broken", Regex("""(?:a|b)*c"""))))

        assertEquals("[redacted]", redactor.redact("ab".repeat(200_000)))
    }
}
