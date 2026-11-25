package loop.core.resources

import loop.api.AttemptId
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceLockStore
import loop.api.ResourceLockUpdate
import loop.api.ResourceRequest
import loop.api.RunId
import loop.core.events.ResourceGranted
import loop.core.events.ResourceQueued
import loop.core.events.ResourceReleased
import loop.core.events.ResourceRequested
import kotlin.time.Clock

/**
 * The resource lock calls (m4-50, PLAN 6.9), one API for every process that takes, queues or frees a lock: `loop run`
 * and the short lived `loop tool mcp` (m4-25). Each call is one change of the machine's rows in [store], under its
 * write lock, by the rules of [ResourceQueue], so two processes never grant one resource twice. Each lock granted
 * records `resource_granted` and each lock freed `resource_released` with its reason, in [sink], for the run [runId]
 * of the caller; an event the sink refuses goes to [unrecorded] and the call still succeeds, since its rows are
 * written. A failed call wrote nothing.
 */
class ResourceLocks(
    private val store: ResourceLockStore,
    private val sink: EventSink,
    private val clock: Clock,
    private val runId: RunId?,
    private val unrecorded: (RecordedEvent, Throwable) -> Unit = { _, _ -> },
) {
    /** Every lock and queue row of the machine. */
    suspend fun rows(): Result<ResourceLockRows> = store.resourceLockRows()

    /** The attempt [attemptId] of the issue [issueId] in [projectId] asks for the resource of [kind] and [resourceId] now. */
    suspend fun request(
        projectId: ProjectId,
        issueId: String,
        issueIdentifier: String,
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
    ): Result<ResourceGrant> {
        val request = ResourceRequest(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, clock.now())
        return change(reason = ResourceReleased.RELEASED) { rows ->
            ResourceQueue.request(rows, request, clock.now()).let { it.change to it.grant }
        }
    }

    /**
     * `request_resource` (m4-25): the attempt [attemptId] of the issue [issueId] in [projectId] asks for the resource of
     * [kind] and [resourceId], or checks a request it made before, by the rules of [ResourceAnswers.ask]. A new request
     * records `resource_requested`, then `resource_granted` or `resource_queued` with its position.
     */
    suspend fun ask(
        projectId: ProjectId,
        issueId: String,
        issueIdentifier: String,
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
    ): Result<ResourceAnswer> {
        val request = ResourceRequest(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, clock.now())
        val ids = mapOf(ResourceRequested.KIND to kind, ResourceRequested.RESOURCE_ID to resourceId)
        val subject = request.granted()
        return change(
            reason = ResourceReleased.RELEASED,
            first = { answer -> if (answer.requested) listOf(event(ResourceRequested.event(clock.now(), projectId, ids), subject)) else emptyList() },
            last = { answer ->
                if (answer is ResourceAnswer.Queued && answer.requested) {
                    val data = ids + (ResourceQueued.POSITION to answer.position.toString())
                    listOf(event(ResourceQueued.event(clock.now(), projectId, data), subject))
                } else {
                    emptyList()
                }
            },
        ) { rows -> ResourceAnswers.ask(rows, request, clock.now()).let { it.change to it.answer } }
    }

    /**
     * `release_resource` (m4-25): the attempt [attemptId] marks its lock of [kind] and [resourceId] for release, by the
     * rules of [ResourceAnswers.release]. Nothing is freed or recorded here: `loop run` gives the resource back and
     * frees the lock on its next tick, which records `resource_released`.
     */
    suspend fun requestRelease(
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
    ): Result<ReleaseAnswer> =
        change(reason = ResourceReleased.RELEASED) { rows ->
            ResourceAnswers.release(rows, kind, resourceId, attemptId, clock.now()).let { it.change to it.answer }
        }

    /** Writes on [lock] what `loop run` got from its prepare (m4-25), when the same attempt still holds it unprepared. */
    suspend fun prepared(
        lock: ResourceLock,
        outcome: Result<Map<String, String>>,
    ): Result<Boolean> =
        change(reason = ResourceReleased.RELEASED) { rows -> ResourceAnswers.prepared(rows, lock, outcome).let { it.change to it.answer } }

    /** The attempt [attemptId] releases its lock of [kind] and [resourceId], which goes to the oldest request of its queue. */
    suspend fun release(
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
    ): Result<ResourceChange> =
        change(reason = ResourceReleased.RELEASED) { rows ->
            ResourceQueue.release(rows, kind, resourceId, attemptId, clock.now()).let { it to it }
        }

    /** Frees every lock and queue row of the attempt [attemptId], each freed lock recorded with [reason]. */
    suspend fun freeAttempt(
        attemptId: AttemptId,
        reason: String,
    ): Result<ResourceChange> = change(reason) { rows -> ResourceQueue.freeAttempt(rows, attemptId, clock.now()).let { it to it } }

    /** Frees every lock and queue row of the project [projectId], each freed lock recorded with [reason]. */
    suspend fun freeProject(
        projectId: ProjectId,
        reason: String,
    ): Result<ResourceChange> = change(reason) { rows -> ResourceQueue.freeProject(rows, projectId, clock.now()).let { it to it } }

    /**
     * Applies [apply] to the rows in one store change, then records the events [first] gives for its value, what it
     * released, with [reason], what it granted, and the events [last] gives.
     */
    private suspend fun <T> change(
        reason: String,
        first: (T) -> List<RecordedEvent> = { emptyList() },
        last: (T) -> List<RecordedEvent> = { emptyList() },
        apply: (ResourceLockRows) -> Pair<ResourceChange, T>,
    ): Result<T> {
        var applied: ResourceChange? = null
        val result =
            store.updateResourceLocks { rows ->
                val (change, value) = apply(rows)
                applied = change
                ResourceLockUpdate(change.rows, value)
            }
        result.onSuccess { value ->
            val change = checkNotNull(applied)
            first(value).forEach { record(it) }
            change.released.forEach { record(released(it, reason)) }
            change.granted.forEach { record(granted(it)) }
            last(value).forEach { record(it) }
        }
        return result
    }

    /** [event] given the run and the holder ids of [lock]. */
    private fun event(
        event: RecordedEvent,
        lock: ResourceLock,
    ): RecordedEvent = event.copy(runId = runId, issueId = lock.issueId, issueIdentifier = lock.issueIdentifier, attemptId = lock.attemptId)

    private fun ResourceRequest.granted(): ResourceLock =
        ResourceLock(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, grantedAt = requestedAt)

    private val ResourceAnswer.requested: Boolean
        get() =
            when (this) {
                is ResourceAnswer.Preparing -> requested
                is ResourceAnswer.Queued -> requested
                else -> false
            }

    private suspend fun record(event: RecordedEvent) {
        sink.record(event).onFailure { unrecorded(event, it) }
    }

    private fun released(
        lock: ResourceLock,
        reason: String,
    ): RecordedEvent =
        ResourceReleased.event(
            ts = clock.now(),
            projectId = lock.projectId,
            data =
                mapOf(
                    ResourceReleased.KIND to lock.kind,
                    ResourceReleased.RESOURCE_ID to lock.resourceId,
                    ResourceReleased.REASON to reason,
                ),
            runId = runId,
            issueId = lock.issueId,
            issueIdentifier = lock.issueIdentifier,
            attemptId = lock.attemptId,
        )

    private fun granted(lock: ResourceLock): RecordedEvent =
        ResourceGranted.event(
            ts = clock.now(),
            projectId = lock.projectId,
            data = mapOf(ResourceGranted.KIND to lock.kind, ResourceGranted.RESOURCE_ID to lock.resourceId),
            runId = runId,
            issueId = lock.issueId,
            issueIdentifier = lock.issueIdentifier,
            attemptId = lock.attemptId,
        )
}
