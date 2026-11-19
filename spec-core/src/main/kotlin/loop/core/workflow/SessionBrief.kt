package loop.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.Issue
import loop.api.LedgerRow
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.ResultSubmitted
import loop.core.events.Results
import loop.core.events.Verdict
import loop.core.kinds.MarkdownSections
import loop.core.orchestrator.attemptsFolder
import loop.core.verify.WorkspaceGit
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence

/**
 * The session brief of plan 9.2 (m3-10): what a fresh session of an issue gets on a retry, on a reused workspace and,
 * from m3-11, after a rotation. Loop builds it from what it recorded itself, never from the agent's memory, in five
 * parts, in this order:
 *
 * 1. the goal and the acceptance criteria of the issue;
 * 2. the commits and the diff stat of the workspace against the base, its uncommitted changes, or the file list of a
 *    workspace that is not a git work tree;
 * 3. the latest result of every check of the newest earlier attempt with a verdict, that verdict and the findings;
 * 4. the counters: earlier attempts, the rotations of the current attempt (m3-11) and the spend of the earlier attempts;
 * 5. last, the agent's own notes, `notes_for_next_attempt` of its last submitted result and the tail of
 *    [PROGRESS_FILE], marked as unverified.
 *
 * Each part is bounded in characters (the bounds are the constants of the companion). A part that
 * cannot be read is one line saying so, `<part>: not available, <reason>.`; [build] never throws but to propagate
 * cancellation. The whole text goes through [redactor]. Its git and file reads block: the caller runs it on IO.
 */
