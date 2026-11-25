package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.core.trackers.Followups
import loop.mcp.LoopMcpServer
import loop.tracker.files.FilesTracker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-33, story 130: `file_followup` end to end. The agents are scripts that speak JSON-RPC to the real `loop tool mcp`
 * over stdio, as in [ToolMcpCommandTest], so the call runs without a model. The follow up is queued and answered
 * `queued`; when the turn ends Loop files it labelled `needs-triage` in the parent's tracker, or the one the parent's
 * kind names, records `followup_filed` with its id and comments that id on the parent. Past
 * [Followups.MAX_PER_ATTEMPT] in an attempt the call is a structured failure and the turn goes on.
 */
class FileFollowupTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** The tool and the reply of each call the agent made, one line each. */
    private val transcript: Path by lazy { root.resolve("transcript.txt") }

    private val followup = """{"title":"Archived items are dropped","body":"The export skips archived items in src/export.kt."}"""

    /**
     * Writes the agent: [body] runs with a `loop tool mcp` session open on stdio, through `call TOOL ARGUMENTS`, which
     * waits for the reply and appends the tool and the reply to [transcript]; `finish` closes the session.
     */
    private fun agent(body: String) {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "${D}LOOP_ATTEMPT_ID" >> '${fixture.agentCallsFile}'
            |replies=$D(mktemp)
            |rpc=$D(mktemp -d)
            |mkfifo "${D}rpc/in"
            |loop tool mcp < "${D}rpc/in" > "${D}replies" &
            |exec 3> "${D}rpc/in"
            |printf '%s\n' '$INITIALIZE' '$INITIALIZED' >&3
            |id=1
            |call() {
            |  id=$D((id + 1))
            |  printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}\n' "${D}id" "${D}1" "${D}2" >&3
            |  n=0
            |  until grep -Eq "\"id\":${D}id[,}]" "${D}replies"; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 9; sleep 0.1; done
            |  echo "${D}1 $D(grep -E "\"id\":${D}id[,}]" "${D}replies")" >> '$transcript'
            |}
            |finish() { exec 3>&-; wait; }
            |$body
            |
            """.trimMargin(),
        )
    }

    private fun runOnce(env: Map<String, String?> = emptyMap()): LoopResult =
        fixture.loop("run", fixture.workflow.toString(), "--once", env = env).also {
            assertEquals(0, it.exitCode, "$it\n${transcriptText()}")
        }

    private fun transcriptText(): String = if (transcript.exists()) transcript.readText() else "(no transcript)"

    /** The reply of each call of [tool], in order: its text parsed as JSON when it is, and its `isError`. */
    private fun answers(tool: String): List<JsonObject> =
        transcript.readLines().filter { it.startsWith("$tool ") }.map { line ->
            val result =
                Json
                    .parseToJsonElement(line.removePrefix("$tool "))
                    .jsonObject
                    .getValue("result")
                    .jsonObject
            val text =
                (result.getValue("content") as JsonArray)
                    .first()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            val answer =
                runCatching { Json.parseToJsonElement(text).jsonObject }
                    .getOrElse { JsonObject(mapOf("text" to JsonPrimitive(text))) }
            JsonObject(answer + ("isError" to (result["isError"] ?: JsonPrimitive(false))))
        }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun events(kind: String): List<JsonObject> =
        fixture
            .query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq")
            .map { Json.parseToJsonElement(it["data"].orEmpty()).jsonObject }

    private fun attemptIds(): List<String> = fixture.agentCallsFile.readLines().filter { it.isNotBlank() }

    /** The `bl-<nn>` issue files the files tracker created, by name. */
    private fun followupFiles(): List<Path> = fixture.issuesDir.listDirectoryEntries("bl-*.md").sortedBy { it.name }

    private fun comments(id: String): String = fixture.issueFile(id).readText().substringAfter("## Comments")

    @Test
    fun `a file_followup over loop tool mcp is queued, then filed needs-triage in the parent's files tracker and named`() {
        fixture.writeWorkflow(activeStates = listOf("todo", "in_progress"))
        fixture.issue("t-1")
        agent(
            """
            |call file_followup '$followup'
            |call move '{"state":"review"}'
            |finish
            """.trimMargin(),
        )

        runOnce()

        val answer = answers("file_followup").single()
        assertEquals("queued", answer.text("status"), answer.toString())
        assertEquals("false", answer.text("isError"))
        assertContains(answer.text("message").orEmpty(), "When this turn ends, Loop files it labelled needs-triage")

        // Without a prefix setting the files tracker writes bl-<nn>.
        val file = followupFiles().single()
        assertEquals("bl-01-archived-items-are-dropped.md", file.name)
        val parsed =
            runBlocking { FilesTracker(fixture.issuesDir, root.resolve("locks")).fetchIssuesByIds(listOf("bl-01")) }
                .getOrThrow()
                .single()
        assertEquals("Archived items are dropped", parsed.title)
        assertEquals("todo", parsed.state)
        assertEquals(listOf("needs-triage"), parsed.labels)
        assertEquals(emptyList(), parsed.blockedBy)
        val attempt = attemptIds().single()
        val lastLine =
            parsed.description
                .orEmpty()
                .trimEnd()
                .lines()
                .last()
        assertEquals("Follow up of t-1 (default:t-1), filed by Loop from attempt $attempt.", lastLine)
        assertContains(parsed.description.orEmpty(), "The export skips archived items in src/export.kt.")

        val filed = events("followup_filed").single()
        assertEquals("default:bl-01", filed.text("followup"))
        assertEquals("default:t-1", filed.text("parent"))
        assertEquals("default", filed.text("tracker"))
        assertContains(comments("t-1"), "Filed the follow up default:bl-01 in the tracker default: Archived items are dropped")
        assertEquals(1, events("followup_requested").size)
        assertEquals("review", fixture.stateOf("t-1"))
    }

    @Test
    fun `with kinds kind followup tracker set the follow up goes to that tracker, here GitHub on recorded responses`() {
        RecordedGitHubIssue().use { github ->
            fixture.writeWorkflow(
                activeStates = listOf("todo", "in_progress"),
                extra =
                    """
                    |trackers:
                    |  - id: gh
                    |    kind: github
                    |    provider:
                    |      repo: octocat/Hello-World
                    |      token: ${D}GITHUB_TOKEN
                    |      api_url: ${github.apiUrl}
                    |    required_labels: [never-on-26]
                    |agent:
                    |  max_turns: 1
                    |kinds:
                    |  code:
                    |    followup: { tracker: gh }
                    |    verify:
                    |      checks:
                    |        - { type: command, run: "test -f never.txt" }
                    """.trimMargin(),
            )
            fixture.issue("t-1")
            agent(
                """
                |call file_followup '$followup'
                |finish
                """.trimMargin(),
            )

            runOnce(env = mapOf("GITHUB_TOKEN" to "ghp_" + "a".repeat(36)))

            assertEquals("queued", answers("file_followup").single().text("status"), transcriptText())
            val create = github.writes.single { it.method == "POST" && it.path.endsWith("/issues") }
            val sent = Json.parseToJsonElement(create.body).jsonObject
            assertEquals("Archived items are dropped", sent.text("title"))
            assertEquals(listOf("needs-triage"), sent.getValue("labels").jsonArray.map { it.jsonPrimitive.content })
            val attempt = attemptIds().single()
            assertTrue(
                sent.text("body").orEmpty().endsWith("Follow up of t-1 (default:t-1), filed by Loop from attempt $attempt."),
                create.body,
            )
            assertEquals(emptyList(), followupFiles(), "nothing is filed in the parent's files tracker")
            val filed = events("followup_filed").single()
            assertEquals("gh:27", filed.text("followup"))
            assertEquals("gh", filed.text("tracker"))
            assertContains(comments("t-1"), "Filed the follow up gh:27 in the tracker gh: Archived items are dropped")
        }
    }

    @Test
    fun `preflight refuses a kinds kind followup tracker that is not in the registry`() {
        fixture.writeWorkflow(
            extra =
                """
                |kinds:
                |  code:
                |    followup: { tracker: gh }
                |    verify:
                |      checks: []
                """.trimMargin(),
        )
        fixture.issue("t-1")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertTrue(run.exitCode != 0, run.toString())
        assertContains(run.stderr, "kinds.code.followup.tracker \"gh\" is not a tracker id, use one of default")
        assertEquals(0, fixture.agentCalls())
    }

    @Test
    fun `past the limit per attempt file_followup is a structured failure and the turn goes on`() {
        fixture.writeWorkflow(activeStates = listOf("todo", "in_progress"))
        fixture.issue("t-1")
        agent(
            (1..4).joinToString("\n") { """call file_followup '{"title":"Follow up $it","body":"Found $it."}'""" } +
                "\ncall comment '{\"text\":\"Still going after the limit.\"}'\ncall move '{\"state\":\"review\"}'\nfinish",
        )

        runOnce()

        val answers = answers("file_followup")
        assertEquals(listOf("queued", "queued", "queued", "error"), answers.map { it.text("status") }, answers.toString())
        val refused = answers.last()
        assertEquals("too_many_followups", refused.text("error"))
        assertEquals("true", refused.text("isError"))
        assertContains(refused.text("message").orEmpty(), "at most 3 per attempt")
        assertContains(refused.text("message").orEmpty(), "Nothing queued.")
        assertEquals("false", answers("comment").single().text("isError"), "the turn goes on after the refusal")
        assertEquals(listOf("bl-01", "bl-02", "bl-03"), followupFiles().map { it.name.take(5) })
        assertEquals(3, events("followup_filed").size)
        assertContains(comments("t-1"), "Still going after the limit.")
        assertEquals("review", fixture.stateOf("t-1"))
    }

    @Test
    fun `the limit the tool descriptions name is the one the core applies`() {
        assertEquals(Followups.MAX_PER_ATTEMPT, LoopMcpServer.FOLLOWUPS_PER_ATTEMPT)
        assertContains(LoopMcpServer.FILE_FOLLOWUP_DESCRIPTION, "at most ${Followups.MAX_PER_ATTEMPT} per attempt")
    }

    private companion object {
        const val D = "$"

        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
        const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    }
}
