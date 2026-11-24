package loop.core.trackers

import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerAdapter
import loop.api.TrackerConfigException
import loop.api.TrackerContext
import loop.api.TrackerProvider
import loop.core.template.TemplateEngine
import loop.core.workflow.ConfigValue
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.TrackerCheck
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * m4-11, story 111: `tracker:` stays the primary tracker, with the id of its `id` key or `default`, and `trackers:`
 * lists extra trackers, each read and checked by its own tracker plugin, into the [TrackersConfig] of the workflow and
 * the [TrackerRegistry] built from it.
 */
class TrackerRegistryTest {
    private val folder: Path = createTempDirectory("loop-trackers").toRealPath()

    /** A github like plugin: `repo` required, `token` a secret, the provider map as its settings. */
    private val remote =
        TestTrackerProvider(
            kind = "remote",
            profile =
                PluginProfile(
                    keys = listOf(ProfileKey("repo", ProfileType.STRING, required = true), ProfileKey("token", ProfileType.STRING)),
                    secrets = setOf("token"),
                ),
            defined = listOf("open", "closed"),
        ) { provider, _ ->
            val refused = provider["repo"] == "bad"
            if (refused) throw TrackerConfigException("invalid_tracker_config", "tracker.provider.repo must be owner/name")
            provider
        }

    /** A files like plugin with no settings, whose profile lists `dir` as the real one does. */
    private val files =
        TestTrackerProvider(
            kind = "files",
            profile = PluginProfile(listOf(ProfileKey("dir", ProfileType.STRING, default = ".loop/issues"))),
            defined = listOf("todo", "in_progress", "review", "done", "canceled"),
            defaultActiveStates = listOf("todo", "in_progress"),
            defaultTerminalStates = listOf("done", "canceled"),
        ) { _, _ -> null }

    private val env = mapOf("REMOTE_TOKEN" to "remote-token-value-0123")

    private fun loader(deferSecrets: Boolean = false) =
        WorkflowLoader(env = env, home = folder, trackerCheck = TrackerCheck.of(listOf(remote, files)), deferSecrets = deferSecrets)

