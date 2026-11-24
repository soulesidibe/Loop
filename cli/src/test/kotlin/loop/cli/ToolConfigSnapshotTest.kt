package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.ProjectId
import loop.core.orchestrator.ToolConfigWriter
import loop.core.workflow.WorkflowLoader
import loop.tracker.github.GitHubTracker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** m1-23: the tool config snapshot `loop tool` reads instead of WORKFLOW.md. */
class ToolConfigSnapshotTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `the snapshot omits a credential provider field and resolves the dir`() {
        val token = "tracker-secret-value-123"
        val env =
            mapOf(
                "LOOP_TEST_ISSUES" to "issues-folder",
                "LOOP_TEST_TRACKER_TOKEN" to token,
            )
        val file = root.resolve("WORKFLOW.md")
        file.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: files\n" +
                "  provider:\n" +
                "    dir: \$LOOP_TEST_ISSUES\n" +
                "    token: \$LOOP_TEST_TRACKER_TOKEN\n" +
                "    team: team-a\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: agent.sh\n" +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
        val workflow = WorkflowLoader(env = env, home = root).load(file).getOrThrow()
        val target = root.resolve("tool-config.json")
        val writer: ToolConfigWriter = ToolConfigSnapshotWriter

        writer.write(target, ProjectId("p1"), workflow, listOf("todo", "in_progress", "review", "done"))

        val text = target.readText()
        assertFalse(token in text, "the token value leaked: $text")
        assertFalse("LOOP_TEST_TRACKER_TOKEN" in text, "the token name leaked: $text")
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertEquals("p1", json.getValue("project_id").jsonPrimitive.content)
        assertEquals(workflow.path.parent.toString(), json.getValue("workflow_dir").jsonPrimitive.content)
        assertEquals("files", json.getValue("tracker_kind").jsonPrimitive.content)
        assertEquals(
            listOf("todo", "in_progress", "review", "done"),
            json.getValue("defined_states").jsonArray.map { it.jsonPrimitive.content },
        )
        // m3-14: what `loop tool mcp` trims its replies to, the default here.
        assertEquals(2000, json.getValue("tool_output_tokens").jsonPrimitive.int)
        assertEquals(2000, readToolConfig(target).getOrThrow().toolOutputTokens)
        // A snapshot written before m3-14, without the key, reads with the default.
        target.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(json - "tool_output_tokens")))
        assertEquals(2000, readToolConfig(target).getOrThrow().toolOutputTokens)
        assertEquals(
            JsonObject(
                mapOf(
                    "dir" to JsonPrimitive(workflow.trackerDir.toString()),
                    "team" to JsonPrimitive("team-a"),
                ),
            ),
            json.getValue("provider"),
        )
    }

    @Test
    fun `m4-14 the snapshot of an extra tracker's issue holds that tracker's kind, states and provider, the primary's id`() {
        val file = root.resolve("WORKFLOW.md")
        file.writeText(
            "---\n" +
                "tracker:\n" +
                "  id: local\n" +
                "  kind: files\n" +
                "trackers:\n" +
                "  - id: gh\n" +
                "    kind: github\n" +
                "    provider:\n" +
                "      repo: octocat/Hello-World\n" +
                "      token: \$LOOP_TEST_GITHUB_TOKEN\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: agent.sh\n" +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
        val token = "ghp_m414SnapshotToken0123456789abcdefgh"
        val workflow = workflowLoader(mapOf("LOOP_TEST_GITHUB_TOKEN" to token), root).load(file).getOrThrow()
        val target = root.resolve("tool-config.json")
        val gh = checkNotNull(workflow.trackers.entry("gh"))

        ToolConfigSnapshotWriter.write(target, ProjectId("p1"), workflow, gh, GitHubTracker.DEFINED_STATES)

        val text = target.readText()
        assertFalse(token in text || "LOOP_TEST_GITHUB_TOKEN" in text, "the token leaked: $text")
        val config = readToolConfig(target).getOrThrow()
        assertEquals("github", config.trackerKind)
        assertEquals(GitHubTracker.DEFINED_STATES, config.definedStates)
        assertEquals("local", config.trackerId, "the primary's id, which bare ids are read by")
        assertEquals(listOf("local", "gh"), config.trackerIds)
        assertEquals(
            JsonObject(mapOf("repo" to JsonPrimitive("octocat/Hello-World"))),
            Json.parseToJsonElement(text).jsonObject.getValue("provider"),
        )
    }
}
