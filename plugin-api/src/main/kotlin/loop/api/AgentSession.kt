package loop.api

import kotlinx.coroutines.flow.Flow

/**
 * An agent session that runs more than one turn for an attempt. It is one agent thread:
 * every turn runs under the same thread id, and the session id of turn `n` is
 * `<thread_id>-<turn_id>` with that turn's own id.
 *
 * The session holds the runner's state between turns. Usage stays absolute for the session so
 * far, so the session accumulates across invocations and each report replaces the last.
 */
public interface AgentSession {
    /**
     * Runs turn number [turn] (1-based) with [input] as the prompt or the continuation guidance.
     * Turn 1 starts with [RunnerEvent.SessionStarted], turns 2..n with [RunnerEvent.TurnStarted].
     * The flow is cold and ends with exactly one terminal event; cancelling the collection
     * kills the agent process.
     */
    public fun runTurn(
        input: String,
        turn: Int,
    ): Flow<RunnerEvent>

    /** Ends the session and releases what it holds. Safe to call after a failed or cancelled turn. */
    public suspend fun stop()

    /**
     * Ends the session like [stop], and gives [report] what the session read while it stopped, in the order it read it
     * (bl-132, m4-04): a wake still running between turns, a turn the runtime started on its own after a background
     * task's notification, may report usage after the last turn ended. Each [RunnerEvent.Usage] is absolute for the
     * session, like the usage of a turn. A [RunnerEvent.StopGraceExpired] says the stop grace ended before the runtime
     * exited, after the usage the runtime's lines had reported so far. Reports come before a failed stop throws. A
     * runner that reads nothing at its stop reports nothing: the default calls [stop].
     */
    public suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
        stop()
    }

    /**
     * Asks the runtime to end the turn that runs now at once, without ending the session (m3-12, the hard limit).
     * The turn's flow then reports the usage of the turn so far and ends with its terminal event, which Loop does not
     * count as a failure. It returns at once and never waits for the turn to end. A runner that cannot interrupt a
     * turn does nothing: Loop cancels the turn's flow after a short grace, and the turn's usage since the runtime's
     * last report is lost. Called only while a turn of this session is collected.
     */
    public fun interrupt() {}
}
