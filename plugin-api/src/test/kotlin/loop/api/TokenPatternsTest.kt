package loop.api

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenPatternsTest {
    private val redactor = SecretRedactor()

    @Test
    fun `a classic github token is redacted`() {
        val token = "ghp_" + "A1b2C3d4E5".repeat(4)

        assertEquals("auth [redacted:github-token] ok", redactor.redact("auth $token ok"))
    }

    @Test
    fun `a fine grained github token is redacted`() {
        val token = "github_pat_" + "11ABCDEFG0_abcdefghij".repeat(3)

        assertEquals("auth [redacted:github-token] ok", redactor.redact("auth $token ok"))
    }

    @Test
    fun `an anthropic key is redacted`() {
        val key = "sk-ant-api03-" + "Ab_9-xYz01".repeat(5)

        assertEquals("key=[redacted:anthropic-key]", redactor.redact("key=$key"))
    }

    @Test
    fun `an openai key and an openai project key are redacted`() {
        val legacy = "sk-" + "Zx9Yw8Vu7T".repeat(4)
        val project = "sk-proj-" + "Ab_9-xYz01".repeat(5)

        assertEquals(
            "[redacted:openai-key] and [redacted:openai-key]",
            redactor.redact("$legacy and $project"),
        )
    }

    @Test
    fun `a near miss is left alone`() {
        val text = "ghp_short and run disk-cleanup-script-for-the-workspace and sk-tooShort"

        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun `a token right after a json escape is redacted`() {
        val github = "ghp_" + "A1b2C3d4E5".repeat(4)
        val anthropic = "sk-ant-api03-" + "Ab_9-xYz01".repeat(5)
        val openai = "sk-" + "Zx9Yw8Vu7T".repeat(4)

        assertEquals("""{"text":"a\n[redacted:github-token]"}""", redactor.redact("""{"text":"a\n$github"}"""))
        assertEquals("""\t[redacted:anthropic-key]""", redactor.redact("""\t$anthropic"""))
        assertEquals("""\u00a0[redacted:openai-key]""", redactor.redact("""\u00a0$openai"""))
    }
}
