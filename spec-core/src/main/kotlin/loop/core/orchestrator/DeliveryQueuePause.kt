package loop.core.orchestrator

import loop.api.AttemptId
import loop.core.delivery.AutoMerge
import loop.core.waiting.Wait
import kotlin.time.Instant

/**
 * The pause of a project's delivery queue (bl-175), held in memory only, like the runner hold (bl-39). The head's CI
 * wait ending in `timeout` pauses it, so an Actions outage does not cost each queued delivery its own timeout in turn.
 * While it holds, no merge wait starts its merge check. `loop approve` of any issue of the project, given at or after
 * the pause, ends it, and so does a restart, since a new process starts without it.
 */
internal class DeliveryQueuePause {
    /** The issue whose CI wait timed out, and when the queue paused. */
    data class Paused(
        val issueId: String,
        val identifier: String,
        val attemptId: AttemptId?,
        val at: Instant,
    )

    /** The pause in effect, null while the queue runs. */
    var paused: Paused? = null
        private set

    /** Pauses the queue after the CI timeout of [wait] at [at]. False when it was already paused, which it stays. */
    fun pause(
        wait: Wait,
        at: Instant,
    ): Boolean {
        if (paused != null) return false
        paused = Paused(wait.issueId, wait.identifier, wait.attemptId, at)
        return true
    }

    /**
     * Ends the pause when one of [approvals] was approved by a human (not [AutoMerge.AUTO]) at or after it began; the
     * pause that ended, null when the queue was not paused or stays paused. An approval from before the pause, still
     * waiting for its delivery, does not end it.
     */
    fun resumeOnApproval(approvals: List<Wait.Approval>): Paused? {
        val current = paused ?: return null
        val approved =
            approvals.any { wait ->
                val at = wait.approvedAt
                wait.approved && wait.approvedBy != AutoMerge.AUTO && at != null && at >= current.at
            }
        if (!approved) return null
        paused = null
        return current
    }
}
