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
import loop.core.events.ResourceReleased
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

    /** Applies [apply] to the rows in one store change, then records what it released, with [reason], and granted. */
    private suspend fun <T> change(
        reason: String,
        apply: (ResourceLockRows) -> Pair<ResourceChange, T>,
    ): Result<T> {
        var applied: ResourceChange? = null
        val result =
            store.updateResourceLocks { rows ->
                val (change, value) = apply(rows)
                applied = change
                ResourceLockUpdate(change.rows, value)
            }
        result.onSuccess {
            val change = checkNotNull(applied)
            change.released.forEach { record(released(it, reason)) }
            change.granted.forEach { record(granted(it)) }
        }
        return result
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
