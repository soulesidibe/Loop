package loop.core.verify

import loop.core.workflow.DenyGuard
import loop.core.workflow.TestLanguage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One changed file of a diff as the deny guards see it (m2-13): its path and text at the merge base, null when it
 * was added, and in the working tree or in HEAD, null when it was deleted. A rename has both paths.
 */
data class TestFile(
    val oldPath: String? = null,
    val oldText: String? = null,
    val newPath: String? = null,
    val newText: String? = null,
)

/** What one deny [guard] found, a sentence that names the file and the line or the test. */
data class GuardFinding(
    val guard: DenyGuard,
    val message: String,
) {
    override fun toString(): String = "${guard.key}: $message"
}

/** The deny guards could not read the diff within their bounds, so the check could not run. */
class DenyGuardsLimit(
    message: String,
) : RuntimeException(message)

/**
 * The deny guards of `guards.deny` (m2-13, plan 6.4, story 70), on the old and new texts of the changed files.
 * Each file is read with the language whose test file globs match its path, on each side, so a test file moved
 * out of the test files loses its tests. The heuristics and their limits are in `docs/spec-decisions.md`:
 *
 * - `deleted_tests`: the test names of every test file of a language, old against new, across the whole diff,
 *   so a test moved to another test file is not a deletion. A deleted test file is named when it held tests.
 * - `added_skips`: a skip line in a file's new text that its old text did not have and no other file lost.
 * - `weaker_assertions`: a file that lost an assertion line, not moved to another file, and added none.
 *
 * The work is bounded: a line is read up to [LINE_MAX] characters, a changed line longer than that throws
 * [DenyGuardsLimit] since cutting it could hide a skip, and so does a pass that runs past its budget.
 */
object DenyGuards {
    /** How many removed assertion lines a finding quotes. */
    private const val QUOTED = 3

    /** The longest line the guards read; an unchanged line is cut here, a changed one over it cannot be read. */
    const val LINE_MAX: Int = 4096

    /** How long one pass of the guards may take before the check could not run. */
    val BUDGET: Duration = 10.seconds

    /** The findings on [files]; the pass ends [budget] after [startNanos], so two passes can share one budget. */
    fun find(
        deny: List<DenyGuard>,
        languages: List<TestLanguage>,
        files: List<TestFile>,
        budget: Duration = BUDGET,
        startNanos: Long = System.nanoTime(),
    ): List<GuardFinding> {
        if (deny.isEmpty()) return emptyList()
        val clock = Deadline(budget, startNanos)
        val readers = languages.map { Reader(it, clock) }
        val sides = files.map { Sides(it, readers) }
        return deny.flatMap { guard ->
            when (guard) {
                DenyGuard.DELETED_TESTS -> readers.flatMap { deletedTests(it, sides) }
                DenyGuard.ADDED_SKIPS -> readers.flatMap { addedSkips(it, sides) }
                DenyGuard.WEAKER_ASSERTIONS -> readers.flatMap { weakerAssertions(it, sides) }
            }.sortedWith(compareBy { it.second }).map { GuardFinding(guard, it.first) }
        }
    }

    /** A finding's message and the path it sorts by. */
    private typealias Found = Pair<String, String>

    private fun deletedTests(
        reader: Reader,
        sides: List<Sides>,
    ): List<Found> {
        val test = reader.test
        val gone = sides.filter { it.oldReader === reader && it.newReader !== reader }
        if (test == null) return gone.map { "deleted test file ${it.file.oldPath}" to it.file.oldPath!! }
        val newNames = Multiset<String>()
        sides.filter { it.newReader === reader }.forEach { side -> reader.testNames(side.newLines).forEach(newNames::add) }
        val found = mutableListOf<Found>()
        for (side in sides.filter { it.oldReader === reader }) {
            val path = side.file.oldPath!!
            val removed = reader.testNames(side.oldLines).filterNot { newNames.take(it) }
            if (removed.isEmpty()) continue
            val tests = "${removed.size} ${plural(removed.size, "test")}: ${removed.joinToString(", ")}"
            val message =
                when {
                    side in gone -> "deleted test file $path ($tests)"
                    else -> "removed ${plural(removed.size, "test")} ${removed.joinToString(", ")} from $path"
                }
            found += message to path
        }
        return found
    }

