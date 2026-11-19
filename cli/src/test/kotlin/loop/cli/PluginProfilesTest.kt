package loop.cli

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * m3-05: every built in runner and tracker declares a profile, and Loop checks the selected plugins' sections
 * against their profiles at startup, in the preflight, through the host's loader and the installed launcher.
 */
class PluginProfilesTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m305ProfileToken0123456789abcdefgh"

    private fun host(env: Map<String, String> = emptyMap()): Host =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()) + env,
            launcherProperty = null,
            userHome = "/tmp",
        )

    /** Replaces the runner block of the fixture's `WORKFLOW.md` with `runner.kind: [kind]` and [section] under `runners.[kind]`. */
    private fun useRunner(
        kind: String,
        section: String,
    ) {
        val text = fixture.workflow.readText()
        val start = text.indexOf("runner:\n")
        val end = text.indexOf("polling:\n")
        fixture.workflow.writeText(text.substring(0, start) + "runner:\n  kind: $kind\nrunners:\n  $kind:\n$section" + text.substring(end))
    }

    /** Replaces the `files` tracker of the fixture's `WORKFLOW.md` with `github` and [provider] lines under `tracker.provider`. */
    private fun useGitHub(provider: String) {
        fixture.workflow.writeText(
            fixture.workflow
                .readText()
                .replace("  kind: files\n  active_states: [todo]\n", "  kind: github\n  provider:\n$provider"),
        )
    }

    private fun preflight(env: Map<String, String> = emptyMap()): PreflightResult.Failed =
        assertIs<PreflightResult.Failed>(
            runBlocking { Preflight(host(env).workflowLoader(), fixture.workflow, TemplateEngine()) { null }.check() },
        )

    @Test
    fun `a missing required key fails preflight naming the key`() {
        useRunner("claude-code", "    model: opus\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.claude-code.command is required"),
            preflight().copy(source = null),
        )

        useRunner("command", "    command: ./agent.sh\n")
        useGitHub("    token: \$GITHUB_TOKEN\n")
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.repo is required"),
            preflight(mapOf("GITHUB_TOKEN" to token)).copy(source = null),
        )
    }

    @Test
    fun `a value of the wrong type fails preflight naming the key`() {
        useRunner("claude-code", "    command: claude\n    turn_timeout_ms: soon\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.claude-code.turn_timeout_ms must be an integer"),
            preflight().copy(source = null),
        )

        useRunner("command", "    command: [./agent.sh]\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.command.command must be a string"),
            preflight().copy(source = null),
        )

        useRunner("command", "    command: ./agent.sh\n")
        useGitHub("    repo: o/r\n    token: \$GITHUB_TOKEN\n    labels: ready\n")
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.labels must be a list"),
            preflight(mapOf("GITHUB_TOKEN" to token)).copy(source = null),
        )
    }

    @Test
    fun `bl-72 strict_mcp is a boolean, bare or quoted, and anything else fails preflight`() {
        useRunner("claude-code", "    command: claude\n    strict_mcp: yes\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.claude-code.strict_mcp must be a boolean"),
            preflight().copy(source = null),
        )

        for (written in listOf("false", "true", "\"false\"")) {
            useRunner("claude-code", "    command: claude\n    strict_mcp: $written\n")
            val workflow = host().workflowLoader().load(fixture.workflow).getOrThrow()
            assertEquals(emptyList(), workflow.configWarnings, "strict_mcp: $written")
        }
    }

    @Test
    fun `a value out of its limits fails preflight naming the key and the range`() {
        // The Claude Code runner alone accepts any positive integer: only its profile caps a timeout at one day.
        useRunner("claude-code", "    command: claude\n    turn_timeout_ms: 86400001\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.claude-code.turn_timeout_ms must be between 1 and 86400000, got 86400001"),
            preflight().copy(source = null),
        )

        useRunner("claude-code", "    command: claude\n    read_timeout_ms: 0\n")
        assertEquals(
            PreflightResult.Failed("invalid_config", "runners.claude-code.read_timeout_ms must be between 1 and 86400000, got 0"),
            preflight().copy(source = null),
        )
    }

    @Test
    fun `an unresolved secret fails startup naming the key and the variable, never a value`() {
        useGitHub("    repo: o/r\n    token: \$LOOP_M305_TOKEN\n")
        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token uses \$LOOP_M305_TOKEN, which is not set"),
            preflight().copy(source = null),
        )

        // Blank counts as unresolved. The launcher's whole output names the variable and holds no value of the env.
        val blank = " \t "
        val env = mapOf("LOOP_M305_TOKEN" to blank, "GITHUB_TOKEN" to token)
        val result = fixture.loop("run", "--once", env = env)

        assertEquals(1, result.exitCode, result.toString())
        assertEquals("loop run: tracker.provider.token uses \$LOOP_M305_TOKEN, which is empty.\n", result.stderr)
        assertFalse(token in result.toString(), result.toString())
        assertFalse("\t" in result.stderr, result.toString())
        assertTrue(fixture.agentCalls() == 0)
    }

    @Test
    fun `an unknown key under a plugin section gives a startup warning naming it, and the run goes on`() {
        useRunner("command", "    command: \"${fixture.agentScript}\"\n    comand: typo\n")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("  kind: files\n", "  kind: files\n  provider:\n    dirr: elsewhere\n"),
        )
        fixture.issue("m3-05")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "loop run: warning: tracker.provider.dirr is not a key of the files tracker, Loop ignores it.\n")
        assertContains(result.stderr, "loop run: warning: runners.command.comand is not a key of the command runner, Loop ignores it.\n")
        assertEquals("review", fixture.stateOf("m3-05"))
    }
}
