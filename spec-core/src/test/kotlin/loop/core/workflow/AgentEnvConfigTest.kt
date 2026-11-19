package loop.core.workflow

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** bl-01: `agent.env`, literal values added to the agent env of every attempt, `{workspace}` left for the runner. */
class AgentEnvConfigTest {
    private val folder: Path = createTempDirectory("loop-agent-env").toRealPath()
    private val loader = WorkflowLoader(env = mapOf("HOST_SECRET" to "secret-value", "GRADLE_OPTS" to "-Dhost"))

    private fun write(agent: String): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |agent:
                |$agent
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun env(agent: String): Map<String, String> =
        loader
            .load(write(agent))
            .getOrThrow()
            .config.agent.env

    private fun error(agent: String): WorkflowError.InvalidConfig = assertIs(loader.load(write(agent)).exceptionOrNull())

    @Test
    fun `no agent env gives an empty map`() {
        assertEquals(emptyMap(), env("  max_turns: 3"))
    }

    @Test
    fun `values are kept as written, the workspace placeholder unresolved`() {
        val env =
            env(
                """
                |  env:
                |    GRADLE_OPTS: "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons"
                |    PLAIN: hello
                |    NUMBER: 42
                |    EMPTY: ""
                |    PARTIAL: ${'$'}HOST_SECRET/suffix
                """.trimMargin(),
            )

        assertEquals(
            mapOf(
                "GRADLE_OPTS" to "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons",
                "PLAIN" to "hello",
                "NUMBER" to "42",
                "EMPTY" to "",
                "PARTIAL" to "\$HOST_SECRET/suffix",
            ),
            env,
        )
    }

    @Test
    fun `a value that is exactly a dollar name is a config error, set or not`() {
        listOf("HOST_SECRET", "UNSET_NAME").forEach { name ->
            val error = error("  env:\n    TOKEN: \$$name")

            assertEquals(
                "agent.env.TOKEN must be a literal value, \$$name would read the host variable $name",
                error.message,
            )
        }
    }

    @Test
    fun `a name Loop sets itself is a config error`() {
        listOf("PATH", "LOOP_TOOL_CONFIG", "LOOP_ISSUE_ID", "LOOP_SESSION_ID", "LOOP_ANYTHING").forEach { name ->
            val error = error("  env:\n    $name: value")

            assertEquals("agent.env.$name is set by Loop and cannot be overridden", error.message)
        }
    }

    @Test
    fun `the Anthropic keys are a config error, Loop never passes them to the agent`() {
        listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN").forEach { name ->
            val error = error("  env:\n    $name: value")

            assertEquals("agent.env.$name is never passed to the agent", error.message)
        }
    }

    @Test
    fun `agent env must be a map`() {
        assertEquals("agent.env must be a map", error("  env: GRADLE_OPTS=x").message)
    }

    @Test
    fun `a value that is not a string is a config error`() {
        assertEquals("agent.env.LIST must be a string", error("  env:\n    LIST: [a, b]").message)
        assertEquals("agent.env.NOTHING must be a string", error("  env:\n    NOTHING:").message)
    }

    @Test
    fun `a key that is not an env var name is a config error`() {
        listOf("1ABC", "WITH-DASH", "A=B").forEach { key ->
            val error = error("  env:\n    \"$key\": value")

            assertEquals("agent.env key \"$key\" must be an environment variable name", error.message)
        }
    }
}
