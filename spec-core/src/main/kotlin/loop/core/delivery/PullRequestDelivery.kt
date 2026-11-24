package loop.core.delivery

import kotlinx.coroutines.withContext
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.IssueComment
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.Redactor
import loop.api.TrackerAdapter
import loop.api.TrackerError
import loop.core.events.BranchPushed
import loop.core.events.Delivery
import loop.core.events.DeliveryFailed
import loop.core.events.EventDefinition
import loop.core.events.IssueCommented
import loop.core.events.IssueMoved
import loop.core.events.PullRequestFound
import loop.core.events.PullRequestOpened
import loop.core.events.PullRequestUpdated
import loop.core.events.Tracker
import loop.core.trackers.UnroutedWrite
import loop.core.verify.WorkspaceFingerprint
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * What a pull request delivery needs (m2-25), all of it facts Loop holds, never the agent's result file or words:
 * the [issue] as the tracker shows it now, which the comment and the move are made with (m4-14), the verification's
 * [attemptId], [fingerprint], rendered [checks] and number of [judgmentCalls], the [workspaceKey] that names the
 * branch, and the URL of the pull request already [commented] on the issue by an earlier try, null when none was.
 */
data class DeliveryFacts(
    val issue: Issue,
    val identifier: String,
    val attemptId: String?,
    val fingerprint: String,
    val checks: List<String>,
    val judgmentCalls: Int,
    val workspaceKey: String,
    val commented: String?,
) {
    val issueId: String get() = issue.id
    val title: String get() = issue.title
    val nativeRef: Map<String, String> get() = issue.nativeRef.orEmpty()
}

/**
 * How a pull request delivery ended (m2-25). [commented] is the URL of the pull request commented on the issue, by
 * this try or an earlier one, null when none is yet: the caller keeps it, so a replay never comments it twice.
 */
sealed interface DeliveryOutcome {
    val commented: String?

    /**
     * The branch is pushed, or an already merged [pullRequest] holds the verified commit, the [pullRequest] is open
     * and its URL on the issue; [moved] when the delivery was asked to move the issue too and did.
     */
    data class Delivered(
        val pullRequest: PullRequest,
        val moved: Boolean,
        override val commented: String?,
    ) : DeliveryOutcome

    /** The [step] could not be done now: a network error, a server error or a rate limit. The next tick tries again. */
    data class Retry(
        val step: String,
        val reason: String,
        override val commented: String? = null,
    ) : DeliveryOutcome

    /** The [step] was refused for good and `delivery_failed` is recorded: only a new approval tries again. */
    data class Failed(
        val step: String,
        val reason: String,
        override val commented: String? = null,
    ) : DeliveryOutcome
}

/**
 * Delivers a verified result as a pull request (m2-25, story 79), the one delivery both policies call. Its steps are
 * separate, in order: the fingerprint must name a commit with nothing uncommitted, since a push carries only commits;
 * the title and body are built from [DeliveryFacts] and redacted; the pull request of the branch is looked up in every
 * state; the commit is pushed to `loop/<workspace key>`; the open pull request is updated, or a new one opened; its URL
 * is commented on the issue, unless an earlier try already did; last, only when a [Handoff] is given, the issue moves
 * to its handoff state. A pull request already merged at the verified commit counts as delivered: nothing is pushed,
 * opened or updated, and the comment and the move still run. Every step is idempotent, so a replay after a crash opens
 * no second pull request. Records `pull_request_found` for a merged one, `branch_pushed`, `pull_request_opened` or
 * `pull_request_updated`, `issue_commented`, `issue_moved`, and `delivery_failed` for a permanent failure. Never
 * throws, except to propagate cancellation.
 */
