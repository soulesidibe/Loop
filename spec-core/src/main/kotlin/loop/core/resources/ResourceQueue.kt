package loop.core.resources

import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.ProjectId
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceRequest
import loop.core.events.ResourceQueued
import kotlin.time.Instant

/**
 * The rules of the resource locks (m4-50, PLAN 6.9), pure functions on the rows of the machine. A lock is keyed by
 * kind and resource id, never by project, so two projects asking for one resource share its lock. A resource is
 * granted once: to the first request when it is free, else the request waits in its queue, and a freed lock goes at
 * once to the oldest request of its queue, first requested first granted. A lock given that way is appended to the
 * locks, and the requests that still wait keep their order.
 *
 * Since m4-26 a request is granted only when its resource is free, not dirty ([ResourceLockRows.dirty]) and its pool
 * of [ResourceLimits] is under its limit; else it waits, and [ResourceGrant.Queued.reason] says why. Requests are
 * granted in queue order, so of two requests a pool limit keeps waiting, the oldest is granted first, whatever its
 * resource. A request of a group ([ResourceRequest.anyOfKind]) waits on each resource of its kind at once, and the
 * first one granted drops the others. A dirty resource is granted to a recheck holder alone, which checks it again
 * and counts in its pool like any holder.
 *
 * Every function first grants each waiting request that can be granted, which only rows written outside these rules
 * can hold, so a new request never jumps a request that was there before it.
 */
object ResourceQueue {
    /**
     * The rows after a request, in [change], and what the request got, in [grant]; for a request of a group that waits
     * (m4-26), [places] holds each resource id with its place.
     */
    data class Requested(
        val change: ResourceChange,
        val grant: ResourceGrant,
        val places: List<Pair<String, ResourceGrant.Queued>> = emptyList(),
    )

    /**
     * The attempt of [request] asks for its resource at [now]. Granted when the attempt already holds it or the
     * resource can be granted and nobody waits before it; else queued at the end, or left in its place when the
     * attempt already waits.
     */
    fun request(
        rows: ResourceLockRows,
        request: ResourceRequest,
        now: Instant,
    ): Requested {
        val settled = settle(rows, now, ResourceChange(rows))
        val held = settled.rows.locks.firstOrNull { it.isOf(request) && it.attemptId == request.attemptId }
        if (held != null) return Requested(settled, ResourceGrant.Granted(held))
        val waiting = settled.rows.queue.any { it.isOf(request) && it.attemptId == request.attemptId }
        val change = if (waiting) settled else settle(settled.rows.copy(queue = settled.rows.queue + request), now, settled)
        val granted = change.rows.locks.firstOrNull { it.isOf(request) && it.attemptId == request.attemptId }
        return Requested(change, granted?.let { ResourceGrant.Granted(it) } ?: queued(change.rows, request))
    }

    /**
     * The holder of [template] asks at [now] for any resource of its kind among [ids], as a check that holds a resource
     * does (m4-26): granted the first one that can be granted, else it waits on each of them at once, in a group, and
     * the first one freed for it drops the others. Its place is its best place among them. [template]'s resource id is
     * not read. A holder that already holds a resource of the kind keeps it.
     */
    fun requestAny(
        rows: ResourceLockRows,
        template: ResourceRequest,
        ids: List<String>,
        now: Instant,
    ): Requested {
        val settled = settle(rows, now, ResourceChange(rows))

        fun ofHolder(
            kind: String,
            attemptId: AttemptId,
        ) = kind == template.kind && attemptId == template.attemptId
        settled.rows.locks
            .firstOrNull { ofHolder(it.kind, it.attemptId) }
            ?.let { return Requested(settled, ResourceGrant.Granted(it)) }
        val waiting = settled.rows.queue.any { it.anyOfKind && ofHolder(it.kind, it.attemptId) }
        val group = ids.distinct().map { template.copy(resourceId = it, anyOfKind = true) }
        val change = if (waiting) settled else settle(settled.rows.copy(queue = settled.rows.queue + group), now, settled)
        change.rows.locks
            .firstOrNull { ofHolder(it.kind, it.attemptId) }
            ?.let { return Requested(change, ResourceGrant.Granted(it)) }
        val places =
            change.rows.queue
                .filter { it.anyOfKind && ofHolder(it.kind, it.attemptId) }
                .map { it.resourceId to queued(change.rows, it) }
        return Requested(change, places.minBy { it.second.position }.second, places)
    }

