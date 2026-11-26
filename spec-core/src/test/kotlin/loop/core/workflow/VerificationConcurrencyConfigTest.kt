package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * bl-188: `agent.max_concurrent_verifications`, the verification and merge check builds that run at once. Absent, the
 * build gate takes `agent.max_concurrent_agents`; a value that is not a positive integer fails preflight.
 */
class VerificationConcurrencyConfigTest {
    private val folder: Path = createTempDirectory("loop-verification-concurrency").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

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

    private fun agent(agent: String): AgentConfig =
        loader
            .load(write(agent))
            .getOrThrow()
            .config.agent

    private fun refusal(agent: String): String {
        val failed =
            assertIs<PreflightResult.Failed>(runBlocking { Preflight(loader, write(agent), TemplateEngine(), { null }).check() })
        assertEquals("invalid_config", failed.code)
        return failed.message
    }

    @Test
    fun `an absent key falls back to max_concurrent_agents`() {
        val config = agent("  max_concurrent_agents: 3")

        assertNull(config.maxConcurrentVerifications)
        assertEquals(3, config.buildCapacity)
        assertEquals(10, AgentConfig().buildCapacity)
    }

    @Test
    fun `max_concurrent_verifications 1 parses beside max_concurrent_agents`() {
        val config = agent("  max_concurrent_agents: 3\n  max_concurrent_verifications: 1")

        assertEquals(1, config.maxConcurrentVerifications)
        assertEquals(1, config.buildCapacity)
        assertEquals(3, config.maxConcurrentAgents)
        assertEquals(2, agent("  max_concurrent_verifications: \"2\"").maxConcurrentVerifications)
    }

    @Test
    fun `preflight refuses 0 and a value that is no integer, naming the key`() {
        assertEquals(
            "agent.max_concurrent_verifications must be a positive integer, got 0",
            refusal("  max_concurrent_verifications: 0"),
        )
        assertEquals(
            "agent.max_concurrent_verifications must be a positive integer, got \"one\"",
            refusal("  max_concurrent_verifications: one"),
        )
    }
}
