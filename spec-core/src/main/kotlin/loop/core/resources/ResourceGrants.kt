package loop.core.resources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceProvider
import loop.core.events.ResourceCheckFailed
import loop.core.events.ResourceReleased
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
 * Locks of other projects are their own runs' to prepare.
 *
 * Since bl-204 a tick also frees as abandoned the rows of every other project that [live] says has no live run, so a
 * killed run never keeps a resource from the others, and frees again the rows of each attempt whose free at its end
 * failed. [live] is never asked about [projectId], whose run is this one.
 *
 * Since m4-26 the provider's own read only check runs right after each provider `release`, and a difference marks the
 * resource dirty in the same change that frees the lock, without asking the agent, since the resource is gone. At the
 * end of an attempt a lock still held first gets its declared `read_only_check`, through [commands], while the resource
 * is up: a failure runs the `restore` entries once, then the check again, and a second failure marks it dirty. A dirty
 * resource some request waits for is checked again on a tick, under the lock of a recheck holder, at most once per
 * [recheckEvery], and a pass clears the mark. [withCheckResource] lends a resource of a kind to a check of the
 * verification or of the merge check.
 *
 * Since bl-208 no provider call holds the others up. Each call runs on a thread of its own with a timeout of
 * [providerTimeout], whose thread is interrupted and left behind when it passes, and the call fails. The prepares and
 * the releases of a [tick], and its rechecks, run in the background: one call at a time per lock, and a call still
 * running on a lock is not started again. A tick waits on a provider only when it frees again an attempt whose free
 * failed, which runs that attempt's provider calls inline. The end of an attempt and a check wait only for the calls in
 * flight on their own locks, and since bl-219 hold the mutex of every lock they give back or free, one granted to the
 * attempt while they waited too. A provider error, even an [Error] such as a [LinkageError], is the failure of its
 * call, never of the run. A lock whose prepare ran is released at its end even when the outcome was never written. A
 * prepare that ends after its lock was freed, which no path of the grants does while the prepare runs, is reported as
 * an unexpected state and never released, since the resource may be another holder's by now. [close], at the end of
 * `loop run`, cancels the background calls and interrupts the provider calls in flight, and a call it cancels before
 * its thread runs it never runs.
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
    private val providerTimeout: Duration = PROVIDER_TIMEOUT,
    /** The threads the provider calls run on (bl-208), a pool of daemons unless a test holds them (bl-219). */
    private val providerCalls: Executor = PROVIDER_CALLS,
    private val failed: (ResourceLock, String) -> Unit = { _, _ -> },
) {
    private val providers: Map<String, ResourceProvider> = providers.flatMap { provider -> provider.kinds.map { it to provider } }.toMap()

    /**
     * One mutex per lock, by attempt, kind and resource id, and per recheck of a resource: the provider calls on one
     * lock never overlap, and a tick skips a lock whose mutex is taken (bl-208). A key is kept only while it is in use
     * (bl-219).
     */
    private val guards = LockGuards<LockKey>()

    /** The background calls of the ticks (bl-208), which [idle] waits for. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** The failure of a background recheck, given as the failure of the next tick. */
    private val recheckFailure = AtomicReference<Throwable?>(null)

    /** The attempts whose free at their end failed, with its reason, freed again on the next tick (bl-204). */
    private val unfreed: MutableMap<AttemptId, String> = ConcurrentHashMap()

    /**
     * The locks, by attempt, kind and resource id, whose provider `release` and own check ran already, with the dirty
     * mark the check gave, so a free tried again never releases twice (bl-204). Null marks are values, hence a
     * synchronized map rather than a concurrent one.
     */
    private val givenBack: MutableMap<LockKey, DirtyResource?> = Collections.synchronizedMap(mutableMapOf())

    /** The locks whose declared check and restore ran at the end of their attempt, with the dirty mark they gave (m4-26). */
    private val endChecked: MutableMap<LockKey, DirtyResource?> = Collections.synchronizedMap(mutableMapOf())

    /** The locks whose provider `prepare` ran in this process, released at the end even with no outcome written (bl-208). */
    private val prepareRan: MutableSet<LockKey> = ConcurrentHashMap.newKeySet()

    /** When the recheck of each dirty resource, by kind and id, last ended (m4-26). */
    private val rechecked: MutableMap<Pair<String, String>, TimeSource.Monotonic.ValueTimeMark> = ConcurrentHashMap()

    /**
     * One pass over the locks: the abandoned rows of other projects and the frees that failed, then the releases, the
     * dirty resources someone waits for, and the prepares of the project, these three started in the background
     * (bl-208). A failed read or change of the rows is the failure, and so is a failed recheck of an earlier tick; the
     * next tick tries again.
     */
    suspend fun tick(): Result<Unit> {
        recheckFailure.getAndSet(null)?.let { return Result.failure(it) }
        locks.freeAbandoned(projectId, live).onFailure { return Result.failure(it) }
        for ((attemptId, reason) in unfreed.toMap()) {
            free(attemptId, reason).onFailure { return Result.failure(it) }
        }
        val released = mine().getOrElse { return Result.failure(it) }.filter { it.releaseRequestedAt != null }
        for (lock in released) inBackground(lock.key, { failed(lock, it) }) { giveBackAndRelease(lock) }
        recheckDirty().onFailure { return Result.failure(it) }
        val unprepared =
            mine().getOrElse { return Result.failure(it) }.filter {
                it.releaseRequestedAt == null &&
                    it.prepared == null &&
                    it.prepareError == null &&
                    it.attemptId !in unfreed &&
                    !it.attemptId.value.startsWith(RECHECK_HOLDER)
            }
        for (lock in unprepared) inBackground(lock.key, { failed(lock, it) }) { prepareInBackground(lock) }
        return Result.success(Unit)
    }

    /**
     * Cancels the background calls of the ticks and interrupts the provider calls they run (bl-208), for the end of
     * `loop run`. A later tick starts no background call; what a canceled call left is for the providers' `recover`.
     */
    fun close() {
        background.cancel()
    }

    /**
     * What the grants keep for each lock, by the name of the map, the empty ones left out (bl-219): nothing once every
     * lock was freed and no call runs.
     */
    internal fun kept(): Map<String, List<String>> =
        mapOf(
            "guards" to guards.keys().map { it.toString() },
            "givenBack" to synchronized(givenBack) { givenBack.keys.map { it.toString() } },
            "endChecked" to synchronized(endChecked) { endChecked.keys.map { it.toString() } },
            "prepareRan" to prepareRan.map { it.toString() },
        ).filterValues { it.isNotEmpty() }

    /** Waits until no background call of a tick runs (bl-208). */
    suspend fun idle() {
        // A copy through toArray, which a concurrent set answers safely while the calls remove themselves: toList reads
        // the size first, then the first element, which a call that just ended may have taken away.
        while (running.isNotEmpty()) ArrayList(running).joinAll()
    }

    /**
     * Starts [block] in the background under the mutex of [key], unless a call holds it already. A failure [block]
     * throws goes to [failure].
     */
    private fun inBackground(
        key: LockKey,
        failure: (String) -> Unit,
        block: suspend () -> Unit,
    ) {
        if (!background.isActive) return
        val unlock = guards.tryLock(key) ?: return
        val job =
            background.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure("the background call failed: ${e.describe()}")
                } finally {
                    unlock()
                }
            }
        running += job
        job.invokeOnCompletion { running -= job }
    }

    /** The released [lock], given back through its provider and freed, when it is still released and held. */
    private suspend fun giveBackAndRelease(lock: ResourceLock) {
        val current = current(lock).getOrElse { return failed(lock, "cannot free: ${it.describe()}") } ?: return
        if (current.releaseRequestedAt == null) return
        val dirty = giveBackOnce(current)
        locks
            .release(current.attemptId, current.kind, current.resourceId, dirty)
            .onSuccess { forget(listOf(current.key)) }
            .onFailure { failed(current, "cannot free: ${it.describe()}") }
    }

    /**
     * The prepare of [lock] when it is still held and unprepared. Every path of the grants that frees a lock holds its
     * mutex, so a lock freed while its prepare ran was freed past them: the provider's `release` is never called, since
     * the resource may be another holder's by now, and the state is reported (bl-219).
     */
    private suspend fun prepareInBackground(lock: ResourceLock) {
        val current = current(lock).getOrElse { return failed(lock, "cannot prepare: ${it.describe()}") } ?: return
        if (current.releaseRequestedAt != null || current.prepared != null || current.prepareError != null) return
        prepareAndWrite(current)
        val after = current(current).getOrElse { return }
        if (after != null) return
        failed(current, "unexpected state: the lock was freed while its prepare ran, the resource is not released")
        forget(listOf(current.key))
    }

    /** The row of [lock] as it is now, by attempt, kind and resource id; null when it was freed. */
    private suspend fun current(lock: ResourceLock): Result<ResourceLock?> =
        locks.rows().map { rows -> rows.locks.firstOrNull { it.key == lock.key } }

    /** Runs [block] under the mutexes of [keys], taken in one order, so two callers never wait on each other. */
    private suspend fun <T> withGuards(
        keys: List<LockKey>,
        block: suspend () -> T,
    ): T = guards.withLocks(keys.distinct().sortedBy { it.toString() }, block)

    /**
     * Runs [block] with the rows and the locks [held] picks from them, read under the mutex of each of those locks, from
     * [first] on (bl-219). A lock granted after [first] was read, by another holder's free, is not guarded yet: then
     * nothing runs, the mutexes are left, and those of every lock seen so far are taken again in their order, until a
     * read under them finds no new lock. A failed read is the failure.
     */
    private suspend fun <T> withHeldGuards(
        first: List<LockKey>,
        held: (ResourceLockRows) -> List<ResourceLock>,
        block: suspend (ResourceLockRows, List<ResourceLock>) -> Result<T>,
    ): Result<T> {
        var keys = first.distinct()
        while (true) {
            var missed = emptyList<LockKey>()
            val done =
                withGuards(keys) {
                    val rows = locks.rows().getOrElse { return@withGuards Result.failure(it) }
                    val mine = held(rows)
                    missed = mine.map { it.key }.filter { it !in keys }
                    if (missed.isEmpty()) block(rows, mine) else null
                }
            if (done != null) return done
            keys = keys + missed
        }
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
    ): Result<ResourceChange> = free(attemptId, reason)

    /**
     * Runs [block] with a resource of [kind] lent to a check (m4-26) of the issue [issueId], [issueIdentifier]: the
     * check asks for any resource of the kind that `resources:` declares, waits its turn in queue order, polling every
     * [poll], gets it prepared, and [block] gets what the provider's `prepare` returned. The resource is given back and
     * freed after [block], whatever its end, and its provider check can mark it dirty like any release; the declared
     * check and its restore are skipped when the caller is canceled, so a cancellation never waits on them. A wait is
     * never a failure: it lasts until the resource is granted or the caller is canceled, and [waiting] hears of it
     * every [warnEvery]. The failure is a kind no entry declares, a prepare that failed or a store that refused a change.
     *
     * A check of the verification runs inside its attempt [attemptId], whose locks are freed only at its end, after
     * the check, so first the locks and requests that attempt holds in the pool of [kind], or of [kind] alone when it
     * has no pool, are given back as at the end of an attempt, recorded with the reason `check`: a check never waits
     * on its own attempt. The merge check has no attempt.
     */
    suspend fun <T> withCheckResource(
        kind: String,
        issueId: String,
        issueIdentifier: String,
        attemptId: AttemptId? = null,
        poll: Duration = CHECK_POLL,
        warnEvery: Duration = CHECK_WAIT_WARN_EVERY,
        waiting: (Duration) -> Unit = {},
        block: suspend (Map<String, String>) -> T,
    ): Result<T> {
        val ids = declarations().filter { it.kind == kind }.map { it.id }
        if (ids.isEmpty()) return Result.failure(ResourceException("no entry of resources declares the kind \"$kind\""))
        if (attemptId != null) {
            val kinds = ResourceLimits.poolOf(kind)?.kinds ?: setOf(kind)
            takeBack(attemptId, kinds).onFailure { return Result.failure(it) }
        }
        val holder = AttemptId("$CHECK_HOLDER${UUID.randomUUID()}")
        var canceled = false
        try {
            val started = TimeSource.Monotonic.markNow()
            var warned = Duration.ZERO
            while (true) {
                val grant = locks.requestAny(projectId, issueId, issueIdentifier, holder, kind, ids).getOrElse { return Result.failure(it) }
                if (grant is ResourceGrant.Granted) break
                val waited = started.elapsedNow()
                if (waited - warned >= warnEvery) {
                    warned = waited
                    waiting(waited)
                }
                delay(poll)
            }
            val prepared = preparedFor(holder, kind).getOrElse { return Result.failure(it) }
            return Result.success(block(prepared))
        } catch (e: CancellationException) {
            canceled = true
            throw e
        } finally {
            withContext(NonCancellable) { free(holder, ResourceReleased.RELEASED, declaredCheck = !canceled) }
        }
    }

    /**
     * Gives back, as at the end of an attempt, the locks of [kinds] that [attemptId] holds, and drops its requests of
     * them, recorded with the reason `check` (m4-26).
     */
    private suspend fun takeBack(
        attemptId: AttemptId,
        kinds: Set<String>,
    ): Result<ResourceChange> {
        fun ResourceLockRows.held() = this.locks.filter { it.attemptId == attemptId && it.kind in kinds }
        val before = locks.rows().getOrElse { return Result.failure(it) }
        val keys = before.held().map { it.key }
        // Read again under the mutexes: a prepare in flight may have written its outcome since.
        return withHeldGuards(keys, { it.held() }) { rows, held ->
            if (held.isEmpty() && rows.queue.none { it.attemptId == attemptId && it.kind in kinds }) {
                return@withHeldGuards Result.success(ResourceChange(rows))
            }
            val marks = held.mapNotNull { lock -> checkAndGiveBack(lock, declaredCheck = true) }
            locks.freeKinds(attemptId, kinds, ResourceReleased.CHECK, marks).onSuccess { forget((keys + held.map { it.key }).distinct()) }
        }
    }

    /** Drops what is kept of the locks of [keys], once freed; their mutexes go when their last caller leaves. */
    private fun forget(keys: List<LockKey>) {
        for (key in keys) {
            givenBack -= key
            endChecked -= key
            prepareRan -= key
        }
    }

    /** What the lock of [kind] that [holder] holds was prepared to, prepared now when the tick has not done it yet. */
    private suspend fun preparedFor(
        holder: AttemptId,
        kind: String,
    ): Result<Map<String, String>> {
        fun Result<ResourceLock?>.orFreed(): Result<ResourceLock> =
            mapCatching { it ?: throw ResourceException("the lock of the check was freed") }
        val found = locks.rows().map { rows -> rows.locks.firstOrNull { it.attemptId == holder && it.kind == kind } }
        val granted = found.orFreed().getOrElse { return Result.failure(it) }
        // Under the mutex of the lock, so a prepare a tick started is waited for, never run twice.
        return withGuards(listOf(granted.key)) {
            val lock = current(granted).orFreed().getOrElse { return@withGuards Result.failure(it) }
            lock.prepared?.let { return@withGuards Result.success(it) }
            lock.prepareError?.let { return@withGuards Result.failure(ResourceException("cannot prepare ${lock.resourceId}: $it")) }
            prepareAndWrite(lock).fold(
                { Result.success(it) },
                { Result.failure(ResourceException("cannot prepare ${lock.resourceId}: ${it.describe()}")) },
            )
        }
    }

    private suspend fun prepareAndWrite(lock: ResourceLock): Result<Map<String, String>> {
        val outcome = prepare(lock)
        outcome.onFailure { failed(lock, "prepare failed: ${it.describe()}") }
        locks.prepared(lock, outcome).onFailure { failed(lock, "cannot write the prepare result: ${it.describe()}") }
        return outcome
    }

    /**
     * Starts in the background a recheck of each dirty resource the workflow declares and some request waits for, once
     * per [recheckEvery] after the last recheck ended, and never two of one resource at a time.
     */
    private suspend fun recheckDirty(): Result<Unit> {
        val rows = locks.rows().getOrElse { return Result.failure(it) }
        for (mark in rows.dirty) {
            val key = mark.kind to mark.resourceId
            val waited =
                rows.queue.any {
                    it.kind == mark.kind && it.resourceId == mark.resourceId && !it.attemptId.value.startsWith(RECHECK_HOLDER)
                }
            if (!waited) continue
            val declaration = declarations().firstOrNull { it.kind == mark.kind && it.id == mark.resourceId } ?: continue
            val last = rechecked[key]
            if (last != null && last.elapsedNow() < recheckEvery) continue
            val failure = { reason: String -> recheckFailure.compareAndSet(null, ResourceException(reason)) }
            inBackground(LockKey(AttemptId(RECHECK_HOLDER), mark.kind, mark.resourceId), { failure(it) }) {
                val outcome = recheck(declaration)
                rechecked[key] = TimeSource.Monotonic.markNow()
                outcome.onFailure { recheckFailure.compareAndSet(null, it) }
            }
        }
        return Result.success(Unit)
    }

    /**
     * One recheck of the dirty [resource] under a real lock, held by a recheck holder, `recheck-<uuid>`, which the
     * queue rules grant on a dirty resource, so the recheck is exclusive across the processes of the machine and counts
     * in the pool of its kind. A resource held or at its limit is left for a later tick. Under the lock the provider
     * prepares it, its declared check runs with what that returned, the provider releases it and runs its own check; a
     * pass clears the mark in the change that frees the lock, which grants the resource to the head of its queue. A
     * free that fails is tried again on the next tick, the mark kept.
     */
    private suspend fun recheck(resource: ResourceDeclaration): Result<Unit> {
        val holder = AttemptId("$RECHECK_HOLDER${UUID.randomUUID()}")
        val grant = locks.request(projectId, "", "", holder, resource.kind, resource.id).getOrElse { return Result.failure(it) }
        val passed = grant is ResourceGrant.Granted && fullCheck(resource, holder) == null
        return locks
            .endRecheck(holder, resource.kind, resource.id, passed)
            .onFailure { unfreed[holder] = ResourceReleased.RELEASED }
            .map { }
    }

    /** The whole check of a resource no holder uses: the difference it found, null when it came back as lent. */
    private suspend fun fullCheck(
        resource: ResourceDeclaration,
        holder: AttemptId,
    ): String? {
        val provider = providers[resource.kind]
        val prepared =
            when (provider) {
                null -> emptyMap()
                else ->
                    guarded { provider.prepare(resource, holder) }.getOrElse { error ->
                        // A failed prepare may have started something, so it is released like a lock's (giveBack).
                        guarded { provider.release(resource) }
                        return "prepare failed: ${error.describe()}"
                    }
            }
        val declared = ReadOnlyChecks.run(resource, prepared, commands)
        if (provider == null) return declared
        val released = guarded { provider.release(resource) }.exceptionOrNull()?.let { "release failed: ${it.describe()}" }
        val own = guarded { provider.readOnlyCheck(resource) }.fold({ it.joinToString("\n").ifEmpty { null } }, { it.describe() })
        return declared ?: released ?: own
    }

    /**
     * Checks each lock of [attemptId], gives it back and frees its rows with the dirty marks the checks gave, or keeps
     * the attempt for the next tick. It holds the mutexes of the attempt's locks throughout, those of locks granted to
     * it while it waited too (bl-219), so it waits for a call in flight on them and no tick starts a prepare on them
     * meanwhile (bl-208).
     */
    private suspend fun free(
        attemptId: AttemptId,
        reason: String,
        declaredCheck: Boolean = true,
    ): Result<ResourceChange> {
        val before =
            locks.rows().getOrElse { error ->
                unfreed[attemptId] = reason
                return Result.failure(error)
            }
        val keys = before.locks.filter { it.attemptId == attemptId }.map { it.key }
        // Read again under the mutexes: a prepare in flight may have written its outcome since.
        return withHeldGuards(keys, { rows -> rows.locks.filter { it.attemptId == attemptId } }) { _, held ->
            val marks = held.mapNotNull { lock -> checkAndGiveBack(lock, declaredCheck) }
            locks.freeAttempt(attemptId, reason, marks).onSuccess { forget((keys + held.map { it.key }).distinct()) }
        }.onSuccess { unfreed -= attemptId }
            .onFailure { unfreed[attemptId] = reason }
    }

    /**
     * The declared check of [lock], run once, when [declaredCheck], then the provider's `release` and own check: the dirty
     * mark the declared check gave, else the one of the provider's check, else null.
     */
    private suspend fun checkAndGiveBack(
        lock: ResourceLock,
        declaredCheck: Boolean,
    ): DirtyResource? {
        if (lock.key !in endChecked) endChecked[lock.key] = if (declaredCheck) endCheck(lock) else null
        val declared = endChecked[lock.key]
        val own = giveBackOnce(lock)
        return declared ?: own
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
     * the provider's check after it; a lock never prepared has none. A lock whose prepare ran in this process counts as
     * prepared even when its outcome was never written (bl-208).
     */
    private suspend fun giveBackOnce(lock: ResourceLock): DirtyResource? {
        if (lock.prepared == null && lock.prepareError == null && lock.key !in prepareRan) return null
        // A null mark is a value too, so getOrPut, which runs again on null, would release twice.
        if (lock.key !in givenBack) givenBack[lock.key] = giveBack(lock)
        return givenBack[lock.key]
    }

    private val ResourceLock.key: LockKey get() = LockKey(attemptId, kind, resourceId)

    /** A lock by its holder, kind and resource id. */
    private data class LockKey(
        val attemptId: AttemptId,
        val kind: String,
        val resourceId: String,
    )

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
        prepareRan += lock.key
        return guarded { provider.prepare(declaration(lock), lock.attemptId) }
    }

    private fun declaration(lock: ResourceLock): ResourceDeclaration =
        declarations().firstOrNull { it.kind == lock.kind && it.id == lock.resourceId } ?: ResourceDeclaration(lock.kind, lock.resourceId)

    /**
     * Runs a provider call, which blocks, on a thread of its own, for at most [providerTimeout] (bl-208): past it, or
     * when the caller is canceled, the thread is interrupted and left to end on its own. Anything the call throws but a
     * cancellation is the failure, an [Error] too, so a broken provider jar never ends the run.
     */
    private suspend fun <T> guarded(call: () -> T): Result<T> {
        val call = InterruptibleCall(call)
        val future = CompletableFuture.supplyAsync(call::run, providerCalls)
        return try {
            // On the default dispatcher, so the timeout counts real time even under a test scheduler.
            Result.success(withContext(Dispatchers.Default) { withTimeout(providerTimeout) { future.await() } })
        } catch (e: TimeoutCancellationException) {
            call.interrupt()
            Result.failure(ResourceException("the provider did not answer in $providerTimeout"))
        } catch (e: CancellationException) {
            call.interrupt()
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** A provider call that can be interrupted while it runs, and only then, so a later call on its thread never is. */
    private class InterruptibleCall<T>(
        private val call: () -> T,
    ) {
        private val lock = Any()
        private var thread: Thread? = null

        /** Set by [interrupt], so a call interrupted before its thread runs it never runs (bl-219). */
        private var canceled = false

        fun run(): T {
            synchronized(lock) {
                if (canceled) throw CancellationException("the provider call was canceled before it started")
                thread = Thread.currentThread()
            }
            try {
                return call()
            } finally {
                synchronized(lock) {
                    thread = null
                    Thread.interrupted()
                }
            }
        }

        fun interrupt() =
            synchronized(lock) {
                canceled = true
                thread?.interrupt()
            }
    }

    private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"

    companion object {
        /** How long a provider `prepare`, `release` or read only check may run before it fails (bl-208). */
        val PROVIDER_TIMEOUT: Duration = 10.minutes

        /** The threads of the provider calls: daemons, so a call that never ends never keeps the JVM up (bl-208). */
        private val PROVIDER_CALLS: ExecutorService =
            Executors.newCachedThreadPool { runnable -> Thread(runnable, "loop-resource-provider").apply { isDaemon = true } }

        /** How often a dirty resource some request waits for is checked again (m4-26). */
        val DIRTY_RECHECK_EVERY: Duration = 10.seconds

        /** How often a check that waits for a resource asks again (m4-26). */
        val CHECK_POLL: Duration = 250.milliseconds

        /** How often a check that waits for a resource is said to wait (m4-26). */
        val CHECK_WAIT_WARN_EVERY: Duration = 5.minutes

        /** The start of the holder id of a check's lock, `check-<uuid>`, never an attempt id (m4-26). */
        const val CHECK_HOLDER: String = "check-"

        /** The start of the holder id of the lock a recheck of a dirty resource takes, `recheck-<uuid>` (m4-26). */
        const val RECHECK_HOLDER: String = "recheck-"
    }
}
