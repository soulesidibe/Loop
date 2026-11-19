package loop.cli

import loop.core.workflow.WorkflowError
import loop.core.workflow.WorkflowFile
import loop.runner.claudecode.ClaudeCodeSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The runner block of a `WORKFLOW.md`, loaded through the host's loader with the runner plugins of the classpath.
 * Until m3-04 these tests lived in spec-core's `WorkflowFileTest`, which read the sealed `RunnerSettings`; since the
 * runner plugins read their own section, they run here, where the plugins are.
 */
class RunnerWorkflowTest {
    @TempDir
    lateinit var folder: Path

    private val loader by lazy { workflowLoader(env = emptyMap(), home = folder) }

    private val bare =
        """
        ---
        tracker:
          kind: files
        runner:
          kind: command
        runners:
          command:
            command: ./agent.sh
        ---
        Prompt
        """.trimIndent()

    private val commandRunnerBlock = "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n"

    private fun write(text: String): Path = folder.resolve("WORKFLOW.md").also { it.writeText(text) }

    private fun load(text: String): WorkflowFile = loader.load(write(text)).getOrThrow()

    private fun loadError(text: String): Throwable? = loader.load(write(text)).exceptionOrNull()

    private fun claudeCodeSettings(workflow: WorkflowFile): ClaudeCodeSettings {
        assertEquals("claude-code", workflow.runner.kind)
        return ClaudeCodeSettings.of(workflow.runner.section("claude-code"))
    }

    @Test
    fun `a runner kind other than command fails`() {
        assertIs<WorkflowError.InvalidConfig>(loadError(bare.replace("kind: command", "kind: codex")))
    }

    @Test
    fun `an absent runner command fails`() {
        assertIs<WorkflowError.InvalidConfig>(loadError(bare.replace("    command: ./agent.sh\n", "    other: x\n")))
    }

    @Test
    fun `a claude-code runner gives its command, model and timeouts`() {
        val text =
            bare.replace(
                commandRunnerBlock,
                "runner:\n  kind: claude-code\nrunners:\n  claude-code:\n    command: claude\n    model: opus\n" +
                    "    turn_timeout_ms: 600000\n    read_timeout_ms: 30000\n",
            )

        val settings = claudeCodeSettings(load(text))

        assertEquals("claude", settings.command)
        assertEquals("opus", settings.model)
        assertEquals(600_000L, settings.turnTimeout.inWholeMilliseconds)
        assertEquals(30_000L, settings.readTimeout.inWholeMilliseconds)
    }

    @Test
    fun `a claude-code runner without a command fails with a clear message`() {
        val text = bare.replace(commandRunnerBlock, "runner:\n  kind: claude-code\nrunners:\n  claude-code:\n    model: opus\n")

        val error = assertIs<WorkflowError.InvalidConfig>(loadError(text))
        assertEquals("runners.claude-code.command is required", error.message)
    }

    @Test
    fun `a claude-code runner without timeouts or model uses one hour, two minutes and no model`() {
        val text = bare.replace(commandRunnerBlock, "runner:\n  kind: claude-code\nrunners:\n  claude-code:\n    command: claude\n")

        val settings = claudeCodeSettings(load(text))

        assertEquals("claude", settings.command)
        assertNull(settings.model)
        assertEquals(3_600_000L, settings.turnTimeout.inWholeMilliseconds)
        assertEquals(120_000L, settings.readTimeout.inWholeMilliseconds)
    }
}
