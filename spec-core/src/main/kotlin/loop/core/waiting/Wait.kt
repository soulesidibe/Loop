package loop.core.waiting

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.MergeCheck
import kotlin.time.Instant

/**
 * One wait of an issue, `Waiting(verify | approval | merge | ask)` (m2-04, m2-24, m2-26, m4-31, m4-52), typed. It is read from and
 * written to the store as a [WaitingIssue] whose data stays an opaque map: [of] translates a record, a record
 * written by an earlier Loop included, and [toRecord] gives it back with every key it was read with, so a wait
 * that goes through this type writes back exactly what it read plus what its transition changed.
 */
sealed class Wait(
    private val record: WaitingIssue,
) {
    val projectId: ProjectId get() = record.projectId
    val issueId: String get() = record.issueId
    val identifier: String get() = record.identifier
    val reason: WaitReason get() = record.reason

    /** The attempt that started the wait, null when none did. */
    val attemptId: AttemptId? get() = record.attemptId
    val runId: RunId? get() = record.runId
    val since: Instant get() = record.since

    /** The name of the kind the issue was dispatched under (m4-18), null when the record holds none. */
    val kind: String? get() = record.data[WaitKeys.KIND]

    protected val data: Map<String, String> get() = record.data

    /**
     * True when the wait stored the state its issue was in when it started, [WaitKeys.STATE], and [state] is another one,
     * trimmed and case folded (m4-31, bl-252): a human moved the issue since. A record with no state is never moved.
     */
    protected fun movedFrom(state: String): Boolean {
        val stored = data[WaitKeys.STATE] ?: return false
        return !stored.trim().equals(state.trim(), ignoreCase = true)
    }

    /** The record this wait is in the store. */
    fun toRecord(): WaitingIssue = record

    protected fun with(
        reason: WaitReason = record.reason,
        since: Instant = record.since,
        data: Map<String, String>,
    ): WaitingIssue = record.copy(reason = reason, since = since, data = data)

    override fun equals(other: Any?): Boolean = other is Wait && other.record == record

    override fun hashCode(): Int = record.hashCode()

    override fun toString(): String = "Wait(${record.reason.wire}, ${record.identifier})"

    /** The checks of a submitted result run (m2-04). */
    class Verify internal constructor(
        record: WaitingIssue,
    ) : Wait(record) {
        /** The state the result moves the issue to once its checks passed, null when the record holds none. */
        val to: String? get() = data[WaitKeys.TO]
    }

    /**
     * A verified result waits for `loop approve` (m2-24), or, approved, for its delivery on the next tick. With pull
     * request delivery (m2-25) it also holds the check lines and the judgment calls for the body, and the URL of the
     * pull request once it was commented. Since bl-252 it holds the [state] its issue was in when it started: a human
     * move of the issue to another state rejects the result, see [humanMoved].
     */
    class Approval internal constructor(
        record: WaitingIssue,
    ) : Wait(record) {
        /** The state the delivery moves the issue to, null when the record holds none. */
        val to: String? get() = data[WaitKeys.TO]

        /** The state the issue was in when the wait started (bl-252), null for a wait stored by an older build. */
        val state: String? get() = data[WaitKeys.STATE]

        /**
         * True when the issue is now in [state], another state than the one the wait started in (bl-252), by the rule of
         * [Ask.humanMoved]: a human rejected the result. A wait stored with no state is never moved.
         */
        fun humanMoved(state: String): Boolean = movedFrom(state)

        /** The workspace fingerprint the result was verified on. */
        val fingerprint: String? get() = data[WaitKeys.FINGERPRINT]

        /** The rendered check lines for the pull request body, blank lines left out (m2-25). */
        val checks: List<String> get() = checkLines(data)

        /** The number of judgment calls for the pull request body, 0 when the record holds none (m2-25). */
        val judgmentCalls: Int get() = data[WaitKeys.JUDGMENT_CALLS]?.toIntOrNull() ?: 0

        /** The URL of the pull request an earlier delivery already commented on the issue (m2-25). */
        val pullRequest: String? get() = data[WaitKeys.PULL_REQUEST]

        /** Who approved it, `auto` for the auto policy, null before `loop approve`. */
        val approvedBy: String? get() = data[WaitingIssue.APPROVED_BY]

        /** True once it was approved. */
        val approved: Boolean get() = toRecord().approved

        /** When it was approved (bl-175), null before `loop approve` or when the record holds no readable time. */
        val approvedAt: Instant? get() = data[WaitingIssue.APPROVED_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() }

        /** True once `loop send-back` sent it back (m4-32): it is never delivered, and its issue dispatches again. */
        val sentBack: Boolean get() = toRecord().sentBack

        /** Who sent it back (m4-32), null before `loop send-back`. */
        val sentBackBy: String? get() = data[WaitingIssue.SENT_BACK_BY]

        /** Why it was sent back (m4-32), null before `loop send-back`. */
        val sentBackReason: String? get() = data[WaitingIssue.SENT_BACK_REASON]

        /** This wait holding the [url] of the pull request a delivery commented on the issue (m2-25). */
        fun commented(url: String): Approval = Approval(with(data = data + (WaitKeys.PULL_REQUEST to url)))

        /** The key of the notice a human was sent for this wait (m4-30), null before one was sent. */
        val humanNotice: String? get() = data[WaitKeys.HUMAN_NOTICE]

        /** This wait holding [key], the key of the notice a human was sent for it (m4-30). */
        fun noticed(key: String): Approval = Approval(with(data = data + (WaitKeys.HUMAN_NOTICE to key)))

        /**
         * This wait without its approval (m2-25): only a new `loop approve` delivers it now. It is a new wait for the
         * human, so the key of the notice of the earlier one goes too (m4-30).
         */
        fun unapproved(): Approval =
            Approval(with(data = data - WaitingIssue.APPROVED_BY - WaitingIssue.APPROVED_AT - WaitKeys.HUMAN_NOTICE))

        /**
         * The `Waiting(merge)` of pull request [number] at [url] that follows this approved delivery (m2-26), since
         * [since]: every key of this wait, its approval included, plus the merge keys, phase `check`, round 1, with no
         * candidate head (bl-174) and no merge called (bl-181).
         */
        fun merging(
            number: Long,
            url: String,
            since: Instant,
        ): Merge =
            Merge(
                with(
                    reason = WaitReason.Merge,
                    since = since,
                    data =
                        data - WaitKeys.CANDIDATE_KEYS - WaitKeys.MERGE_CALLED +
                            mapOf(
                                WaitKeys.PULL_REQUEST to url,
                                WaitKeys.PULL_REQUEST_NUMBER to number.toString(),
                                WaitKeys.PHASE to WaitKeys.PHASE_CHECK,
                                WaitKeys.BASE_SHA to "",
                                WaitKeys.VERIFIED_FINGERPRINT to data[WaitKeys.FINGERPRINT].orEmpty(),
                                WaitKeys.MERGED_HEAD to "",
                                WaitKeys.ROUND to "1",
                            ),
                ),
            )
    }

    /**
     * An approved pull request waits for its merge check and its merge (m2-26), in the [phase] it stored, so a restart
     * resumes it without a second pull request, a second merge or a lost approval.
     */
    class Merge internal constructor(
        record: WaitingIssue,
    ) : Wait(record) {
        /** The fingerprint the merge goes on from: the verified one, then the merged one after a passing check. */
        val fingerprint: String? get() = data[WaitKeys.FINGERPRINT]

        /** The fingerprint the result was verified on before its merge check. */
        val verifiedFingerprint: String? get() = data[WaitKeys.VERIFIED_FINGERPRINT]

        /** The rendered check lines of the approval, blank lines left out. */
        val checks: List<String> get() = checkLines(data)

        /** Who approved it, `auto` for the auto merge (m2-28). */
        val approvedBy: String? get() = data[WaitingIssue.APPROVED_BY]

        /** The number of the pull request as stored, empty when the record holds none, for the texts. */
        val numberText: String get() = data[WaitKeys.PULL_REQUEST_NUMBER].orEmpty()

        /** The number of the pull request, null when the record holds none or no number. */
        val pullRequestNumber: Long? get() = data[WaitKeys.PULL_REQUEST_NUMBER]?.toLongOrNull()

        /** The phase it resumes in: [MergePhase.Merge] or [MergePhase.Ci] only when the record says so, else the check. */
        val phase: MergePhase
            get() =
                when (data[WaitKeys.PHASE]) {
                    WaitKeys.PHASE_MERGE -> MergePhase.Merge
                    WaitKeys.PHASE_CI -> MergePhase.Ci
                    else -> MergePhase.Check
                }

        /** When the merged head was pushed for the CI wait (m3-15), null outside it or when the record cannot say. */
        val ciStartedAt: Instant? get() = data[WaitKeys.CI_STARTED_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() }

        /** The milliseconds the CI wait counted, 0 when none (m3-15). */
        val ciWaitedMs: Long get() = data[WaitKeys.CI_WAITED_MS]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

        /** The base commit the merge check ran or runs against, empty before the first. */
        val baseSha: String? get() = data[WaitKeys.BASE_SHA]

        /** The commit that passed the merge check, null before. */
        val mergedHead: String? get() = data[WaitKeys.MERGED_HEAD]?.takeIf { it.isNotBlank() }

        /** The merge commit the check of phase `check` made and pushes early (bl-174), null before it exists. */
        val candidateHead: String? get() = data[WaitKeys.CANDIDATE_HEAD]?.takeIf { it.isNotBlank() }

        /** True once [head] was pushed early as the candidate head (bl-174), so pushing it again records nothing. */
        fun pushedEarly(head: String): Boolean = data[WaitKeys.CANDIDATE_PUSHED] == "true" && candidateHead.equals(head, ignoreCase = true)

        /** True once Loop called the merge of the pull request in this round (bl-181): a merge found then is its own. */
        val mergeCalled: Boolean get() = data[WaitKeys.MERGE_CALLED] == "true"

        /** The round of the merge check, from 1. */
        val round: Int get() = data[WaitKeys.ROUND]?.toIntOrNull() ?: 1

        /** True once a merge was refused because a branch moved under it: a second such refusal is permanent. */
        val retried: Boolean get() = data[WaitKeys.MERGE_RETRY] == "true"

        /** This wait holding pull request [number] as the one it merges. */
        fun numbered(number: Long): Merge = Merge(with(data = data + (WaitKeys.PULL_REQUEST_NUMBER to number.toString())))

        /** This wait holding [base] as the base its check is about to merge, so a stop after the merge commit finds it. */
        fun intending(base: String): Merge = Merge(with(data = data + (WaitKeys.BASE_SHA to base)))

        /** This wait holding [head], the merge commit its check just made, as the candidate head, not pushed yet (bl-174). */
        fun candidate(head: String): Merge = Merge(with(data = data - WaitKeys.CANDIDATE_PUSHED + (WaitKeys.CANDIDATE_HEAD to head)))

        /** This wait about to call the merge of its pull request (bl-181), stored before the call. */
        fun callingMerge(): Merge = Merge(with(data = data + (WaitKeys.MERGE_CALLED to "true")))

        /** This wait once its candidate head was pushed (bl-174). */
        fun candidatePushed(): Merge = Merge(with(data = data + (WaitKeys.CANDIDATE_PUSHED to "true")))

        /**
         * This wait after a clean merge check against [base] (m2-26): phase `merge`, [head] the merged head and its
         * clean fingerprint the one the merge goes on from, the round written, a refused merge forgotten. The candidate
         * head stays, so the push that starts the CI wait knows whether it was pushed early (bl-174).
         */
        fun checked(
            base: String,
            head: String,
        ): Merge =
            Merge(
                with(
                    data =
                        data - WaitKeys.MERGE_RETRY - WaitKeys.CI_KEYS +
                            mapOf(
                                WaitKeys.PHASE to WaitKeys.PHASE_MERGE,
                                WaitKeys.BASE_SHA to base,
                                WaitKeys.MERGED_HEAD to head,
                                WaitKeys.FINGERPRINT to MergeCheck.cleanFingerprint(head),
                                WaitKeys.ROUND to round.toString(),
                            ),
                ),
            )

        /**
         * This wait back in phase `check` for [round], after the base moved before the merge; the CI wait starts anew,
         * the candidate head goes, since the new round makes a new merge commit (bl-174), and so does a merge called in
         * the round before (bl-181).
         */
        fun again(round: Int): Merge =
            Merge(
                with(
                    data =
                        data - WaitKeys.CI_KEYS - WaitKeys.CANDIDATE_KEYS - WaitKeys.MERGE_CALLED +
                            mapOf(WaitKeys.PHASE to WaitKeys.PHASE_CHECK, WaitKeys.ROUND to round.toString()),
                ),
            )

        /** This wait in phase `ci` (m3-15), its merged head pushed at [at], nothing counted yet. */
        fun waitingForCi(at: Instant): Merge =
            Merge(
                with(
                    data =
                        data +
                            mapOf(
                                WaitKeys.PHASE to WaitKeys.PHASE_CI,
                                WaitKeys.CI_STARTED_AT to at.toString(),
                                WaitKeys.CI_WAITED_MS to "0",
                            ),
                ),
            )

        /** This wait having counted [ms] of the CI wait in all (m3-15). */
        fun waited(ms: Long): Merge = Merge(with(data = data + (WaitKeys.CI_WAITED_MS to ms.toString())))

        /** This wait once CI allowed the merge (m3-15): phase `merge`, the CI keys kept to say the head was pushed. */
        fun ciPassed(): Merge = Merge(with(data = data + (WaitKeys.PHASE to WaitKeys.PHASE_MERGE)))

        /** This wait once a merge was refused because a branch moved under it, tried once more at the next tick. */
        fun retrying(): Merge = Merge(with(data = data + (WaitKeys.MERGE_RETRY to "true")))

        /**
         * The unapproved `Waiting(approval)` a refused merge falls back to, since [since]: the merge keys and the
         * approval go, the fingerprint stays the one this wait holds, the merged one after a passing check, so a new
         * approval replays it. The key of the earlier notice goes too (m4-30): the human is told again.
         */
        fun refused(since: Instant): Approval =
            Approval(
                with(
                    reason = WaitReason.Approval,
                    since = since,
                    data = data - WaitKeys.MERGE_KEYS - WaitingIssue.APPROVED_BY - WaitingIssue.APPROVED_AT - WaitKeys.HUMAN_NOTICE,
                ),
            )

        /** The approved `Waiting(approval)` of a workflow that no longer merges: the merge keys go, the approval stays. */
        fun unmerged(): Approval = Approval(with(reason = WaitReason.Approval, data = data - WaitKeys.MERGE_KEYS))
    }

    /**
     * An agent asked a human a question with `ask` (m4-31): the attempt ended without verification, its workspace kept,
     * and the issue waits until a human moves it or answers it with `loop answer` (m4-52). [state] is the state it was in
     * when the question was asked. Answered, it no longer waits for a human: its issue dispatches again, and the attempt
     * that renders the answer ends the wait.
     */
    class Ask internal constructor(
        record: WaitingIssue,
    ) : Wait(record) {
        /** The state the issue was in at the ask, null when the record holds none. */
        val state: String? get() = data[WaitKeys.STATE]

        /** The kind of the question, `engineering`, `design`, `product` or `human`, null when the record holds none. */
        val questionKind: String? get() = data[WaitKeys.QUESTION_KIND]

        /** The key of the notice a human was sent for this wait (m4-30), null before one was sent. */
        val humanNotice: String? get() = data[WaitKeys.HUMAN_NOTICE]

        /** This wait holding [key], the key of the notice a human was sent for it (m4-30). */
        fun noticed(key: String): Ask = Ask(with(data = data + (WaitKeys.HUMAN_NOTICE to key)))

        /** The question as Loop posted it on the issue (m4-52), null for a wait stored before m4-52. */
        val question: String? get() = data[WaitKeys.QUESTION]

        /** This wait holding [question], the text Loop posted on the issue (m4-52). */
        fun asking(question: String): Ask = Ask(with(data = data + (WaitKeys.QUESTION to question)))

        /** The answer `loop answer` gave (m4-52), null before. */
        val answer: String? get() = data[WaitingIssue.ANSWER]

        /** Who answered (m4-52), null before `loop answer`. */
        val answeredBy: String? get() = data[WaitingIssue.ANSWERED_BY]

        /** True once `loop answer` answered it (m4-52). */
        val answered: Boolean get() = data[WaitingIssue.ANSWERED_AT] != null

        /**
         * True when a human moved the issue since the ask, which ends the wait, as for an approval: the issue is gone
         * ([state] null), out of the active states ([active] false), or in another state than the one it was asked in.
         * A record with no state ends on the first two only.
         */
        fun humanMoved(
            state: String?,
            active: Boolean,
        ): Boolean {
            if (state == null || !active) return true
            return movedFrom(state)
        }
    }

    /** A wait for a reason this Loop does not know, kept as it is: its issue waits. */
    class Other internal constructor(
        record: WaitingIssue,
    ) : Wait(record)

    companion object {
        /** The typed wait of [record], whatever Loop wrote it. */
        fun of(record: WaitingIssue): Wait =
            when (record.reason) {
                WaitReason.Verify -> Verify(record)
                WaitReason.Approval -> Approval(record)
                WaitReason.Merge -> Merge(record)
                WaitReason.Ask -> Ask(record)
                else -> Other(record)
            }

        /**
         * The `Waiting(verify)` of a submitted result whose checks run, moving the issue to [to] once they pass, under
         * the [kind] the issue was dispatched under (m4-18), null in Symphony mode.
         */
        fun verify(
            projectId: ProjectId,
            issueId: String,
            identifier: String,
            attemptId: AttemptId?,
            runId: RunId?,
            since: Instant,
            to: String,
            kind: String? = null,
        ): Verify {
            val data = mapOf(WaitKeys.TO to to) + listOfNotNull(kind?.let { WaitKeys.KIND to it })
            return Verify(WaitingIssue(projectId, issueId, identifier, WaitReason.Verify, attemptId, runId, since, data))
        }

        /**
         * The `Waiting(approval)` of a result verified on [fingerprint] (m2-24), delivered to [to]. With pull request
         * delivery (m2-25) [checks] is the rendered check lines and [judgmentCalls] their number; [approvedBy] and
         * [approvedAt] approve it at once, for the auto policy; [pullRequest] is the URL already commented. [kind] is
         * the kind the issue was dispatched under (m4-18), null in Symphony mode. [state] is the state the issue is in
         * as the wait starts (bl-252), null only for a record shaped like an older build's.
         */
        fun approval(
            projectId: ProjectId,
            issueId: String,
            identifier: String,
            attemptId: AttemptId?,
            runId: RunId?,
            since: Instant,
            to: String,
            fingerprint: String,
            checks: String? = null,
            judgmentCalls: Int? = null,
            approvedBy: String? = null,
            approvedAt: Instant? = null,
            pullRequest: String? = null,
            kind: String? = null,
            state: String? = null,
        ): Approval {
            val data =
                mapOf(WaitKeys.TO to to, WaitKeys.FINGERPRINT to fingerprint) +
                    listOfNotNull(
                        checks?.let { WaitKeys.CHECKS to it },
                        judgmentCalls?.let { WaitKeys.JUDGMENT_CALLS to it.toString() },
                        approvedBy?.let { WaitingIssue.APPROVED_BY to it },
                        approvedAt?.let { WaitingIssue.APPROVED_AT to it.toString() },
                        pullRequest?.let { WaitKeys.PULL_REQUEST to it },
                        kind?.let { WaitKeys.KIND to it },
                        state?.let { WaitKeys.STATE to it },
                    )
            return Approval(WaitingIssue(projectId, issueId, identifier, WaitReason.Approval, attemptId, runId, since, data))
        }

        /**
         * The `Waiting(ask)` of an agent's question of [questionKind] (m4-31), asked while the issue was in [state], under
         * the [kind] the issue was dispatched under (m4-18).
         */
        fun ask(
            projectId: ProjectId,
            issueId: String,
            identifier: String,
            attemptId: AttemptId?,
            runId: RunId?,
            since: Instant,
            state: String,
            questionKind: String,
            kind: String? = null,
        ): Ask {
            val data =
                mapOf(WaitKeys.STATE to state, WaitKeys.QUESTION_KIND to questionKind) + listOfNotNull(kind?.let { WaitKeys.KIND to it })
            return Ask(WaitingIssue(projectId, issueId, identifier, WaitReason.Ask, attemptId, runId, since, data))
        }

        private fun checkLines(data: Map<String, String>): List<String> =
            data[WaitKeys.CHECKS]
                ?.lines()
                ?.filter { it.isNotBlank() }
                .orEmpty()
    }
}

/** The phase of a merge wait (m2-26). */
enum class MergePhase {
    /** The merge check runs: fetch the base, merge it, run the checks again. */
    Check,

    /** The merge check passed and the merged head was pushed: wait for its Actions runs (m3-15). */
    Ci,

    /** The merge check passed: push the merged head, read the base again, merge the pull request. */
    Merge,
}
