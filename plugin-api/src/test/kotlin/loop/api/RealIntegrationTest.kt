package loop.api

import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** m3-24, spec 17.8 items 3 and 4: the gate of the real integration profile, without any real target. */
class RealIntegrationTest {
    @Test
    fun `17_8 item 3 an unset profile skips the test and probes nothing`() {
        var probed = false

        assertThrows<TestAbortedException> {
            RealIntegration.require("codex", emptyMap()) {
                probed = true
                emptyList()
            }
        }

        assertFalse(probed)
    }

    @Test
    fun `17_8 item 3 a profile naming another target skips the test`() {
        assertThrows<TestAbortedException> {
            RealIntegration.require("codex", mapOf(RealIntegration.VARIABLE to "github")) { emptyList() }
        }
    }

    @Test
    fun `17_8 item 4 an enabled profile with missing items fails naming each of them`() {
        val error =
            assertThrows<AssertionError> {
                RealIntegration.require("codex", mapOf(RealIntegration.VARIABLE to " GitHub , codex ")) {
                    listOf("a codex login", "the command codex on PATH")
                }
            }

        assertTrue("LOOP_REAL_INTEGRATION names codex" in error.message!!, error.message)
        assertTrue("a codex login; the command codex on PATH" in error.message!!, error.message)
    }

    @Test
    fun `17_8 item 4 an unknown target name fails instead of skipping`() {
        val error =
            assertThrows<AssertionError> {
                RealIntegration.require("codex", mapOf(RealIntegration.VARIABLE to "codx")) { emptyList() }
            }

        assertTrue("codx" in error.message!!, error.message)
    }

    @Test
    fun `an enabled profile with nothing missing lets the test run`() {
        RealIntegration.require("codex", mapOf(RealIntegration.VARIABLE to "codex")) { emptyList() }
    }

    @Test
    fun `run ids are fresh and prefixed`() {
        val first = RealIntegration.runId()

        assertTrue(Regex("loop-real-[0-9a-f]{8}").matches(first), first)
        assertNotEquals(first, RealIntegration.runId())
    }
}