    /**
     * The attempt [attemptId] releases its lock of [kind] and [resourceId] at [now], which goes to the oldest request
     * of its queue. Nothing changes when the attempt does not hold it. With [dirty] (m4-26) the resource is marked
     * dirty in the same change, so it is lent to nobody until the mark is cleared.
     */
    fun release(
        rows: ResourceLockRows,
        kind: String,
        resourceId: String,
        attemptId: AttemptId,
        now: Instant,
        dirty: DirtyResource? = null,
    ): ResourceChange {
        val holds = rows.locks.any { it.kind == kind && it.resourceId == resourceId && it.attemptId == attemptId }
        val marks = if (holds) listOfNotNull(dirty) else emptyList()
        return free(
            rows,
            now,
            lockGoes = { it.kind == kind && it.resourceId == resourceId && it.attemptId == attemptId },
            requestGoes = { false },
            dirty = marks,
        )
    }

    /**
     * Every lock and queue row of the attempt [attemptId] goes at [now], each freed lock to the oldest request of its
     * queue, and the resources of [dirty] are marked dirty in the same change (m4-26). With [expected], the kind and
     * resource id of each lock the caller gave back, nothing changes when the attempt holds a lock outside it (bl-219),
     * and [ResourceChange.unexpected] lists those locks.
     */
    fun freeAttempt(
        rows: ResourceLockRows,
        attemptId: AttemptId,
        now: Instant,
        dirty: List<DirtyResource> = emptyList(),
        expected: Set<Pair<String, String>>? = null,
    ): ResourceChange {
        val unexpected = unexpected(rows, expected) { it.attemptId == attemptId }
        if (unexpected.isNotEmpty()) return ResourceChange(rows, unexpected = unexpected)
        return free(rows, now, lockGoes = { it.attemptId == attemptId }, requestGoes = { it.attemptId == attemptId }, dirty = dirty)
    }

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

    /**
     * Every lock and queue row of the attempt [attemptId] whose kind is one of [kinds] goes at [now], each freed lock
     * to the oldest request of its queue, and the resources of [dirty] are marked dirty in the same change (m4-26). A
     * check that holds a resource frees this way what its own attempt holds in the pool of its kind, which the attempt
     * would otherwise keep until its end, after the check. With [expected], nothing changes when the attempt holds a
     * lock of [kinds] outside it, as in [freeAttempt] (bl-219).
     */
    fun freeKinds(
        rows: ResourceLockRows,
        attemptId: AttemptId,
        kinds: Set<String>,
        now: Instant,
        dirty: List<DirtyResource> = emptyList(),
        expected: Set<Pair<String, String>>? = null,
    ): ResourceChange {
        val unexpected = unexpected(rows, expected) { it.attemptId == attemptId && it.kind in kinds }
        if (unexpected.isNotEmpty()) return ResourceChange(rows, unexpected = unexpected)
        return free(
            rows,
            now,
            lockGoes = { it.attemptId == attemptId && it.kind in kinds },
            requestGoes = { it.attemptId == attemptId && it.kind in kinds },
            dirty = dirty,
        )
    }

    /** The locks [goes] picks whose kind and resource id are not in [expected]; none when [expected] is null. */
    private fun unexpected(
        rows: ResourceLockRows,
        expected: Set<Pair<String, String>>?,
        goes: (ResourceLock) -> Boolean,
    ): List<ResourceLock> = if (expected == null) emptyList() else rows.locks.filter { goes(it) && (it.kind to it.resourceId) !in expected }

    /**
     * The recheck holder [holder] of the dirty resource of [kind] and [resourceId] is done at [now] (m4-26): its lock
     * and requests go, and when the check [passed] while it held the lock the dirty mark goes too, in the same change,
     * so the resource goes to the oldest request of its queue.
     */
    fun endRecheck(
        rows: ResourceLockRows,
        holder: AttemptId,
        kind: String,
        resourceId: String,
        passed: Boolean,
        now: Instant,
    ): ResourceChange {
        val held = rows.locks.any { it.attemptId == holder && it.kind == kind && it.resourceId == resourceId }
        val left = if (passed && held) rows.copy(dirty = rows.dirty.filterNot { it.kind == kind && it.resourceId == resourceId }) else rows
        return free(left, now, lockGoes = { it.attemptId == holder }, requestGoes = { it.attemptId == holder })
    }

    /** The dirty mark of [kind] and [resourceId] goes at [now] (m4-26), and the resource goes to the oldest request of its queue. */
    fun clearDirty(
        rows: ResourceLockRows,
        kind: String,
        resourceId: String,
        now: Instant,
    ): ResourceChange {
        val left = rows.copy(dirty = rows.dirty.filterNot { it.kind == kind && it.resourceId == resourceId })
        return settle(left, now, ResourceChange(left))
    }

