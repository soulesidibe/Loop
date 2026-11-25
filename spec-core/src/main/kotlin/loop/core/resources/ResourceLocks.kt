package loop.core.resources

import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceLockStore
import loop.api.ResourceLockUpdate
import loop.api.ResourceRequest
import loop.api.RunId
import loop.core.events.ResourceCheckFailed
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
     * A check that holds a resource (m4-26): the holder [holder] of the issue [issueId] in [projectId] asks for any
     * resource of [kind] among [ids], or checks the request it made before, by the rules of [ResourceQueue.requestAny].
     * A new request records `resource_requested` for each id, then `resource_granted`, or `resource_queued` for each
     * id with its position and reason; a call that only checks records nothing new.
     */
    suspend fun requestAny(
        projectId: ProjectId,
        issueId: String,
        issueIdentifier: String,
        holder: AttemptId,
        kind: String,
        ids: List<String>,
    ): Result<ResourceGrant> {
        val template = ResourceRequest(kind, ids.firstOrNull().orEmpty(), projectId, issueId, issueIdentifier, holder, clock.now())
        var requested = false
        var places = emptyList<Pair<String, ResourceGrant.Queued>>()
        return change(
            reason = ResourceReleased.RELEASED,
            first = { _ ->
                if (!requested) return@change emptyList()
                ids.distinct().map { id ->
                    val data = mapOf(ResourceRequested.KIND to kind, ResourceRequested.RESOURCE_ID to id)
                    event(ResourceRequested.event(clock.now(), projectId, data), template.copy(resourceId = id).granted())
                }
            },
            last = { grant ->
                if (!requested || grant !is ResourceGrant.Queued) return@change emptyList()
                places.map { (id, place) ->
                    val data =
                        mapOf(
                            ResourceQueued.KIND to kind,
                            ResourceQueued.RESOURCE_ID to id,
                            ResourceQueued.POSITION to place.position.toString(),
                            ResourceQueued.REASON to place.reason,
                        )
                    event(ResourceQueued.event(clock.now(), projectId, data), template.copy(resourceId = id).granted())
                }
            },
        ) { rows ->
            requested = rows.locks.none { it.kind == kind && it.attemptId == holder } &&
                rows.queue.none { it.kind == kind && it.attemptId == holder }
            val asked = ResourceQueue.requestAny(rows, template, ids, clock.now())
            places = asked.places
            asked.change to asked.grant
        }
    }

    /**
     * `request_resource` (m4-25): the attempt [attemptId] of the issue [issueId] in [projectId] asks for the resource of
     * [kind] and [resourceId], or checks a request it made before, by the rules of [ResourceAnswers.ask]. A new request
     * records `resource_requested`, then `resource_granted` or `resource_queued` with its position. First, in the same
     * change, the rows of every other project that [live] says has no live run are freed as abandoned (bl-204), so a
     * request never waits behind a dead holder.
     */
    suspend fun ask(
        projectId: ProjectId,
        issueId: String,
        issueIdentifier: String,
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
        live: (ProjectId) -> Boolean = { true },
    ): Result<ResourceAnswer> {
        val request = ResourceRequest(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, clock.now())
        val ids = mapOf(ResourceRequested.KIND to kind, ResourceRequested.RESOURCE_ID to resourceId)
        val subject = request.granted()
        return change(
            reason = ResourceReleased.ABANDONED,
            first = { answer ->
                if (answer.requested) listOf(event(ResourceRequested.event(clock.now(), projectId, ids), subject)) else emptyList()
            },
            last = { answer ->
                if (answer is ResourceAnswer.Queued && answer.requested) {
                    val data = ids + (ResourceQueued.POSITION to answer.position.toString()) + (ResourceQueued.REASON to answer.reason)
                    listOf(event(ResourceQueued.event(clock.now(), projectId, data), subject))
                } else {
                    emptyList()
                }
            },
        ) { rows ->
            val swept = ResourceQueue.freeAbandoned(rows, projectId, live, clock.now())
            val asked = ResourceAnswers.ask(swept.rows, request, clock.now())
            val change =
                asked.change.copy(
                    released = swept.released + asked.change.released,
                    granted = swept.granted + asked.change.granted,
                )
            change to asked.answer
        }
    }

    /**
     * Frees the rows of every project other than [own] that [live] says has no live run (bl-204), each freed lock
     * recorded with the reason `abandoned` and granted to the oldest request of its queue left. Rows of [own] alone
     * change nothing, so they are only read.
     */
    suspend fun freeAbandoned(
        own: ProjectId,
        live: (ProjectId) -> Boolean,
    ): Result<ResourceChange> {
        val rows = rows().getOrElse { return Result.failure(it) }
        if (rows.locks.all { it.projectId == own } && rows.queue.all { it.projectId == own }) return Result.success(ResourceChange(rows))
        return change(
            ResourceReleased.ABANDONED,
        ) { current -> ResourceQueue.freeAbandoned(current, own, live, clock.now()).let { it to it } }
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

    /**
     * The attempt [attemptId] releases its lock of [kind] and [resourceId], which goes to the oldest request of its
     * queue. With [dirty] (m4-26) the resource is marked dirty in the same change, which records
     * `resource_check_failed`, and it is lent to nobody until the mark is cleared.
     */
    suspend fun release(
        attemptId: AttemptId,
        kind: String,
        resourceId: String,
        dirty: DirtyResource? = null,
    ): Result<ResourceChange> =
        change(reason = ResourceReleased.RELEASED) { rows ->
            ResourceQueue.release(rows, kind, resourceId, attemptId, clock.now(), dirty).let { it to it }
        }

    /**
     * Frees every lock and queue row of the attempt [attemptId], each freed lock recorded with [reason], and marks the
     * resources of [dirty] dirty in the same change (m4-26), each recorded as `resource_check_failed`.
     */
    suspend fun freeAttempt(
        attemptId: AttemptId,
        reason: String,
        dirty: List<DirtyResource> = emptyList(),
    ): Result<ResourceChange> = change(reason) { rows -> ResourceQueue.freeAttempt(rows, attemptId, clock.now(), dirty).let { it to it } }

    /**
     * Frees every lock and queue row of the attempt [attemptId] whose kind is one of [kinds], each freed lock recorded
     * with [reason], and marks the resources of [dirty] dirty in the same change (m4-26), for a check of the attempt
     * that needs a resource of that pool.
     */
    suspend fun freeKinds(
        attemptId: AttemptId,
        kinds: Set<String>,
        reason: String,
        dirty: List<DirtyResource> = emptyList(),
    ): Result<ResourceChange> =
        change(reason) { rows -> ResourceQueue.freeKinds(rows, attemptId, kinds, clock.now(), dirty).let { it to it } }

    /**
     * Ends the recheck of the dirty resource of [kind] and [resourceId] by its holder [holder] (m4-26): the lock goes,
     * and when the check [passed] the mark goes in the same change, which grants the resource to the head of its queue.
     */
    suspend fun endRecheck(
        holder: AttemptId,
        kind: String,
        resourceId: String,
        passed: Boolean,
    ): Result<ResourceChange> =
        change(
            reason = ResourceReleased.RELEASED,
        ) { rows -> ResourceQueue.endRecheck(rows, holder, kind, resourceId, passed, clock.now()).let { it to it } }

    /** Clears the dirty mark of [kind] and [resourceId] (m4-26), a check of it passed, and grants it to the head of its queue. */
    suspend fun clearDirty(
        kind: String,
        resourceId: String,
    ): Result<ResourceChange> =
        change(
            reason = ResourceReleased.RELEASED,
        ) { rows -> ResourceQueue.clearDirty(rows, kind, resourceId, clock.now()).let { it to it } }

    /** Frees every lock and queue row of the project [projectId], each freed lock recorded with [reason]. */
    suspend fun freeProject(
        projectId: ProjectId,
        reason: String,
    ): Result<ResourceChange> = change(reason) { rows -> ResourceQueue.freeProject(rows, projectId, clock.now()).let { it to it } }

    /**
     * Applies [apply] to the rows in one store change, then records what it released, with [reason], the events
     * [first] gives for its value, what it granted, and the events [last] gives.
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
            change.marked.forEach { mark -> record(checkFailed(mark, change.released)) }
            change.released.forEach { record(released(it, reason)) }
            first(value).forEach { record(it) }
            change.granted.forEach { record(granted(it)) }
            last(value).forEach { record(it) }
        }
        return result
    }

    /** [event] given the run and the holder ids of [lock]. */
    private fun event(
        event: RecordedEvent,
        lock: ResourceLock,
    ): RecordedEvent =
        event.copy(
            runId = runId,
            issueId = lock.issueId.ofEvent(),
            issueIdentifier = lock.issueIdentifier.ofEvent(),
            attemptId = lock.attemptId.ofEvent(),
        )

    /**
     * The attempt id an event of a lock of this holder carries: none for a check's holder (m4-26), `check-<uuid>`, or a
     * recheck holder, `recheck-<uuid>`, which are no attempt, so their events belong to the issue and the run alone.
     */
    private fun AttemptId.ofEvent(): AttemptId? =
        takeUnless { it.value.startsWith(ResourceGrants.CHECK_HOLDER) || it.value.startsWith(ResourceGrants.RECHECK_HOLDER) }

    /** The issue id an event carries: none for a recheck holder, whose lock has an empty one (m4-26). */
    private fun String.ofEvent(): String? = takeIf { it.isNotEmpty() }

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
            issueId = lock.issueId.ofEvent(),
            issueIdentifier = lock.issueIdentifier.ofEvent(),
            attemptId = lock.attemptId.ofEvent(),
        )

    /** `resource_check_failed` of [mark], with the ids of the holder whose lock of it is among [released]. */
    private fun checkFailed(
        mark: DirtyResource,
        released: List<ResourceLock>,
    ): RecordedEvent {
        val holder = released.firstOrNull { it.kind == mark.kind && it.resourceId == mark.resourceId }
        return ResourceCheckFailed.event(
            ts = clock.now(),
            projectId = mark.projectId,
            data =
                mapOf(
                    ResourceCheckFailed.KIND to mark.kind,
                    ResourceCheckFailed.RESOURCE_ID to mark.resourceId,
                    ResourceCheckFailed.CHECK to mark.check,
                    ResourceCheckFailed.DIFFERENCE to mark.difference,
                ),
            runId = runId,
            issueId = holder?.issueId?.ofEvent(),
            issueIdentifier = holder?.issueIdentifier?.ofEvent(),
            attemptId = holder?.attemptId?.ofEvent(),
        )
    }

    private fun granted(lock: ResourceLock): RecordedEvent =
        ResourceGranted.event(
            ts = clock.now(),
            projectId = lock.projectId,
            data = mapOf(ResourceGranted.KIND to lock.kind, ResourceGranted.RESOURCE_ID to lock.resourceId),
            runId = runId,
            issueId = lock.issueId.ofEvent(),
            issueIdentifier = lock.issueIdentifier.ofEvent(),
            attemptId = lock.attemptId.ofEvent(),
        )
}
