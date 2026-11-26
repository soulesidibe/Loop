package loop.core.reactions

import kotlinx.coroutines.withContext
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.PullRequestReview
import loop.api.PullRequestReviews
import loop.api.RecordedEvent
import loop.api.ReviewComment
import loop.api.SecretRedactor
import loop.core.events.EventDefinition
import loop.core.events.ReactionKeys
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.waiting.Wait
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The `changes_requested` reaction of `reactions:` (m4-36, PLAN 6.7, story 133). [Reactions.poll] asks it about each
 * watched wait, the unapproved `Waiting(approval)` waits of m4-34 that hold a pull request, at most once every
 * [Reactions.POLL_FLOOR_MS], through the run's delivery client.
 *
 * It reads the reviews of the pull request and its current head. Only the reviews of an author whose association is in
 * `trusted_associations`, by default `OWNER`, `MEMBER` and `COLLABORATOR`, count: any other review, one with no
 * association included, neither triggers, counts nor escalates, and any other review comment never reaches the agent.
 * The latest decisive review of each trusted reviewer counts ([standing]); a `CHANGES_REQUESTED` one on the current head
 * is a change request. The change requests standing on one head make a round, named by its newest review: a new head or a
 * new review starts a new round. Under `send_to_agent` the reviews not sent before are sent, once per review id: the
 * review comments are read then, never on a poll with no change request, and [sendBack] gets the [findings], each review
 * body and comment, redacted and cut. The orchestrator ends the wait and schedules a retry on a `max_attempts` slot,
 * `loop.retry_kind` = `reaction`; `reaction_triggered` follows with [ReactionKeys.REVIEW] and [ReactionKeys.HEAD]. When
 * that slot was the last one and the issue parked instead, the event holds no review id and the reviews are not marked
 * sent, so after `loop resume` they reach the agent. Since bl-224 the event of a scheduled retry also holds the findings,
 * which reach the retry after a restart, and a wait a human approved or sent back while GitHub was read is kept: the
 * reaction records `reaction_skipped` with `human_decided`, counts nothing and marks nothing sent. When the round was sent and still stands `escalate_after` after its
 * reaction, Loop sends nothing more for it: it records `reaction_skipped` with `escalated` and tells the human through
 * [escalate], with the pull request URL, once per round. `retries` bounds the reactions of an issue as for `ci_failed`; a
 * round past it records `retries_spent` and, when `escalate_after` is set, is told to the human at once, once per round.
 *
 * What was sent, and when, is read from the run store's reaction events, [reactionsBefore], and the ones this process
 * recorded, so a restart never sends a review twice nor tells the human twice; a store that cannot be read sends
 * nothing. A reviews or comments read refused for good, a 403 or a 404 naming "Pull requests: read" among them, records
 * `reaction_skipped` with `reviews_unread` and the provider's words once per wait; one that can pass with time is logged
 * and records nothing. The wait goes on either way. Under `notify_human` a new round records `reaction_skipped` once.
 */
