package loop.api

import kotlin.time.Instant

/**
 * Delivers a verified result as a pull request (m2-25, story 79), host side with Loop's token. The Orchestrator
 * orders the steps, each one separate: it checks the fingerprint, calls [findPullRequest], then [push], then
 * [openPullRequest] or [updatePullRequest], then comments and, last and only when asked, moves the issue through its
 * [TrackerAdapter]. Every step is idempotent, so a delivery replayed after a crash opens no second pull request.
 * Implementations return a failed [Result] holding a [DeliveryError] instead of throwing.
 */
public interface DeliveryExecutor {
    /** The repository the pull request is opened on, `owner/name`. */
    public val repository: String

    /**
     * Pushes the commit [sha] to `refs/heads/[branch]` of the configured remote, from the project repository, never
     * forced. The local branch [branch] must point at [sha]: a branch that moved after the verification delivers
     * nothing. Pushing a commit the remote branch already holds succeeds. A rejected push (not a fast forward) is
     * [DeliveryError.Rejected].
     */
    public suspend fun push(
        sha: String,
        branch: String,
    ): Result<Unit>

    /**
     * The pull request whose head is [branch] and whose base is the configured base, whatever its state: the open
     * one when there is one, else the latest merged one, else the latest closed one. Null when there is none.
     */
    public suspend fun findPullRequest(branch: String): Result<PullRequest?>

    /**
     * Opens a pull request from [branch] against the configured base. When the provider answers that one already
     * exists, a race with another delivery, it looks the open one up once more and updates it instead.
     */
    public suspend fun openPullRequest(
        branch: String,
        title: String,
        body: String,
    ): Result<PullRequest>

    /** Sets the [title] and [body] of the pull request [number]. */
    public suspend fun updatePullRequest(
        number: Long,
        title: String,
        body: String,
    ): Result<PullRequest>

    /**
     * Fetches `refs/heads/<base>` of the configured base from the provider (m2-26), with the token only in the
     * fetch's own git process, lands its objects in the project repository and writes `refs/loop/base/<base>` there
     * at the fetched commit. The fetched commit id, the proof: never a ref name read after the copy. A failure that
     * can pass with time is [DeliveryError.Unavailable], any other [DeliveryError.Rejected]. Not supported by
     * default.
     */
    public suspend fun fetchBase(): Result<String> = Result.failure(DeliveryError.Rejected("this delivery cannot fetch the base"))

    /** The commit the configured base is at on the provider now (m2-26), read once more right before a merge. */
    public suspend fun baseTip(): Result<String> = Result.failure(DeliveryError.Rejected("this delivery cannot read the base"))

    /**
     * Merges the pull request [number] with [method], only when its head is still [sha] (m2-26): the provider
     * refuses the merge when the head moved. The commit the merge made on the base. A refusal (401, 403, 404, 405,
     * 409, 422) is [DeliveryError.Rejected], with [DeliveryError.retryOnce] when the provider says a branch moved
     * while it merged; a network error, a 5xx answer or a rate limit hold is [DeliveryError.Unavailable]. Not
     * supported by default.
     */
    public suspend fun mergePullRequest(
        number: Long,
        sha: String,
        method: MergeMethod,
    ): Result<String> = Result.failure(DeliveryError.Rejected("this delivery cannot merge a pull request"))

    /**
     * Whether the provider would merge the pull request [number] now (m2-28): its `mergeable` answer, null while the
     * provider has not computed it, and its mergeable state, such as GitHub's `clean`, `unstable` (a commit status or
     * check that is not green), `dirty` (a conflict) or `blocked`. Read before an auto merge, which merges only a
     * mergeable `clean` pull request. Not supported by default, which no auto merge passes.
     */
    public suspend fun mergeability(number: Long): Result<Mergeability> =
        Result.failure(DeliveryError.Rejected("this delivery cannot read whether a pull request is mergeable"))

