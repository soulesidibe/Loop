package loop.api

import java.text.Normalizer

/**
 * The entries of an issue's area (m4-17, bl-10): repository relative paths, each a folder or a file, never a glob.
 * Each tracker reads its own form of the area, the `area` front matter list of `files` or the `Area:` body line of
 * GitHub, and normalizes it here, so every tracker gives [Issue.area] the same shape. The overlap of two areas is
 * the dispatch's rule, in `loop.core.trackers`.
 */
public object IssueArea {
    /**
     * Characters refused in an entry: those of a glob pattern, since an area names paths, never patterns, the
     * backslash, and the backtick, which quotes a path in markdown and is never part of one in practice.
     */
    private val REFUSED = setOf('*', '?', '[', ']', '{', '}', '\\', '`')

    /** The most entries an area holds; a longer list is malformed, so one issue cannot make the overlap check costly. */
    public const val MAX_ENTRIES: Int = 64

    /**
     * [entry] normalized: in Unicode NFC, trimmed, each segment trimmed, without `.` or empty segments and without a
     * trailing slash, so `./spec-core / src/` is `spec-core/src`. Null when it is no repository relative path: blank,
     * absolute, holding a `..` segment, a glob character, a backslash, a backtick, a control or format character (such
     * as a zero width space) or whitespace inside a segment, or naming the repository root itself (`.`). A path whose
     * name holds a space cannot be an entry, its parent folder can; the refusal also keeps a prose line from reading
     * as a path list.
     */
    public fun normalize(entry: String): String? {
        val trimmed = Normalizer.normalize(entry, Normalizer.Form.NFC).trim()
        if (trimmed.startsWith("/") || trimmed.any { it in REFUSED || it.isISOControl() || it.isFormat() }) return null
        val segments = trimmed.split("/").map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || ".." in segments || segments.any { segment -> segment.any { it.isWhitespace() } }) {
            return null
        }
        return segments.joinToString("/")
    }

    /** [entries] normalized, in the order written, each once; null when one of them is malformed or there are too many. */
    public fun parse(entries: List<String>): List<String>? {
        if (entries.size > MAX_ENTRIES) return null
        return entries.map { normalize(it) ?: return null }.distinct()
    }

    private fun Char.isFormat(): Boolean = Character.getType(this) == Character.FORMAT.toInt()
}
