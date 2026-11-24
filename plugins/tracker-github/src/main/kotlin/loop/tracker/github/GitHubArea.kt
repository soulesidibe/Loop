package loop.tracker.github

import loop.api.IssueArea

/**
 * The area of a GitHub issue (m4-17): a tracker without front matter carries it as a column 0 `Area:` line of the
 * body, with comma separated repository relative paths, such as `Area: spec-core, docs/agents`. Every such line
 * counts, its paths joined in the order written. The word is read as written, so a line starting `area:` is prose,
 * and the lines of fenced code blocks are ignored. Comments never carry it.
 */
internal object GitHubArea {
    private val LINE = Regex("Area:(.*)")
    private val FENCE = Regex(" {0,3}(`{3,}|~{3,})(.*)")

    /** What [read] found: the normalized [area], empty when the body has none, or the first [malformed] line. */
    class Read(
        val area: List<String>,
        val malformed: String?,
    )

    /** The `Area:` lines of [body]: their normalized paths, or the first line holding no path or a malformed one. */
    fun read(body: String?): Read {
        val entries = mutableListOf<String>()
        var fence: String? = null
        for (raw in body.orEmpty().lines()) {
            val line = raw.removeSuffix("\r")
            val marker = FENCE.matchEntire(line)
            if (fence == null && marker != null && (marker.groupValues[1][0] == '~' || '`' !in marker.groupValues[2])) {
                fence = marker.groupValues[1]
                continue
            }
            if (fence != null) {
                val closes =
                    marker != null && marker.groupValues[1][0] == fence[0] && marker.groupValues[1].length >= fence.length &&
                        marker.groupValues[2].isBlank()
                if (closes) fence = null
                continue
            }
            val value = LINE.matchEntire(line)?.groupValues?.get(1) ?: continue
            val paths = IssueArea.parse(value.split(",")).takeIf { value.isNotBlank() }
            if (paths == null) return Read(emptyList(), line.take(MAX_LINE))
            entries += paths
        }
        return Read(entries.distinct(), null)
    }

    /** The longest part of a malformed line a warning quotes. */
    private const val MAX_LINE = 200
}