    /**
     * The GitHub Actions runs of the commit [sha] (m3-15), one page of at most 100 with the total the provider counts,
     * read by the CI wait before a merge and by the CI condition of an auto merge. Runs are matched by their head
     * commit only. A refusal (401, 403, 404) is [DeliveryError.Rejected], a 403 or 404 naming the "Actions: read"
     * permission; a network error, a 5xx answer or a rate limit hold is [DeliveryError.Unavailable]. Not supported by
     * default, which no CI wait and no auto merge passes.
     */
    public suspend fun actionsRuns(sha: String): Result<ActionsRuns> =
        Result.failure(DeliveryError.Rejected("this delivery cannot read the Actions runs of a commit"))

    /**
     * The jobs of the Actions run [runId] (m4-07), one page of at most 100 with the total the provider counts, read by
     * the CI wait for the failed runs only, to tell a run GitHub refused to start (every job with zero steps) from a red
     * one. A refusal (401, 403, 404) is [DeliveryError.Rejected], a 403 or 404 naming the "Actions: read" permission; a
     * network error, a 5xx answer or a rate limit hold is [DeliveryError.Unavailable]. Not supported by default.
     */
    public suspend fun actionsJobs(runId: Long): Result<ActionsJobs> =
        Result.failure(DeliveryError.Rejected("this delivery cannot read the jobs of an Actions run"))

    /**
     * The reviews of the pull request [number] (m4-36), with the commit its head is at now, read by the
     * `changes_requested` reaction. Every page of the listing, in the provider's order, oldest first. A refusal (401,
     * 403, 404) is [DeliveryError.Rejected], a 403 or 404 naming the "Pull requests: read" permission; a network error,
     * a 5xx answer or a rate limit hold is [DeliveryError.Unavailable]. Not supported by default, which no reaction
     * passes.
     */
    public suspend fun pullRequestReviews(number: Long): Result<PullRequestReviews> =
        Result.failure(DeliveryError.Rejected("this delivery cannot read the reviews of a pull request"))

    /**
     * The review comments left on the diff of the pull request [number] (m4-36), newest first, one page of at most 100,
     * read by the `changes_requested` reaction only when a change request stands. A comment the provider gives without
     * its id, path or body is left out. Its failures are those of [pullRequestReviews]. Not supported by default.
     */
    public suspend fun pullRequestReviewComments(number: Long): Result<List<ReviewComment>> =
        Result.failure(DeliveryError.Rejected("this delivery cannot read the review comments of a pull request"))
}

/**
 * The reviews of one pull request as the provider listed them (m4-36): the commit its head is at now, [headSha], its
 * web [url] and its [reviews] in the provider's order, oldest first.
 */
public data class PullRequestReviews(
    public val headSha: String,
    public val url: String?,
    public val reviews: List<PullRequestReview>,
)

/**
 * One review of a pull request (m4-36): its [id], the login of its [reviewer], its [state] as the provider names it,
 * such as `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED` or `DISMISSED`, the commit it was left on, [commitId], its
 * [body], empty when it has none, when it was submitted, null for a pending one, and how its author is associated
 * with the repository, [association], such as `OWNER`, `MEMBER`, `COLLABORATOR` or `NONE`, null when the provider did
 * not say.
 */
public data class PullRequestReview(
    public val id: Long,
    public val reviewer: String,
    public val state: String,
    public val commitId: String?,
    public val body: String,
    public val submittedAt: Instant?,
    public val association: String?,
)

/**
 * One review comment on the diff of a pull request (m4-36): its [id], the review it belongs to, [reviewId], null when
 * the provider did not say, the file [path], the [line] it is on, null when the provider gives none, its [body], and
 * how its author is associated with the repository, [association], null when the provider did not say.
 */
public data class ReviewComment(
    public val id: Long,
    public val reviewId: Long?,
    public val path: String,
    public val line: Long?,
    public val body: String,
    public val association: String?,
)

/**
 * The jobs of one Actions run as the provider listed them (m4-07): [jobs], at most one page, and [totalCount], the
 * number of jobs it counts in all, so a run is never called not started on a part of its jobs.
 */
