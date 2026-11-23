package loop.tracker.files

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The `files` tracker: one markdown file per issue in [issuesDir], with `id`, `title`,
 * `state`, `priority`, `labels` and `blocked_by` in its front matter and the body as
 * the description. A malformed file is skipped and passed to [onMalformed] when
 * fetching by states, and fails the call when fetching by ids.
 *
 * `identifier` is the front matter `id`; `createdAt` is always null, because file
 * times are reset by git checkout. A move rewrites the `state:` line only, through a
 * temporary file and an atomic rename, so a reader never sees a partial file.
 *
 * A comment is inserted as bytes at the end of the first column 0 `## Comments` section
 * (after the front matter, outside ``` and ~~~ fences; the section ends at the next
 * column 0 `#` or `##` heading), or in a new section before `## Progress`, or at the end.
 * An unclosed fence before the insert point makes the body malformed, so nothing is written.
 * Every other byte is kept, except a final newline added when missing.
 *
 * A label write (m2-11) replaces the front matter `labels` entry with one flow list line, or adds
 * that line before the closing `---` when there is none. The entry is found by parsing, so a quoted
 * key or a space before the colon is the same entry. It runs from its key line to the line before the
 * next key, without the blank and comment lines at its end: a comment on the key line or between the
 * items is replaced with the entry, every other byte is kept. The new text must parse back to the
 * intended labels in order with every other field unchanged, else the write fails with
 * [TrackerError.Malformed] and nothing is written. Nothing is written when the label set does not change.
 *
 * A move, a comment or a label write reads, changes and renames under one lock, so none loses another.
 */
class FilesTracker(
    private val issuesDir: Path,
    private val lockDir: Path,
    private val onMalformed: (TrackerError.Malformed) -> Unit = {},
    /** Gets one operator warning per issue and missing or malformed blocker pair, the first time it is seen. */
    private val onWarning: (String) -> Unit = {},
) : TrackerAdapter {
    override val definedStates: List<String> = DEFINED_STATES

    /** Issue and blocker pairs already warned about, so each is warned once per process. */
    private val warnedMissing = mutableSetOf<Pair<String, String>>()

    /** An empty [states] list returns empty without reading the folder (spec 17.3). */
    override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
        if (states.isEmpty()) return Result.success(emptyList())
        return io {
            val wanted = states.map { it.lowercase() }.toSet()
            val all = issueFiles().map { it to parse(it) }
            val blockers = Blockers.of(all)
            all.map { it.second }.mapNotNull { parsed ->
                parsed.fold(
                    onSuccess = { it.takeIf { issue -> issue.state.lowercase() in wanted }?.withBlockedReason(blockers) },
                    onFailure = {
                        onMalformed(it as TrackerError.Malformed)
                        null
                    },
                )
            }
        }
    }

    /**
     * What the blocker check knows, read once per call: every well formed issue's id to its state,
     * and the files that do not parse, which claim a blocker id by name or raw `id:` line.
     */
    private class Blockers(
        val stateById: Map<String, String>,
        val malformedFiles: List<Path>,
    ) {
        companion object {
            fun of(all: List<Pair<Path, Result<Issue>>>): Blockers =
                Blockers(
                    stateById = all.mapNotNull { it.second.getOrNull() }.associate { it.id to it.state },
                    malformedFiles = all.filter { it.second.isFailure }.map { it.first },
                )
        }
    }

    /**
     * Sets [Issue.blockedReason] from the first blocker in `blocked_by` order that is not `done`.
     * Only `done` resolves a blocker: `canceled`, every other state and a missing or malformed
     * blocker file keep the issue blocked (fail closed). A missing or malformed blocker is warned about once.
     */
    private fun Issue.withBlockedReason(blockers: Blockers): Issue {
        for (blocker in blockedBy) {
            val state = blockers.stateById[blocker]
            if (state == null) {
                val malformed = blockers.malformedFiles.any { claimsAnyId(it, setOf(blocker)) }
                val detail = if (malformed) "has an issue file that cannot be read" else "has no issue file"
                warnOnce(this, blocker, "$identifier: blocked_by $blocker $detail, so $identifier stays blocked.")
                return copy(blockedReason = "blocked_by $blocker (${if (malformed) "malformed" else "missing"})")
            }
            if (state.lowercase() != DONE) return copy(blockedReason = "blocked_by $blocker ($state)")
        }
        return this
    }

    private fun warnOnce(
        issue: Issue,
        blocker: String,
        message: String,
    ) {
        val first = synchronized(warnedMissing) { warnedMissing.add(issue.id to blocker) }
        if (first) onWarning(message)
    }

    /** An empty [ids] list returns empty without reading the folder (spec 17.3). */
    override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
        if (ids.isEmpty()) return Result.success(emptyList())
        return io {
            val wanted = ids.toSet()
            val found = mutableListOf<Issue>()
            val all = issueFiles().map { it to parse(it) }
            val blockers = Blockers.of(all)
            for ((file, parsed) in all) {
                val issue = parsed.getOrNull()
                if (issue != null) {
                    if (issue.id in wanted) found += issue.withBlockedReason(blockers)
                } else if (claimsAnyId(file, wanted)) {
                    throw parsed.exceptionOrNull()!!
                }
            }
            found
        }
    }

    override suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> {
        if (toState !in definedStates) {
            return Result.failure(TrackerError.UndefinedState(toState, definedStates))
        }
        return io {
            locked {
                val file = fileOf(issueId)
                val rewritten = rewriteState(file.readText(), toState) ?: throw TrackerError.Malformed(file.toString())
                writeAtomically(file, rewritten.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun fileOf(issueId: String): Path = find(issueId).file

    /** An issue with its `labels` as written, before normalization, and the front matter it was read from. */
    private class Parsed(
        val issue: Issue,
        val rawLabels: List<String>,
        val frontMatter: YamlMap,
    )

    /** A well formed issue file with the text it was parsed from, read once. */
    private class Found(
        val file: Path,
        val text: String,
        val parsed: Parsed,
    )

    private fun find(issueId: String): Found =
        issueFiles().firstNotNullOfOrNull { file ->
            val text = file.readText()
            parseText(file, text).getOrNull()?.takeIf { it.issue.id == issueId }?.let { Found(file, text, it) }
        } ?: throw TrackerError.NotFound(issueId)

    /**
     * Runs [block] holding the write lock: a JVM lock for threads (a file lock alone
     * throws when the same JVM asks twice) and an exclusive lock on a file in
     * the machine store ([lockDir]) for other processes. Read, change and rename happen inside it.
     */
    private fun <T> locked(block: () -> T): T =
        JVM_LOCK.withLock {
            FileChannel.open(lockFile(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { block() }
            }
        }

    /**
     * The lock file lives in [lockDir], keyed by the issues folder, so it never shows in the user's
     * checkout. [lockDir] is `<loop home>/locks`: the same machine store the tool process already
     * opens, so every process agrees on it, it is private to the user, and an agent sandbox that
     * redirects the temp folder cannot split the lock.
     */
    private fun lockFile(): Path {
        val key = issuesDir.toRealPath().toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val short = digest.take(LOCK_KEY_BYTES).joinToString("") { "%02x".format(it) }
        Files.createDirectories(lockDir)
        return lockDir.resolve("tracker-$short.lock")
    }

    override suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> {
        val entry = formatEntry(comment) ?: return Result.failure(TrackerError.BlankComment())
        return io {
            locked {
                val file = fileOf(issueId)
                val rewritten =
                    insertComment(file.readText(), entry) ?: throw TrackerError.Malformed(file.toString())
                writeAtomically(file, rewritten.toByteArray(Charsets.UTF_8))
            }
        }
    }

    override suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> {
        val adding = add.map { it.trim() }.filter { it.isNotEmpty() }
        val removing = remove.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return io {
            locked {
                val found = find(issueId)
                val current = found.parsed.rawLabels
                val kept = current.filterNot { it.trim().lowercase() in removing }
                val labels =
                    adding.fold(kept) { acc, label ->
                        if (acc.any { it.trim().equals(label, ignoreCase = true) }) acc else acc + label
                    }
                if (labels != current) {
                    val rewritten =
                        rewriteLabels(found.text, found.parsed, labels) ?: throw TrackerError.Malformed(found.file.toString())
                    writeAtomically(found.file, rewritten.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun issueFiles(): List<Path> =
        issuesDir
            .listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "md" && !it.name.startsWith(".") }
            .sortedBy { it.name }

    private fun parse(file: Path): Result<Issue> = parseText(file, file.readText()).map { it.issue }

    private fun parseText(
        file: Path,
        text: String,
    ): Result<Parsed> =
        try {
            Result.success(parseIssue(text) ?: throw TrackerError.Malformed(file.toString()))
        } catch (e: TrackerError.Malformed) {
            Result.failure(e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // kaml throws its own exception types for unparseable yaml.
            Result.failure(TrackerError.Malformed(file.toString(), e))
        }

    /** A malformed file counts for an id refresh when its name or a raw `id:` line names a requested id. */
    private fun claimsAnyId(
        file: Path,
        ids: Set<String>,
    ): Boolean {
        if (ids.any { file.name == "$it.md" || file.name.startsWith("$it-") }) return true
        val rawId =
            runCatching { file.readText() }
                .getOrNull()
                ?.let {
                    ID_LINE
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.trim('"', '\'')
                }
        return rawId != null && rawId in ids
    }

    private fun writeAtomically(
        file: Path,
        bytes: ByteArray,
    ) {
        val tmp = Files.createTempFile(issuesDir, ".${file.name}.", ".tmp")
        try {
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file))
            } catch (e: UnsupportedOperationException) {
                // Not a POSIX file system: the temp file keeps its default permissions.
            }
            Files.write(tmp, bytes)
            Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private suspend fun <T> io(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (e: TrackerError) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(TrackerError.Unavailable(e))
            }
        }

    companion object {
        val DEFINED_STATES: List<String> = listOf("todo", "in_progress", "review", "needs_info", "done", "canceled")

        private const val DONE = "done"
        private const val LOCK_KEY_BYTES = 8
        private val JVM_LOCK = ReentrantLock()
        private val LINE_START = Regex("(?<=\n)")
        private val NEWLINE = Regex("\r\n|\n|\r")
        private val HEADING = Regex("^#{1,2}( .*|\t.*)?$")

        private const val LABELS = "labels"
        private val PLAIN_LABEL = Regex("""[A-Za-z0-9]([A-Za-z0-9 _./-]*[A-Za-z0-9_./-])?""")
        private val YAML_NULLS = setOf("null", "~")

        private val ID_LINE = Regex("""^id:(.*)$""", RegexOption.MULTILINE)

        private class FrontMatter(
            val yaml: String,
            val body: String,
        )

        private fun splitFrontMatter(text: String): FrontMatter? {
            val lines = text.split("\n").map { it.removeSuffix("\r") }
            if (lines.firstOrNull() != "---") return null
            val end = (1 until lines.size).firstOrNull { lines[it] == "---" } ?: return null
            return FrontMatter(
                yaml = lines.subList(1, end).joinToString("\n"),
                body = lines.drop(end + 1).joinToString("\n"),
            )
        }

        /** Null when a required field is missing or empty. Throws kaml's exceptions for unparseable yaml. */
        private fun parseIssue(text: String): Parsed? {
            val frontMatter = splitFrontMatter(text) ?: return null
            if (frontMatter.yaml.isBlank()) return null
            val map = Yaml.default.parseToYamlNode(frontMatter.yaml) as? YamlMap ?: return null
            val id = map.text("id") ?: return null
            val title = map.text("title") ?: return null
            val state = map.text("state") ?: return null
            val rawLabels = map.textList(LABELS) ?: return null
            val issue =
                Issue(
                    id = id,
                    identifier = id,
                    title = title,
                    state = state,
                    priority = map.text("priority")?.toIntOrNull(),
                    labels = rawLabels.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                    blockedBy = map.textList("blocked_by") ?: return null,
                    description = frontMatter.body.trim().ifEmpty { null },
                    createdAt = null,
                )
            return Parsed(issue, rawLabels, map)
        }

        private fun YamlMap.text(key: String): String? = (get<YamlNode>(key) as? YamlScalar)?.content?.trim()?.ifEmpty { null }

        /** Empty when absent, null when not a list of scalars. */
        private fun YamlMap.textList(key: String): List<String>? =
            when (val node = get<YamlNode>(key)) {
                null, is YamlNull -> emptyList()
                is YamlList -> node.items.map { (it as? YamlScalar)?.content ?: return null }
                else -> null
            }

        /** `YYYY-MM-DD HH:MMZ author: first line`, then the other lines indented two spaces. Null when blank. */
        private fun formatEntry(comment: IssueComment): List<String>? {
            val lines =
                comment.text
                    .split(NEWLINE)
                    .map { it.trimEnd() }
                    .filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val iso = comment.at.toString()
            val first = "${iso.substring(0, 10)} ${iso.substring(11, 16)}Z ${comment.author}: ${lines.first().trimStart()}"
            return listOf(first) + lines.drop(1).map { "  $it" }
        }

        /**
         * Inserts [entry] at the end of the first `## Comments` section, every other byte
         * kept. Null when there is no front matter or a fence is left open before the insert
         * point. See the class doc for the rules.
         */
        private fun insertComment(
            text: String,
            entry: List<String>,
        ): String? {
            val lines = text.split(LINE_START).filter { it.isNotEmpty() }
            val eol = if (lines.firstOrNull()?.endsWith("\r\n") == true) "\r\n" else "\n"

            fun content(line: String) = line.trimEnd('\r', '\n')
            if (lines.isEmpty() || content(lines[0]) != "---") return null
            val fmEnd = (1 until lines.size).firstOrNull { content(lines[it]) == "---" } ?: return null

            // Column 0 headings (# and ##) outside fences, after the front matter.
            val headings = mutableListOf<Int>()
            var fence: Char? = null
            var fenceStart = -1
            for (i in fmEnd + 1 until lines.size) {
                val line = content(lines[i])
                val marker = line.firstOrNull()?.takeIf { (it == '`' || it == '~') && line.startsWith("$it$it$it") }
                when {
                    fence != null -> if (marker == fence) fence = null
                    marker != null -> {
                        fence = marker
                        fenceStart = i
                    }
                    HEADING.matches(line) -> headings += i
                }
            }
            val block = entry.joinToString("") { it + eol }
            val lastIndex = lines.lastIndex

            fun terminated(index: Int) = if (lines[index].endsWith("\n")) lines[index] else lines[index] + eol

            val comments = headings.firstOrNull { content(lines[it]).trimEnd() == "## Comments" }
            val out = StringBuilder()
            if (comments != null) {
                val end = headings.firstOrNull { it > comments } ?: lines.size
                val last = (comments + 1 until end).lastOrNull { content(lines[it]).isNotBlank() }
                val at = if (last != null) last + 1 else end
                if (fence != null && fenceStart < at) return null
                for (i in 0 until at - 1) out.append(lines[i])
                out.append(terminated(at - 1))
                if (last != null) out.append(eol)
                out.append(block)
                if (at < lines.size && content(lines[at]).isNotBlank()) out.append(eol)
                for (i in at..lastIndex) out.append(lines[i])
            } else {
                val progress = headings.firstOrNull { content(lines[it]).trimEnd() == "## Progress" } ?: lines.size
                if (fence != null && fenceStart < progress) return null
                for (i in 0 until progress - 1) out.append(lines[i])
                out.append(terminated(progress - 1))
                if (content(lines[progress - 1]).isNotBlank()) out.append(eol)
                out
                    .append("## Comments")
                    .append(eol)
                    .append(eol)
                    .append(block)
                if (progress < lines.size) out.append(eol)
                for (i in progress..lastIndex) out.append(lines[i])
            }
            return out.toString()
        }

        /**
         * The text of [before] with its `labels` entry replaced by one flow list line of [labels], or that line
         * added before the closing `---` when there is no entry. See the class doc for the entry's lines.
         * Null when the new text does not parse back to [labels] in order with every other field unchanged.
         */
        private fun rewriteLabels(
            text: String,
            before: Parsed,
            labels: List<String>,
        ): String? {
            val lines = text.split(LINE_START).filter { it.isNotEmpty() }

            fun content(line: String) = line.trimEnd('\r', '\n')
            if (lines.isEmpty() || content(lines[0]) != "---") return null
            val fmEnd = (1 until lines.size).firstOrNull { content(lines[it]) == "---" } ?: return null
            val eol = if (lines[0].endsWith("\r\n")) "\r\n" else "\n"
            val line = "labels: [${labels.joinToString(", ") { flowItem(it) }}]$eol"
            // kaml lines count from 1 at the first front matter line, which is lines[1].
            val keys = before.frontMatter.entries.keys
            val key = keys.firstOrNull { it.content == LABELS }
            val rewritten =
                if (key == null) {
                    (lines.subList(0, fmEnd) + line + lines.subList(fmEnd, lines.size)).joinToString("")
                } else {
                    val start = key.location.line
                    val next = keys.map { it.location.line }.filter { it > start }.minOrNull() ?: fmEnd
                    var end = maxOf(next, start + 1)
                    while (end - 1 > start && content(lines[end - 1]).trim().let { it.isEmpty() || it.startsWith("#") }) end--
                    val indent = content(lines[start]).takeWhile { it == ' ' || it == '\t' }
                    (lines.subList(0, start) + (indent + line) + lines.subList(end, lines.size)).joinToString("")
                }
            val after =
                try {
                    parseIssue(rewritten)
                } catch (e: Exception) {
                    // kaml throws its own exception types for unparseable yaml.
                    null
                } ?: return null
            val unchanged =
                after.rawLabels == labels &&
                    after.frontMatter.otherFields() == before.frontMatter.otherFields() &&
                    after.issue.description == before.issue.description
            return rewritten.takeIf { unchanged }
        }

        /** Every front matter field but `labels`, in order, as key and content. */
        private fun YamlMap.otherFields(): List<Pair<String, String>> =
            entries.filterKeys { it.content != LABELS }.map { (key, value) -> key.content to value.contentToString() }

        /** A label as a YAML flow list item: plain when that reads back as the same text, double quoted otherwise. */
        private fun flowItem(label: String): String =
            if (PLAIN_LABEL.matches(label) && label.lowercase() !in YAML_NULLS) {
                label
            } else {
                "\"" + label.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }

        /** Replaces the first `state:` line of the front matter, every other byte kept. Null when there is none. */
        private fun rewriteState(
            text: String,
            toState: String,
        ): String? {
            var start = 0
            var lineNo = 0
            while (true) {
                val newline = text.indexOf('\n', start)
                val end = if (newline == -1) text.length else newline
                val raw = text.substring(start, end)
                val line = raw.removeSuffix("\r")
                when {
                    lineNo == 0 && line != "---" -> return null
                    lineNo > 0 && line == "---" -> return null
                    lineNo > 0 && line.startsWith("state:") -> {
                        val lineEnd = if (raw.endsWith("\r")) "\r" else ""
                        return text.substring(0, start) + "state: $toState" + lineEnd + text.substring(end)
                    }
                }
                if (newline == -1) return null
                start = newline + 1
                lineNo++
            }
        }
    }
}
