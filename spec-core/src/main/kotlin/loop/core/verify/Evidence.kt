package loop.core.verify

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.core.kinds.DeliverableFile
import loop.core.orchestrator.attemptsFolder
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The evidence of one check run (m2-05): what ran, how it ended, the [fingerprint] of the workspace it ran
 * on and its whole [output], stdout and stderr merged as written, not yet redacted. A [holdout] check's
 * evidence keeps no output, exit code or duration, only its [result] (m2-14): the file is readable by the agent,
 * what the hidden check found must stay hidden.
 */
data class Evidence(
    val check: Int,
    val command: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val fingerprint: String,
    val output: String,
    val outputTruncated: Boolean,
    /** Why the check could not start, null when it started. */
    val reason: String? = null,
    val holdout: Boolean = false,
    /** A [holdout]'s outcome, `passed`, `failed` or `could_not_run`, in place of its exit code and duration (m2-14). */
    val result: String? = null,
)

/**
 * The judgment calls of one verification (m2-09): the [criteria] of the issue that hold no command, in order,
 * not yet redacted, and the [fingerprint] of the workspace they were listed on. No check passes them.
 */
data class JudgmentCalls(
    val fingerprint: String,
    val criteria: List<String>,
)

/**
 * The deliverable of one verification (m4-21): the [files] matching the kind's `deliverable.files`, in path order, and the
 * [fingerprint] of the workspace they were listed on.
 */
data class DeliverableEvidence(
    val fingerprint: String,
    val files: List<DeliverableFile>,
)

/** Saves the evidence of a check run of an attempt; the seam between the orchestrator and the files. */
fun interface EvidenceWriter {
    /** Writes [evidence] and returns its reference, the file name; null when nothing is kept. Throws on a failed write. */
    fun write(
        projectId: ProjectId,
        attemptId: AttemptId,
        evidence: Evidence,
    ): String?

    /** Writes [calls] like [write] writes a check run (m2-09); keeps nothing unless the writer keeps files. */
    fun writeJudgmentCalls(
        projectId: ProjectId,
        attemptId: AttemptId,
        calls: JudgmentCalls,
    ): String? = null

    /** Writes [deliverable] like [write] writes a check run (m4-21); keeps nothing unless the writer keeps files. */
    fun writeDeliverable(
        projectId: ProjectId,
        attemptId: AttemptId,
        deliverable: DeliverableEvidence,
    ): String? = null

    /**
     * Saves the [text] of a result file the agent submitted (m2-15), valid or not, and returns its reference;
     * null when nothing is kept, the default. Throws on a failed write.
     */
    fun writeResult(
        projectId: ProjectId,
        attemptId: AttemptId,
        text: String,
    ): String? = null

    companion object {
        /** Keeps nothing, for orchestrator tests that do not look at evidence. */
        val NONE: EvidenceWriter = EvidenceWriter { _, _, _ -> null }
    }
}

/**
 * The evidence files (m2-05), beside the Attempt file: `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.evidence-<n>.log`,
 * `n` from 1 per attempt, the next after the files already there, so a check run again after a restart never
 * replaces one. Plain files, not a folder: the start of run sweep deletes the folders of the attempts folder.
 *
 * A file is a header of `key: value` lines, a blank line, then the output, none for a holdout check. A submitted
 * result (m2-15) is saved beside them by [writeResult]. The whole text is redacted with
 * [redactor] before it is written, never on read. A file is created owner only, never over an existing entry
 * and never through a symlink. Writes are serialized on this instance.
 */
