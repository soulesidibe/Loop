package loop.core.orchestrator

import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.AttemptId

/**
 * Whether the next attempt of an issue continues the agent session of its last one (m4-46, story 108). Since m4-08 a
 * lost network fails a turn as `runner_unavailable`, which [RunnerHold] takes without a `max_attempts` slot. When the
 * hold lifts, the next attempt resumes the runtime session of the held one only when that was cheap: the hold took
 * the attempt ([RunnerHold.Outcome.Held], not the third hit counted as an ordinary failure), its last session reported
 * a runtime session id, and that session's last occupancy, as a fraction of its model's window, is below
 * `context.soft_limit` of the next attempt. Any other case starts a fresh session with the session brief, as a rotation
 * does. A rotation never comes here: it always opens a fresh session.
 *
 * The prompt is the same either way, so a runner that cannot resume ([AgentRunner.resumeSession] returns null, Codex
 * for one) opens a fresh session with the brief, as before m4-46.
 *
 * Kept in memory only, like [RunnerHold]: a restart forgets it, and the next attempt starts fresh. Each choice is used
 * once. Thread safe.
 */
internal class NetworkResume {
    /** What the next attempt of an issue opens. */
    sealed interface Choice {
        /** Continue [runtimeSessionId], whose last occupancy was [fraction] of its window. */
        data class Resume(
            val runtimeSessionId: String,
            val fraction: Double,
        ) : Choice

        /** A fresh session with the session brief, for [reason]. */
        data class Fresh(
            val reason: String,
        ) : Choice
    }

    /** The last session of one attempt of an issue, and whether the hold took that attempt. */
    private data class LastSession(
        val attemptId: AttemptId,
        val runnerKind: String,
        val runtimeSessionId: String?,
        val fraction: Double?,
        val held: Boolean = false,
    )

    private val sessions = mutableMapOf<String, LastSession>()

    /**
     * The attempt [attemptId] of [issueId], run by the runner of [runnerKind], stopped its last session, which reported
     * [runtimeSessionId] and whose last occupancy was [fraction] of its window; null when it reported none or its window
     * is unknown. It replaces what an earlier attempt of the issue left.
     */
    @Synchronized
    fun sessionEnded(
        issueId: String,
        attemptId: AttemptId,
        runnerKind: String,
        runtimeSessionId: String?,
        fraction: Double?,
    ) {
        sessions[issueId] = LastSession(attemptId, runnerKind, runtimeSessionId, fraction)
    }

    /**
     * The attempt [attemptId] of [issueId] ended; [held] is true when the run level hold took it. An attempt the hold did
     * not take, or one that stopped no session, forgets the issue's last session, so its next attempt starts fresh.
     */
    @Synchronized
    fun attemptEnded(
        issueId: String,
        attemptId: AttemptId?,
        held: Boolean,
    ) {
        val last = sessions[issueId]
        if (!held || last == null || last.attemptId != attemptId) {
            sessions -= issueId
            return
        }
        sessions[issueId] = last.copy(held = true)
    }

    /** The choice for the next attempt of [issueId], run by [runnerKind] under [softLimit]. It is used up. */
    @Synchronized
    fun choose(
        issueId: String,
        runnerKind: String,
        softLimit: Double,
    ): Choice {
        val last = sessions.remove(issueId)
        return when {
            last == null || !last.held -> Choice.Fresh(NOT_HELD)
            last.runnerKind != runnerKind -> Choice.Fresh(RUNNER_CHANGED)
            last.runtimeSessionId == null -> Choice.Fresh(NO_RUNTIME_SESSION)
            last.fraction == null -> Choice.Fresh(NO_OCCUPANCY)
            last.fraction >= softLimit -> Choice.Fresh(AT_SOFT_LIMIT)
            else -> Choice.Resume(last.runtimeSessionId, last.fraction)
        }
    }

    companion object {
        /** The last attempt was not held: a first dispatch, an ordinary failure, a restart since. */
        const val NOT_HELD = "not_held"

        /** The held attempt ran another runner kind than the next one. */
        const val RUNNER_CHANGED = "runner_changed"

        /** The held session reported no runtime session id. */
        const val NO_RUNTIME_SESSION = "no_runtime_session"

        /** The held session reported no occupancy, or its model's window is unknown. */
        const val NO_OCCUPANCY = "no_occupancy"

        /** The held session's last occupancy was at or above the soft limit. */
        const val AT_SOFT_LIMIT = "at_soft_limit"

        /**
         * The session of the attempt [ctx] for [choice]: [AgentRunner.resumeSession] for a resume, else, or when the
         * runner cannot resume, [AgentRunner.openSession]. Null for a runner without continuation.
         */
        fun open(
            runner: AgentRunner,
            ctx: AttemptContext,
            choice: Choice,
        ): AgentSession? = (choice as? Choice.Resume)?.let { runner.resumeSession(ctx, it.runtimeSessionId) } ?: runner.openSession(ctx)
    }
}
