package loop.api

/**
 * The entries of an issue's area (m4-17, bl-10): repository relative paths, each a folder or a file, never a glob.
 * Each tracker reads its own form of the area, the `area` front matter list of `files` or the `Area:` body line of
 * GitHub, and normalizes it here, so every tracker gives [Issue.area] the same shape. The overlap of two areas is
 * the dispatch's rule, in `loop.core.trackers`.
 */
public object IssueArea {
    /** Characters of a glob pattern: an area names paths, never patterns. */
    private val GLOB = setOf('*', '?', '[', ']', '{', '}')

    /**
     * [entry] normalized: trimmed, without `.` or empty segments and without a trailing slash, so `./spec-core/` is
     * `spec-core`. Null when it is no repository relative path: blank, absolute, holding a `..` segment, a glob
     * character, a backslash or a control character, or naming the repository root itself (`.`).
     */
    public fun normalize(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.startsWith("/") || trimmed.any { it in GLOB || it == '\\' || it.isISOControl() }) return null
        val segments = trimmed.split("/").filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || ".." in segments) return null
        return segments.joinToString("/")
    }

    /** [entries] normalized, in the order written, each once; null when one of them is malformed. */
    public fun parse(entries: List<String>): List<String>? = entries.map { normalize(it) ?: return null }.distinct()
}