class EvidenceFiles(
    private val loopHome: Path,
    private val redactor: Redactor,
) : EvidenceWriter {
    override fun write(
        projectId: ProjectId,
        attemptId: AttemptId,
        evidence: Evidence,
    ): String = save(projectId, attemptId, text(attemptId, evidence), INFIX, SUFFIX)

    /**
     * m2-09: the judgment calls of a verification, numbered with the check runs of the attempt: a header of
     * `attempt`, `judgment_calls` (the count) and `fingerprint`, a blank line, then `- <criterion>` a line.
     */
    override fun writeJudgmentCalls(
        projectId: ProjectId,
        attemptId: AttemptId,
        calls: JudgmentCalls,
    ): String =
        save(
            projectId,
            attemptId,
            buildString {
                appendLine("attempt: ${attemptId.value}")
                appendLine("judgment_calls: ${calls.criteria.size}")
                appendLine("fingerprint: ${calls.fingerprint}")
                appendLine()
                calls.criteria.forEach { appendLine("- ${it.lines().joinToString(" ")}") }
            },
            INFIX,
            SUFFIX,
        )

    /**
     * m4-21: the deliverable files of a verification, numbered with the check runs of the attempt: a header of `attempt`,
     * `deliverable` (the count) and `fingerprint`, a blank line, then `- <path>, <size> bytes, sha256 <digest>` a line.
     */
    override fun writeDeliverable(
        projectId: ProjectId,
        attemptId: AttemptId,
        deliverable: DeliverableEvidence,
    ): String =
        save(
            projectId,
            attemptId,
            buildString {
                appendLine("attempt: ${attemptId.value}")
                appendLine("deliverable: ${deliverable.files.size}")
                appendLine("fingerprint: ${deliverable.fingerprint}")
                appendLine()
                deliverable.files.forEach { appendLine("- ${it.path.lines().joinToString(" ")}, ${it.size} bytes, sha256 ${it.sha256}") }
            },
            INFIX,
            SUFFIX,
        )

    /**
     * The submitted result (m2-15) beside the evidence, `<attempt_id>.result-<n>.json`, numbered on its own
     * like the evidence, redacted and created the same way. The text is kept as the agent wrote it.
     */
    override fun writeResult(
        projectId: ProjectId,
        attemptId: AttemptId,
        text: String,
    ): String = save(projectId, attemptId, text, RESULT_INFIX, RESULT_SUFFIX)

    private fun save(
        projectId: ProjectId,
        attemptId: AttemptId,
        text: String,
        infix: String,
        suffix: String,
    ): String =
        synchronized(this) {
            val folder = attemptsFolder(loopHome, projectId)
            Files.createDirectories(folder)
            val bytes = redactor.redact(text).toByteArray(Charsets.UTF_8)
            // An entry taken since the listing (another process) moves on to the next number.
            generateSequence(nextNumber(folder, attemptId, infix, suffix)) { it + 1 }
                .map { number -> "${attemptId.value}$infix$number$suffix" }
                .first { name -> create(folder.resolve(name), bytes) }
        }

    /** Writes [bytes] to a new [file]; false when an entry, a symlink included, is already there. */
    private fun create(
        file: Path,
        bytes: ByteArray,
    ): Boolean =
        try {
            Files.newByteChannel(file, CREATE_OPTIONS, OWNER_ONLY).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            true
        } catch (e: FileAlreadyExistsException) {
            false
        }

    /**
     * The text of the evidence file [name] of [projectId], as written. Only a regular file of the attempts
     * folder named like an evidence file is read, never through a symlink.
     */
    fun read(
        projectId: ProjectId,
        name: String,
    ): Result<String> =
        runCatching {
            require(NAME.matches(name)) { "\"$name\" is not an evidence file name" }
            val file = attemptsFolder(loopHome, projectId).resolve(name)
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun nextNumber(
        folder: Path,
        attemptId: AttemptId,
        infix: String,
        suffix: String,
    ): Int {
        val prefix = "${attemptId.value}$infix"
        val taken =
            folder
                .listDirectoryEntries("$prefix*$suffix")
                .mapNotNull {
                    it.name
                        .removePrefix(prefix)
                        .removeSuffix(suffix)
                        .toIntOrNull()
                }
        return (taken.maxOrNull() ?: 0) + 1
    }

    private fun text(
        attemptId: AttemptId,
        evidence: Evidence,
    ): String =
        buildString {
            appendLine("attempt: ${attemptId.value}")
            appendLine("check: ${evidence.check}")
            // One line: a command written over several YAML lines would end the header early.
            appendLine("command: ${evidence.command.lines().joinToString(" ")}")
            if (evidence.holdout) {
                appendLine("fingerprint: ${evidence.fingerprint}")
                appendLine("holdout: true")
                evidence.result?.let { appendLine("result: $it") }
                appendLine("output: hidden, a holdout check's output is never saved")
            } else {
                evidence.exitCode?.let { appendLine("exit_code: $it") }
                appendLine("timed_out: ${evidence.timedOut}")
                appendLine("duration_ms: ${evidence.durationMs}")
                appendLine("fingerprint: ${evidence.fingerprint}")
                appendLine("output_truncated: ${evidence.outputTruncated}")
                evidence.reason?.let { appendLine("reason: ${it.lines().joinToString(" ")}") }
            }
            appendLine()
            append(evidence.output)
        }

    private companion object {
        const val INFIX = ".evidence-"
        const val SUFFIX = ".log"
        const val RESULT_INFIX = ".result-"
        const val RESULT_SUFFIX = ".json"
        val NAME = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*\.evidence-\d+\.log""")
        val CREATE_OPTIONS = setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
