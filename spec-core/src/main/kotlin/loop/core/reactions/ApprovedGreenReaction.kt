package loop.core.reactions

import kotlinx.coroutines.withContext
import loop.api.DeliveryExecutor
import loop.api.PullRequestReview
import loop.api.PullRequestReviews
import loop.api.RecordedEvent
import loop.api.SecretRedactor
import loop.core.delivery.CiVerdict
import loop.core.delivery.CiWait
import loop.core.delivery.MergeCheck
import loop.core.events.Delivery
import loop.core.events.EventDefinition
import loop.core.events.MergeCheckFinished
import loop.core.events.ReactionKeys
import loop.core.events.ReactionTriggered
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.waiting.Wait
import loop.core.workflow.CiMode
import loop.core.workflow.WorkflowFile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The `approved_green` reaction of `reactions:` (m4-37, PLAN 6.7, story 134). [Reactions.poll] asks it about each
 * watched wait, the unapproved `Waiting(approval)` waits of m4-34 that hold a pull request, at most once every
 * [Reactions.POLL_FLOOR_MS], through the run's delivery client. Its action is `notify_human` only.
 *
 * Approved: among the reviews of a trusted author, as `changes_requested` reads them (m4-36), the latest decisive
 * review of each reviewer holds at least one `APPROVED` on the current head and no `CHANGES_REQUESTED` on any head,
 * since GitHub keeps a change request in force until its reviewer reviews again. The trusted associations are those of
 * `changes_requested` when it lists them, else `OWNER`, `MEMBER` and `COLLABORATOR`.
 *
 * Green follows the code kind's merge `ci`: under `ci: actions`, [CiWait.verdict] of the Actions runs of the current
 * head is [CiVerdict.Green]; under `ci: none`, the latest `merge_check_finished` of the issue in the run store,
 * [mergeChecksBefore], passed on the current head (m4-05), so a head pushed after the check is not green. A code kind
 * that does not merge has no green.
 *
 * Approved and green, Loop records `reaction_triggered` with the head and the green, then tells the human through
 * [notify], with the pull request URL, once per head: the `approved_green` reactions of the run store,
 * [reactionsBefore], and the ones this process recorded, so a restart never tells a head twice, and a new head that is
 * approved and green tells again. A store that cannot be read tells nothing. A read that fails is logged at warn and the
 * wait goes on.
 */
