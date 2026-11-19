package loop.core.workflow

/**
 * Trims a tool output to `context.tool_output_tokens` before it reaches the model (plan 8.7, m3-14): the model gets the
 * start of the text, cut at a line end when one fits, and a note saying how much is left out and where the full text
 * is. The full text is kept elsewhere by the caller, the evidence file or the attempt file.
 */
object ToolOutputTrim {
    /**
     * [text] when it is at most [maxTokens] of [counter]; else its longest start that fits with the note, cut at its
     * last line end when that still fits, and the note, which ends with [fullAt], for example
     * `The full text is in the attempt file.`. Only the note when no start fits.
     */
    fun trim(
        text: String,
        maxTokens: Int,
        counter: TokenCounter,
        fullAt: String,
    ): String {
        if (counter.count(text) <= maxTokens) return text

        fun cut(kept: String): String {
            val start = kept.trimEnd()
            val note = "(cut to fit context.tool_output_tokens: ${text.length - start.length} of its characters are left out. $fullAt)"
            return if (start.isEmpty()) note else "$start\n$note"
        }

        fun fits(candidate: String): Boolean = counter.count(candidate) <= maxTokens

        if (!fits(cut(""))) return cut("")
        var low = 0
        var high = text.length - 1
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (fits(cut(text.take(mid)))) low = mid else high = mid - 1
        }
        val prefix = text.take(low)
        val atLineEnd = prefix.substringBeforeLast('\n', "").takeIf { it.isNotBlank() }?.let(::cut)
        return atLineEnd?.takeIf(::fits) ?: cut(prefix)
    }
}