public data class ActionsJobs(
    public val totalCount: Long,
    public val jobs: List<ActionsJob>,
)

/**
 * One job of an Actions run (m4-07): its [id], its [name], its [status] and [conclusion] as the provider names them,
 * and the number of [steps] it ran, zero for a job GitHub refused to start (billing or limits). Since m4-35 [stepList]
 * holds each step that has a name, in order, so the `ci_failed` reaction can name the step that failed.
 */
public data class ActionsJob(
    public val id: Long,
    public val name: String,
    public val status: String,
    public val conclusion: String?,
    public val steps: Int,
    public val stepList: List<ActionsStep> = emptyList(),
)

/** One step of an Actions job (m4-35): its [name] and its [conclusion] as the provider names it, null while it runs. */
public data class ActionsStep(
    public val name: String,
    public val conclusion: String?,
)

/**
 * The Actions runs of one commit as the provider listed them (m3-15): [runs], at most one page, and [totalCount], the
 * number of runs it counts in all, so a listing longer than its page is never judged on a part of it.
 */
public data class ActionsRuns(
    public val totalCount: Long,
    public val runs: List<ActionsRun>,
)

/**
 * One Actions run (m3-15): its [id], its [name], the [workflowId] and the [event] that started it, its [runNumber]
 * within that workflow and the [runAttempt] of a rerun, its [status] as the provider names it, such as `queued`,
 * `in_progress` or `completed`, its [conclusion] once completed, such as `success` or `failure`, and its web [url].
 */
public data class ActionsRun(
    public val id: Long,
    public val name: String,
    public val workflowId: Long,
    public val event: String,
    public val runNumber: Long,
    public val runAttempt: Long,
    public val status: String,
    public val conclusion: String?,
    public val url: String,
)

/**
 * What the provider says of merging one pull request now (m2-28): [mergeable], null while it is not computed yet, and
 * its [state] as the provider names it, null when it said nothing.
 */
public data class Mergeability(
    public val mergeable: Boolean?,
    public val state: String?,
)

/** How a pull request is merged (m2-26): a merge commit or a squash, never a rebase, which rewrites what was checked. */
public enum class MergeMethod(
    public val wire: String,
) {
    Merge("merge"),
    Squash("squash"),
}

/**
 * One pull request: its [number], its web [url], whether this delivery [created] it rather than found or updated it,
 * its [state] and the commit its head is at, [headSha], null when the provider did not say.
 */
public data class PullRequest(
    public val number: Long,
    public val url: String,
    public val created: Boolean,
    public val state: PullRequestState = PullRequestState.Open,
    public val headSha: String? = null,
)

/** Where a pull request stands: open, merged, or closed without a merge. */
public enum class PullRequestState {
    Open,
    Merged,
    Closed,
}

/** Why a delivery step failed: [permanent] when trying again cannot help, so the delivery waits for a new approval. */
public sealed class DeliveryError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public abstract val permanent: Boolean

    /**
     * True for a refusal worth one more try at the next tick before it counts as permanent (m2-26): the provider
     * refused a merge because a branch moved under it while it merged, as GitHub's 405 "Base branch was modified" and
     * 409 "Head branch was modified" say, which its own delayed update of a just pushed head can cause.
     */
    public open val retryOnce: Boolean = false

    /**
     * Refused for good, until a human looks: a rejected push, a branch that is not at the verified commit, an answer
     * such as 401, 403, 404 or 422, or a body over the provider's limit. [retryOnce] marks a merge refused because a
     * branch moved under it.
     */
    public class Rejected(
        detail: String,
        cause: Throwable? = null,
        override val retryOnce: Boolean = false,
    ) : DeliveryError(detail, cause) {
        override val permanent: Boolean = true
    }

    /** Could not be done now, tried again at the next tick: a network error, a 5xx answer or a rate limit hold. */
    public class Unavailable(
        detail: String,
        cause: Throwable? = null,
    ) : DeliveryError(detail, cause) {
        override val permanent: Boolean = false
    }
}
