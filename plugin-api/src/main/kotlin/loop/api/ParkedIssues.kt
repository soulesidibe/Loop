package loop.api

import kotlin.time.Instant

/**
 * Stores the issues Loop parked, so they survive a restart. A parked issue is
 * not dispatched until `loop resume` or a change of its tracker state or labels.
 * Like [EventSink], implementations return a failed [Result] instead of throwing.
 */
public interface ParkedIssues {
    /** Parks [issue], replacing any record for the same project and issue id. */
    public suspend fun park(issue: ParkedIssue): Result<Unit>

    /** Every parked issue of [projectId], in no particular order. */
    public suspend fun list(projectId: ProjectId): Result<List<ParkedIssue>>

    /** Removes the record and returns it, or null when the issue was not parked. */
    public suspend fun unpark(
        projectId: ProjectId,
        issueId: String,
    ): Result<ParkedIssue?>
}

/**
 * One parked issue with the tracker [state] and [labels] it had when parked,
 * so a later change can be detected. [runId] is null when Loop ran without one.
 */
public data class ParkedIssue(
    public val projectId: ProjectId,
    public val issueId: String,
    public val identifier: String,
    public val reason: ParkReason,
    public val state: String,
    public val labels: Set<String>,
    public val parkedAt: Instant,
    public val runId: RunId? = null,
) {
    init {
        require(projectId.value.isNotBlank()) { "Parked issue needs a project id" }
        require(issueId.isNotBlank()) { "Parked issue needs an issue id" }
    }
}

/** Why an issue was parked, as the wire string stored and traced. */
@JvmInline
public value class ParkReason(
    public val wire: String,
) {
    public companion object {
        /** The issue failed `retries.max_attempts` consecutive attempts. */
        public val MaxAttempts: ParkReason = ParkReason("max_attempts")

        /**
         * An attempt went past one of its spend budgets (m2-20), named in the `issue_parked` event, for example
         * `per_attempt_tokens` (the m1-08 token cap, whose `token_cap` park reason it replaces) or `per_day_usd`.
         * It is not a failure. A park stored as `token_cap` by an older Loop still loads and resumes.
         */
        public val Budget: ParkReason = ParkReason("budget")

        /**
         * `retries.max_continuations` consecutive normal exits left the issue state and
         * labels unchanged. It is not a failure.
         */
        public val MaxContinuations: ParkReason = ParkReason("max_continuations")

        /**
         * A submitted result got the `unverified` verdict (m2-08): the kind has no check, a check could not
         * run, or the issue's acceptance commands differ from the list frozen at its first dispatch (m2-09).
         * Nothing proves the work, so it is never delivered. It is not a failure.
         */
        public val Unverified: ParkReason = ParkReason("unverified")

        /**
         * An agent session crossed the soft limit once more after the attempt already rotated `context.max_rotations`
         * times (m3-11). It is not a failure.
         */
        public val MaxRotations: ParkReason = ParkReason("max_rotations")

        /**
         * Two rotations in a row without progress, no new commit and no verification failing fewer checks (m3-12): the
         * issue is too big for one agent session, and a human should split it. It is not a failure.
         */
        public val SplitIssue: ParkReason = ParkReason("split_issue")
    }
}
