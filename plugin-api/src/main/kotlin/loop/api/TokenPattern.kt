package loop.api

/**
 * A well known token shape, redacted as `[redacted:LABEL]` even when Loop never learned
 * its value. [regex] is prefix anchored and linear; it must not hold capturing groups.
 */
public data class TokenPattern(
    public val label: String,
    public val regex: Regex,
) {
    public companion object {
        /** GitHub, Anthropic and OpenAI style keys. Anthropic comes before OpenAI, whose `sk-` prefix it shares. */
        public val DEFAULTS: List<TokenPattern> =
            listOf(
                TokenPattern("github-token", Regex("""gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,}""")),
                TokenPattern("anthropic-key", Regex("""sk-ant-[A-Za-z0-9_-]{20,}""")),
                TokenPattern("openai-key", Regex("""sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}""")),
            )
    }
}
