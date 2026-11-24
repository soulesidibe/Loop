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
    fun `a google api key is redacted`() {
        val key = "AIza" + "Ab9_-xYz01".repeat(3) + "12345"

        assertEquals("key=[redacted:google-api-key] ok", redactor.redact("key=$key ok"))
    }

    @Test
    fun `a google oauth access token is redacted`() {
        val token = "ya29." + "a0AfB_byC1234567890-xyz"
        val serviceAccount = "ya29.c." + "b0AaEkM1234567890_abc-xyz.extra"

        assertEquals("Bearer [redacted:google-oauth-token]", redactor.redact("Bearer $token"))
        assertEquals("Bearer [redacted:google-oauth-token]", redactor.redact("Bearer $serviceAccount"))
    }

    @Test
    fun `a near miss is left alone`() {
        val shortGoogleKey = "AIza" + "Ab9_-xYz01".repeat(3) + "1234"
        val attachedGoogleKey = "prefixAIza" + "Ab9_-xYz01".repeat(3) + "12345"
        val shortGoogleOauth = "ya29.shortToken123456789"
        val googleRefreshToken = "1//0gAbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        val text =
            "ghp_short and run disk-cleanup-script-for-the-workspace and sk-tooShort " +
                "and $shortGoogleKey and $attachedGoogleKey and $shortGoogleOauth and $googleRefreshToken"

        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun `a token right after a json escape is redacted`() {
        val github = "ghp_" + "A1b2C3d4E5".repeat(4)
        val anthropic = "sk-ant-api03-" + "Ab_9-xYz01".repeat(5)
        val openai = "sk-" + "Zx9Yw8Vu7T".repeat(4)
        val googleApiKey = "AIza" + "Ab9_-xYz01".repeat(3) + "12345"
        val googleOauth = "ya29." + "a0AfB_byC1234567890-xyz"

        assertEquals("""{"text":"a\n[redacted:github-token]"}""", redactor.redact("""{"text":"a\n$github"}"""))
        assertEquals("""\t[redacted:anthropic-key]""", redactor.redact("""\t$anthropic"""))
        assertEquals("""\u00a0[redacted:openai-key]""", redactor.redact("""\u00a0$openai"""))
        assertEquals("""\n[redacted:google-api-key]""", redactor.redact("""\n$googleApiKey"""))
        assertEquals("""\t[redacted:google-oauth-token]""", redactor.redact("""\t$googleOauth"""))
    }
}
