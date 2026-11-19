package loop.store

import kotlinx.coroutines.runBlocking
import loop.api.ProjectId
import loop.api.RecordedEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Instant

/**
 * Runs [block] while a second store, opened by [open], records events in [project] in a loop, as a running Loop does
 * beside a command like `loop approve` or `loop resume` (m2-24, bl-41). A read then write transaction of [block] that
 * does not take the write lock first fails on a stale read snapshot (`SQLITE_BUSY_SNAPSHOT`) once the writer commits
 * between its read and its write. [block] starts once the writer has committed its first event. A write the writer
 * fails is thrown after [block].
 */
fun <T> whileAnotherStoreWrites(
    open: () -> SqliteEventStore,
    project: ProjectId,
    block: () -> T,
): T {
    val at = Instant.parse("2026-11-13T10:00:00Z")
    val stop = AtomicBoolean(false)
    val started = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    val writer =
        thread {
            try {
                open().use { other ->
                    var n = 0
                    while (!stop.get()) {
                        runBlocking { other.record(RecordedEvent(at, project, "poll", data = mapOf("n" to "${n++}"))).getOrThrow() }
                        started.countDown()
                    }
                }
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                started.countDown()
            }
        }
    val result =
        try {
            started.await()
            block()
        } finally {
            stop.set(true)
            writer.join()
        }
    failure.get()?.let { throw it }
    return result
}
