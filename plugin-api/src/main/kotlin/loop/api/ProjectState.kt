package loop.api

import kotlin.time.Instant

/**
 * Stores the claims of a Project, so a restart knows which issues a dead process held (m2-02).
 * One record per claimed issue, written when its attempt starts and removed when it ends.
 * Like [EventSink], implementations return a failed [Result] instead of throwing.
 */
public interface Claims {
    /** Writes [claim], replacing any record for the same project and issue id. */
    public suspend fun claim(claim: Claim): Result<Unit>

    /** Every claim of [projectId], in no particular order. */
    public suspend fun claims(projectId: ProjectId): Result<List<Claim>>

    /** Removes the claim of [issueId], doing nothing when there is none. */
    public suspend fun release(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit>

    public companion object {
        /** Keeps nothing: every write succeeds, every read is empty. */
        public val NONE: Claims =
            object : Claims {
                override suspend fun claim(claim: Claim): Result<Unit> = Result.success(Unit)

                override suspend fun claims(projectId: ProjectId): Result<List<Claim>> = Result.success(emptyList())

                override suspend fun release(
                    projectId: ProjectId,
                    issueId: String,
                ): Result<Unit> = Result.success(Unit)
            }
    }
}

/**
 * One claimed issue: the [attemptId] working it in [runId], and the agent session of that attempt
 * once it started ([sessionId], and [runtimeSessionId] when the runtime reports one).
 */
public data class Claim(
    public val projectId: ProjectId,
    public val issueId: String,
    public val identifier: String,
    public val attemptId: AttemptId,
    public val runId: RunId?,
    public val claimedAt: Instant,
    public val sessionId: SessionId? = null,
    public val runtimeSessionId: String? = null,
) {
    init {
        require(projectId.value.isNotBlank()) { "Claim needs a project id" }
        require(issueId.isNotBlank()) { "Claim needs an issue id" }
    }
}

/**
 * Stores the retry bookkeeping of a Project, one record per issue: its queued retry and its
 * counters (m2-02), so a restart keeps the due time and the attempt cap. Like [EventSink],
 * implementations return a failed [Result] instead of throwing.
 */
public interface RetryStates {
    /** Writes [state], replacing any record for the same project and issue id. */
    public suspend fun saveRetryState(state: RetryState): Result<Unit>

