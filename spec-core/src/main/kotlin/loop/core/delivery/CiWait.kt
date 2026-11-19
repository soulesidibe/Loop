package loop.core.delivery

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

    /** The most failed runs a comment names. */
    private const val SHOWN_RUNS = 10

    /** The most characters of one run line in a comment. */
    private const val LINE_MAX = 200

    /**
     * The verdict on [listing]: runs grouped by workflow and event, the highest run number kept, since a rerun raises
     * the attempt of the same run and the listing shows its latest attempt. A total above the runs listed is
     * [CiVerdict.Pending], never judged on a part. No run is [CiVerdict.NoRun]. A kept run completed with a conclusion
     * other than `success`, `skipped` or `neutral`, an unknown one or none included, is [CiVerdict.Red], fail closed,
     * even while others run, since the head can no longer turn green. A kept run not completed is
     * [CiVerdict.Pending]. Otherwise [CiVerdict.Green].
     */
    fun verdict(listing: ActionsRuns): CiVerdict {
        val kept =
            listing.runs
                .groupBy { it.workflowId to it.event }
                .values
                .map { group -> group.maxBy { it.runNumber } }
                .sortedBy { listing.runs.indexOf(it) }
        if (listing.totalCount > listing.runs.size) return CiVerdict.Pending(kept, emptyList(), overPage = true, listing.totalCount)
        if (kept.isEmpty()) return CiVerdict.NoRun
        val completed = kept.filter { it.status.equals(COMPLETED, ignoreCase = true) }
        val failed = completed.filter { it.conclusion?.lowercase() !in PASSING }
        if (failed.isNotEmpty()) return CiVerdict.Red(kept, failed)
        val pending = kept - completed.toSet()
        if (pending.isNotEmpty()) return CiVerdict.Pending(kept, pending, overPage = false, listing.totalCount)
        return CiVerdict.Green(kept)
    }

    /**
     * What the wait does with [verdict] after an answered poll, [waitedMs] the time counted so far: the verdict first,
     * green merges and red fails; then the timeout, only while runs are pending; then the no run rule, only with zero
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
     * runs as [failedRunLines] gives them, and what a human does next.
     */
    fun failedComment(
        number: String,
        reason: String,
        runLines: List<String>,
    ): String =
        buildString {
            append(
                "Loop did not merge pull request #$number, ci: $reason. The pull request stays open and the result waits for a new approval.",
            )
            if (runLines.isNotEmpty()) {
                append("\n\nFailed runs:\n")
                append(runLines.joinToString("\n"))
            }
            append("\n\nRerun the runs on GitHub or fix the branch, then approve again with loop approve.")
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
