package loop.runner.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgyPreflightTest {
    @Test
    fun `missing binary fails preflight with an error`() {
        val result =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> null },
            ).check()

        assertNotNull(result.error)
        assertTrue("cannot be found" in result.error, result.error)
    }

    @Test
    fun `wrong major version fails preflight with an error`() {
        val result =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) AgyPreflight.ProbeOutput(0, "2.0.0\n", "") else null
                },
            ).check()

        assertEquals(
            "agy 2.0.0 is not of the pinned major version 1 (pinned ${AcpSettings.PINNED_AGY_VERSION})",
            result.error,
        )
    }

    @Test
    fun `matching major version and listed model pass with no error or warnings`() {
        val modelsStdout =
            "\r⠋ Fetching available models...\rgemini-4-neon-high   Gemini 4 Neon (High)\n" +
                "gemini-3.8-flash-low  Gemini 3.8 Flash (Low)\n"
        val result =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    when {
                        "--version" in argv -> AgyPreflight.ProbeOutput(0, "1.5.16\n", "")
                        "models" in argv -> AgyPreflight.ProbeOutput(0, modelsStdout, "")
                        else -> null
                    }
                },
            ).check()

        assertNull(result.error)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `login error, offline failure, and missing model produce warnings without failing preflight`() {
        val loginWarn =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) {
                        AgyPreflight.ProbeOutput(0, "1.5.16\n", "")
                    } else {
                        AgyPreflight.ProbeOutput(1, "", "Error: not logged in")
                    }
                },
            ).check()
        assertNull(loginWarn.error)
        assertTrue(loginWarn.warnings.single().contains("agy login check failed"), "${loginWarn.warnings}")

        val offlineWarn =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) AgyPreflight.ProbeOutput(0, "1.5.16\n", "") else null
                },
            ).check()
        assertNull(offlineWarn.error)
        assertTrue(offlineWarn.warnings.single().contains("timed out or unavailable"), "${offlineWarn.warnings}")

        val missingModelWarn =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-xhigh",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) {
                        AgyPreflight.ProbeOutput(0, "1.5.16\n", "")
                    } else {
                        AgyPreflight.ProbeOutput(0, "gemini-4-neon-high  Gemini 4 Neon (High)\n", "")
                    }
                },
            ).check()
        assertNull(missingModelWarn.error)
        assertTrue(missingModelWarn.warnings.single().contains("was not listed by agy models"), "${missingModelWarn.warnings}")
    }
}
