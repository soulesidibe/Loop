package loop.core.resources

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

        override suspend fun resourceLockRows() = Result.success(rows)

        override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> =
            synchronized(this) {
                if (refuse > 0) {
                    refuse--
                    return Result.failure(IllegalStateException("the store is busy"))
                }
                val update = change(rows)
                rows = update.rows
                Result.success(update.value)
            }
    }

    private class Sink : EventSink {
        val events = mutableListOf<RecordedEvent>()

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
    ) = ResourceGrants(project, locks, listOf(provider), { declarations }, live = {
        peeked += it
        live(it)
    }, providerTimeout = providerTimeout) { lock, reason -> warnings += "${lock.resourceId}: $reason" }

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

    @Test
    fun `a tick gives back a released lock through the provider, frees it and the next in its queue is prepared`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.pass()
            ask("a1")
            locks.requestRelease(AttemptId("a1"), "ios-simulator", "iphone").getOrThrow()

            grants.pass()
            assertEquals(listOf("prepare iphone", "release iphone"), calls, "the release runs in the background of the tick")
            grants.pass()

            assertEquals(listOf("prepare iphone", "release iphone", "prepare iphone"), calls)
            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            assertEquals(mapOf("udid" to "UDID-iphone", "can" to "ui tests"), lock("iphone").prepared)
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
}
