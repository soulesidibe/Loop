package loop.core.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import loop.api.Redactor
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.machine.MachineLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

/** The machine gate on its own (bl-101): what a claim leaves in the store when it is cancelled. */
class MachineGateTest {
    private val machine = FakeMachine(MachineLimits(maxConcurrentAgents = 2))
    private val gate = MachineGate(machine, KeyValueLog(out = {}, clock = Clock.System, redactor = Redactor.NONE), Clock.System) { "h1" }

    @Test
    fun `a claim cancelled after the store wrote its slot releases that slot`() =
        runTest {
            assertEquals(null, gate.readLimits())
            // The store committed the slot, then the cancel reached the claim before it returned.
            machine.onClaimed = { throw CancellationException("the run stops") }

            assertFailsWith<CancellationException> { gate.claim(LogContext()) }

            assertEquals(emptyList(), machine.slots)
            assertEquals(listOf("claimed h1", "released h1"), machine.log)
        }
}
