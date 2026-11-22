package loop.core.events

import loop.api.RecordedEvent
import loop.core.delivery.RiskDimension
import loop.core.events.HumanText.SHORT_SHA

/** The fields the delivery events share. */
object Delivery {
    const val TO = "to"
    const val BY = "by"
    const val POLICY = "policy"
    const val FINGERPRINT = "fingerprint"
    const val SHA = "sha"
    const val BRANCH = "branch"
    const val NUMBER = "number"
    const val URL = "url"
    const val STATE = "state"
    const val STEP = "step"
    const val REASON = "reason"
    const val BASE = "base"
    const val BASE_SHA = "base_sha"
    const val ROUND = "round"
    const val OUTCOME = "outcome"
    const val HEAD = "head"
    const val METHOD = "method"
    const val RUNS = "runs"
}

/** An event whose trace details are the listed keys, each printed when present. */
abstract class KeyedEvent(
    name: String,
    private vararg val traceKeys: String,
) : EventDefinition(name) {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { traceKeys.forEach { key -> add(key, event.data[key]) } }
}

/** A verified result waits for `loop approve` under the human delivery policy (m2-24). */
object ApprovalRequested : KeyedEvent("approval_requested", Delivery.TO, Delivery.POLICY, Delivery.FINGERPRINT) {
    override val sample = mapOf(Delivery.TO to "review", Delivery.POLICY to "human", Delivery.FINGERPRINT to "git:abc:def:0")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "waits for approval to move to ${data[Delivery.TO]}, the delivery policy is human, approve it with loop approve"
}

/** `loop approve` approved a waiting result (m2-24). */
object ApprovalGiven : KeyedEvent("approval_given", Delivery.BY, Delivery.TO) {
    override val human = HumanShow.SILENT
    override val sample = mapOf(Delivery.BY to "alice", Delivery.TO to "review")
}

/** m2-25: the verified commit pushed to the issue's branch. */
object BranchPushed : KeyedEvent("branch_pushed", Delivery.SHA, Delivery.BRANCH) {
    override val sample = mapOf(Delivery.SHA to "0123456789abcdef", Delivery.BRANCH to "loop/bl-1")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "pushed ${data[Delivery.SHA]?.take(SHORT_SHA)} to ${data[Delivery.BRANCH]}"
}

/** m2-25: a pull request opened for the issue's branch. */
object PullRequestOpened : KeyedEvent("pull_request_opened", Delivery.NUMBER, Delivery.URL) {
    override val sample = mapOf(Delivery.NUMBER to "12", Delivery.URL to "https://example.com/pull/12")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "opened pull request #${data[Delivery.NUMBER]}, ${data[Delivery.URL]}"
}

/** m2-25: the issue's open pull request updated. */
object PullRequestUpdated : KeyedEvent("pull_request_updated", Delivery.NUMBER, Delivery.URL) {
    override val sample = mapOf(Delivery.NUMBER to "12", Delivery.URL to "https://example.com/pull/12")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "updated pull request #${data[Delivery.NUMBER]}, ${data[Delivery.URL]}"
}

/** m2-25: a pull request of the issue's branch already merged at the verified commit. */
object PullRequestFound : KeyedEvent("pull_request_found", Delivery.NUMBER, Delivery.STATE, Delivery.URL) {
    override val sample = mapOf(Delivery.NUMBER to "12", Delivery.URL to "https://example.com/pull/12", Delivery.STATE to "merged")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "found pull request #${data[Delivery.NUMBER]} already ${data[Delivery.STATE]}, ${data[Delivery.URL]}"
}

/** m2-25: a delivery step refused for good; the result waits for a new approval. */
object DeliveryFailed : KeyedEvent("delivery_failed", Delivery.STEP, Delivery.REASON) {
    override val sample = mapOf(Delivery.STEP to "push", Delivery.REASON to "rejected")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "could not be delivered, ${data[Delivery.STEP]}: ${data[Delivery.REASON]}. It waits for a new approval with loop approve"
}

/** m2-26: the tip of the base fetched host side for the merge check. */
object BaseFetched : KeyedEvent("base_fetched", Delivery.BASE, Delivery.SHA) {
    override val sample = mapOf(Delivery.SHA to "0123456789abcdef", Delivery.BASE to "main")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "fetched ${data[Delivery.BASE]} at ${data[Delivery.SHA]?.take(SHORT_SHA)}"
}

