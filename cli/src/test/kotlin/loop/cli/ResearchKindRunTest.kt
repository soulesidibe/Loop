package loop.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * m4-23, end to end with the files tracker and the command runner as the fake agent writing `report.md`: a research
 * kind, `workspace: plain`, `populate: empty` and `deliverable.report`, works in an empty folder Loop creates, with none
 * of the workflow's four workspace hooks run, and is fingerprinted `tree:`. Its report is checked for its required
 * sections and, by the example sources check of the docs, for reachable sources on a local test server; it is posted on
 * the issue as one comment by the attach delivery and waits as `Waiting(approval)`, and `loop approve` moves it.
 */
class ResearchKindRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val sourcesCheck: Path =
        Path
            .of(System.getProperty("loop.rootWorkflow") ?: fail("system property loop.rootWorkflow is not set"))
            .parent
            .resolve("docs/examples/research/check-sources.sh")

    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(if (exchange.requestURI.path == "/queues") 200 else 404, -1)
                exchange.close()
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private val report =
        "# Queues\n\n## Summary\n\nQueues hold.\n\n## Sources\n\n- http://127.0.0.1:${server.address.port}/queues\n"

    /** The folder the hooks would write a marker to, one file a hook, outside the project. */
    private val markers: Path by lazy { root.resolve("hook-markers").createDirectories() }

    /** The fake agent: lists what it found in its workspace, writes the report, then asks for the move to review. */
    private fun agent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |ls -A > "${root.resolve("found.txt")}"
            |printf '%s' '$report' > report.md
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun workflow() {
        val hook = { name: String -> "touch \"$markers/$name\"" }
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |hooks:
                |  after_create: ${hook("after_create")}
                |  before_run: ${hook("before_run")}
                |  after_run: ${hook("after_run")}
                |  before_remove: ${hook("before_remove")}
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |  research:
                |    match: { label: research }
                |    workspace: plain
                |    populate: empty
                |    deliverable: { report: report.md }
                |    delivery: { attach: true, on_delivered: { move_to: done } }
                |    verify:
                |      require_sections: [Summary, Sources]
                |      checks:
                |        - { type: command, run: "sh '$sourcesCheck' report.md", timeout_ms: 60000 }
                """.trimMargin(),
        )
    }

    private fun attemptId(): String = fixture.query("SELECT attempt_id FROM attempts").single()["attempt_id"].orEmpty()

    @Test
    fun `a research issue works in an empty workspace with no hook run, its report is posted and waits for approval, approve moves it`() {
        fixture.issue("r-1", labels = listOf("ready-for-agent", "research"))
        agent()
        workflow()

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("", root.resolve("found.txt").readText().trim(), "the workspace is empty when the agent starts")
        assertFalse(fixture.workspace("r-1").resolve(".git").exists(), "the workspace is not a git repository")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"verified\"")
        assertContains(verdict, "\"deliverable\":\"1\"")
        assertNotNull(Regex("\"fingerprint\":\"tree:[0-9a-f]{16}\"").find(verdict), verdict)
        val sourcesEvidence =
            Files.walk(fixture.loopHome.resolve("attempts")).use { files ->
                files.filter { it.fileName.toString().contains(".evidence-") }.toList()
            }
        assertTrue(sourcesEvidence.any { it.readText().contains("reachable: http://127.0.0.1:") }, sourcesEvidence.toString())

        assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"], run.toString())
        assertEquals("todo", fixture.stateOf("r-1"))
        val issueText = fixture.issueFile("r-1").readText()
        val expected =
            "Loop attached the deliverable of r-1, verified in attempt ${attemptId()}: 1 file.\n\n" +
                "`report.md`, ${report.length} bytes:\n\n```\n$report```"
        // The files tracker writes a comment's lines indented under its first line, blank lines left out.
        assertContains(issueText, "loop: " + expected.lines().filter { it.isNotBlank() }.joinToString("\n  "))
        assertEquals(1, issueText.split("Loop attached the deliverable").size - 1, "one report comment")

        val approve = fixture.loop("approve", "r-1", "--workflow", fixture.workflow.toString())

        assertEquals(0, approve.exitCode, approve.toString())
        assertContains(approve.stdout, "Loop moves it to done on its next tick.")

        val deliver = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, deliver.exitCode, deliver.toString())
        assertEquals("done", fixture.stateOf("r-1"), deliver.toString())
        assertEquals(emptyList(), fixture.query("SELECT reason FROM waiting_issues"))
        assertEquals(emptyList(), markers.listDirectoryEntries(), "no workspace hook ran")
        assertEquals(emptyList(), fixture.query("SELECT data FROM events WHERE kind = 'hook_finished'"))
    }
}
