package loop.tracker.github

import loop.api.ActionsRuns
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.MergeMethod
import loop.api.Mergeability
import loop.api.PullRequest
import loop.api.TrackerError
import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Pull request delivery on GitHub (m2-25): [push] and, since m2-26, [fetchBase] through [GitPush], to and from the
 * explicit URL of the repository on the GitHub host of the API with the token in a credential helper scoped to that
 * host, and the pull request steps, the base tip read and the merge through [GitHubPullRequests]. Never throws, cancellation aside: a failure is a failed [Result] with a
 * [DeliveryError]. GitHub's answers are classed like this: unreachable, a rate limit or a 5xx answer is
 * [DeliveryError.Unavailable], tried again at the next tick; any other answer, such as 401, 403, 404 or 422, and a
 * success that cannot be read are [DeliveryError.Rejected], which waits for a human.
 */
class GitHubDelivery(
    override val repository: String,
    private val git: GitPush,
    private val pullRequests: GitHubPullRequests,
    /** The base branch the pull requests target, fetched by [fetchBase] (m2-26). */
    val base: String = DEFAULT_BASE,
) : DeliveryExecutor {
    /**
     * Delivery to [owner]/[repo] with [token]: pushes commits of the project repository [projectDir] to
     * [GitPush.pushUrl] of the API at [apiUrl], and opens pull requests against [base] through that API. Git processes
     * get only [GitPush.minimalEnv] of [env]; the token is added to the push alone.
     */
    constructor(
        owner: String,
        repo: String,
        token: String,
        projectDir: Path,
        apiUrl: URI = GitHubTrackerSettings.DEFAULT_API_URL,
        base: String = DEFAULT_BASE,
        env: Map<String, String> = System.getenv(),
        transport: GitHubTransport = JdkGitHubTransport(),
        clock: Clock = Clock.System,
    ) : this(
        repository = "$owner/$repo",
        git = GitPush(projectDir, GitPush.pushUrl(apiUrl, owner, repo), token, env),
        pullRequests = GitHubPullRequests(GitHubClient(apiUrl, token, transport, clock), owner, repo, base),
        base = base,
    )

    override suspend fun push(
        sha: String,
        branch: String,
    ): Result<Unit> =
        try {
            git.push(sha, branch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(DeliveryError.Rejected("Cannot push $sha to $branch: ${e.message ?: e::class.simpleName}", e))
        }

    override suspend fun findPullRequest(branch: String): Result<PullRequest?> =
        github("Cannot look up the pull request of $branch on $repository") { pullRequests.find(branch) }

    override suspend fun openPullRequest(
        branch: String,
        title: String,
        body: String,
    ): Result<PullRequest> = github("Cannot open the pull request of $branch on $repository") { pullRequests.open(branch, title, body) }

    override suspend fun updatePullRequest(
        number: Long,
        title: String,
        body: String,
    ): Result<PullRequest> = github("Cannot update pull request #$number on $repository") { pullRequests.update(number, title, body) }

    /** Fetches [base] through [GitPush.fetch] and writes `refs/loop/base/<base>` in the project repository (m2-26). */
    override suspend fun fetchBase(): Result<String> =
        try {
            git.fetch(base)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(DeliveryError.Rejected("Cannot fetch $base: ${e.message ?: e::class.simpleName}", e))
        }

    override suspend fun baseTip(): Result<String> = github("Cannot read the tip of $base on $repository") { pullRequests.baseTip() }

    /**
     * Merges through [GitHubPullRequests.merge]. A 405 "Base branch was modified" or a 409 "Head branch was modified" is
     * a [DeliveryError.Rejected] with [DeliveryError.retryOnce] (m2-26): GitHub says so when a branch moved while it
     * merged, which its delayed update of a just pushed head can cause, so one more try at the next tick may pass.
     */
    override suspend fun mergePullRequest(
        number: Long,
        sha: String,
        method: MergeMethod,
    ): Result<String> {
        val merged = github("Cannot merge pull request #$number on $repository") { pullRequests.merge(number, sha, method.wire) }
        val error = merged.exceptionOrNull() ?: return merged
        val cause = error.cause as? TrackerError.Status
        if (cause == null || !branchModified(cause)) return merged
        return Result.failure(DeliveryError.Rejected(error.message.orEmpty(), cause, retryOnce = true))
    }

    /** Reads through [GitHubPullRequests.mergeability] (m2-28). */
    override suspend fun mergeability(number: Long): Result<Mergeability> =
        github("Cannot read whether pull request #$number on $repository is mergeable") { pullRequests.mergeability(number) }

    /** Lists through [GitHubPullRequests.actionsRuns] (m3-15). */
    override suspend fun actionsRuns(sha: String): Result<ActionsRuns> =
        github("Cannot list the Actions runs on $repository") { pullRequests.actionsRuns(sha) }

    /** The result of [call], a GitHub failure as a [DeliveryError] whose message starts with [context]. */
    private suspend fun <T> github(
        context: String,
        call: suspend () -> T,
    ): Result<T> =
        try {
            Result.success(call())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerError) {
            Result.failure(deliveryError(context, e))
        } catch (e: Exception) {
            Result.failure(DeliveryError.Rejected("$context: ${e.message}", e))
        }

    companion object {
        const val DEFAULT_BASE: String = "main"

        /**
         * [error] as a [DeliveryError], its message after [context]: unreachable, a rate limit and a 5xx answer can
         * pass with time, [DeliveryError.Unavailable]; anything else is [DeliveryError.Rejected].
         */
        internal fun deliveryError(
            context: String,
            error: TrackerError,
        ): DeliveryError {
            val message = "$context: ${error.message}"
            return when {
                error is TrackerError.Unavailable -> DeliveryError.Unavailable(message, error)
                error is TrackerError.RateLimited -> DeliveryError.Unavailable(message, error)
                error is TrackerError.Status && error.status >= SERVER_ERROR -> DeliveryError.Unavailable(message, error)
                else -> DeliveryError.Rejected(message, error)
            }
        }

        /** True for GitHub's refusal of a merge because the base or the head branch moved while it merged. */
        internal fun branchModified(error: TrackerError.Status): Boolean =
            error.status in setOf(NOT_ALLOWED, CONFLICT) && error.message.orEmpty().contains(BRANCH_MODIFIED, ignoreCase = true)

        private const val SERVER_ERROR = 500
        private const val NOT_ALLOWED = 405
        private const val CONFLICT = 409
        private const val BRANCH_MODIFIED = "branch was modified"
    }
}
