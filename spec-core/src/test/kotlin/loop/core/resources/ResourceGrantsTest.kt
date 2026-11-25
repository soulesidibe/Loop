package loop.core.resources

import kotlinx.coroutines.test.runTest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

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

    private class MemoryStore : ResourceLockStore {
        var rows = ResourceLockRows()

        /** The number of the next changes refused, as a busy store would. */
        var refuse = 0

        override suspend fun resourceLockRows() = Result.success(rows)

        override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> {
            if (refuse > 0) {
                refuse--
                return Result.failure(IllegalStateException("the store is busy"))
            }
            val update = change(rows)
            rows = update.rows
            return Result.success(update.value)
        }
    }

    private class Sink : EventSink {
        val events = mutableListOf<RecordedEvent>()

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            events += event
            return Result.success(Unit)
        }
    }

    /** Records each call as `prepare <id>` or `release <id>`, in [calls], and fails the prepare of [failing]. */
    private class Provider(
        val calls: MutableList<String>,
        val failing: String? = null,
    ) : ResourceProvider {
        override val kinds = setOf("ios-simulator")

        override fun prepare(resource: ResourceDeclaration): Map<String, String> {
            calls += "prepare ${resource.id}"
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
    private val calls = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val locks = ResourceLocks(store, sink, clock, RunId("r1"))
    private val declarations =
        listOf(ResourceDeclaration("ios-simulator", "iphone", can = listOf("ui tests")), ResourceDeclaration("display", "main"))

    private val peeked = mutableListOf<ProjectId>()

    private fun grants(
        provider: ResourceProvider = Provider(calls),
        project: ProjectId = p1,
        live: (ProjectId) -> Boolean = { true },
    ) = ResourceGrants(project, locks, listOf(provider), { declarations }, live = {
        peeked += it
        live(it)
    }) { lock, reason -> warnings += "${lock.resourceId}: $reason" }

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

            grants().tick().getOrThrow()

            assertEquals(listOf("prepare iphone"), calls)
            assertEquals(mapOf("udid" to "UDID-iphone", "can" to "ui tests"), lock("iphone").prepared)
            assertEquals(emptyMap(), lock("main").prepared)
            assertNull(lock("other").prepared, "a lock of another project is its own run's to prepare")
            grants().tick().getOrThrow()
            assertEquals(listOf("prepare iphone"), calls, "a prepared lock is never prepared again")
        }

    @Test
    fun `a tick gives back a released lock through the provider, frees it and the next in its queue is prepared`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.tick().getOrThrow()
            ask("a1")
            locks.requestRelease(AttemptId("a1"), "ios-simulator", "iphone").getOrThrow()

            grants.tick().getOrThrow()

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
            grants.tick().getOrThrow()

            assertEquals("cannot boot iphone", lock("iphone").prepareError)
            assertEquals(listOf("iphone: prepare failed: cannot boot iphone"), warnings)
            ask("a1")
            grants.tick().getOrThrow()

            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(emptyList(), store.rows.locks)
        }

    @Test
    fun `the end of an attempt runs the provider's release before the free, and the next holder is prepared after`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.tick().getOrThrow()

            grants.endAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()
            assertEquals(listOf("prepare iphone", "release iphone"), calls)
            assertEquals(AttemptId("a2"), lock("iphone").attemptId)
            grants.tick().getOrThrow()

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

            grants(live = { it != p2 }).tick().getOrThrow()

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

            grants(live = { true }).tick().getOrThrow()

            assertEquals(listOf(AttemptId("a1")), store.rows.locks.map { it.attemptId })
            assertEquals(listOf(AttemptId("b1")), store.rows.queue.map { it.attemptId })
        }

    @Test
    fun `bl-204 a free that failed at the attempt end is retried on the next tick, without a second release`() =
        runTest {
            ask("a1")
            ask("a2")
            val grants = grants()
            grants.tick().getOrThrow()
            store.refuse = 1

            val ended = grants.endAttempt(AttemptId("a1"), "attempt_ended")
            assertEquals(true, ended.isFailure)
            assertEquals(AttemptId("a1"), lock("iphone").attemptId)
            grants.tick().getOrThrow()

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

            grants(Provider(calls, failing = "iphone")).tick().getOrThrow()

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

            grants().tick().getOrThrow()

            assertEquals(emptyList(), calls, "no check ran outside the limit")
            assertEquals(listOf(AttemptId("a1")), store.rows.queue.map { it.attemptId }, "the recheck's request went")
            assertEquals(1, store.rows.dirty.size)
        }

    @Test
    fun `m4-26 a check takes back the lock its own attempt holds in the pool, then holds it itself`() =
        runTest {
            ask("a1")
            val grants = grants()
            grants.tick().getOrThrow()

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
}
