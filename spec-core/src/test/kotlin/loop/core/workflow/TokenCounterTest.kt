package loop.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** m3-13: the token counter of the prompt budget (plan 8.5) and the window its limit is a fraction of. */
class TokenCounterTest {
    @Test
    fun `with no tokenizer the counter estimates one token per 3 characters, rounded up, and names the method`() {
        val counter = TokenCounters.forModel("anthropic/claude-opus-5-5")

        assertEquals(TokenCounters.ESTIMATE, counter.method)
        assertEquals("estimate", counter.method)
        assertEquals(0, counter.count(""))
        assertEquals(1, counter.count("a"))
        assertEquals(1, counter.count("abc"))
        assertEquals(2, counter.count("abcd"))
        assertEquals(334, counter.count("x".repeat(1_000)))
    }

    @Test
    fun `a runner with no model counts with the estimate too`() {
        assertEquals(TokenCounters.ESTIMATE, TokenCounters.forModel(null).method)
    }

    private val context =
        ContextConfig(
            promptLimit = 0.05,
            windows =
                mapOf(
                    "anthropic/claude-opus-5-5" to 1_000_000L,
                    "anthropic/claude-sonnet-5-5" to 400_000L,
                    "openai/gpt-6" to 200_000L,
                ),
        )

    @Test
    fun `the window of a model is its key in context windows, or the key that ends with its name`() {
        assertEquals(PromptLimit(tokens = 50_000, window = 1_000_000), context.promptLimitFor("anthropic/claude-opus-5-5"))
        assertEquals(PromptLimit(tokens = 20_000, window = 400_000), context.promptLimitFor("claude-sonnet-5-5"))
    }

    @Test
    fun `a runner with no known model uses the smallest window, so the limit is never looser than for any configured model`() {
        assertEquals(PromptLimit(tokens = 10_000, window = 200_000), context.promptLimitFor(null))
        assertEquals(PromptLimit(tokens = 10_000, window = 200_000), context.promptLimitFor("sonnet"))
    }

    @Test
    fun `the limit is the fraction of the window rounded down, computed in decimal`() {
        val million = mapOf("a/b" to 1_000_000L)
        assertEquals(50_000, ContextConfig(promptLimit = 0.05, windows = million).promptLimitFor(null)?.tokens)
        assertEquals(70_000, ContextConfig(promptLimit = 0.07, windows = million).promptLimitFor(null)?.tokens)
        // m4-02: with the shipped windows, the smallest is the Codex model's 475000.
        assertEquals(23_750, ContextConfig(promptLimit = 0.05).promptLimitFor(null)?.tokens)
        // 0.29 times 100 is 28.999999999999996 in floating point.
        assertEquals(29, ContextConfig(promptLimit = 0.29, windows = mapOf("a/b" to 100L)).promptLimitFor(null)?.tokens)
        assertEquals(333, ContextConfig(promptLimit = 0.333, windows = mapOf("a/b" to 1_001L)).promptLimitFor(null)?.tokens)
    }

    @Test
    fun `no window at all gives no limit`() {
        assertNull(ContextConfig(windows = emptyMap()).promptLimitFor(null))
    }
}
