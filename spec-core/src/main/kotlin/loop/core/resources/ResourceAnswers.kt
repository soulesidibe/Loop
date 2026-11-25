package loop.core.resources

import loop.api.AttemptId
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceRequest
import kotlin.time.Instant

/**
 * What `request_resource` answers the attempt (m4-25, PLAN 6.10), decided by [ResourceAnswers.ask] on the rows of the
 * machine. A request is granted and `loop run` prepares the lock ([Preparing]), or it waits in the queue ([Queued]).
 * The attempt calls again to check: [Granted] carries what the provider's `prepare` returned, once; a request after
 * that is a double request ([AlreadyHeld]).
 */
sealed interface ResourceAnswer {
    /** The attempt holds [lock], prepared, and is answered so for the first time. */
    data class Granted(
        val lock: ResourceLock,
    ) : ResourceAnswer

    /** The attempt holds [lock], which `loop run` has not prepared yet; [requested] is true for a new request. */
    data class Preparing(
        val lock: ResourceLock,
        val requested: Boolean,
    ) : ResourceAnswer

    /** The request waits at [position], 1 being the next one granted, behind [holder]; [requested] is true for a new request. */
    data class Queued(
        val position: Int,
        val holder: ResourceLock,
        val requested: Boolean,
    ) : ResourceAnswer

    /** The attempt already holds [lock] and was answered granted: a double request. */
    data class AlreadyHeld(
        val lock: ResourceLock,
    ) : ResourceAnswer

    /** The attempt released [lock], which `loop run` has not freed yet. */
    data class Releasing(
        val lock: ResourceLock,
    ) : ResourceAnswer

    /** `loop run` could not prepare [lock], whose [ResourceLock.prepareError] says why; the lock is now marked for release. */
    data class PrepareFailed(
        val lock: ResourceLock,
    ) : ResourceAnswer
}

/** What `release_resource` answers the attempt (m4-25). */
sealed interface ReleaseAnswer {
    /** The attempt held [lock], now marked for release: `loop run` gives it back and frees it on its next tick. */
    data class Marked(
        val lock: ResourceLock,
    ) : ReleaseAnswer

    /** The attempt holds no lock of the resource, or released it already; [position] is its place when it waits in the queue. */
    data class NotHeld(
        val position: Int?,
    ) : ReleaseAnswer
}

/**
 * The rules of the resource tools (m4-25), pure functions on the rows of the machine like [ResourceQueue], which
 * takes and queues the locks. The tool process applies [ask] and [release]; `loop run` applies [prepared].
 */
object ResourceAnswers {
    /** The rows after a call, in [change], and its answer. */
    data class Answered<T>(
        val change: ResourceChange,
        val answer: T,
    )

    /** The attempt of [request] asks for its resource at [now], or checks a request it made before. */
    fun ask(
        rows: ResourceLockRows,
        request: ResourceRequest,
        now: Instant,
    ): Answered<ResourceAnswer> {
        val held = rows.locks.firstOrNull { it.isOf(request.kind, request.resourceId) && it.attemptId == request.attemptId }
        if (held != null) {
            val answer: ResourceAnswer =
                when {
                    held.releaseRequestedAt != null -> ResourceAnswer.Releasing(held)
                    held.prepareError != null -> ResourceAnswer.PrepareFailed(held)
                    held.prepared == null -> ResourceAnswer.Preparing(held, requested = false)
                    held.answered -> ResourceAnswer.AlreadyHeld(held)
                    else -> ResourceAnswer.Granted(held.copy(answered = true))
                }
            val changed =
                when (answer) {
                    is ResourceAnswer.Granted -> answer.lock
                    is ResourceAnswer.PrepareFailed -> held.copy(releaseRequestedAt = now)
                    else -> held
                }
            return Answered(ResourceChange(rows.replace(held, changed)), answer)
        }
        val waiting = rows.queue.filter { it.kind == request.kind && it.resourceId == request.resourceId }
        val place = waiting.indexOfFirst { it.attemptId == request.attemptId }
        val holder = rows.locks.firstOrNull { it.isOf(request.kind, request.resourceId) }
        if (place >= 0 && holder != null) return Answered(ResourceChange(rows), ResourceAnswer.Queued(place + 1, holder, requested = false))
        val requested = ResourceQueue.request(rows, request, now)
        val answer =
            when (val grant = requested.grant) {
                is ResourceGrant.Granted -> ResourceAnswer.Preparing(grant.lock, requested = place < 0)
                is ResourceGrant.Queued -> ResourceAnswer.Queued(grant.position, grant.holder, requested = place < 0)
            }
        return Answered(requested.change, answer)
    }

    /** The attempt [attemptId] releases its lock of [kind] and [resourceId] at [now]: the lock is marked, never freed here. */
    fun release(
        rows: ResourceLockRows,
        kind: String,
        resourceId: String,
        attemptId: AttemptId,
        now: Instant,
    ): Answered<ReleaseAnswer> {
        val held = rows.locks.firstOrNull { it.isOf(kind, resourceId) && it.attemptId == attemptId && it.releaseRequestedAt == null }
        if (held == null) {
            val waiting = rows.queue.filter { it.kind == kind && it.resourceId == resourceId }
            val place = waiting.indexOfFirst { it.attemptId == attemptId }.takeIf { it >= 0 }?.plus(1)
            return Answered(ResourceChange(rows), ReleaseAnswer.NotHeld(place))
        }
        val marked = held.copy(releaseRequestedAt = now)
        return Answered(ResourceChange(rows.replace(held, marked)), ReleaseAnswer.Marked(marked))
    }

    /**
     * Writes on [lock] what its prepare gave, the values or the failure's [Throwable.message], when the same attempt
     * still holds it unprepared. The value is true when it was written.
     */
    fun prepared(
        rows: ResourceLockRows,
        lock: ResourceLock,
        outcome: Result<Map<String, String>>,
    ): Answered<Boolean> {
        val current =
            rows.locks.firstOrNull { it.isOf(lock.kind, lock.resourceId) && it.attemptId == lock.attemptId }
                ?.takeIf { it.prepared == null && it.prepareError == null }
                ?: return Answered(ResourceChange(rows), false)
        val written =
            outcome.fold(
                { current.copy(prepared = it) },
                { current.copy(prepareError = it.message ?: it::class.simpleName ?: "unknown error") },
            )
        return Answered(ResourceChange(rows.replace(current, written)), true)
    }

    private fun ResourceLock.isOf(
        kind: String,
        resourceId: String,
    ) = this.kind == kind && this.resourceId == resourceId

    private fun ResourceLockRows.replace(
        old: ResourceLock,
        new: ResourceLock,
    ): ResourceLockRows = if (old == new) this else copy(locks = locks.map { if (it == old) new else it })
}
