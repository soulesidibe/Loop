package loop.core.resources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceLock
import loop.api.ResourceProvider
import loop.core.events.ResourceCheckFailed
import loop.core.events.ResourceReleased
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
 *
 * Since m4-26 the provider's own read only check runs right after each provider `release`, and a difference marks the
 * resource dirty in the same change that frees the lock, without asking the agent, since the resource is gone. At the
 * end of an attempt a lock still held first gets its declared `read_only_check`, through [commands], while the resource
 * is up: a failure runs the `restore` entries once, then the check again, and a second failure marks it dirty. A dirty
 * resource some request waits for is checked again on a tick, at most once per [recheckEvery], and a pass clears the
 * mark. [withCheckResource] lends a resource of a kind to a check of the verification or of the merge check.
 */
class ResourceGrants(
    private val projectId: ProjectId,
    private val locks: ResourceLocks,
    providers: List<ResourceProvider>,
    private val declarations: () -> List<ResourceDeclaration>,
    private val live: (ProjectId) -> Boolean = { true },
    private val commands: ResourceCommands = ResourceCommands.NONE,
    private val recheckEvery: Duration = DIRTY_RECHECK_EVERY,
    private val clock: Clock = Clock.System,
    private val failed: (ResourceLock, String) -> Unit = { _, _ -> },
) {
    private val providers: Map<String, ResourceProvider> = providers.flatMap { provider -> provider.kinds.map { it to provider } }.toMap()

    private val mutex = Mutex()

    /** The attempts whose free at their end failed, with its reason, freed again on the next tick (bl-204). */
    private val unfreed = mutableMapOf<AttemptId, String>()

    /**
     * The locks, by attempt, kind and resource id, whose provider `release` and own check ran already, with the dirty
     * mark the check gave, so a free tried again never releases twice (bl-204).
     */
    private val givenBack = mutableMapOf<Triple<AttemptId, String, String>, DirtyResource?>()

    /** The locks whose declared check and restore ran at the end of their attempt, with the dirty mark they gave (m4-26). */
    private val endChecked = mutableMapOf<Triple<AttemptId, String, String>, DirtyResource?>()

    /** When each dirty resource, by kind and id, was last checked again (m4-26). */
    private val rechecked = mutableMapOf<Pair<String, String>, TimeSource.Monotonic.ValueTimeMark>()

    /**
     * One pass over the locks: the abandoned rows of other projects and the frees that failed, then the releases, the
     * dirty resources someone waits for, and the prepares of the project. A failed read or change of the rows is the
     * failure; the next tick tries again.
     */
    suspend fun tick(): Result<Unit> =
        mutex.withLock {
            locks.freeAbandoned(projectId, live).onFailure { return@withLock Result.failure(it) }
            for ((attemptId, reason) in unfreed.toMap()) {
                free(attemptId, reason).onFailure { return@withLock Result.failure(it) }
            }
            val released = mine().getOrElse { return@withLock Result.failure(it) }.filter { it.releaseRequestedAt != null }
            for (lock in released) {
                val dirty = giveBackOnce(lock)
                locks
                    .release(lock.attemptId, lock.kind, lock.resourceId, dirty)
                    .onSuccess { givenBack -= lock.key }
                    .onFailure { failed(lock, "cannot free: ${it.describe()}") }
            }
            recheckDirty().onFailure { return@withLock Result.failure(it) }
            val unprepared =
                mine().getOrElse { return@withLock Result.failure(it) }.filter {
                    it.releaseRequestedAt == null && it.prepared == null && it.prepareError == null && it.attemptId !in unfreed
                }
            for (lock in unprepared) prepareAndWrite(lock)
            Result.success(Unit)
        }

    /**
     * Frees every lock and queue row of the attempt [attemptId], each freed lock recorded with [reason], after the
     * declared read only check of each lock it still holds and the provider's `release` of each lock it prepared, so
     * the next holder is prepared only once the resource is back. A free that fails is the failure, and the next
     * [tick] tries it again (bl-204).
     */
    suspend fun endAttempt(
        attemptId: AttemptId,
        reason: String,
    ): Result<ResourceChange> = mutex.withLock { free(attemptId, reason) }

    /**
     * Runs [block] with a resource of [kind] lent to a check (m4-26) of the issue [issueId], [issueIdentifier]: the
     * check asks for any resource of the kind that `resources:` declares, waits its turn in queue order, polling every
     * [poll], gets it prepared, and [block] gets what the provider's `prepare` returned. The resource is given back and
     * freed after [block], whatever its end, and its provider check can mark it dirty like any release. A wait is never
     * a failure: it lasts until the resource is granted or the caller is canceled. The failure is a kind no entry
     * declares, a prepare that failed or a store that refused the request.
     */
    suspend fun <T> withCheckResource(
        kind: String,
        issueId: String,
        issueIdentifier: String,
        poll: Duration = CHECK_POLL,
        block: suspend (Map<String, String>) -> T,
    ): Result<T> {
        val ids = declarations().filter { it.kind == kind }.map { it.id }
        if (ids.isEmpty()) return Result.failure(ResourceException("no entry of resources declares the kind \"$kind\""))
        val holder = AttemptId("$CHECK_HOLDER${UUID.randomUUID()}")
        try {
            while (true) {
                val grant = locks.requestAny(projectId, issueId, issueIdentifier, holder, kind, ids).getOrElse { return Result.failure(it) }
                if (grant is ResourceGrant.Granted) break
                delay(poll)
            }
            val prepared = mutex.withLock { preparedFor(holder, kind) }.getOrElse { return Result.failure(it) }
            return Result.success(block(prepared))
        } finally {
            withContext(NonCancellable) { mutex.withLock { free(holder, ResourceReleased.RELEASED) } }
        }
    }

    /** What the lock of [kind] that [holder] holds was prepared to, prepared now when the tick has not done it yet. */
    private suspend fun preparedFor(
        holder: AttemptId,
        kind: String,
    ): Result<Map<String, String>> {
        val lock =
            locks
                .rows()
                .getOrElse { return Result.failure(it) }
                .locks
                .firstOrNull { it.attemptId == holder && it.kind == kind }
                ?: return Result.failure(ResourceException("the lock of the check was freed"))
        lock.prepared?.let { return Result.success(it) }
        lock.prepareError?.let { return Result.failure(ResourceException("cannot prepare ${lock.resourceId}: $it")) }
        return prepareAndWrite(lock).fold(
            { Result.success(it) },
            { Result.failure(ResourceException("cannot prepare ${lock.resourceId}: ${it.describe()}")) },
        )
    }

    private suspend fun prepareAndWrite(lock: ResourceLock): Result<Map<String, String>> {
        val outcome = prepare(lock)
        outcome.onFailure { failed(lock, "prepare failed: ${it.describe()}") }
        locks.prepared(lock, outcome).onFailure { failed(lock, "cannot write the prepare result: ${it.describe()}") }
        return outcome
    }

    /**
     * Checks again each dirty resource the workflow declares and some request waits for, once per [recheckEvery]: the
     * provider prepares it, its declared check runs with what that returned, the provider releases it and runs its own
     * check. A pass clears the mark, which grants it to the head of its queue.
     */
    private suspend fun recheckDirty(): Result<Unit> {
        val rows = locks.rows().getOrElse { return Result.failure(it) }
        for (mark in rows.dirty) {
            val key = mark.kind to mark.resourceId
            if (rows.queue.none { it.kind == mark.kind && it.resourceId == mark.resourceId }) continue
            val declaration = declarations().firstOrNull { it.kind == mark.kind && it.id == mark.resourceId } ?: continue
            val last = rechecked[key]
            if (last != null && last.elapsedNow() < recheckEvery) continue
            rechecked[key] = TimeSource.Monotonic.markNow()
            if (fullCheck(declaration) == null) locks.clearDirty(mark.kind, mark.resourceId).onSuccess { rechecked -= key }
        }
        return Result.success(Unit)
    }

    /** The whole check of a resource no one holds: the difference it found, null when it came back as lent. */
    private suspend fun fullCheck(resource: ResourceDeclaration): String? {
        val provider = providers[resource.kind]
        val prepared =
            if (provider == null) emptyMap() else guarded { provider.prepare(resource) }.getOrElse { return "prepare failed: ${it.describe()}" }
        val declared = ReadOnlyChecks.run(resource, prepared, commands)
        if (provider == null) return declared
        val released = guarded { provider.release(resource) }.exceptionOrNull()?.let { "release failed: ${it.describe()}" }
        val own = guarded { provider.readOnlyCheck(resource) }.fold({ it.joinToString("\n").ifEmpty { null } }, { it.describe() })
        return declared ?: released ?: own
    }

    /**
     * Checks each lock of [attemptId], gives it back and frees its rows with the dirty marks the checks gave, or keeps
     * the attempt for the next tick.
     */
    private suspend fun free(
        attemptId: AttemptId,
        reason: String,
    ): Result<ResourceChange> {
        val rows =
            locks.rows().getOrElse { error ->
                unfreed[attemptId] = reason
                return Result.failure(error)
            }
        val marks =
            rows.locks.filter { it.attemptId == attemptId }.mapNotNull { lock ->
                if (lock.key !in endChecked) endChecked[lock.key] = endCheck(lock)
                val declared = endChecked[lock.key]
                val own = giveBackOnce(lock)
                declared ?: own
            }
        return locks
            .freeAttempt(attemptId, reason, marks)
            .onSuccess {
                unfreed -= attemptId
                givenBack.keys.removeAll { it.first == attemptId }
                endChecked.keys.removeAll { it.first == attemptId }
            }.onFailure { unfreed[attemptId] = reason }
    }

    /**
     * The declared read only check of [lock] at the end of its attempt, while the resource is up: when it fails, the
     * `restore` entries run once, in order, then the check again; the dirty mark of a second failure, else null. A
     * lock its holder released passed the check in `loop tool mcp`, and one never prepared is not up.
     */
    private suspend fun endCheck(lock: ResourceLock): DirtyResource? {
        if (lock.releaseRequestedAt != null) return null
        val prepared = lock.prepared ?: return null
        val resource = declaration(lock)
        ReadOnlyChecks.run(resource, prepared, commands) ?: return null
        val env = ReadOnlyChecks.env(prepared)
        for (command in resource.restore) {
            val outcome = commands.run(command, env)
            if (!outcome.passed) failed(lock, "restore failed: ${ReadOnlyChecks.difference(outcome)}")
        }
        val difference = ReadOnlyChecks.run(resource, prepared, commands) ?: return null
        return DirtyResource(lock.kind, lock.resourceId, lock.projectId, ResourceCheckFailed.READ_ONLY_CHECK, difference, clock.now())
    }

    private suspend fun mine(): Result<List<ResourceLock>> = locks.rows().map { rows -> rows.locks.filter { it.projectId == projectId } }

    /**
     * Runs [giveBack] for [lock] unless it ran already for the same holder and resource, and returns the dirty mark of
     * the provider's check after it; an unprepared lock has none.
     */
    private suspend fun giveBackOnce(lock: ResourceLock): DirtyResource? {
        if (lock.prepared == null && lock.prepareError == null) return null
        // A null mark is a value too, so getOrPut, which runs again on null, would release twice.
        if (lock.key !in givenBack) givenBack[lock.key] = giveBack(lock)
        return givenBack[lock.key]
    }

    private val ResourceLock.key: Triple<AttemptId, String, String> get() = Triple(attemptId, kind, resourceId)

    /**
     * Runs the provider's `release` of [lock], even when its prepare failed, since it may have started something, then
     * the provider's own read only check: the dirty mark of a difference, else null.
     */
    private suspend fun giveBack(lock: ResourceLock): DirtyResource? {
        val provider = providers[lock.kind] ?: return null
        val resource = declaration(lock)
        guarded { provider.release(resource) }.onFailure { failed(lock, "release failed: ${it.describe()}") }
        val difference =
            guarded { provider.readOnlyCheck(resource) }
                .fold({ it.joinToString("\n") }, { "the check failed: ${it.describe()}" })
                .ifEmpty { return null }
        return DirtyResource(lock.kind, lock.resourceId, lock.projectId, ResourceCheckFailed.PROVIDER, difference, clock.now())
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

    companion object {
        /** How often a dirty resource some request waits for is checked again (m4-26). */
        val DIRTY_RECHECK_EVERY: Duration = 10.seconds

        /** How often a check that waits for a resource asks again (m4-26). */
        val CHECK_POLL: Duration = 250.milliseconds

        /** The start of the holder id of a check's lock, `check-<uuid>`, never an attempt id (m4-26). */
        const val CHECK_HOLDER: String = "check-"
    }
}
