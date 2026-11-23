package loop.core.delivery

import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.core.events.CiFinished
import loop.core.workflow.CiNoRun
import loop.core.workflow.MergeConfig

/**
 * What the Actions runs of a merged head say (m3-15), from [CiWait.verdict]. [kept] holds one run per workflow and
 * event, the one with the highest run number.
 */
sealed interface CiVerdict {
    val kept: List<ActionsRun>

    /** Every kept run completed with `success`, `skipped` or `neutral`. */
    data class Green(
        override val kept: List<ActionsRun>,
    ) : CiVerdict

    /** A kept run completed with any other conclusion, or none: [failed]. */
    data class Red(
        override val kept: List<ActionsRun>,
        val failed: List<ActionsRun>,
    ) : CiVerdict

    /** Every failed kept run was refused by GitHub before any step ran, [notStarted] (m4-07): the code was not judged. */
    data class NotStarted(
        override val kept: List<ActionsRun>,
        val notStarted: List<ActionsRun>,
    ) : CiVerdict

    /** A kept run is not completed yet, [pending], or the listing holds more runs than one page, [overPage] of [total]. */
    data class Pending(
        override val kept: List<ActionsRun>,
        val pending: List<ActionsRun>,
        val overPage: Boolean,
        val total: Long,
    ) : CiVerdict

    /** The merged head has no run at all. */
    data object NoRun : CiVerdict {
        override val kept: List<ActionsRun> = emptyList()
    }
}

/** What the CI wait does after an answered poll (m3-15). */
sealed interface CiDecision {
    /** CI allows the merge, with its `ci_finished` [outcome]. */
    data class Merge(
        val outcome: String,
    ) : CiDecision

    /** CI refuses the merge, with its `ci_finished` [outcome] and the [reason] in a line. */
    data class Fail(
        val outcome: String,
        val reason: String,
    ) : CiDecision

    /** CI has not decided yet: the wait goes on. */
    data object Wait : CiDecision
}

/**
 * The CI wait before a merge (m3-15): the verdict on the Actions runs of the merged head, a pure function, what the wait
 * decides after an answered poll, and the texts it writes.
 */
object CiWait {
    const val GREEN = CiFinished.GREEN
    const val RED = CiFinished.RED
    const val NO_RUN_PASS = CiFinished.NO_RUN_PASS
    const val NO_RUN_FAIL = CiFinished.NO_RUN_FAIL
    const val TIMEOUT = CiFinished.TIMEOUT
    const val NOT_STARTED = CiFinished.NOT_STARTED

    /** The `step` of a `delivery_failed` of the CI wait. */
    const val STEP = "ci"

    /** The permission the runs listing needs, named by a 403 or a 404 on it. */
    const val PERMISSION = "Actions: read"

    /** The least time between two polls of one wait, whatever the tick. */
    const val POLL_FLOOR_MS: Long = 30_000

    /** The most runs one listing page holds, the `per_page` Loop asks for. */
    const val PAGE: Int = 100

    /** Conclusions of a completed run that pass: green, neutral, or a run its workflow skipped. */
    private val PASSING = setOf("success", "skipped", "neutral")

    private const val COMPLETED = "completed"

    /** The most failed runs whose jobs one poll reads (m4-07). */
    const val JOBS_READ: Int = 10

    /** The most failed runs a comment names. */
    private const val SHOWN_RUNS = 10

    /** The most characters of one run line in a comment. */
    private const val LINE_MAX = 200

    /**
     * The runs of [listing] whose jobs the verdict needs (m4-07): the kept runs completed with a conclusion that does
     * not pass, the runs [verdict] would call red, in the order of the listing, at most 10 so a poll sends at most 10
     * more requests. None when the listing holds more runs than one page, which is pending whatever its jobs say.
     */
    fun jobsToRead(listing: ActionsRuns): List<ActionsRun> =
        if (listing.totalCount > listing.runs.size) emptyList() else failedRuns(keptRuns(listing)).take(JOBS_READ)