class PullRequestDelivery(
    private val executor: DeliveryExecutor,
    private val tracker: TrackerAdapter,
    private val redactor: Redactor,
    private val clock: Clock,
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val record: suspend (definition: EventDefinition, data: Map<String, String>) -> Unit,
) {
    /**
     * The optional last step: the move of the issue [from] its state [to] its handoff state, recorded as `issue_moved`
     * with [event] added to its data.
     */
    data class Handoff(
        val from: String,
        val to: String,
        val event: Map<String, String> = emptyMap(),
    )

    /** Runs the steps in order and stops at the first that does not succeed; without [handoff], after the comment. */
    suspend fun deliver(
        facts: DeliveryFacts,
        handoff: Handoff? = null,
    ): DeliveryOutcome {
        val sha =
            WorkspaceFingerprint.committedHead(facts.fingerprint)
                ?: return failed(
                    STEP_FINGERPRINT,
                    "the verified workspace ${facts.fingerprint} is not a clean commit, a push would not carry what was verified",
                    facts.commented,
                )
        val branch = branch(facts.workspaceKey)
        val title = redactor.redact("${facts.identifier}: ${facts.title}")
        val body = body(facts)
        val chars = body.codePointCount(0, body.length)
        if (chars > MAX_BODY_CHARS) {
            return failed(STEP_BODY, "the body has $chars characters, over the limit of $MAX_BODY_CHARS", facts.commented)
        }
        val found = step(STEP_PULL_REQUEST) { executor.findPullRequest(branch) }.getOrElse { return it.outcome(facts.commented) }
        val pullRequest =
            if (found != null && delivered(found, sha)) {
                record(
                    PullRequestFound,
                    mapOf(
                        Delivery.NUMBER to found.number.toString(),
                        Delivery.URL to found.url,
                        Delivery.STATE to MERGED,
                    ),
                )
                found
            } else {
                push(sha, branch)?.let { return it.outcome(facts.commented) }
                openOrUpdate(branch, title, body, found?.takeIf { it.state == PullRequestState.Open })
                    .getOrElse { return it.outcome(facts.commented) }
            }
        val commented = comment(facts, pullRequest).getOrElse { return it.outcome(facts.commented) }
        if (handoff == null) return DeliveryOutcome.Delivered(pullRequest, moved = false, commented = commented)
        move(facts, handoff)?.let { return it.outcome(commented) }
        return DeliveryOutcome.Delivered(pullRequest, moved = true, commented = commented)
    }

    /** The pull request body, redacted, built from [facts] only. */
    private fun body(facts: DeliveryFacts): String =
        redactor.redact(
            EvidenceSummary.pullRequestBody(
                facts.identifier,
                facts.attemptId,
                facts.fingerprint,
                facts.checks,
                facts.judgmentCalls,
                link(facts),
            ),
        )

    /** The push step: [sha] to [branch], recorded as `branch_pushed`. Its failure, null when it pushed. */
    private suspend fun push(
        sha: String,
        branch: String,
    ): Throwable? {
        step(STEP_PUSH) { executor.push(sha, branch) }.onFailure { return it }
        record(BranchPushed, mapOf(Delivery.SHA to sha, Delivery.BRANCH to branch))
        return null
    }

    /** The pull request step: updates [open] when there is one, opens a new one otherwise, and records which. */
    private suspend fun openOrUpdate(
        branch: String,
        title: String,
        body: String,
        open: PullRequest?,
    ): Result<PullRequest> {
        val result =
            step(STEP_PULL_REQUEST) {
                if (open != null) executor.updatePullRequest(open.number, title, body) else executor.openPullRequest(branch, title, body)
            }
        result.onSuccess { pullRequest ->
            val definition = if (pullRequest.created) PullRequestOpened else PullRequestUpdated
            record(definition, mapOf(Delivery.NUMBER to pullRequest.number.toString(), Delivery.URL to pullRequest.url))
        }
        return result
    }

    /** The comment step: the URL of [pullRequest] on the issue, unless an earlier try commented it. The URL commented. */
    private suspend fun comment(
        facts: DeliveryFacts,
        pullRequest: PullRequest,
    ): Result<String> {
        if (facts.commented == pullRequest.url) return Result.success(pullRequest.url)
        val what =
            when {
                pullRequest.state == PullRequestState.Merged -> "found pull request #${pullRequest.number} for this issue, already merged"
                pullRequest.created -> "opened pull request #${pullRequest.number} for this issue"
                else -> "updated pull request #${pullRequest.number} for this issue"
            }
        val text = redactor.redact("Loop $what: ${pullRequest.url}")
        step(STEP_COMMENT) { tracker.addComment(facts.issue, IssueComment(LOOP_AUTHOR, clock.now(), text)) }
            .onFailure { return Result.failure(it) }
        record(
            IssueCommented,
            mapOf(
                Tracker.CHARS to text.length.toString(),
                Tracker.BY to Tracker.BY_HOST,
                Tracker.REASON to PULL_REQUEST_REASON,
            ),
        )
        return Result.success(pullRequest.url)
    }

    /** The optional last step: the move of the issue to its handoff state. Its failure, null when it moved. */
    private suspend fun move(
        facts: DeliveryFacts,
        handoff: Handoff,
    ): Throwable? {
        step(STEP_MOVE) { tracker.moveIssue(facts.issue, handoff.to) }.onFailure { return it }
        record(
            IssueMoved,
            mapOf(Tracker.FROM to handoff.from, Tracker.TO to handoff.to, Tracker.BY to Tracker.BY_HOST, Tracker.VERIFIED to "true") +
                handoff.event,
        )
        return null
    }

    /** `Closes #<number>` for an issue of the pull request's own GitHub repository, `Refs <identifier>` otherwise. */
    private fun link(facts: DeliveryFacts): String {
        val repo = facts.nativeRef["repo"]
        val number = facts.nativeRef["number"]
        return if (repo != null && number != null && repo.equals(executor.repository, ignoreCase = true)) {
            "Closes #$number"
        } else {
            "Refs ${facts.identifier}"
        }
    }

    /** A step's result, any exception it throws turned into its failure, so one failure path classifies them all. */
    private suspend fun <T> step(
        name: String,
        block: suspend () -> Result<T>,
    ): Result<T> =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }.recoverCatching { throw StepFailure(name, it) }

    private suspend fun Throwable.outcome(commented: String?): DeliveryOutcome {
        val failure = this as StepFailure
        val reason = redactor.redact(failure.cause.describe()).take(REASON_MAX)
        return if (permanent(failure.cause)) {
            failed(failure.step, reason, commented)
        } else {
            DeliveryOutcome.Retry(failure.step, reason, commented)
        }
    }

    private suspend fun failed(
        step: String,
        reason: String,
        commented: String?,
    ): DeliveryOutcome.Failed {
        val redacted = redactor.redact(reason).take(REASON_MAX)
        record(DeliveryFailed, mapOf(Delivery.STEP to step, Delivery.REASON to redacted))
        return DeliveryOutcome.Failed(step, redacted, commented)
    }

    private class StepFailure(
        val step: String,
        override val cause: Throwable,
    ) : Exception(cause)

    companion object {
        /**
         * True when [found], the pull request of the branch, already delivers the commit [sha]: merged with its head at
         * that commit, as m2-26 merges it. A merged one at another commit, or a closed one, is history.
         */
        fun delivered(
            found: PullRequest,
            sha: String,
        ): Boolean = found.state == PullRequestState.Merged && found.headSha.equals(sha, ignoreCase = true)

        /** The branch a verified commit is pushed to, the one the worktree hooks check out: `loop/<workspace key>`. */
        fun branch(workspaceKey: String): String = "loop/$workspaceKey"

        /**
         * True when trying [error] again cannot help, so the delivery waits for a new approval: a [DeliveryError]
         * says so itself; a tracker refusal, any status under 500 but a rate limit, is permanent, like a write for a
         * tracker the registry does not have (m4-14); an unreachable tracker, a rate limit hold, a server error and any
         * other exception are tried again at the next tick.
         */
        fun permanent(error: Throwable): Boolean =
            when (error) {
                is DeliveryError -> error.permanent
                is TrackerError.Unavailable, is TrackerError.RateLimited -> false
                is TrackerError.Status -> error.status < SERVER_ERROR
                is TrackerError, is UnroutedWrite -> true
                else -> false
            }

        /** GitHub refuses a pull request body longer than this many characters. */
        const val MAX_BODY_CHARS: Int = 65_536

        const val STEP_FINGERPRINT = "fingerprint"
        const val STEP_BODY = "body"
        const val STEP_PUSH = "push"
        const val STEP_PULL_REQUEST = "pull_request"
        const val STEP_COMMENT = "comment"
        const val STEP_MOVE = "move"

        /** The `reason` of the `issue_commented` event of the pull request comment. */
        const val PULL_REQUEST_REASON = IssueCommented.PULL_REQUEST

        private const val MERGED = "merged"
        private const val LOOP_AUTHOR = "loop"
        private const val REASON_MAX = 200
        private const val SERVER_ERROR = 500

        private fun Throwable.describe(): String =
            (message ?: this::class.simpleName ?: "unknown error").lineSequence().first().trimEnd('.')
    }
}
