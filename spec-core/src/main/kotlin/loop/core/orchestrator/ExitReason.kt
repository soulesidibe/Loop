package loop.core.orchestrator

/**
 * Why an attempt ended normally, or why a retry was dropped: one vocabulary for `exit_reason` on
 * `attempt_finished` and `reason` on `retry_dropped`. The no progress counter (m1-27) skips a
 * [NOT_FOUND] exit and counts every other normal exit.
 */
internal object ExitReason {
    /** The issue is gone from the tracker. */
    const val NOT_FOUND = "not_found"
    const val TERMINAL = "terminal"
    const val NOT_ACTIVE = "not_active"

    /** Active, but without a required label, or blocked (not dispatchable). */
    const val NOT_ELIGIBLE = "not_eligible"

    /** The turn count reached `agent.max_turns`. */
    const val MAX_TURNS = "max_turns"

    /** The runner has no session, so the attempt ran one turn. */
    const val NO_CONTINUATION = "no_continuation"

    /** The verified result waits for `loop approve` under the `human` delivery policy (m2-24); no continuation follows. */
    const val WAITING_APPROVAL = "waiting_approval"

    /** The attempt failed because the by-id refresh after a turn failed (spec 16.5). */
    const val REFRESH_ERROR = "issue_state_refresh_error"
}
