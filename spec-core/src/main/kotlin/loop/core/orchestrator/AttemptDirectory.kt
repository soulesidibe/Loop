package loop.core.orchestrator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import loop.api.AttemptId
import loop.api.ProjectId
import loop.core.trackers.TrackerEntry
import loop.core.workflow.WorkflowFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * The folder of one Attempt, outside its workspace (m1-23): `<LOOP_HOME>/attempts/<project_id>/<attempt_id>/`.
 * It holds the tool config snapshot ([TOOL_CONFIG_FILE]) and the host side request queue ([REQUESTS_DIR]).
 */
fun attemptDirectory(
    loopHome: Path,
    projectId: ProjectId,
    attemptId: AttemptId,
): Path = attemptsFolder(loopHome, projectId).resolve(attemptId.value)

/** The parent of every attempt directory of [projectId], shared with m1-22's attempt files. */
fun attemptsFolder(
    loopHome: Path,
    projectId: ProjectId,
): Path = loopHome.resolve(ATTEMPTS_DIR).resolve(projectId.value)

/**
 * The Attempt file (m1-13): `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.jsonl.gz`, the rendered
 * prompt and later the full runner stream (m1-22). It sits beside the attempt directory, never inside
 * it, because that directory is deleted when the Attempt ends and the file is kept.
 */
fun attemptFile(
    loopHome: Path,
    projectId: ProjectId,
    attemptId: AttemptId,
): Path = attemptsFolder(loopHome, projectId).resolve("${attemptId.value}$ATTEMPT_FILE_SUFFIX")

/** The tool config snapshot `loop tool` reads instead of `WORKFLOW.md`. */
const val TOOL_CONFIG_FILE = "tool-config.json"

/** The folder of queued tool requests inside an attempt directory. */
const val REQUESTS_DIR = "requests"

/** The folder of the full texts of trimmed tool replies inside an attempt directory (m3-14). */
const val TOOL_OUTPUTS_DIR = "tool-outputs"

private const val ATTEMPTS_DIR = "attempts"

private const val ATTEMPT_FILE_SUFFIX = ".jsonl.gz"

/**
 * Writes the tool config snapshot at [target] for an Attempt of [projectId] run with [workflow]. [tracker] is the
 * tracker of the Attempt's issue and [definedStates] its adapter's states (m4-14), so the agent's `move` is checked
 * against the issue's own tracker. The cli implements it, so spec-core holds no tracker specific knowledge. A failure
 * throws; the orchestrator fails the Attempt.
 */
fun interface ToolConfigWriter {
    fun write(
        target: Path,
        projectId: ProjectId,
        workflow: WorkflowFile,
        tracker: TrackerEntry,
        definedStates: List<String>,
    )

    /** The snapshot of an Attempt on an issue of the primary tracker. */
    fun write(
        target: Path,
        projectId: ProjectId,
        workflow: WorkflowFile,
        definedStates: List<String>,
    ) = write(target, projectId, workflow, workflow.trackers.primary, definedStates)

    companion object {
        /** Writes nothing, for orchestrator tests that do not look at the snapshot. */
        val NONE: ToolConfigWriter = ToolConfigWriter { _, _, _, _, _ -> }
    }
}

/** A tracker write `loop tool` asks Loop to apply host side when the turn ends. */
@Serializable
sealed interface ToolRequest {
    /** The `request` value of the tool request events. */
    val kind: String

    @Serializable
    @SerialName("move")
    data class Move(
        val to: String,
    ) : ToolRequest {
        override val kind: String get() = "move"
    }

    @Serializable
    @SerialName("comment")
    data class Comment(
        val text: String,
    ) : ToolRequest {
        override val kind: String get() = "comment"
    }

    /**
     * A result the agent submitted with the `submit_result` MCP tool (m2-16): the PLAN section 7 JSON [text] as the
     * tool validated it. The drain treats it like the result file `.loop/result.json`, with `source` = `mcp`.
     */
    @Serializable
    @SerialName("result")
    data class Result(
        val text: String,
    ) : ToolRequest {
        override val kind: String get() = "result"
    }

    /**
     * A question for a human the agent asked with the `ask` MCP tool (m4-31): its [questionKind], the [question],
     * redacted and trimmed, the [options] it sees and the one it recommends. The drain posts it on the issue and the
     * issue waits as `Waiting(ask)`.
     */
    @Serializable
    @SerialName("ask")
    data class Ask(
        @SerialName("question_kind") val questionKind: String,
        val question: String,
        val options: List<String> = emptyList(),
        val recommendation: String? = null,
    ) : ToolRequest {
        override val kind: String get() = "ask"
    }

    /**
     * A follow up the agent filed with the `file_followup` MCP tool (m4-33): its [title] and [body], redacted and
     * trimmed. The drain creates it labelled `needs-triage`, see [loop.core.trackers.Followups].
     */
    @Serializable
    @SerialName("followup")
    data class Followup(
        val title: String,
        val body: String,
    ) : ToolRequest {
        override val kind: String get() = "followup"
    }
}

/** One file of the queue: decoded, or unreadable with the reason. */
sealed interface QueuedRequest {
    val file: Path

    data class Valid(
        override val file: Path,
        val request: ToolRequest,
    ) : QueuedRequest

    data class Unreadable(
        override val file: Path,
        val reason: String,
    ) : QueuedRequest
}

