package loop.core.verify

import loop.core.workflow.CheckConfig
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * The parts of the `changed_line_coverage` check (bl-117) that need no git: the changed lines of a `git diff -U0`,
 * the JaCoCo XML reports, which Kover writes too, and the verdict with its output. [CoreChecks.changedLineCoverage]
 * runs git and puts them together.
 */
internal object ChangedLineCoverage {
    /** The changed lines of one file: line numbers in the new file, or every line when [whole], an untracked file. */
    data class ChangedFile(
        val path: String,
        val lines: Set<Int>,
        val whole: Boolean = false,
    )

    /**
     * The added and changed lines of each file of a `git diff -U0` patch, by the new path. A deleted file and a pure
     * deletion give no line, and a file whose only hunks delete lines is left out.
     */
    fun changedLines(patch: String): List<ChangedFile> {
        val files = linkedMapOf<String, MutableSet<Int>>()
        var path: String? = null
        var oldLeft = 0
        var newLeft = 0
        // Only a line feed ends a line: with --text, a binary file's added lines may hold a lone carriage return.
        for (line in patch.splitToSequence('\n')) {
            if (oldLeft > 0 || newLeft > 0) {
                when {
                    line.startsWith("-") -> oldLeft--
                    line.startsWith("+") -> newLeft--
                    line.startsWith("\\") -> Unit
                    else -> throw IOException("git diff gave an unexpected line inside a hunk: ${line.take(LINE_SHOWN)}")
                }
                continue
            }
            when {
                line.startsWith("diff ") -> path = null
                line.startsWith("+++ ") -> path = newPath(line.removePrefix("+++ "))
                line.startsWith("@@ ") -> {
                    val hunk = HUNK.find(line) ?: throw IOException("git diff gave a hunk header it cannot read: ${line.take(LINE_SHOWN)}")
                    oldLeft = hunk.groupValues[1].ifEmpty { "1" }.toInt()
                    val start = hunk.groupValues[2].toInt()
                    newLeft = hunk.groupValues[3].ifEmpty { "1" }.toInt()
                    val target = path ?: continue
                    if (newLeft > 0) files.getOrPut(target) { sortedSetOf() } += start until start + newLeft
                }
            }
        }
        return files.map { (file, lines) -> ChangedFile(file, lines) }
    }

