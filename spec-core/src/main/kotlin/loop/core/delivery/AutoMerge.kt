package loop.core.delivery

import loop.api.ActionsRuns
import loop.api.Mergeability
import loop.core.events.IssueCommented
import loop.core.verify.DiffEntry
import loop.core.verify.ProtectedPaths
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.DenyGuard

/**
 * One hard condition of an auto merge (m2-28, story 100), by its [wire] name: each one is a veto, and any one failing
 * falls back to the `human` policy.
 */
enum class AutoMergeCondition(
    val wire: String,
) {
    /** The verdict is `verified`, every holdout passed. */
    VERIFIED("verified"),

    /** The merge check passed on the current base, and the merged head is the delivered fingerprint and the pull request's head. */
    MERGE_CHECK("merge_check"),

    /**
     * CI is green and the provider reports the pull request mergeable, green following the kind's `ci` (m4-05): under
     * `ci: actions` GitHub's `mergeable` with the state `clean` and every Actions run of the merged head completed and
     * passed; under `ci: none` the merge check passed and GitHub's `mergeable` with a state that is not blocked by
     * anything but CI. Read after the merge risk review, see [AutoMerge.ciFailure].
     */
    CI("ci"),

    /** No test was lost: the merge check's `diff_policy` ran every deny guard and they found nothing. */
    NO_LOST_TESTS("no_lost_tests"),

    /** The diff touches none of `auto_merge.protected_paths`. */
    PROTECTED_PATHS("protected_paths"),

    /** The diff changes at most `auto_merge.max_diff_lines` lines. */
    DIFF_SIZE("diff_size"),

    /** No verification of the delivered attempt judged `diff_policy` against `guards.base` (`diff_base_unpinned`, bl-32). */
    DIFF_BASE_PINNED("diff_base_pinned"),
}

/** A [condition] that failed, with its [reason] in a line. */
data class FailedCondition(
    val condition: AutoMergeCondition,
    val reason: String,
)

/**
 * What the auto merge conditions read (m2-28), gathered by the auto merge gate right before the merge: the attempt's
 * [verifiedVerdict], the [hiddenChecksPassed] of the wait against the kind's [holdouts], the delivered [fingerprint],
 * the [mergedHead] and the head the provider shows, [shownHead], the [denyGuards] of the kind's `diff_policy`, the
 * [diff] against the checked base, the [autoMerge] config and whether the attempt recorded `diff_base_unpinned`,
 * [unpinned]. The CI condition reads [CiFacts] instead, later.
 */
data class AutoMergeFacts(
    val verifiedVerdict: Boolean,
    val hiddenChecksPassed: Int,
    val holdouts: Int,
    val fingerprint: String,
    val mergedHead: String,
    val shownHead: String?,
    val pullRequestNumber: Long,
    val denyGuards: List<DenyGuard>,
    val diff: List<DiffEntry>,
    val autoMerge: AutoMergeConfig,
    val unpinned: Boolean,
)

/**
 * What the CI condition reads (m2-28, send back 5 of PR #75), right after the merge risk review so a head pushed a
 * moment before has had time to register its runs: the [mergeability] of pull request [pullRequestNumber], the [ci]
 * mode, and under [CiMode.Actions] only the Actions runs of the [mergedHead], [headRuns] (m3-15), with the [noRun]
 * rule, the CI wait having already applied the grace. Under [CiMode.None] no run is read and [headRuns] is null
 * (m4-05, bl-146).
 */
data class CiFacts(
    val pullRequestNumber: Long,
    val mergedHead: String,
    val mergeability: Result<Mergeability>,
    val headRuns: Result<ActionsRuns>? = null,
    val ci: CiMode = CiMode.None,
    val noRun: CiNoRun = CiNoRun.Fail,
)

/** The hard conditions of an auto merge and the comment of a fallback (m2-28). */
object AutoMerge {
    /** The `approved_by` of an approval the `auto` policy gave itself. */
    const val AUTO: String = "auto"

    /** The comment reason of a fallback. */
    const val COMMENT_REASON: String = IssueCommented.AUTO_MERGE_FALLBACK

