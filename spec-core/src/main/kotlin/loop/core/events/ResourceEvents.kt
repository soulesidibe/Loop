package loop.core.events

/**
 * m4-25: the attempt of the event asked for the resource of [KIND] and [RESOURCE_ID] with `request_resource`, the
 * first time; a call that only checks a request already made records nothing.
 */
object ResourceRequested : EventDefinition("resource_requested") {
    const val KIND = ResourceGranted.KIND
    const val RESOURCE_ID = ResourceGranted.RESOURCE_ID

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "avd", RESOURCE_ID to "api35")
}

/**
 * m4-25: the request of the attempt of the event for the resource of [KIND] and [RESOURCE_ID] joined its queue at
 * [POSITION], 1 being the next one granted, since another attempt holds it.
 */
object ResourceQueued : EventDefinition("resource_queued") {
    const val KIND = ResourceGranted.KIND
    const val RESOURCE_ID = ResourceGranted.RESOURCE_ID
    const val POSITION = "position"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "avd", RESOURCE_ID to "api35", POSITION to "1")
}

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
