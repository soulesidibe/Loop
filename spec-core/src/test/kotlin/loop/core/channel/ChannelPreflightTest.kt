package loop.core.channel

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import loop.core.workflow.ChannelConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowLoader
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** m4-30: preflight reads `channel:`, takes `terminal`, the default, and refuses a kind Loop does not know. */
class ChannelPreflightTest {
    private val folder: Path = createTempDirectory("loop-channel").toRealPath()

    private fun check(extra: String): PreflightResult {
        val path =
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
                    |$extra
                    |---
                    |Work on {{ issue.identifier }}
                    """.trimMargin(),
                )
            }
        return runBlocking { Preflight(WorkflowLoader(env = emptyMap()), path, TemplateEngine()) { null }.check() }
    }

    @Test
    fun `preflight accepts channel kind terminal`() {
        val result = check("channel: { kind: terminal }")

        assertIs<PreflightResult.Ok>(result, result.toString())
        assertEquals(ChannelConfig(kind = "terminal"), result.workflow.config.channel)
    }

    @Test
    fun `with no channel the terminal is the default`() {
        val result = check("")

        assertIs<PreflightResult.Ok>(result, result.toString())
        assertEquals(ChannelConfig.TERMINAL, result.workflow.config.channel.kind)
    }

    @Test
    fun `preflight refuses a channel kind Loop does not know, with invalid_config naming the kinds it knows`() {
        val result = check("channel: { kind: telegram }")

        assertIs<PreflightResult.Failed>(result, result.toString())
        assertEquals("invalid_config", result.code)
        assertEquals("channel.kind is \"telegram\", which is not a channel Loop knows, use terminal", result.message)
    }

    @Test
    fun `preflight refuses a channel that is not a map, and a channel with no kind`() {
        val notMap = check("channel: terminal")
        val noKind = check("channel: { }")

        assertIs<PreflightResult.Failed>(notMap, notMap.toString())
        assertEquals("invalid_config", notMap.code)
        assertIs<PreflightResult.Failed>(noKind, noKind.toString())
        assertEquals("invalid_config", noKind.code)
    }

    @Test
    fun `preflight reads hooks on_human_needed`() {
        val result = check("hooks:\n  on_human_needed: ./notify.sh")

        assertIs<PreflightResult.Ok>(result, result.toString())
        assertEquals("./notify.sh", result.workflow.config.hooks.onHumanNeeded)
    }
}