class ChangesRequestedReaction(
    /** The reaction events of the project in the run store, every run. */
    private val reactionsBefore: suspend () -> Result<List<RecordedEvent>>,
    /** Records an event of [Wait]'s issue and attempt. */
    private val record: suspend (Wait, EventDefinition, Map<String, String>) -> Unit,
    /**
     * Ends the wait, unless a human decided it since the tick read it (bl-224), and sends its issue back to its agent
     * with the findings, on a `max_attempts` slot. What came of it, see [SendBackOutcome].
     */
    private val sendBack: suspend (Wait.Approval, Issue, String) -> SendBackOutcome,
    /** Tells the human that changes are still requested: the wait, the reason, the pull request URL, when. */
    private val escalate: suspend (Wait.Approval, String, String?, Instant) -> Unit,
    /** The log context of a wait. */
    private val contextOf: (Wait) -> LogContext,
    private val log: KeyValueLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val io: CoroutineContext,
) {
    /** The reviews this process sent, by issue id, each with when it was sent: a store write that failed still counts. */
    private val sent = mutableMapOf<String, MutableMap<Long, Instant>>()

    /** The `changes_requested` reactions this process triggered, by issue id. */
    private val counted = mutableMapOf<String, Int>()

    /** The skips recorded in this process, by issue id and a key of the wait or the round, so each is recorded once. */
    private val skipped = mutableSetOf<Pair<String, String>>()

    /**
     * Reads the reviews of the watched wait [one], through [reviews], which [Reactions.poll] shares with
     * `approved_green` (m4-37), and reacts as [reaction] says; the comments are read through [executor]. True when the
     * issue was sent back, so the wait ended.
     */
    suspend fun poll(
        one: Reactions.Watched,
        reaction: Reaction,
        executor: DeliveryExecutor,
        reviews: suspend () -> Result<PullRequestReviews> = { executor.pullRequestReviews(one.number) },
    ): Boolean {
        val read = read(one, "reviews") { reviews() } ?: return false
        val trusted = (reaction.trustedAssociations ?: ReactionsConfig.DEFAULT_TRUSTED_ASSOCIATIONS).toSet()
        val standing = standing(read, trusted)
        if (standing.isEmpty()) return false
        val stored =
            reactionsBefore().getOrElse { error ->
                warn(one, "cannot read the changes_requested reactions of the run store: ${error.message ?: error::class.simpleName}")
                return false
            }
        val issueId = one.wait.issueId
        val sentAt = sentIn(stored, issueId).toMutableMap()
        sent[issueId]?.forEach { (id, at) -> sentAt.merge(id, at) { a, b -> minOf(a, b) } }
        val round = standing.maxOf { it.id }
        val unsent = standing.filter { it.id !in sentAt }
        val data = data(one, reaction, read.headSha)
        if (unsent.isEmpty()) {
            escalateIfDue(one, reaction, read, round, sentAt[round], stored, data)
            return false
        }
        if (reaction.action == ReactionAction.NotifyHuman) {
            skipRound(one, round, ReactionSkipped.NOTIFY_HUMAN, data, stored)
            return false
        }
        val used = maxOf(countIn(stored, issueId), counted[issueId] ?: 0)
        val retries = reaction.retries
        if (retries != null && used >= retries) {
            val recorded = skipRound(one, round, ReactionSkipped.RETRIES_SPENT, data + (ReactionKeys.RETRY to used.toString()), stored)
            if (recorded && reaction.escalateAfterMs != null) {
                val reason =
                    "changes are requested on pull request #${one.number}, review $round, and the changes_requested reaction " +
                        "spent its $retries retries. Loop sends nothing more for this round, read the pull request"
                escalate(one.wait, reason, read.url ?: one.wait.pullRequest, clock.now())
            }
            return false
        }
        val comments = read(one, "review comments") { executor.pullRequestReviewComments(one.number) } ?: return false
        val findings =
            findings(one.number.toString(), read.headSha, unsent, comments.filter { it.association.trusted(trusted) }) {
                redactor.redact(it)
            }
        val retry = used + 1
        log.log(
            LogLevel.WARN,
            REACTIONS,
            LogOutcome.FAILED,
            contextOf(one.wait),
            mapOf(ReactionKeys.REACTION to ReactionsConfig.CHANGES_REQUESTED, ReactionKeys.RETRY to retry),
            "changes are requested on pull request #${one.number}, the issue goes back to its agent",
        )
        val extra =
            when (sendBack(one.wait, one.issue, findings)) {
                SendBackOutcome.Scheduled -> {
                    val now = clock.now()
                    sent.getOrPut(issueId) { mutableMapOf() }.putAll(unsent.associate { it.id to now })
                    // bl-224: the findings, so a restart before the retry still gives the reviews marked sent to the agent.
                    mapOf(ReactionKeys.REVIEW to unsent.joinToString(",") { it.id.toString() }, ReactionKeys.FINDINGS to findings)
                }
                SendBackOutcome.Parked -> emptyMap()
                SendBackOutcome.Decided -> {
                    record(one.wait, ReactionSkipped, data + (ReactionKeys.REASON to ReactionSkipped.HUMAN_DECIDED))
                    return true
                }
                SendBackOutcome.Kept -> return true
            }
        counted[issueId] = retry
        record(one.wait, ReactionTriggered, data + extra + (ReactionKeys.RETRY to retry.toString()))
        return true
    }

    /**
     * Tells the human once that the round [round], sent at [sentAt], still stands `escalate_after` later, and records it.
     * The escalations in [stored] and the ones of this process count, so a restart tells nobody twice.
     */
    private suspend fun escalateIfDue(
        one: Reactions.Watched,
        reaction: Reaction,
        read: PullRequestReviews,
        round: Long,
        sentAt: Instant?,
        stored: List<RecordedEvent>,
        data: Map<String, String>,
    ) {
        val after = reaction.escalateAfterMs ?: return
        if (reaction.action != ReactionAction.SendToAgent || sentAt == null) return
        val now = clock.now()
        if (now - sentAt < after.milliseconds) return
        if (!skipRound(one, round, ReactionSkipped.ESCALATED, data, stored)) return
        val reason =
            "changes are still requested on pull request #${one.number}, review $round, ${after.milliseconds} after Loop sent " +
                "it to the agent. Loop sends nothing more for this round, read the pull request"
        escalate(one.wait, reason, read.url ?: one.wait.pullRequest, now)
    }

    /**
     * Records `reaction_skipped` with [reason] and [data] once for the round [round] of [one]'s issue: not when this
     * process or the run store, [stored], holds it already. True when it was recorded now.
     */
    private suspend fun skipRound(
        one: Reactions.Watched,
        round: Long,
        reason: String,
        data: Map<String, String>,
        stored: List<RecordedEvent>,
    ): Boolean {
        val issueId = one.wait.issueId
        if (skippedIn(stored, issueId, round, reason)) return false
        if (!skipped.add(issueId to "$reason:$round")) return false
        record(one.wait, ReactionSkipped, data + reasonOf(round, reason))
        return true
    }

    /** Records `reaction_skipped` with [data] once for [one]'s issue and [key]. */
    private suspend fun skipOnce(
        one: Reactions.Watched,
        key: String,
        data: Map<String, String>,
    ) {
        if (skipped.add(one.wait.issueId to key)) record(one.wait, ReactionSkipped, data)
    }

    /**
     * The answer of [call], the read of [what] of [one]'s pull request, null when it failed: a refusal for good records
     * `reaction_skipped` with `reviews_unread` once per wait, anything else is logged at warn. The wait goes on either way.
     */
    private suspend fun <T> read(
        one: Reactions.Watched,
        what: String,
        call: suspend () -> Result<T>,
    ): T? {
        val answer =
            try {
                withContext(io) { call() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return answer.getOrElse { error ->
            val detail = redactor.redact(error.message ?: error::class.simpleName.orEmpty()).take(REASON_MAX)
            warn(one, "cannot read the $what of pull request #${one.number}: $detail")
            if (error is DeliveryError && error.permanent) {
                skipOnce(
                    one,
                    "unread:${one.wait.since}",
                    data(one, null, null) + (ReactionKeys.REASON to ReactionSkipped.REVIEWS_UNREAD) + (ReactionKeys.DETAIL to detail),
                )
            }
            null
        }
    }

    private fun warn(
        one: Reactions.Watched,
        text: String,
    ) {
        log.log(LogLevel.WARN, REACTIONS, LogOutcome.FAILED, contextOf(one.wait), reason = redactor.redact(text).take(REASON_MAX))
    }

    private fun data(
        one: Reactions.Watched,
        reaction: Reaction?,
        head: String?,
    ): Map<String, String> =
        buildMap {
            put(ReactionKeys.REACTION, ReactionsConfig.CHANGES_REQUESTED)
            put(ReactionKeys.ACTION, (reaction?.action ?: ReactionAction.SendToAgent).wire)
            put(ReactionKeys.ISSUE, one.wait.identifier)
            put(ReactionKeys.PULL_REQUEST, one.number.toString())
            head?.let { put(ReactionKeys.HEAD, it) }
            reaction?.retries?.let { put(ReactionKeys.RETRIES, it.toString()) }
        }

    companion object {
        private const val REACTIONS = "reactions"
        private const val REASON_MAX = 200
        private const val CHANGES_REQUESTED = "CHANGES_REQUESTED"
        private const val ELLIPSIS = "..."

        /** The states of a review that decide where its reviewer stands; a comment or a pending review does not. */
        private val DECISIVE = setOf("APPROVED", CHANGES_REQUESTED, "DISMISSED")

        /** The most characters of one review body in the findings. */
        const val BODY_MAX: Int = 2_000

        /** The most characters of one comment line in the findings. */
        const val LINE_MAX: Int = 300

        /** The most comments the findings hold. */
        const val COMMENTS_MAX: Int = 20

        /**
         * The change requests standing on the current head of [read], oldest first: among the reviews of an author
         * whose association is in [trusted], the latest decisive review of each reviewer, `APPROVED`,
         * `CHANGES_REQUESTED` or `DISMISSED`, by submission time then id, kept when it requests changes on the head. A
         * review of any other author, one with no association included, is dropped first, so it neither triggers nor
         * replaces a trusted one. A comment does not replace a change request, as on GitHub, and a review on an older
         * head requests nothing of this one.
         */
        fun standing(
            read: PullRequestReviews,
            trusted: Set<String>,
        ): List<PullRequestReview> =
            latestDecisive(read, trusted)
                .filter { it.state.uppercase() == CHANGES_REQUESTED && it.commitId == read.headSha }
                .sortedBy { it.id }

        /**
         * Among the reviews of [read] whose author's association is in [trusted], the latest decisive review of each
         * reviewer, `APPROVED`, `CHANGES_REQUESTED` or `DISMISSED`, by submission time then id, on any head. A review of
         * any other author, one with no association included, is dropped first. Shared with `approved_green` (m4-37).
         */
        fun latestDecisive(
            read: PullRequestReviews,
            trusted: Set<String>,
        ): List<PullRequestReview> =
            read.reviews
                .filter { it.association.trusted(trusted) && it.state.uppercase() in DECISIVE }
                .groupBy { it.reviewer }
                .values
                .map { reviews ->
                    reviews.maxWith(compareBy<PullRequestReview, Instant?>(nullsFirst()) { it.submittedAt }.thenBy { it.id })
                }

        /**
         * The reviews of [issueId] the `changes_requested` reactions among [events] sent, each with the time of the first
         * `reaction_triggered` that sent it.
         */
        fun sentIn(
            events: List<RecordedEvent>,
            issueId: String,
        ): Map<Long, Instant> =
            events
                .filter { it.kind == ReactionTriggered.name && it.isChangesRequested(issueId) }
                .flatMap { event -> reviewIds(event).map { it to event.ts } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, times) -> times.min() }

        /** The `changes_requested` reactions of [issueId] among [events]. */
        fun countIn(
            events: List<RecordedEvent>,
            issueId: String,
        ): Int = events.count { it.kind == ReactionTriggered.name && it.isChangesRequested(issueId) }

        /** True when [events] hold a `reaction_skipped` with [reason] for the round named by review [round] of [issueId]. */
        private fun skippedIn(
            events: List<RecordedEvent>,
            issueId: String,
            round: Long,
            reason: String,
        ): Boolean =
            events.any {
                it.kind == ReactionSkipped.name &&
                    it.isChangesRequested(issueId) &&
                    it.data[ReactionKeys.REASON] == reason &&
                    round in reviewIds(it)
            }

        /** True when this author association, compared upper case, is in [trusted]; a missing one never is. */
        private fun String?.trusted(trusted: Set<String>): Boolean = this != null && trim().uppercase() in trusted

        private fun RecordedEvent.isChangesRequested(issueId: String): Boolean =
            this.issueId == issueId && data[ReactionKeys.REACTION] == ReactionsConfig.CHANGES_REQUESTED

        private fun reviewIds(event: RecordedEvent): List<Long> =
            event.data[ReactionKeys.REVIEW]
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty()

        private fun reasonOf(
            round: Long,
            reason: String,
        ): Map<String, String> = mapOf(ReactionKeys.REVIEW to round.toString(), ReactionKeys.REASON to reason)

        /**
         * The findings of the change requests [reviews] on pull request [number] at [head] for the agent: each review,
         * its reviewer and its body, cut at [BODY_MAX] characters, then its comments among [comments], one line each with
         * the path and the line, cut at [LINE_MAX] characters, at most [COMMENTS_MAX] in all, in the order of [comments]. Each text goes through
         * [redact] before it is cut. The findings end by saying the agent cannot reply on GitHub.
         */
        fun findings(
            number: String,
            head: String,
            reviews: List<PullRequestReview>,
            comments: List<ReviewComment>,
            redact: (String) -> String,
        ): String {
            var shown = 0
            var hidden = 0
            return buildString {
                append(redact("Changes are requested on pull request #$number, on its head $head."))
                reviews.forEach { review ->
                    append("\n\nReview ${review.id} by ${redact(review.reviewer)}, changes requested on $head:\n")
                    append(cut(redact(review.body.trim()).ifEmpty { "(no text)" }, BODY_MAX))
                    val own = comments.filter { it.reviewId == review.id }
                    if (own.isEmpty()) return@forEach
                    append("\n\nIts comments:")
                    own.forEach { comment ->
                        if (shown >= COMMENTS_MAX) {
                            hidden++
                            return@forEach
                        }
                        shown++
                        val where = comment.line?.let { "${comment.path}, line $it" } ?: "${comment.path}, no line"
                        val text =
                            comment.body
                                .lines()
                                .joinToString(" ") { it.trim() }
                                .trim()
                        append("\n").append(cut(redact("- $where: $text"), LINE_MAX))
                    }
                }
                if (hidden > 0) append("\n- and $hidden more comments")
                append(
                    "\n\nAddress each review and comment in the workspace and commit. You cannot reply on GitHub or resolve " +
                        "the threads, a human does that once the pull request is updated.",
                )
            }
        }

        private fun cut(
            text: String,
            max: Int,
        ): String = if (text.length > max) text.take(max - ELLIPSIS.length) + ELLIPSIS else text
    }
}
