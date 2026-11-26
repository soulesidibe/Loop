package loop.core.reactions

import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.Issue
import loop.api.RecordedEvent
import loop.api.SecretRedactor
import loop.core.delivery.CiVerdict
import loop.core.delivery.CiWait
import loop.core.events.EventDefinition
import loop.core.events.ReactionKeys
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.waiting.Wait
import loop.core.workflow.CiMode
import loop.core.workflow.WorkflowFile

/** What the CI wait does with a red verdict, as the `ci_failed` reaction decides (m4-35). */
sealed interface CiFailedOutcome {
    /** Today's path of m3-15: `delivery_failed`, the comment, an unapproved `Waiting(approval)`; [note] ends the comment. */
    data class Refuse(
        val note: String? = null,
    ) : CiFailedOutcome

    /**
     * The issue goes back to its agent: the CI wait ends with no `delivery_failed`, [comment] is posted on the issue and
     * [findings] become `loop.verify_findings` of the retry. [retry] is this reaction's count for the issue, from 1.
     */
    data class SendBack(
        val comment: String,
        val findings: String,
        val retry: Int,
        val retries: Int?,
    ) : CiFailedOutcome
}

/**
 * The `ci_failed` reaction of `reactions:` (m4-35, PLAN 6.7, bl-89). The CI wait of m3-15 asks it what to do with a
 * red verdict, [CiVerdict.Red] only: a run GitHub refused to start (`not_started`), a timeout and a failing no run rule
 * never reach it, and under `ci: none` no Actions run is read, so it never fires.
 *
 * Under `send_to_agent`, while the issue's count of `ci_failed` reactions is below `retries` (no `retries` bounds it by
 * `max_attempts` only), [decide] gives [CiFailedOutcome.SendBack] with the findings: the failed runs and their URLs, and
 * the failed job and step names from the jobs read of m4-07, since the agent cannot open the run URLs. The CI wait then
 * comments and calls [send], which hands the wait to [sendBack]: the orchestrator ends the wait and schedules a retry on
 * a `max_attempts` slot, `loop.retry_kind` = `reaction`. `reaction_triggered` follows, with the findings when the retry
 * is scheduled, so they reach the retry after a restart (bl-224). Past `retries` the red
 * verdict keeps today's path, `reaction_skipped` with `retries_spent` and a comment saying so. The count is the
 * `reaction_triggered` events of the issue the run store holds, [reactionsBefore], so it survives a restart, and the
 * count kept in this process, the larger one; a store that cannot be read keeps today's path. Under `notify_human` the
 * red verdict keeps today's path with `reaction_skipped`, since M4 sends no notice for a reaction.
 */
