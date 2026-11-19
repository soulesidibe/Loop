package loop.runner.command

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import loop.api.AgentEnvPolicy
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerConfigException
import loop.api.RunnerEvent
import loop.api.RunnerProvider
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** m3-04: the command runner registers itself as the `command` runner plugin and parses its own section. */
class CommandRunnerProviderTest {
    @TempDir
    lateinit var workspace: Path

    private val provider = CommandRunnerProvider()

    @Test
    fun `m3-05 its profile declares one required key, no secret and no limit`() {
        val profile: PluginProfile = provider.profile

        assertEquals(listOf(ProfileKey("command", ProfileType.STRING, required = true)), profile.keys)
        assertEquals(emptySet(), profile.secrets)
        assertEquals(emptyMap(), profile.limits)
    }

    @Test
    fun `the service loader finds the command provider`() {
        val kinds = ServiceLoader.load(RunnerProvider::class.java).map { it.kind }

        assertEquals(listOf("command"), kinds.filter { it == "command" })
        assertEquals("command", provider.section)
    }

    @Test
    fun `a section without a command is refused naming the key`() {
        val absent = assertFailsWith<RunnerConfigException> { provider.validate(emptyMap()) }
        val blank = assertFailsWith<RunnerConfigException> { provider.validate(mapOf("command" to " ")) }
        val list = assertFailsWith<RunnerConfigException> { provider.validate(mapOf("command" to listOf("a"))) }

        assertEquals("runners.command.command is required", absent.message)
        assertEquals("runners.command.command is required", blank.message)
        assertEquals("runners.command.command must be a string", list.message)
    }

    @Test
    fun `the runner it builds runs the command with the agent env policy`() {
        val runner =
            provider.create(
                mapOf("command" to "echo \"\$KEPT \${GONE:-none} \$ADDED\""),
                AgentEnvPolicy(
                    hostEnv = mapOf("KEPT" to "kept", "GONE" to "secret", "PATH" to System.getenv("PATH").orEmpty()),
                    removedEnvNames = setOf("GONE"),
                    addedEnv = mapOf("ADDED" to "added"),
                ),
                holdoutDir = null,
            )

        assertIs<CommandRunner>(runner)
        val output =
            runBlocking { runner.run(context(), "").filterIsInstance<RunnerEvent.Output>().toList() }
                .joinToString("") { it.line }
        assertEquals("kept none added", output.trim())
    }

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "m3-04",
                    title = "Runner plugins",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )
}
