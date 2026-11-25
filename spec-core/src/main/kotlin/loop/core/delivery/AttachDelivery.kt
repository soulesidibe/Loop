package loop.core.delivery

import kotlinx.coroutines.withContext
import loop.api.Issue
import loop.api.IssueComment
import loop.api.Redactor
import loop.api.TrackerAdapter
import loop.core.events.EventDefinition
import loop.core.events.IssueCommented
import loop.core.events.Tracker
import loop.core.kinds.DeliverableFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * One deliverable file as the attach delivery posts it (m4-22): the [file] listed at verification and its [text], null
 * when it cannot be inlined, for the [reason] given.
 */
data class AttachedFile(
    val file: DeliverableFile,
    val text: String?,
    val reason: String? = null,
)

/**
 * The comment of the attach delivery (m4-22), a pure function: each deliverable file under its path, in a fenced block
 * longer than any backtick fence the file holds, in the order given, while the comment stays under [MAX_CHARS]. A file
 * that does not fit, or cannot be inlined, is listed by path, size and sha256 digest instead, so nothing is cut in
 * silence.
 */
object AttachComment {
    /**
     * The cap of the comment, in UTF-16 characters, below GitHub's limit of 65,536 characters per comment. A UTF-16
     * count is never below GitHub's, which counts an emoji once.
     */
    const val MAX_CHARS: Int = 60_000

    /** At most this many files are listed one a line; the others are counted on a last line. */
    const val MAX_LISTED: Int = 100

    /** A path longer than this many characters is cut in the listing; the workspace holds the file whole. */
    const val PATH_MAX: Int = 200

    /** A reason longer than this many characters is cut in the listing. */
    const val REASON_MAX: Int = 100

    const val LISTED_LEAD = "Listed, not inlined, the workspace is kept until the issue is delivered:"
    const val OVER_CAP = "over the $MAX_CHARS character cap of a comment"
    const val NO_ROOM = "no room left under the $MAX_CHARS character cap of a comment"

    private const val MORE_SAVED = "listed in the deliverable evidence of the verdict"
    private const val MORE_UNSAVED = "not listed here, the workspace holds them"

    /** The room kept for the last line of a listing cut at [MAX_LISTED]. */
    private const val MORE_ROOM = 100

    /** The [text] of a comment and how many of its files are [listed] only, not inlined. */
    data class Composed(
        val text: String,
        val listed: Int,
    )

    /** The text of [compose], for a deliverable whose evidence was saved. */
    fun build(
        identifier: String,
        attempt: String?,
        files: List<AttachedFile>,
    ): String = compose(identifier, attempt, files, evidence = true).text

    /**
     * The comment of the deliverable [files] of [identifier], verified in [attempt] (null when the attempt is not known):
     * a first line, then each file inlined in order while the rest still fits when listed, then the files listed, each
     * with its reason. [evidence] is false when the deliverable evidence of the verdict was not saved, so the count of
     * files past [MAX_LISTED] does not point at it.
     */
    fun compose(
        identifier: String,
        attempt: String?,
        files: List<AttachedFile>,
        evidence: Boolean,
    ): Composed {
        val count = if (files.size == 1) "1 file" else "${files.size} files"
        val verified = attempt?.let { "verified in attempt $it" } ?: "verified"
        val header = "Loop attached the deliverable of $identifier, $verified: $count."
        // The room each file takes when listed, with the longest reason it can get, so a later file always fits.
        val reserved = files.map { listLine(it.file, it.reason ?: NO_ROOM).length + 1 }
        var used = header.length + SEPARATOR + LISTED_LEAD.length + MORE_ROOM
        val blocks = mutableListOf<String>()
        val listed = mutableListOf<String>()
        var more = 0
        for ((index, attached) in files.withIndex()) {
            val slots = MAX_LISTED - listed.size
            val rest = reserved.subList(index + 1, files.size).take(slots).sum()
            val block = attached.text?.let { block(attached.file, it) }
            if (block != null && used + SEPARATOR + block.length + rest <= MAX_CHARS) {
                blocks += block
                used += SEPARATOR + block.length
                continue
            }
            if (slots <= 0) {
                more++
                continue
            }
            val reason =
                attached.reason
                    ?: if (header.length + SEPARATOR + checkNotNull(block).length > MAX_CHARS) OVER_CAP else NO_ROOM
            val line = listLine(attached.file, reason)
            listed += line
            used += line.length + 1
        }
        val notInlined = listed.size + more
        if (more > 0) listed += "- and $more more files, ${if (evidence) MORE_SAVED else MORE_UNSAVED}"
        val listing = if (listed.isEmpty()) emptyList() else listOf((listOf(LISTED_LEAD) + listed).joinToString("\n"))
        return Composed((listOf(header) + blocks + listing).joinToString("\n\n"), notInlined)
    }