    private fun write(front: String): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                "---\n$front" +
                    "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n---\nWork on {{ issue.identifier }}\n",
            )
        }

    private fun load(front: String): WorkflowFile = loader().load(write(front)).getOrThrow()

    private fun preflight(front: String): PreflightResult =
        runBlocking { Preflight(loader(), write(front), TemplateEngine(), { null }).check() }
            .let { if (it is PreflightResult.Failed) it.copy(source = null) else it }

    private val primary =
        "tracker:\n" +
            "  id: gh\n" +
            "  kind: remote\n" +
            "  provider: { repo: acme/app, token: \$REMOTE_TOKEN }\n" +
            "  required_labels: [ready-for-agent]\n" +
            "  active_states: [open]\n" +
            "  terminal_states: [closed]\n"

    @Test
    fun `a workflow with tracker and trackers loads the primary id and each extra tracker's kind, settings and states`() {
        val workflow =
            load(
                primary +
                    "trackers:\n" +
                    "  - id: local\n" +
                    "    kind: files\n" +
                    "    provider: { dir: ./side-issues }\n" +
                    "    active_states: [todo]\n" +
                    "    terminal_states: [done]\n" +
                    "  - id: mirror\n" +
                    "    kind: remote\n" +
                    "    provider: { repo: acme/mirror }\n",
            )
        val trackers = workflow.trackers

        assertEquals("gh", trackers.primary.id)
        assertEquals(listOf("gh", "local", "mirror"), trackers.ids)
        assertEquals("gh", workflow.trackerId)
        assertEquals(mapOf("repo" to "acme/app", "token" to "\$REMOTE_TOKEN"), trackers.primary.settings)

        val local = trackers.entry("local")!!
        assertEquals("files", local.kind)
        assertNull(local.settings)
        assertEquals(folder.resolve("side-issues"), local.dir)
        assertEquals(listOf("todo"), local.activeStates)
        assertEquals(listOf("done"), local.terminalStates)
        assertEquals(mapOf("dir" to ConfigValue.Str("./side-issues")), local.provider)

        val mirror = trackers.entry("mirror")!!
        assertEquals("remote", mirror.kind)
        assertEquals(mapOf("repo" to "acme/mirror"), mirror.settings)
        // An extra tracker that sets no states gets its plugin's defaults, as tracker: does.
        assertEquals(listOf("open"), mirror.activeStates)
        assertEquals(listOf("closed"), mirror.terminalStates)
        assertNull(trackers.entry("lin"))
    }

    @Test
    fun `the primary tracker's id is default when tracker sets no id`() {
        val workflow = load("tracker:\n  kind: files\ntrackers:\n  - { id: gh, kind: remote, provider: { repo: acme/app } }\n")

        assertEquals("default", workflow.trackers.primary.id)
        assertEquals(TrackersConfig.DEFAULT_ID, workflow.trackerId)
        assertEquals(listOf("default", "gh"), workflow.trackers.ids)
    }

    @Test
    fun `each tracker has its own required labels, the primary's from tracker`() {
        val trackers =
            load(
                primary +
                    "trackers:\n" +
                    "  - id: local\n" +
                    "    kind: files\n" +
                    "    required_labels: [ready-for-agent, local]\n" +
                    "  - id: mirror\n" +
                    "    kind: remote\n" +
                    "    provider: { repo: acme/mirror }\n" +
                    "    required_labels: [mirror-ready]\n",
            ).trackers

        assertEquals(listOf("ready-for-agent"), trackers.entry("gh")!!.requiredLabels)
        assertEquals(listOf("ready-for-agent", "local"), trackers.entry("local")!!.requiredLabels)
        assertEquals(listOf("mirror-ready"), trackers.entry("mirror")!!.requiredLabels)
    }

    @Test
    fun `an extra tracker with no required labels requires none, whatever the primary's`() {
        val trackers = load(primary + "trackers:\n  - { id: local, kind: files }\n").trackers

        assertEquals(emptyList(), trackers.entry("local")!!.requiredLabels)
    }

    @Test
    fun `the registry gives each tracker id its adapter, states and required labels`() {
        val config = load(primary + "trackers:\n  - { id: local, kind: files, required_labels: [mine] }\n").trackers
        val built = mutableListOf<String>()

        val registry = TrackerRegistry.build(config) { entry -> NamedAdapter(entry.id).also { built += entry.id } }

        assertEquals(listOf("gh", "local"), built)
        assertEquals("gh", registry.primary.id)
        assertEquals("gh", (registry.primaryAdapter.native as NamedAdapter).id)
        assertEquals("local", (registry.adapter("local")!!.native as NamedAdapter).id)
        // m4-12: each adapter namespaces the ids of its own tracker.
        assertEquals(listOf("gh", "local"), listOf(registry.primaryAdapter.trackerId, registry.adapter("local")!!.trackerId))
        assertEquals(listOf("mine"), registry.entry("local")!!.requiredLabels)
        assertEquals(listOf("todo", "in_progress"), registry.entry("local")!!.activeStates)
        assertSame(config, registry.config)
        assertNull(registry.adapter("lin"))
    }

    @Test
    fun `a workflow with no trackers key loads exactly as before`() {
        val front =
            "tracker:\n  kind: files\n  active_states: [todo]\n  required_labels: [ready-for-agent]\n" +
                "  provider: { dir: ./issues }\n"

        val workflow = load(front)

        assertEquals("default", workflow.trackerId)
        assertEquals(emptyList(), workflow.extraTrackers)
        assertEquals(listOf("default"), workflow.trackers.ids)
        val primary = workflow.trackers.primary
        assertEquals(
            TrackerEntry(
                id = "default",
                kind = "files",
                dir = folder.resolve("issues"),
                settings = null,
                activeStates = listOf("todo"),
                terminalStates = listOf("done", "canceled"),
                requiredLabels = listOf("ready-for-agent"),
                provider = mapOf("dir" to ConfigValue.Str("./issues")),
            ),
            primary,
        )
        assertEquals(workflow.trackerKind, primary.kind)
        assertEquals(workflow.trackerDir, primary.dir)
        assertEquals(workflow.activeStates, primary.activeStates)
        assertEquals(workflow.terminalStates, primary.terminalStates)
        assertEquals(workflow.requiredLabels, primary.requiredLabels)
        assertEquals(workflow.config.trackerProvider, primary.provider)
        assertEquals(emptyList(), workflow.configWarnings)
        assertEquals(emptyList(), workflow.trackers.extras)
        // An empty list is no extra tracker either.
        assertEquals(emptyList(), load(front + "trackers: []\n").extraTrackers)
    }

    @Test
    fun `preflight refuses an id used twice, naming the entry`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].id \"gh\" is already the id of the primary tracker"),
            preflight(primary + "trackers:\n  - { id: gh, kind: files }\n"),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[1].id \"local\" is already the id of trackers[0]"),
            preflight(primary + "trackers:\n  - { id: local, kind: files }\n  - { id: local, kind: files, provider: { dir: ./b } }\n"),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].id \"default\" is already the id of the primary tracker"),
            preflight("tracker:\n  kind: files\ntrackers:\n  - { id: default, kind: files, provider: { dir: ./b } }\n"),
        )
    }

    @Test
    fun `preflight refuses an id that does not match the id pattern, naming the entry`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].id \"Local_1\" must match [a-z][a-z0-9-]*"),
            preflight(primary + "trackers:\n  - { id: Local_1, kind: files }\n"),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.id \"1gh\" must match [a-z][a-z0-9-]*"),
            preflight("tracker:\n  id: 1gh\n  kind: files\n"),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].id is required"),
            preflight(primary + "trackers:\n  - { kind: files }\n"),
        )
    }

    @Test
    fun `preflight refuses a kind with no tracker plugin, naming the entry`() {
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].kind \"linear\" is not supported, use files or remote"),
            preflight(primary + "trackers:\n  - { id: lin, kind: linear }\n"),
        )
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].kind is required"),
            preflight(primary + "trackers:\n  - { id: lin }\n"),
        )
    }

    @Test
    fun `without tracker plugins an extra tracker other than files is refused, naming the entry`() {
        val path = write("tracker:\n  kind: files\ntrackers:\n  - { id: lin, kind: linear }\n")

        val error = WorkflowLoader(env = emptyMap()).load(path).exceptionOrNull()

        assertEquals("trackers[0].kind \"linear\" is not supported, use files", error?.message)
    }

    @Test
    fun `preflight refuses an extra tracker whose plugin refuses its provider, naming the entry`() {
        // The profile check of the plugin names the entry's own section.
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "trackers[0].provider.repo is required"),
            preflight(primary + "trackers:\n  - { id: mirror, kind: remote }\n"),
        )
        // The plugin's own parse names tracker.provider, which Loop renames to the entry's section.
        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "trackers[0].provider.repo must be owner/name"),
            preflight(primary + "trackers:\n  - { id: mirror, kind: remote, provider: { repo: bad } }\n"),
        )
        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "trackers[0].provider.token uses \$MISSING_TOKEN, which is not set"),
            preflight(primary + "trackers:\n  - { id: mirror, kind: remote, provider: { repo: acme/b, token: \$MISSING_TOKEN } }\n"),
        )
    }

    @Test
    fun `preflight refuses a state the extra tracker's plugin does not define, naming the entry`() {
        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "trackers[0].active_states has \"Todo\", which is not a state of the remote tracker. Valid states: open, closed",
            ),
            preflight(primary + "trackers:\n  - { id: mirror, kind: remote, provider: { repo: acme/b }, active_states: [Todo] }\n"),
        )
    }

    @Test
    fun `trackers must be a list of maps`() {
        assertEquals(PreflightResult.Failed("invalid_config", "trackers must be a list"), preflight(primary + "trackers: { id: x }\n"))
        assertEquals(PreflightResult.Failed("invalid_config", "trackers[0] must be a map"), preflight(primary + "trackers: [local]\n"))
        assertEquals(
            PreflightResult.Failed("invalid_config", "trackers[0].required_labels must be a list of strings"),
            preflight(primary + "trackers:\n  - { id: local, kind: files, required_labels: mine }\n"),
        )
    }

    @Test
    fun `an unknown key of an extra tracker is warned about, not refused`() {
        val workflow = load(primary + "trackers:\n  - { id: local, kind: files, required_label: [x] }\n")

        assertEquals(listOf("trackers[0].required_label is not a key of a tracker entry, Loop ignores it"), workflow.configWarnings)
    }

    @Test
    fun `a deferred secret of an extra tracker is named and its settings dropped`() {
        val path = write(primary + "trackers:\n  - { id: mirror, kind: remote, provider: { repo: acme/b, token: \$MIRROR_TOKEN } }\n")

        val workflow = loader(deferSecrets = true).load(path).getOrThrow()

        assertEquals(
            listOf("trackers[0].provider.token uses \$MIRROR_TOKEN, which is not set"),
            workflow.deferredSecrets.map { it.sentence },
        )
        assertNull(workflow.trackers.entry("mirror")!!.settings)
    }

    /** A tracker plugin for these tests, whose [parse] is [answer]. */
    private class TestTrackerProvider(
        override val kind: String,
        override val profile: PluginProfile = PluginProfile(emptyList()),
        private val defined: List<String>,
        override val defaultActiveStates: List<String> = defined.take(1),
        override val defaultTerminalStates: List<String> = defined.takeLast(1),
        private val answer: (Map<String, Any?>, Map<String, String>) -> Any?,
    ) : TrackerProvider {
        override val definedStates = defined

        override fun parse(
            provider: Map<String, Any?>,
            env: Map<String, String>,
        ): Any? = answer(provider, env)

        override fun create(
            settings: Any?,
            context: TrackerContext,
        ): TrackerAdapter = NamedAdapter(kind)
    }

    /** An adapter that only knows its [id]. */
    private class NamedAdapter(
        val id: String,
    ) : TrackerAdapter {
        override val definedStates: List<String> = emptyList()

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
    }
}
