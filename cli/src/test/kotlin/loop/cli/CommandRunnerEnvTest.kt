package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** m1-23: `loop run` with `runner.kind: command` gives the agent the shared env policy, end to end. */
class CommandRunnerEnvTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `every referenced dollar var and the Anthropic keys are absent from a command agent env, Loop's own vars are there`() {
        val envDump = root.resolve("agent-env.txt")
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |env > '$envDump'
            |loop tool move review
            |
            """.trimMargin(),
        )
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(
            text
                .replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_TRACKER_TOKEN\n")
                .replace("  root: ./workspaces\n", "  root: \$LOOP_TEST_ROOT\n"),
        )
        fixture.issue("t-1")

        val result =
            fixture.loop(
                "run",
                fixture.workflow.toString(),
                "--once",
                env =
                    mapOf(
                        "LOOP_TEST_TRACKER_TOKEN" to "tracker-secret-value",
                        "LOOP_TEST_ROOT" to root.resolve("ws-root").toString(),
                        "ANTHROPIC_API_KEY" to "sk-host-value",
                        "ANTHROPIC_AUTH_TOKEN" to "token-host-value",
                        "LOOP_TEST_HARMLESS" to "kept",
                    ),
            )

        assertEquals(0, result.exitCode, result.toString())
        val agentEnv = envDump.readLines()
        val dump = agentEnv.joinToString("\n")
        listOf("LOOP_TEST_TRACKER_TOKEN", "LOOP_TEST_ROOT", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN").forEach { name ->
            assertTrue(agentEnv.none { it.startsWith("$name=") }, "$name in\n$dump")
        }
        assertTrue(agentEnv.any { it.startsWith("LOOP_TOOL_CONFIG=") }, dump)
        assertTrue(agentEnv.any { it == "HOME=${fixture.homeDir}" }, dump)
        assertTrue(agentEnv.any { it == "LOOP_TEST_HARMLESS=kept" }, dump)
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
    }

    @Test
    fun `an agent env variable reaches a command agent with the workspace placeholder replaced, over the host value`() {
        val envDump = root.resolve("agent-env.txt")
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |env > '$envDump'
            |loop tool move review
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  env:
                |    GRADLE_OPTS: "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons"
                |    PLAIN_VALUE: plain
                """.trimMargin(),
        )
        fixture.issue("t-1")

        val result =
            fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GRADLE_OPTS" to "-Dfrom.host=1"))

        assertEquals(0, result.exitCode, result.toString())
        val agentEnv = envDump.readLines()
        val dump = agentEnv.joinToString("\n")
        val workspace = fixture.workspace("t-1").toRealPath()
        assertTrue(
            agentEnv.any { it == "GRADLE_OPTS=-Dorg.gradle.daemon.registry.base=$workspace/.scratch/gradle-daemons" },
            dump,
        )
        assertTrue(agentEnv.any { it == "PLAIN_VALUE=plain" }, dump)
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
    }
}
