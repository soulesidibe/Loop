package loop.api

import kotlin.time.Instant

/**
 * Reads and moves issues in one tracker. Every call returns a [Result] whose
 * failure is a [TrackerError]; implementations do not throw for expected failures.
 * [updateLabels] is optional: a tracker without labels keeps its default, which fails
 * with [TrackerError.Unsupported]. [completeIssue] is optional too: its default returns the issue unchanged.
 * [createIssue] is optional like [updateLabels] (m4-33): its default fails with [TrackerError.Unsupported].
 *
 * The core writes through the versions of [moveIssue] and [addComment] that take the current [Issue] (m4-14, spec 11
 * and 17.5): a tracker whose writes need a provider id beyond [Issue.id], such as a project item id, reads it from
 * [Issue.nativeRef]. Their defaults call the id versions, so a tracker that writes by id overrides only those.
 */
public interface TrackerAdapter {
    /** Every state this tracker defines, in the tracker's own spelling. */
    public val definedStates: List<String>

    /** Issues in any of [states]. Malformed issues are skipped, not failed. */
    public suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>>

    /** Issues with the given ids. A malformed issue among them fails the call. */
    public suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>>

    /** Moves the issue [issueId] to [toState], which must be in [definedStates]. */
    public suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit>

    /**
     * Appends [comment] to the issue [issueId]. Fails with [TrackerError.NotFound] when
     * there is no such issue, [TrackerError.Malformed] when the issue cannot be read, and
     * [TrackerError.BlankComment] when the text is blank. The text is written as given:
     * redaction is the caller's job.
     */
    public suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit>

    /**
     * Moves the current [issue] to [toState], like the id version, which the default calls with [Issue.id]. The
     * [issue] is the one the core holds, its [Issue.nativeRef] included, with the tracker's own id (m4-14).
     */
    public suspend fun moveIssue(
        issue: Issue,
        toState: String,
    ): Result<Unit> = moveIssue(issue.id, toState)

    /**
     * Appends [comment] to the current [issue], like the id version, which the default calls with [Issue.id]. The
     * [issue] is the one the core holds, its [Issue.nativeRef] included, with the tracker's own id (m4-14).
     */
    public suspend fun addComment(
        issue: Issue,
        comment: IssueComment,
    ): Result<Unit> = addComment(issue.id, comment)

    /**
     * Completes [issue] just before a prompt is rendered (bl-06): a tracker that keeps what the agent must see
     * outside the issue it lists, such as GitHub comments, adds it to the returned issue, for example to its
     * [Issue.description]. It is called once per dispatch, where a failure means the prompt cannot be rendered
     * with all of it, so the attempt does not start. It is called again at each rotation of the agent session,
     * after the old session's [AgentSession.stop] with its usage callback, on the issue read again by id
     * (bl-132): there a failure starts the new session anyway, with the issue as the turn end read it (bl-152).
     * The default returns [issue] unchanged, for a tracker whose issue is already complete, like the files
     * tracker whose comments are part of the body.
     */
    public suspend fun completeIssue(issue: Issue): Result<Issue> = Result.success(issue)

    /**
     * Adds the labels [add] to the issue [issueId] and removes the labels [remove] in one write
     * (m2-11). Labels are compared trimmed and case insensitively, like the normalized
     * [Issue.labels]: a label already there is not added twice, a label not there is not an error,
     * and a blank one is ignored. Fails with [TrackerError.NotFound] when there is no such issue,
     * [TrackerError.Malformed] when the issue cannot be read, and [TrackerError.Unsupported] when the
     * tracker has no label write.
     */
    public suspend fun updateLabels(
        issueId: String,
        add: List<String> = emptyList(),
        remove: List<String> = emptyList(),
    ): Result<Unit> = Result.failure(TrackerError.Unsupported("labels"))

    /**
     * Creates an issue with [title], the [body] as its description and the [labels], in the tracker's first active
     * state or its equivalent, such as an `open` GitHub issue (m4-33, a follow up filed by an agent). Returns the new
     * issue as a read by id would give it, with the id the tracker gave it. Fails with [TrackerError.Rejected] when
     * the title is blank, with nothing written, and with [TrackerError.Unsupported] when the tracker cannot create
     * issues, the default.
     */
    public suspend fun createIssue(
        title: String,
        body: String,
        labels: List<String>,
    ): Result<Issue> = Result.failure(TrackerError.Unsupported("issues"))
}

/** A comment on an issue: who wrote it, when, and the [text] as final, already redacted. */
public data class IssueComment(
    public val author: String,
    public val at: Instant,
    public val text: String,
)

/** Why a tracker call failed. */
public sealed class TrackerError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** [state] is not one of the tracker's [definedStates]. */
    public class UndefinedState(
        public val state: String,
        public val definedStates: List<String>,
    ) : TrackerError("\"$state\" is not a state of the tracker. Valid states: ${definedStates.joinToString()}")

    /** No issue with [issueId] in the tracker. */
    public class NotFound(
        public val issueId: String,
    ) : TrackerError("No issue \"$issueId\" in the tracker")

    /** The issue at [source] cannot be read into an [Issue]. */
    public class Malformed(
        public val source: String,
        cause: Throwable? = null,
    ) : TrackerError("Malformed issue at $source", cause)

    /** The comment text has nothing in it once trimmed. */
    public class BlankComment : TrackerError("The comment is empty")

    /** The tracker does not implement the optional write [operation]. */
    public class Unsupported(
        public val operation: String,
    ) : TrackerError("The tracker does not support writing $operation")

    /**
     * The tracker refused a write before sending it (m2-23), such as a comment over the provider's length limit;
     * the message says why and what was not written. No request is made.
     */
    public class Rejected(
        detail: String,
    ) : TrackerError(detail)

    /** The provider answered a request with the non success HTTP [status] (spec 11.4 `tracker_status`). */
    public class Status(
        public val status: Int,
        detail: String,
    ) : TrackerError("The tracker answered $status: $detail")

    /**
     * The provider refused a request for its rate limit (spec 11.4 `tracker_rate_limited`). [retryAt] is when
     * requests may start again, null when the provider did not say.
     */
    public class RateLimited(
        public val retryAt: Instant?,
    ) : TrackerError("The tracker rate limit is exhausted" + (retryAt?.let { ", requests resume at $it" } ?: ""))

    /** A paged read could not be completed consistently (spec 11.4 `tracker_pagination`). */
    public class Pagination(
        detail: String,
    ) : TrackerError("The tracker pages are inconsistent: $detail")

    /** The tracker could not be reached or read. */
    public class Unavailable(
        cause: Throwable,
    ) : TrackerError(cause.message ?: "Tracker unavailable", cause)
}
