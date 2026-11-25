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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * m4-50: the lock calls apply [ResourceQueue] to the store's rows in one change and record `resource_granted` for each
 * lock granted and `resource_released` with its reason for each lock freed. A sink that refuses an event never undoes
 * the change.
 */
class ResourceLocksTest {
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

    private class Sink(
        val refuse: Boolean = false,
    ) : EventSink {
        val events = mutableListOf<RecordedEvent>()

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            if (refuse) return Result.failure(IllegalStateException("sink down"))
            events += event
            return Result.success(Unit)
        }
    }

    private fun RecordedEvent.line() = "$kind ${attemptId?.value} ${data.values.joinToString(" ")}"

    @Test
    fun `a request, a release to the next in the queue and the free of an attempt record their grants and releases`() =
        runTest {
            val store = MemoryStore()
            val sink = Sink()
            val locks = ResourceLocks(store, sink, clock, RunId("r1"))

            assertIs<ResourceGrant.Granted>(locks.request(p1, "1", "t-1", AttemptId("a1"), "avd", "api35").getOrThrow())
            assertIs<ResourceGrant.Queued>(locks.request(p1, "2", "t-2", AttemptId("a2"), "avd", "api35").getOrThrow())
            locks.release(AttemptId("a1"), "avd", "api35").getOrThrow()
            locks.freeAttempt(AttemptId("a2"), "attempt_ended").getOrThrow()

            assertEquals(
                listOf(
                    "resource_granted a1 avd api35",
                    "resource_released a1 avd api35 released",
                    "resource_granted a2 avd api35",
                    "resource_released a2 avd api35 attempt_ended",
                ),
                sink.events.map { it.line() },
            )
            val released = sink.events[1]
            assertEquals(p1, released.projectId)
            assertEquals(RunId("r1"), released.runId)
            assertEquals("1", released.issueId)
            assertEquals("t-1", released.issueIdentifier)
            assertEquals(ResourceLockRows(), store.rows)
        }

    @Test
    fun `freeing a project records each freed lock with the reason, and an event the sink refuses goes to unrecorded only`() =
        runTest {
            val store = MemoryStore()
            val unrecorded = mutableListOf<String>()
            val locks = ResourceLocks(store, Sink(refuse = true), clock, RunId("r1")) { event, error -> unrecorded += "${event.kind}: ${error.message}" }
            locks.request(p1, "1", "t-1", AttemptId("a1"), "avd", "api35").getOrThrow()
            locks.request(p1, "1", "t-1", AttemptId("a1"), "display", "main").getOrThrow()

            val change = locks.freeProject(p1, "abandoned").getOrThrow()

            assertEquals(listOf("avd", "display"), change.released.map { it.kind })
            assertTrue(store.rows.locks.isEmpty())
            assertEquals(
                listOf("resource_granted: sink down", "resource_granted: sink down", "resource_released: sink down", "resource_released: sink down"),
                unrecorded,
            )
        }
}
