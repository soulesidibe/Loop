package loop.runner.process

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HostEnvironmentTest {
    private val host = mapOf("HOME" to "/home/me", "PATH" to "/usr/bin", "TRACKER_TOKEN" to "secret")

    @Test
    fun `the host environment plus overrides minus removed names`() {
        val env =
            agentEnvironment(
                overrides = mapOf("PATH" to "/loop/bin:/usr/bin", "LOOP_ISSUE_ID" to "m1-05"),
                removed = setOf("TRACKER_TOKEN"),
                host = host,
            )

        assertEquals(mapOf("HOME" to "/home/me", "PATH" to "/loop/bin:/usr/bin", "LOOP_ISSUE_ID" to "m1-05"), env)
    }

    @Test
    fun `Loop's overrides survive removal and the always removed names win`() {
        val env =
            agentEnvironment(
                overrides =
                    mapOf(
                        "PATH" to "/loop/bin:/usr/bin",
                        "LOOP_ISSUE_ID" to "m1-05",
                        "ANTHROPIC_API_KEY" to "from-override",
                    ),
                removed = setOf("TRACKER_TOKEN", "PATH", "LOOP_ISSUE_ID"),
                host = host + mapOf("ANTHROPIC_API_KEY" to "sk-host", "ANTHROPIC_AUTH_TOKEN" to "token-host"),
            )

        assertEquals(mapOf("HOME" to "/home/me", "PATH" to "/loop/bin:/usr/bin", "LOOP_ISSUE_ID" to "m1-05"), env)
    }

    @Test
    fun `added names override the host and removed names, Loop's overrides and the always removed names win`() {
        val env =
            agentEnvironment(
                overrides = mapOf("PATH" to "/loop/bin:/usr/bin"),
                removed = setOf("TRACKER_TOKEN", "GRADLE_OPTS"),
                host = host + mapOf("GRADLE_OPTS" to "-Dhost", "ANTHROPIC_API_KEY" to "sk-host"),
                added = mapOf("GRADLE_OPTS" to "-Dadded", "PATH" to "/added", "ANTHROPIC_API_KEY" to "sk-added"),
            )

        assertEquals(mapOf("HOME" to "/home/me", "PATH" to "/loop/bin:/usr/bin", "GRADLE_OPTS" to "-Dadded"), env)
    }

    @Test
    fun `every workspace placeholder in an added value is replaced by the workspace path`() {
        val added =
            resolveAddedEnv(
                mapOf("A" to "{workspace}/x:{workspace}/y", "B" to "plain", "C" to "{other}"),
                workspace = Path("/ws/bl-01"),
            )

        assertEquals(mapOf("A" to "/ws/bl-01/x:/ws/bl-01/y", "B" to "plain", "C" to "{other}"), added)
    }
}
