package loop.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * bl-214 and bl-205: the model of `risk.reviewer` picks the runtime that runs the merge risk reviewer, Codex for a Codex
 * model and Gemini for a Gemini model.
 */
class RiskReviewerRuntimeTest {
    @Test
    fun `bl-214 a gpt, codex or o series model runs on Codex, whatever its case`() {
        listOf("gpt-6.1-sol", "GPT-5", "gpt-5.1-codex-max", "codex-mini-latest", "o3", "o4-mini", " gpt-6.1-sol ").forEach {
            assertEquals(RiskReviewerRuntime.Codex, RiskReviewerRuntime.of(it), it)
        }
    }

    @Test
    fun `bl-205 a gemini model runs on Gemini, whatever its case`() {
        listOf("gemini-4-neon-high", "Gemini-3.8-flash-low", " gemini-4-carbon-medium ").forEach {
            assertEquals(RiskReviewerRuntime.Gemini, RiskReviewerRuntime.of(it), it)
        }
    }

    @Test
    fun `bl-205 gemini-4-neon-high picks Gemini, sonnet keeps Claude Code and gpt-6_1-sol keeps Codex`() {
        assertEquals(RiskReviewerRuntime.Gemini, RiskReviewerRuntime.of("gemini-4-neon-high"))
        assertEquals(RiskReviewerRuntime.ClaudeCode, RiskReviewerRuntime.of("sonnet"))
        assertEquals(RiskReviewerRuntime.Codex, RiskReviewerRuntime.of("gpt-6.1-sol"))
    }

    @Test
    fun `bl-214 a Claude model, an alias, another name or no model stays on Claude Code`() {
        listOf("claude-sonnet-5-5", "sonnet", "opus", "fable", "ollama-llama", "openai", "gpt", "gemini", null).forEach {
            assertEquals(RiskReviewerRuntime.ClaudeCode, RiskReviewerRuntime.of(it), "$it")
        }
    }
}
