package loop.api

import kotlin.time.Instant

/**
 * Token counts in the ledger (m2-19): input, output, cache read and cache creation tokens. Every value comes
 * from the runner's absolute totals, never from a sum of its reports.
 */
public data class LedgerTokens(
    public val input: Long,
    public val output: Long,
    public val cacheRead: Long,
    public val cacheCreation: Long,
) {
    /** The four counts together, cache reads included, like the attempt total of the token cap. */
    public val total: Long get() = input + output + cacheRead + cacheCreation
}

/**
 * What the ledger knows of the attempt's setup: the [tracker] kind, the issue [kind] (null in Symphony mode), the
 * [runner] kind, its [model], the [templateVersion] (SHA-256 of the prompt template) and the [configVersion]
 * (SHA-256 of `WORKFLOW.md`, null when the workflow was not read from a file).
 *
 * Since bl-147 [model] is the model the runtime reported, as `context.windows` keys it, `<provider>/<model>`: on a
 * line, its session's latest report; on a row, the attempt's latest. Without a report it is the runner's configured
 * model as written, null when the runner has none set. So [model] can differ between the lines of one attempt, the
 * other keys never do.
 */
public data class LedgerSetup(
    public val tracker: String,
    public val kind: String?,
    public val runner: String,
    public val model: String?,
    public val templateVersion: String,
    public val configVersion: String?,
)

/**
 * The peak context occupancy of one agent session (plan 8.3, m3-09): the highest input size of its main session
 * requests, cached tokens included, in [tokens], and the [window] `context.windows` gives its model, null when the
 * model has none. Never a sum.
 */
public data class LedgerPeak(
    public val tokens: Long,
    public val window: Long?,
) {
    init {
        require(tokens >= 0) { "A peak occupancy is never negative" }
    }

    /** [tokens] over [window], null without a window. */
    public val fraction: Double? get() = window?.takeIf { it > 0 }?.let { tokens.toDouble() / it }
}

/**
 * One ledger line, written when a turn of an attempt ends (m2-19). [tokens] and [costUsd] are this turn's share:
 * the absolute totals at its end minus those at the end of the previous turn. [attemptTokens] and
 * [attemptCostUsd] are the attempt's absolute totals so far. Each is null when the runner reported nothing to
 * build it from, never zero. [outcome] is `completed` or `failed`.
 *
 * Since m3-09 the line names its agent session, [threadId] (the spec's [sessionId] changes with every turn), and
 * [peak], the session's peak occupancy at the end of the turn, so the last line of a session holds its peak. [peak]
 * is null while the session has no occupancy report, as under a runner that does not declare occupancy, never zero.
 *
 * Since m3-11 it carries [rotations], the attempt's rotations at the end of the turn, so the attempt's last line holds
 * its rotation count.
 *
 * Since m3-12 it carries [interrupted], `hard_limit` for a turn Loop interrupted because its session reached
 * `context.hard_limit` of its window, null for any other turn. An interrupted turn is not a failed one: its [outcome]
 * stays `completed`.
 *
 * Since m4-03 it carries [wake], true for a wake turn: one that read the notification of a background task run an
 * earlier turn started. Its usage counts like any turn's, but it does not count against `agent.max_turns`.
 */
public data class LedgerTurn(
    public val ts: Instant,
    public val projectId: ProjectId,
    public val runId: RunId?,
    public val issueId: String,
    public val identifier: String,
    public val attemptId: AttemptId,
    public val sessionId: SessionId?,
    public val turnId: TurnId?,
    public val turn: Int,
    public val outcome: String,
    public val setup: LedgerSetup,
    public val tokens: LedgerTokens?,
    public val attemptTokens: LedgerTokens?,
    public val costUsd: Double?,
    public val attemptCostUsd: Double?,
    public val wallMs: Long,
    public val threadId: String? = null,
    public val peak: LedgerPeak? = null,
    public val rotations: Int = 0,
    public val interrupted: String? = null,
    public val wake: Boolean = false,
)

