package loop.api

import kotlin.time.Instant

/**
 * Receives every event Loop records: phase transitions, runner output, tracker moves.
 * Implementations return a failed [Result] instead of throwing, so a broken sink
 * never stops the caller; the caller logs the failure and goes on.
 */
public interface EventSink {
    /** Records [event]. A failure means the event was not stored. */
    public suspend fun record(event: RecordedEvent): Result<Unit>
}

/**
 * One recorded event with the ids of everything it belongs to. Only [projectId]
 * is required: an event always belongs to a project (ADR 0001), while a poll
 * event, for example, has no issue, attempt, session or turn.
 *
 * [kind] names the event (`preflight`, `phase`, `attempt_started`, `attempt_finished`,
 * `issue_moved`, `runner_output`, `hook_finished`). A `hook_finished` event has no phase;
 * outside an attempt (workspace removal) it carries the issue and no attempt id.
 * [phase] is set when [kind] is `phase`, with the attempt phase name of the spec.
 * [data] holds the event's other fields as text, so this module needs no json dependency.
 */
public data class RecordedEvent(
    public val ts: Instant,
    public val projectId: ProjectId,
    public val kind: String,
    public val runId: RunId? = null,
    public val issueId: String? = null,
    public val issueIdentifier: String? = null,
    public val attemptId: AttemptId? = null,
    public val sessionId: SessionId? = null,
    public val turnId: TurnId? = null,
    public val phase: String? = null,
    public val data: Map<String, String> = emptyMap(),
) {
    init {
        require(projectId.value.isNotBlank()) { "Recorded event needs a project id" }
        require(kind.isNotBlank()) { "Recorded event needs a kind" }
    }
}
