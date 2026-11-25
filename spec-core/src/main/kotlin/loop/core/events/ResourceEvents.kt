package loop.core.events

/**
 * m4-50: the resource of [KIND] and [RESOURCE_ID] was granted to the attempt of the event, at its request or when the
 * lock went to the oldest request of its queue.
 */
object ResourceGranted : EventDefinition("resource_granted") {
    const val KIND = "kind"
    const val RESOURCE_ID = "resource_id"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "avd", RESOURCE_ID to "api35")
}

/**
 * m4-50: the lock the attempt of the event held on the resource of [KIND] and [RESOURCE_ID] was freed, and why:
 * [RELEASED] by its holder, [ATTEMPT_ENDED] at the end of the attempt, whatever the end, or [ABANDONED] by the startup
 * sweep of a later run, for a lock a dead process left.
 */
object ResourceReleased : EventDefinition("resource_released") {
    const val KIND = ResourceGranted.KIND
    const val RESOURCE_ID = ResourceGranted.RESOURCE_ID
    const val REASON = "reason"
    const val RELEASED = "released"
    const val ATTEMPT_ENDED = "attempt_ended"
    const val ABANDONED = AttemptFinished.ABANDONED

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "avd", RESOURCE_ID to "api35", REASON to ATTEMPT_ENDED)
}
