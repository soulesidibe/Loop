package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * bl-28, end to end with the command runner: an agent that tries to move the operator's refs, write the shared config
 * and `info/exclude`, and push to `origin` from its workspace. With `workspace.git: clone` none of it reaches the
 * project; with today's worktree hooks the same script moves the project's `main`.
 */
class CloneWorkspaceCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the agent leaves what it saw, outside the project. */
    private val seen: Path by lazy { Files.createDirectories(root.resolve("seen")) }

    /**
     * Commits hello.txt, then tries every write of the issue on the operator's repository, each one allowed to fail,
     * keeping the push's exit code and output, then moves the issue to review.
     */
    private fun writeAgent() {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |git add hello.txt
            |git -c user.name="Loop agent" -c user.email=agent@example.com commit -q -m "work on ${'$'}LOOP_ISSUE_IDENTIFIER"
            |git rev-parse HEAD > "$seen/head.txt"
            |git update-ref refs/heads/main HEAD
            |git update-ref refs/heads/feature HEAD
            |git update-ref refs/tags/v1 HEAD
            |git -C . config core.hooksPath /tmp/x
            |exclude="${'$'}(git rev-parse --git-path info/exclude)"
            |mkdir -p "${'$'}(dirname "${'$'}exclude")"
            |echo "agent-was-here" >> "${'$'}exclude"
            |git push origin HEAD:refs/heads/other > "$seen/push.txt" 2>&1
            |echo ${'$'}? > "$seen/push-exit.txt"
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private val verify =
        """
        |agent:
        |  max_turns: 1
        |kinds:
        |  code:
        |    delivery: { policy: auto, auto_merge: { enabled: true } }
        |    verify:
        |      checks:
        |        - { type: command, run: "test -f hello.txt" }
        """.trimMargin()

    /** The project's refs as `git for-each-ref` prints them, one per line. */
    private fun refs(repo: TestRepo): List<String> = repo.git("for-each-ref").lines()

    @Test
    fun `in a clone workspace the agent moves no ref of the project, writes neither its config nor its exclude, and cannot push to origin`() {
        fixture.issue("t-1")
        writeAgent()
        fixture.writeWorkflow(extra = verify)
        fixture.workflow.writeText(fixture.workflow.readText().replace("  root: ./workspaces\n", "  root: ./workspaces\n  git: clone\n"))
        val repo = TestRepo(fixture.projectDir, fixture.homeDir).init(seed = false)
        repo.git("tag", "v1")
        repo.git("branch", "feature")
        repo.git("config", "user.name", "Operator")
        repo.git("config", "user.email", "operator@example.com")
        val excludeFile = fixture.projectDir.resolve(".git/info/exclude")
        val exclude = if (excludeFile.exists()) excludeFile.readText() else null
        val config = fixture.projectDir.resolve(".git/config").readText()
        val before = refs(repo)

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val head = seen.resolve("head.txt").readText().trim()
        val workspace = fixture.workspace("t-1")
        assertEquals(head, repo.git("rev-parse", "HEAD", cwd = workspace), "the agent's commit is the workspace's head")
        val written = listOf("$head commit\trefs/heads/loop/t-1")
        val after = refs(repo)
        assertEquals(
            before,
            after.filterNot { it in written || it.endsWith("\trefs/loop/workspace/t-1") },
            "the project's refs, byte for byte, apart from Loop's two",
        )
        assertContains(after, written.single(), "Loop wrote the loop branch at the verified commit")
        assertEquals(1, after.count { it.endsWith("\trefs/loop/workspace/t-1") }, "the keep ref")
        assertNotEquals("0", seen.resolve("push-exit.txt").readText().trim(), "the push failed")
        assertContains(seen.resolve("push.txt").readText(), "'origin' does not appear to be a git repository")
        assertEquals(config, fixture.projectDir.resolve(".git/config").readText(), "the project's config is unchanged")
        assertEquals(exclude, if (excludeFile.exists()) excludeFile.readText() else null, "the project's exclude is unchanged")
        // The agent's writes landed in its own clone.
        assertEquals(head, repo.git("rev-parse", "refs/heads/main", cwd = workspace))
        assertEquals("/tmp/x", repo.git("config", "--local", "core.hooksPath", cwd = workspace))
        assertEquals("Operator", repo.git("config", "--local", "user.name", cwd = workspace), "the identity is copied")
        assertFalse(workspace.resolve(".git").toFile().isFile, "a clone, not a worktree")
    }

    @Test
    fun `the same agent in a worktree workspace moves the project's main, so the clone is what protects it`() {
        fixture.issue("t-1")
        writeAgent()
        fixture.writeWorkflow(extra = verify)
        val repo = fixture.useGitWorktrees()
        val main = repo.git("rev-parse", "refs/heads/main")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        val head = seen.resolve("head.txt").readText().trim()
        assertNotEquals(main, head)
        assertEquals(head, repo.git("rev-parse", "refs/heads/main"), "the agent moved the operator's main")
        assertEquals(head, repo.git("rev-parse", "refs/heads/feature"))
        assertEquals(head, repo.git("rev-parse", "refs/tags/v1"))
        assertEquals("/tmp/x", repo.git("config", "--local", "core.hooksPath"), "the agent wrote the shared config")
        assertContains(fixture.projectDir.resolve(".git/info/exclude").readText(), "agent-was-here")
    }
    @Test
    fun `in a clone the diff_policy base is the project's, so moving main and the base ref in the clone does not hide a protected edit`() {
        fixture.issue("t-1")
        fixture.projectDir.resolve("guarded").toFile().mkdirs()
        fixture.projectDir.resolve("guarded/data.txt").writeText("keep me\n")
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo changed >> guarded/data.txt
            |git -c user.name=Agent -c user.email=agent@example.com commit -qam edit
            |git update-ref refs/heads/main HEAD
            |git update-ref refs/loop/base/main HEAD
            |loop tool move review
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |retries:
                |  max_attempts: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks: [{ type: diff_policy }]
                |      guards:
                |        protect: [guarded/]
                """.trimMargin(),
        )
        fixture.workflow.writeText(fixture.workflow.readText().replace("  root: ./workspaces\n", "  root: ./workspaces\n  git: clone\n"))
        val repo = TestRepo(fixture.projectDir, fixture.homeDir).init(seed = false)
        val main = repo.git("rev-parse", "refs/heads/main")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        val workspace = fixture.workspace("t-1")
        assertEquals(repo.git("rev-parse", "HEAD", cwd = workspace), repo.git("rev-parse", "refs/heads/main", cwd = workspace))
        assertEquals(main, repo.git("rev-parse", "refs/heads/main"), "the project's main did not move")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"refuted\"")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertContains(trace.stdout, "command=\"diff_policy base=$main protect=guarded/\"")
        assertEquals("todo", fixture.stateOf("t-1"))
    }
}
