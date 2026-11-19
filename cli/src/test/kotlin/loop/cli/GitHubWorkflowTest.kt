package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import loop.api.ProjectId
import loop.api.TrackerError
import loop.core.template.TemplateEngine
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowFile
import loop.runner.process.agentEnvironment
import loop.tracker.github.GitHubTracker
import loop.tracker.github.GitHubTrackerSettings
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bl-23: `tracker.kind: github` through `WORKFLOW.md`, the loader, preflight and the host. */
class GitHubWorkflowTest {
    @TempDir
    lateinit var root: Path

    private val token = "ghp_bl23TokenValue0123456789abcdefghij"

    private fun write(
        provider: String = "    repo: Soules-Studio-Ltd/loop-playground\n    token: \$GITHUB_TOKEN\n",
        states: String = "",
        requiredLabels: String = "  required_labels: [ready-for-agent]\n",
    ): Path =
        root.resolve("WORKFLOW.md").also {
            it.writeText(
                "---\n" +
                    "tracker:\n" +
                    "  kind: github\n" +
                    "  provider:\n" +
                    provider +
                    states +
                    requiredLabels +
                    "runner:\n" +
                    "  kind: command\n" +
                    "runners:\n" +
                    "  command:\n" +
                    "    command: agent.sh\n" +
                    "---\n" +
                    "Work on {{ issue.identifier }}\n",
            )
        }

    private fun load(
        path: Path,
        env: Map<String, String> = mapOf("GITHUB_TOKEN" to token),
    ): Result<WorkflowFile> = workflowLoader(env, root).load(path)

    /** The host's preflight, with its tracker check, as `loop run` builds it; the failure without its source. */
    private fun preflight(
        path: Path,
        env: Map<String, String>,
    ): PreflightResult =
        runBlocking {
            Preflight(workflowLoader(env, root), path, TemplateEngine(), { trackerProblem(it) }).check()
        }.let { if (it is PreflightResult.Failed) it.copy(source = null) else it }

    @Test
    fun `17_1 item 10 the token is resolved from the environment through WORKFLOW md`() {
        val workflow = load(write()).getOrThrow()

        assertEquals("github", workflow.trackerKind)
        val settings = assertIs<GitHubTrackerSettings>(workflow.trackerSettings)
        assertEquals("Soules-Studio-Ltd", settings.owner)
        assertEquals("loop-playground", settings.repo)
        assertEquals(token, settings.token)
        assertEquals("GITHUB_TOKEN", settings.tokenEnvName)
        assertEquals(GitHubTrackerSettings.DEFAULT_API_URL, settings.apiUrl)
        assertEquals(listOf("ready-for-agent"), workflow.requiredLabels)
    }

    @Test
    fun `the github tracker gets its profile default states`() {
        val workflow = load(write()).getOrThrow()

        assertEquals(listOf("open"), workflow.activeStates)
        assertEquals(listOf("closed"), workflow.terminalStates)
        assertIs<PreflightResult.Ok>(preflight(write(), mapOf("GITHUB_TOKEN" to token)))
    }

    @Test
    fun `17_1 item 10 an unset token variable fails preflight with a missing secret naming the variable`() {
        val result = preflight(write(), mapOf("OTHER" to token))

        assertEquals(
            PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token uses \$GITHUB_TOKEN, which is not set"),
            result,
        )
    }

    @Test
    fun `an empty or blank token variable fails preflight naming the variable, never a value`() {
        for (value in listOf("", "   ")) {
            val result = preflight(write(), mapOf("GITHUB_TOKEN" to value))

            assertEquals(
                PreflightResult.Failed("missing_tracker_secret", "tracker.provider.token uses \$GITHUB_TOKEN, which is empty"),
                result,
            )
        }
    }

    @Test
    fun `bl-22 a token with whitespace or a control character inside is refused at load without showing it`() {
        for (value in listOf("ghp_bl23 inside", "ghp_bl23\tinside", "ghp_bl23\u0007inside")) {
            val result = preflight(write(), mapOf("GITHUB_TOKEN" to value))

            val failed = assertIs<PreflightResult.Failed>(result)
            assertEquals("invalid_tracker_config", failed.code)
            assertEquals("tracker.provider.token uses \$GITHUB_TOKEN, whose value holds whitespace or a control character", failed.message)
            assertFalse("ghp_bl23" in failed.message, failed.message)
            assertFalse("inside" in failed.message, failed.message)
        }
    }

    @Test
    fun `a github config error keeps its category in preflight`() {
        val result = preflight(write(provider = "    repo: not-a-repo\n    token: \$GITHUB_TOKEN\n"), mapOf("GITHUB_TOKEN" to token))

        assertEquals(
            PreflightResult.Failed("invalid_tracker_config", "tracker.provider.repo must be owner/name, got \"not-a-repo\""),
            result,
        )
    }

