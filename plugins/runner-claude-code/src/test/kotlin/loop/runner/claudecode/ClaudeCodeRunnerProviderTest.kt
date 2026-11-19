package loop.runner.claudecode

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.AgentEnvPolicy
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
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
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** m3-04: the Claude Code runner registers itself as the `claude-code` runner plugin and parses its own section. */
class ClaudeCodeRunnerProviderTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var bin: Path

    private val provider = ClaudeCodeRunnerProvider()

    @Test
    fun `m3-05 its profile declares its four keys, no secret and a one day limit on both timeouts`() {
        // bl-72 added a fifth key after these four, strict_mcp, checked in its own test below.
        assertEquals(
            listOf(
                ProfileKey("command", ProfileType.STRING, required = true),
                ProfileKey("model", ProfileType.STRING),
                ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "3600000"),
                ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "120000"),
            ),
            provider.profile.keys.take(4),
        )
        assertEquals(emptySet(), provider.profile.secrets)
        assertEquals(mapOf("turn_timeout_ms" to 1L..86_400_000L, "read_timeout_ms" to 1L..86_400_000L), provider.profile.limits)
    }

    @Test
    fun `bl-72 its profile ends with strict_mcp, a boolean that defaults to true, and has no other key`() {
        assertEquals(
            listOf("command", "model", "turn_timeout_ms", "read_timeout_ms", "strict_mcp"),
            provider.profile.keys.map { it.name },
        )
        assertEquals(ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "true"), provider.profile.keys.last())
    }

    @Test
    fun `m3-05 the profile defaults are the defaults the settings use`() {
        val settings = ClaudeCodeSettings.of(mapOf("command" to "claude"))

        assertEquals(
            settings.turnTimeout.inWholeMilliseconds.toString(),
            provider.profile.keys
                .single { it.name == "turn_timeout_ms" }
                .default,
        )
        assertEquals(
            settings.readTimeout.inWholeMilliseconds.toString(),
            provider.profile.keys
                .single { it.name == "read_timeout_ms" }
                .default,
        )
    }

    @Test
    fun `bl-72 strict MCP is on by default, as the profile says, and a section can turn it off`() {
        val default = ClaudeCodeSettings.of(mapOf("command" to "claude"))

        assertTrue(default.strictMcp)
        assertEquals(
            default.strictMcp.toString(),
            provider.profile.keys
                .single { it.name == "strict_mcp" }
                .default,
        )
        assertFalse(ClaudeCodeSettings.of(mapOf("command" to "claude", "strict_mcp" to "false")).strictMcp)
        assertFalse(ClaudeCodeSettings.of(mapOf("command" to "claude", "strict_mcp" to " False ")).strictMcp)
        assertTrue(ClaudeCodeSettings.of(mapOf("command" to "claude", "strict_mcp" to "TRUE")).strictMcp)
        assertTrue(ClaudeCodeSettings.of(mapOf("command" to "claude", "strict_mcp" to " ")).strictMcp)
    }

    @Test
    fun `the service loader finds the claude-code provider`() {
        val kinds = ServiceLoader.load(RunnerProvider::class.java).map { it.kind }

        assertEquals(listOf("claude-code"), kinds.filter { it == "claude-code" })
        assertEquals("claude-code", provider.section)
    }

    @Test
    fun `a section gives its command, model and timeouts, quoted integers included`() {
        val settings =
            ClaudeCodeSettings.of(
                mapOf("command" to "claude", "model" to "opus", "turn_timeout_ms" to "600000", "read_timeout_ms" to "30000"),
                holdoutDir = Path("/holdout"),
            )

        assertEquals(
            ClaudeCodeSettings("claude", "opus", 600_000.milliseconds, 30_000.milliseconds, Path("/holdout")),
            settings,
        )
    }

    @Test
    fun `a section without timeouts or model uses one hour, two minutes and no model`() {
        val settings = ClaudeCodeSettings.of(mapOf("command" to "claude", "model" to " "))

        assertEquals("claude", settings.command)
        assertNull(settings.model)
        assertEquals(1.hours, settings.turnTimeout)
        assertEquals(120.seconds, settings.readTimeout)
    }

    @Test
    fun `an invalid section is refused naming the key`() {
        fun message(config: Map<String, Any?>): String? = assertFailsWith<RunnerConfigException> { provider.validate(config) }.message

        assertEquals("runners.claude-code.command is required", message(mapOf("model" to "opus")))
        assertEquals("runners.claude-code.command is required", message(mapOf("command" to "")))
        assertEquals("runners.claude-code.command must be a string", message(mapOf("command" to mapOf("a" to "b"))))
        assertEquals(
            "runners.claude-code.turn_timeout_ms must be a positive integer",
            message(mapOf("command" to "claude", "turn_timeout_ms" to "0")),
        )
        assertEquals(
            "runners.claude-code.read_timeout_ms must be a positive integer",
            message(mapOf("command" to "claude", "read_timeout_ms" to "soon")),
        )
        assertEquals(
            "runners.claude-code.strict_mcp must be a boolean",
            message(mapOf("command" to "claude", "strict_mcp" to "yes")),
        )
    }

    @Test
    fun `the runner it builds reports its model and denies the holdout folder`() {
        val policy = AgentEnvPolicy(hostEnv = emptyMap(), removedEnvNames = emptySet(), addedEnv = emptyMap())

        val runner = provider.create(mapOf("command" to "claude", "model" to "sonnet"), policy, holdoutDir = Path("/holdout"))
        val bare = provider.create(mapOf("command" to "claude"), policy, holdoutDir = null)

        assertIs<ClaudeCodeRunner>(runner)
        assertEquals("sonnet", runner.model)
        assertEquals(Path("/holdout"), runner.settings.holdoutDir)
        assertNull(bare.model)
    }

    @Test
    fun `bl-72 the runner it builds keeps the strict MCP setting of its section`() {
        val policy = AgentEnvPolicy(hostEnv = emptyMap(), removedEnvNames = emptySet(), addedEnv = emptyMap())

        val strict = assertIs<ClaudeCodeRunner>(provider.create(mapOf("command" to "claude"), policy, holdoutDir = null))
        val open =
            assertIs<ClaudeCodeRunner>(provider.create(mapOf("command" to "claude", "strict_mcp" to "false"), policy, holdoutDir = null))

        assertTrue(strict.settings.strictMcp)
        assertFalse(open.settings.strictMcp)
    }

    @Test
    fun `the runner it builds gets the agent env policy, removed names and added env`() {
        val claude = FakeClaude(bin)
        val policy =
            AgentEnvPolicy(
                hostEnv =
                    mapOf(
                        "PATH" to System.getenv("PATH"),
                        "HOME" to System.getProperty("user.home"),
                        "KEPT_VALUE" to "kept",
                        "GONE_SECRET" to "secret",
                    ),
                removedEnvNames = setOf("GONE_SECRET"),
                addedEnv = mapOf("ADDED_VALUE" to "added"),
            )
        val runner = provider.create(mapOf("command" to claude.script.toString()), policy, holdoutDir = null)

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Work on t-1").toList() } }

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val env = claude.env
        assertEquals("kept", env["KEPT_VALUE"])
        assertFalse("GONE_SECRET" in env, "a removed name reached the agent")
        assertEquals("added", env["ADDED_VALUE"])
    }

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "t-1",
                    identifier = "t-1",
                    title = "Claude",
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
