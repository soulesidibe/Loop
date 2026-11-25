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

    /** A path longer than this many characters is cut in the listing, which the evidence of the verdict holds whole. */
    const val PATH_MAX: Int = 200

    /** A reason longer than this many characters is cut in the listing. */
    const val REASON_MAX: Int = 100

    const val LISTED_LEAD = "Listed, not inlined, the workspace is kept until the issue is delivered:"
    const val OVER_CAP = "over the $MAX_CHARS character cap of a comment"
    const val NO_ROOM = "no room left under the $MAX_CHARS character cap of a comment"

    /** The room kept for the last line of a listing cut at [MAX_LISTED]. */
    private const val MORE_ROOM = 100

    /**
     * The comment of the deliverable [files] of [identifier], verified in [attempt]: a first line, then each file
     * inlined in order while the rest still fits when listed, then the files listed, each with its reason.
     */
    fun build(
        identifier: String,
        attempt: String,
        files: List<AttachedFile>,
    ): String {
        val count = if (files.size == 1) "1 file" else "${files.size} files"
        val header = "Loop attached the deliverable of $identifier, verified in attempt $attempt: $count."
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
        if (more > 0) listed += "- and $more more files, listed in the deliverable evidence of the verdict"
        val listing = if (listed.isEmpty()) emptyList() else listOf((listOf(LISTED_LEAD) + listed).joinToString("\n"))
        return (listOf(header) + blocks + listing).joinToString("\n\n")
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
        return "${code(file.path)}, ${file.size} bytes:\n\n$fence\n$body$fence"
    }

    private fun listLine(
        file: DeliverableFile,
        reason: String,
    ): String {
        val path =
            file.path
                .lines()
                .joinToString(" ")
                .let { if (it.length > PATH_MAX) it.take(PATH_MAX) + "..." else it }
        val why = reason.lines().joinToString(" ").take(REASON_MAX)
        return "- ${code(path)}, ${file.size} bytes, sha256 ${file.sha256}, $why"
    }

    private const val SEPARATOR = 2
    private const val FENCE_MIN = 3
}

/**
 * The attach delivery of a kind with `delivery.attach: true` (m4-22, story 120): the deliverable files listed at
 * verification are read again from the workspace, each checked against its listed digest, and posted on the issue as
 * one comment through the tracker's `addComment`, redacted like every Loop comment, written by `loop`. Recorded as
 * `issue_commented` with the reason `deliverable`. Only the comment: the wait and the move stay the orchestrator's.
 */
class AttachDelivery(
    private val tracker: TrackerAdapter,
    private val redactor: Redactor,
    private val clock: Clock,
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val record: suspend (definition: EventDefinition, data: Map<String, String>) -> Unit,
) {
    /**
     * Posts the deliverable [files] of [identifier], verified in [attempt] and read from [workspace], on [issue]. The
     * failure, its reason redacted, null when it was posted.
     */
    suspend fun post(
        issue: Issue,
        identifier: String,
        attempt: String,
        workspace: Path,
        files: List<DeliverableFile>,
    ): String? {
        val text =
            try {
                withContext(io) {
                    val attached = files.map { read(workspace, it) }.map { it.copy(text = it.text?.let(redactor::redact)) }
                    redactor.redact(AttachComment.build(identifier, attempt, attached)).trim()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return redactor.redact("cannot build the deliverable comment: ${e.describe()}").take(REASON_MAX)
            }
        val failure =
            try {
                withContext(io) { tracker.addComment(issue, IssueComment(LOOP_AUTHOR, clock.now(), text)) }.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure != null) return redactor.redact("cannot post the deliverable: ${failure.describe()}").take(REASON_MAX)
        record(
            IssueCommented,
            mapOf(Tracker.CHARS to text.length.toString(), Tracker.BY to Tracker.BY_HOST, Tracker.REASON to REASON),
        )
        return null
    }

    companion object {
        /** The `reason` of the `issue_commented` event of the deliverable comment. */
        const val REASON = IssueCommented.DELIVERABLE

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
         * on a kind with a deliverable needs `attach` and its own opt in, as `auto_merge.enabled` is for code.
         */
        fun refusal(
            kindKey: String,
            files: List<String>,
            attach: Boolean,
            attachAuto: Boolean,
            pullRequest: Boolean,
            auto: Boolean,
        ): String? {
            val delivery = "$kindKey.delivery"
            return when {
                attach && files.isEmpty() -> "$delivery.attach needs $kindKey.deliverable.files, the files it posts"
                attachAuto && !attach -> "$delivery.attach_auto.enabled needs $delivery.attach: true"
                attach && pullRequest -> "$delivery.attach and $delivery.pull_request cannot both deliver a kind, use one of them"
                files.isEmpty() || !auto -> null
                !attach ->
                    "$delivery.policy is auto, which a kind with deliverable.files supports only with $delivery.attach: true " +
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
