package loop.core.orchestrator

/**
 * The turns of one attempt as its limits count them (m4-03, bl-155). An ordinary turn counts against
 * `agent.max_turns`. A wake turn, one that read the notification of a background task run started in an earlier turn
 * (the runner says so with `RunnerEvent.WakeTurn`), never does: it counts against `agent.max_wake_turns` instead, so a
 * chatty background task cannot keep an attempt alive forever. Turns keep counting across the sessions of an attempt.
 */
internal class TurnCount(
    private val maxTurns: Int,
    private val maxWakeTurns: Int,
) {
    init {
        require(maxTurns >= 1) { "max_turns must be at least 1, got $maxTurns" }
        require(maxWakeTurns >= 1) { "max_wake_turns must be at least 1, got $maxWakeTurns" }
    }

    /** The ordinary turns that ended so far. */
    var counted = 0
        private set

    /** The wake turns that ended so far. */
    var wakes = 0
        private set

    /** The number the next ordinary turn has among the `agent.max_turns`, as the continuation guidance says it. */
    val next: Int get() = counted + 1

    /**
     * Counts a turn that just ended, a [wake] turn or an ordinary one. Returns the exit reason of the limit it reached,
     * [ExitReason.MAX_TURNS] or [ExitReason.MAX_WAKE_TURNS], or null while the attempt may run another turn.
     */
    fun end(wake: Boolean): String? {
        if (wake) wakes++ else counted++
        return limit()
    }

    /** The limit the turns so far reached, null while none is. */
    fun limit(): String? =
        when {
            counted >= maxTurns -> ExitReason.MAX_TURNS
            wakes >= maxWakeTurns -> ExitReason.MAX_WAKE_TURNS
            else -> null
        }

    companion object {
        /** True for the exit reason of a turn limit, which a refutation in the last turn replaces (m2-06). */
        fun isLimit(reason: String?): Boolean = reason == ExitReason.MAX_TURNS || reason == ExitReason.MAX_WAKE_TURNS
    }
}
