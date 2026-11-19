package loop.core.workflow

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Counts the tokens of a text for the prompt budget (plan 8.5, m3-13). [method] names how, and the trace records it
 * beside every count, so a count is never read as more exact than it is.
 */
interface TokenCounter {
    /** How this counter counts, `estimate` for the conservative estimate. */
    val method: String

    fun count(text: String): Int
}

/** The counters Loop has: the model's tokenizer when one is available offline, otherwise the conservative estimate. */
object TokenCounters {
    /** The method name of [estimate]. */
    const val ESTIMATE: String = "estimate"

    /**
     * The conservative estimate: one token per 3 characters (UTF-16 units, as `prompt_chars` counts them), rounded up.
     * Real tokenizers average about 4 characters per token on English and code, so it overcounts and a limit it holds
     * also holds for the model.
     */
    val estimate: TokenCounter =
        object : TokenCounter {
            override val method: String = ESTIMATE

            override fun count(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        }

    /**
     * The counter for [model], a `context.windows` key or a runner's configured model, null when the runner names none.
     * No tokenizer of the models Loop runs is available offline on the JVM, so every model gets [estimate] for now.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forModel(model: String?): TokenCounter = estimate

    private const val CHARS_PER_TOKEN = 3
}

/** The prompt limit of one model: [tokens], `context.prompt_limit` times the [window] the limit was taken from. */
data class PromptLimit(
    val tokens: Long,
    val window: Long,
)

/**
 * The window the prompt limit of [model] is a fraction of (m3-13): the `context.windows` entry whose key is [model], else
 * the smallest of those whose key ends with `/<model>`, so Claude Code's `claude-opus-5-5` finds
 * `anthropic/claude-opus-5-5`. A runner with no model, such as `command`, or a model no key names, such as the alias
 * `sonnet`, gets the smallest window of `context.windows`, so its limit is never looser than for any configured model.
 * Null when `context.windows` is empty.
 */
fun ContextConfig.promptWindow(model: String?): Long? {
    if (model != null) {
        windows[model]?.let { return it }
        windows
            .filterKeys { it.endsWith("/$model") }
            .values
            .minOrNull()
            ?.let { return it }
    }
    return windows.values.minOrNull()
}

/** The prompt limit of [model]: `prompt_limit` times its [promptWindow], rounded down in decimal; null without a window. */
fun ContextConfig.promptLimitFor(model: String?): PromptLimit? {
    val window = promptWindow(model) ?: return null
    val tokens = BigDecimal(promptLimit.toString()).multiply(BigDecimal.valueOf(window)).setScale(0, RoundingMode.FLOOR).toLong()
    return PromptLimit(tokens, window)
}
