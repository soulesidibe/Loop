package loop.cli

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.core.events.AttemptStarted
import loop.core.events.ToolRequestDropped
import loop.core.events.ToolRequestEvent
import loop.core.events.Tracker
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.core.orchestrator.attemptsFolder
import loop.store.SqliteEventStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Clock

/**
 * Sweeps the attempt directories a previous run of [projectId] left behind (m1-23), called once the
 * run lock is held, so no live attempt of this Project owns one. Directories only: m1-22's attempt
 * files share the folder. Each queued request is dropped unapplied with `tool_request_dropped` on its
 * Issue, because its attempt is dead and its turn never ended normally, then the directory is deleted
 * without following links. Never throws: a failure is logged and the run goes on.
 */
@OptIn(ExperimentalPathApi::class)
internal suspend fun sweepAttemptDirectories(
    loopHome: Path,
    projectId: ProjectId,
    runId: RunId,
    store: SqliteEventStore,
    redactor: Redactor,
    log: KeyValueLog,
    clock: Clock,
) {
    val context = LogContext(runId = runId, projectId = projectId)
    val folder = attemptsFolder(loopHome, projectId)
    val leftovers =
        try {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return
            folder.listDirectoryEntries().filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.sortedBy { it.name }
        } catch (e: Exception) {
            log.log(LogLevel.WARN, ATTEMPT_DIRECTORY, LogOutcome.FAILED, context, reason = e.sentence())
            return
        }
    for (dir in leftovers) {
        val attemptId = AttemptId(dir.name)
        try {
            val attemptContext = context.copy(attemptId = attemptId)
            val pending = ToolRequestQueue(dir).pending()
            val started =
                store
                    .eventsForAttempt(attemptId)
                    .onFailure { error ->
                        log.log(LogLevel.WARN, "store", LogOutcome.FAILED, attemptContext, reason = error.sentence())
                    }.getOrNull()
                    ?.map { it.event }
                    ?.firstOrNull { it.kind == AttemptStarted.name }
            var unrecorded = 0
            pending.forEach { entry ->
                val event =
                    ToolRequestDropped.event(
                        ts = clock.now(),
                        projectId = projectId,
                        data = droppedData(entry, redactor),
                        runId = started?.runId ?: runId,
                        issueId = started?.issueId,
                        issueIdentifier = started?.issueIdentifier,
                        attemptId = attemptId,
                    )
                store.record(event).onFailure { error ->
                    unrecorded++
                    log.log(LogLevel.WARN, "store", LogOutcome.FAILED, attemptContext, reason = error.sentence())
                }
            }
            // A drop the store did not record keeps the directory, so the next run tries again.
            if (unrecorded > 0) continue
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) dir.deleteRecursively()
            log.log(
                LogLevel.WARN,
                ATTEMPT_DIRECTORY,
                LogOutcome.COMPLETED,
                attemptContext.copy(issueId = started?.issueId, issueIdentifier = started?.issueIdentifier),
                fields = mapOf("dropped" to pending.size),
                reason = "deleted an attempt directory left by a previous run",
            )
        } catch (e: Exception) {
            log.log(LogLevel.WARN, ATTEMPT_DIRECTORY, LogOutcome.FAILED, context.copy(attemptId = attemptId), reason = e.sentence())
        }
    }
}

/** The `tool_request_dropped` data of [entry]: `request` and `to`, `chars` (of the redacted, trimmed text) or `source`. */
private fun droppedData(
    entry: QueuedRequest,
    redactor: Redactor,
): Map<String, String> =
    when (entry) {
        is QueuedRequest.Unreadable -> mapOf(ToolRequestEvent.REQUEST to ToolRequestEvent.UNKNOWN)
        is QueuedRequest.Valid ->
            when (val request = entry.request) {
                is ToolRequest.Move -> mapOf(ToolRequestEvent.REQUEST to request.kind, Tracker.TO to request.to)
                is ToolRequest.Comment ->
                    mapOf(
                        ToolRequestEvent.REQUEST to request.kind,
                        Tracker.CHARS to
                            redactor
                                .redact(request.text)
                                .trim()
                                .length
                                .toString(),
                    )
                // m2-16: a result queued by the submit_result MCP tool, named like the drain names it.
                is ToolRequest.Result -> mapOf(ToolRequestEvent.REQUEST to request.kind, ToolRequestEvent.SOURCE to Tracker.MCP)
            }
    }

private const val ATTEMPT_DIRECTORY = "attempt_directory"
