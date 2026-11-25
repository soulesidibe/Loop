package loop.api

import kotlin.time.Instant

/**
 * Keeps the resource locks and their queue (m4-50, PLAN 6.9) in the machine level store, shared by every project of
 * the machine (ADR 0001), so two projects asking for one resource share its lock. The rules of the queue are Loop's
 * core, never the store's: the store hands every row of the machine to a change and writes back what it returns, in
 * one write transaction, so of two processes changing the rows at once one waits for the other. Like [EventSink],
 * implementations return a failed [Result] instead of throwing.
 */
public interface ResourceLockStore {
    /** Every lock and queue row of the machine, of every project. */
    public suspend fun resourceLockRows(): Result<ResourceLockRows>

    /**
     * Reads every lock and queue row of the machine, hands them to [change] and writes the rows it returns in their
     * place, in one write transaction. [change] runs under the write lock, must not touch the store, and may run
     * again. The value it returns is the result.
     */
    public suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T>

    public companion object {
        /** Keeps nothing: every read is empty and every change runs on empty rows, whose result is dropped. */
        public val NONE: ResourceLockStore =
            object : ResourceLockStore {
                override suspend fun resourceLockRows(): Result<ResourceLockRows> = Result.success(ResourceLockRows())

                override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> =
                    Result.success(change(ResourceLockRows()).value)
            }
    }
}

/**
 * The rows [ResourceLockStore.updateResourceLocks] writes, [rows], and the [value] the change returns to its caller.
 */
public data class ResourceLockUpdate<T>(
    public val rows: ResourceLockRows,
    public val value: T,
)

/**
 * The resource rows of the machine: [locks], at most one per kind and resource id, and [queue], the requests that
 * wait, oldest first.
 */
public data class ResourceLockRows(
    public val locks: List<ResourceLock> = emptyList(),
    public val queue: List<ResourceRequest> = emptyList(),
)

/**
 * The lock of the resource of [kind] and [resourceId], keyed by both and never by project: the attempt [attemptId]
 * of the issue [issueId] in [projectId] holds it since [grantedAt]. [prepared] is what the provider's `prepare`
 * returned, empty until it ran.
 */
public data class ResourceLock(
    public val kind: String,
    public val resourceId: String,
    public val projectId: ProjectId,
    public val issueId: String,
    public val issueIdentifier: String,
    public val attemptId: AttemptId,
    public val grantedAt: Instant,
    public val prepared: Map<String, String> = emptyMap(),
)

/**
 * One request that waits in the queue of the resource of [kind] and [resourceId]: the attempt [attemptId] of the
 * issue [issueId] in [projectId] asked for it at [requestedAt].
 */
public data class ResourceRequest(
    public val kind: String,
    public val resourceId: String,
    public val projectId: ProjectId,
    public val issueId: String,
    public val issueIdentifier: String,
    public val attemptId: AttemptId,
    public val requestedAt: Instant,
)
