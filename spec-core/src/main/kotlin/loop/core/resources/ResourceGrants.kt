package loop.core.resources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceLock
import loop.api.ResourceProvider

/**
 * What `loop run` does with the resource locks of its project [projectId] (m4-25, PLAN 6.9 and 6.10), so a booted
 * simulator or a background emulator is a child of `loop run`, never of the short lived `loop tool mcp` that took the
 * lock. On each [tick] it gives back each lock its holder released, through the provider's `release`, then frees it,
 * which grants it to the oldest request of its queue; then it prepares each lock not prepared yet, through the
 * provider's `prepare`, or to nothing for a kind no provider of [providers] serves, and writes the result on the lock,
 * where the holder's next `request_resource` call reads it. [endAttempt] runs the provider's `release` before the free
 * at the end of an attempt. A provider gets the entry of [declarations], the current `resources:` of the workflow, or
 * its kind and id alone when a reload removed it. A provider that fails goes to [failed] and the free goes on.
 * Locks of other projects are their own runs' to prepare. One call at a time.
 *
 * Since bl-204 a tick also frees as abandoned the rows of every other project that [live] says has no live run, so a
 * killed run never keeps a resource from the others, and frees again the rows of each attempt whose free at its end
 * failed. [live] is never asked about [projectId], whose run is this one.
 */
class ResourceGrants(
    private val projectId: ProjectId,
    private val locks: ResourceLocks,
    providers: List<ResourceProvider>,
    private val declarations: () -> List<ResourceDeclaration>,
    private val live: (ProjectId) -> Boolean = { true },
    private val failed: (ResourceLock, String) -> Unit = { _, _ -> },
) {
    private val providers: Map<String, ResourceProvider> = providers.flatMap { provider -> provider.kinds.map { it to provider } }.toMap()

    private val mutex = Mutex()

    /** The attempts whose free at their end failed, with its reason, freed again on the next tick (bl-204). */
    private val unfreed = mutableMapOf<AttemptId, String>()

    /** The locks whose provider `release` ran already, so a free tried again never releases twice (bl-204). */
    private val givenBack = mutableSetOf<ResourceLock>()

    /**
     * One pass over the locks: the abandoned rows of other projects and the frees that failed, then the releases and the
     * prepares of the project. A failed read or change of the rows is the failure; the next tick tries again.
     */
    suspend fun tick(): Result<Unit> =
        mutex.withLock {
            locks.freeAbandoned(projectId, live).onFailure { return@withLock Result.failure(it) }
            for ((attemptId, reason) in unfreed.toMap()) {
                free(attemptId, reason).onFailure { return@withLock Result.failure(it) }
            }
            val released = mine().getOrElse { return@withLock Result.failure(it) }.filter { it.releaseRequestedAt != null }
            for (lock in released) {
                giveBack(lock)
                locks.release(lock.attemptId, lock.kind, lock.resourceId).onFailure { failed(lock, "cannot free: ${it.describe()}") }
            }
            val unprepared =
                mine().getOrElse { return@withLock Result.failure(it) }.filter {
                    it.releaseRequestedAt == null && it.prepared == null && it.prepareError == null
                }
            for (lock in unprepared) {
                val outcome = prepare(lock)
                outcome.onFailure { failed(lock, "prepare failed: ${it.describe()}") }
                locks.prepared(lock, outcome).onFailure { failed(lock, "cannot write the prepare result: ${it.describe()}") }
            }
            Result.success(Unit)
        }

    /**
     * Frees every lock and queue row of the attempt [attemptId], each freed lock recorded with [reason], after the
     * provider's `release` of each lock it prepared, so the next holder is prepared only once the resource is back. A
     * free that fails is the failure, and the next [tick] tries it again (bl-204).
     */
    suspend fun endAttempt(
        attemptId: AttemptId,
        reason: String,
    ): Result<ResourceChange> = mutex.withLock { free(attemptId, reason) }

    /** Gives back each lock of [attemptId] not given back yet, then frees its rows, or keeps it for the next tick. */
    private suspend fun free(
        attemptId: AttemptId,
        reason: String,
    ): Result<ResourceChange> {
        val rows =
            locks.rows().getOrElse { error ->
                unfreed[attemptId] = reason
                return Result.failure(error)
            }
        rows.locks.filter { it.attemptId == attemptId && it !in givenBack }.forEach { lock ->
            giveBack(lock)
            givenBack += lock
        }
        return locks
            .freeAttempt(attemptId, reason)
            .onSuccess {
                unfreed -= attemptId
                givenBack.removeAll { it.attemptId == attemptId }
            }.onFailure { unfreed[attemptId] = reason }
    }

    private suspend fun mine(): Result<List<ResourceLock>> = locks.rows().map { rows -> rows.locks.filter { it.projectId == projectId } }

    /** Runs the provider's `release` of [lock] when its prepare ran, even when it failed, since it may have started something. */
    private suspend fun giveBack(lock: ResourceLock) {
        if (lock.prepared == null && lock.prepareError == null) return
        val provider = providers[lock.kind] ?: return
        guarded { provider.release(declaration(lock)) }.onFailure { failed(lock, "release failed: ${it.describe()}") }
    }

    private suspend fun prepare(lock: ResourceLock): Result<Map<String, String>> {
        val provider = providers[lock.kind] ?: return Result.success(emptyMap())
        return guarded { provider.prepare(declaration(lock)) }
    }

    private fun declaration(lock: ResourceLock): ResourceDeclaration =
        declarations().firstOrNull { it.kind == lock.kind && it.id == lock.resourceId } ?: ResourceDeclaration(lock.kind, lock.resourceId)

    /** Runs a provider call, which blocks, off the caller's thread; any exception but a cancellation is the failure. */
    private suspend fun <T> guarded(call: () -> T): Result<T> =
        try {
            Result.success(withContext(Dispatchers.IO) { call() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"
}
