package loop.core.machine

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The machine level config (m3-16): `<LOOP_HOME>/config.yaml`, opt in, its keys under `machine:`. */
class MachineConfigTest {
    private val home: Path = createTempDirectory("loop-machine-config").toRealPath()

    private fun write(text: String) = MachineConfig.file(home).writeText(text)

    private fun failure(text: String): MachineConfigError {
        write(text)
        val error = MachineConfig.read(home).exceptionOrNull()
        return assertIs<MachineConfigError>(error, "expected a failure for:\n$text")
    }

    @Test
    fun `no file means no machine limit`() {
        assertNull(MachineConfig.read(home).getOrThrow())
    }

    @Test
    fun `every key is read`() {
        write("machine:\n  max_concurrent_agents: 2\n  per_day_usd: 12.5\n  per_day_tokens: 5000000\n")
        assertEquals(
            MachineLimits(maxConcurrentAgents = 2, perDayUsd = 12.5, perDayTokens = 5_000_000),
            MachineConfig.read(home).getOrThrow(),
        )
    }

    @Test
    fun `an absent key is no limit and an empty file is a config without limits`() {
        write("machine:\n  per_day_usd: 3\n")
        assertEquals(MachineLimits(perDayUsd = 3.0), MachineConfig.read(home).getOrThrow())
        write("")
        assertEquals(MachineLimits(), MachineConfig.read(home).getOrThrow())
    }

    @Test
    fun `a malformed value fails naming the key and the file`() {
        val error = failure("machine:\n  max_concurrent_agents: 0\n")
        assertEquals("machine.max_concurrent_agents", error.key)
        val message = error.message.orEmpty()
        assertTrue("machine.max_concurrent_agents must be a positive integer, got 0" in message, message)
        assertTrue(MachineConfig.file(home).toString() in message, message)
        assertEquals("machine.per_day_usd", failure("machine:\n  per_day_usd: lots\n").key)
        assertEquals("machine.per_day_tokens", failure("machine:\n  per_day_tokens: -4\n").key)
        assertEquals("machine.per_day_tokens", failure("machine:\n  per_day_tokens: [1]\n").key)
    }

    @Test
    fun `an unknown key under machine fails naming it`() {
        val error = failure("machine:\n  max_concurrent_agent: 2\n")
        assertEquals("machine.max_concurrent_agent", error.key)
        assertTrue("machine.max_concurrent_agent is not a machine key" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a file that is not a yaml map or a machine that is not a map fails`() {
        assertEquals("machine", failure("machine: 3\n").key)
        assertNull(failure("- a\n- b\n").key)
        assertNull(failure("machine: [unclosed\n").key)
    }
}