/**
 * The row of one finished attempt (m2-19), written when it records `attempt_finished`. [outcome] is
 * `succeeded`, `failed` or `canceled`. [tokens] and [costUsd] are the latest absolute totals the runner
 * reported, null when it reported none. [checks] counts the checks the attempt's verifications ran and
 * [verdict] is its latest verdict, both null when it submitted no result. [guards] stays null until Loop has
 * guards. The attempt's number and its human touches are counted by the store when it writes the row.
 */
public data class LedgerAttempt(
    public val projectId: ProjectId,
    public val runId: RunId?,
    public val issueId: String,
    public val identifier: String,
    public val attemptId: AttemptId,
    public val setup: LedgerSetup,
    public val startedAt: Instant,
    public val finishedAt: Instant,
    public val outcome: String,
    public val reason: String?,
    public val turns: Int,
    public val issueState: String?,
    public val tokens: LedgerTokens?,
    public val costUsd: Double?,
    public val checks: Int?,
    public val guards: Int?,
    public val verdict: String?,
) {
    init {
        require(projectId.value.isNotBlank()) { "Ledger attempt needs a project id" }
        require(issueId.isNotBlank()) { "Ledger attempt needs an issue id" }
    }

    /** The attempt's wall time, from its start to its end. */
    public val wallMs: Long get() = (finishedAt - startedAt).inWholeMilliseconds
}

/**
 * A stored attempt row: the [attempt], its 1 based [number] among the rows of its issue, and its [humanTouches],
 * the `human_touch` events of its issue recorded after those the previous row of the issue counted.
 */
public data class LedgerRow(
    public val attempt: LedgerAttempt,
    public val number: Int,
    public val humanTouches: Int,
)

/** Appends the ledger line of each turn (m2-19). Implementations return a failed [Result] instead of throwing. */
public fun interface TurnLedger {
    public fun append(turn: LedgerTurn): Result<Unit>

    public companion object {
        /** Keeps nothing. */
        public val NONE: TurnLedger = TurnLedger { Result.success(Unit) }
    }
}

/** Stores the row of each finished attempt (m2-19). Like [EventSink], implementations return a failed [Result] instead of throwing. */
public interface AttemptLedger {
    /** Writes the row of [attempt], counting its number and human touches; a second row for the same attempt id is ignored. */
    public suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit>

    /** Every row of [issueId] in [projectId], oldest first. */
    public suspend fun attemptRows(
        projectId: ProjectId,
        issueId: String,
    ): Result<List<LedgerRow>>

    /** Every row of [projectId] whose attempt finished after [since], oldest first; the spend budgets of a day read it (m2-20). */
    public suspend fun attemptRowsSince(
        projectId: ProjectId,
        since: Instant,
    ): Result<List<LedgerRow>>

    public companion object {
        /** Keeps nothing: every write succeeds, every read is empty. */
        public val NONE: AttemptLedger =
            object : AttemptLedger {
                override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> = Result.success(Unit)

                override suspend fun attemptRows(
                    projectId: ProjectId,
                    issueId: String,
                ): Result<List<LedgerRow>> = Result.success(emptyList())

                override suspend fun attemptRowsSince(
                    projectId: ProjectId,
                    since: Instant,
                ): Result<List<LedgerRow>> = Result.success(emptyList())
            }
    }
}

/**
 * A moment a human had to act on an issue (plan glossary), recorded as a `human_touch` event with its [TOUCH]
 * kind. The ledger counts them per attempt row.
 */
public object HumanTouch {
    /** The event kind. */
    public const val EVENT: String = "human_touch"

    /** The data key naming which touch it was. */
    public const val TOUCH: String = "touch"

    /** `loop resume` made a parked issue dispatchable again. */
    public const val RESUME: String = "resume"

    /** `loop approve` approved a delivery. */
    public const val APPROVE: String = "approve"

    /** `loop answer` answered an agent's question (m4-52). */
    public const val ANSWER: String = "answer"

    /** `loop send-back` sent a waiting delivery back with a reason (m4-32). */
    public const val SEND_BACK: String = "send_back"

    /** A human changed the issue in the tracker: its state, its labels, or removed it. */
    public const val MOVE: String = "move"
}