    /** Removes the record of [issueId], doing nothing when there is none. */
    public suspend fun deleteRetryState(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit>

    /** Every record of [projectId], in no particular order. */
    public suspend fun retryStates(projectId: ProjectId): Result<List<RetryState>>

    public companion object {
        /** Keeps nothing: every write succeeds, every read is empty. */
        public val NONE: RetryStates =
            object : RetryStates {
                override suspend fun saveRetryState(state: RetryState): Result<Unit> = Result.success(Unit)

                override suspend fun deleteRetryState(
                    projectId: ProjectId,
                    issueId: String,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun retryStates(projectId: ProjectId): Result<List<RetryState>> = Result.success(emptyList())
            }
    }
}

/**
 * The retry bookkeeping of one issue. [retry] is its queued retry, null when none is queued;
 * [failures] is the consecutive failure count (`retries.max_attempts`) and [noProgress] the
 * consecutive no progress count (`retries.max_continuations`).
 */
public data class RetryState(
    public val projectId: ProjectId,
    public val issueId: String,
    public val identifier: String,
    public val retry: QueuedRetry?,
    public val failures: Int,
    public val noProgress: Int,
) {
    init {
        require(projectId.value.isNotBlank()) { "Retry state needs a project id" }
        require(issueId.isNotBlank()) { "Retry state needs an issue id" }
    }
}

/** A queued retry: the spec [attempt], when it is due, and the last error, null for a continuation. */
public data class QueuedRetry(
    public val attempt: Int,
    public val dueAt: Instant,
    public val error: String?,
)

/**
 * Stores the waiting issues of a Project (m2-04): an issue Loop is waiting on, for example the checks of a
 * submitted result, is not dispatched while it waits, whatever its tracker state, and the wait survives a
 * restart. One record per issue. Like [EventSink], implementations return a failed [Result] instead of throwing.
 */
public interface WaitingIssues {
    /** Writes [issue], replacing any record for the same project and issue id. */
    public suspend fun startWaiting(issue: WaitingIssue): Result<Unit>

    /** Every waiting issue of [projectId], in no particular order. */
    public suspend fun waitingIssues(projectId: ProjectId): Result<List<WaitingIssue>>

    /** Removes the record of [issueId], doing nothing when there is none. */
    public suspend fun endWaiting(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit>

    /**
     * Removes the record of [issueId] only when it is a [WaitReason.Approval] wait neither approved nor sent back (bl-224),
     * so a reaction that read the wait earlier never deletes a `loop approve` or a `loop send-back` committed since. The
     * record as it was before the call, removed or not, null when [issueId] does not wait. This default reads then
     * removes; a store that can do it in one transaction overrides it.
     */
    public suspend fun endUndecidedApproval(
        projectId: ProjectId,
        issueId: String,
    ): Result<WaitingIssue?> =
        waitingIssues(projectId).mapCatching { records ->
            val found = records.firstOrNull { it.issueId == issueId } ?: return@mapCatching null
            if (found.reason == WaitReason.Approval && !found.approved && !found.sentBack) endWaiting(projectId, issueId).getOrThrow()
            found
        }

    /**
     * Marks the [WaitReason.Approval] wait of [issueId] approved by [by] at [at] (m2-24), in its data under
     * [WaitingIssue.APPROVED_BY] and [WaitingIssue.APPROVED_AT]. A wait already approved, a wait sent back (m4-32), or a
     * wait for another reason, is left as it is. The record as it is after the call, null when [issueId] does not wait.
     * This default reads then writes; a store that can do it in one transaction overrides it.
     */
    public suspend fun approve(
        projectId: ProjectId,
        issueId: String,
        by: String,
        at: Instant,
    ): Result<WaitingIssue?> =
        waitingIssues(projectId).mapCatching { records ->
            val found = records.firstOrNull { it.issueId == issueId } ?: return@mapCatching null
            if (found.reason != WaitReason.Approval || found.approved || found.sentBack) return@mapCatching found
            val approved = found.copy(data = found.data + mapOf(WaitingIssue.APPROVED_BY to by, WaitingIssue.APPROVED_AT to at.toString()))
            startWaiting(approved).getOrThrow()
            approved
        }

    /**
     * Marks the [WaitReason.Ask] wait of [issueId] answered by [by] at [at] with [answer] (m4-52), in its data under
     * [WaitingIssue.ANSWER], [WaitingIssue.ANSWERED_BY] and [WaitingIssue.ANSWERED_AT]. A wait already answered, or a
     * wait for another reason, is left as it is. The record as it is after the call, null when [issueId] does not wait.
     * This default reads then writes; a store that can do it in one transaction overrides it.
     */
    public suspend fun answer(
        projectId: ProjectId,
        issueId: String,
        by: String,
        answer: String,
        at: Instant,
    ): Result<WaitingIssue?> =
        waitingIssues(projectId).mapCatching { records ->
            val found = records.firstOrNull { it.issueId == issueId } ?: return@mapCatching null
            if (found.reason != WaitReason.Ask || found.data[WaitingIssue.ANSWERED_AT] != null) return@mapCatching found
            val answered = found.copy(data = found.data + WaitingIssue.answerData(by, answer, at))
            startWaiting(answered).getOrThrow()
            answered
        }

    /**
     * Marks the [WaitReason.Approval] wait of [issueId], approved or not, sent back by [by] at [at] with [reason] (m4-32):
     * its approval keys go, so it is never delivered, and its data holds [WaitingIssue.SENT_BACK_REASON],
     * [WaitingIssue.SENT_BACK_BY] and [WaitingIssue.SENT_BACK_AT]. A wait already sent back, or a wait for another reason,
     * is left as it is. The record as it is after the call, null when [issueId] does not wait. This default reads then
     * writes; a store that can do it in one transaction overrides it.
     */
    public suspend fun sendBack(
        projectId: ProjectId,
        issueId: String,
        by: String,
        reason: String,
        at: Instant,
    ): Result<WaitingIssue?> =
        waitingIssues(projectId).mapCatching { records ->
            val found = records.firstOrNull { it.issueId == issueId } ?: return@mapCatching null
            if (found.reason != WaitReason.Approval || found.sentBack) return@mapCatching found
            val sent = found.sentBack(by, reason, at)
            startWaiting(sent).getOrThrow()
            sent
        }

    public companion object {
        /** Keeps nothing: every write succeeds, every read is empty. */
        public val NONE: WaitingIssues =
            object : WaitingIssues {
                override suspend fun startWaiting(issue: WaitingIssue): Result<Unit> = Result.success(Unit)

                override suspend fun waitingIssues(projectId: ProjectId): Result<List<WaitingIssue>> = Result.success(emptyList())

                override suspend fun endWaiting(
                    projectId: ProjectId,
                    issueId: String,
                ): Result<Unit> = Result.success(Unit)
            }
    }
}

/**
 * One waiting issue: what it waits for ([reason]), the [attemptId] and [runId] that started the wait, and
 * since when. [data] holds what the wait needs to go on after a restart, an opaque map the store keeps as it is:
 * the core reads and writes its keys in one place (bl-79). Once `loop approve` ran, an approval wait holds
 * [APPROVED_BY] and [APPROVED_AT] (m2-24), the two keys [WaitingIssues.approve] writes. Once `loop answer` ran, an ask
 * wait holds [ANSWER], [ANSWERED_BY] and [ANSWERED_AT] (m4-52), the keys [WaitingIssues.answer] writes. Once
 * `loop send-back` ran, an approval wait holds [SENT_BACK_REASON], [SENT_BACK_BY] and [SENT_BACK_AT] and no approval
 * (m4-32), what [WaitingIssues.sendBack] writes.
 */
public data class WaitingIssue(
    public val projectId: ProjectId,
    public val issueId: String,
    public val identifier: String,
    public val reason: WaitReason,
    public val attemptId: AttemptId?,
    public val runId: RunId?,
    public val since: Instant,
    public val data: Map<String, String> = emptyMap(),
) {
    init {
        require(projectId.value.isNotBlank()) { "Waiting issue needs a project id" }
        require(issueId.isNotBlank()) { "Waiting issue needs an issue id" }
    }

    /** True once a human approved this [WaitReason.Approval] wait (m2-24). */
    public val approved: Boolean get() = data[APPROVED_AT] != null

    /** True once `loop send-back` sent this [WaitReason.Approval] wait back (m4-32). */
    public val sentBack: Boolean get() = data[SENT_BACK_AT] != null

    /** This record sent back by [by] at [at] with [reason] (m4-32): its approval dropped, the send back keys added. */
    public fun sentBack(
        by: String,
        reason: String,
        at: Instant,
    ): WaitingIssue = copy(data = data - APPROVED_BY - APPROVED_AT + sendBackData(by, reason, at))

    public companion object {
        /** The data key of who approved an approval wait. */
        public const val APPROVED_BY: String = "approved_by"

        /** The data key of when an approval wait was approved, an ISO instant. */
        public const val APPROVED_AT: String = "approved_at"

        /** The data key of the answer `loop answer` gave to an ask wait (m4-52). */
        public const val ANSWER: String = "answer"

        /** The data key of who answered an ask wait (m4-52). */
        public const val ANSWERED_BY: String = "answered_by"

        /** The data key of when an ask wait was answered, an ISO instant (m4-52). */
        public const val ANSWERED_AT: String = "answered_at"

        /** The data keys [WaitingIssues.answer] writes: [answer] given by [by] at [at]. */
        public fun answerData(
            by: String,
            answer: String,
            at: Instant,
        ): Map<String, String> = mapOf(ANSWER to answer, ANSWERED_BY to by, ANSWERED_AT to at.toString())

        /** The data key of the reason `loop send-back` gave for an approval wait (m4-32). */
        public const val SENT_BACK_REASON: String = "sent_back_reason"

        /** The data key of who sent an approval wait back (m4-32). */
        public const val SENT_BACK_BY: String = "sent_back_by"

        /** The data key of when an approval wait was sent back, an ISO instant (m4-32). */
        public const val SENT_BACK_AT: String = "sent_back_at"

        /** The data keys [WaitingIssues.sendBack] adds: [reason] given by [by] at [at]. */
        public fun sendBackData(
            by: String,
            reason: String,
            at: Instant,
        ): Map<String, String> = mapOf(SENT_BACK_REASON to reason, SENT_BACK_BY to by, SENT_BACK_AT to at.toString())
    }
}

/** What an issue waits for, as the wire string stored and shown by `loop status`. */
@JvmInline
public value class WaitReason(
    public val wire: String,
) {
    public companion object {
        /** The checks of a submitted result are running. */
        public val Verify: WaitReason = WaitReason("verify")

        /** A verified result waits for a human's `loop approve` before it is delivered (m2-24). */
        public val Approval: WaitReason = WaitReason("approval")

        /** An approved pull request waits for its merge check and its merge (m2-26). */
        public val Merge: WaitReason = WaitReason("merge")

        /** An agent asked a human a question with `ask` (m4-31): the issue waits for the answer or a human move. */
        public val Ask: WaitReason = WaitReason("ask")
    }
}

/**
 * Stores the acceptance command list of each issue, frozen at its first dispatch in Loop mode (m2-09), so a
 * later attempt or a restored wait runs the commands its criteria held then, never a list rewritten in the issue
 * between attempts. A list is kept as a digest and a count, never as text: a command can hold a secret. One
 * record per issue. Like [EventSink], implementations return a failed [Result] instead of throwing.
 */
public interface FrozenAcceptanceCommands {
    /** Writes [commands] when the issue has no record yet. Returns the record in the store and whether this call wrote it. */
    public suspend fun freeze(commands: FrozenCommands): Result<Freeze>

    /** Records [digest] and [count] as the changed list of the issue, waiting for `loop resume`; nothing when it has no record. */
    public suspend fun markChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
        count: Int,
    ): Result<Unit>

    /**
     * Forgets the changed list of the issue, keeping its frozen list: a later verification found the frozen list
     * again, so nothing is left for `loop resume` to accept. Nothing when it has no record.
     */
    public suspend fun clearChanged(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit>

    /**
     * Makes the changed list of the issue its frozen list (`loop resume`), only when its digest is [digest], the
     * list the issue holds as the human resumes it. Returns the new record, null when no change waited or the
     * changed list is another one.
     */
    public suspend fun acceptChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
    ): Result<FrozenCommands?>

    public companion object {
        /** Keeps nothing: every freeze writes, so a list always matches itself; nothing is ever changed. */
        public val NONE: FrozenAcceptanceCommands =
            object : FrozenAcceptanceCommands {
                override suspend fun freeze(commands: FrozenCommands): Result<Freeze> = Result.success(Freeze(commands, written = true))

                override suspend fun markChanged(
                    projectId: ProjectId,
                    issueId: String,
                    digest: String,
                    count: Int,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun clearChanged(
                    projectId: ProjectId,
                    issueId: String,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun acceptChanged(
                    projectId: ProjectId,
                    issueId: String,
                    digest: String,
                ): Result<FrozenCommands?> = Result.success(null)
            }
    }
}

/**
 * The frozen acceptance command list of one issue: the [digest] of its commands and their [count], since
 * [frozenAt]. [changedDigest] and [changedCount] are the list a later verification found instead, null until
 * one differs; `loop resume` makes them the frozen list.
 */
public data class FrozenCommands(
    public val projectId: ProjectId,
    public val issueId: String,
    public val identifier: String,
    public val digest: String,
    public val count: Int,
    public val frozenAt: Instant,
    public val changedDigest: String? = null,
    public val changedCount: Int? = null,
) {
    init {
        require(projectId.value.isNotBlank()) { "Frozen commands need a project id" }
        require(issueId.isNotBlank()) { "Frozen commands need an issue id" }
        require((changedDigest == null) == (changedCount == null)) { "A changed list needs both its digest and its count" }
    }
}

/** What [FrozenAcceptanceCommands.freeze] found: the record in the store, and whether the call wrote it. */
public data class Freeze(
    public val stored: FrozenCommands,
    public val written: Boolean,
)
