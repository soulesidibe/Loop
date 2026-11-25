package loop.core.reactions

import kotlinx.coroutines.withContext
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.Mergeability
import loop.api.SecretRedactor
import loop.core.delivery.MergeCheck
import loop.core.events.EventDefinition
import loop.core.events.ReactionKeys
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.waiting.Wait
import loop.core.workflow.GuardsConfig
import loop.core.workflow.WorkflowFile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The reactions of `reactions:` (m4-34, PLAN 6.7): what Loop does when something happens to a pull request it opened
 * while the issue waits. Only `merge_conflict` acts so far.
 *
 * The watched set is every unapproved `Waiting(approval)` that holds a pull request, not sent back, whose issue the tick
 * read active and that runs no attempt. Under the `human` policy the pull request opens after the approval, so these are
 * the fallback waits: after a `delivery_failed` that left the pull request open, or after a fallen back auto merge.
 * Each tick [poll] reads the mergeability of each watched pull request at most once, at most once every [POLL_FLOOR_MS],
 * through the run's delivery client, so a rate limit hold lasts across ticks. `mergeable_state` `dirty` with a known
 * `mergeable` is a conflict; anything else, a failed read included, keeps the wait and records nothing.
 *
 * A conflict under `send_to_agent` records `reaction_triggered` and hands the wait to [sendBack] with the conflict
 * findings of the merge check: the orchestrator ends the wait and schedules a retry on a `max_attempts` slot. Under
 * `notify_human` it records `reaction_skipped` once per wait, since M4 sends no notice for a reaction.
 */
class Reactions(
    /** The run's delivery client for a workflow, the memoized executor of m3-15. */
    private val executorFor: (WorkflowFile) -> DeliveryExecutor,
    /** Records an event of [Wait]'s issue and attempt. */
    private val record: suspend (Wait, EventDefinition, Map<String, String>) -> Unit,
    /** Ends the wait and sends its issue back to its agent with the findings, on a `max_attempts` slot. */
    private val sendBack: suspend (Wait.Approval, Issue, String) -> Unit,
    /** The log context of a wait. */
    private val contextOf: (Wait) -> LogContext,
    private val log: KeyValueLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val io: CoroutineContext,
) {
    /** When each watched pull request was last polled in this process, by issue id. */
    private val polls = mutableMapOf<String, Instant>()

    /** The waits a `reaction_skipped` was recorded for, by issue id and `since`, so it is recorded once per wait. */
    private val skipped = mutableSetOf<Pair<String, Instant>>()

    /** One watched wait: its issue as the tick read it and the number of its pull request. */
    data class Watched(
        val wait: Wait.Approval,
        val issue: Issue,
        val number: Long,
    )

    /**
     * One tick of the reactions of [workflow] over the approval waits [approvals]: [active] are the issues the tick read
     * in an active state, [busy] the issues that run an attempt. Nothing is read when no reaction acts.
     */
    suspend fun poll(
        workflow: WorkflowFile,
        approvals: List<Wait.Approval>,
        active: List<Issue>,
        busy: Set<String>,
    ) {
        val reaction = workflow.config.reactions.mergeConflict
        val watched = if (reaction == null) emptyList() else watched(approvals, active, busy)
        polls.keys.retainAll(watched.map { it.issue.id }.toSet())
        skipped.retainAll { (issueId, since) -> watched.any { it.issue.id == issueId && it.wait.since == since } }
        if (reaction == null) return
        val executor = executorFor(workflow)
        for (one in watched) {
            val now = clock.now()
            val last = polls[one.issue.id]
            if (last != null && now - last < POLL_FLOOR_MS.milliseconds) continue
            polls[one.issue.id] = now
            val mergeability = read(one, "read the mergeability of pull request #${one.number}") { executor.mergeability(one.number) }
            if (mergeability == null || !conflicts(mergeability)) continue
            react(one, reaction, workflow, executor)
        }
    }

    private suspend fun react(
        one: Watched,
        reaction: Reaction,
        workflow: WorkflowFile,
        executor: DeliveryExecutor,
    ) {
        val data =
            mapOf(
                ReactionKeys.REACTION to ReactionsConfig.MERGE_CONFLICT,
                ReactionKeys.ACTION to reaction.action.wire,
                ReactionKeys.ISSUE to one.wait.identifier,
                ReactionKeys.PULL_REQUEST to one.number.toString(),
            )
        when (reaction.action) {
            ReactionAction.NotifyHuman -> {
                if (skipped.add(one.issue.id to one.wait.since)) {
                    record(one.wait, ReactionSkipped, data + (ReactionKeys.REASON to ReactionSkipped.NOTIFY_HUMAN))
                }
            }
            ReactionAction.SendToAgent -> {
                val tip = read(one, "read the base before the merge_conflict reaction") { executor.baseTip() } ?: return
                val base = baseOf(workflow)
                val detail = "GitHub reports pull request #${one.number} as dirty, it conflicts with $base"
                record(one.wait, ReactionTriggered, data)
                log.log(
                    LogLevel.WARN,
                    REACTIONS,
                    LogOutcome.FAILED,
                    contextOf(one.wait),
                    mapOf(ReactionKeys.REACTION to ReactionsConfig.MERGE_CONFLICT),
                    detail,
                )
                polls -= one.issue.id
                sendBack(one.wait, one.issue, redactor.redact(MergeCheck.conflictFindings(base, tip, detail)))
            }
        }
    }

    /** The answer of one delivery [call], null when it failed: logged at warn, the wait kept, nothing recorded. */
    private suspend fun <T> read(
        one: Watched,
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
            val reason = redactor.redact("cannot $what: ${error.message ?: error::class.simpleName}").take(REASON_MAX)
            log.log(LogLevel.WARN, REACTIONS, LogOutcome.FAILED, contextOf(one.wait), reason = reason)
            null
        }
    }

    companion object {
        /** At most one mergeability read per watched pull request in this time, in milliseconds. */
        const val POLL_FLOOR_MS: Long = 30_000

        private const val REACTIONS = "reactions"
        private const val DIRTY = "dirty"
        private const val REASON_MAX = 200

        /**
         * The watched waits of [approvals]: unapproved, not sent back, holding a pull request number, their issue in
         * [active] and not in [busy].
         */
        fun watched(
            approvals: List<Wait.Approval>,
            active: List<Issue>,
            busy: Set<String>,
        ): List<Watched> {
            val byId = active.associateBy { it.id }
            return approvals.mapNotNull { wait ->
                if (wait.approved || wait.sentBack || wait.issueId in busy) return@mapNotNull null
                val issue = byId[wait.issueId] ?: return@mapNotNull null
                val number = pullRequestNumber(wait) ?: return@mapNotNull null
                Watched(wait, issue, number)
            }
        }

        /** The number of the pull request [wait] holds, from the end of its URL (`.../pull/7`), null when it holds none. */
        fun pullRequestNumber(wait: Wait.Approval): Long? =
            wait.pullRequest
                ?.trim()
                ?.substringAfterLast("/pull/", "")
                ?.trimEnd('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0 }

        /** True when [mergeability] is a conflict: `mergeable_state` `dirty`, with `mergeable` computed. */
        fun conflicts(mergeability: Mergeability): Boolean = mergeability.mergeable != null && mergeability.state == DIRTY

        /** The base of the pull request delivery of the code kind. */
        private fun baseOf(workflow: WorkflowFile): String =
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.base ?: GuardsConfig.DEFAULT_BASE
    }
}