/**
 * bl-59: the fetch of the base before an attempt's hooks failed. [Delivery.SHA], when present, is the last fetched
 * `refs/loop/base/<base>` the hooks get as `LOOP_BASE_REF` instead; absent, `LOOP_BASE_REF` is unset.
 */
object BaseFetchFailed : KeyedEvent("base_fetch_failed", Delivery.BASE, Delivery.SHA, Delivery.REASON) {
    override val sample = mapOf(Delivery.BASE to "main", Delivery.SHA to "0123456789abcdef", Delivery.REASON to "cannot fetch main")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val base = data[Delivery.BASE]
        val fallback =
            data[Delivery.SHA]?.let { "the hooks start from the last fetched $base at ${it.take(SHORT_SHA)}" }
                ?: "no earlier fetch, the hooks get no LOOP_BASE_REF"
        return "could not fetch $base before the attempt, $fallback: ${data[Delivery.REASON]}"
    }
}

/** m2-26: a round of the merge check started against a fetched base. */
object MergeCheckStarted : KeyedEvent("merge_check_started", Delivery.BASE_SHA, Delivery.ROUND) {
    /** The round resumed after a restart. */
    const val RESUMED = "resumed"

    override val sample = mapOf(Delivery.BASE_SHA to "0123456789abcdef", Delivery.ROUND to "1")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "merge check started against ${data[Delivery.BASE_SHA]?.take(SHORT_SHA)}, round ${data[Delivery.ROUND]}"
}

/** m2-26: how a round of the merge check ended. */
object MergeCheckFinished : KeyedEvent("merge_check_finished", Delivery.OUTCOME, Delivery.ROUND, Delivery.HEAD) {
    const val UP_TO_DATE = "up_to_date"
    const val MERGED = "merged"
    const val CONFLICT = "conflict"
    const val REFUTED = "refuted"

    override val sample = mapOf(Delivery.BASE_SHA to "0123456789abcdef", Delivery.ROUND to "1", Delivery.OUTCOME to UP_TO_DATE)

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val base = data[Delivery.BASE_SHA]?.take(SHORT_SHA)
        return when (data[Delivery.OUTCOME]) {
            UP_TO_DATE -> "merge check passed, the base $base is already in the branch"
            MERGED -> "merge check passed, merged the base into ${data[Delivery.HEAD]?.take(SHORT_SHA)} and every check passed again"
            CONFLICT -> "merge check failed, merging $base conflicts, the issue goes back to its agent"
            // bl-32: no head when nothing was merged, diff_policy failed against a base already in the branch.
            REFUTED ->
                if (data[Delivery.HEAD] == null) {
                    "merge check failed, diff_policy failed against the base $base already in the branch, the issue goes back to its agent"
                } else {
                    "merge check failed, the checks failed on the merged result, the issue goes back to its agent"
                }
            else -> "merge check ended, ${data[Delivery.OUTCOME]}"
        }
    }
}

/**
 * bl-172: at startup, a merge wait behind the head of the delivery queue was found in phase [PHASE] (`ci` or `merge`) and
 * went back to phase `check` at its [Delivery.ROUND], so only the head holds the queue. [HEAD_ISSUE] names the head.
 */
object MergeRequeued : KeyedEvent("merge_requeued", MergeRequeued.PHASE, Delivery.ROUND, MergeRequeued.HEAD_ISSUE) {
    /** The phase the wait was found in. */
    const val PHASE = "phase"

    /** The identifier of the issue at the head of the queue. */
    const val HEAD_ISSUE = "head_issue"

    override val sample = mapOf(PHASE to "ci", Delivery.ROUND to "1", HEAD_ISSUE to "bl-2")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "waits behind ${data[HEAD_ISSUE]} in the delivery queue, back to the merge check of round ${data[Delivery.ROUND]} " +
        "from phase ${data[PHASE]}"
}

/**
 * bl-176: the base moved after the merge check from [Delivery.BASE_SHA] to [TIP] only in [FILES], each matching
 * `recheck_skip_paths` and none in the pull request's diff, so the merge goes on with no new round. [FILES] lists at
 * most [FILES_MAX] paths, comma separated.
 */
object MergeRoundSkipped : KeyedEvent("merge_round_skipped", Delivery.BASE_SHA, MergeRoundSkipped.TIP, MergeRoundSkipped.FILES) {
    /** The base tip the merge goes on against. */
    const val TIP = "tip"