    /** `@@ -<start>[,<count>] +<start>[,<count>] @@`, the old count, the new start and the new count. */
    private val HUNK = Regex("""^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    private const val LINE_SHOWN = 80

    /** The path of a `+++` line, `b/` prefix dropped, unquoted when git quoted it; null for `/dev/null`. */
    private fun newPath(written: String): String? {
        val text = if (written.startsWith("\"")) unquote(written) else written.trimEnd('\t')
        if (text == "/dev/null") return null
        return text.removePrefix("b/")
    }

    /** A path git quoted C style: `\"`, `\\`, the usual letter escapes and octal bytes, read as UTF-8. */
    private fun unquote(quoted: String): String {
        val body = quoted.removePrefix("\"").substringBeforeLast('"')
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val next = body[i + 1]
            if (next in '0'..'7') {
                val digits = body.substring(i + 1).takeWhile { it in '0'..'7' }.take(OCTAL_DIGITS)
                bytes.write(digits.toInt(OCTAL))
                i += 1 + digits.length
                continue
            }
            bytes.write(ESCAPES[next]?.code ?: next.code)
            i += 2
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private const val OCTAL = 8
    private const val OCTAL_DIGITS = 3
    private val ESCAPES =
        mapOf('a' to '\u0007', 'b' to '\b', 't' to '\t', 'n' to '\n', 'v' to '\u000b', 'f' to '\u000c', 'r' to '\r')

    /** The report files the [globs] find under [root], or the first glob that finds none. */
    data class Found(
        val files: List<Path>,
        val missing: String?,
    )

    /**
     * The regular files under [root] that the [globs] match, anchored at [root] with `*` and `?` within one segment
     * and `**` across segments. Symlinks are never followed or taken and `.git` folders are skipped. Each glob must
     * find at least one file: a report the build did not write must not leave its lines out silently.
     */
    fun find(
        root: Path,
        globs: List<String>,
    ): Found {
        val files = sortedSetOf<Path>()
        for (glob in globs) {
            val matcher = ProtectedPaths(listOf(glob), coversFolders = false)
            val segments = glob.split('/')
            val literal = segments.takeWhile { '*' !in it && '?' !in it }
            val start = root.resolve(literal.joinToString("/"))
            val matched = mutableListOf<Path>()
            if (literal.size == segments.size) {
                if (Files.isRegularFile(start, LinkOption.NOFOLLOW_LINKS)) matched.add(start)
            } else if (Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(start, ReportVisitor(root, matcher, matched))
            }
            val inside = matched.filter { it.toRealPath().startsWith(root) }
            if (inside.isEmpty()) return Found(emptyList(), glob)
            files += inside
        }
        return Found(files.toList(), null)
    }

    /** Adds to [matched] the regular files under a folder whose path from [root] the [matcher] takes, `.git` skipped. */
    private class ReportVisitor(
        private val root: Path,
        private val matcher: ProtectedPaths,
        private val matched: MutableList<Path>,
    ) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult = if (dir.fileName?.toString() == GIT_DIR) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            if (attrs.isRegularFile && matcher.match(root.relativize(file).joinToString("/")) != null) matched.add(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(
            file: Path,
            exc: IOException,
        ): FileVisitResult = FileVisitResult.CONTINUE
    }

    private const val GIT_DIR = ".git"

    /** A report or changed source file Loop could not read, or a report that is not JaCoCo XML; the check could not run. */
    class Unreadable(
        message: String,
    ) : IOException(message)

    /**
     * The countable lines of every source file of the [reports], by `<package path>/<file name>`, each with true when
     * it is covered. A line counts when it has instructions, missed or covered, and is covered when one of them is.
     * The reports are merged: a line is countable when one report lists it with instructions and covered when one
     * report covers it, so per module reports and an aggregated one add up.
     */
    fun read(
        root: Path,
        reports: List<Path>,
    ): Map<String, Map<Int, Boolean>> {
        val merged = HashMap<String, HashMap<Int, Boolean>>()
        for (report in reports) {
            val shown = root.relativize(report).joinToString("/")
            val handler = JacocoHandler(shown, merged)
            try {
                Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS).use { input -> parser().parse(input, handler) }
            } catch (e: Unreadable) {
                throw e
            } catch (e: SAXException) {
                throw Unreadable("cannot parse the coverage report $shown: ${e.message?.lineSequence()?.first() ?: e::class.simpleName}")
            } catch (e: IOException) {
                throw Unreadable("cannot read the coverage report $shown: ${e.message ?: e::class.simpleName}")
            }
            if (!handler.sawReport) throw Unreadable("cannot parse the coverage report $shown: it has no root element")
        }
        return merged
    }

    /**
     * A SAX parser that never reads a file or an address an XML document names: JaCoCo writes a DOCTYPE with the
     * external `report.dtd`, which must not be fetched, and no entity of a report may expand to a file.
     */
    private fun parser() =
        try {
            SAXParserFactory
                .newInstance()
                .apply {
                    isNamespaceAware = false
                    isValidating = false
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }.newSAXParser()
        } catch (e: ParserConfigurationException) {
            throw Unreadable("cannot set up a safe XML parser: ${e.message}")
        }

    /** Reads `report > package > sourcefile > line` elements into [merged]. */
    private class JacocoHandler(
        private val shown: String,
        private val merged: HashMap<String, HashMap<Int, Boolean>>,
    ) : DefaultHandler() {
        var sawReport = false
        private var depth = 0
        private var pkg: String? = null
        private var source: HashMap<Int, Boolean>? = null

        override fun resolveEntity(
            publicId: String?,
            systemId: String?,
        ): InputSource = InputSource(StringReader(""))

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String,
            attributes: Attributes,
        ) {
            depth++
            if (depth == 1) {
                if (qName != "report") throw Unreadable("the coverage report $shown is not a JaCoCo XML report, its root is <$qName>")
                sawReport = true
                return
            }
            when (qName) {
                "package" -> if (depth == 2) pkg = attributes.required("name", qName)
                "sourcefile" -> {
                    val pkg = pkg
                    if (depth == 3 && pkg != null) {
                        val name = attributes.required("name", qName)
                        source = merged.getOrPut(if (pkg.isEmpty()) name else "$pkg/$name") { HashMap() }
                    }
                }
                "line" -> {
                    val source = source
                    if (depth == 4 && source != null) {
                        val nr = attributes.number("nr")
                        val missed = attributes.number("mi")
                        val covered = attributes.number("ci")
                        if (missed + covered > 0) source[nr] = source[nr] == true || covered > 0
                    }
                }
            }
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qName: String,
        ) {
            when {
                depth == 2 && qName == "package" -> pkg = null
                depth == 3 && qName == "sourcefile" -> source = null
            }
            depth--
        }

        private fun Attributes.required(
            name: String,
            element: String,
        ): String = getValue(name) ?: throw SAXException("a <$element> element has no $name attribute")

        private fun Attributes.number(name: String): Int {
            val text = required(name, "line")
            return text.toIntOrNull()?.takeIf { it >= 0 } ?: throw SAXException("a <line> element has $name=\"${text.take(LINE_SHOWN)}\"")
        }
    }

    /**
     * The package path, `app/tools`, that the first `package` clause of the changed file at [path] under [root]
     * declares, the empty path when it declares none, or null when it is not a regular file. JaCoCo keys a source file
     * by this package, not by its folder, so a file whose folder does not match its package is still found.
     */
    fun declaredPackage(
        root: Path,
        path: String,
    ): String? {
        val file = root.resolve(path)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val head =
            try {
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { String(it.readNBytes(HEAD_BYTES), Charsets.UTF_8) }
            } catch (e: IOException) {
                throw Unreadable("cannot read the changed file $path: ${e.message ?: e::class.simpleName}")
            }
        val name = PACKAGE.find(head)?.groupValues?.get(1) ?: return ""
        return name.replace("`", "").replace('.', '/')
    }

    /** A Kotlin, Java, Groovy or Scala `package` clause at the start of a line, its dotted name captured. */
    private val PACKAGE = Regex("""^[ \t]*package[ \t]+([\p{L}_`][\p{L}\p{N}_.`]*)""", RegexOption.MULTILINE)

    /** The `package` clause is looked for in this many first bytes of a file. */
    private const val HEAD_BYTES = 65_536

    /** What the check found: whether it [passed] and the [output] that says so. */
    data class Verdict(
        val passed: Boolean,
        val output: String,
    )

    /**
     * Judges the [changed] files against the [coverage] of [reportCount] reports: it passes when the covered changed
     * lines are at least [minPercent] percent of the countable ones, and when none counts. A changed file matches a
     * report's source file when its path ends with the source file's package path and name, the longest such match
     * winning, else when its extension is one the reports use and [packageOf] gives the package path it declares
     * (see [declaredPackage]) and that path and its name are a report's key. [against] names the diff in the output. The uncovered lines come first and the summary last, so the
     * findings, which keep the tail of the output, show both.
     */
    fun judge(
        changed: List<ChangedFile>,
        coverage: Map<String, Map<Int, Boolean>>,
        reportCount: Int,
        minPercent: Double,
        against: String,
        packageOf: (String) -> String?,
    ): Verdict {
        val byName = coverage.keys.groupBy { it.substringAfterLast('/') }
        val extensions =
            coverage.keys
                .mapNotNull { key ->
                    key.substringAfterLast('/').substringAfterLast('.', "").ifEmpty { null }
                }.toSet()
        val uncovered = mutableListOf<Pair<String, List<Int>>>()
        val unreported = mutableListOf<String>()
        var covered = 0
        var countable = 0
        for (file in changed.sortedBy { it.path }) {
            val name = file.path.substringAfterLast('/')
            val reportable = name.substringAfterLast('.', "") in extensions
            val byPath =
                byName[name]
                    .orEmpty()
                    .filter { file.path == it || file.path.endsWith("/$it") }
                    .maxByOrNull { it.length }
            val byPackage =
                if (byPath != null || !reportable) {
                    null
                } else {
                    packageOf(file.path)?.let { if (it.isEmpty()) name else "$it/$name" }?.takeIf { it in coverage }
                }
            val key = byPath ?: byPackage
            if (key == null) {
                if (reportable) unreported += file.path
                continue
            }
            val lines = coverage.getValue(key)
            val counted = if (file.whole) lines.keys.sorted() else file.lines.filter { it in lines }.sorted()
            countable += counted.size
            covered += counted.count { lines.getValue(it) }
            val missed = counted.filter { !lines.getValue(it) }
            if (missed.isNotEmpty()) uncovered += file.path to missed
        }
        val read = if (reportCount == 1) "1 coverage report read." else "$reportCount coverage reports read."
        val notes = mutableListOf<String>()
        if (unreported.isNotEmpty()) {
            val count = if (unreported.size == 1) "1 changed file is" else "${unreported.size} changed files are"
            val names = unreported.take(UNREPORTED_SHOWN).joinToString(", ")
            val more = if (unreported.size > UNREPORTED_SHOWN) " and ${unreported.size - UNREPORTED_SHOWN} more" else ""
            notes += "$count in no coverage report: $names$more. Their lines do not count."
        }
        if (countable == 0) {
            val summary = "$against: no changed line is listed with instructions in a coverage report, nothing to cover. $read"
            return Verdict(true, (notes + summary).joinToString("\n"))
        }
        val passed = covered * PERCENT >= minPercent * countable
        val floor = "the floor of ${CheckConfig.percent(minPercent)}%"
        val rate = "$covered of $countable changed lines covered, ${tenths(covered, countable)}%"
        val summary = "$against: $rate, ${if (passed) "at or above" else "below"} $floor. $read"
        if (passed) return Verdict(true, (notes + summary).joinToString("\n"))
        val shown = uncovered.take(FILES_SHOWN).map { (path, lines) -> "$path: ${ranges(lines)}" }
        val more = if (uncovered.size > FILES_SHOWN) listOf("and ${uncovered.size - FILES_SHOWN} more files") else emptyList()
        return Verdict(false, (listOf("Uncovered changed lines:") + shown + more + notes + summary).joinToString("\n"))
    }

    /** At most this many files with uncovered lines are named, so the findings' last lines hold them and the summary. */
    const val FILES_SHOWN = 15

    /** At most this many line ranges are named per file. */
    const val RANGES_SHOWN = 10

    private const val UNREPORTED_SHOWN = 3

    private const val PERCENT = 100.0

    /** [part] of [whole] in percent, rounded down to a tenth, `33.3` or `50`, so a rate under the floor never reads as it. */
    private fun tenths(
        part: Int,
        whole: Int,
    ): String {
        val value = part.toLong() * TENTHS_SCALE / whole
        return if (value % 10 == 0L) "${value / 10}" else "${value / 10}.${value % 10}"
    }

    private const val TENTHS_SCALE = 1000L

    /** Sorted line numbers as ranges, `3, 5-7`, the first [RANGES_SHOWN] of them and how many lines are left. */
    private fun ranges(lines: List<Int>): String {
        val ranges = mutableListOf<IntRange>()
        for (line in lines) {
            val last = ranges.lastOrNull()
            if (last != null && line == last.last + 1) ranges[ranges.size - 1] = last.first..line else ranges += line..line
        }
        val text = ranges.take(RANGES_SHOWN).joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
        val left = ranges.drop(RANGES_SHOWN).sumOf { it.last - it.first + 1 }
        return if (left > 0) "$text and $left more lines" else text
    }
}
