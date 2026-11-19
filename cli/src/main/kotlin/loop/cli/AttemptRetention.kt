package loop.cli

import loop.api.ProjectId
import loop.api.RunId
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.orchestrator.attemptsFolder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Prunes the attempt files of [projectId] last modified more than [retentionDays] ago (m2-18, plan 10.3), called
 * at startup once the run lock is held, so no live attempt of this Project writes one. Only regular files named
 * `<attempt_id>.jsonl.gz` in this Project's folder: evidence files back verdicts, which are kept forever, attempt
 * directories belong to the sweep, and a link is never followed nor deleted. Never throws: a failure is logged
 * and the run goes on. Logs one line when it deletes something.
 */
internal fun pruneAttemptFiles(
    loopHome: Path,
    projectId: ProjectId,
    runId: RunId,
    retentionDays: Int,
    log: KeyValueLog,
    clock: Clock,
) {
    val context = LogContext(runId = runId, projectId = projectId)
    val folder = attemptsFolder(loopHome, projectId)
    val cutoff = (clock.now() - retentionDays.days).toEpochMilliseconds()
    var pruned = 0
    var failed = 0
    var reason: String? = null
    val candidates =
        try {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return
            folder.listDirectoryEntries("*$ATTEMPT_FILE_SUFFIX")
        } catch (e: Exception) {
            log.log(LogLevel.WARN, ATTEMPT_RETENTION, LogOutcome.FAILED, context, reason = e.sentence())
            return
        }
    for (file in candidates) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() >= cutoff) continue
            // Deleting a path removes the entry itself, never what a link points to.
            if (Files.deleteIfExists(file)) pruned++
        } catch (e: Exception) {
            failed++
            reason = reason ?: "cannot prune ${file.name}: ${e.sentence()}"
        }
    }
    val fields = mapOf("pruned" to pruned, "retention_days" to retentionDays)
    if (failed > 0) {
        log.log(LogLevel.WARN, ATTEMPT_RETENTION, LogOutcome.FAILED, context, fields = fields + ("failed" to failed), reason = reason)
    } else if (pruned > 0) {
        log.log(LogLevel.INFO, ATTEMPT_RETENTION, LogOutcome.COMPLETED, context, fields = fields)
    }
}

private const val ATTEMPT_RETENTION = "attempt_retention"
private const val ATTEMPT_FILE_SUFFIX = ".jsonl.gz"
