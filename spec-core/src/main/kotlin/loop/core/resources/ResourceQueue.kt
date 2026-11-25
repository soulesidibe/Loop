package loop.core.resources

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceRequest
import kotlin.time.Instant

/**
 * The rules of the resource locks (m4-50, PLAN 6.9), pure functions on the rows of the machine. A lock is keyed by
 * kind and resource id, never by project, so two projects asking for one resource share its lock. A resource is
 * granted once: to the first request when it is free, else the request waits in its queue, and a freed lock goes at
 * once to the oldest request of its queue, first requested first granted. A lock given that way is appended to the
 * locks, and the requests that still wait keep their order.
 *
 * Every function first grants the head of a queue whose resource has no lock, which only rows written outside these
 * rules can hold, so a new request never jumps a request that was there before it.
 */
object ResourceQueue {
    /** The rows after a request, in [change], and what the request got, in [grant]. */
    data class Requested(
        val change: ResourceChange,
        val grant: ResourceGrant,
    )

    /**
     * The attempt of [request] asks for its resource at [now]. Granted when the attempt already holds it or the
     * resource is free and nobody waits; else queued at the end, or left in its place when the attempt already waits.
     */
    fun request(
        rows: ResourceLockRows,
        request: ResourceRequest,
        now: Instant,
    ): Requested {
        val settled = settle(rows, now, ResourceChange(rows))
        val current = settled.rows
        val holder = current.locks.firstOrNull { it.kind == request.kind && it.resourceId == request.resourceId }
        if (holder == null) {
            val lock = request.granted(now)
            val change = settled.copy(rows = current.copy(locks = current.locks + lock), granted = settled.granted + lock)
            return Requested(change, ResourceGrant.Granted(lock))
        }
        if (holder.attemptId == request.attemptId) return Requested(settled, ResourceGrant.Granted(holder))
        val waiting = current.queue.filter { it.kind == request.kind && it.resourceId == request.resourceId }
        val place = waiting.indexOfFirst { it.attemptId == request.attemptId }
        if (place >= 0) return Requested(settled, ResourceGrant.Queued(place + 1, holder))
        val change = settled.copy(rows = current.copy(queue = current.queue + request))
        return Requested(change, ResourceGrant.Queued(waiting.size + 1, holder))
    }

    /**
     * The attempt [attemptId] releases its lock of [kind] and [resourceId] at [now], which goes to the oldest request
     * of its queue. Nothing changes when the attempt does not hold it.
     */
    fun release(
        rows: ResourceLockRows,
        kind: String,
        resourceId: String,
        attemptId: AttemptId,
        now: Instant,
    ): ResourceChange =
        free(rows, now, lockGoes = { it.kind == kind && it.resourceId == resourceId && it.attemptId == attemptId }, requestGoes = { false })

    /** Every lock and queue row of the attempt [attemptId] goes at [now], each freed lock to the oldest request of its queue. */
    fun freeAttempt(
        rows: ResourceLockRows,
        attemptId: AttemptId,
        now: Instant,
    ): ResourceChange = free(rows, now, lockGoes = { it.attemptId == attemptId }, requestGoes = { it.attemptId == attemptId })

    /** Every lock and queue row of the project [projectId] goes at [now], each freed lock to the oldest request of its queue left. */
    fun freeProject(
        rows: ResourceLockRows,
        projectId: ProjectId,
        now: Instant,
    ): ResourceChange = free(rows, now, lockGoes = { it.projectId == projectId }, requestGoes = { it.projectId == projectId })

    /**
     * Every lock and queue row of the projects other than [own] that [live] says have no live run goes at [now]
     * (bl-204), each freed lock to the oldest request of its queue left. [live] is asked once per such project.
     */
    fun freeAbandoned(
        rows: ResourceLockRows,
        own: ProjectId,
        live: (ProjectId) -> Boolean,
        now: Instant,
    ): ResourceChange {
        val others = (rows.locks.map { it.projectId } + rows.queue.map { it.projectId }).toSet() - own
        val dead = others.filterNot(live).toSet()
        if (dead.isEmpty()) return ResourceChange(rows)
        return free(rows, now, lockGoes = { it.projectId in dead }, requestGoes = { it.projectId in dead })
    }

    /** Drops the requests [requestGoes] picks and the locks [lockGoes] picks, then grants each free resource to its oldest request. */
    private fun free(
        rows: ResourceLockRows,
        now: Instant,
        lockGoes: (ResourceLock) -> Boolean,
        requestGoes: (ResourceRequest) -> Boolean,
    ): ResourceChange {
        val (released, kept) = rows.locks.partition(lockGoes)
        val left = ResourceLockRows(locks = kept, queue = rows.queue.filterNot(requestGoes))
        return settle(left, now, ResourceChange(left, released = released))
    }

    /** Grants every resource of [rows] that has no lock and a queue to the oldest request of that queue, at [now]. */
    private fun settle(
        rows: ResourceLockRows,
        now: Instant,
        change: ResourceChange,
    ): ResourceChange {
        val held = rows.locks.map { it.kind to it.resourceId }.toMutableSet()
        val heads = mutableListOf<ResourceRequest>()
        for (waiting in rows.queue) {
            if (held.add(waiting.kind to waiting.resourceId)) heads += waiting
        }
        if (heads.isEmpty()) return change.copy(rows = rows)
        val granted = heads.map { it.granted(now) }
        val settled = ResourceLockRows(locks = rows.locks + granted, queue = rows.queue - heads.toSet())
        return change.copy(rows = settled, granted = change.granted + granted)
    }

    private fun ResourceRequest.granted(now: Instant): ResourceLock =
        ResourceLock(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, grantedAt = now)
}

/**
 * The resource rows after a change, [rows], with the locks it [released] and the locks it [granted], each grant given
 * to the oldest request of its queue or to a request of a free resource.
 */
data class ResourceChange(
    val rows: ResourceLockRows,
    val released: List<ResourceLock> = emptyList(),
    val granted: List<ResourceLock> = emptyList(),
)

/** What a request got: the lock, or a place in the queue, 1 for the next one granted, behind [holder]. */
sealed interface ResourceGrant {
    /** The attempt holds [lock]. */
    data class Granted(
        val lock: ResourceLock,
    ) : ResourceGrant

    /** The request waits at [position] of the queue, 1 being the next one granted, while [holder] holds the lock. */
    data class Queued(
        val position: Int,
        val holder: ResourceLock,
    ) : ResourceGrant
}
