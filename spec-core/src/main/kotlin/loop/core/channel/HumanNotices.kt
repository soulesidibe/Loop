package loop.core.channel

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import loop.api.HumanChannel
import loop.api.HumanEvent
import loop.api.HumanNotice
import loop.api.ParkedIssue
import loop.core.events.EventDefinition
import loop.core.events.HookFailed
import loop.core.events.HumanNotified
import loop.core.trackers.IssueIdDisplay
import loop.core.waiting.Wait
import loop.core.workspace.HookListener
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import loop.core.workspace.HookRunner
import loop.core.workspace.ShellHookRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.time.Instant

/**
 * The notices of m4-30: Loop tells a human that a run needs them, through the [channel] and through
 * `hooks.on_human_needed`, when an issue is parked and when a verified result waits for `loop approve`, fresh or after a
 * delivery failed for good or an auto merge fell back, and since m4-36 when changes are still requested on a pull request
 * past `escalate_after`. Never twice for one wait or park: an approval wait keeps the key
 * of its notice in its data ([Wait.Approval.noticed]), so the same wait saved again on a later tick or read back after a
 * restart notifies nobody, and the wait a refused delivery or merge leaves, which drops the key, notifies again. A park
 * is notified once, by the one call that records it.
 *
 * Nothing here ever stops the run: a channel that fails is a warning, and a hook that fails, hangs past
 * `hooks.timeout_ms` or cannot start records `hook_failed`.
 */
