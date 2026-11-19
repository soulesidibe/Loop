package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.api.TrackerConfigException
import loop.api.TrackerProvider
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * bl-23: a tracker kind the host registers with [WorkflowLoader]. Since m3-05 a kind is a [TrackerProvider] the host
 * hands the loader through [TrackerCheck], so a [FakeTrackerProvider] stands in for `github`; the cli tests cover the
 * real ones. The m3-05 cases are in [TrackerProviderTest].
 */
class TrackerKindTest {
    private val folder: Path = createTempDirectory("loop-tracker-kind").toRealPath()

    private fun write(tracker: String): Path = writeTrackerWorkflow(folder, tracker)

    private fun loader(
        trackers: List<TrackerProvider>?,
        env: Map<String, String> = emptyMap(),
    ) = WorkflowLoader(env = env, trackerCheck = trackers?.let(TrackerCheck::of))

    private fun preflight(
        path: Path,
        trackers: List<TrackerProvider>?,
        env: Map<String, String> = emptyMap(),
    ): PreflightResult =
        runBlocking { Preflight(loader(trackers, env), path, TemplateEngine(), { null }).check() }
            .let { if (it is PreflightResult.Failed) it.copy(source = null) else it }

    @Test
    fun `a registered kind reads its provider map as plain values and gets its settings on the workflow`() {
        val kind = FakeTrackerProvider { provider, env -> "settings of ${provider["repo"]} with ${env["TOKEN"]}" }
        val path =
            write(
                "  kind: fake\n  provider:\n    repo: o/r\n    token: \$TOKEN\n    labels: [a, b]\n" +
                    "    nested:\n      key: value\n    none: null\n",
            )

        val workflow = loader(listOf(kind), env = mapOf("TOKEN" to "t")).load(path).getOrThrow()

        assertEquals("fake", workflow.trackerKind)
        assertEquals("settings of o/r with t", workflow.trackerSettings)
        assertEquals(
            mapOf("repo" to "o/r", "token" to "\$TOKEN", "labels" to listOf("a", "b"), "nested" to mapOf("key" to "value"), "none" to null),
            kind.seen,
        )
    }

    @Test
    fun `a registered kind gives its own default states when the workflow sets none`() {
        val trackers = listOf(FakeTrackerProvider())

        val defaults = loader(trackers).load(write("  kind: fake\n")).getOrThrow()
        assertEquals(listOf("open"), defaults.activeStates)
        assertEquals(listOf("closed"), defaults.terminalStates)

        val set = loader(trackers).load(write("  kind: fake\n  active_states: [triage]\n  terminal_states: [gone]\n")).getOrThrow()
        assertEquals(listOf("triage"), set.activeStates)
        assertEquals(listOf("gone"), set.terminalStates)
    }

    @Test
    fun `the files tracker has no settings object and keeps its defaults`() {
        // A loader without tracker plugins, as in spec-core and the conformance tests, knows `files` alone.
        val workflow = loader(null).load(write("  kind: files\n")).getOrThrow()

        assertNull(workflow.trackerSettings)
        assertEquals(listOf("todo", "in_progress"), workflow.activeStates)
    }

    @Test
    fun `an unregistered kind is refused, naming every kind this build has`() {
        val trackers =
            listOf(FakeTrackerProvider(kind = "github"), FakeTrackerProvider(kind = "files"), FakeTrackerProvider(kind = "alpha"))

        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"jira\" is not supported, use alpha, files or github"),
            preflight(write("  kind: jira\n"), trackers),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"jira\" is not supported, use files"),
            preflight(write("  kind: jira\n"), null),
        )
    }

    @Test
    fun `a tracker config error is a preflight failure with its own category`() {
        val trackers =
            listOf(
                FakeTrackerProvider { _, _ ->
                    throw TrackerConfigException("missing_tracker_secret", "tracker.provider.token uses \$TOKEN, which is not set")
                },
            )

        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token uses \$TOKEN, which is not set"),
            preflight(write("  kind: fake\n  provider:\n    token: \$TOKEN\n"), trackers),
        )
    }

    @Test
    fun `any other failure of a kind is invalid config`() {
        val trackers = listOf(FakeTrackerProvider { _, _ -> throw IllegalStateException("tracker.provider.repo is odd") })

        val error = loader(trackers).load(write("  kind: fake\n")).exceptionOrNull()

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("tracker.provider.repo is odd", error.message)
    }

    @Test
    fun `tracker provider must be a map for a registered kind too`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.provider must be a map"),
            preflight(write("  kind: fake\n  provider: nope\n"), listOf(FakeTrackerProvider())),
        )
    }
}
