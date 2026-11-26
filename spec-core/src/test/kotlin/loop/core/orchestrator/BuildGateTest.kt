package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.core.log.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The build gate on its own (bl-188): first in, first out, a live capacity, cancels and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildGateTest {
    private val context = LogContext()

    @Test
    fun `with capacity 1 three concurrent builds run one at a time in arrival order`() =
        runTest {
            val gate = BuildGate({ 1 })
            val log = mutableListOf<String>()
            var running = 0
            var most = 0
            val ends = List(3) { CompletableDeferred<Unit>() }
            val jobs =
                (0 until 3).map { n ->
                    launch {
                        gate.withBuild(context) {
                            running++
                            most = maxOf(most, running)
                            log += "start $n"
                            ends[n].await()
                            log += "end $n"
                            running--
                        }
                    }.also { runCurrent() }
                }
            assertEquals(listOf("start 0"), log)
            assertEquals(2, gate.queued)
            ends.forEach { end ->
                end.complete(Unit)
                runCurrent()
            }
            jobs.forEach { it.join() }

            assertEquals(listOf("start 0", "end 0", "start 1", "end 1", "start 2", "end 2"), log)
            assertEquals(1, most)
        }

    @Test
    fun `lowering the capacity from 2 to 1 while two builds run admits no queued build until both have ended`() =
        runTest {
            var capacity = 2
            val gate = BuildGate({ capacity })
            val started = mutableListOf<Int>()
            val ends = List(3) { CompletableDeferred<Unit>() }
            val jobs =
                (0 until 3).map { n ->
                    launch {
                        gate.withBuild(context) {
                            started += n
                            ends[n].await()
                        }
                    }.also { runCurrent() }
                }
            assertEquals(listOf(0, 1), started)

            capacity = 1
            ends[0].complete(Unit)
            runCurrent()
            gate.admit()
            runCurrent()
            assertEquals(listOf(0, 1), started)

            ends[1].complete(Unit)
            runCurrent()
            assertEquals(listOf(0, 1, 2), started)
            ends[2].complete(Unit)
            jobs.forEach { it.join() }
        }

    @Test
    fun `raising the capacity then calling admit starts a queued build`() =
        runTest {
            var capacity = 1
            val gate = BuildGate({ capacity })
            val started = mutableListOf<Int>()
            val ends = List(2) { CompletableDeferred<Unit>() }
            val jobs =
                (0 until 2).map { n ->
                    launch {
                        gate.withBuild(context) {
                            started += n
                            ends[n].await()
                        }
                    }.also { runCurrent() }
                }
            assertEquals(listOf(0), started)

            capacity = 2
            gate.admit()
            runCurrent()

            assertEquals(listOf(0, 1), started)
            ends.forEach { it.complete(Unit) }
            jobs.forEach { it.join() }
        }

    @Test
    fun `a cancelled waiter leaves the queue and takes no capacity`() =
        runTest {
            val gate = BuildGate({ 1 })
            val started = mutableListOf<Int>()
            val ends = List(3) { CompletableDeferred<Unit>() }
            val jobs =
                (0 until 3).map { n ->
                    launch {
                        gate.withBuild(context) {
                            started += n
                            ends[n].await()
                        }
                    }.also { runCurrent() }
                }
            assertEquals(2, gate.queued)

            jobs[1].cancel()
            runCurrent()
            assertEquals(1, gate.queued)

            ends[0].complete(Unit)
            runCurrent()
            assertEquals(listOf(0, 2), started)
            assertEquals(0, gate.queued)
            ends[2].complete(Unit)
            jobs.forEach { it.join() }
            assertEquals(0, gate.running)
        }

    @Test
    fun `a block that throws still releases its capacity`() =
        runTest {
            val gate = BuildGate({ 1 })

            assertFailsWith<IllegalStateException> { gate.withBuild(context) { error("the build broke") } }

            assertEquals(0, gate.running)
            var ran = false
            gate.withBuild(context) { ran = true }
            assertTrue(ran)
        }

    @Test
    fun `a build that has to wait is reported once with the builds ahead of it`() =
        runTest {
            val queued = mutableListOf<Int>()
            val gate = BuildGate({ 1 }) { _, ahead -> queued += ahead }
            val ends = List(3) { CompletableDeferred<Unit>() }
            val jobs =
                (0 until 3).map { n ->
                    launch { gate.withBuild(context) { ends[n].await() } }.also { runCurrent() }
                }

            assertEquals(listOf(1, 2), queued)
            ends.forEach { it.complete(Unit) }
            jobs.forEach { it.join() }
        }
}
