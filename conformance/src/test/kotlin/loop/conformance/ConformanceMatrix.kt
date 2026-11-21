package loop.conformance

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Checks the conformance test matrix of docs/spec-decisions.md against the test
 * sources (bl-154). Each backticked `.kt` path must be a file. A path without a
 * folder is a file of the folder of the last full path before it on the same line.
 * Each backticked text in the parentheses right after a path must be contained in
 * the name of a backticked test function of that file, so the matrix can shorten a
 * long name or name a group of tests by their common prefix.
 */
object ConformanceMatrix {
    private const val HEADING = "## Conformance test matrix"
    private val testFunction = Regex("""fun\s+`([^`]+)`\s*\(""")

    data class Result(
        val paths: Int,
        val names: Int,
        val problems: List<String>,
    )

    fun check(
        markdown: String,
        root: Path,
    ): Result {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trimEnd() == HEADING }
        if (start < 0) return Result(0, 0, listOf("no `$HEADING` section"))
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        val scan = Scan(root)
        for (index in start + 1 until end) scan.line(index + 1, lines[index])
        return Result(scan.paths, scan.names, scan.problems)
    }

    private class Scan(
        private val root: Path,
    ) {
        var paths = 0
        var names = 0
        val problems = mutableListOf<String>()
        private val testNames = mutableMapOf<Path, List<String>>()

        fun line(
            number: Int,
            text: String,
        ) {
            var folder: String? = null
            var at = 0
            while (true) {
                val token = backticked(text, at) ?: return
                at = token.last + 1
                val written = text.substring(token.first + 1, token.last)
                if (!written.endsWith(".kt")) continue
                paths++
                val relative = if ('/' in written) written else listOfNotNull(folder, written).joinToString("/")
                if ('/' in written) folder = written.substringBeforeLast('/')
                val file = root.resolve(relative)
                val exists = file.isRegularFile()
                if (!exists) {
                    val shown = if (relative == written) "`$written`" else "`$written` ($relative)"
                    problems += "line $number: $shown is not a file"
                }
                if (opensParenthesis(text, at) == null) continue
                val group = parentheses(text, at)
                if (group == null) {
                    problems += "line $number: the parentheses after `$written` are not closed"
                    return
                }
                at = group.last + 1
                val inner = text.substring(group.first + 1, group.last)
                var innerAt = 0
                while (true) {
                    val name = backticked(inner, innerAt) ?: break
                    innerAt = name.last + 1
                    val wanted = inner.substring(name.first + 1, name.last)
                    names++
                    if (exists && testNamesOf(file).none { wanted in it }) {
                        problems += "line $number: `$written` has no test named like `$wanted`"
                    }
                }
            }
        }

        private fun testNamesOf(file: Path): List<String> =
            testNames.getOrPut(file) { testFunction.findAll(file.readText()).map { it.groupValues[1] }.toList() }

        /** The next backticked span at or after [from], both backticks included. */
        private fun backticked(
            text: String,
            from: Int,
        ): IntRange? {
            val open = text.indexOf('`', from)
            if (open < 0) return null
            val close = text.indexOf('`', open + 1)
            if (close < 0) return null
            return open..close
        }

        /** The index of the parenthesis that opens right after [from], spaces aside. */
        private fun opensParenthesis(
            text: String,
            from: Int,
        ): Int? {
            var open = from
            while (open < text.length && text[open] == ' ') open++
            return open.takeIf { it < text.length && text[it] == '(' }
        }

        /**
         * The balanced parentheses that open right after [from], both included, or null
         * when they are not closed. Parentheses inside a backticked span do not count.
         */
        private fun parentheses(
            text: String,
            from: Int,
        ): IntRange? {
            val open = opensParenthesis(text, from) ?: return null
            var depth = 0
            var index = open
            while (index < text.length) {
                when (text[index]) {
                    '`' -> index = text.indexOf('`', index + 1).takeIf { it >= 0 } ?: return null
                    '(' -> depth++
                    ')' -> if (--depth == 0) return open..index
                }
                index++
            }
            return null
        }
    }
}