    /** GitHub mergeable states an auto merge accepts: everything green, `has_hooks` on GitHub Enterprise. */
    private val GREEN_STATES = setOf("clean", "has_hooks")

    /**
     * The states an auto merge accepts under `ci: none` (m4-05): `unstable` adds failing checks GitHub reports, such as
     * runs Actions refused for billing (bl-98), which `ci: none` does not wait for.
     */
    private val GREEN_STATES_CI_NONE = GREEN_STATES + "unstable"

    /**
     * Every condition but [AutoMergeCondition.CI] that fails on [facts], in the order of [AutoMergeCondition], each with
     * its reason; empty when all hold. CI is read after the merge risk review, see [ciFailure].
     */
    fun failures(facts: AutoMergeFacts): List<FailedCondition> =
        AutoMergeCondition.entries.filter { it != AutoMergeCondition.CI }.mapNotNull { condition ->
            reason(condition, facts)?.let { FailedCondition(condition, it) }
        }

    /**
     * The CI condition on [facts], null when it holds (send back 5 of PR #75, m3-15, m4-05). Under [CiMode.Actions] the
     * pull request is mergeable and `clean`, and the Actions runs of the merged head pass [CiWait.verdict]: a red or
     * pending verdict fails it, and a head with no run unless `ci_no_run` is `pass`. Under [CiMode.None] green means the
     * merge check passed, which the gate proved before reading CI, so no run is read and only the pull request must be
     * mergeable, `unstable` included. Anything that cannot be read fails, closed.
     */
    fun ciFailure(facts: CiFacts): FailedCondition? = ciReason(facts)?.let { FailedCondition(AutoMergeCondition.CI, it) }

    private fun ciReason(facts: CiFacts): String? {
        val number = facts.pullRequestNumber
        val found =
            facts.mergeability.getOrElse { error ->
                return "cannot read whether pull request #$number is mergeable: ${firstLine(error)}"
            }
        val green = if (facts.ci == CiMode.Actions) GREEN_STATES else GREEN_STATES_CI_NONE
        when {
            found.mergeable == null ->
                return "GitHub has not said whether pull request #$number is mergeable (state ${found.state ?: "unknown"})"
            found.mergeable != true || found.state?.lowercase() !in green ->
                return "pull request #$number is not mergeable and green, mergeable ${found.mergeable}, state ${found.state ?: "unknown"}"
        }
        if (facts.ci == CiMode.None) return null
        val head = short(facts.mergedHead)
        val read = facts.headRuns ?: return "the Actions runs of the merged head $head were not read"
        val runs = read.getOrElse { return "cannot read the Actions runs of the merged head $head: ${firstLine(it)}" }
        return when (val verdict = CiWait.verdict(runs)) {
            is CiVerdict.Green -> null
            is CiVerdict.Red ->
                "Actions runs on the merged head $head did not pass: " +
                    shown(verdict.failed.map { "${it.name} (${it.conclusion ?: "no conclusion"})" })
            is CiVerdict.Pending -> "Actions runs on the merged head $head are pending: ${CiWait.pendingText(verdict)}"
            CiVerdict.NoRun -> if (facts.noRun == CiNoRun.Pass) null else "no Actions run on the merged head $head, and ci_no_run is fail"
        }
    }

    private fun firstLine(error: Throwable): String = (error.message ?: error::class.simpleName).orEmpty().lines().first()

    internal fun shown(items: List<String>): String =
        items.take(SHOWN).joinToString(", ") + if (items.size > SHOWN) " and ${items.size - SHOWN} more" else ""

