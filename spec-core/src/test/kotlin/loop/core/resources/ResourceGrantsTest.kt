package loop.core.resources

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import loop.api.ResourceLockRows
import loop.api.ResourceLockStore
import loop.api.ResourceLockUpdate
import loop.api.ResourceProvider
import loop.api.RunId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * m4-25: what `loop run` does with the locks of its project on each tick: it gives back each lock its holder released,
 * through the provider's `release`, then frees it, which grants it to the next in its queue; and it prepares each lock
 * not prepared yet, through the provider's `prepare`, or to nothing for a plain lock, writing the result on the lock.
 * At the end of an attempt the provider's `release` runs before the free, so the next holder never prepares first.
 */
class ResourceGrantsTest {
    private val now = Instant.parse("2026-11-25T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val p1 = ProjectId("p1")
    private val p2 = ProjectId("p2")

    /**
     * The rows in memory. A change is atomic, as a transaction of the real store is: since bl-208 the background calls
     * of a tick change the rows from several threads at once, and an unguarded read and write would lose one change.
     */
    private class MemoryStore : ResourceLockStore {
        @Volatile
        var rows = ResourceLockRows()

        /** The number of the next changes refused, as a busy store would. */
        @Volatile
        var refuse = 0

        /** Told of each change, with the rows before and after it, inside the change (bl-219). */
        @Volatile
        var changed: (ResourceLockRows, ResourceLockRows) -> Unit = { _, _ -> }

        override suspend fun resourceLockRows() = Result.success(rows)

        override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> =
            synchronized(this) {
                if (refuse > 0) {
                    refuse--
                    return Result.failure(IllegalStateException("the store is busy"))
                }
                val update = change(rows)
                changed(rows, update.rows)
                rows = update.rows
                Result.success(update.value)
            }
    }

    /** Records from several threads at once: the background calls of a tick free and grant locks (bl-249). */
    private class Sink : EventSink {
        val events: MutableList<RecordedEvent> = CopyOnWriteArrayList()

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            events += event
            return Result.success(Unit)
        }
    }

    /**
     * Records each call as `prepare <id>` or `release <id>`, in [calls], and the holder of each prepare in [holders],
     * and fails the prepare of [failing].
     */
    private class Provider(
        val calls: MutableList<String>,
        val failing: String? = null,
        val holders: MutableList<String> = mutableListOf(),
    ) : ResourceProvider {
        override val kinds = setOf("ios-simulator")

        override fun prepare(
            resource: ResourceDeclaration,
            attemptId: AttemptId,
        ): Map<String, String> {
            calls += "prepare ${resource.id}"
            holders += attemptId.value
            if (resource.id == failing) throw ResourceException("cannot boot ${resource.id}")
            return mapOf("udid" to "UDID-${resource.id}", "can" to resource.can.joinToString())
        }

        override fun release(resource: ResourceDeclaration) {
            calls += "release ${resource.id}"
        }

        override fun readOnlyCheck(resource: ResourceDeclaration): List<String> = emptyList()

        override fun recover(liveHolders: List<ResourceHolder>) = Unit
    }

    private val store = MemoryStore()
    private val sink = Sink()
    private val calls: MutableList<String> = CopyOnWriteArrayList()
    private val warnings: MutableList<String> = CopyOnWriteArrayList()
    private val locks = ResourceLocks(store, sink, clock, RunId("r1"))
    private val declarations =
        listOf(ResourceDeclaration("ios-simulator", "iphone", can = listOf("ui tests")), ResourceDeclaration("display", "main"))

    private val peeked = mutableListOf<ProjectId>()