    /**
     * The verdict on [listing], with [jobs] the jobs read for some of its runs, by run id: runs grouped by workflow and
     * event, the highest run number kept, since a rerun raises the attempt of the same run and the listing shows its
     * latest attempt. A total above the runs listed is [CiVerdict.Pending], never judged on a part. No run is
     * [CiVerdict.NoRun]. A kept run completed with a conclusion other than `success`, `skipped` or `neutral`, an unknown
     * one or none included, has failed. Since m4-07 a failed run whose jobs were read, all on their page, at least one,
     * every one with zero steps, is not started: GitHub refused it (billing or limits) and judged no code. When every
     * failed run is not started the verdict is [CiVerdict.NotStarted], else [CiVerdict.Red] naming the other failed
     * runs, a failed run whose jobs were not read included, fail closed. Both come even while others run, since the head
     * can no longer turn green. A kept run not completed is [CiVerdict.Pending]. Otherwise [CiVerdict.Green].
     */
    fun verdict(
        listing: ActionsRuns,
        jobs: Map<Long, ActionsJobs> = emptyMap(),
    ): CiVerdict {
        val kept = keptRuns(listing)
        if (listing.totalCount > listing.runs.size) return CiVerdict.Pending(kept, emptyList(), overPage = true, listing.totalCount)
        if (kept.isEmpty()) return CiVerdict.NoRun
        val failed = failedRuns(kept)
        if (failed.isNotEmpty()) {
            val red = failed.filterNot { run -> jobs[run.id]?.let(::refused) == true }
            return if (red.isEmpty()) CiVerdict.NotStarted(kept, failed) else CiVerdict.Red(kept, red)
        }
        val pending = kept.filterNot { it.status.equals(COMPLETED, ignoreCase = true) }
        if (pending.isNotEmpty()) return CiVerdict.Pending(kept, pending, overPage = false, listing.totalCount)
        return CiVerdict.Green(kept)
    }

    /** One run per workflow and event of [listing], the highest run number, in the order of the listing. */
    private fun keptRuns(listing: ActionsRuns): List<ActionsRun> =
        listing.runs
            .groupBy { it.workflowId to it.event }
            .values
            .map { group -> group.maxBy { it.runNumber } }
            .sortedBy { listing.runs.indexOf(it) }

    /** The runs of [kept] completed with a conclusion that does not pass. */
    private fun failedRuns(kept: List<ActionsRun>): List<ActionsRun> =
        kept.filter { it.status.equals(COMPLETED, ignoreCase = true) && it.conclusion?.lowercase() !in PASSING }

    /**
     * Whether [jobs] are those of a run GitHub refused to start (bl-98): every job on the page, at least one, and none
     * with a step. A run with no job at all, such as a workflow file GitHub cannot read, was judged and stays red.
     */
    private fun refused(jobs: ActionsJobs): Boolean =
        jobs.jobs.isNotEmpty() && jobs.totalCount <= jobs.jobs.size && jobs.jobs.all { it.steps == 0 }