class CiFailedReaction(
    /** The `reaction_triggered` events of the project in the run store, every run. */
    private val reactionsBefore: suspend () -> Result<List<RecordedEvent>>,
    /** Records an event of [Wait]'s issue and attempt. */
    private val record: suspend (Wait, EventDefinition, Map<String, String>) -> Unit,
    /**
     * Ends the wait and sends its issue back to its agent with the findings, on a `max_attempts` slot. True when the
     * retry is scheduled, false when that slot was the last one and the issue parked.
     */
    private val sendBack: suspend (Wait, Issue, String) -> Boolean,
    /** The log context of a wait. */
    private val contextOf: (Wait) -> LogContext,
    private val log: KeyValueLog,
    private val redactor: SecretRedactor,
) {
    /** The `ci_failed` reactions this process triggered, by issue id: a store write that failed still counts. */
    private val counted = mutableMapOf<String, Int>()

    /**
     * What the CI wait of [wait] does with the red [verdict] of its poll under [workflow]: [jobs] are the jobs it read
     * of the failed runs, by run id, [reason] the line of its `delivery_failed`. A skip is recorded here.
     */
    suspend fun decide(
        workflow: WorkflowFile,
        wait: Wait.Merge,
        verdict: CiVerdict.Red,
        jobs: Map<Long, ActionsJobs>,
        reason: String,
    ): CiFailedOutcome {
        val reaction = workflow.config.reactions.ciFailed ?: return CiFailedOutcome.Refuse()
        val data = data(wait, reaction)
        if (reaction.action == ReactionAction.NotifyHuman) {
            record(wait, ReactionSkipped, data + (ReactionKeys.REASON to ReactionSkipped.NOTIFY_HUMAN))
            return CiFailedOutcome.Refuse()
        }
        val stored =
            reactionsBefore().getOrElse { error ->
                val text =
                    redactor.redact(
                        "cannot read the ci_failed reactions of the run store: ${error.message ?: error::class.simpleName}",
                    )
                log.log(LogLevel.WARN, REACTIONS, LogOutcome.FAILED, contextOf(wait), reason = text.take(REASON_MAX))
                return CiFailedOutcome.Refuse(UNREAD_NOTE)
            }
        val used = maxOf(countIn(stored, wait.issueId), counted[wait.issueId] ?: 0)
        val retries = reaction.retries
        if (retries != null && used >= retries) {
            record(
                wait,
                ReactionSkipped,
                data + (ReactionKeys.RETRY to used.toString()) + (ReactionKeys.REASON to ReactionSkipped.RETRIES_SPENT),
            )
            return CiFailedOutcome.Refuse(spentNote(used))
        }
        val retry = used + 1
        val lines = CiWait.failedRunLines(verdict.failed) { redactor.redact(it) }
        return CiFailedOutcome.SendBack(
            comment = sendBackComment(wait.numberText, reason, lines, retry, retries),
            findings = findings(wait.numberText, wait.mergedHead.orEmpty(), reason, verdict.failed, jobs) { redactor.redact(it) },
            retry = retry,
            retries = retries,
        )
    }

    /** Sends the issue of [wait] back to its agent as [outcome] says: counts it, logs it, records `reaction_triggered`. */
    suspend fun send(
        workflow: WorkflowFile,
        wait: Wait.Merge,
        issue: Issue,
        outcome: CiFailedOutcome.SendBack,
    ) {
        val reaction = workflow.config.reactions.ciFailed ?: Reaction(ReactionAction.SendToAgent, outcome.retries)
        counted[wait.issueId] = outcome.retry
        log.log(
            LogLevel.WARN,
            REACTIONS,
            LogOutcome.FAILED,
            contextOf(wait),
            mapOf(ReactionKeys.REACTION to ReactionsConfig.CI_FAILED, ReactionKeys.RETRY to outcome.retry),
            "the Actions runs of pull request #${wait.numberText} failed, the issue goes back to its agent",
        )
        val scheduled = sendBack(wait, issue, outcome.findings)
        // bl-224: recorded after the send back, so the event holds the findings only when the retry is scheduled.
        val findings = if (scheduled) mapOf(ReactionKeys.FINDINGS to outcome.findings) else emptyMap()
        record(wait, ReactionTriggered, data(wait, reaction) + (ReactionKeys.RETRY to outcome.retry.toString()) + findings)
    }

    private fun data(
        wait: Wait.Merge,
        reaction: Reaction,
    ): Map<String, String> =
        buildMap {
            put(ReactionKeys.REACTION, ReactionsConfig.CI_FAILED)
            put(ReactionKeys.ACTION, reaction.action.wire)
            put(ReactionKeys.ISSUE, wait.identifier)
            put(ReactionKeys.PULL_REQUEST, wait.numberText)
            reaction.retries?.let { put(ReactionKeys.RETRIES, it.toString()) }
        }

    companion object {
        private const val REACTIONS = "reactions"
        private const val REASON_MAX = 200

        /** The most lines of failed jobs and steps the findings hold. */
        private const val JOB_LINES = 20

        /** The most characters of one line of the findings. */
        private const val LINE_MAX = 200

        /** Conclusions of a job or a step that pass, as for a run (m3-15). */
        private val PASSING = setOf("success", "skipped", "neutral")

        /** The end of the comment when the store could not be read, so the reaction did not fire. */
        const val UNREAD_NOTE: String =
            "The ci_failed reaction did not send the issue back, Loop could not read its count in the run store."

        /** The `ci_failed` reactions of [issueId] among [events], the `reaction_triggered` events of the run store. */
        fun countIn(
            events: List<RecordedEvent>,
            issueId: String,
        ): Int =
            events.count {
                it.kind == ReactionTriggered.name && it.issueId == issueId && it.data[ReactionKeys.REACTION] == ReactionsConfig.CI_FAILED
            }

        /** The end of the comment once the issue was sent back [used] times, as many as `retries`. */
        fun spentNote(used: Int): String =
            "The ci_failed reaction sent the issue back $used ${if (used == 1) "time" else "times"}, its retries are spent."

        /**
         * The warning of a workflow whose `ci_failed` reaction in [reactions] never fires, null when it can: the code
         * kind's merge [ci] is `none`, or null as no `merge` is set, so Loop reads no Actions run.
         */
        fun neverFires(
            reactions: ReactionsConfig,
            ci: CiMode?,
        ): String? {
            if (reactions.ciFailed == null) return null
            val key = "reactions.${ReactionsConfig.CI_FAILED} never fires: kinds.code.delivery.pull_request.merge"
            return when (ci) {
                CiMode.Actions -> null
                CiMode.None -> "$key.ci is none, so Loop reads no Actions run"
                null -> "$key is not set, so Loop waits for no Actions run"
            }
        }

        /** The comment on the issue when the reaction sends it back: the CI wait's reason, the reaction, the failed runs. */
        private fun sendBackComment(
            number: String,
            reason: String,
            runLines: List<String>,
            retry: Int,
            retries: Int?,
        ): String =
            buildString {
                append("Loop did not merge pull request #$number, ci: $reason. ")
                append("The ci_failed reaction sends the issue back to its agent, reaction $retry")
                if (retries != null) append(" of $retries")
                append(". The pull request stays open.")
                if (runLines.isNotEmpty()) append("\n\nFailed runs:\n").append(runLines.joinToString("\n"))
            }

        /**
         * The findings of a red verdict for the agent: the CI wait's [reason] on pull request [number] and its merged
         * [head], the [failed] runs with their URLs, as [CiWait.failedRunLines] gives them, then for each failed run the
         * jobs that did not pass, from [jobs], with the steps that did not pass, or a line saying its jobs were not read.
         * Each line goes through [redact] and is cut at 200 characters, at most 20 job lines. The agent cannot open the
         * run URLs, so the job and step names are what it acts on.
         */
        fun findings(
            number: String,
            head: String,
            reason: String,
            failed: List<ActionsRun>,
            jobs: Map<Long, ActionsJobs>,
            redact: (String) -> String,
        ): String {
            val jobLines =
                failed.flatMap { run ->
                    val read = jobs[run.id] ?: return@flatMap listOf("- ${run.name}, its jobs were not read")
                    val bad = read.jobs.filter { it.conclusion?.lowercase() !in PASSING }
                    if (bad.isEmpty()) return@flatMap listOf("- ${run.name}, no failed job listed")
                    bad.map { job ->
                        val steps = job.stepList.filter { it.conclusion != null && it.conclusion?.lowercase() !in PASSING }
                        val named =
                            if (steps.isEmpty()) {
                                "no failed step named"
                            } else {
                                "failed step " + steps.joinToString(", ") { "${it.name} (${it.conclusion})" }
                            }
                        "- ${run.name}, job ${job.name} (${job.conclusion ?: "no conclusion"}), $named"
                    }
                }
            val shown = jobLines.take(JOB_LINES).map { cut(redact(it)) }
            val more = if (jobLines.size > JOB_LINES) listOf("- and ${jobLines.size - JOB_LINES} more") else emptyList()
            return buildString {
                append(redact("The Actions runs of pull request #$number failed on its merged head $head: $reason."))
                append("\n\nFailed runs:\n")
                append(CiWait.failedRunLines(failed, redact).joinToString("\n"))
                append("\n\nFailed jobs and steps:\n")
                append((shown + more).joinToString("\n"))
                append(
                    "\n\nYou cannot open the run URLs or read the CI logs. Find the cause from the job and step names, " +
                        "reproduce it in the workspace, fix it and commit.",
                )
            }
        }

        private fun cut(line: String): String = if (line.length > LINE_MAX) line.take(LINE_MAX - ELLIPSIS.length) + ELLIPSIS else line

        private const val ELLIPSIS = "..."
    }
}