    /** The files that changed between the checked base and the tip. */
    const val FILES = "files"

    /** At most this many files are listed. */
    const val FILES_MAX = 20

    override val sample = mapOf(Delivery.BASE_SHA to "0123456789abcdef", TIP to "fedcba9876543210", FILES to ".loop/issues/bl-1.md")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "the base moved to ${data[TIP]?.take(SHORT_SHA)} only in recheck_skip_paths, merges with no new round: ${data[FILES]}"
}

/**
 * bl-178: the early conflict check of a merge wait queued behind the head of the delivery queue found, with
 * `git merge-tree`, that merging the fetched base tip [BASE] into its head conflicts in [PATHS]. The issue goes back to
 * its agent before its turn, with the merge check's conflict comment. [PATHS] lists at most [PATHS_MAX] paths, comma
 * separated.
 */
object MergeConflictEarly : KeyedEvent(
    "merge_conflict_early",
    MergeConflictEarly.ISSUE,
    MergeConflictEarly.BASE,
    MergeConflictEarly.PATHS,
) {
    /** The identifier of the queued issue whose merge conflicts. */
    const val ISSUE = "issue"

    /** The fetched base tip, a commit. */
    const val BASE = "base"

    /** The conflicted paths. */
    const val PATHS = "paths"

    /** At most this many paths are listed. */
    const val PATHS_MAX = 20

    override val sample = mapOf(ISSUE to "bl-3", BASE to "0123456789abcdef", PATHS to "src/main/kotlin/App.kt")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "merging the base at ${data[BASE]?.take(SHORT_SHA)} conflicts in ${data[PATHS]}, " +
        "found while queued, the issue goes back to its agent before its turn"
}

/** m3-15: the merge waits for the Actions runs of the merged head [Delivery.SHA], pushed for them. */
object CiWaiting : KeyedEvent("ci_waiting", Delivery.SHA) {
    override val sample = mapOf(Delivery.SHA to "0123456789abcdef")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "waits for the Actions runs of ${data[Delivery.SHA]?.take(SHORT_SHA)} before the merge"
}

/** m3-15: how the CI wait ended, its outcome and the ids of the runs it kept. */
object CiFinished : KeyedEvent("ci_finished", Delivery.OUTCOME, Delivery.RUNS) {
    const val GREEN = "green"
    const val RED = "red"
    const val NO_RUN_PASS = "no_run_pass"
    const val NO_RUN_FAIL = "no_run_fail"
    const val TIMEOUT = "timeout"

    override val sample = mapOf(Delivery.OUTCOME to GREEN, Delivery.RUNS to "30433642,30433650")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = when (data[Delivery.OUTCOME]) {
        GREEN -> "CI passed, every Actions run of the merged head is green"
        RED -> "CI failed, an Actions run of the merged head did not pass, the pull request stays open"
        NO_RUN_PASS -> "CI passed, the merged head has no Actions run and ci_no_run is pass"
        NO_RUN_FAIL -> "CI failed, the merged head has no Actions run and ci_no_run is fail"
        TIMEOUT -> "CI timed out, Actions runs of the merged head are still pending"
        else -> "CI wait ended, ${data[Delivery.OUTCOME]}"
    }
}

/**
 * bl-175: the CI wait of the head of the delivery queue, [ISSUE], ended in `timeout`, so the queue of the project
 * pauses: no merge wait starts its merge check until `loop approve` or a restart. Held in memory, like the runner hold.
 */
object DeliveryQueuePaused : KeyedEvent("delivery_queue_paused", DeliveryQueuePaused.ISSUE, Delivery.REASON) {
    /** The identifier of the issue whose CI wait timed out. */
    const val ISSUE = "issue"

    /** The only reason so far: the head's CI wait timed out. */
    const val CI_TIMEOUT = "ci_timeout"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.RUN
    override val sample = mapOf(ISSUE to "bl-2", Delivery.REASON to CI_TIMEOUT)

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = when (data[Delivery.REASON]) {
        CI_TIMEOUT ->
            "delivery queue paused, the CI of ${data[ISSUE]} timed out, no merge check starts until loop approve or a restart"
        else -> "delivery queue paused after ${data[ISSUE]}, ${data[Delivery.REASON]}"
    }