    /**
     * What the wait does with [verdict] after an answered poll, [waitedMs] the time counted so far: the verdict first,
     * green merges, red fails and not started fails with its own outcome (m4-07), never read as no run; then the timeout, only while runs are pending; then the no run rule, only with zero
     * runs once the grace is over.
     */
    fun decide(
        verdict: CiVerdict,
        waitedMs: Long,
        merge: MergeConfig,
    ): CiDecision =
        when (verdict) {
            is CiVerdict.Green -> CiDecision.Merge(GREEN)
            is CiVerdict.Red -> CiDecision.Fail(RED, "Actions runs did not pass: ${runNames(verdict.failed)}")
            is CiVerdict.NotStarted ->
                CiDecision.Fail(
                    NOT_STARTED,
                    "GitHub did not start the Actions runs, for billing or limits, so the code was not judged: " +
                        runNames(verdict.notStarted),
                )
            is CiVerdict.Pending ->
                if (waitedMs >= merge.ciTimeoutMs) {
                    CiDecision.Fail(TIMEOUT, "Actions runs still pending after ${duration(merge.ciTimeoutMs)}: ${pendingText(verdict)}")
                } else {
                    CiDecision.Wait
                }
            CiVerdict.NoRun ->
                when {
                    waitedMs < merge.ciNoRunGraceMs -> CiDecision.Wait
                    merge.ciNoRun == CiNoRun.Pass -> CiDecision.Merge(NO_RUN_PASS)
                    else -> CiDecision.Fail(NO_RUN_FAIL, "no Actions run after ${duration(merge.ciNoRunGraceMs)}, and ci_no_run is fail")
                }
        }

    /** What is pending in [verdict], in a line. */
    fun pendingText(verdict: CiVerdict.Pending): String =
        if (verdict.overPage) {
            "${verdict.total} runs, more than one page of $PAGE"
        } else {
            "${verdict.pending.size} of the ${verdict.kept.size} kept runs not completed, " +
                AutoMerge.shown(verdict.pending.map { "${it.name} (${it.status})" })
        }

    /**
     * One line per run of [failed] for a comment, `- <name> (<conclusion>): <url>`, each passed through [redact] and
     * cut at 200 characters, at most 10, then `- and <n> more` when there are more.
     */
    fun failedRunLines(
        failed: List<ActionsRun>,
        redact: (String) -> String,
    ): List<String> {
        val lines =
            failed.take(SHOWN_RUNS).map { run ->
                val line = redact("- ${run.name} (${run.conclusion ?: "no conclusion"}): ${run.url}")
                if (line.length > LINE_MAX) line.take(LINE_MAX - ELLIPSIS.length) + ELLIPSIS else line
            }
        return if (failed.size > SHOWN_RUNS) lines + "- and ${failed.size - SHOWN_RUNS} more" else lines
    }

    /**
     * The comment on the issue after the CI wait refused the merge of pull request [number]: the [reason], the failed
     * runs as [failedRunLines] gives them, and what a human does next. Runs GitHub did not start, [notStarted] (m4-07),
     * are named as such, and the next step is the Actions billing and limits, never the code.
     */
    fun failedComment(
        number: String,
        reason: String,
        runLines: List<String>,
        notStarted: Boolean = false,
    ): String =
        buildString {
            append(
                "Loop did not merge pull request #$number, ci: $reason. The pull request stays open and the result waits for a new approval.",
            )
            if (runLines.isNotEmpty()) {
                append(if (notStarted) "\n\nRuns not started:\n" else "\n\nFailed runs:\n")
                append(runLines.joinToString("\n"))
            }
            if (notStarted) {
                append(
                    "\n\nCheck the Actions billing and limits of the repository, rerun the runs on GitHub, then approve again with loop approve.",
                )
            } else {
                append("\n\nRerun the runs on GitHub or fix the branch, then approve again with loop approve.")
            }
        }

    /** The ids of [runs], comma separated, for `ci_finished`. */
    fun runIds(runs: List<ActionsRun>): String = runs.joinToString(",") { it.id.toString() }

    private fun runNames(runs: List<ActionsRun>): String = AutoMerge.shown(runs.map { "${it.name} (${it.conclusion ?: "no conclusion"})" })

    /** [ms] in whole minutes when it is some, else in seconds. */
    private fun duration(ms: Long): String =
        if (ms >= MINUTE_MS && ms % MINUTE_MS == 0L) "${ms / MINUTE_MS} minutes" else "${ms / SECOND_MS} seconds"

    private const val ELLIPSIS = "..."
    private const val MINUTE_MS = 60_000L
    private const val SECOND_MS = 1_000L
}