    private fun reason(
        condition: AutoMergeCondition,
        facts: AutoMergeFacts,
    ): String? =
        when (condition) {
            AutoMergeCondition.VERIFIED ->
                when {
                    !facts.verifiedVerdict -> "the attempt has no verified verdict at the delivered fingerprint"
                    facts.hiddenChecksPassed < facts.holdouts ->
                        "${facts.hiddenChecksPassed} of the ${facts.holdouts} hidden checks of the kind passed in its verification"
                    else -> null
                }
            AutoMergeCondition.MERGE_CHECK ->
                when {
                    facts.fingerprint != MergeCheck.cleanFingerprint(facts.mergedHead) ->
                        "the delivered fingerprint ${facts.fingerprint} is not the merged head ${short(facts.mergedHead)}"
                    facts.shownHead != null && !facts.shownHead.equals(facts.mergedHead, ignoreCase = true) ->
                        "pull request #${facts.pullRequestNumber} shows the head ${short(facts.shownHead)}, " +
                            "not the checked ${short(facts.mergedHead)}"
                    else -> null
                }
            AutoMergeCondition.CI -> null
            AutoMergeCondition.NO_LOST_TESTS -> {
                val missing = DenyGuard.entries.filter { it !in facts.denyGuards }
                if (missing.isEmpty()) {
                    null
                } else {
                    "the merge check's diff_policy does not run the deny guards ${missing.joinToString(", ") { it.key }}, " +
                        "so a lost test cannot be ruled out"
                }
            }
            AutoMergeCondition.PROTECTED_PATHS -> {
                val globs = ProtectedPaths(facts.autoMerge.protectedPaths, ignoreCase = true)
                val touched = facts.diff.mapNotNull { entry -> globs.match(entry.path)?.let { "${entry.path} (protected by $it)" } }
                if (touched.isEmpty()) {
                    null
                } else {
                    "the diff touches " + touched.take(SHOWN).joinToString(", ") +
                        if (touched.size > SHOWN) " and ${touched.size - SHOWN} more" else ""
                }
            }
            AutoMergeCondition.DIFF_SIZE -> {
                val lines = facts.diff.sumOf { it.added + it.deleted }
                val limit = facts.autoMerge.maxDiffLines
                val uncounted = facts.diff.filter { !it.counted }.map { it.path }
                when {
                    uncounted.isNotEmpty() -> "the lines of ${shown(uncounted)} were not counted, so the diff size is unknown"
                    lines > limit -> "the diff changes $lines lines, over the limit of $limit"
                    else -> null
                }
            }
            AutoMergeCondition.DIFF_BASE_PINNED ->
                if (facts.unpinned) {
                    "a verification of the attempt could not fetch the base and judged diff_policy against guards.base (diff_base_unpinned)"
                } else {
                    null
                }
        }

    /**
     * The comment of a fallback on pull request [number]: why when the workflow no longer allows an auto merge,
     * [stopped], the [failures] of the hard conditions, then the merge risk, its band and score and each dimension's
     * verdict and reasons when [risk] was assessed, else why it was not, [riskSkipped]. It addresses the operator and
     * names no command, like the evidence summary.
     */
    fun fallbackComment(
        number: String,
        failures: List<FailedCondition>,
        risk: RiskAssessment?,
        riskSkipped: String?,
        stopped: String? = null,
    ): String =
        buildString {
            if (stopped != null) {
                appendLine("Loop did not merge pull request #$number on its own: $stopped, so it waits for an operator's approval.")
            } else {
                appendLine(
                    "Loop did not merge pull request #$number on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.",
                )
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("Failed conditions:")
                failures.forEach { appendLine("- ${it.condition.wire}: ${it.reason}") }
            }
            appendLine()
            if (risk == null) {
                appendLine("Merge risk: not assessed, ${riskSkipped ?: "unknown reason"}.")
            } else {
                val worst =
                    risk.worst
                        .takeIf { it.isNotEmpty() }
                        ?.let { list ->
                            ", at its worst verdict: ${list.joinToString(", ") { it.wire }}"
                        }.orEmpty()
                appendLine("Merge risk: band ${risk.band.wire}, score ${MergeRisk.format(risk.score)}$worst.")
                risk.commentLines().forEach { appendLine(it) }
            }
            appendLine()
            append("Once approved, Loop runs the merge check again and merges.")
        }

    private fun short(sha: String): String = sha.take(SHORT_SHA)

    private const val SHOWN = 10
    private const val SHORT_SHA = 12
}
