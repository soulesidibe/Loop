package loop.core.resources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-219: the mutexes of the locks of [ResourceGrants]. A key is kept only while a caller uses it, so a lock freed
 * leaves nothing behind, and two callers on one key always share one mutex.
 */
class LockGuardsTest {
    private val guards = LockGuards<String>()

    @Test
    fun `a key freed and guarded again at once, the second caller waits for the first`() =
        runTest {
            val inside = CountDownLatch(1)
            val leave = CountDownLatch(1)
            val order: MutableList<String> = CopyOnWriteArrayList()
            val first =
                async(Dispatchers.Default) {
                    guards.withLocks(listOf("a1 iphone")) {
                        order += "first in"
                        inside.countDown()
                        leave.await()
                        order += "first out"
                    }
                }
            assertTrue(inside.await(5, TimeUnit.SECONDS))

            val second = async(Dispatchers.Default) { guards.withLocks(listOf("a1 iphone")) { order += "second in" } }
            Thread.sleep(300)

            assertFalse(second.isCompleted, "the second caller did not wait for the first")
            leave.countDown()
            first.await()
            second.await()
            assertEquals(listOf("first in", "first out", "second in"), order)
            assertEquals(emptySet(), guards.keys())
        }

    @Test
    fun `callers arriving while a key changes hands never hold it at the same time`() =
        runTest {
            val holding = AtomicInteger()
            val overlaps = AtomicInteger()
            val callers =
                (1..200).map {
                    async(Dispatchers.Default) {
                        guards.withLocks(listOf("a1 iphone")) {
                            if (holding.incrementAndGet() > 1) overlaps.incrementAndGet()
                            Thread.sleep(1)
                            holding.decrementAndGet()
                        }
                    }
                }
            callers.forEach { it.await() }

            assertEquals(0, overlaps.get())
            assertEquals(emptySet(), guards.keys())
        }

    @Test
    fun `a try on a key in use fails, and an unlocked key is forgotten`() {
        val unlock = assertNotNull(guards.tryLock("a1 iphone"))

        assertNull(guards.tryLock("a1 iphone"))
        assertEquals(setOf("a1 iphone"), guards.keys())
        unlock()
        assertEquals(emptySet(), guards.keys())
        assertNotNull(guards.tryLock("a1 iphone")).invoke()
    }
}
