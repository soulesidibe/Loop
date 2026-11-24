package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.AttemptContext
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.RunnerProvider
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.runner.command.CommandRunner
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * m3-04: `runner.kind` selects a runner plugin found with `ServiceLoader`, on the classpath and in the JARs of
 * `<LOOP_HOME>/plugins/`.
 */
class RunnerPluginsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun host(): Host =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()),
            launcherProperty = null,
            userHome = "/tmp",
        )

    /** Replaces the runner block of the fixture's `WORKFLOW.md` with `runner.kind: [kind]` and [section] under `runners.[key]`. */
    private fun useRunner(
        kind: String,
        section: String,
        key: String = kind,
    ) {
        val text = fixture.workflow.readText()
        val start = text.indexOf("runner:\n")
        val end = text.indexOf("polling:\n")
        fixture.workflow.writeText(text.substring(0, start) + "runner:\n  kind: $kind\nrunners:\n  $key:\n$section" + text.substring(end))
    }

    @Test
    fun `command and claude-code are found on the classpath with the service loader`() {
        val plugins = RunnerPlugins.discover(root.resolve("no-plugins")).getOrThrow()

        // m3-17: codex-app-server registers after them, from its own module, followed by acp.
        assertEquals(listOf("command", "claude-code", "codex-app-server", "acp"), plugins.kinds)
    }

    @Test
    fun `runnerFor builds the runner of the provider whose kind matches runner kind`() {
        val fake = FakeProvider("fake")
        val other = FakeProvider("other")
        val plugins = RunnerPlugins.of(listOf(RunnerPlugins.Found(other, "classpath"), RunnerPlugins.Found(fake, "classpath"))).getOrThrow()
        useRunner("fake", "    level: high\n")
        val workflow = workflowLoader(emptyMap(), fixture.homeDir, plugins).load(fixture.workflow).getOrThrow()
        val holdout = root.resolve("holdout")

        val runner = runnerFor(workflow, plugins, hostEnv = mapOf("A" to "1"), removedEnvNames = setOf("B"), holdoutDir = holdout)

        assertSame(fake.built, runner)
        assertEquals(mapOf("level" to "high"), fake.config)
        assertEquals(AgentEnvPolicy(mapOf("A" to "1"), setOf("B"), workflow.config.agent.env), fake.env)
        assertEquals(holdout, fake.holdoutDir)
        assertNull(other.built)
        // The load asked the selected provider to validate its own section, and only that one.
        assertEquals(listOf<Map<String, Any?>>(mapOf("level" to "high")), fake.validated)
        assertEquals(emptyList<Map<String, Any?>>(), other.validated)
    }

    @Test
    fun `the classpath command provider builds the command runner, as before`() {
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        val runner = runnerFor(workflow, host.runnerPlugins.getOrThrow(), host.rawEnv, agentEnvRemovedNames(workflow))

        assertIs<CommandRunner>(runner)
    }

    @Test
    fun `m3-17 runner kind codex-app-server builds the codex runner from runners codex, through plugin loading alone`() {
        useRunner("codex-app-server", "    command: codex app-server\n", key = "codex")
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        val runner = runnerFor(workflow, host.runnerPlugins.getOrThrow(), host.rawEnv, agentEnvRemovedNames(workflow))

        assertEquals("loop.runner.codex.CodexAppServerRunner", runner.javaClass.name)
        val provider = host.runnerPlugins.getOrThrow().provider("codex-app-server")!!
        assertEquals("loop.runner.codex.CodexAppServerRunnerProvider", provider.javaClass.name)
        assertEquals("codex", provider.section)
    }

    @Test
    fun `m3-20 runner kind codex-app-server is built for Loop's runs, given the holdout folder, through plugin loading alone`() {
        useRunner("codex-app-server", "    command: codex app-server\n", key = "codex")
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        // Before m3-20 the provider refused a holdout folder, which loop run always gives, since nothing guarded the run.
        val runner =
            runnerFor(
                workflow,
                host.runnerPlugins.getOrThrow(),
                host.rawEnv,
                agentEnvRemovedNames(workflow),
                holdoutDir = root.resolve("holdout"),
            )

        assertEquals("loop.runner.codex.CodexAppServerRunner", runner.javaClass.name)
    }

    @Test
    fun `runner kind acp builds the acp runner from runners acp through plugin loading`() {
        useRunner(
            "acp",
            "    command: agy acp\n    preset: agy\n    model: gemini-4-neon-high\n",
            key = "acp",
        )
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        val runner =
            runnerFor(
                workflow,
                host.runnerPlugins.getOrThrow(),
                host.rawEnv,
                agentEnvRemovedNames(workflow),
                holdoutDir = root.resolve("holdout"),
            )

        assertEquals("loop.runner.acp.AcpRunner", runner.javaClass.name)
        assertEquals("gemini-4-neon-high", runner.model)
        val provider = host.runnerPlugins.getOrThrow().provider("acp")!!
        assertEquals("loop.runner.acp.AcpRunnerProvider", provider.javaClass.name)
        assertEquals("acp", provider.section)
    }

    @Test
    fun `a provider in a JAR under the plugins folder of LOOP_HOME is found and selected by its kind`() {
        val jar =
            PluginJars.build(
                root.resolve("build"),
                fixture.loopHome.resolve("plugins").createDirectories(),
                "JarRunnerProvider",
                "jar-runner",
            )
        useRunner("jar-runner", "    script: \"${fixture.agentScript}\"\n")
        val host = host()

        val plugins = host.runnerPlugins.getOrThrow()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()
        val runner = runnerFor(workflow, plugins, host.rawEnv, agentEnvRemovedNames(workflow))

        assertEquals(listOf("command", "claude-code", "codex-app-server", "acp", "jar-runner"), plugins.kinds)
        val provider = plugins.provider("jar-runner")!!
        assertEquals("loop.testplugin.JarRunnerProvider", provider.javaClass.name)
        val loader = assertIs<URLClassLoader>(provider.javaClass.classLoader)
        assertEquals(listOf(jar.toUri().toURL()), loader.urLs.toList())
        // The JAR's provider delegates to the command runner of the classpath.
        assertIs<CommandRunner>(runner)
    }

    @Test
    fun `bl-104 a JAR provider built against m3-04, with no profile, loads and is checked by its own validate only`() {
        PluginJars.build(
            root.resolve("build"),
            fixture.loopHome.resolve("plugins").createDirectories(),
            "OldRunnerProvider",
            "old-runner",
            withProfile = false,
        )
        useRunner("old-runner", "    script: \"${fixture.agentScript}\"\n    extra: 1\n")
        val host = host()

        val plugins = host.runnerPlugins.getOrThrow()
        val provider = plugins.provider("old-runner")!!
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        assertSame(PluginProfile.UNDECLARED, provider.profile)
        assertEquals(emptyList(), workflow.configWarnings)
        assertIs<CommandRunner>(runnerFor(workflow, plugins, host.rawEnv, agentEnvRemovedNames(workflow)))
    }

    @Test
    fun `a JAR provider runs an attempt end to end through the installed launcher`() {
        PluginJars.build(root.resolve("build"), fixture.loopHome.resolve("plugins").createDirectories(), "JarRunnerProvider", "jar-runner")
        useRunner("jar-runner", "    script: \"${fixture.agentScript}\"\n")
        fixture.issue("m3-04")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("m3-04"))
    }

    @Test
    fun `an unknown runner kind fails preflight with invalid_config naming the kind and the kinds found`() {
        useRunner("nope", "    command: x\n")

        val result = runBlocking { Preflight(host().workflowLoader(), fixture.workflow, TemplateEngine()) { null }.check() }

        val failed = assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", failed.code)
        assertEquals("runner.kind \"nope\" is not supported, use command, claude-code, codex-app-server or acp", failed.message)
    }

    @Test
    fun `a section its provider refuses fails preflight with invalid_config and the provider's sentence`() {
        useRunner("command", "    other: x\n")

        val result = runBlocking { Preflight(host().workflowLoader(), fixture.workflow, TemplateEngine()) { null }.check() }

        val failed = assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", failed.code)
        assertEquals("runners.command.command is required", failed.message)
    }

    @Test
    fun `two providers of one kind fail startup naming both`() {
        val jar =
            PluginJars.build(
                root.resolve("build"),
                fixture.loopHome.resolve("plugins").createDirectories(),
                "SecondCommandProvider",
                "command",
            )

        val result = LoopCommand(host()).test(listOf("run", "--once", fixture.workflow.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertEquals(
            "loop run: two runner plugins provide runner.kind \"command\": " +
                "loop.runner.command.CommandRunnerProvider from the classpath and loop.testplugin.SecondCommandProvider from $jar.\n",
            result.stderr,
        )
        assertTrue(fixture.agentCalls() == 0)
    }

    @Test
    fun `a JAR whose provider cannot load fails startup naming the JAR`() {
        val jar = PluginJars.buildUnloadable(fixture.loopHome.resolve("plugins").createDirectories(), "broken", "MissingProvider")

        val result = LoopCommand(host()).test(listOf("run", "--once", fixture.workflow.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.stderr.startsWith("loop run: cannot load the runner plugins of $jar: "), result.stderr)
        assertContains(result.stderr, "loop.testplugin.MissingProvider")
        assertTrue(fixture.agentCalls() == 0)
    }

    /** A provider that records what Loop gives it. */
    private class FakeProvider(
        override val kind: String,
    ) : RunnerProvider {
        override val profile: PluginProfile = PluginProfile(listOf(ProfileKey("level", ProfileType.STRING)))
        val validated = mutableListOf<Map<String, Any?>>()
        var config: Map<String, Any?>? = null
        var env: AgentEnvPolicy? = null
        var holdoutDir: Path? = null
        var built: AgentRunner? = null

        override fun validate(config: Map<String, Any?>) {
            validated += config
        }

        override fun create(
            config: Map<String, Any?>,
            env: AgentEnvPolicy,
            holdoutDir: Path?,
        ): AgentRunner {
            this.config = config
            this.env = env
            this.holdoutDir = holdoutDir
            return object : AgentRunner {
                override val capabilities =
                    RunnerCapabilities(
                        continuation = false,
                        loopTools = RunnerCapabilities.LoopTools.Files,
                        usage = false,
                        cost = false,
                        occupancy = false,
                        sandbox = false,
                    )

                override fun run(
                    ctx: AttemptContext,
                    prompt: String,
                ): Flow<RunnerEvent> = emptyFlow()
            }.also { built = it }
        }
    }
}
