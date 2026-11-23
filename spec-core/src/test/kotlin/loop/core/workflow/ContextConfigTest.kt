package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** m1-17: the `context:` section of the plan (8.2, 8.4) is parsed with its defaults and validated. Nothing uses it yet. */
class ContextConfigTest {
    private val folder: Path = createTempDirectory("loop-context-config").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private fun write(sections: String): Path =
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
                |$sections
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun context(sections: String): ContextConfig =
        loader
            .load(write(sections))
            .getOrThrow()
            .config.context

    private fun error(sections: String): String? {
        val error = loader.load(write(sections)).exceptionOrNull()
        assertIs<WorkflowError.InvalidConfig>(error, "expected an invalid config for:\n$sections")
        return error.message
    }

    @Test
    fun `a workflow without context takes the plan defaults`() {
        val context = context("")

        assertEquals(0.20, context.softLimit)
        assertEquals(0.21, context.hardLimit)
        assertEquals(0.05, context.promptLimit)
        assertEquals(2000, context.toolOutputTokens)
        assertEquals(5, context.maxRotations)
        assertEquals(mapOf("anthropic/claude-opus-5-5" to 1_000_000L, "openai/gpt-6.1-sol" to 475_000L), context.windows)
    }

    @Test
    fun `m4-02 the defaults are a hard limit of 0_21 and a soft limit of 0_20`() {
        val context = ContextConfig()

        assertEquals(0.21, context.hardLimit)
        assertEquals(0.20, context.softLimit)
        assertEquals(475_000L, ContextConfig.DEFAULT_WINDOWS["openai/gpt-6.1-sol"])
        assertEquals(1_000_000L, ContextConfig.DEFAULT_WINDOWS["anthropic/claude-opus-5-5"])
    }

    @Test
    fun `m4-02 a soft limit above the default hard limit is refused, naming the default and the two fixes`() {
        assertEquals(
            "context.soft_limit (0.23) must not be above the default context.hard_limit (0.21): " +
                "set context.hard_limit or lower context.soft_limit",
            error("context:\n  soft_limit: 0.23"),
        )
    }

    @Test
    fun `m4-02 a soft limit above the default hard limit passes with a hard limit set`() {
        val context = context("context:\n  soft_limit: 0.23\n  hard_limit: 0.25")

        assertEquals(0.23, context.softLimit)
        assertEquals(0.25, context.hardLimit)
    }

    @Test
    fun `m4-02 the default soft limit passes under a hard limit set at it`() {
        assertEquals(0.20, context("context:\n  hard_limit: 0.20").softLimit)
    }

    @Test
    fun `an empty context section takes the defaults too`() {
        assertEquals(ContextConfig(), context("context:"))
    }

    @Test
    fun `every key reads an explicit value`() {
        val context =
            context(
                """
                |context:
                |  soft_limit: 0.1
                |  hard_limit: "0.3"
                |  prompt_limit: 0.02
                |  tool_output_tokens: 500
                |  max_rotations: 2
                """.trimMargin(),
            )

        assertEquals(ContextConfig(0.1, 0.3, 0.02, 500, 2), context)
    }

    @Test
    fun `windows add to the shipped defaults and override them per model`() {
        val context =
            context(
                """
                |context:
                |  windows:
                |    anthropic/claude-opus-5-5: 500000
                |    openai/gpt-x: "400000"
                """.trimMargin(),
            )

        assertEquals(
            mapOf("anthropic/claude-opus-5-5" to 500_000L, "openai/gpt-6.1-sol" to 475_000L, "openai/gpt-x" to 400_000L),
            context.windows,
        )
    }

    @Test
    fun `a limit of exactly 1 is accepted`() {
        assertEquals(1.0, context("context:\n  soft_limit: 1\n  hard_limit: 1").hardLimit)
    }

    @Test
    fun `a limit outside 0 to 1 fails naming the key and the value`() {
        assertEquals("context.soft_limit must be a fraction above 0 and at most 1, got 0", error("context:\n  soft_limit: 0"))
        assertEquals("context.hard_limit must be a fraction above 0 and at most 1, got 1.5", error("context:\n  hard_limit: 1.5"))
        assertEquals("context.prompt_limit must be a fraction above 0 and at most 1, got -0.05", error("context:\n  prompt_limit: -0.05"))
    }

