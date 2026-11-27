package loop.core.merge

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.Mergeability
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RiskReview
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.api.RiskReviewer
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.delivery.AutoMerge
import loop.core.delivery.AutoMergeCondition
import loop.core.delivery.AutoMergeFacts
import loop.core.delivery.CiFacts
import loop.core.delivery.CiWait
import loop.core.delivery.DiffCut
import loop.core.delivery.FailedCondition
import loop.core.delivery.FittedPrompt
import loop.core.delivery.MergeRisk
import loop.core.delivery.RiskAssessment
import loop.core.delivery.RiskBand
import loop.core.events.DiffBaseUnpinned
import loop.core.events.EventDefinition
import loop.core.events.MergeRiskAssessed
import loop.core.events.Verdict
import loop.core.verify.CloneScope
import loop.core.verify.DiffEntry
import loop.core.waiting.Wait
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.MergeConfig
import loop.core.workflow.TestLanguage
import loop.core.workflow.TokenCounters
import loop.core.workflow.WorkflowFile
import loop.core.workflow.promptLimitFor
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** What the auto merge gate decided (m2-28): merge, or fall back to the `human` policy with the reasons. */
internal sealed interface GateOutcome {
    /** Merge: every condition holds and the [risk] assessed is low with no dimension at its worst. */
    data class Merge(
        val risk: RiskAssessment,
    ) : GateOutcome

    /**
     * Fall back: the [failures] of the hard conditions, the [risk] when it was assessed, else why it was not,
     * [riskSkipped], and the [reason] in a line for the event and the log. [stopped] when the workflow no longer allows
     * an auto merge at all, said first in the comment.
     */
    data class FallBack(
        val failures: List<FailedCondition>,
        val risk: RiskAssessment?,
        val riskSkipped: String?,
        val reason: String,
        val stopped: String? = null,
    ) : GateOutcome
}

/**
 * The gate of an auto merge (m2-28, story 100), run in the merge phase right before the merge of a wait the `auto`
 * policy approved. The hard conditions are read first, each a veto. The read only reviewer then runs even when one
 * failed (bl-96), so the fallback comment carries a band for the operator who reviews it; its spend is written to the
 * ledger as a row so the spend budgets count it. The score and band are computed in code. The CI condition is read
 * last, after the review (send back 5 of PR #75), and only when every hard condition held. Every failure of the
 * reviewer, a timeout, a crash, an answer that is not valid JSON or a missing dimension, falls back: the gate fails
 * closed. It reads the code kind's delivery (m4-18): only a code issue reaches a merge wait, since no other kind may
 * declare `delivery.pull_request` and the approval wait pins the kind its issue was dispatched under.
 */