class ApprovedGreenReaction(
    /** The reaction events of the project in the run store, every run. */
    private val reactionsBefore: suspend () -> Result<List<RecordedEvent>>,
    /** The `merge_check_finished` events of the project in the run store, every run. */
    private val mergeChecksBefore: suspend () -> Result<List<RecordedEvent>>,
    /** Records an event of [Wait]'s issue and attempt. */
    private val record: suspend (Wait, EventDefinition, Map<String, String>) -> Unit,
    /** Tells the human that the pull request is approved and green: the wait, the reason, the pull request URL, when. */
    private val notify: suspend (Wait.Approval, String, String?, Instant) -> Unit,
    /** The log context of a wait. */
    private val contextOf: (Wait) -> LogContext,
    private val log: KeyValueLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val io: CoroutineContext,
) {
    /** The heads this process told the human about, by issue id and head, lower case. */
    private val told = mutableSetOf<Pair<String, String>>()

    /**
     * Reads the reviews of the watched wait [one] through [reviews] and, approved, the green of its head through
     * [executor] or the run store, and tells the human as [reaction] says. True when the human was told now.
     */
    suspend fun poll(
        one: Reactions.Watched,
        reaction: Reaction,
        workflow: WorkflowFile,
        executor: DeliveryExecutor,
        reviews: suspend () -> Result<PullRequestReviews> = { executor.pullRequestReviews(one.number) },
    ): Boolean {
        if (reaction.action != ReactionAction.NotifyHuman) return false
        val read = read(one, "the reviews of pull request #${one.number}") { reviews() } ?: return false
        val trusted =
            (workflow.config.reactions.changesRequested?.trustedAssociations ?: ReactionsConfig.DEFAULT_TRUSTED_ASSOCIATIONS).toSet()
        val approvers = approvers(read, trusted)
        if (approvers.isEmpty()) return false
        val head = read.headSha
        val key = one.wait.issueId to head.lowercase()
        if (key in told) return false
        val stored =
            reactionsBefore().getOrElse { error ->
                warn(one, "cannot read the approved_green reactions of the run store: ${error.message ?: error::class.simpleName}")
                return false
            }
        if (toldIn(stored, one.wait.issueId, head)) {
            told += key
            return false
        }
        val green = green(one, workflow, head, executor) ?: return false
        told += key
        record(
            one.wait,
            ReactionTriggered,
            mapOf(
                ReactionKeys.REACTION to ReactionsConfig.APPROVED_GREEN,
                ReactionKeys.ACTION to reaction.action.wire,
                ReactionKeys.ISSUE to one.wait.identifier,
                ReactionKeys.PULL_REQUEST to one.number.toString(),
                ReactionKeys.HEAD to head,
                ReactionKeys.GREEN to green,
            ),
        )
        log.log(
            LogLevel.INFO,
            REACTIONS,
            LogOutcome.COMPLETED,
            contextOf(one.wait),
            mapOf(ReactionKeys.REACTION to ReactionsConfig.APPROVED_GREEN, ReactionKeys.GREEN to green),
            "pull request #${one.number} is approved and green, the human is told",
        )
        notify(one.wait, reason(one.number, head, approvers, green), read.url ?: one.wait.pullRequest, clock.now())
        return true
    }

    /**
     * The green of [head] under the code kind's merge `ci` of [workflow]: [ReactionKeys.GREEN_ACTIONS] or
     * [ReactionKeys.GREEN_MERGE_CHECK], null when it is not green, cannot be read or the kind does not merge.
     */
    private suspend fun green(
        one: Reactions.Watched,
        workflow: WorkflowFile,
        head: String,
        executor: DeliveryExecutor,
    ): String? =
        when (
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.merge
                ?.ci
        ) {
            CiMode.Actions -> {
                val listing = read(one, "the Actions runs of the head of pull request #${one.number}") { executor.actionsRuns(head) }
                ReactionKeys.GREEN_ACTIONS.takeIf { listing != null && CiWait.verdict(listing) is CiVerdict.Green }
            }
            CiMode.None -> {
                val checks =
                    mergeChecksBefore().getOrElse { error ->
                        warn(one, "cannot read the merge checks of the run store: ${error.message ?: error::class.simpleName}")
                        null
                    }
                ReactionKeys.GREEN_MERGE_CHECK.takeIf { checks != null && mergeCheckPassed(checks, one.wait.issueId, head) }
            }
            null -> null
        }

    /** The answer of [call], the read of [what], null when it failed: logged at warn, the wait kept, nothing recorded. */
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
            warn(one, "cannot read $what: ${error.message ?: error::class.simpleName}")
            null
        }
    }

    private fun warn(
        one: Reactions.Watched,
        text: String,
    ) {
        log.log(LogLevel.WARN, REACTIONS, LogOutcome.FAILED, contextOf(one.wait), reason = redactor.redact(text).take(REASON_MAX))
    }

    companion object {
        private const val REACTIONS = "reactions"
        private const val REASON_MAX = 200
        private const val APPROVED = "APPROVED"
        private const val CHANGES_REQUESTED = "CHANGES_REQUESTED"

        /** The merge check outcomes that pass (m2-26). */
        private val PASSED = setOf(MergeCheck.UP_TO_DATE, MergeCheck.MERGED)

        /**
         * The reviewers who approved the current head of [read], sorted, among the latest decisive review of each
         * reviewer whose association is in [trusted]; none when such a review requests changes, on any head.
         */
        fun approvers(
            read: PullRequestReviews,
            trusted: Set<String>,
        ): List<String> {
            val latest = ChangesRequestedReaction.latestDecisive(read, trusted)
            if (latest.any { it.state.uppercase() == CHANGES_REQUESTED }) return emptyList()
            return latest
                .filter { it.state.uppercase() == APPROVED && it.commitId.equals(read.headSha, ignoreCase = true) }
                .map(PullRequestReview::reviewer)
                .sorted()
        }

        /**
         * True when the latest `merge_check_finished` of [issueId] among [events], by time then order, passed,
         * `up_to_date` or `merged`, on [head]: green under `ci: none` (m4-05).
         */
        fun mergeCheckPassed(
            events: List<RecordedEvent>,
            issueId: String,
            head: String,
        ): Boolean {
            val latest =
                events
                    .filter { it.kind == MergeCheckFinished.name && it.issueId == issueId }
                    .sortedBy { it.ts }
                    .lastOrNull() ?: return false
            return latest.data[Delivery.OUTCOME] in PASSED && latest.data[Delivery.HEAD].equals(head, ignoreCase = true)
        }

        /** True when [events] hold an `approved_green` reaction of [issueId] on [head]. */
        fun toldIn(
            events: List<RecordedEvent>,
            issueId: String,
            head: String,
        ): Boolean =
            events.any {
                it.kind == ReactionTriggered.name &&
                    it.issueId == issueId &&
                    it.data[ReactionKeys.REACTION] == ReactionsConfig.APPROVED_GREEN &&
                    it.data[ReactionKeys.HEAD].equals(head, ignoreCase = true)
            }

        /** The reason of the notice: the pull request [number], its [head], its [approvers] and which [green] applied. */
        fun reason(
            number: Long,
            head: String,
            approvers: List<String>,
            green: String,
        ): String {
            val how =
                if (green == ReactionKeys.GREEN_ACTIONS) {
                    "its Actions runs passed (ci: actions)"
                } else {
                    "the merge check passed on it (ci: none)"
                }
            return "pull request #$number is approved and green on its head ${MergeCheck.short(head)}: approved by " +
                "${approvers.joinToString(", ")}, and $how. It is ready to merge"
        }
    }
}
