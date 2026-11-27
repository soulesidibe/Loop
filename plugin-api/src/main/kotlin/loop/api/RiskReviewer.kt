package loop.api

import java.nio.file.Path

/**
 * The reviewer of the merge risk assessment (m2-28): one read only runner session in the issue's [RiskReviewRequest.workspace]
 * that reads the prompt and answers with one verdict per dimension as JSON text. The orchestrator parses the answer, applies
 * the code floors and computes the score: the reviewer never decides the merge. Implementations return a failed [Result]
 * holding a [RiskReviewError] instead of throwing.
 */
public fun interface RiskReviewer {
    public suspend fun review(request: RiskReviewRequest): Result<RiskReview>
}

/**
 * One review: the [workspace] it may read, the [model] that judges, the [prompt] and its [timeoutMs].
 *
 * bl-254: [baseRef] is the project's base commit the merge was checked against, and [cloneWorkspace] is true when the
 * workflow's `workspace.git` is `clone` and [workspace] is one of its clone workspaces. A reviewer whose runner runs its
 * own git in the workspace, such as the protected roots check of the ACP runner, passes both on, so that git follows
 * the clone rule and never takes its base from the clone's own refs. Both default to the `hooks` mode behavior.
 */
public data class RiskReviewRequest(
    public val workspace: Path,
    public val model: String,
    public val prompt: String,
    public val timeoutMs: Long,
    public val baseRef: String? = null,
    public val cloneWorkspace: Boolean = false,
)

/** What the reviewer answered: its final [text], and the [tokens] and [costUsd] it spent, null when not reported. */
public data class RiskReview(
    public val text: String,
    public val tokens: LedgerTokens?,
    public val costUsd: Double?,
)

/** Why a review gave no answer: it ran out of time, or it could not start or ended in error. */
public sealed class RiskReviewError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The session did not answer within its time limit, and was stopped. */
    public class Timeout(
        detail: String,
    ) : RiskReviewError(detail)

    /** The session could not start, exited in error, or printed no answer. */
    public class Crashed(
        detail: String,
        cause: Throwable? = null,
    ) : RiskReviewError(detail, cause)
}
