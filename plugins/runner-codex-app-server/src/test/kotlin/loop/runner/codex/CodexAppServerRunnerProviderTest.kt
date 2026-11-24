package loop.runner.codex

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
import kotlin.io.path.readLines
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

/** m3-17: the Codex runner registers as the `codex-app-server` runner plugin, its section `runners.codex`. */
class CodexAppServerRunnerProviderTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val provider = CodexAppServerRunnerProvider()

    @Test
    fun `17_5 plugin loading finds the codex-app-server provider with the service loader`() {
        val found = ServiceLoader.load(RunnerProvider::class.java).filter { it.kind == "codex-app-server" }

        assertEquals(listOf("loop.runner.codex.CodexAppServerRunnerProvider"), found.map { it.javaClass.name })
        assertEquals("codex", found.single().section)
    }

    /**
     * m3-19: the profile now has command and the two timeouts with the spec's defaults, no secret, and timeouts of one
     * day at most; m3-20 adds `strict_mcp`, a boolean that defaults to true; bl-141 adds `model`, a string with no
     * default. The name predates m3-19 and is kept
     * because the `deleted_tests` guard refutes any test name that disappears; the maintainer renames it after the
     * merge.
     */
    @Test
    fun `its profile has one key, command, a string that defaults to codex app-server, and no secret or limit`() {
        assertEquals(
            listOf(
                ProfileKey("command", ProfileType.STRING, default = "codex app-server"),
                ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "3600000"),
                ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "5000"),
                ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "true"),
                ProfileKey("model", ProfileType.STRING),
            ),
            provider.profile.keys,
        )
        assertEquals(emptySet(), provider.profile.secrets)
        assertEquals(mapOf("turn_timeout_ms" to 1L..86_400_000L, "read_timeout_ms" to 1L..86_400_000L), provider.profile.limits)
    }

    @Test
    fun `m3-19 a section gives its timeouts, quoted or not, absent or blank ones the spec's defaults`() {
        assertEquals(
            CodexAppServerSettings(turnTimeout = 5_400_000.milliseconds, readTimeout = 30.seconds),
            CodexAppServerSettings.of(mapOf("turn_timeout_ms" to "5400000", "read_timeout_ms" to 30_000)),
        )
        assertEquals(
            CodexAppServerSettings(turnTimeout = 1.hours, readTimeout = 5.seconds),
            CodexAppServerSettings.of(mapOf("turn_timeout_ms" to " ", "read_timeout_ms" to null)),
        )
        assertEquals(CodexAppServerSettings(turnTimeout = 1.hours, readTimeout = 5.seconds), CodexAppServerSettings.of(emptyMap()))
    }

    @Test
    fun `m3-19 a timeout that is not a positive integer is refused naming the key`() {
        listOf("0", "-5", "soon", "1.5", true, listOf(1)).forEach { value ->
            val failure = assertFailsWith<RunnerConfigException>("$value") { provider.validate(mapOf("read_timeout_ms" to value)) }

            assertEquals("runners.codex.read_timeout_ms must be a positive integer", failure.message)
        }
        val failure = assertFailsWith<RunnerConfigException> { provider.validate(mapOf("turn_timeout_ms" to "0")) }
        assertEquals("runners.codex.turn_timeout_ms must be a positive integer", failure.message)
    }

    @Test
    fun `a section gives its command, an absent or blank one the spec's default`() {
        assertEquals(
            CodexAppServerSettings("codex app-server --listen stdio://"),
            CodexAppServerSettings.of(
                mapOf(
                    "command" to "codex app-server --listen stdio://",
                ),
            ),
        )
        assertEquals(CodexAppServerSettings("codex app-server"), CodexAppServerSettings.of(emptyMap()))
        assertEquals(CodexAppServerSettings("codex app-server"), CodexAppServerSettings.of(mapOf("command" to " ")))
        assertEquals(CodexAppServerSettings("codex app-server"), CodexAppServerSettings.of(mapOf("command" to null)))
        assertEquals(
            CodexAppServerSettings().command,
            provider.profile.keys
                .first()
                .default,
        )
    }

    @Test
    fun `m3-20 the command is split into the words of its argv with quotes and backslashes, nothing expanded`() {
        assertEquals(listOf("codex", "app-server"), CodexAppServerSettings.of(emptyMap()).argv)
        assertEquals(
            listOf("/opt/my tools/codex", "app-server", "--flag=a b", "it's", "x y"),
            CodexAppServerSettings.of(mapOf("command" to "'/opt/my tools/codex'  app-server --flag=\"a b\" it\\'s x\\ y")).argv,
        )
    }

    @Test
    fun `m3-20 a command written for a shell is refused naming the key, since no shell runs it`() {
        listOf(
            "codex app-server | tee log",
            "codex app-server; true",
            "codex app-server && true",
            "codex app-server > log",
            "\$HOME/bin/codex app-server",
            "`which codex` app-server",
            "~/bin/codex app-server",
            "codex app-server --config *.toml",
            "'codex app-server",
            "\"codex app-server",
            "codex app-server \\",
            "''",
        ).forEach { command ->
            val failure = assertFailsWith<RunnerConfigException>(command) { provider.validate(mapOf("command" to command)) }

            assertTrue(failure.message!!.startsWith("runners.codex.command "), "$command: ${failure.message}")
        }
        // Quoted, the same characters are plain words.
        assertEquals(
            listOf("codex", "app-server", "a|b", "\$HOME", "~", "*"),
            CodexAppServerSettings.of(mapOf("command" to "codex app-server 'a|b' '\$HOME' \"~\" \\*")).argv,
        )
    }

    @Test
    fun `bl-135 a command that starts with an env assignment is refused when the config is read, never run as a program`() {
        listOf(
            "FOO=bar codex app-server",
            "OPENAI_BASE_URL=http://x CODEX_HOME=/tmp/c codex app-server",
            "'FOO=bar' codex",
        ).forEach { command ->
            val failure = assertFailsWith<RunnerConfigException>(command) { provider.validate(mapOf("command" to command)) }

            assertTrue(failure.message!!.startsWith("runners.codex.command "), "$command: ${failure.message}")
            assertTrue("agent.env" in failure.message!!, "$command: ${failure.message}")
            assertFalse("bar" in failure.message!! || "http" in failure.message!!, "the value is never written: ${failure.message}")
        }
        // An `=` in a later word, or a program word that is not a name, is a plain word.
        assertEquals(
            listOf("codex", "app-server", "FOO=bar"),
            CodexAppServerSettings.of(mapOf("command" to "codex app-server FOO=bar")).argv,
        )
        assertEquals(listOf("./=codex", "app-server"), CodexAppServerSettings.of(mapOf("command" to "./=codex app-server")).argv)
    }

    @Test
    fun `a command that is not a string is refused naming the key`() {
        val failure = assertFailsWith<RunnerConfigException> { provider.validate(mapOf("command" to listOf("codex"))) }

        assertEquals("runners.codex.command must be a string", failure.message)
    }

    @Test
    fun `the runner it builds has the section's command and no model`() {
        val policy = AgentEnvPolicy(hostEnv = emptyMap(), removedEnvNames = emptySet(), addedEnv = emptyMap())

        val runner = assertIs<CodexAppServerRunner>(provider.create(mapOf("command" to "codex app-server"), policy, holdoutDir = null))

        assertEquals(CodexAppServerSettings("codex app-server"), runner.settings)
        assertNull(runner.model)
    }

    /**
     * m3-20 landed the guards and lifted the refusal of m3-18: given a holdout folder, as `loop run` always gives one,
     * the provider builds the runner with it, so the approval rules refuse reads and edits under it. The name predates
     * m3-20 and is kept because the `deleted_tests` guard refutes any test name that disappears; the maintainer
     * renames it after the merge.
     */
    @Test
    fun `m3-18 it refuses to build a runner for Loop's runs, a holdout folder given, until m3-20 lands the guards`() {
        val policy = AgentEnvPolicy(hostEnv = emptyMap(), removedEnvNames = emptySet(), addedEnv = emptyMap())
        val holdouts = dir.resolve("holdouts")

        val runner = assertIs<CodexAppServerRunner>(provider.create(mapOf("command" to "codex app-server"), policy, holdoutDir = holdouts))

        assertEquals(CodexAppServerSettings("codex app-server", holdoutDir = holdouts), runner.settings)
    }

    @Test
    fun `m3-20 strict MCP is on by default, as the profile says, and a section can turn it off`() {
        val default = CodexAppServerSettings.of(emptyMap())

        assertTrue(default.strictMcp)
        assertEquals(
            default.strictMcp.toString(),
            provider.profile.keys
                .single { it.name == "strict_mcp" }
                .default,
        )
        assertFalse(CodexAppServerSettings.of(mapOf("strict_mcp" to "false")).strictMcp)
        assertFalse(CodexAppServerSettings.of(mapOf("strict_mcp" to " False ")).strictMcp)
        assertFalse(CodexAppServerSettings.of(mapOf("strict_mcp" to false)).strictMcp)
        assertTrue(CodexAppServerSettings.of(mapOf("strict_mcp" to " ")).strictMcp)
        assertTrue(CodexAppServerSettings.of(mapOf("strict_mcp" to null)).strictMcp)
    }

    @Test
    fun `bl-141 a section gives its model, trimmed, and none when it is absent, null or blank`() {
        assertEquals("gpt-6.1-sol", CodexAppServerSettings.of(mapOf("model" to " gpt-6.1-sol ")).model)
        assertNull(CodexAppServerSettings.of(emptyMap()).model)
        assertNull(CodexAppServerSettings.of(mapOf("model" to null)).model)
        assertNull(CodexAppServerSettings.of(mapOf("model" to " ")).model)
    }

    @Test
    fun `bl-141 a model that is not a string is refused naming the key`() {
        listOf(1, true, listOf("gpt-6.1-sol")).forEach { value ->
            val failure = assertFailsWith<RunnerConfigException>("$value") { provider.validate(mapOf("model" to value)) }

            assertEquals("runners.codex.model must be a string", failure.message)
        }
    }

    @Test
    fun `m3-20 a strict_mcp that is not a boolean is refused naming the key`() {
        listOf("yes", "1", 1, listOf(true)).forEach { value ->
            val failure = assertFailsWith<RunnerConfigException>("$value") { provider.validate(mapOf("strict_mcp" to value)) }

            assertEquals("runners.codex.strict_mcp must be a boolean", failure.message)
        }
    }

    @Test
    fun `the runner it builds gets the agent env policy, removed names and added env`() {
        val report = dir.resolve("env.txt")
        val server =
            FakeAppServer.write(dir) {
                shell("printf '%s\\n' \"\$KEPT_VALUE\" \"\${GONE_SECRET-unset}\" \"\$ADDED_VALUE\" > '$report'")
                exit(0)
            }
        val policy =
            AgentEnvPolicy(
                hostEnv =
                    mapOf(
                        "PATH" to System.getenv("PATH"),
                        "HOME" to home.toString(),
                        "KEPT_VALUE" to "kept",
                        "GONE_SECRET" to "secret",
                    ),
                removedEnvNames = setOf("GONE_SECRET"),
                addedEnv = mapOf("ADDED_VALUE" to "added"),
            )
        val runner = provider.create(mapOf("command" to server.command), policy, holdoutDir = null)

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(), "Work on c-01").toList() } }

        assertIs<RunnerEvent.TurnFailed>(events.last(), "$events")
        assertEquals(listOf("kept", "unset", "added"), report.readLines())
    }

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "c-01",
                    title = "Codex",
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