    private fun grants(
        provider: ResourceProvider = Provider(calls),
        project: ProjectId = p1,
        live: (ProjectId) -> Boolean = { true },
        providerTimeout: Duration = ResourceGrants.PROVIDER_TIMEOUT,
        providerCalls: Executor? = null,
    ) = ResourceGrants(
        project,
        locks,
        listOf(provider),
        { declarations },
        live = {
            peeked += it
            live(it)
        },
        providerTimeout = providerTimeout,
        providerCalls = providerCalls ?: Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } },
    ) { lock, reason -> warnings += "${lock.resourceId}: $reason" }

    /** One tick, then a wait for the provider calls it started in the background (bl-208). */
    private suspend fun ResourceGrants.pass() {
        tick().getOrThrow()
        idle()
    }

    private suspend fun ask(
        attempt: String,
        kind: String = "ios-simulator",
        id: String = "iphone",
        project: ProjectId = p1,
    ) = locks.ask(project, "i-$attempt", "t-$attempt", AttemptId(attempt), kind, id).getOrThrow()

    private fun lock(id: String) = store.rows.locks.single { it.resourceId == id }

    @Test
    fun `a tick prepares the unprepared locks of its project with the declaration, a plain lock to nothing`() =
        runTest {
            ask("a1")
            ask("a2", kind = "display", id = "main")
            ask("b1", id = "other", project = p2)
            val holders = mutableListOf<String>()

            grants(Provider(calls, holders = holders)).pass()

            assertEquals(listOf("prepare iphone"), calls)
            assertEquals(listOf("a1"), holders, "bl-208: prepare gets the attempt that holds the lock")
            assertEquals(mapOf("udid" to "UDID-iphone", "can" to "ui tests"), lock("iphone").prepared)
            assertEquals(emptyMap(), lock("main").prepared)
            assertNull(lock("other").prepared, "a lock of another project is its own run's to prepare")
            grants().pass()
            assertEquals(listOf("prepare iphone"), calls, "a prepared lock is never prepared again")
        }

    /**
     * The release is held in the provider, so the state after the tick is fixed: the tick promises to start it in the
     * background, not when it ends, and once it ends the free may grant a2 before the tick reads the rows again, which
     * then prepares a2 in the same tick (bl-249).
     */
    @Test
    fun `a tick gives back a released lock through the provider, frees it and the next in its queue is prepared`() =
        runTest {
            val provider = GatedProvider(calls)
            provider.gate("release iphone")
            ask("a1")
            ask("a2")
            val grants = grants(provider)
            grants.pass()
            ask("a1")
            locks.requestRelease(AttemptId("a1"), "ios-simulator", "iphone").getOrThrow()

            grants.tick().getOrThrow()
            assertTrue(provider.entered("release iphone"), "the release runs in the background of the tick")
            assertEquals(listOf("prepare iphone a1", "prepared iphone a1", "release iphone"), calls)
            assertEquals(AttemptId("a1"), lock("iphone").attemptId, "the lock is freed only once its release ends")
            provider.open("release iphone")
            grants.idle()
            assertEquals(AttemptId("a2"), lock("iphone").attemptId, "the free grants the lock to the next in its queue")
            assertNull(lock("iphone").prepared)
            grants.pass()

            assertEquals(listOf("prepare iphone a1", "prepared iphone a1", "release iphone", "prepare iphone a2", "prepared iphone a2"), calls)
            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            assertEquals(mapOf("udid" to "UDID-iphone"), lock("iphone").prepared)
            assertEquals(emptyList(), provider.overlaps)
            assertEquals(
                listOf("resource_released a1 released", "resource_granted a2"),
                sink.events
                    .filter { it.kind == "resource_released" || (it.kind == "resource_granted" && it.attemptId?.value == "a2") }
                    .map { "${it.kind} ${it.attemptId?.value}" + (it.data["reason"]?.let { reason -> " $reason" } ?: "") },
            )
        }

    @Test
    fun `a failed prepare is written on the lock and warned, and the lock is given back once released`() =
        runTest {
            ask("a1")
            val grants = grants(Provider(calls, failing = "iphone"))
            grants.pass()

            assertEquals("cannot boot iphone", lock("iphone").prepareError)
            assertEquals(listOf("iphone: prepare failed: cannot boot iphone"), warnings)
            ask("a1")
            grants.pass()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
        }

    @Test
    fun `the end of an attempt runs the provider's release before the free, and the next holder is prepared after`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.pass()

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()
            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            grants.pass()

            assertEquals(listOf("prepare iphone", "release iphone", "prepare iphone"), calls)
        }

    @Test
    fun `an unprepared lock is freed at the attempt end without a release`() =
        runTest {
            ask("a1")

            grants().endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(emptyList(), calls)
            assertEquals(emptyList(), store.rows.locks)
        }

    @Test
    fun `bl-204 a tick frees the rows of a project with no live run as abandoned, and the next in the queue is granted`() =
        runTest {
            ask("a1", project = p2)
            ask("a2", project = p2, id = "other")
            ask("b1")

            grants(live = { it != p2 }).pass()

            assertEquals(listOf(AttemptId("b1")), store.rows.locks.map { it.attemptId })
            assertEquals(emptyList(), store.rows.queue)
            assertEquals(listOf(p2), peeked, "its own project is never peeked, the other once")
            assertEquals(
                listOf("a1 abandoned", "a2 abandoned"),
                sink.events.filter { it.kind == "resource_released" }.map { "${it.attemptId?.value} ${it.data["reason"]}" },
            )
            assertEquals(listOf("prepare iphone"), calls, "b1's lock is prepared on the same tick")
        }

    @Test
    fun `bl-204 a tick keeps the rows of a project whose run is live or unreadable`() =
        runTest {
            ask("a1", project = p2)
            ask("b1")

            grants(live = { true }).pass()

            assertEquals(listOf(AttemptId("a1")), store.rows.locks.map { it.attemptId })
            assertEquals(listOf(AttemptId("b1")), store.rows.queue.map { it.attemptId })
        }

    @Test
    fun `bl-204 a free that failed at the attempt end is retried on the next tick, without a second release`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.pass()
            store.refuse = 1

            val ended = grants.endAttempt(AttemptId("a1"), "attempt_ended")
            assertEquals(true, ended.isFailure)
            assertEquals(AttemptId("a1"), lock("iphone").attemptId)
            grants.pass()

            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            assertEquals(listOf("prepare iphone", "release iphone", "prepare iphone"), calls)
            assertEquals(
                listOf("a1 attempt_ended"),
                sink.events.filter { it.kind == "resource_released" }.map { "${it.attemptId?.value} ${it.data["reason"]}" },
            )
        }

    @Test
    fun `m4-26 a dirty resource is rechecked under the lock of a recheck holder, and a failed prepare is released`() =
        runTest {
            store.rows = store.rows.copy(dirty = listOf(DirtyResource("ios-simulator", "iphone", p1, "provider", "app installed", now)))
            ask("a1")

            grants(Provider(calls, failing = "iphone")).pass()

            assertEquals(listOf("prepare iphone", "release iphone"), calls, "a failed prepare is released like a lock's")
            assertEquals(1, store.rows.dirty.size, "the failed recheck keeps the mark")
            assertEquals(emptyList(), store.rows.locks, "the recheck holder's lock is freed")
            assertEquals(listOf(AttemptId("a1")), store.rows.queue.map { it.attemptId })
            val recheck = sink.events.filter { it.kind == "resource_granted" || it.kind == "resource_released" }
            assertEquals(listOf("resource_granted", "resource_released"), recheck.map { it.kind }, "the recheck held a real lock")
            assertTrue(recheck.all { it.attemptId == null && it.issueId == null }, "$recheck")
        }

    @Test
    fun `m4-26 a recheck waits for a later tick when the pool of the dirty resource is at its limit`() =
        runTest {
            store.rows = store.rows.copy(dirty = listOf(DirtyResource("ios-simulator", "iphone", p1, "provider", "app installed", now)))
            ask("a1")
            ask("b1", id = "sim-1", project = p2)
            ask("b2", id = "sim-2", project = p2)

            grants().pass()

            assertEquals(emptyList(), calls, "no check ran outside the limit")
            assertEquals(listOf(AttemptId("a1")), store.rows.queue.map { it.attemptId }, "the recheck's request went")
            assertEquals(1, store.rows.dirty.size)
        }

    @Test
    fun `m4-26 a check takes back the lock its own attempt holds in the pool, then holds it itself`() =
        runTest {
            ask("a1")
            val grants = grants()
            grants.pass()

            val prepared =
                grants
                    .withCheckResource("ios-simulator", "i-a1", "t-a1", AttemptId("a1")) { it }
                    .getOrThrow()

            assertEquals("UDID-iphone", prepared["udid"])
            assertEquals(listOf("prepare iphone", "release iphone", "prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
            val released = sink.events.filter { it.kind == "resource_released" }.map { "${it.attemptId?.value} ${it.data["reason"]}" }
            assertEquals(listOf("a1 check", "null released"), released)
        }

    /**
     * A provider whose calls on the resource [stuck] block until [gate] opens, deaf to interrupts, as a hung provider
     * would: its `prepare` when [hangOn] is `prepare`, its `release` when it is `release`. [entered] opens once it blocks,
     * [interrupted] once an interrupt reached it.
     */
    private class HangingProvider(
        val calls: MutableList<String>,
        val hangOn: String,
        val stuck: String = "iphone",
        val gate: CountDownLatch = CountDownLatch(1),
        val entered: CountDownLatch = CountDownLatch(1),
        val deaf: Boolean = true,
        val interrupted: CountDownLatch = CountDownLatch(1),
    ) : ResourceProvider {
        override val kinds = setOf("ios-simulator")

        override fun prepare(
            resource: ResourceDeclaration,
            attemptId: AttemptId,
        ): Map<String, String> {
            calls += "prepare ${resource.id}"
            if (hangOn == "prepare" && resource.id == stuck) hang()
            return mapOf("udid" to "UDID-${resource.id}")
        }

        override fun release(resource: ResourceDeclaration) {
            calls += "release ${resource.id}"
            if (hangOn == "release" && resource.id == stuck) hang()
        }

        override fun readOnlyCheck(resource: ResourceDeclaration): List<String> = emptyList()

        override fun recover(liveHolders: List<ResourceHolder>) = Unit

        private fun hang() {
            entered.countDown()
            while (true) {
                try {
                    gate.await()
                    return
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    if (!deaf) throw e
                }
            }
        }
    }

    @Test
    fun `bl-208 a hung prepare never holds the tick, nor the end of another attempt, and fails after the core timeout`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "prepare")
            ask("a1")
            ask("a2", id = "ipad")
            val grants = grants(provider, providerTimeout = 3.seconds)

            val started = TimeSource.Monotonic.markNow()
            grants.tick().getOrThrow()
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS), "the prepare of iphone started")
            grants.tick().getOrThrow()
            grants.endAttempt(AttemptId("a2"), "attempt_ended").getOrThrow()
            val waited = started.elapsedNow()

            assertTrue(waited < 2.seconds, "the ticks and the end of a2 waited on the hung prepare: $waited")
            assertEquals(setOf("prepare iphone", "prepare ipad", "release ipad"), calls.toSet())
            assertEquals(listOf(AttemptId("a1")), store.rows.locks.map { it.attemptId })
            grants.idle()
            assertEquals("the provider did not answer in 3s", lock("iphone").prepareError)
            assertTrue(warnings.contains("iphone: prepare failed: the provider did not answer in 3s"), "$warnings")
            provider.gate.countDown()
        }

    @Test
    fun `bl-208 a hung release at the end of an attempt fails after the core timeout and the lock is freed`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "release")
            ask("a1")
            ask("a2")
            val grants = grants(provider, providerTimeout = 300.milliseconds)
            grants.pass()

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            assertTrue(warnings.contains("iphone: release failed: the provider did not answer in 300ms"), "$warnings")
            provider.gate.countDown()
        }

    @Test
    fun `bl-208 a prepare that stops on the interrupt of the core timeout fails the same way`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "prepare", deaf = false)
            ask("a1")

            grants(provider, providerTimeout = 200.milliseconds).pass()

            assertEquals("the provider did not answer in 200ms", lock("iphone").prepareError)
        }

    @Test
    fun `bl-208 the end of an attempt waits for the prepare in flight on its lock, then releases it`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "prepare")
            ask("a1")
            val grants = grants(provider)
            grants.tick().getOrThrow()
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS), "the prepare started")

            val ended = async { grants.endAttempt(AttemptId("a1"), "attempt_ended") }
            // The end of a1 runs until it suspends, then real time passes, so a release started on the provider's thread
            // without waiting for the prepare would have been recorded.
            yield()
            Thread.sleep(500)
            yield()
            assertFalse(ended.isCompleted, "the end of a1 did not wait for its prepare in flight")
            assertEquals(listOf("prepare iphone"), calls, "the release started while the prepare was in flight")
            provider.gate.countDown()
            ended.await().getOrThrow()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
        }

    @Test
    fun `bl-208 a lock whose prepare ran but whose outcome was not written is released at the end of its attempt`() =
        runTest {
            val provider =
                object : ResourceProvider by Provider(calls) {
                    override fun prepare(
                        resource: ResourceDeclaration,
                        attemptId: AttemptId,
                    ): Map<String, String> {
                        calls += "prepare ${resource.id}"
                        store.refuse = 1
                        return mapOf("udid" to "UDID-${resource.id}")
                    }
                }
            ask("a1")
            val grants = grants(provider)
            grants.pass()
            assertNull(lock("iphone").prepared, "the write of the outcome failed")

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
        }

    @Test
    fun `bl-208 a provider Error is a failure of its call, never of the tick or of the free`() =
        runTest {
            val provider =
                object : ResourceProvider by Provider(calls) {
                    override fun prepare(
                        resource: ResourceDeclaration,
                        attemptId: AttemptId,
                    ): Map<String, String> = throw LinkageError("a class of the provider jar is missing")

                    override fun release(resource: ResourceDeclaration): Unit = throw NoClassDefFoundError("loop/Gone")
                }
            ask("a1")
            ask("a2")
            val grants = grants(provider)

            grants.pass()
            assertEquals("a class of the provider jar is missing", lock("iphone").prepareError)
            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            assertTrue(warnings.contains("iphone: release failed: loop/Gone"), "$warnings")
        }

    @Test
    fun `bl-208 a hung release of a lock its holder released never holds the ticks, and fails after the core timeout`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "release")
            ask("a1")
            ask("a2", id = "ipad")
            val grants = grants(provider, providerTimeout = 5.seconds)
            grants.pass()
            locks.requestRelease(AttemptId("a1"), "ios-simulator", "iphone").getOrThrow()

            val started = TimeSource.Monotonic.markNow()
            grants.tick().getOrThrow()
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS), "the release of iphone started")
            grants.tick().getOrThrow()
            grants.endAttempt(AttemptId("a2"), "attempt_ended").getOrThrow()
            val waited = started.elapsedNow()
            grants.idle()

            assertTrue(waited < 3.seconds, "the ticks and the end of a2 waited on the hung release: $waited")
            assertTrue(warnings.contains("iphone: release failed: the provider did not answer in 5s"), "$warnings")
            assertEquals(emptyList(), store.rows.locks, "the lock of the hung release is freed after the timeout")
            provider.gate.countDown()
        }

    @Test
    fun `bl-208 a provider Error from the read only check marks the resource dirty and the lock is still freed`() =
        runTest {
            val provider =
                object : ResourceProvider by Provider(calls) {
                    override fun readOnlyCheck(resource: ResourceDeclaration): List<String> =
                        throw LinkageError("a class of the provider's check is missing")
                }
            ask("a1")
            val grants = grants(provider)
            grants.pass()

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
            assertEquals(listOf("the check failed: a class of the provider's check is missing"), store.rows.dirty.map { it.difference })
        }

    @Test
    fun `bl-208 close cancels the background calls of the ticks and interrupts the provider call in flight`() =
        runTest {
            val provider = HangingProvider(calls, hangOn = "prepare", deaf = false)
            ask("a1")
            val grants = grants(provider)
            grants.tick().getOrThrow()
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS), "the prepare started")

            grants.close()

            assertTrue(provider.interrupted.await(2, TimeUnit.SECONDS), "the prepare in flight was not interrupted")
            grants.idle()
            grants.tick().getOrThrow()
            grants.idle()
            assertEquals(listOf("prepare iphone"), calls, "a tick after close started a provider call")
        }

    /**
     * A provider whose calls named in [gate], such as `prepare ipad a1`, block until [open], and that records each call
     * as `prepare <id> <holder>` or `release <id>` when it starts, and `prepared <id> <holder>` when a prepare ends
     * (bl-219). Two calls on one resource at a time are recorded in [overlaps].
     */
    private class GatedProvider(
        val calls: MutableList<String>,
    ) : ResourceProvider {
        override val kinds = setOf("ios-simulator")

        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val entered = ConcurrentHashMap<String, CountDownLatch>()
        private val inFlight = ConcurrentHashMap<String, AtomicInteger>()
        val overlaps: MutableList<String> = CopyOnWriteArrayList()

        fun gate(call: String) {
            gates[call] = CountDownLatch(1)
            entered[call] = CountDownLatch(1)
        }

        fun open(call: String) = gates.getValue(call).countDown()

        fun entered(call: String): Boolean = entered.getValue(call).await(5, TimeUnit.SECONDS)

        /** Whether a call on the resource [id] runs now. */
        fun busy(id: String): Boolean = (inFlight[id]?.get() ?: 0) > 0

        private fun <T> call(
            id: String,
            name: String,
            block: () -> T,
        ): T {
            val running = inFlight.computeIfAbsent(id) { AtomicInteger() }
            if (running.incrementAndGet() > 1) overlaps += "$name while another call on $id ran"
            calls += name
            try {
                entered[name]?.countDown()
                gates[name]?.await()
                return block()
            } finally {
                running.decrementAndGet()
            }
        }

        override fun prepare(
            resource: ResourceDeclaration,
            attemptId: AttemptId,
        ): Map<String, String> =
            call(resource.id, "prepare ${resource.id} ${attemptId.value}") {
                calls += "prepared ${resource.id} ${attemptId.value}"
                mapOf("udid" to "UDID-${resource.id}")
            }

        override fun release(resource: ResourceDeclaration) = call(resource.id, "release ${resource.id}") { }

        override fun readOnlyCheck(resource: ResourceDeclaration): List<String> = emptyList()

        override fun recover(liveHolders: List<ResourceHolder>) = Unit
    }

    /** The locks the store freed while a call of [provider] on their resource still ran (bl-219). */
    private fun freedDuringCalls(provider: GatedProvider): MutableList<String> {
        val freed: MutableList<String> = CopyOnWriteArrayList()
        store.changed = { before, after ->
            val kept = after.locks.map { Triple(it.attemptId, it.kind, it.resourceId) }.toSet()
            before.locks
                .filter { Triple(it.attemptId, it.kind, it.resourceId) !in kept && provider.busy(it.resourceId) }
                .forEach { freed += "${it.attemptId.value} ${it.resourceId}" }
        }
        return freed
    }

    /**
     * a1 holds iphone, its prepare blocked, and waits in the queue of ipad, which b1 holds, before c1. Returns once the
     * prepare of iphone for a1 runs.
     */
    private suspend fun lateGrant(provider: GatedProvider): ResourceGrants {
        provider.gate("prepare iphone a1")
        provider.gate("prepare ipad a1")
        ask("b1", id = "ipad")
        val grants = grants(provider)
        grants.pass()
        ask("a1")
        ask("a1", id = "ipad")
        ask("c1", id = "ipad")
        grants.tick().getOrThrow()
        assertTrue(provider.entered("prepare iphone a1"), "the prepare of iphone for a1 started")
        return grants
    }

    /**
     * The rest of [lateGrant] once [waiting], the end of a1 or a check's take back, waits on the guard of iphone: b1
     * ends, so ipad goes to a1 and a tick starts its prepare, then the prepare of iphone ends. [waiting] must wait for
     * the prepare of ipad, release ipad once after it, and only then free it to c1.
     */
    private suspend fun grantedWhileWaiting(
        grants: ResourceGrants,
        provider: GatedProvider,
        waiting: Deferred<*>,
    ) {
        val freed = freedDuringCalls(provider)
        Thread.sleep(300)
        assertFalse(waiting.isCompleted, "it did not wait for the prepare of iphone")
        grants.endAttempt(AttemptId("b1"), "attempt_ended").getOrThrow()
        assertEquals(AttemptId("a1"), lock("ipad").attemptId)
        grants.tick().getOrThrow()
        assertTrue(provider.entered("prepare ipad a1"), "the prepare of ipad for a1 started")

        provider.open("prepare iphone a1")
        Thread.sleep(500)

        assertFalse(waiting.isCompleted, "it did not wait for the prepare of ipad")
        assertEquals(1, calls.count { it == "release ipad" }, "ipad was released during its prepare: $calls")
        assertEquals(AttemptId("a1"), lock("ipad").attemptId, "ipad was freed during its prepare")
        provider.open("prepare ipad a1")
        waiting.await()

        val prepared = calls.indexOf("prepared ipad a1")
        assertEquals(2, calls.count { it == "release ipad" }, "$calls")
        assertTrue(prepared >= 0 && calls.lastIndexOf("release ipad") > prepared, "$calls")
        assertEquals(AttemptId("c1"), lock("ipad").attemptId)
        assertEquals(emptyList(), provider.overlaps)
        assertEquals(emptyList(), freed)
    }

    @Test
    fun `bl-219 a lock granted while the end of its attempt waits on another guard is released after its prepare, then freed`() =
        runTest {
            val provider = GatedProvider(calls)
            val grants = lateGrant(provider)

            val ended = async(Dispatchers.Default) { grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow() }
            grantedWhileWaiting(grants, provider, ended)

            assertEquals(listOf("ipad"), store.rows.locks.map { it.resourceId })
            grants.pass()
            assertTrue("prepare ipad c1" in calls, "$calls")
        }

    @Test
    fun `bl-219 a lock granted while a check's take back waits on another guard is released after its prepare, then freed`() =
        runTest {
            val provider = GatedProvider(calls)
            val grants = lateGrant(provider)

            val checked =
                async(Dispatchers.Default) {
                    grants
                        .withCheckResource("ios-simulator", "i-a1", "t-a1", AttemptId("a1"), poll = 20.milliseconds) { it }
                        .getOrThrow()
                }
            grantedWhileWaiting(grants, provider, checked)

            val released = sink.events.filter { it.kind == "resource_released" }.map { "${it.attemptId?.value} ${it.data["reason"]}" }
            assertTrue(released.count { it == "a1 check" } == 2, "$released")
        }

    /**
     * a1 holds iphone, prepared, and waits in the queue of ipad, which b1 holds unprepared, before c1. The release of
     * iphone and the prepare of ipad for a1 block (bl-219).
     */
    private suspend fun grantedWhileGivingBack(provider: GatedProvider): ResourceGrants {
        provider.gate("release iphone")
        provider.gate("prepare ipad a1")
        ask("a1")
        val grants = grants(provider)
        grants.pass()
        ask("b1", id = "ipad")
        ask("a1", id = "ipad")
        ask("c1", id = "ipad")
        return grants
    }

    /**
     * The rest of [grantedWhileGivingBack] once [giving], the end of a1 or a check's take back, gives iphone back: b1
     * ends, so ipad goes to a1 and a tick starts its prepare, which ends before the release of iphone when
     * [prepareFirst]. [giving] must leave ipad to a1 until its prepare ended, release it once after, and only then free
     * it to c1.
     */
    private suspend fun grantedWhileGivingBack(
        grants: ResourceGrants,
        provider: GatedProvider,
        giving: Deferred<*>,
        prepareFirst: Boolean,
    ) {
        val freed = freedDuringCalls(provider)
        assertTrue(provider.entered("release iphone"), "the release of iphone started")
        grants.endAttempt(AttemptId("b1"), "attempt_ended").getOrThrow()
        assertEquals(AttemptId("a1"), lock("ipad").attemptId)
        grants.tick().getOrThrow()
        assertTrue(provider.entered("prepare ipad a1"), "the prepare of ipad for a1 started")

        if (prepareFirst) {
            provider.open("prepare ipad a1")
            grants.idle()
            provider.open("release iphone")
        } else {
            provider.open("release iphone")
            Thread.sleep(500)
            assertFalse(giving.isCompleted, "it did not wait for the prepare of ipad")
            assertEquals(0, calls.count { it == "release ipad" }, "ipad was released during its prepare: $calls")
            assertEquals(AttemptId("a1"), lock("ipad").attemptId, "ipad was freed during its prepare")
            provider.open("prepare ipad a1")
        }
        giving.await()

        val prepared = calls.indexOf("prepared ipad a1")
        assertEquals(1, calls.count { it == "release ipad" }, "$calls")
        assertTrue(prepared >= 0 && calls.indexOf("release ipad") > prepared, "$calls")
        val check = calls.indexOfFirst { it.startsWith("prepare iphone check-") }.takeIf { it >= 0 } ?: calls.size
        assertEquals(1, calls.take(check).count { it == "release iphone" }, "$calls")
        assertEquals(AttemptId("c1"), lock("ipad").attemptId)
        assertEquals(emptyList(), provider.overlaps)
        assertEquals(emptyList(), freed)
        assertEquals(emptyList(), warnings.filter { "unexpected state" in it })
        assertEquals(emptyMap(), grants.kept())
    }

    @Test
    fun `bl-219 a lock granted while the end of its attempt gives back another is released after its prepare, then freed`() =
        runTest {
            for (prepareFirst in listOf(false, true)) {
                store.rows = ResourceLockRows()
                calls.clear()
                val provider = GatedProvider(calls)
                val grants = grantedWhileGivingBack(provider)

                val ended = async(Dispatchers.Default) { grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow() }
                grantedWhileGivingBack(grants, provider, ended, prepareFirst)

                assertEquals(listOf("ipad"), store.rows.locks.map { it.resourceId }, "prepare first: $prepareFirst")
            }
        }

    @Test
    fun `bl-219 a lock granted while a check's take back gives back another is released after its prepare, then freed`() =
        runTest {
            for (prepareFirst in listOf(false, true)) {
                store.rows = ResourceLockRows()
                calls.clear()
                val provider = GatedProvider(calls)
                val grants = grantedWhileGivingBack(provider)

                val checked =
                    async(Dispatchers.Default) {
                        grants
                            .withCheckResource("ios-simulator", "i-a1", "t-a1", AttemptId("a1"), poll = 20.milliseconds) { it }
                            .getOrThrow()
                    }
                grantedWhileGivingBack(grants, provider, checked, prepareFirst)

                assertEquals(listOf("ipad"), store.rows.locks.map { it.resourceId }, "prepare first: $prepareFirst")
            }
        }

    @Test
    fun `bl-219 a prepare that ends after its lock was freed never calls release, and is reported`() =
        runTest {
            val provider = GatedProvider(calls)
            provider.gate("prepare iphone a1")
            ask("a1")
            val grants = grants(provider)
            grants.tick().getOrThrow()
            assertTrue(provider.entered("prepare iphone a1"))

            // Freed past the grants, by a change of the rows they did not make, as the run of another project can.
            locks.freeAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()
            provider.open("prepare iphone a1")
            grants.idle()

            assertEquals(listOf("prepare iphone a1", "prepared iphone a1"), calls)
            assertTrue(warnings.any { it.startsWith("iphone: unexpected state") }, "$warnings")
            assertEquals(emptyMap(), grants.kept())
        }

    @Test
    fun `bl-219 after a lock is given back and freed through a release, nothing kept for it remains`() =
        runTest {
            ask("a1")
            val grants = grants()
            grants.pass()
            locks.requestRelease(AttemptId("a1"), "ios-simulator", "iphone").getOrThrow()

            grants.pass()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
            assertEquals(emptyMap(), grants.kept())
        }

    @Test
    fun `bl-219 after the end of an attempt nothing kept for its locks remains`() =
        runTest {
            ask("a1")
            val grants = grants()
            grants.pass()

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()

            assertEquals(emptyMap(), grants.kept())
        }

    /** An executor that holds each task until [runHeld], as a pool whose thread has not started yet would (bl-219). */
    private class HeldExecutor : Executor {
        val held = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            held += command
        }

        fun runHeld() {
            while (true) {
                val task = held.poll() ?: return
                Thread(task).apply { start() }.join(5_000)
            }
        }
    }

    @Test
    fun `bl-219 a provider call canceled by close before its thread starts is never run`() =
        runTest {
            val executor = HeldExecutor()
            ask("a1")
            val grants = grants(providerCalls = executor)
            grants.tick().getOrThrow()
            val deadline = TimeSource.Monotonic.markNow() + 5.seconds
            while (executor.held.isEmpty() && deadline.hasNotPassedNow()) Thread.sleep(10)
            assertEquals(1, executor.held.size, "the prepare was handed to the executor")

            grants.close()
            grants.idle()
            executor.runHeld()

            assertEquals(emptyList(), calls, "a call canceled before its thread started ran")
        }
}