internal class AutoMergeGate(
    private val git: MergeGit,
    private val attemptEvents: suspend (AttemptId) -> Result<List<RecordedEvent>>,
    private val reviewerFor: (WorkflowFile) -> RiskReviewer?,
    private val attemptLedger: AttemptLedger,
    /**
     * Why no reviewer may spend now for an issue under a workflow, null when it may: a spend budget already used up, the
     * attempts running beside the gate included, or a ledger that cannot be read for it. The orchestrator reads the spend.
     */
    private val budgetPassed: suspend (WorkflowFile, String) -> String?,
    /** What the ledger records of a workflow's setup, for the reviewer's row. */
    private val setupOf: (WorkflowFile) -> LedgerSetup,
    /** The folder of the project's attempt files, where the submitted results are saved (m2-15). */
    private val attemptsFolder: Path,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val io: CoroutineContext,
    private val newId: () -> String,
    private val projectId: ProjectId,
    private val runId: RunId,
) {
    /**
     * Decides the auto merge of [wait] on [issue]: [mergedHead] in [workspace], checked against the base the wait
     * stores, the provider showing [shownHead]. Records `merge_risk_assessed` through [record] once the risk is assessed,
     * and logs through [warn] what goes wrong without changing the decision.
     */
    suspend fun evaluate(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        executor: DeliveryExecutor,
        workspace: Path,
        number: Long,
        mergedHead: String,
        shownHead: String?,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
        warn: suspend (String) -> Unit,
    ): GateOutcome {
        notAllowedBy(workflow)?.let { stopped -> return fallBack(emptyList(), null, stopped, stopped).copy(stopped = stopped) }
        val kind = checkNotNull(workflow.config.kinds?.code)
        val delivery = checkNotNull(kind.delivery)
        val testGlobs = (kind.guards?.languages ?: TestLanguage.BUILT_IN).flatMap { it.files }
        val base = wait.baseSha.orEmpty()
        val diff =
            step { git.diffStat(workspace, base, mergedHead) }.getOrElse { error ->
                val reason = "cannot read the diff against the base: ${error.message}"
                return fallBack(emptyList(), null, reason, reason)
            }
        val events = attemptEvents(wait.attemptId ?: AttemptId(NO_ATTEMPT))
        val verifiedAt = wait.verifiedFingerprint ?: wait.fingerprint
        val list = events.getOrNull().orEmpty()
        val facts =
            AutoMergeFacts(
                verifiedVerdict =
                    list.any {
                        it.kind == Verdict.name &&
                            it.data[Verdict.VERDICT] == VERIFIED &&
                            it.data[Verdict.MERGE_CHECK] != "true" &&
                            it.data[Verdict.FINGERPRINT] == verifiedAt
                    },
                hiddenChecksPassed = wait.checks.count { it.startsWith(HIDDEN_CHECK_LINE) },
                holdouts = kind.holdout.size,
                fingerprint = wait.fingerprint.orEmpty(),
                mergedHead = mergedHead,
                shownHead = shownHead,
                pullRequestNumber = number,
                denyGuards = (kind.checks + kind.holdout).filterIsInstance<CheckConfig.DiffPolicy>().flatMap { it.deny }.distinct(),
                diff = diff,
                autoMerge = delivery.autoMerge,
                unpinned = list.any { it.kind == DiffBaseUnpinned.name },
                testGlobs = testGlobs,
            )
        var failures = AutoMerge.failures(facts)
        events.exceptionOrNull()?.let { error ->
            // Fail closed: what the events would prove is not proven.
            val reason = "cannot read the events of the attempt: ${error.message}"
            val unread = setOf(AutoMergeCondition.VERIFIED, AutoMergeCondition.DIFF_BASE_PINNED)
            failures =
                (failures.filter { it.condition !in unread } + unread.map { FailedCondition(it, reason) })
                    .sortedBy { it.condition.ordinal }
        }
        // bl-96: the review runs even when a hard condition failed, so the operator who reviews the fallback gets a band.
        val risk = assessRisk(wait, issue, workflow, workspace, base, mergedHead, diff, testGlobs, record, warn)
        if (failures.isNotEmpty()) {
            val reasons = failures.map { "${it.condition.wire}: ${it.reason}" } + listOfNotNull(risk.fallBack)
            return fallBack(failures, risk.assessment, risk.skipped, reasons.joinToString("; "))
        }
        // Send back 5 of PR #75: CI is read after the review, so the checks of the head just pushed had time to register.
        val ci = AutoMerge.ciFailure(ciFacts(executor, number, mergedHead, delivery.pullRequest?.merge))
        if (ci == null && risk.fallBack == null && risk.assessment != null) return GateOutcome.Merge(risk.assessment)
        val reasons = listOfNotNull(ci?.let { "${it.condition.wire}: ${it.reason}" }, risk.fallBack)
        return fallBack(listOfNotNull(ci), risk.assessment, risk.skipped, reasons.joinToString("; "))
    }

    /**
     * What the merge risk part of the gate found: the [assessment] when it was made, else why not, [skipped], and the
     * reason it calls for a fallback, [fallBack], null when the risk allows the auto merge.
     */
    private data class RiskOutcome(
        val assessment: RiskAssessment?,
        val skipped: String?,
        val fallBack: String?,
    )

    /**
     * What the CI condition reads of pull request [number] and its [mergedHead], read now: its mergeability and, under
     * `ci: actions` only, the Actions runs of the merged head (m3-15) and the jobs of its failed runs (m4-07). Under `ci: none` no run is read, of the merged
     * head or the base tip, and no workflow file (m4-05, bl-146).
     */
    private suspend fun ciFacts(
        executor: DeliveryExecutor,
        number: Long,
        mergedHead: String,
        merge: MergeConfig?,
    ): CiFacts {
        val ci = merge?.ci ?: CiMode.None
        val headRuns = if (ci == CiMode.Actions) provider { executor.actionsRuns(mergedHead) } else null
        val failed = headRuns?.getOrNull()?.let(CiWait::jobsToRead).orEmpty()
        return CiFacts(
            pullRequestNumber = number,
            mergedHead = mergedHead,
            mergeability = mergeability(executor, number),
            headRuns = headRuns,
            headJobs = failed.associate { run -> run.id to provider { executor.actionsJobs(run.id) } },
            ci = ci,
            noRun = merge?.ciNoRun ?: CiNoRun.Fail,
        )
    }

    /** A provider read on [io], an exception it throws turned into its failure. */
    private suspend fun <T> provider(read: suspend () -> Result<T>): Result<T> =
        try {
            withContext(io) { read() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * The reviewer's session and the assessment, whether the hard conditions held or not (bl-96); CI is read after it.
     * The code facts read the [testGlobs] of the guards' languages.
     */
    private suspend fun assessRisk(
        wait: Wait,
        issue: Issue,
        workflow: WorkflowFile,
        workspace: Path,
        base: String,
        mergedHead: String,
        diff: List<DiffEntry>,
        testGlobs: List<String>,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
        warn: suspend (String) -> Unit,
    ): RiskOutcome {
        val kind = checkNotNull(workflow.config.kinds?.code)
        val risk = kind.delivery.risk
        val model = risk.reviewer ?: return riskFailed("no risk reviewer is configured")
        // m4-06: the host picks the reviewer apart from the runner, Claude Code's for every runner.kind, so no reviewer
        // means its command cannot run here: the reviewer setup, not the result, keeps the merge waiting.
        val reviewer =
            reviewerFor(workflow)
                ?: return riskFailed(
                    "this Loop cannot run its merge risk reviewer, so the reviewer setup, not the result, is why the merge waits",
                )
        budgetPassed(workflow, wait.issueId)?.let { return riskFailed(it) }
        val facts = MergeRisk.facts(diff, risk, testGlobs, kind.delivery.autoMerge.maxDiffLines, kind.delivery.autoMerge.docPaths)
        val text =
            step { git.diffText(workspace, base, mergedHead, DIFF_MAX_CHARS + 1) }.getOrElse { error ->
                return riskFailed("cannot read the diff for the reviewer: ${error.message}")
            }
        // m3-13: the prompt, redacted as the reviewer reads it, fits prompt_limit of the reviewer model's window, the diff
        // cut to fit; a prompt that cannot fit even with an empty diff falls back like a reviewer that fails.
        val result = submittedResult(wait.attemptId)
        val fence = UUID.randomUUID().toString().replace("-", "")
        val counter = TokenCounters.forModel(model)
        val limit = workflow.config.context.promptLimitFor(model)
        val render = { diff: String, cut: DiffCut? ->
            redactor.redact(
                MergeRisk.prompt(wait.identifier, issue.title, issue.description, result, facts, base, diff, cut, fence, mergedHead),
            )
        }
        val diff = text.take(DIFF_MAX_CHARS)
        val readCut = text.length > DIFF_MAX_CHARS
        val fitted =
            if (limit == null) {
                val cut = if (readCut) DiffCut(0, atLeast = true, MergeRisk.leftOut(facts.files, diff, diff.length, readCut)) else null
                render(diff, cut).let { FittedPrompt(it, counter.count(it), cut) }
            } else {
                MergeRisk.fit(diff, readCut, facts.files, limit.tokens, counter, render) ?: run {
                    val empty = counter.count(render("", DiffCut(diff.length, readCut, facts.files.map { it.path })))
                    return riskFailed(
                        "the reviewer's prompt is $empty tokens even with an empty diff, over its prompt limit of " +
                            "${limit.tokens} tokens (context.prompt_limit ${workflow.config.context.promptLimit} of a " +
                            "${limit.window} token window for $model)",
                    )
                }
            }
        val started = clock.now()
        // bl-254: the reviewer's runner may run its own git in the workspace, so it gets the project's base and the mode.
        val request =
            RiskReviewRequest(
                workspace,
                model,
                fitted.prompt,
                risk.timeoutMs,
                baseRef = base.takeIf { it.isNotEmpty() },
                cloneWorkspace = CloneScope.of(workflow)?.covers(workspace) == true,
            )
        val answer =
            try {
                withTimeoutOrNull(risk.timeoutMs + BACKSTOP_MS) { withContext(io) { reviewer.review(request) } }
                    ?: Result.failure(RiskReviewError.Timeout("the reviewer did not answer within ${risk.timeoutMs} ms"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(RiskReviewError.Crashed(e.message ?: e::class.simpleName.orEmpty(), e))
            }
        writeSpend(wait, issue, workflow, model, started, answer)?.let { warn(it) }
        val review =
            answer.getOrElse { error ->
                val why =
                    when (error) {
                        is RiskReviewError.Timeout -> "the reviewer timed out: ${error.message}"
                        else -> "the reviewer failed: ${error.message}"
                    }
                return riskFailed(why)
            }
        val verdicts = MergeRisk.parse(review.text).getOrElse { return riskFailed(it.message ?: "the reviewer's answer is invalid") }
        // bl-137: the reviewer may not have seen the riskiest files of a cut diff, so its band is at least medium.
        val assessment = MergeRisk.assess(verdicts, facts.floors, fitted.cut?.let { MergeRisk.cutFloor(it, facts.files.size) })
        record(
            MergeRiskAssessed,
            assessment.eventData() +
                mapOf(
                    MergeRiskAssessed.REVIEWER to model,
                    MergeRiskAssessed.PROMPT_TOKENS to fitted.tokens.toString(),
                    MergeRiskAssessed.DIFF_CUT to (fitted.cut != null).toString(),
                    MergeRiskAssessed.FILES_LEFT_OUT to (fitted.cut?.filesLeftOut?.size ?: 0).toString(),
                ),
        )
        if (assessment.allowsAutoMerge) return RiskOutcome(assessment, null, null)
        val band =
            if (assessment.worst.isNotEmpty()) {
                "the merge risk band is ${assessment.band.wire} with ${assessment.worst.joinToString(
                    ", ",
                ) { it.wire }} at its worst verdict"
            } else {
                "the merge risk band is ${assessment.band.wire}, an auto merge needs low"
            }
        val reason = band + assessment.bandFloor?.let { ", and at least ${RiskBand.MEDIUM.wire} since $it" }.orEmpty()
        return RiskOutcome(assessment, null, reason)
    }

    private fun riskFailed(reason: String): RiskOutcome = RiskOutcome(null, redactor.redact(reason).lines().first(), reason)

    private fun fallBack(
        failures: List<FailedCondition>,
        risk: RiskAssessment?,
        skipped: String?,
        reason: String,
    ): GateOutcome.FallBack =
        GateOutcome.FallBack(
            failures,
            risk,
            skipped,
            redactor
                .redact(reason)
                .lines()
                .first()
                .take(REASON_MAX),
        )

    /**
     * What the provider says of merging pull request [number]: read again while it has not computed `mergeable` yet, at
     * most [MERGEABLE_READS] reads [MERGEABLE_READ_DELAY_MS] apart, the last answer kept.
     */
    private suspend fun mergeability(
        executor: DeliveryExecutor,
        number: Long,
    ): Result<Mergeability> {
        var last: Result<Mergeability> = Result.failure(IllegalStateException("not read"))
        repeat(MERGEABLE_READS) { read ->
            if (read > 0) delay(MERGEABLE_READ_DELAY_MS)
            last = provider { executor.mergeability(number) }
            if (last.getOrNull()?.mergeable != null || last.isFailure) return last
        }
        return last
    }

    /**
     * Writes the reviewer's spend as a ledger row of its own (m2-28), reason [REVIEW_REASON], so the per issue and per
     * day spend budgets read it like an attempt's. A write that fails changes nothing else: it returns why, redacted, for
     * the log (bl-96), and null when the row was written.
     */
    private suspend fun writeSpend(
        wait: Wait,
        issue: Issue,
        workflow: WorkflowFile,
        model: String,
        started: kotlin.time.Instant,
        answer: Result<RiskReview>,
    ): String? {
        val review = answer.getOrNull()
        val row =
            LedgerAttempt(
                projectId = projectId,
                runId = runId,
                issueId = wait.issueId,
                identifier = wait.identifier,
                attemptId = AttemptId(newId()),
                setup =
                    setupOf(workflow).copy(
                        tracker = workflow.trackers.entryOf(wait.issueId).id,
                        runner = REVIEWER_RUNNER,
                        model = model,
                    ),
                startedAt = started,
                finishedAt = clock.now(),
                outcome = if (review != null) "succeeded" else "failed",
                reason = REVIEW_REASON,
                turns = 1,
                issueState = issue.state,
                tokens = review?.tokens,
                costUsd = review?.costUsd,
                checks = null,
                guards = null,
                verdict = null,
            )
        val failure =
            try {
                attemptLedger.recordAttempt(row).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } ?: return null
        // The spend is lost to the budgets, the gate's decision is not changed by it.
        val why = (failure.message ?: failure::class.simpleName).orEmpty().lines().first()
        return redactor.redact("the reviewer's spend was not written to the ledger, so the spend budgets miss it: $why").take(REASON_MAX)
    }

    /** The latest result the attempt submitted (m2-15), as saved beside its evidence; null when it saved none. */
    private suspend fun submittedResult(attemptId: AttemptId?): String? {
        val attempt = attemptId?.value ?: return null
        return step {
            val folder = attemptsFolder
            val prefix = "$attempt$RESULT_INFIX"
            Files.list(folder).use { files ->
                files
                    .toList()
                    .filter { it.fileName.toString().startsWith(prefix) && it.fileName.toString().endsWith(RESULT_SUFFIX) }
                    .maxByOrNull {
                        it.fileName
                            .toString()
                            .removePrefix(prefix)
                            .removeSuffix(RESULT_SUFFIX)
                            .toIntOrNull() ?: 0
                    }?.let { Files.readString(it).take(RESULT_MAX_CHARS) }
            }
        }.getOrNull()
    }

    private suspend fun <T> step(block: () -> T): Result<T> =
        try {
            Result.success(withContext(io) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /**
         * Why [workflow] does not allow an auto merge, null when it does: `policy: auto` on the `code` kind and
         * `pull_request.merge`. Read before the gate, and again after it from the effective workflow, since a reload
         * during the review must stop the merge (round 2 of the PR #75 review). The opt in `auto_merge.enabled` is not
         * read here: the loader rejects `policy: auto` without it, so a loaded workflow under auto always has it, and
         * a change that drops it is a rejected reload, see [rejectedReload].
         */
        fun notAllowedBy(workflow: WorkflowFile): String? {
            val delivery =
                workflow.config.kinds
                    ?.code
                    ?.delivery
            return when {
                delivery == null -> "$NOT_ALLOWED, it has no kinds.code.delivery"
                delivery.policy != DeliveryPolicy.Auto -> "$NOT_ALLOWED, kinds.code.delivery.policy is ${delivery.policy.wire}"
                delivery.pullRequest?.merge == null -> "$NOT_ALLOWED, kinds.code.delivery.pull_request.merge is gone"
                else -> null
            }
        }

        /**
         * Why an auto merge stops when the workflow file holds a change whose reload was rejected with [code] and
         * [message], during the review or before it (round 3 of the PR #75 review): the file on disk is not the
         * effective workflow, so the operator's current intent is unknown, and dropping the opt in is such a change.
         */
        fun rejectedReload(
            code: String,
            message: String,
        ): String = "$NOT_ALLOWED, its last change was rejected, $code: $message"

        private const val NOT_ALLOWED = "the workflow no longer allows an auto merge"
        const val REVIEWER_RUNNER: String = RiskReviewRow.RUNNER
        const val REVIEW_REASON: String = RiskReviewRow.REASON
        private const val VERIFIED = "verified"
        private const val HIDDEN_CHECK_LINE = "- hidden check "
        private const val NO_ATTEMPT = "none"
        private const val RESULT_INFIX = ".result-"
        private const val RESULT_SUFFIX = ".json"
        private const val RESULT_MAX_CHARS = 20_000
        const val DIFF_MAX_CHARS = 100_000
        private const val BACKSTOP_MS = 30_000L
        private const val REASON_MAX = 500
        const val MERGEABLE_READS = 5
        const val MERGEABLE_READ_DELAY_MS = 2_000L
    }
}

/** The ledger row of a merge risk review (m2-28): its runner and reason, public for `loop report` (m4-38). */
object RiskReviewRow {
    const val RUNNER: String = "risk_reviewer"
    const val REASON: String = "merge_risk_review"
}
