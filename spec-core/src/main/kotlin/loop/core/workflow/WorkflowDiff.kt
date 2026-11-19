package loop.core.workflow

/**
 * A unified line diff of two `WORKFLOW.md` texts with [CONTEXT] lines of context, for the reload
 * record (m1-15). Lines are compared whole; a missing final newline is not marked. Texts too large
 * for the line table ([MAX_CELLS]) diff as one hunk that removes every old line and adds every new one.
 */
object WorkflowDiff {
    private const val CONTEXT = 3
    private const val MAX_CELLS = 4_000_000L

    /** Empty when the texts have the same lines. */
    fun unified(
        old: String,
        new: String,
        oldLabel: String,
        newLabel: String,
    ): String {
        val a = lines(old)
        val b = lines(new)
        val edits = edits(a, b)
        if (edits.all { it.op == Op.SAME }) return ""
        return buildString {
            append("--- ").append(oldLabel).append('\n')
            append("+++ ").append(newLabel).append('\n')
            hunks(edits).forEach { hunk -> append(hunk) }
        }
    }

    private enum class Op { SAME, REMOVED, ADDED }

    /** One line of the edit script, with its 1 based line numbers in the old and new texts. */
    private class Edit(
        val op: Op,
        val text: String,
        val oldLine: Int,
        val newLine: Int,
    )

    private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

    /** The edit script of a longest common subsequence of [a] and [b]. */
    private fun edits(
        a: List<String>,
        b: List<String>,
    ): List<Edit> {
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return a.mapIndexed { i, line -> Edit(Op.REMOVED, line, i + 1, 0) } +
                b.mapIndexed { j, line -> Edit(Op.ADDED, line, a.size, j + 1) }
        }
        // common[i][j]: length of the longest common subsequence of a[i..] and b[j..].
        val common = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                common[i][j] = if (a[i] == b[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
            }
        }
        val edits = mutableListOf<Edit>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> edits += Edit(Op.SAME, a[i++], i, ++j)
                // A removal goes before an addition, so a changed line reads old then new.
                i < a.size && (j == b.size || common[i + 1][j] >= common[i][j + 1]) -> edits += Edit(Op.REMOVED, a[i++], i, j)
                else -> edits += Edit(Op.ADDED, b[j++], i, j)
            }
        }
        return edits
    }

    /** The hunks of [edits]: each change with [CONTEXT] lines around it, changes closer than twice that merged. */
    private fun hunks(edits: List<Edit>): List<String> {
        val changed = edits.indices.filter { edits[it].op != Op.SAME }
        val ranges = mutableListOf<IntRange>()
        for (index in changed) {
            val start = maxOf(0, index - CONTEXT)
            val end = minOf(edits.size - 1, index + CONTEXT)
            val last = ranges.lastOrNull()
            if (last != null && start <= last.last + 1) ranges[ranges.size - 1] = last.first..end else ranges += start..end
        }
        return ranges.map { range -> hunk(edits.subList(range.first, range.last + 1)) }
    }

    private fun hunk(edits: List<Edit>): String {
        val oldCount = edits.count { it.op != Op.ADDED }
        val newCount = edits.count { it.op != Op.REMOVED }
        val first = edits.first()
        // A hunk with no line on one side starts after the line before it, 0 at the top (unified diff convention).
        val oldStart = if (oldCount == 0) first.oldLine else edits.first { it.op != Op.ADDED }.oldLine
        val newStart = if (newCount == 0) first.newLine else edits.first { it.op != Op.REMOVED }.newLine
        return buildString {
            append("@@ -").append(oldStart).append(',').append(oldCount)
            append(" +")
                .append(newStart)
                .append(',')
                .append(newCount)
                .append(" @@\n")
            edits.forEach { edit ->
                val mark =
                    when (edit.op) {
                        Op.SAME -> ' '
                        Op.REMOVED -> '-'
                        Op.ADDED -> '+'
                    }
                append(mark).append(edit.text).append('\n')
            }
        }
    }
}