    @Test
    fun `a limit that is not a number fails quoting the value`() {
        assertEquals(
            "context.soft_limit must be a fraction above 0 and at most 1, got \"twenty\"",
            error("context:\n  soft_limit: twenty"),
        )
        assertEquals(
            "context.hard_limit must be a fraction above 0 and at most 1, got \"NaN\"",
            error("context:\n  hard_limit: NaN"),
        )
        assertEquals(
            "context.hard_limit must be a fraction above 0 and at most 1, got \"20%\"",
            error("context:\n  hard_limit: 20%"),
        )
    }

    @Test
    fun `the soft limit cannot be above the hard limit`() {
        assertEquals(
            "context.soft_limit (0.3) must not be above context.hard_limit (0.25)",
            error("context:\n  soft_limit: 0.3\n  hard_limit: 0.25"),
        )
    }

    @Test
    fun `the prompt limit cannot be above the soft limit`() {
        assertEquals(
            "context.prompt_limit (0.22) must not be above context.soft_limit (0.2)",
            error("context:\n  prompt_limit: 0.22"),
        )
    }

    @Test
    fun `tool output tokens and max rotations must be positive integers`() {
        assertEquals("context.tool_output_tokens must be a positive integer, got 0", error("context:\n  tool_output_tokens: 0"))
        assertEquals("context.max_rotations must be a positive integer, got \"five\"", error("context:\n  max_rotations: five"))
        assertEquals("context.max_rotations must be a positive integer, got -1", error("context:\n  max_rotations: -1"))
    }

    @Test
    fun `context and windows must be maps`() {
        assertEquals("context must be a map", error("context: 0.2"))
        assertEquals("context.windows must be a map", error("context:\n  windows: [1000000]"))
    }

    @Test
    fun `a window must be a positive integer`() {
        assertEquals(
            "context.windows.openai/gpt-x must be a positive integer, got 0",
            error("context:\n  windows:\n    openai/gpt-x: 0"),
        )
        assertEquals(
            "context.windows.openai/gpt-x must be a positive integer, got \"big\"",
            error("context:\n  windows:\n    openai/gpt-x: big"),
        )
        assertEquals(
            "context.windows.openai/gpt-x must be a positive integer, got nothing",
            error("context:\n  windows:\n    openai/gpt-x:"),
        )
    }

    @Test
    fun `a window key must be provider slash model`() {
        for (key in listOf("claude-opus-5-5", "/claude-opus-5-5", "anthropic/")) {
            assertEquals(
                "context.windows key \"$key\" must be provider/model, for example anthropic/claude-opus-5-5",
                error("context:\n  windows:\n    \"$key\": 1000000"),
            )
        }
    }

    @Test
    fun `an invalid context value fails preflight with invalid_config`() {
        val result =
            runBlocking {
                Preflight(
                    loader = loader,
                    path = write("context:\n  hard_limit: 2"),
                    templates = TemplateEngine(),
                    trackerCheck = { null },
                ).check()
            }

        // The file as read (m1-15) is not what this test is about.
        assertEquals(
            PreflightResult.Failed("invalid_config", "context.hard_limit must be a fraction above 0 and at most 1, got 2"),
            assertIs<PreflightResult.Failed>(result).copy(source = null),
        )
    }

    @Test
    fun `m4-02 preflight refuses a soft limit of 0_23 with no hard limit, naming the default hard limit`() {
        val result =
            runBlocking {
                Preflight(
                    loader = loader,
                    path = write("context:\n  soft_limit: 0.23"),
                    templates = TemplateEngine(),
                    trackerCheck = { null },
                ).check()
            }

        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "context.soft_limit (0.23) must not be above the default context.hard_limit (0.21): " +
                    "set context.hard_limit or lower context.soft_limit",
            ),
            assertIs<PreflightResult.Failed>(result).copy(source = null),
        )
    }
}