/**
 * The request queue of one attempt directory, one JSON file per request in [REQUESTS_DIR], no lock.
 * A writer puts a temp file in the same folder, then moves it atomically to
 * `<epoch ms, 19 digits>-<pid>-<random>.json`, so a reader never sees half a request. The reader
 * takes `*.json` only, in name order; the caller deletes each file right after applying it.
 */
class ToolRequestQueue(
    attemptDir: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pid: Long = ProcessHandle.current().pid(),
) {
    private val folder: Path = attemptDir.resolve(REQUESTS_DIR)

    /**
     * Queues [request] and returns its file. Creates no folder: when the attempt directory is gone
     * (the Attempt ended) it throws [java.nio.file.NoSuchFileException].
     */
    fun enqueue(request: ToolRequest): Path {
        val stem = "%019d-%d-%s".format(nowMillis(), pid, UUID.randomUUID().toString().replace("-", ""))
        val temp = folder.resolve("$stem$TEMP_SUFFIX")
        val target = folder.resolve("$stem$REQUEST_SUFFIX")
        try {
            temp.writeText(json.encodeToString(ToolRequest.serializer(), request))
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // The original failure is what the caller reports; a failed cleanup must not replace it.
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
        return target
    }

    /**
     * The queued requests in name order; empty when there is no [REQUESTS_DIR] or it is not a real
     * directory (a symlink is never followed). Only a regular file, not a symlink, is opened, without
     * following links; any other `*.json` entry (a FIFO, a folder, a link) is [QueuedRequest.Unreadable],
     * so the caller deletes just that entry. Throws when the folder itself cannot be listed.
     */
    fun pending(): List<QueuedRequest> {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return folder
            .listDirectoryEntries("*$REQUEST_SUFFIX")
            .sortedBy { it.name }
            .map { file ->
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    QueuedRequest.Unreadable(file, NOT_A_FILE)
                } else {
                    read(file)
                }
            }
    }

    private fun read(file: Path): QueuedRequest =
        try {
            val text = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readBytes().toString(Charsets.UTF_8) }
            QueuedRequest.Valid(file, json.decodeFromString(ToolRequest.serializer(), text))
        } catch (e: SerializationException) {
            // Not the decoder's message: it quotes the file, which may hold comment text.
            QueuedRequest.Unreadable(file, UNREADABLE)
        } catch (e: IllegalArgumentException) {
            QueuedRequest.Unreadable(file, UNREADABLE)
        } catch (e: IOException) {
            QueuedRequest.Unreadable(file, "cannot read the request file: ${(e.message ?: "unknown error").trimEnd('.')}")
        }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
        const val REQUEST_SUFFIX = ".json"
        const val UNREADABLE = "not a valid tool request"
        const val NOT_A_FILE = "not a regular file"
        val json = Json { classDiscriminator = "type" }
    }
}

/** The full [text] of a reply of the MCP [tool] that reached the agent trimmed (m3-14). */
@Serializable
data class SavedToolOutput(
    val tool: String,
    val text: String,
)

/**
 * The full texts of the tool replies `loop tool mcp` trimmed to `context.tool_output_tokens` (m3-14), one JSON file per
 * reply in [TOOL_OUTPUTS_DIR] of the attempt directory, written like a request ([ToolRequestQueue]): a temp file moved
 * atomically to `<epoch ms, 19 digits>-<pid>-<random>.json`. The orchestrator, the one writer of the Attempt file, moves
 * each into it as a `tool_output` record at the drain and when the attempt ends, then deletes the file.
 */
class ToolOutputQueue(
    private val attemptDir: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pid: Long = ProcessHandle.current().pid(),
) {
    private val folder: Path = attemptDir.resolve(TOOL_OUTPUTS_DIR)

    /**
     * Saves [output] and returns its file. Creates [TOOL_OUTPUTS_DIR] in the attempt directory, never the directory
     * itself: when the attempt ended and its directory is gone it throws [java.nio.file.NoSuchFileException].
     */
    fun save(output: SavedToolOutput): Path {
        if (!Files.isDirectory(attemptDir, LinkOption.NOFOLLOW_LINKS)) throw NoSuchFileException(attemptDir.toString())
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(folder)
            } catch (e: FileAlreadyExistsException) {
                // Another call of the same attempt made it first.
            }
        }
        val stem = "%019d-%d-%s".format(nowMillis(), pid, UUID.randomUUID().toString().replace("-", ""))
        val temp = folder.resolve("$stem.tmp")
        val target = folder.resolve("$stem.json")
        try {
            temp.writeText(Json.encodeToString(SavedToolOutput.serializer(), output))
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
        return target
    }

    /**
     * The saved replies in name order, each with its file; the reply is null when the file is not a regular file or
     * cannot be decoded, so the caller deletes just that entry. Empty when [TOOL_OUTPUTS_DIR] is missing or is not a
     * real directory (a symlink is never followed). Throws when the folder itself cannot be listed.
     */
    fun pending(): List<Pair<Path, SavedToolOutput?>> {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return folder
            .listDirectoryEntries("*.json")
            .sortedBy { it.name }
            .map { file -> file to read(file) }
    }

    private fun read(file: Path): SavedToolOutput? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            val text = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readBytes().toString(Charsets.UTF_8) }
            Json.decodeFromString(SavedToolOutput.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