    /** The fence of [text]: three backticks, or one more than its longest run of backticks. */
    fun fence(text: String): String {
        val longest =
            Regex("`+")
                .findAll(text)
                .maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(FENCE_MIN, longest + 1))
    }

    /** [text] as a code span, its delimiter longer than any backtick run it holds, padded when it starts or ends with one. */
    fun code(text: String): String {
        val longest =
            Regex("`+")
                .findAll(text)
                .maxOfOrNull { it.value.length } ?: 0
        val delimiter = "`".repeat(longest + 1)
        val pad = if (longest > 0) " " else ""
        return "$delimiter$pad$text$pad$delimiter"
    }

    private fun block(
        file: DeliverableFile,
        text: String,
    ): String {
        val fence = fence(text)
        val body = if (text.endsWith("\n")) text else text + "\n"
        return "${code(oneLine(file.path))}, ${file.size} bytes:\n\n$fence\n$body$fence"
    }

    /** [text] on one line, so a path holding a newline adds no markdown to the comment. */
    private fun oneLine(text: String): String = text.lines().joinToString(" ")

    private fun listLine(
        file: DeliverableFile,
        reason: String,
    ): String {
        val path = oneLine(file.path).let { if (it.length > PATH_MAX) it.take(PATH_MAX) + "..." else it }
        val why = oneLine(reason).take(REASON_MAX)
        return "- ${code(path)}, ${file.size} bytes, sha256 ${file.sha256}, $why"
    }

    private const val SEPARATOR = 2
    private const val FENCE_MIN = 3
}

/**
 * The attach delivery of a kind with `delivery.attach: true` (m4-22, story 120): the deliverable files listed at
 * verification are read again from the workspace, each checked against its listed digest ([prepare]), and posted on
 * the issue as one comment through the tracker's `addComment` ([post]), redacted like every Loop comment, written by
 * `loop`. Recorded as `issue_commented` with the reason `deliverable`. Only the comment: the wait and the move stay
 * the orchestrator's, which moves on its own only when every file was inlined.
 */