    /**
     * Drops the requests [requestGoes] picks and the locks [lockGoes] picks, marks the resources of [dirty], one mark
     * per resource, then grants what can be granted.
     */
    private fun free(
        rows: ResourceLockRows,
        now: Instant,
        lockGoes: (ResourceLock) -> Boolean,
        requestGoes: (ResourceRequest) -> Boolean,
        dirty: List<DirtyResource> = emptyList(),
    ): ResourceChange {
        val (released, kept) = rows.locks.partition(lockGoes)
        val marked = dirty.filter { mark -> rows.dirty.none { it.kind == mark.kind && it.resourceId == mark.resourceId } }
        val left = ResourceLockRows(locks = kept, queue = rows.queue.filterNot(requestGoes), dirty = rows.dirty + marked)
        return settle(left, now, ResourceChange(left, released = released, marked = marked))
    }

    /**
     * Grants, in queue order at [now], each waiting request whose resource is free, not dirty, and whose pool is under
     * its limit; a request of a group granted drops the other requests of its group.
     */
    private fun settle(
        rows: ResourceLockRows,
        now: Instant,
        change: ResourceChange,
    ): ResourceChange {
        val locks = rows.locks.toMutableList()
        val queue = rows.queue.toMutableList()
        val granted = mutableListOf<ResourceLock>()
        for (waiting in rows.queue) {
            if (waiting !in queue || blockedBy(waiting, locks, rows.dirty) != null) continue
            val lock = waiting.granted(now)
            locks += lock
            granted += lock
            queue -= waiting
            if (waiting.anyOfKind) {
                queue.removeAll { it.anyOfKind && it.kind == waiting.kind && it.attemptId == waiting.attemptId }
            }
        }
        if (granted.isEmpty()) return change.copy(rows = rows)
        val settled = rows.copy(locks = locks, queue = queue)
        return change.copy(rows = settled, granted = change.granted + granted)
    }

    /**
     * Why the resource of [request] cannot be granted now, a reason of `resource_queued`, null when it can. A dirty
     * resource is granted to a recheck holder alone (m4-26), `recheck-<uuid>`, which checks it again.
     */
    private fun blockedBy(
        request: ResourceRequest,
        locks: List<ResourceLock>,
        dirty: List<DirtyResource>,
    ): String? {
        val kind = request.kind
        val resourceId = request.resourceId
        if (locks.any { it.kind == kind && it.resourceId == resourceId }) return ResourceQueued.HELD
        val recheck = request.attemptId.value.startsWith(ResourceGrants.RECHECK_HOLDER)
        if (!recheck && dirty.any { it.kind == kind && it.resourceId == resourceId }) return ResourceQueued.DIRTY
        val pool = ResourceLimits.poolOf(kind) ?: return null
        return if (locks.count { it.kind in pool.kinds } >= pool.limit) ResourceQueued.LIMIT else null
    }

    /** The place of the waiting [request] in its queue, with the holder and why it waits. */
    private fun queued(
        rows: ResourceLockRows,
        request: ResourceRequest,
    ): ResourceGrant.Queued {
        val waiting = rows.queue.filter { it.isOf(request) }
        val place = waiting.indexOfFirst { it.attemptId == request.attemptId }
        val holder = rows.locks.firstOrNull { it.isOf(request) }
        val reason = blockedBy(request, rows.locks, rows.dirty) ?: ResourceQueued.HELD
        return ResourceGrant.Queued(place + 1, holder, reason)
    }

    private fun ResourceLock.isOf(request: ResourceRequest) = kind == request.kind && resourceId == request.resourceId

    private fun ResourceRequest.isOf(request: ResourceRequest) = kind == request.kind && resourceId == request.resourceId

    private fun ResourceRequest.granted(now: Instant): ResourceLock =
        ResourceLock(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, grantedAt = now)
}

/**
 * The resource rows after a change, [rows], with the locks it [released], the locks it [granted], each grant given to
 * the oldest request of its queue or to a request of a free resource, and the dirty marks it added, [marked] (m4-26).
 * A free that expected other locks changed nothing, and [unexpected] holds the locks it did not expect (bl-219).
 */
data class ResourceChange(
    val rows: ResourceLockRows,
    val released: List<ResourceLock> = emptyList(),
    val granted: List<ResourceLock> = emptyList(),
    val marked: List<DirtyResource> = emptyList(),
    val unexpected: List<ResourceLock> = emptyList(),
)

/** What a request got: the lock, or a place in the queue, 1 for the next one granted, behind [holder]. */
sealed interface ResourceGrant {
    /** The attempt holds [lock]. */
    data class Granted(
        val lock: ResourceLock,
    ) : ResourceGrant

    /**
     * The request waits at [position] of the queue, 1 being the next one granted, while [holder], null when none,
     * holds the lock. [reason] is why (m4-26): `held`, `limit` or `dirty`, see [ResourceQueued].
     */
    data class Queued(
        val position: Int,
        val holder: ResourceLock?,
        val reason: String = ResourceQueued.HELD,
    ) : ResourceGrant
}
