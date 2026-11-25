package loop.core.resources

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.ResourceLockRows
import loop.api.ResourceLockStore
import loop.api.ResourceLockUpdate
import loop.api.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m4-25: what `request_resource` and `release_resource` answer, decided in one change of the rows. A new request is
 * granted and prepared by `loop run` (preparing), or queued; a call that checks again keeps its place; the first call
 * after the prepare gets the prepared values, a request after that is a double request. A release only marks the lock,
 * `loop run` frees it. Each new request records `resource_requested`, then `resource_granted` or `resource_queued`.
 */
class ResourceAnswersTest {
    private val now = Instant.parse("2026-11-25T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val p1 = ProjectId("p1")

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

    private val store = MemoryStore()
    private val sink = Sink()
    private val locks = ResourceLocks(store, sink, clock, RunId("r1"))

    private suspend fun ask(attempt: String) = locks.ask(p1, "i-$attempt", "t-$attempt", AttemptId(attempt), "avd", "api35").getOrThrow()

    private suspend fun release(attempt: String) = locks.requestRelease(AttemptId(attempt), "avd", "api35").getOrThrow()

    private fun lines() = sink.events.map { "${it.kind} ${it.attemptId?.value} ${it.data.values.joinToString(" ")}" }

    @Test
    fun `a request is granted then preparing until loop run writes the prepare result, then granted once`() =
        runTest {
            assertEquals(ResourceAnswer.Preparing::class, ask("a1")::class)
            assertIs<ResourceAnswer.Preparing>(ask("a1"), "a second call before the prepare still prepares")

            val lock = store.rows.locks.single()
            assertEquals(true, locks.prepared(lock, Result.success(mapOf("serial" to "emulator-5554"))).getOrThrow())
            val granted = assertIs<ResourceAnswer.Granted>(ask("a1"))
            assertEquals(mapOf("serial" to "emulator-5554"), granted.lock.prepared)
            assertEquals(
                true,
                store.rows.locks
                    .single()
                    .answered,
            )
            assertIs<ResourceAnswer.AlreadyHeld>(ask("a1"), "a request once answered granted is a double request")

            assertEquals(listOf("resource_requested a1 avd api35", "resource_granted a1 avd api35"), lines())
        }

    @Test
    fun `a request of a held resource is queued with its position and keeps its place when it checks again`() =
        runTest {
            ask("a1")
            val queued = assertIs<ResourceAnswer.Queued>(ask("a2"))
            assertEquals(1, queued.position)
            assertEquals(true, queued.requested)
            assertEquals(2, assertIs<ResourceAnswer.Queued>(ask("a3")).position)
            val again = assertIs<ResourceAnswer.Queued>(ask("a2"))
            assertEquals(1, again.position)
            assertEquals(false, again.requested)

            assertEquals(
                listOf(
                    "resource_requested a1 avd api35",
                    "resource_granted a1 avd api35",
                    "resource_requested a2 avd api35",
                    "resource_queued a2 avd api35 1 held",
                    "resource_requested a3 avd api35",
                    "resource_queued a3 avd api35 2 held",
                ),
                lines(),
            )
        }

    @Test
    fun `a release marks the lock and keeps it until loop run frees it, a second release or one not held is refused`() =
        runTest {
            ask("a1")
            ask("a2")
            assertIs<ReleaseAnswer.NotHeld>(release("a2"), "a request that waits holds nothing")

            val marked = assertIs<ReleaseAnswer.Marked>(release("a1"))
            assertEquals(now, marked.lock.releaseRequestedAt)
            assertEquals(
                AttemptId("a1"),
                store.rows.locks
                    .single()
                    .attemptId,
                "the lock stays until loop run frees it",
            )
            assertIs<ReleaseAnswer.NotHeld>(release("a1"), "a lock already released is not held")
            assertIs<ResourceAnswer.Releasing>(ask("a1"))
            assertIs<ReleaseAnswer.NotHeld>(release("a9"))
            assertEquals(4, sink.events.size, "a release records nothing until loop run frees the lock: ${lines()}")
        }

    @Test
    fun `a failed prepare is answered once and marks the lock for release`() =
        runTest {
            ask("a1")
            locks.prepared(store.rows.locks.single(), Result.failure(IllegalStateException("no runtime"))).getOrThrow()

            val failed = assertIs<ResourceAnswer.PrepareFailed>(ask("a1"))
            assertEquals("no runtime", failed.lock.prepareError)
            assertNotNull(
                store.rows.locks
                    .single()
                    .releaseRequestedAt,
            )
            assertIs<ResourceAnswer.Releasing>(ask("a1"))
        }

    @Test
    fun `a prepare result for a lock that changed hands or was prepared already is not written`() =
        runTest {
            ask("a1")
            val first = store.rows.locks.single()
            locks.freeAttempt(AttemptId("a1"), "attempt_ended").getOrThrow()
            ask("a2")

            assertEquals(false, locks.prepared(first, Result.success(mapOf("serial" to "old"))).getOrThrow())
            assertNull(
                store.rows.locks
                    .single()
                    .prepared,
            )
            val second = store.rows.locks.single()
            assertEquals(true, locks.prepared(second, Result.success(emptyMap())).getOrThrow())
            assertEquals(false, locks.prepared(second, Result.success(mapOf("serial" to "again"))).getOrThrow())
            assertEquals(
                emptyMap(),
                store.rows.locks
                    .single()
                    .prepared,
            )
        }

    @Test
    fun `bl-204 a request frees the rows of another project with no live run before it answers, never peeking its own`() =
        runTest {
            val p2 = ProjectId("p2")
            locks.ask(p2, "i-a1", "t-a1", AttemptId("a1"), "avd", "api35").getOrThrow()
            locks.ask(p2, "i-a2", "t-a2", AttemptId("a2"), "avd", "api35").getOrThrow()
            val peeked = mutableListOf<ProjectId>()

            val answer =
                locks
                    .ask(p1, "i-b1", "t-b1", AttemptId("b1"), "avd", "api35") {
                        peeked += it
                        it != p2
                    }.getOrThrow()

            assertIs<ResourceAnswer.Preparing>(answer, "the dead holder and its queue are gone, so b1 is granted")
            assertEquals(listOf(p2), peeked)
            assertEquals(listOf(AttemptId("b1")), store.rows.locks.map { it.attemptId })
            assertEquals(emptyList(), store.rows.queue)
            assertEquals(
                listOf("resource_released a1 avd api35 abandoned", "resource_requested b1 avd api35", "resource_granted b1 avd api35"),
                lines().drop(4),
            )
        }
}
