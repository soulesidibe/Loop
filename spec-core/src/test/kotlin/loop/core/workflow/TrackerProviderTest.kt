package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerProvider
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * m3-05: the tracker plugins the host hands [WorkflowLoader], `files` included, and the profile check of
 * `tracker.provider`. spec-core never sees a plugin, so a [FakeTrackerProvider] stands in; the cli tests cover the
 * real ones. The bl-23 cases of a registered kind are in [TrackerKindTest].
 */
class TrackerProviderTest {
    private val folder: Path = createTempDirectory("loop-tracker-provider").toRealPath()

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
    fun `with providers the files tracker is a provider like any other`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"files\" is not supported, use fake"),
            preflight(write("  kind: files\n"), listOf(FakeTrackerProvider())),
        )
        val files = FakeTrackerProvider(kind = "files") { _, _ -> null }

        assertNull(loader(listOf(files)).load(write("  kind: files\n")).getOrThrow().trackerSettings)
    }

    @Test
    fun `with no provider found any kind is refused, saying no tracker plugin was found`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"jira\" is not supported, no tracker plugin was found"),
            preflight(write("  kind: jira\n"), emptyList()),
        )
    }

    private val profiled =
        FakeTrackerProvider(
            profile =
                PluginProfile(
                    keys =
                        listOf(
                            ProfileKey("repo", ProfileType.STRING, required = true),
                            ProfileKey("token", ProfileType.STRING, required = true),
                            ProfileKey("page_size", ProfileType.INTEGER, default = "50"),
                        ),
                    secrets = setOf("token"),
                    limits = mapOf("page_size" to 1L..100L),
                ),
        )

    @Test
    fun `m3-05 a tracker section is checked against its profile before the provider reads it`() {
        val env = mapOf("TOKEN" to "ghp_profileToken0123456789")

        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.repo is required"),
            preflight(write("  kind: fake\n  provider:\n    token: \$TOKEN\n"), listOf(profiled), env),
        )
        assertNull(profiled.seen)
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.repo must be a string"),
            preflight(write("  kind: fake\n  provider:\n    repo: [o, r]\n    token: \$TOKEN\n"), listOf(profiled), env),
        )
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.page_size must be between 1 and 100, got 500"),
            preflight(write("  kind: fake\n  provider:\n    repo: o/r\n    token: \$TOKEN\n    page_size: 500\n"), listOf(profiled), env),
        )
    }

    @Test
    fun `m3-05 a missing or unresolved tracker secret fails with missing_tracker_secret naming the variable`() {
        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token is required"),
            preflight(write("  kind: fake\n  provider:\n    repo: o/r\n"), listOf(profiled)),
        )
        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token uses \$TOKEN, which is not set"),
            preflight(write("  kind: fake\n  provider:\n    repo: o/r\n    token: \$TOKEN\n"), listOf(profiled)),
        )
    }

    @Test
    fun `bl-104 a tracker secret out of its limits fails with invalid_tracker_config, never printing its value`() {
        val pinned =
            FakeTrackerProvider(
                profile =
                    PluginProfile(
                        keys = listOf(ProfileKey("pin", ProfileType.INTEGER)),
                        secrets = setOf("pin"),
                        limits = mapOf("pin" to 1L..9_999L),
                    ),
            )

        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.pin must be between 1 and 9999"),
            preflight(write("  kind: fake\n  provider:\n    pin: 123456\n"), listOf(pinned)),
        )
    }

    @Test
    fun `m3-05 an unknown key of the tracker section is a warning on the workflow, not a failure`() {
        val path = write("  kind: fake\n  provider:\n    repo: o/r\n    token: literal-token\n    repoo: x\n")

        val workflow = loader(listOf(profiled)).load(path).getOrThrow()

        assertEquals(listOf("tracker.provider.repoo is not a key of the fake tracker, Loop ignores it"), workflow.configWarnings)
    }
}
