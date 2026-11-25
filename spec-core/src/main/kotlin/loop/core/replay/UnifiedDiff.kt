package loop.core.replay

/**
 * A line diff of two texts in the unified format (m4-40), as `diff -u` prints it: the two labels, then hunks with
 * [CONTEXT] lines of context around each change. The texts split on `\n` only, so a missing final line break shows as
 * a changed empty last line. Myers' algorithm on what is left once the common first and last lines are set aside.
 */
object UnifiedDiff {
    /** The lines of context around a change, as `diff -u` keeps them. */
    const val CONTEXT: Int = 3

    /** The diff of [original] into [revised], labelled [originalLabel] and [revisedLabel], or null when they are equal. */
    fun of(
        original: String,
        revised: String,
        originalLabel: String,
        revisedLabel: String,
    ): String? {
        if (original == revised) return null
        val edits = edits(lines(original), lines(revised))
        return (listOf("--- $originalLabel", "+++ $revisedLabel") + hunks(edits)).joinToString("\n")
    }

    /** The edit script of [original] into [revised], each line with its mark: ` ` kept, `-` deleted, `+` inserted. */
    internal fun script(
        original: String,
        revised: String,
    ): List<String> = edits(lines(original), lines(revised)).map { "${it.op.mark}${it.line}" }

    private enum class Op(
        val mark: Char,
    ) {
        KEEP(' '),
        DELETE('-'),
        INSERT('+'),
    }

    private class Edit(
        val op: Op,
        val line: String,
    )

    private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')

    /** Every line of both texts as kept, deleted from [a] or inserted from [b], in order. */
    private fun edits(
        a: List<String>,
        b: List<String>,
    ): List<Edit> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val middle = myers(a.subList(prefix, a.size - suffix), b.subList(prefix, b.size - suffix))
        return a.take(prefix).map { Edit(Op.KEEP, it) } + middle + a.takeLast(suffix).map { Edit(Op.KEEP, it) }
    }

    /**
     * Myers' shortest edit script of [a] into [b]. Each step keeps only the furthest points of its own diagonals,
     * `2d + 1` of them, so the memory grows with the square of the number of differences, not of the lines.
     */
    private fun myers(
        a: List<String>,
        b: List<String>,
    ): List<Edit> {
        val n = a.size
        val m = b.size
        val trace = mutableListOf<IntArray>()
        // The furthest x on diagonal k after the previous step, at index k + d of a step of size 2d + 1.
        var previous = IntArray(1)
        var found = false
        var d = 0
        while (!found) {
            val current = IntArray(2 * d + 1)
            for (k in -d..d step 2) {
                var x =
                    when {
                        d == 0 -> 0
                        k == -d || (k != d && at(previous, d - 1, k - 1) < at(previous, d - 1, k + 1)) -> at(previous, d - 1, k + 1)
                        else -> at(previous, d - 1, k - 1) + 1
                    }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                current[k + d] = x
                if (x >= n && y >= m) found = true
            }
            trace += current
            previous = current
            d++
        }
        val edits = ArrayDeque<Edit>()
        var x = n
        var y = m
        for (step in trace.indices.reversed()) {
            val k = x - y
            if (step == 0) {
                while (x > 0 && y > 0) {
                    edits.addFirst(Edit(Op.KEEP, a[x - 1]))
                    x--
                    y--
                }
                break
            }
            val before = trace[step - 1]
            val down = k == -step || (k != step && at(before, step - 1, k - 1) < at(before, step - 1, k + 1))
            val previousK = if (down) k + 1 else k - 1
            val previousX = at(before, step - 1, previousK)
            val previousY = previousX - previousK
            val startX = if (down) previousX else previousX + 1
            val startY = startX - k
            while (x > startX && y > startY) {
                edits.addFirst(Edit(Op.KEEP, a[x - 1]))
                x--
                y--
            }
            if (down) edits.addFirst(Edit(Op.INSERT, b[previousY])) else edits.addFirst(Edit(Op.DELETE, a[previousX]))
            x = previousX
            y = previousY
        }
        return edits
    }

    /** The furthest x on diagonal [k] in the [step] of size `2 * step + 1`. */
    private fun at(
        step: IntArray,
        d: Int,
        k: Int,
    ): Int = step[k + d]

    /** The hunks of [edits]: changes closer than twice [CONTEXT] lines share one. */
    private fun hunks(edits: List<Edit>): List<String> {
        val changes = edits.indices.filter { edits[it].op != Op.KEEP }
        val groups = mutableListOf<IntRange>()
        for (index in changes) {
            val last = groups.lastOrNull()
            if (last != null && index - last.last <= 2 * CONTEXT + 1) {
                groups[groups.size - 1] = last.first..index
            } else {
                groups += index..index
            }
        }
        // The lines of each text before each edit, for the hunk headers.
        val aBefore = IntArray(edits.size + 1)
        val bBefore = IntArray(edits.size + 1)
        edits.forEachIndexed { i, edit ->
            aBefore[i + 1] = aBefore[i] + if (edit.op != Op.INSERT) 1 else 0
            bBefore[i + 1] = bBefore[i] + if (edit.op != Op.DELETE) 1 else 0
        }
        return groups.flatMap { group ->
            val start = maxOf(0, group.first - CONTEXT)
            val end = minOf(edits.size, group.last + CONTEXT + 1)
            val aCount = aBefore[end] - aBefore[start]
            val bCount = bBefore[end] - bBefore[start]
            val header = "@@ -${range(aBefore[start], aCount)} +${range(bBefore[start], bCount)} @@"
            listOf(header) + (start until end).map { "${edits[it].op.mark}${edits[it].line}" }
        }
    }

    /** A hunk range: its first line, 1 based, or the line before it when it is empty, then its size. */
    private fun range(
        before: Int,
        count: Int,
    ): String = "${if (count == 0) before else before + 1},$count"
}
