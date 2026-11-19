package loop.api

import kotlin.time.Duration
import kotlin.time.Instant

/** An event a runner emits while it executes an attempt. */
public sealed interface RunnerEvent {
    /**
     * The agent session started; [threadId] and [turnId] compose [sessionId] as `<thread_id>-<turn_id>`.
     * [runtimeSessionId] is the agent runtime's own session id (for example Claude Code's), when it has one.
     */
    public data class SessionStarted(
        public val sessionId: SessionId,
        public val threadId: String,
        public val turnId: TurnId,
        public val runtimeSessionId: String? = null,
    ) : RunnerEvent

    /**
     * Turn [turn] (2..n) of an [AgentSession] started on the thread [threadId] of
     * [SessionStarted], with its own [turnId]; [sessionId] is `<thread_id>-<turn_id>`.
     * Turn 1 emits [SessionStarted] instead, so a thread has one `SessionStarted`.
     */
    public data class TurnStarted(
        public val sessionId: SessionId,
        public val threadId: String,
        public val turnId: TurnId,
        public val turn: Int,
    ) : RunnerEvent

    /** One line the agent process wrote on [stream]. */
    public data class Output(
        public val stream: Stream,
        public val line: String,
    ) : RunnerEvent {
        /** The process stream a line came from. */
        public enum class Stream {
            Stdout,
            Stderr,
        }
    }

    /**
     * Token usage of the agent session. The values are absolute totals for the attempt's agent
     * session so far, never deltas: a new report replaces the last one, it never adds to it.
     * [totalCostUsd] is null when the runtime does not report a cost.
     */
    public data class Usage(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val inputTokens: Long,
        public val outputTokens: Long,
        public val cacheReadInputTokens: Long,
        public val cacheCreationInputTokens: Long,
        public val totalCostUsd: Double?,
    ) : RunnerEvent

    /**
     * A running estimate of the session's tokens during a turn, before the turn's [Usage] (m1-38). The
     * values are absolute for the session so far, like [Usage]: the real totals of the earlier turns plus
     * what the runtime reported so far in this turn. The turn's [Usage] replaces it. Loop uses it only
     * for token thresholds: it never stops or parks anything. A runner that cannot estimate never emits it.
     */
    public data class UsageEstimate(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val inputTokens: Long,
        public val outputTokens: Long,
        public val cacheReadInputTokens: Long,
        public val cacheCreationInputTokens: Long,
    ) : RunnerEvent

    /**
     * The context occupancy of the main session during turn [turnId] (plan 8.3, m3-06), written `context_usage`.
     *
     * [occupancyTokens] is the input size of the latest model request of the main session, cached tokens included
     * (input, cache read and cache creation tokens of that one request), never a cumulative total: a new report
     * replaces the last one and may be smaller, for example after the runtime trims its context. It covers the main
     * session only; a subagent's window is its own and is not counted. Spend never reads it, spend reads [Usage].
     * Only a runner that declares [RunnerCapabilities.occupancy] emits it.
     *
     * [model] is the model of that request as `context.windows` keys it, `provider/model`, for example
     * `anthropic/claude-opus-5-5` (m3-08): Loop looks the window up with it. Null when the runtime did not name its
     * model; Loop then reports the occupancy in tokens, with no fraction.
     */
    public data class ContextUsage(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val occupancyTokens: Long,
        public val model: String? = null,
    ) : RunnerEvent

    /**
     * The runtime's latest rate limit snapshot of its account during turn [turnId] (conformance 17.5 item 13, m3-21),
     * written `rate_limit`. It reports, it never stops: the runtime itself refuses a turn past its limit, and that
     * refusal is a [TurnFailed]. [limitId] and [limitName] name the limit when the runtime does, [primary] and
     * [secondary] are its windows, null when the update did not carry them, and [reached] is the runtime's own word
     * for a limit it says is reached, for example `rate_limit_reached`, null while none is. A runner emits it only
     * when the snapshot changed since the last one, so a repeated identical update is recorded once.
     */
    public data class RateLimit(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val limitId: String?,
        public val limitName: String?,
        public val primary: Window?,
        public val secondary: Window?,
        public val reached: String?,
    ) : RunnerEvent {
        /**
         * One rate limit window: [usedPercent] of it is used, it spans [windowMinutes] and resets at [resetsAt], each
         * null when the runtime did not say.
         */
        public data class Window(
            public val usedPercent: Int,
            public val windowMinutes: Long?,
            public val resetsAt: Instant?,
        )
    }

    /** The turn ended normally with [exitCode]. */
    public data class TurnCompleted(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val exitCode: Int,
    ) : RunnerEvent

    /**
     * The turn failed; [exitCode] is null when the process never exited on its own.
     *
     * [category] is null for an ordinary failure, which counts toward `retries.max_attempts`. A runner sets
     * [Category.RunnerUnavailable] only when the runtime's own error result says the runner cannot reach its model
     * (an authentication or access refusal, an expired login, a rate limit or an overload), never from text the agent
     * printed: such a failure says nothing about the issue, takes no `max_attempts` slot and holds dispatch for the
     * whole run (bl-39). [retryAfter] is how long the runtime asked to wait, null when it gave no time; it is read
     * only with a category.
     */
    public data class TurnFailed(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val exitCode: Int?,
        public val reason: String,
        public val category: Category? = null,
        public val retryAfter: Duration? = null,
    ) : RunnerEvent {
        /** The kind of a failure that is not about the issue; [wire] is how events record it. */
        public enum class Category(
            public val wire: String,
        ) {
            /** The runner cannot use its model right now: 401, 403, an expired login, 429 or an overload. */
            RunnerUnavailable("runner_unavailable"),
        }
    }

    /**
     * The agent asked for input it cannot get in an unattended run. The attempt
     * fails with [reason], which is recorded so it shows in `loop trace`.
     */
    public data class TurnInputRequired(
        public val sessionId: SessionId,
        public val turnId: TurnId,
        public val reason: String,
    ) : RunnerEvent
}