class AttachDelivery(
    private val tracker: TrackerAdapter,
    private val redactor: Redactor,
    private val clock: Clock,
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val record: suspend (definition: EventDefinition, data: Map<String, String>) -> Unit,
) {
    /** The deliverable comment of [prepare], or why it could not be built. */
    sealed interface Prepared {
        /** The redacted [text], under the cap, and how many files it only [listed], zero when every file is inlined. */
        data class Comment(
            val text: String,
            val listed: Int,
        ) : Prepared

        /** Why the comment could not be built, redacted. */
        data class Failed(
            val reason: String,
        ) : Prepared
    }

    /**
     * The comment of the deliverable [files] of [identifier], verified in [attempt] and read from [workspace], with
     * [evidence] true when the deliverable evidence of the verdict was saved. The texts and the paths are redacted
     * before the cap is applied, so a redaction marker longer than its secret still fits; a comment that redaction of
     * the whole still puts over the cap lists every file instead.
     */
    suspend fun prepare(
        identifier: String,
        attempt: String?,
        workspace: Path,
        files: List<DeliverableFile>,
        evidence: Boolean,
    ): Prepared =
        try {
            withContext(io) {
                val attached =
                    files.map { read(workspace, it) }.map {
                        it.copy(file = it.file.copy(path = redactor.redact(it.file.path)), text = it.text?.let(redactor::redact))
                    }
                val composed = AttachComment.compose(identifier, attempt, attached, evidence)
                val text = redactor.redact(composed.text).trim()
                if (text.length <= AttachComment.MAX_CHARS) {
                    Prepared.Comment(text, composed.listed)
                } else {
                    val listing = attached.map { it.copy(text = null, reason = it.reason ?: AttachComment.OVER_CAP) }
                    val listed = AttachComment.compose(identifier, attempt, listing, evidence)
                    Prepared.Comment(redactor.redact(listed.text).trim(), listed.listed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Prepared.Failed(redactor.redact("cannot build the deliverable comment: ${e.describe()}").take(REASON_MAX))
        }

    /** Posts the prepared [comment] on [issue]. The failure, its reason redacted, null when it was posted. */
    suspend fun post(
        issue: Issue,
        comment: Prepared.Comment,
    ): String? {
        val failure =
            try {
                withContext(io) { tracker.addComment(issue, IssueComment(LOOP_AUTHOR, clock.now(), comment.text)) }.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure != null) return redactor.redact("cannot post the deliverable: ${failure.describe()}").take(REASON_MAX)
        record(
            IssueCommented,
            mapOf(Tracker.CHARS to comment.text.length.toString(), Tracker.BY to Tracker.BY_HOST, Tracker.REASON to REASON),
        )
        return null
    }

    companion object {
        /** The `reason` of the `issue_commented` event of the deliverable comment. */
        const val REASON = IssueCommented.DELIVERABLE

        /** The line of the evidence summary when an auto delivery waits because [listed] files are not inlined. */
        fun fallbackNote(listed: Int): String {
            val files = if (listed == 1) "1 deliverable file is" else "$listed deliverable files are"
            return "$files only listed, not inlined, so Loop did not move it on its own."
        }

        /** The line of the evidence summary when the deliverable comment could not be posted, for the [reason] given. */
        fun failureNote(reason: String): String = "The deliverable could not be posted: $reason."

        const val CHANGED = "changed since its verification"
        const val NOT_TEXT = "not UTF-8 text"

        /**
         * [file] of [workspace] as the comment shows it: its text when it is still the file verification listed, same
         * size and digest, and valid UTF-8; otherwise no text and the reason. A file whose size alone puts it over the
         * cap is not read. Never follows a symlink.
         */
        fun read(
            workspace: Path,
            file: DeliverableFile,
        ): AttachedFile {
            // A UTF-8 character takes at most 4 bytes, so a larger file holds more characters than the cap.
            if (file.size > AttachComment.MAX_CHARS.toLong() * UTF8_MAX_BYTES) return AttachedFile(file, null, AttachComment.OVER_CAP)
            val bytes =
                try {
                    Files
                        .newInputStream(
                            workspace.resolve(file.path),
                            LinkOption.NOFOLLOW_LINKS,
                        ).use { it.readNBytes(file.size.toInt() + 1) }
                } catch (e: IOException) {
                    return AttachedFile(file, null, "cannot be read: ${e.describe()}")
                }
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            if (bytes.size.toLong() != file.size || digest != file.sha256) return AttachedFile(file, null, CHANGED)
            val text =
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (e: CharacterCodingException) {
                    return AttachedFile(file, null, NOT_TEXT)
                }
            return AttachedFile(file, text)
        }

        /**
         * Why the delivery of the kind at [kindKey] cannot run, as a sentence for preflight, or null when it can, from
         * the kind's deliverable [files], [attach], [attachAuto] (`delivery.attach_auto.enabled`), [pullRequest] and
         * [auto] (`policy: auto`). `attach` needs a deliverable and is not combined with a pull request; `policy: auto`
         * on a kind with a deliverable needs `attach` and its own opt in, as `auto_merge.enabled` is for code. [report]
         * is true when [files] holds the path of `deliverable.report` (m4-23), which the messages then name.
         */
        fun refusal(
            kindKey: String,
            files: List<String>,
            attach: Boolean,
            attachAuto: Boolean,
            pullRequest: Boolean,
            auto: Boolean,
            report: Boolean = false,
        ): String? {
            val delivery = "$kindKey.delivery"
            val deliverable = if (report) "deliverable.report" else "deliverable.files"
            return when {
                attach && files.isEmpty() -> "$delivery.attach needs $kindKey.deliverable.files, the files it posts"
                attachAuto && !attach -> "$delivery.attach_auto.enabled needs $delivery.attach: true"
                attach && pullRequest -> "$delivery.attach and $delivery.pull_request cannot both deliver a kind, use one of them"
                files.isEmpty() || !auto -> null
                !attach ->
                    "$delivery.policy is auto, which a kind with $deliverable supports only with $delivery.attach: true " +
                        "and the opt in $delivery.attach_auto.enabled: true, or use policy: human"
                !attachAuto -> "$delivery.policy is auto, which needs the opt in $delivery.attach_auto.enabled: true"
                else -> null
            }
        }

        private const val LOOP_AUTHOR = "loop"
        private const val REASON_MAX = 200
        private const val UTF8_MAX_BYTES = 4

        private fun Throwable.describe(): String =
            (message ?: this::class.simpleName ?: "unknown error").lineSequence().first().trimEnd('.')
    }
}
