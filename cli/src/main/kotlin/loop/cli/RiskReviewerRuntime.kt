package loop.cli

/**
 * The runtime that runs the merge risk reviewer (bl-214), chosen by the model `kinds.code.delivery.risk.reviewer` names,
 * not by `runner.kind`: the reviewer is a concern apart from the runner that did the work (m4-06). [of] is the one place
 * the choice is made, so a reviewer of another runtime adds its value and its model names here, as Gemini did (bl-205).
 */
enum class RiskReviewerRuntime {
    /** Claude Code, the reviewer of m2-28: every model no other runtime claims, aliases such as `sonnet` included. */
    ClaudeCode,

    /** Codex (bl-214): a model of the `gpt-` or `codex-` families, or of the `o` series such as `o3` and `o4-mini`. */
    Codex,

    /** Gemini (bl-205), through `agy acp`: a model whose name starts with `gemini-`. */
    Gemini,
    ;

    companion object {
        private val CODEX_MODEL = Regex("""^(gpt-|codex-|o\d).*""")
        private const val GEMINI_PREFIX = "gemini-"

        /** The runtime of [model], trimmed and in any case; [ClaudeCode] for a model no other runtime claims, or none. */
        fun of(model: String?): RiskReviewerRuntime {
            val name = model?.trim()?.lowercase() ?: return ClaudeCode
            return when {
                CODEX_MODEL.matches(name) -> Codex
                name.startsWith(GEMINI_PREFIX) -> Gemini
                else -> ClaudeCode
            }
        }
    }
}
