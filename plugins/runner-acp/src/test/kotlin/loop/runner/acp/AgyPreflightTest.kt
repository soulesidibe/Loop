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
                        "--version" in argv -> AgyPreflight.ProbeOutput(0, "${AcpSettings.PINNED_AGY_VERSION}\n", "")
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
                        AgyPreflight.ProbeOutput(0, "${AcpSettings.PINNED_AGY_VERSION}\n", "")
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
                    if ("--version" in argv) AgyPreflight.ProbeOutput(0, "${AcpSettings.PINNED_AGY_VERSION}\n", "") else null
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
                        AgyPreflight.ProbeOutput(0, "${AcpSettings.PINNED_AGY_VERSION}\n", "")
                    } else {
                        AgyPreflight.ProbeOutput(0, "gemini-4-neon-high  Gemini 4 Neon (High)\n", "")
                    }
                },
            ).check()
        assertNull(missingModelWarn.error)
        assertTrue(missingModelWarn.warnings.single().contains("was not listed by agy models"), "${missingModelWarn.warnings}")
    }

    @Test
    fun `an agy older than the minimum version fails preflight naming the reason`() {
        val result =
            AgyPreflight(
                command = "agy acp",
                model = "gemini-4-neon-high",
                resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                runProbe = { argv, _, _ ->
                    if ("--version" in argv) AgyPreflight.ProbeOutput(0, "1.5.16\n", "") else null
                },
            ).check()

        assertEquals(
            "agy 1.5.16 is older than the minimum version ${AcpSettings.MIN_AGY_VERSION} " +
                "(pinned ${AcpSettings.PINNED_AGY_VERSION}): ${AcpSettings.MIN_AGY_REASON}; run agy update",
            result.error,
        )
    }

    @Test
    fun `a banner naming a newer version before the real one never decides the version`() {
        val outputs =
            listOf(
                "new version 1.6.0 available\n1.5.16\n",
                "A new version 1.6.0 available, run agy update\n\u001B[1m1.5.16\u001B[0m\n",
                "1.5.16\nnew version 1.6.0 available\n",
                "new version 1.6.0 available\nagy version 1.5.16\n",
            )
        for (stdout in outputs) {
            val result =
                AgyPreflight(
                    command = "agy acp",
                    model = "gemini-4-neon-high",
                    resolveBinary = { _, _ -> "/usr/local/bin/agy" },
                    runProbe = { argv, _, _ ->
                        if ("--version" in argv) AgyPreflight.ProbeOutput(0, stdout, "") else null
                    },
                ).check()
            assertNotNull(result.error, stdout)
            assertTrue(result.error.startsWith("agy 1.5.16 is older than the minimum version"), "$stdout: ${result.error}")
        }
    }

    @Test
    fun `the version is read from a line that is only a version, else the last version token`() {
        assertEquals("1.5.18", AgyPreflight.extractSemver("1.5.18\n"))
        assertEquals("1.5.16", AgyPreflight.extractSemver("new version 1.6.0 available\n1.5.16\n"))
        assertEquals("1.5.16", AgyPreflight.extractSemver("1.5.16\nnew version 1.6.0 available\n"))
        assertEquals("1.5.16", AgyPreflight.extractSemver("new version 1.6.0 available\nagy version 1.5.16"))
        assertEquals("1.5.18", AgyPreflight.extractSemver("agy 1.5.18"))
        assertEquals("1.5.18-rc.1", AgyPreflight.extractSemver("v1.5.18-rc.1\r\n"))
        assertNull(AgyPreflight.extractSemver("agy: unknown flag"))
    }
}
