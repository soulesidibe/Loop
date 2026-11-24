package loop.runner.acp

import loop.api.AgentEnvPolicy
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.RunnerConfigException
import loop.api.RunnerProvider
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AcpRunnerProviderTest {
    @TempDir
    lateinit var dir: Path

    private val provider = AcpRunnerProvider()

    @Test
    fun `service loader discovers the acp runner provider`() {
        val found = ServiceLoader.load(RunnerProvider::class.java).filter { it.kind == "acp" }

        assertEquals(listOf("loop.runner.acp.AcpRunnerProvider"), found.map { it.javaClass.name })
        assertEquals("acp", found.single().section)
    }

    @Test
    fun `profile declares all acp keys, defaults, and timeout limits`() {
        assertEquals(
            listOf(
                ProfileKey("command", ProfileType.STRING, required = true),
                ProfileKey("preset", ProfileType.STRING),
                ProfileKey("model", ProfileType.STRING),
                ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "3600000"),
                ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "120000"),
                ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "true"),
            ),
            provider.profile.keys,
        )
        assertEquals(emptySet(), provider.profile.secrets)
        assertEquals(
            mapOf("turn_timeout_ms" to 1L..86_400_000L, "read_timeout_ms" to 1L..86_400_000L),
            provider.profile.limits,
        )
    }

    @Test
    fun `validates agy preset config and generic config with strict_mcp false`() {
        val agy =
            AcpSettings.of(
                mapOf(
                    "command" to "agy acp",
                    "preset" to "agy",
                    "model" to "gemini-4-neon-high",
                ),
            )
        assertEquals(listOf("agy", "acp"), agy.argv)
        assertEquals("agy", agy.preset)
        assertEquals("gemini-4-neon-high", agy.model)
        assertTrue(agy.strictMcp)
        assertEquals(3_600_000.milliseconds, agy.turnTimeout)
        assertEquals(120_000.milliseconds, agy.readTimeout)

        val agyByPath =
            AcpSettings.of(
                mapOf(
                    "command" to "/usr/local/bin/agy acp",
                    "preset" to "agy",
                    "model" to "gemini-3.8-flash-low",
                ),
            )
        assertEquals(listOf("/usr/local/bin/agy", "acp"), agyByPath.argv)

        val generic =
            AcpSettings.of(
                mapOf(
                    "command" to "my-acp-agent --stdio",
                    "strict_mcp" to false,
                    "read_timeout_ms" to "5000",
                    "turn_timeout_ms" to 60_000,
                ),
            )
        assertEquals(listOf("my-acp-agent", "--stdio"), generic.argv)
        assertNull(generic.preset)
        assertNull(generic.model)
        assertFalse(generic.strictMcp)
        assertEquals(5_000.milliseconds, generic.readTimeout)
        assertEquals(60_000.milliseconds, generic.turnTimeout)
    }

    @Test
    fun `rejects invalid preset, model, strict_mcp, and command combinations naming keys without leaking values`() {
        assertEquals(
            "runners.acp.command is required",
            assertFailsWith<RunnerConfigException> { provider.validate(emptyMap()) }.message,
        )

        val badPreset =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "goose acp", "preset" to "goose", "strict_mcp" to false))
            }
        assertEquals("runners.acp.preset must be agy", badPreset.message)
        assertFalse("goose" in badPreset.message!!)

        val modelWithoutPreset =
            assertFailsWith<RunnerConfigException> {
                provider.validate(
                    mapOf("command" to "my-agent", "model" to "gemini-4-neon-high", "strict_mcp" to false),
                )
            }
        assertEquals("runners.acp.model requires runners.acp.preset: agy", modelWithoutPreset.message)
        assertFalse("gemini-4-neon-high" in modelWithoutPreset.message!!)

        val strictWithoutPreset =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "my-agent"))
            }
        assertTrue(strictWithoutPreset.message!!.startsWith("runners.acp.strict_mcp "), strictWithoutPreset.message)

        val missingModelWithAgy =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "agy acp", "preset" to "agy"))
            }
        assertEquals("runners.acp.model is required when runners.acp.preset is agy", missingModelWithAgy.message)

        val nonGeminiModel =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "agy acp", "preset" to "agy", "model" to "claude-opus-5-5"))
            }
        assertEquals("runners.acp.model must start with gemini- when runners.acp.preset is agy", nonGeminiModel.message)
        assertFalse("claude-opus-5-5" in nonGeminiModel.message!!)

        listOf(
            "agy",
            "agy chat",
            "agy acp --dangerously-skip-permissions",
            "agy acp --sandbox",
            "gemini acp",
            "/usr/local/bin/not-agy acp",
        ).forEach { badCommand ->
            val failure =
                assertFailsWith<RunnerConfigException>(badCommand) {
                    provider.validate(mapOf("command" to badCommand, "preset" to "agy", "model" to "gemini-4-neon-high"))
                }
            assertEquals(
                "runners.acp.command must be agy acp (or <path>/agy acp) when runners.acp.preset is agy",
                failure.message,
                badCommand,
            )
        }
    }

    @Test
    fun `rejects shell syntax and leading env assignments in command`() {
        listOf(
            "my-agent | tee log",
            "my-agent && true",
            "\$HOME/bin/agent",
            "~/bin/agent",
            "FOO=secret my-agent",
            "'unterminated",
        ).forEach { command ->
            val failure =
                assertFailsWith<RunnerConfigException>(command) {
                    provider.validate(mapOf("command" to command, "strict_mcp" to false))
                }
            assertTrue(failure.message!!.startsWith("runners.acp.command "), "$command: ${failure.message}")
            assertFalse("secret" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `rejects invalid timeout and boolean values`() {
        val badRead =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "my-agent", "strict_mcp" to false, "read_timeout_ms" to 0))
            }
        assertTrue(badRead.message!!.startsWith("runners.acp.read_timeout_ms "), badRead.message)

        val badTurn =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "my-agent", "strict_mcp" to false, "turn_timeout_ms" to 86_400_001))
            }
        assertTrue(badTurn.message!!.startsWith("runners.acp.turn_timeout_ms "), badTurn.message)

        val badBool =
            assertFailsWith<RunnerConfigException> {
                provider.validate(mapOf("command" to "my-agent", "strict_mcp" to "yes"))
            }
        assertEquals("runners.acp.strict_mcp must be a boolean", badBool.message)
    }

    @Test
    fun `create builds AcpRunner with configured model and holdout folder`() {
        val policy = AgentEnvPolicy(hostEnv = emptyMap(), removedEnvNames = emptySet(), addedEnv = emptyMap())
        val holdout = dir.resolve("holdout")

        val runner =
            assertIs<AcpRunner>(
                provider.create(
                    mapOf("command" to "agy acp", "preset" to "agy", "model" to "gemini-4-neon-high"),
                    policy,
                    holdoutDir = holdout,
                ),
            )
        assertEquals("gemini-4-neon-high", runner.model)
        assertEquals(holdout, runner.settings.holdoutDir)

        val generic =
            assertIs<AcpRunner>(
                provider.create(mapOf("command" to "my-agent", "strict_mcp" to false), policy, holdoutDir = null),
            )
        assertNull(generic.model)
    }
}
