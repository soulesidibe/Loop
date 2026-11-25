package loop.core.resources

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
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

        override suspend fun resourceLockRows() = Result.success(rows)

        override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> {
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

    private fun grants(
        provider: ResourceProvider = Provider(calls),
        project: ProjectId = p1,
    ) = ResourceGrants(project, locks, listOf(provider), { declarations }) { lock, reason -> warnings += "${lock.resourceId}: $reason" }

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
}