    private fun addedSkips(
        reader: Reader,
        sides: List<Sides>,
    ): List<Found> {
        val skip = reader.skip ?: return emptyList()
        val changes = lineChanges(reader, sides, skip)
        val pool = Multiset<String>().also { set -> changes.flatMap { it.lost }.forEach(set::add) }
        return changes.flatMap { change ->
            val path = change.side.file.newPath ?: return@flatMap emptyList()
            change.gained.filterNot { pool.take(it) }.map { "added a skip to $path: $it" to path }
        }
    }

    private fun weakerAssertions(
        reader: Reader,
        sides: List<Sides>,
    ): List<Found> {
        val assertion = reader.assertion ?: return emptyList()
        val changes = lineChanges(reader, sides, assertion)
        val pool = Multiset<String>().also { set -> changes.flatMap { it.gained }.forEach(set::add) }
        return changes.mapNotNull { change ->
            val lost = change.lost.filterNot { pool.take(it) }
            if (lost.isEmpty() || change.gained.isNotEmpty()) return@mapNotNull null
            val file = change.side.file
            val path = file.oldPath!!
            val renamed = file.newPath?.takeIf { it != path && change.side.newReader === reader }
            val named = if (renamed != null) "$path (renamed to $renamed)" else path
            val quoted = lost.take(QUOTED).joinToString(", ") + if (lost.size > QUOTED) ", ..." else ""
            "removed ${lost.size} ${plural(lost.size, "assertion")} from $named and added none: $quoted" to path
        }
    }

    /** The lines of one file, old and new path together, that match a pattern and that it [gained] and [lost]. */
    private class LineChange(
        val side: Sides,
        val gained: List<String>,
        val lost: List<String>,
    )

    /**
     * The lines that match [pattern] (once per match, trimmed) that each file gained and lost, a renamed file once
     * for both its paths. A side outside [reader]'s test files has none. Only the changed lines are matched, and a
     * changed line over [LINE_MAX] throws [DenyGuardsLimit].
     */
    private fun lineChanges(
        reader: Reader,
        sides: List<Sides>,
        pattern: Regex,
    ): List<LineChange> =
        sides.mapNotNull { side ->
            val old = if (side.oldReader === reader) side.oldLines.map(String::trim) else emptyList()
            val new = if (side.newReader === reader) side.newLines.map(String::trim) else emptyList()
            if (old.isEmpty() && new.isEmpty()) return@mapNotNull null
            val oldSet = Multiset<String>().also { set -> old.forEach(set::add) }
            val newSet = Multiset<String>().also { set -> new.forEach(set::add) }
            val gained = new.filterNot { oldSet.take(it) }
            val lost = old.filterNot { newSet.take(it) }
            val path = side.file.newPath ?: side.file.oldPath
            val matched = { lines: List<String> -> lines.flatMap { line -> reader.matches(line, pattern, path) } }
            LineChange(side, matched(gained), matched(lost)).takeIf { it.gained.isNotEmpty() || it.lost.isNotEmpty() }
        }

    private fun plural(
        count: Int,
        word: String,
    ): String = if (count == 1) word else "${word}s"

    /**
     * A file and the reader of each of its sides, the first language whose globs match that side's path, and the
     * code lines of each side, comments blanked, read once.
     */
    private class Sides(
        val file: TestFile,
        readers: List<Reader>,
    ) {
        val oldReader: Reader? = file.oldPath?.let { path -> readers.firstOrNull { it.covers(path) } }
        val newReader: Reader? =
            file.newPath?.takeIf { file.newText != null }?.let { path -> readers.firstOrNull { it.covers(path) } }
        val oldLines: List<String> by lazy { oldReader?.code(file.oldText.orEmpty()).orEmpty() }
        val newLines: List<String> by lazy { newReader?.code(file.newText.orEmpty()).orEmpty() }
    }