    /** The kinds [openIn] reads: this one and [DeliveryQueueResumed]. */
    val QUEUE_KINDS: Set<String> get() = setOf(name, DeliveryQueueResumed.name)

    /**
     * The `delivery_queue_paused` among [events], in recording order, with no `delivery_queue_resumed` after it: the
     * pause still in effect, or the one an earlier run left, which a restart ends. Null when none.
     */
    fun openIn(events: List<RecordedEvent>): RecordedEvent? = events.lastOrNull { it.kind in QUEUE_KINDS }?.takeIf { it.kind == name }
}

/** bl-175: the paused delivery queue resumed, [Delivery.BY] [APPROVE] (`loop approve` of any issue) or [RESTART]. */
object DeliveryQueueResumed : KeyedEvent("delivery_queue_resumed", Delivery.BY) {
    const val APPROVE = "approve"
    const val RESTART = "restart"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.RUN
    override val sample = mapOf(Delivery.BY to APPROVE)

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = when (data[Delivery.BY]) {
        APPROVE -> "delivery queue resumed by loop approve"
        RESTART -> "delivery queue resumed by a restart"
        else -> "delivery queue resumed, ${data[Delivery.BY]}"
    }
}

/** m2-26: the pull request merged with a sha guard. */
object PullRequestMerged : KeyedEvent("pull_request_merged", Delivery.NUMBER, Delivery.SHA, Delivery.METHOD) {
    /** The commit the merge made on the base. */
    const val MERGE_SHA = "merge_sha"

    override val sample = mapOf(Delivery.NUMBER to "12", Delivery.SHA to "0123456789abcdef", Delivery.METHOD to "squash")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "merged pull request #${data[Delivery.NUMBER]} at ${data[Delivery.SHA]?.take(SHORT_SHA)} with ${data[Delivery.METHOD]}"
}

/** m2-28: the merge risk assessment of an auto merge: the band, the score, each dimension's verdict and the floors. */
object MergeRiskAssessed : EventDefinition("merge_risk_assessed") {
    const val BAND = "band"
    const val SCORE = "score"
    const val FLOORS = "floors"
    const val REVIEWER = "reviewer"

    /** m3-13: the tokens of the reviewer's prompt, as the counter of the reviewer model counted them. */
    const val PROMPT_TOKENS = "prompt_tokens"

    /** bl-137: `true` when the diff of the reviewer's prompt was cut to fit, which floors the band at medium. */
    const val DIFF_CUT = "diff_cut"

    /** bl-137: how many changed files the cut left out in whole or in part, 0 when the diff was shown whole. */
    const val FILES_LEFT_OUT = "files_left_out"

    /** The dimension keys, in the order of [RiskDimension]. */
    private val DIMENSIONS = RiskDimension.entries.map { it.wire }

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample =
        RiskDimension.entries.associate { it.wire to it.verdicts.first() } +
            mapOf(FLOORS to "", SCORE to "1.00", BAND to "low", REVIEWER to "sonnet")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(BAND, data[BAND])
        add(SCORE, data[SCORE])
        DIMENSIONS.forEach { add(it, data[it]) }
        add(FLOORS, data[FLOORS]?.ifEmpty { null })
        add(REVIEWER, data[REVIEWER])
        add(PROMPT_TOKENS, data[PROMPT_TOKENS])
        add(DIFF_CUT, data[DIFF_CUT])
        add(FILES_LEFT_OUT, data[FILES_LEFT_OUT])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val verdicts = DIMENSIONS.joinToString(", ") { "$it ${data[it]}" }
        val floors = data[FLOORS]?.takeIf { it.isNotEmpty() }?.let { ", floors $it" }.orEmpty()
        val cut = if (data[DIFF_CUT] == "true") ", the reviewer's diff cut with ${data[FILES_LEFT_OUT]} files left out" else ""
        return "merge risk band ${data[BAND]}, score ${data[SCORE]}: $verdicts$floors$cut"
    }
}

/** m2-28: an auto merge fell back to the human policy. */
object AutoMergeFallback : EventDefinition("auto_merge_fallback") {
    const val CONDITIONS = "conditions"
    const val BAND = "band"
    const val REASON = "reason"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(CONDITIONS to "ci", REASON to "ci is not green")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(CONDITIONS, event.data[CONDITIONS])
        add(BAND, event.data[BAND])
        add(REASON, event.data[REASON])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "did not merge on its own, ${data[REASON]}. It waits for approval with loop approve"
}
