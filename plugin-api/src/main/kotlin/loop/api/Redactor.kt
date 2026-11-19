package loop.api

import java.util.concurrent.atomic.AtomicReference

/**
 * Removes secrets from text before Loop writes it anywhere. Every writer calls [redact]
 * before writing: the event store, the operator log, the cli echo paths and the
 * Attempt file writer (m1-13). Launcher `warn` lines are intentionally not routed through
 * it, they carry pids and durations only.
 */
public fun interface Redactor {
    /** [text] with every secret replaced by a marker. Never throws. */
    public fun redact(text: String): String

    public companion object {
        /** Returns the text unchanged, for read only paths and tests. */
        public val NONE: Redactor = Redactor { it }

        /** What a redactor returns in place of the text when redaction itself fails. */
        public const val FAILED_MARKER: String = "[redacted]"
    }
}

/**
 * The [Redactor] of a run. It knows the secret values it [learn]ed, by env name, and
 * replaces each by `[redacted:NAME]`. Thread safe: [learn] swaps an immutable snapshot.
 */
public class SecretRedactor(
    patterns: List<TokenPattern> = TokenPattern.DEFAULTS,
) : Redactor {
    private val labels = patterns.map { it.label }

    // One pass for every pattern. A token starts at a word start, so `disk-...` is not an `sk-` key,
    // or right after a json escape (`\n`, `\t`, `\u00a0`...), as in a raw stream-json runner line.
    private val tokens: Regex? =
        patterns
            .takeIf { it.isNotEmpty() }
            ?.withIndex()
            ?.joinToString("|", prefix = "$TOKEN_START(?:", postfix = ")") { (index, pattern) ->
                "(?<p$index>${pattern.regex.pattern})"
            }?.let(::Regex)

    private val snapshot = AtomicReference(Snapshot(emptySet()))

    /**
     * Adds [secrets], env name to value, to the known secrets. A run never forgets a secret.
     * A value under [MIN_SECRET_LENGTH] characters, blank included, is ignored: it would
     * mangle every line.
     */
    public fun learn(secrets: Map<String, String>) {
        val kept =
            secrets.entries
                .filter { (_, value) -> value.isNotBlank() && value.length >= MIN_SECRET_LENGTH }
                .mapTo(mutableSetOf()) { (name, value) -> name to value }
        if (kept.isEmpty()) return
        // Nothing new keeps the current snapshot, so a tick's learn does not recompile the regex.
        snapshot.updateAndGet { current -> if (current.secrets.containsAll(kept)) current else Snapshot(current.secrets + kept) }
    }

    /**
     * Learned values first, longest first in one pass, then the token patterns in one pass.
     * Markers come from the lambda form of replace, so a `$` or `\` in a value means nothing.
     * When the end of one value overlaps the start of another in the text, the pass redacts
     * the match found first (the longest at that position) and the rest of the other value
     * stays visible.
     * Never throws: on any internal failure (the regex engine's stack included) it returns
     * [Redactor.FAILED_MARKER], never the raw text (Symphony 17.6).
     */
    override fun redact(text: String): String =
        try {
            val current = snapshot.get()
            val known = current.known?.replace(text) { match -> "[redacted:${current.nameOf.getValue(match.value)}]" } ?: text
            tokens?.replace(known) { match -> "[redacted:${labelOf(match)}]" } ?: known
        } catch (e: Exception) {
            Redactor.FAILED_MARKER
        } catch (e: StackOverflowError) {
            Redactor.FAILED_MARKER
        }

    /** The current snapshot, for tests of [learn]. */
    internal fun snapshotForTest(): Any = snapshot.get()

    private fun labelOf(match: MatchResult): String = labels[labels.indices.first { match.groups["p$it"] != null }]

    public companion object {
        /** The shortest secret value Loop redacts. */
        public const val MIN_SECRET_LENGTH: Int = 4

        /** Not after a word character, or right after a json escape. Every branch has a bounded length. */
        private const val TOKEN_START: String =
            """(?:(?<![A-Za-z0-9_-])|(?<=\\[nrtbf])|(?<=\\u[0-9A-Fa-f]{4}))"""
    }

    /** The known secrets, compiled once per [learn] into one alternation, longest value first. */
    private class Snapshot(
        /** Every name and value pair learned, so a name with a new value keeps its old one too. */
        val secrets: Set<Pair<String, String>>,
    ) {
        // Two names with one value: the first name in sort order names the marker. A value with a
        // quote, a backslash or a control character is also known in its json escaped form, the
        // form a raw stream-json runner line carries, under the same name.
        val nameOf: Map<String, String> =
            secrets
                .flatMap { (name, value) -> listOf(name to value, name to jsonEscaped(value)) }
                .sortedByDescending { (name, _) -> name }
                .associate { (name, value) -> value to name }

        val known: Regex? =
            nameOf.keys
                .sortedByDescending { it.length }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("|") { Regex.escape(it) }
                ?.let(::Regex)

        private fun jsonEscaped(value: String): String =
            buildString {
                for (c in value) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
            }
    }
}
