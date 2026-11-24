package loop.tracker.github

import loop.api.IssueArea

/**
 * The area of a GitHub issue (m4-17): a tracker without front matter carries it as a column 0 `Area:` line of the
 * body, with comma separated repository relative paths, such as `Area: spec-core, docs/agents`. Every such line
 * counts, its paths joined in the order written. The word is read as written, so a line starting `area:` is prose.
 * The lines of fenced code blocks and the text of HTML comments are ignored: a line that starts inside a comment is no
 * `Area:` line, and a comment after the paths is left out of them. An `Area:` line that is no path list, such as a
 * prose sentence (an entry holds no space), is malformed, and then every `Area:` line of the body is ignored.
 */
internal object GitHubArea {
    private const val PREFIX = "Area:"
    private const val OPEN = "<!--"
    private const val CLOSE = "-->"
    private val FENCE = Regex(" {0,3}(`{3,}|~{3,})(.*)")

    /**
     * What [read] found: the normalized [area], empty when the body has none, or the [malformed] line's number, from 1,
     * with a [fingerprint] of its text that tells an edited line apart without keeping the text.
     */
    class Read(
        val area: List<String>,
        val malformed: Int? = null,
        val fingerprint: Int = 0,
    )

    /** The `Area:` lines of [body]: their normalized paths, or the number of the first line holding none or a malformed one. */
    fun read(body: String?): Read {
        val entries = mutableListOf<String>()
        var fence: String? = null
        var inComment = false
        for ((index, raw) in body.orEmpty().lines().withIndex()) {
            val line = raw.removeSuffix("\r")
            if (!inComment) {
                val marker = FENCE.matchEntire(line)
                if (fence == null && marker != null && (marker.groupValues[1][0] == '~' || '`' !in marker.groupValues[2])) {
                    fence = marker.groupValues[1]
                    continue
                }
                if (fence != null) {
                    val closes =
                        marker != null &&
                            marker.groupValues[1][0] == fence[0] &&
                            marker.groupValues[1].length >= fence.length &&
                            marker.groupValues[2].isBlank()
                    if (closes) fence = null
                    continue
                }
            }
            val startsInComment = inComment
            val visible = uncommented(line, inComment).also { inComment = it.second }.first
            if (startsInComment || !line.startsWith(PREFIX)) continue
            val value = visible.removePrefix(PREFIX)
            val paths = IssueArea.parse(value.split(",")).takeIf { value.isNotBlank() }
            if (paths == null) return Read(emptyList(), index + 1, line.hashCode())
            entries += paths
        }
        return Read(entries.distinct())
    }

    /** [line] without the text of its HTML comments, given whether it [starts] inside one, and whether it ends inside one. */
    private fun uncommented(
        line: String,
        starts: Boolean,
    ): Pair<String, Boolean> {
        val text = StringBuilder()
        var inComment = starts
        var at = 0
        while (at < line.length) {
            if (inComment) {
                val close = line.indexOf(CLOSE, at)
                if (close < 0) return text.toString() to true
                at = close + CLOSE.length
                inComment = false
            } else {
                val open = line.indexOf(OPEN, at)
                if (open < 0) {
                    text.append(line, at, line.length)
                    break
                }
                text.append(line, at, open)
                at = open + OPEN.length
                inComment = true
            }
        }
        return text.toString() to inComment
    }
}