    @Test
    fun `preflight checks the configured states against the github tracker's own`() {
        val result = preflight(write(states = "  active_states: [todo]\n"), mapOf("GITHUB_TOKEN" to token))

        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "tracker.active_states has \"todo\", which is not a state of the github tracker. Valid states: open, closed, review, needs_info",
            ),
            result,
        )
        assertEquals(
            "tracker.terminal_states has \"done\", which is not a state of the github tracker. Valid states: open, closed, review, needs_info",
            trackerProblem(load(write(states = "  terminal_states: [done]\n")).getOrThrow()),
        )
    }

    @Test
    fun `bl-46 a handoff state in active_states or terminal_states fails preflight, it matches no issue`() {
        val cases =
            listOf(
                "  active_states: [open, review]\n" to "tracker.active_states has \"review\"",
                "  active_states: [open, needs_info]\n" to "tracker.active_states has \"needs_info\"",
                "  terminal_states: [closed, needs_info]\n" to "tracker.terminal_states has \"needs_info\"",
                "  terminal_states: [closed, review]\n" to "tracker.terminal_states has \"review\"",
            )
        for ((states, start) in cases) {
            assertEquals(
                PreflightResult.Failed(
                    "invalid_config",
                    "$start, a handoff state of the github tracker. A handoff state matches no issue, and a move to it " +
                        "would not stop the worker, so list only open and closed there",
                ),
                preflight(write(states = states), mapOf("GITHUB_TOKEN" to token)),
            )
        }
    }

    @Test
    fun `bl-46 preflight accepts open and closed as the only configured states`() {
        val path = write(states = "  active_states: [open]\n  terminal_states: [closed]\n")

        assertIs<PreflightResult.Ok>(preflight(path, mapOf("GITHUB_TOKEN" to token)))
        assertNull(trackerProblem(load(path).getOrThrow()))
    }

    @Test
    fun `bl-46 an empty required_labels fails preflight, a handoff move would always be refused`() {
        val expected =
            PreflightResult.Failed(
                "invalid_config",
                "tracker.required_labels is empty, so every move to review or needs_info would be refused: " +
                    "the move removes the required labels to take the issue out of the active set. " +
                    "Add the label that routes an issue to Loop, for example ready-for-agent",
            )
        for (labels in listOf("", "  required_labels: []\n", "  required_labels: [\"  \"]\n")) {
            assertEquals(expected, preflight(write(requiredLabels = labels), mapOf("GITHUB_TOKEN" to token)), labels)
        }
    }

    @Test
    fun `m2-23 handoff_labels reaches the settings and the tool config snapshot, which has the four states`() {
        val provider = "    repo: o/r\n    token: \$GITHUB_TOKEN\n    handoff_labels:\n      review: in-review\n"
        val workflow = load(write(provider = provider)).getOrThrow()
        val target = root.resolve("tool-config.json")

        ToolConfigSnapshotWriter.write(target, ProjectId("p1"), workflow, GitHubTracker.DEFINED_STATES)

        val settings = assertIs<GitHubTrackerSettings>(workflow.trackerSettings)
        assertEquals(mapOf("review" to "in-review", "needs_info" to "needs-info"), settings.handoffLabels)
        val json = Json.parseToJsonElement(target.readText()).jsonObject
        assertEquals(
            listOf("open", "closed", "review", "needs_info"),
            (json["defined_states"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(JsonObject(mapOf("review" to JsonPrimitive("in-review"))), json["provider"]?.jsonObject?.get("handoff_labels"))
        assertFalse(token in target.readText())
    }

    @Test
    fun `m2-23 a handoff label that is also a required label fails preflight, the move would remove it`() {
        val provider = "    repo: o/r\n    token: \$GITHUB_TOKEN\n    handoff_labels:\n      needs_info: Ready-For-Agent\n"

        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "tracker.provider.handoff_labels.needs_info is \"Ready-For-Agent\", which is also in tracker.required_labels, " +
                    "so a move to needs_info would remove it",
            ),
            preflight(write(provider = provider), mapOf("GITHUB_TOKEN" to token)),
        )
    }

    @Test
    fun `m2-23 trackerFor gives the github tracker the required labels of the workflow, or the ones it is passed`() {
        val workflow =
            load(
                write(provider = "    repo: o/r\n    token: \$GITHUB_TOKEN\n    api_url: http://127.0.0.1:9/api\n"),
            ).getOrThrow()
        val noLabels = workflow.copy(requiredLabels = emptyList())

        val refused = runBlocking { assertIs<GitHubTracker>(trackerFor(noLabels, root)).moveIssue("1", "review") }
        val passed =
            runBlocking {
                assertIs<GitHubTracker>(
                    trackerFor(noLabels, root, requiredLabels = { listOf("ready-for-agent") }),
                ).moveIssue("1", "review")
            }
        // With the workflow's ready-for-agent the move goes to GitHub, here a closed port, so it fails as unavailable.
        val sent = runBlocking { assertIs<GitHubTracker>(trackerFor(workflow, root)).moveIssue("1", "review") }

        assertIs<TrackerError.Rejected>(refused.exceptionOrNull())
        assertIs<TrackerError.Unavailable>(passed.exceptionOrNull())
        assertIs<TrackerError.Unavailable>(sent.exceptionOrNull())
    }

    @Test
    fun `an unknown tracker kind names files and github`() {
        val path = write().also { it.writeText(it.readText().replace("kind: github", "kind: jira")) }

        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"jira\" is not supported, use files or github"),
            preflight(path, mapOf("GITHUB_TOKEN" to token)),
        )
    }

    @Test
    fun `trackerFor builds the github tracker with the loaded settings`() {
        val workflow =
            load(write(provider = "    repo: o/r\n    token: \$GITHUB_TOKEN\n    api_url: http://127.0.0.1:9/api\n")).getOrThrow()

        val tracker = assertIs<GitHubTracker>(trackerFor(workflow, root))

        assertEquals(listOf("open", "closed", "review", "needs_info"), tracker.definedStates)
        assertNull(trackerProblem(workflow))
        assertEquals(URI("http://127.0.0.1:9/api"), (workflow.trackerSettings as GitHubTrackerSettings).apiUrl)
    }

    @Test
    fun `the token variable is kept out of the agent env`() {
        val workflow = load(write()).getOrThrow()

        val removed = agentEnvRemovedNames(workflow)
        assertTrue("GITHUB_TOKEN" in removed, "$removed")
        val agentEnv = agentEnvironment(removed = removed, host = mapOf("GITHUB_TOKEN" to token, "PATH" to "/usr/bin"))
        assertEquals(mapOf("PATH" to "/usr/bin"), agentEnv)
    }

    @Test
    fun `the token is redacted and absent from the tool config snapshot`() {
        val workflow = load(write(provider = "    repo: o/r\n    token: \$GITHUB_TOKEN\n    labels: [loop]\n")).getOrThrow()
        val target = root.resolve("tool-config.json")

        ToolConfigSnapshotWriter.write(target, ProjectId("p1"), workflow, GitHubTracker.DEFINED_STATES)

        val text = target.readText()
        assertFalse(token in text, "the token value leaked: $text")
        assertFalse("GITHUB_TOKEN" in text, "the token name leaked: $text")
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("github"), json["tracker_kind"])
        assertEquals(JsonArray(GitHubTracker.DEFINED_STATES.map(::JsonPrimitive)), json["defined_states"])
        assertEquals(
            JsonObject(mapOf("repo" to JsonPrimitive("o/r"), "labels" to JsonArray(listOf(JsonPrimitive("loop"))))),
            json["provider"],
        )
        assertEquals(mapOf("GITHUB_TOKEN" to token), secretValues(workflow, mapOf("GITHUB_TOKEN" to token, "OTHER" to "x".repeat(20))))
    }

    @Test
    fun `a reload that changes the github settings warns that the running tracker keeps the old ones`() {
        val previous = load(write()).getOrThrow()
        val reloaded = load(write(provider = "    repo: Soules-Studio-Ltd/example-app\n    token: \$GITHUB_TOKEN\n")).getOrThrow()

        assertEquals(
            listOf(
                "tracker.provider changed to Soules-Studio-Ltd/example-app, Loop keeps reading Soules-Studio-Ltd/loop-playground until loop run is restarted",
            ),
            reloadWarnings(previous, reloaded, mapOf("GITHUB_TOKEN" to token)),
        )
        assertEquals(emptyList(), reloadWarnings(previous, load(write()).getOrThrow(), mapOf("GITHUB_TOKEN" to token)))
    }

    @Test
    fun `a reload that changes the tracker kind warns that the running tracker keeps the old one`() {
        val github = load(write()).getOrThrow()
        val files =
            load(write().also { it.writeText(it.readText().replace("  kind: github\n  provider:\n", "  kind: files\n  provider:\n")) })
                .getOrThrow()

        assertEquals(
            listOf(
                "tracker.kind changed to files, Loop keeps reading the github tracker of Soules-Studio-Ltd/loop-playground until loop run is restarted",
                // m3-05: the github keys left under tracker.provider are unknown to the files tracker.
                "tracker.provider.repo is not a key of the files tracker, Loop ignores it",
                "tracker.provider.token is not a key of the files tracker, Loop ignores it",
            ),
            reloadWarnings(github, files, mapOf("GITHUB_TOKEN" to token)),
        )
        assertEquals(
            listOf("tracker.kind changed to github, Loop keeps reading the tracker at ${files.trackerDir} until loop run is restarted"),
            reloadWarnings(files, github, mapOf("GITHUB_TOKEN" to token)),
        )
    }
}
