package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.core.delivery.AttachComment
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-22, end to end with the command runner as the fake agent: a content issue whose kind declares `deliverable.files`
 * and `delivery.attach: true` is verified, its deliverable posted on the issue as one comment through the tracker's
 * `addComment`, on the `files` tracker and on the GitHub tracker on recorded responses alike, and it waits as
 * `Waiting(approval)`; `loop approve` names `on_delivered.move_to` and the next run moves it there.
 */
class AttachDeliveryRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** A post that holds a code fence, so its own block needs a longer one. */
    private val post = "# Post\n\nA plain line.\n\n```sh\necho hi\n```\n"

    private val bigSize = 70_000

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The fake agent: writes the post, and with [big] a file over the cap, then asks for the move to review. */
    private fun agent(big: Boolean) {
        val bigLine = if (big) "|head -c $bigSize /dev/zero | tr '\\0' 'a' > out/big.md\n" else ""
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |mkdir -p out
            |printf '%s' '${post.replace("'", "'\\''")}' > out/post.md
            $bigLine|loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The content kind, matched by [match], on a plain empty workspace, delivered by attach. */
    private fun contentKind(match: String) =
        """
        |kinds:
        |  code:
        |    verify:
        |      checks:
        |        - { type: command, run: "true" }
        |  content:
        |    match: $match
        |    workspace: plain
        |    populate: empty
        |    deliverable: { files: [out/**/*.md] }
        |    delivery: { attach: true, on_delivered: { move_to: done } }
        |    verify:
        |      checks:
        |        - { type: command, run: "test -f out/post.md" }
        """.trimMargin()

    private fun attemptId(): String = fixture.query("SELECT attempt_id FROM attempts").single()["attempt_id"].orEmpty()

    @Test
    fun `on the files tracker the deliverable is one comment, the issue waits for approval, and approve moves it to on_delivered`() {
        fixture.issue("c-1", labels = listOf("ready-for-agent", "content"))
        agent(big = true)
        fixture.writeWorkflow(extra = "agent:\n  max_turns: 1\n" + contentKind("{ label: content }"))

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"], run.toString())
        assertEquals("todo", fixture.stateOf("c-1"))
        val issueText = fixture.issueFile("c-1").readText()
        val big = "a".repeat(bigSize).toByteArray()
        val expected =
            "Loop attached the deliverable of c-1, verified in attempt ${attemptId()}: 2 files.\n\n" +
                "`out/post.md`, ${post.length} bytes:\n\n````\n$post````\n\n" +
                "${AttachComment.LISTED_LEAD}\n" +
                "- `out/big.md`, $bigSize bytes, sha256 ${sha256(big)}, over the 60000 character cap of a comment"
        // The files tracker writes a comment's lines indented under its first line, blank lines left out.
        assertContains(issueText, "loop: " + expected.lines().filter { it.isNotBlank() }.joinToString("\n  "))
        assertEquals(1, issueText.split("Loop attached the deliverable").size - 1, "one deliverable comment")
        assertTrue(issueText.indexOf("Loop attached") < issueText.lastIndexOf("once an operator approves it"), "the evidence summary comes after it")
        // The file over the cap stays in the workspace until the issue is delivered.
        assertTrue(fixture.workspace("c-1").resolve("out/big.md").isRegularFile())
        val commented = fixture.query("SELECT data FROM events WHERE kind = 'issue_commented'").map { it["data"].orEmpty() }
        assertContains(commented.first(), "\"reason\":\"deliverable\"")

        val approve = fixture.loop("approve", "c-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, approve.exitCode, approve.toString())
        assertContains(approve.stdout, "Approved c-1, waiting since ")
        assertContains(approve.stdout, "Loop moves it to done on its next tick.")

        val deliver = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, deliver.exitCode, deliver.toString())
        assertEquals("done", fixture.stateOf("c-1"), deliver.toString())
        assertEquals(emptyList(), fixture.query("SELECT reason FROM waiting_issues"))
        assertEquals(1, fixture.issueFile("c-1").readText().split("Loop attached the deliverable").size - 1, "posted once")
    }

    @Test
    fun `on the GitHub tracker on recorded responses the same comment is posted through addComment`() {
        RecordedGitHubIssue().use { github ->
            agent(big = false)
            fixture.workflow.writeText(
                "---\n" +
                    "tracker:\n" +
                    "  id: local\n" +
                    "  kind: files\n" +
                    "  active_states: [todo]\n" +
                    "  required_labels: [ready-for-agent]\n" +
                    github.trackersYaml +
                    "workspace:\n" +
                    "  root: ./workspaces\n" +
                    "runner:\n" +
                    "  kind: command\n" +
                    "runners:\n" +
                    "  command:\n" +
                    "    command: \"${fixture.agentScript.toAbsolutePath()}\"\n" +
                    "agent:\n" +
                    "  max_turns: 1\n" +
                    contentKind("{ tracker: gh }") + "\n" +
                    "---\n" +
                    "Work on {{ issue.identifier }}\n",
            )

            val run = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("GITHUB_TOKEN" to GITHUB_TOKEN))

            assertEquals(0, run.exitCode, run.toString())
            assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"], run.toString())
            val comments = github.writes.filter { it.method == "POST" && it.path.endsWith("/issues/26/comments") }
            assertEquals(2, comments.size, github.writePaths.toString())
            assertEquals(github.writePaths.size, comments.size, "no label write: the issue waits, unmoved")
            val body =
                Json
                    .parseToJsonElement(comments.first().body)
                    .jsonObject
                    .getValue("body")
                    .jsonPrimitive
                    .content
            val identifier = fixture.query("SELECT identifier FROM waiting_issues").single()["identifier"]
            assertEquals(
                "Loop attached the deliverable of $identifier, verified in attempt ${attemptId()}: 1 file.\n\n" +
                    "`out/post.md`, ${post.length} bytes:\n\n````\n$post````",
                body,
            )
        }
    }

    private companion object {
        const val GITHUB_TOKEN = "ghp_m422AttachToken0123456789abcdefghijklmn"
    }
}
