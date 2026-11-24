package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * m4-15, story 115: `agent.max_concurrent_agents_by_tracker`, a map of tracker id to a positive count beside the spec's
 * limits. Unlike `max_concurrent_agents_by_state`, which ignores bad entries as the spec asks, this Loop key refuses an
 * unknown tracker id and a value below 1 at preflight.
 */
class TrackerConcurrencyConfigTest {
    private val folder: Path = createTempDirectory("loop-tracker-concurrency").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private fun write(agent: String): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |  id: local
                |trackers:
                |  - id: side
                |    kind: files
                |    provider: { dir: ./side-issues }
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

    private fun limits(agent: String): Map<String, Int> =
        loader
            .load(write(agent))
            .getOrThrow()
            .config.agent.maxConcurrentAgentsByTracker

    private fun preflight(agent: String): PreflightResult =
        runBlocking { Preflight(loader, write(agent), TemplateEngine(), { null }).check() }

    private fun refusal(agent: String): String {
        val failed = assertIs<PreflightResult.Failed>(preflight(agent))
        assertEquals("invalid_config", failed.code)
        return failed.message
    }

    @Test
    fun `no key means no tracker has a limit of its own`() {
        assertEquals(emptyMap(), limits("  max_turns: 3"))
        assertEquals(emptyMap(), AgentConfig().maxConcurrentAgentsByTracker)
    }

    @Test
    fun `a positive count per tracker id is parsed, the primary's and an extra tracker's`() {
        assertEquals(
            mapOf("local" to 1, "side" to 3),
            limits("  max_concurrent_agents_by_tracker:\n    local: 1\n    side: 3"),
        )
    }

    @Test
    fun `the key sits beside the spec's limits, which keep their own meaning`() {
        val workflow =
            loader
                .load(
                    write(
                        "  max_concurrent_agents: 4\n" +
                            "  max_concurrent_agents_by_state:\n    todo: 2\n" +
                            "  max_concurrent_agents_by_tracker:\n    side: 1",
                    ),
                ).getOrThrow()

        assertEquals(4, workflow.config.agent.maxConcurrentAgents)
        assertEquals(mapOf("todo" to 2), workflow.config.agent.maxConcurrentAgentsByState)
        assertEquals(mapOf("side" to 1), workflow.config.agent.maxConcurrentAgentsByTracker)
        assertIs<PreflightResult.Ok>(preflight("  max_concurrent_agents_by_tracker:\n    side: 1"))
    }

    @Test
    fun `preflight refuses an unknown tracker id, naming the known ones`() {
        assertEquals(
            "agent.max_concurrent_agents_by_tracker.gh names no tracker of this workflow, use one of local, side",
            refusal("  max_concurrent_agents_by_tracker:\n    gh: 1"),
        )
    }

    @Test
    fun `preflight refuses a value below 1 and a value that is no integer`() {
        assertEquals(
            "agent.max_concurrent_agents_by_tracker.side must be a positive integer, got 0",
            refusal("  max_concurrent_agents_by_tracker:\n    side: 0"),
        )
        assertEquals(
            "agent.max_concurrent_agents_by_tracker.local must be a positive integer, got -2",
            refusal("  max_concurrent_agents_by_tracker:\n    local: -2"),
        )
        assertEquals(
            "agent.max_concurrent_agents_by_tracker.local must be a positive integer, got \"two\"",
            refusal("  max_concurrent_agents_by_tracker:\n    local: two"),
        )
    }

    @Test
    fun `preflight refuses a key that is not a map`() {
        assertEquals(
            "agent.max_concurrent_agents_by_tracker must be a map of tracker id to a positive integer",
            refusal("  max_concurrent_agents_by_tracker: 2"),
        )
    }

    @Test
    fun `a single tracker project names its primary tracker by its id, default when tracker has none`() {
        folder.resolve("WORKFLOW.md").writeText(
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
            |  max_concurrent_agents_by_tracker:
            |    default: 2
            |---
            |Prompt
            """.trimMargin(),
        )
        val workflow = loader.load(folder.resolve("WORKFLOW.md")).getOrThrow()

        assertEquals(mapOf("default" to 2), workflow.config.agent.maxConcurrentAgentsByTracker)
    }
}
