package loop.core.orchestrator

import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RunId
import loop.core.channel.NoticeRecorder
import loop.core.events.EventDefinition
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.workspace.HookListener
import kotlin.time.Clock

/**
 * m4-30: records what the human notices did in the run of [runId], outside any attempt: each event through [sink], a
 * sink failure logged, each run of the hook through the listener [hookListener] builds, which records `hook_finished`,
 * and each warning in [log].
 */
internal class NoticeRecording(
    private val sink: EventSink,
    private val sinkName: String,
    private val log: KeyValueLog,
    private val clock: Clock,
    private val projectId: ProjectId,
    private val runId: RunId,
    private val hookListener: (record: suspend (EventDefinition, Map<String, String>) -> Unit, context: () -> LogContext) -> HookListener,
) : NoticeRecorder {
    override suspend fun record(
        issueId: String,
        identifier: String,
        definition: EventDefinition,
        data: Map<String, String>,
    ) {
        val event = definition.event(clock.now(), projectId, data, runId, issueId, identifier)
        log.guardSink(sinkName, context(issueId, identifier)) { sink.record(event).getOrThrow() }
    }

    override fun hookListener(
        issueId: String,
        identifier: String,
    ): HookListener = hookListener({ definition, data -> record(issueId, identifier, definition, data) }, { context(issueId, identifier) })

    override suspend fun warn(
        issueId: String,
        identifier: String,
        reason: String,
    ) {
        log.log(LogLevel.WARN, NOTICE, LogOutcome.FAILED, context(issueId, identifier), reason = reason)
    }

    private fun context(
        issueId: String,
        identifier: String,
    ) = LogContext(runId = runId, projectId = projectId, issueId = issueId, issueIdentifier = identifier)

    private companion object {
        /** The log event of a notice to a human. */
        const val NOTICE = "notice"
    }
}