class HumanNotices(
    private val channel: HumanChannel,
    /** What the workflow in effect says, read at each notice so a reload reaches the next one. */
    private val settings: () -> NoticeSettings,
    /** The `LOOP_*` variables every run of the hook gets, beside the `LOOP_NOTICE_*` ones. */
    private val env: Map<String, String>,
    private val recorder: NoticeRecorder,
    /** The run's redaction, applied to the reason and the URL before the channel, the events and the hook see them. */
    private val redact: (String) -> String,
    private val runner: HookRunner = ShellHookRunner(),
) {
    /**
     * Saves the approval [wait] through [save], with the key of its notice, and tells the human about [event] for
     * [reason] when the wait has not been notified yet. An approved wait, the auto policy's, is saved and notifies nobody.
     * The wait is saved before the human is told, so a stop in between loses a notice rather than sending it twice.
     */
    suspend fun waiting(
        wait: Wait.Approval,
        event: HumanEvent,
        reason: String,
        save: suspend (Wait.Approval) -> Unit,
    ) {
        val key = key(wait.issueId, wait.reason.wire, wait.since)
        if (wait.approvedBy != null || wait.humanNotice == key) {
            save(wait)
            return
        }
        save(wait.noticed(key))
        tell(wait.issueId, wait.identifier, event, reason, wait.pullRequest, wait.since)
    }

    /**
     * Saves the ask [wait] (m4-31) through [save], with the key of its notice, and tells the human about its question for
     * [reason] when the wait has not been notified yet, so the same wait saved again or read back after a restart never
     * notifies twice. Saved before the human is told, as [waiting] does.
     */
    suspend fun asking(
        wait: Wait.Ask,
        reason: String = "",
        save: suspend (Wait.Ask) -> Unit,
    ) {
        val key = key(wait.issueId, wait.reason.wire, wait.since)
        if (wait.humanNotice == key) {
            save(wait)
            return
        }
        save(wait.noticed(key))
        tell(wait.issueId, wait.identifier, HumanEvent.Question, reason, url = null, at = wait.since)
    }

    /**
     * Tells the human that changes are still requested on the pull request at [url] of [wait]'s issue (m4-36), for
     * [reason], at [at]. Called once per review round, where the `changes_requested` reaction records its escalation.
     */
    suspend fun changesRequested(
        wait: Wait.Approval,
        reason: String,
        url: String?,
        at: Instant,
    ) {
        tell(wait.issueId, wait.identifier, HumanEvent.ChangesRequested, reason, url, at)
    }

    /**
     * Tells the human that the pull request at [url] of [wait]'s issue is approved and green (m4-37), for [reason], at
     * [at]. Called once per head, where the `approved_green` reaction records it.
     */
    suspend fun approvedGreen(
        wait: Wait.Approval,
        reason: String,
        url: String?,
        at: Instant,
    ) {
        tell(wait.issueId, wait.identifier, HumanEvent.ApprovedGreen, reason, url, at)
    }

    /** Tells the human that [park] parked its issue, for [reason]. Called once per park, where the park is recorded. */
    suspend fun parked(
        park: ParkedIssue,
        reason: String,
    ) {
        tell(park.issueId, park.identifier, HumanEvent.Parked, reason, url = null, at = park.parkedAt)
    }

    private suspend fun tell(
        issueId: String,
        identifier: String,
        event: HumanEvent,
        reason: String,
        url: String?,
        at: Instant,
    ) {
        val settings = settings()
        val notice = HumanNotice(issueId, settings.display.name(issueId, identifier), event, redact(reason), url?.let(redact), at)
        val channelError =
            try {
                channel.notify(notice).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }?.let { redact(it.describe()) }
        channelError?.let { recorder.warn(issueId, identifier, "cannot tell the human through the ${settings.channel} channel: $it") }
        recorder.record(
            issueId,
            identifier,
            HumanNotified,
            listOfNotNull(
                HumanNotified.EVENT to event.wire,
                HumanNotified.CHANNEL to settings.channel,
                HumanNotified.REASON to notice.reason.take(REASON_MAX),
                notice.url?.let { HumanNotified.URL to it },
                notice.nextCommand?.let { HumanNotified.COMMAND to it },
                channelError?.let { HumanNotified.CHANNEL_ERROR to it.take(REASON_MAX) },
            ).toMap(),
        )
        settings.hook?.takeIf { it.isNotBlank() }?.let { runHook(it, notice, identifier, settings) }
    }

    /**
     * Runs `hooks.on_human_needed` in the project folder with [notice] as JSON on stdin and as `LOOP_NOTICE_*` variables.
     * The JSON goes through a file only the owner reads, which the shell reads as its stdin before the operator's
     * [script] runs, and which is removed afterwards.
     */
    private suspend fun runHook(
        script: String,
        notice: HumanNotice,
        identifier: String,
        settings: NoticeSettings,
    ) {
        val hook = HookName.ON_HUMAN_NEEDED
        val listener = recorder.hookListener(notice.issueId, identifier)
        var file: Path? = null
        val result =
            try {
                file =
                    Files.createTempFile(
                        "loop-notice-",
                        ".json",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                file.writeText(json(notice))
                listener.started(hook, script)
                runner.run(
                    hook,
                    "exec < \"\$$NOTICE_FILE\"\n$script",
                    settings.folder,
                    env + variables(notice) + (NOTICE_FILE to file.toString()),
                    settings.timeoutMs,
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { listener.stopped(hook) }
                throw e
            } catch (e: Exception) {
                HookResult(hook, exitCode = null, timedOut = false, timeoutMs = settings.timeoutMs, durationMs = 0, output = e.describe())
            } finally {
                runCatching { file?.deleteIfExists() }
            }
        listener.finished(script, result)
        val reason = result.reason ?: return
        recorder.record(
            notice.issueId,
            identifier,
            HookFailed,
            listOfNotNull(
                HookFailed.HOOK to hook.id,
                result.exitCode?.let { HookFailed.EXIT_CODE to it.toString() },
                HookFailed.TIMED_OUT to result.timedOut.toString(),
                HookFailed.REASON to redact(reason).take(REASON_MAX),
            ).toMap(),
        )
    }

    companion object {
        /** The variable that names the file holding the notice JSON, the hook's stdin. */
        const val NOTICE_FILE: String = "LOOP_NOTICE_FILE"

        /** The characters of a reason kept in an event. */
        private const val REASON_MAX = 500

        /** The key of the notice of a wait or a park: the issue, the reason and when it started. */
        fun key(
            issueId: String,
            reason: String,
            since: Instant,
        ): String = "$issueId|$reason|$since"

        /** The `LOOP_NOTICE_*` variables of [notice]; the URL and the command only when the notice has them. */
        fun variables(notice: HumanNotice): Map<String, String> =
            listOfNotNull(
                "LOOP_NOTICE_ISSUE_ID" to notice.issueId,
                "LOOP_NOTICE_IDENTIFIER" to notice.identifier,
                "LOOP_NOTICE_EVENT" to notice.event.wire,
                "LOOP_NOTICE_REASON" to notice.reason,
                notice.url?.let { "LOOP_NOTICE_URL" to it },
                "LOOP_NOTICE_AT" to notice.at.toString(),
                notice.nextCommand?.let { "LOOP_NOTICE_COMMAND" to it },
            ).toMap()

        /** [notice] as the JSON object the hook reads on stdin, a missing URL or command as null. */
        fun json(notice: HumanNotice): String =
            buildJsonObject {
                put("issue_id", JsonPrimitive(notice.issueId))
                put("identifier", JsonPrimitive(notice.identifier))
                put("event", JsonPrimitive(notice.event.wire))
                put("reason", JsonPrimitive(notice.reason))
                put("url", notice.url?.let(::JsonPrimitive) ?: JsonNull)
                put("at", JsonPrimitive(notice.at.toString()))
                put("command", notice.nextCommand?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()

        private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"
    }
}

/** What the workflow in effect says about notices (m4-30), read at each one. */
data class NoticeSettings(
    /** `hooks.on_human_needed`, null or blank when there is none. */
    val hook: String?,
    /** `hooks.timeout_ms`. */
    val timeoutMs: Long,
    /** The project folder, the folder of `WORKFLOW.md`, where the hook runs. */
    val folder: Path,
    /** How the issue is shown, so the next command of a notice works as typed. */
    val display: IssueIdDisplay,
    /** The kind of the channel, `channel.kind`, for the events and warnings. */
    val channel: String,
)

/** Records what the notices did (m4-30): the events, the runs of the hook and the warnings, in the caller's run. */
interface NoticeRecorder {
    /** Records a [definition] event about the issue [issueId]; a failure is the recorder's to log. */
    suspend fun record(
        issueId: String,
        identifier: String,
        definition: EventDefinition,
        data: Map<String, String>,
    )

    /** The listener of a run of `hooks.on_human_needed` about the issue [issueId], which records its `hook_finished`. */
    fun hookListener(
        issueId: String,
        identifier: String,
    ): HookListener

    /** Logs [reason] at warn about the issue [issueId]. */
    suspend fun warn(
        issueId: String,
        identifier: String,
        reason: String,
    )
}