    /** One language, its patterns compiled, and how it drops comments before matching. */
    private class Reader(
        val language: TestLanguage,
        private val clock: Deadline,
    ) {
        private val files = ProtectedPaths(language.files)
        val test: Regex? = language.test?.let { Regex(it, RegexOption.MULTILINE) }
        val skip: Regex? = language.skip?.let { Regex(it, RegexOption.MULTILINE) }
        val assertion: Regex? = language.assertion?.let { Regex(it, RegexOption.MULTILINE) }

        fun covers(path: String): Boolean = files.match(path) != null

        /**
         * The names of the tests in the code [lines], in order: the first group that matched, else the whole match.
         * Each line is cut to [LINE_MAX], the same on both sides of the diff.
         */
        fun testNames(lines: List<String>): List<String> {
            val test = test ?: return emptyList()
            val text = lines.joinToString("\n") { it.take(LINE_MAX) }
            return test
                .findAll(clock.watch(text))
                .map { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: match.value.trim() }
                .toList()
        }

        /** [line] once per match of [pattern] on it; a line of [path] over [LINE_MAX] throws [DenyGuardsLimit]. */
        fun matches(
            line: String,
            pattern: Regex,
            path: String?,
        ): List<String> {
            if (line.length > LINE_MAX) {
                throw DenyGuardsLimit("a changed line of $path has ${line.length} characters, over the $LINE_MAX the deny guards read")
            }
            return List(pattern.findAll(clock.watch(line)).count()) { line }
        }

        /**
         * The lines of [text] with its comment lines blanked: a line that starts with the line comment marker, and a
         * block comment that starts a line, up to the line that closes it.
         */
        fun code(text: String): List<String> {
            val line = language.lineComment
            val block = language.blockComment
            var inBlock = false
            return text.lines().map { raw ->
                clock.check()
                val trimmed = raw.trim()
                when {
                    inBlock && block != null -> {
                        val end = raw.indexOf(block.second)
                        if (end < 0) {
                            ""
                        } else {
                            inBlock = false
                            raw.substring(end + block.second.length)
                        }
                    }
                    line != null && trimmed.startsWith(line) -> ""
                    block != null && trimmed.startsWith(block.first) -> {
                        val rest = trimmed.substring(block.first.length)
                        val end = rest.indexOf(block.second)
                        if (end < 0) {
                            inBlock = true
                            ""
                        } else {
                            rest.substring(end + block.second.length)
                        }
                    }
                    else -> raw
                }
            }
        }
    }

    /** The end of a pass's [budget]; [watch] makes a regular expression stop there, in the middle of a match. */
    private class Deadline(
        private val budget: Duration,
        start: Long,
    ) {
        private val end = start + budget.inWholeNanoseconds

        fun check() {
            if (System.nanoTime() - end > 0) {
                val seconds = budget.inWholeMilliseconds / 1000.0
                val shown = if (seconds % 1.0 == 0.0) seconds.toLong().toString() else seconds.toString()
                throw DenyGuardsLimit("the deny guards did not finish within $shown s")
            }
        }

        fun watch(text: CharSequence): CharSequence = Watched(text, 0, text.length)

        /** [text] from [start] to [end], checking the deadline every [EVERY] reads, which a matcher does all the time. */
        private inner class Watched(
            private val text: CharSequence,
            private val start: Int,
            private val end: Int,
        ) : CharSequence {
            private var reads = 0

            override val length: Int get() = end - start

            override fun get(index: Int): Char {
                if (++reads % EVERY == 0) check()
                return text[start + index]
            }

            override fun subSequence(
                startIndex: Int,
                endIndex: Int,
            ): CharSequence = Watched(text, start + startIndex, start + endIndex)

            override fun toString(): String = text.subSequence(start, end).toString()
        }
    }

    private const val EVERY = 4096

    /** A bag of strings that [take] removes one of at a time. */
    private class Multiset<T> {
        private val counts = HashMap<T, Int>()

        fun add(item: T) {
            counts[item] = (counts[item] ?: 0) + 1
        }

        /** Removes one [item]; false when there was none. */
        fun take(item: T): Boolean {
            val count = counts[item] ?: return false
            if (count == 1) counts.remove(item) else counts[item] = count - 1
            return true
        }
    }
}
