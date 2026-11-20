package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerAdapter
import loop.api.TrackerContext
import loop.api.TrackerProvider
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.tracker.files.FilesTracker
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * m3-05: `tracker.kind` selects a tracker plugin found with `ServiceLoader`, like the runner plugins (m3-04), on the
 * classpath, where `files` and `github` register, and in the JARs of `<LOOP_HOME>/plugins/`.
 */
class TrackerPluginsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun host(): Host =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()),
            launcherProperty = null,
            userHome = "/tmp",
        )

    /** Replaces `kind: files` of the fixture's `WORKFLOW.md` with [kind] and adds [provider] lines under `tracker.provider`. */
    private fun useTracker(
        kind: String,
        provider: String = "",
    ) {
        val section = if (provider.isEmpty()) "" else "  provider:\n$provider"
        fixture.workflow.writeText(fixture.workflow.readText().replace("  kind: files\n", "  kind: $kind\n$section"))
    }

    @Test
    fun `files and github are found on the classpath with the service loader`() {
        val plugins = TrackerPlugins.discover(root.resolve("no-plugins")).getOrThrow()

        assertEquals(listOf("files", "github"), plugins.kinds.sorted())
        assertEquals("loop.tracker.files.FilesTrackerProvider", plugins.provider("files")?.javaClass?.name)
        assertEquals("loop.tracker.github.GitHubTrackerProvider", plugins.provider("github")?.javaClass?.name)
    }

    @Test
    fun `trackerFor builds the tracker of the provider whose kind matches tracker kind`() {
        val fake = FakeProvider("fake")
        val other = FakeProvider("other")
        val plugins =
            TrackerPlugins
                .of(
                    listOf(TrackerPlugins.Found(other, "classpath"), TrackerPlugins.Found(fake, "classpath")),
                ).getOrThrow()
        useTracker("fake", "    level: high\n")
        val workflow = workflowLoader(emptyMap(), fixture.homeDir, trackers = plugins).load(fixture.workflow).getOrThrow()
        val warnings = mutableListOf<String>()

        val tracker =
            trackerFor(workflow, fixture.loopHome, onWarning = { warnings += it }, requiredLabels = { listOf("x") }, plugins = plugins)

        assertSame(fake.built, tracker)
        assertNull(other.built)
        assertEquals(mapOf("level" to "high"), fake.parsed)
        assertEquals("settings of fake", fake.settings)
        val context = fake.context!!
        assertEquals(workflow.trackerDir, context.trackerDir)
        assertEquals(fixture.loopHome.resolve("locks"), context.lockDir)
        assertEquals(listOf("x"), context.requiredLabels())
        context.onWarning("seen")
        assertEquals(listOf("seen"), warnings)
        // The fixture sets the active states; the terminal states default to the provider's, not the files ones.
        assertEquals(listOf("todo"), workflow.activeStates)
        assertEquals(listOf("gone"), workflow.terminalStates)
        assertEquals("settings of fake", workflow.trackerSettings)
    }

    @Test
    fun `the classpath files provider builds the files tracker, as before`() {
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        val tracker = trackerFor(workflow, host.loopHome, plugins = host.trackerPlugins.getOrThrow())

        assertIs<FilesTracker>(tracker)
        assertNull(trackerProblem(workflow, host.trackerPlugins.getOrThrow()))
    }

    @Test
    fun `bl-104 trackerFor throws for a kind no provider answers to`() {
        val workflow = host().workflowLoader().load(fixture.workflow).getOrThrow()
        val none = TrackerPlugins.of(emptyList()).getOrThrow()

        val failure = assertFailsWith<IllegalStateException> { trackerFor(workflow, fixture.loopHome, plugins = none) }
        assertEquals("tracker.kind \"files\" is not supported, no tracker plugin was found", failure.message)
        assertEquals("tracker.kind \"files\" is not supported, no tracker plugin was found", trackerProblem(workflow, none))
    }

    @Test
    fun `an unknown tracker kind fails preflight with invalid_config naming the kinds found`() {
        useTracker("jira")

        val result = runBlocking { Preflight(host().workflowLoader(), fixture.workflow, TemplateEngine()) { null }.check() }

        val failed = assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", failed.code)
        assertEquals("tracker.kind \"jira\" is not supported, use files or github", failed.message)
    }

    @Test
    fun `a provider in a JAR of the plugin folder is found and selected by its kind`() {
        val jar =
            PluginJars.buildTracker(
                root.resolve("build"),
                fixture.loopHome.resolve("plugins").createDirectories(),
                "JarTrackerProvider",
                "jar-files",
            )
        useTracker("jar-files")
        val host = host()

        val plugins = host.trackerPlugins.getOrThrow()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()
        val tracker = trackerFor(workflow, host.loopHome, plugins = plugins)

        assertEquals(listOf("files", "github", "jar-files"), plugins.kinds.sorted())
        val provider = plugins.provider("jar-files")!!
        assertEquals("loop.testplugin.JarTrackerProvider", provider.javaClass.name)
        val loader = assertIs<URLClassLoader>(provider.javaClass.classLoader)
        assertEquals(listOf(jar.toUri().toURL()), loader.urLs.toList())
        // The JAR's provider delegates to the files tracker of the classpath.
        assertIs<FilesTracker>(tracker)
    }

    @Test
    fun `a JAR tracker runs an attempt end to end through the installed launcher`() {
        PluginJars.buildTracker(
            root.resolve("build"),
            fixture.loopHome.resolve("plugins").createDirectories(),
            "JarTrackerProvider",
            "jar-files",
        )
        useTracker("jar-files")
        fixture.issue("m3-05")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("m3-05"))
    }

    @Test
    fun `two providers of one kind fail startup naming both`() {
        val jar =
            PluginJars.buildTracker(
                root.resolve("build"),
                fixture.loopHome.resolve("plugins").createDirectories(),
                "SecondFilesProvider",
                "files",
            )

        val result = LoopCommand(host()).test(listOf("run", "--once", fixture.workflow.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertEquals(
            "loop run: two tracker plugins provide tracker.kind \"files\": " +
                "loop.tracker.files.FilesTrackerProvider from the classpath and loop.testplugin.SecondFilesProvider from $jar.\n",
            result.stderr,
        )
        assertTrue(fixture.agentCalls() == 0)
    }

    /** A provider that records what Loop gives it; it reads `level` and defaults to `todo` and `gone`. */
    private class FakeProvider(
        override val kind: String,
    ) : TrackerProvider {
        override val profile: PluginProfile = PluginProfile(listOf(ProfileKey("level", ProfileType.STRING)))
        override val defaultActiveStates: List<String> = listOf("todo")
        override val defaultTerminalStates: List<String> = listOf("gone")
        override val definedStates: List<String> = listOf("todo", "gone")
        var parsed: Map<String, Any?>? = null
        var settings: Any? = null
        var context: TrackerContext? = null
        var built: TrackerAdapter? = null

        override fun parse(
            provider: Map<String, Any?>,
            env: Map<String, String>,
        ): Any? {
            parsed = provider
            return "settings of $kind"
        }

        override fun create(
            settings: Any?,
            context: TrackerContext,
        ): TrackerAdapter {
            this.settings = settings
            this.context = context
            return object : TrackerAdapter {
                override val definedStates: List<String> = this@FakeProvider.definedStates

                override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = Result.success(emptyList())

                override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> = Result.success(emptyList())

                override suspend fun moveIssue(
                    issueId: String,
                    toState: String,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun addComment(
                    issueId: String,
                    comment: IssueComment,
                ): Result<Unit> = Result.success(Unit)
            }.also { built = it }
        }
    }
}