class SessionBrief(
    private val git: WorkspaceGit,
    private val ledger: AttemptLedger,
    /** Every event of an attempt, oldest first. */
    private val events: suspend (AttemptId) -> Result<List<RecordedEvent>>,
    /** The text of a saved result file by the reference its `result_submitted` event names; throws when it cannot be read. */
    private val savedResult: (String) -> String,
    private val redactor: Redactor,
) {
    /**
     * The brief of [issue] in its [workspace]. [base] is the commit or ref part 2 diffs against; [findings] are the
     * verify findings the attempt renders, null without a refutation. [rotations] counts the rotations of the current
     * attempt so far (m3-11), 0 outside a rotation.
     */
    suspend fun build(
        projectId: ProjectId,
        issue: Issue,
        workspace: Path,
        base: String,
        findings: String?,
        rotations: Int = 0,
    ): String {
        val rows = read { ledger.attemptRows(projectId, issue.id).getOrThrow() }.mapFailure { "cannot read the ledger: $it" }
        // Newest first, a few at most: the facts of the latest attempts are the ones that matter.
        val recent =
            rows
                .getOrNull()
                .orEmpty()
                .takeLast(RECENT_ATTEMPTS)
                .reversed()
        val eventsOf = mutableMapOf<AttemptId, List<RecordedEvent>>()
        val recentEvents: suspend () -> List<Pair<LedgerRow, List<RecordedEvent>>> = {
            recent.map { row -> row to eventsOf.getOrPut(row.attempt.attemptId) { events(row.attempt.attemptId).getOrThrow() } }
        }
        val parts =
            listOf(
                part(GOAL, Result.success(goal(issue))),
                part(WORKSPACE, read { workspace(workspace, base) }),
                part(CHECKS, rows.flatMap { read { checks(recentEvents(), findings) } }),
                part(COUNTERS, rows.flatMap { read { counters(it, rotations) } }),
                part(NOTES, read { notes(workspace, rows.flatMap { read { lastNotes(recentEvents()) } }) }),
            )
        return redactor.redact(parts.joinToString("\n\n"))
    }

    private fun part(
        title: String,
        text: Result<String>,
    ): String =
        text.fold(
            onSuccess = { "### $title\n${cut(it, BOUNDS.getValue(title))}" },
            onFailure = { "$title: not available, ${line(it.message ?: it::class.simpleName ?: "unknown error").trimEnd('.')}." },
        )

    private fun goal(issue: Issue): String {
        val lines = issue.description.orEmpty().lines()
        val section = MarkdownSections.sectionsOpeningWith(lines, ACCEPTANCE_CRITERIA).firstOrNull()
        val criteria =
            section
                ?.let { range -> lines.slice(range).joinToString("\n").trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: "The issue has no acceptance criteria section."
        return "${issue.identifier}: ${issue.title}\n\n$criteria"
    }

    private fun workspace(
        root: Path,
        base: String,
    ): String {
        val top = git.run(root, "rev-parse", "--show-toplevel")
        val tracked = top.code == 0 && runCatching { Path.of(top.stdout.trim()).toRealPath() == root.toRealPath() }.getOrDefault(false)
        if (!tracked) return files(root)
        val baseCommit =
            git
                .run(root, "rev-parse", "--verify", "-q", "$base^{commit}")
                .takeIf { it.code == 0 }
                ?.stdout
                ?.trim() ?: throw IOException("the base $base is not a commit of this workspace")
        val log = git.run(root, "log", "--format=%h %s", "--max-count=${MAX_COMMITS + 1}", "$baseCommit..HEAD", "--")
        if (log.code != 0) throw IOException("git log exited with code ${log.code}: ${log.firstLine()}")
        val commits = log.stdout.lines().filter { it.isNotBlank() }
        val diff = git.diffStat(root, baseCommit, "HEAD")
        val status = git.run(root, "status", "--porcelain=v1", "--untracked-files=all")
        if (status.code != 0) throw IOException("git status exited with code ${status.code}: ${status.firstLine()}")
        val uncommitted = status.stdout.lines().filter { it.isNotBlank() && !it.endsWith(RESULT_FILE) }
        return buildString {
            appendLine("Base: $base")
            appendLine("Commits since the base, newest first:")
            appendList(commits, MAX_COMMITS, "older commits left out", "No commits since the base.")
            appendLine("Files changed against the base:")
            val changed = diff.map { if (it.counted) "${it.path} (+${it.added} -${it.deleted})" else "${it.path} (not counted)" }
            appendList(changed, MAX_FILES, "${changed.size - MAX_FILES} more files left out", "No file changed against the base.")
            appendLine("Uncommitted changes, from git status:")
            appendList(uncommitted.map { it.trimEnd() }, MAX_FILES, "${uncommitted.size - MAX_FILES} more entries left out", "None.")
        }.trimEnd()
    }

    /** The file list of a workspace that is not a git work tree, sorted, links never followed. */
    private fun files(root: Path): String {
        val paths =
            Files.walk(root).use { stream ->
                stream
                    .asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { root.relativize(it).invariantSeparatorsPathString }
                    .filter { it != RESULT_FILE }
                    .take(WALK_MAX)
                    .toList()
                    .sorted()
            }
        return buildString {
            appendLine("This workspace is not a git work tree. Its files:")
            appendList(paths, MAX_FILES, "${paths.size - MAX_FILES} more files left out", "No files yet.")
        }.trimEnd()
    }

    private fun checks(
        recent: List<Pair<LedgerRow, List<RecordedEvent>>>,
        findings: String?,
    ): String {
        val found = recent.firstNotNullOfOrNull { (row, events) -> events.lastOrNull { it.kind == Verdict.name }?.let { row to events } }
        if (found == null && findings == null) throw IOException("no earlier attempt has a verdict")
        return buildString {
            if (found != null) {
                val (row, events) = found
                val verdict = events.last { it.kind == Verdict.name }.data
                val mergeCheck = if (verdict[Verdict.MERGE_CHECK] == "true") ", of the merge check" else ""
                val reason = verdict[Verdict.REASON]?.let { " ($it)" }.orEmpty()
                appendLine("Last verdict: ${verdict[Verdict.VERDICT]}, attempt ${row.number}$mergeCheck$reason")
                val latest =
                    events
                        .filter { it.kind == CheckFinished.name && it.data[CheckEvent.CANCELED] != "true" }
                        .associateBy { (if (it.data[CheckEvent.HOLDOUT] == "true") "h" else "v") + it.data[CheckEvent.CHECK] }
                        .values
                        .sortedWith(compareBy({ it.data[CheckEvent.HOLDOUT] == "true" }, { it.data[CheckEvent.CHECK]?.toIntOrNull() }))
                latest.forEach { appendLine(checkLine(it.data)) }
            }
            if (findings != null) {
                appendLine("Findings of the last refutation:")
                appendLine(cut(findings.trim(), MAX_FINDINGS_CHARS))
            }
        }.trimEnd()
    }

    private fun checkLine(data: Map<String, String>): String {
        // A hidden check never shows its command: what it tests stays hidden (m2-14).
        if (data[CheckEvent.HOLDOUT] == "true") {
            val result = if (data[CheckEvent.RESULT] == CheckEvent.COULD_NOT_RUN) "could not run" else data[CheckEvent.RESULT]
            return "- hidden check: $result"
        }
        val outcome =
            when {
                data[CheckEvent.TIMED_OUT] == "true" -> "timed out"
                data[CheckEvent.EXIT_CODE] == "0" -> "passed"
                data[CheckEvent.EXIT_CODE] != null -> "failed with exit code ${data[CheckEvent.EXIT_CODE]}"
                else -> "could not run: ${data[CheckEvent.REASON]}"
            }
        return "- check ${data[CheckEvent.CHECK]}, ${line(data[CheckEvent.COMMAND].orEmpty())}: $outcome"
    }

    private fun counters(
        rows: List<LedgerRow>,
        rotations: Int,
    ): String {
        val tokens = rows.mapNotNull { it.attempt.tokens?.total }
        val costs = rows.mapNotNull { it.attempt.costUsd }
        val spend =
            listOfNotNull(
                tokens.takeIf { it.isNotEmpty() }?.let { "${it.sum()} tokens" },
                costs.takeIf { it.isNotEmpty() }?.let { String.format(Locale.ROOT, "%.2f USD", it.sum()) },
            ).joinToString(", ").ifEmpty { "not reported" }
        return "- Attempts before this one: ${rows.size}\n- Rotations: $rotations\n- Spend so far: $spend"
    }

    /** `notes_for_next_attempt` of the last result an earlier attempt submitted, null when it wrote none. */
    private fun lastNotes(recent: List<Pair<LedgerRow, List<RecordedEvent>>>): String? {
        val reference =
            recent.firstNotNullOfOrNull { (_, events) ->
                events.lastOrNull { it.kind == ResultSubmitted.name && it.data[Results.RESULT] != null }?.data?.get(Results.RESULT)
            } ?: return null
        val root = Json.parseToJsonElement(savedResult(reference)) as? JsonObject ?: return null
        return (root[NOTES_FIELD] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun notes(
        root: Path,
        resultNotes: Result<String?>,
    ): String {
        val progress = progress(root)
        val notes = resultNotes.getOrNull()
        if (notes == null && progress == null) {
            val why = resultNotes.exceptionOrNull()?.let { line(it.message.orEmpty()) } ?: "no notes for the next attempt"
            throw IOException("$why, and no progress file $PROGRESS_FILE")
        }
        return buildString {
            appendLine(
                "An earlier session wrote these notes. Loop has not checked them: where they disagree with the facts above, trust the facts.",
            )
            appendLine("Notes for the next attempt, from the last submitted result:")
            appendLine(
                resultNotes.fold(
                    onSuccess = { it?.let { text -> cut(text, MAX_NOTES_CHARS) } ?: "None." },
                    onFailure = { "Not available, ${line(it.message.orEmpty()).trimEnd('.')}." },
                ),
            )
            appendLine("Progress file $PROGRESS_FILE, newest last:")
            appendLine(progress ?: "None.")
        }.trimEnd()
    }

    /**
     * The last [MAX_PROGRESS_CHARS] characters of [PROGRESS_FILE], null when there is none. Only a regular file inside
     * the workspace is read, never through a link, and only its last [PROGRESS_READ_MAX] bytes.
     */
    private fun progress(root: Path): String? {
        val file = root.resolve(PROGRESS_FILE)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (!file.toRealPath().startsWith(root.toRealPath())) return null
        val text =
            Files
                .newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                .use { channel ->
                    val start = (channel.size() - PROGRESS_READ_MAX).coerceAtLeast(0)
                    channel.position(start)
                    val buffer = ByteBuffer.allocate((channel.size() - start).toInt())
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) break
                    }
                    String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                }.trimEnd()
        if (text.isBlank()) return null
        return if (text.length <=
            MAX_PROGRESS_CHARS
        ) {
            text
        } else {
            "(older lines left out)\n" + text.takeLast(MAX_PROGRESS_CHARS).substringAfter('\n')
        }
    }

    private fun StringBuilder.appendList(
        items: List<String>,
        max: Int,
        more: String,
        none: String,
    ) {
        if (items.isEmpty()) appendLine(none)
        items.take(max).forEach { appendLine("- ${line(it)}") }
        if (items.size > max) appendLine("- ($more)")
    }

    private suspend fun <T> read(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
        fold(onSuccess = transform, onFailure = {
            Result.failure(it)
        })

    private fun <T> Result<T>.mapFailure(message: (String) -> String): Result<T> =
        recoverCatching { throw IOException(message(line(it.message ?: it::class.simpleName ?: "unknown error"))) }

    companion object {
        /** Where the agent keeps its progress (plan 9.1), relative to the workspace. */
        const val PROGRESS_FILE: String = ".loop/progress.md"

        /** At most so many characters for part 1, cut at the end. */
        const val MAX_GOAL_CHARS: Int = 4000

        /** At most so many characters for part 2, after its lists are cut. */
        const val MAX_WORKSPACE_CHARS: Int = 3000

        /** At most so many commits in part 2, newest first. */
        const val MAX_COMMITS: Int = 20

        /** At most so many changed files, uncommitted entries or files of a scratch workspace in part 2. */
        const val MAX_FILES: Int = 40

        /** At most so many characters for part 3. */
        const val MAX_CHECKS_CHARS: Int = 3000

        /** At most so many characters of findings in part 3. */
        const val MAX_FINDINGS_CHARS: Int = 2000

        /** At most so many characters for part 4. */
        const val MAX_COUNTERS_CHARS: Int = 500

        /** At most so many characters for part 5. */
        const val MAX_NOTES_PART_CHARS: Int = 3500

        /** At most so many characters of `notes_for_next_attempt` in part 5, cut at the end. */
        const val MAX_NOTES_CHARS: Int = 1000

        /** At most so many characters of the progress file in part 5, its newest, the last ones. */
        const val MAX_PROGRESS_CHARS: Int = 2000

        /** How many of the newest earlier attempts parts 3 and 5 read. */
        const val RECENT_ATTEMPTS: Int = 5

        private const val PROGRESS_READ_MAX = 64L * 1024
        private const val WALK_MAX = 10_000
        private const val LINE_MAX = 300
        private const val RESULT_FILE = ".loop/result.json"
        private const val NOTES_FIELD = "notes_for_next_attempt"
        private const val ACCEPTANCE_CRITERIA = "Acceptance criteria"

        private const val GOAL = "Goal and acceptance criteria"
        private const val WORKSPACE = "Workspace state"
        private const val CHECKS = "Checks"
        private const val COUNTERS = "Counters"
        private const val NOTES = "Agent notes, unverified"

        /**
         * The titles of the parts whose newest facts come last (m3-14): the checks, which end with the findings of the
         * last refutation, and the agent's notes, which end with the progress file, newest last. A part cut to fit the
         * prompt limit keeps its first line and its last lines; the other parts list their facts first to last or newest
         * first and keep their first lines.
         */
        val NEWEST_LAST_PARTS: Set<String> = setOf(CHECKS, NOTES)

        private val BOUNDS =
            mapOf(
                GOAL to MAX_GOAL_CHARS,
                WORKSPACE to MAX_WORKSPACE_CHARS,
                CHECKS to MAX_CHECKS_CHARS,
                COUNTERS to MAX_COUNTERS_CHARS,
                NOTES to MAX_NOTES_PART_CHARS,
            )

        /** [text] cut at its last whole line within [max] characters, with a line saying so. */
        private fun cut(
            text: String,
            max: Int,
        ): String {
            if (text.length <= max) return text
            val kept = text.take(max).substringBeforeLast('\n', text.take(max))
            return "$kept\n(cut at $max characters)"
        }

        /** One line of at most [LINE_MAX] characters. */
        private fun line(text: String): String =
            text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                .take(LINE_MAX)

        /**
         * Reads the saved result [reference] of [projectId] beside the evidence (m2-15), `<attempt_id>.result-<n>.json`:
         * only a regular file of the attempts folder named like one, never through a link, at most 1 MiB.
         */
        fun savedResults(
            loopHome: Path,
            projectId: ProjectId,
        ): (String) -> String =
            { reference ->
                require(SAVED_RESULT.matches(reference)) { "\"$reference\" is not a saved result name" }
                val file = attemptsFolder(loopHome, projectId).resolve(reference)
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(SAVED_RESULT_MAX) }.toString(Charsets.UTF_8)
            }

        private const val SAVED_RESULT_MAX = 1024 * 1024
        private val SAVED_RESULT = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*\.result-\d+\.json""")
    }
}
