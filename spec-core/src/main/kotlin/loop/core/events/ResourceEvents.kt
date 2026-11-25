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
 * [POSITION], 1 being the next one granted. Since m4-26 [REASON] says why it waits: [HELD], another holder has it,
 * [LIMIT], a grant would pass the machine limit of its pool ([loop.core.resources.ResourceLimits]), or [DIRTY], its
 * read only check failed and it is lent to nobody until a check passes.
 */
object ResourceQueued : EventDefinition("resource_queued") {
    const val KIND = ResourceGranted.KIND
    const val RESOURCE_ID = ResourceGranted.RESOURCE_ID
    const val POSITION = "position"
    const val REASON = "reason"
    const val HELD = "held"
    const val LIMIT = "limit"
    const val DIRTY = "dirty"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "avd", RESOURCE_ID to "api35", POSITION to "1", REASON to LIMIT)
}

/**
 * m4-26: the read only check of the resource of [KIND] and [RESOURCE_ID] failed after the attempt of the event held
 * it, so the resource is marked dirty and lent to nobody until a check passes again. [CHECK] is [READ_ONLY_CHECK], the
 * declared command still failing at the end of the attempt after `restore` ran once, or [PROVIDER], the provider's own
 * check after its `release`; [DIFFERENCE] is what it found, cut.
 */
object ResourceCheckFailed : EventDefinition("resource_check_failed") {
    const val KIND = ResourceGranted.KIND
    const val RESOURCE_ID = ResourceGranted.RESOURCE_ID
    const val CHECK = "check"
    const val DIFFERENCE = "difference"
    const val READ_ONLY_CHECK = "read_only_check"
    const val PROVIDER = "provider"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(KIND to "ios-simulator", RESOURCE_ID to "iphone-16", CHECK to PROVIDER, DIFFERENCE to "app installed")
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
