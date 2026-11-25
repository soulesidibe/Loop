package loop.runner.acp.real

import loop.api.RealIntegration
import loop.runner.acp.AgyPreflight
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gate tests for the `agy` real integration target, run in every build with no real `agy` call. */
class AgyRealProfileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the agy target is skipped when the profile is unset or names another target`() {
        listOf(emptyMap(), mapOf(RealIntegration.VARIABLE to "codex")).forEach { env ->
            assertThrows<TestAbortedException> {
                RealIntegration.require("agy", env) { AgyRealProfile.missing(env) }
            }
        }
    }

    @Test
    fun `an enabled agy target with no binary on PATH fails naming agy`() {
        val env =
            mapOf(
                RealIntegration.VARIABLE to "agy",
                "PATH" to dir.resolve("empty").createDirectory().toString(),
            )

        val error = assertThrows<AssertionError> { RealIntegration.require("agy", env) { AgyRealProfile.missing(env) } }

        assertTrue("cannot be found on PATH" in error.message!!, error.message)
    }

    @Test
    fun `an enabled agy target with a login failure or missing model fails naming the cause`() {
        val env = mapOf(RealIntegration.VARIABLE to "agy")
        val loginFail =
            assertThrows<AssertionError> {
                RealIntegration.require("agy", env) {
                    AgyRealProfile.missing(
                        env = env,
                        resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                        runProbe = { argv, _, _ ->
                            if ("--version" in argv) {
                                AgyPreflight.ProbeOutput(0, "1.5.16\n", "")
                            } else {
                                AgyPreflight.ProbeOutput(1, "", "Error: not logged in")
                            }
                        },
                    )
                }
            }
        assertTrue("agy login check failed" in loginFail.message!!, loginFail.message)
    }

    @Test
    fun `an enabled agy target with pinned version and listed model lets the smoke test run`() {
        val env =
            mapOf(
                RealIntegration.VARIABLE to "agy",
                AgyRealProfile.MODEL_VARIABLE to "gemini-3.8-flash-low",
            )

        val missing =
            AgyRealProfile.missing(
                env = env,
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) {
                        AgyPreflight.ProbeOutput(0, "1.5.16\n", "")
                    } else {
                        AgyPreflight.ProbeOutput(0, "gemini-3.8-flash-low  Flash\n", "")
                    }
                },
            )

        assertEquals(emptyList(), missing)
        assertEquals("agy acp", AgyRealProfile.command(emptyMap()))
        assertEquals("gemini-4-neon-high", AgyRealProfile.model(emptyMap()))
    }
}
