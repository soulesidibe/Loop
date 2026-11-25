package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * m4-21, end to end with the files tracker, git worktrees and the command runner as the fake agent: an issue labelled
 * `content` is dispatched under the `content` kind, whose deliverable is the markdown files under `out`. The workflow's
 * `command` checks, the banned dash example of the docs among them, run in the issue's worktree, the matching files are
 * recorded with the verdict, and the result waits for approval under the default `human` policy.
 */
class ContentKindRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val post = "# Post\n\nA plain line, with commas, and periods.\n"

    @Test
    fun `a content issue runs under content, its deliverable files are recorded with the verdict, its checks run in the worktree`() {
        fixture.issue("c-1", labels = listOf("ready-for-agent", "content"))
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |mkdir -p out
            |printf '${post.replace("\n", "\\n")}' > out/post.md
            |echo "not a deliverable" > notes.txt
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |  content:
                |    match: { label: content }
                |    deliverable: { files: [out/**/*.md] }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f .git" }
                |        - type: command
                |          run: |
                |            ! grep -rnE "${'$'}(printf '\342\200\224|\342\200\223')" out/
                """.trimMargin(),
        )
        fixture.useGitWorktrees()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("content", fixture.query("SELECT kind FROM attempts").single()["kind"], result.toString())
        assertTrue(fixture.workspace("c-1").resolve(".git").isRegularFile(), "the workspace is a git worktree")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"verified\"")
        assertContains(verdict, "\"checks\":\"2\"")
        assertContains(verdict, "\"deliverable\":\"1\"")
        val name = assertNotNull(Regex("\"deliverable_evidence\":\"([^\"]+)\"").find(verdict), verdict).groupValues[1]
        val evidence = Files.walk(fixture.loopHome.resolve("attempts")).use { files -> files.filter { it.name == name }.toList() }
        val listed = evidence.single().readText()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(post.toByteArray()).joinToString("") { "%02x".format(it) }
        assertContains(listed, "deliverable: 1")
        assertContains(listed, "- out/post.md, ${post.toByteArray().size} bytes, sha256 $sha256")
        // The default human policy: the verified result waits for loop approve, the issue still in its state.
        assertEquals("approval", fixture.query("SELECT reason FROM waiting_issues").single()["reason"])
        assertEquals("todo", fixture.stateOf("c-1"))
    }
}
