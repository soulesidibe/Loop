package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `loop run --once` on a temporary git repository whose WORKFLOW.md uses the
 * documented worktree hooks, in the documented `$(dirname "$LOOP_WORKFLOW")` form.
 */
class WorktreeDispatchTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `dispatch creates a worktree on the issue branch without the issues folder`() {
        fixture.issue("m1-04")
        val repo = fixture.useGitWorktrees(beforeRunExtra = "env | grep '^LOOP_' > hook-env.txt")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        val workspace = fixture.workspace("m1-04").toRealPath()
        assertTrue(workspace.toString() in repo.worktrees(), repo.worktrees().toString())
        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace))
        assertTrue(repo.hasBranch("loop/m1-04"))
        assertFalse(workspace.resolve(".loop/issues").exists())
        assertTrue(workspace.resolve("WORKFLOW.md").exists())
        assertTrue(workspace.resolve("hello.txt").exists(), "the agent ran in the worktree")
        assertEquals("review", fixture.stateOf("m1-04"))
    }

    @Test
    fun `hooks receive the issue variables`() {
        fixture.issue("m1-04")
        fixture.useGitWorktrees(beforeRunExtra = "env | grep '^LOOP_' > hook-env.txt")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        val written =
            fixture
                .workspace("m1-04")
                .resolve("hook-env.txt")
                .readLines()
                .associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("m1-04", written["LOOP_ISSUE_ID"])
        assertEquals("m1-04", written["LOOP_ISSUE_IDENTIFIER"])
        assertEquals("m1-04", written["LOOP_WORKSPACE_KEY"])
        // m4-12: the tracker id is its own variable, the issue id staying native.
        assertEquals("default", written["LOOP_TRACKER_ID"])
    }

    @Test
    fun `m4-12 an in flight primary workspace keeps its folder and branch, and after_create never runs again`() {
        fixture.issue("m1-04")
        val repo = fixture.useGitWorktrees(beforeRunExtra = "env | grep '^LOOP_' > hook-env.txt")
        // Counts every after_create run, after the hook made the worktree.
        val afterCreateLog = root.resolve("after-create.log")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace(
                "worktree-after-create.sh\"",
                "worktree-after-create.sh\" && echo ran >> \"$afterCreateLog\"",
            ),
        )
        // The workspace as Loop made it before m4-12: the hook ran with the key of the bare identifier, the branch
        // holds the work of the open pull request, and the store holds the attempt under the bare id.
        val workspace = fixture.workspace("m1-04").createDirectories()
        afterCreate(repo, workspace, key = "m1-04")
        workspace.resolve("in-flight.txt").writeText("work of the open pull request\n")
        repo.git("add", "in-flight.txt", cwd = workspace)
        repo.commit("in flight work", cwd = workspace)
        val inFlightHead = repo.git("rev-parse", "HEAD", cwd = workspace)
        fixture.seed(fixture.attemptStarted(EARLIER, "m1-04"), fixture.attemptFinished(EARLIER, "m1-04"))
        val worktreesBefore = repo.worktrees()

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(afterCreateLog.exists(), "after_create ran again")
        assertEquals(worktreesBefore, repo.worktrees(), "the same worktree, no other")
        assertEquals(listOf("loop/m1-04"), repo.git("branch", "--list", "loop/*", "--format=%(refname:short)").lines())
        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace))
        assertEquals(0, repo.gitResult("merge-base", "--is-ancestor", inFlightHead, "loop/m1-04").exitCode, "the branch kept its work")
        assertTrue(workspace.resolve("in-flight.txt").exists())
        assertEquals("review", fixture.stateOf("m1-04"))
        // The new attempt is named with its tracker, its workspace key unchanged; the earlier rows stay bare.
        val started = fixture.query("SELECT issue_id, attempt_id FROM events WHERE kind = 'attempt_started' ORDER BY seq")
        assertEquals(listOf("m1-04", "default:m1-04"), started.map { it["issue_id"] })
        val hookEnv = workspace.resolve("hook-env.txt").readLines().associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("m1-04", hookEnv["LOOP_WORKSPACE_KEY"])
    }

    /** Runs the documented after_create hook in [workspace] with the workspace key [key], as a `loop run` does. */
    private fun afterCreate(
        repo: TestRepo,
        workspace: Path,
        key: String,
    ) {
        val builder =
            ProcessBuilder("sh", fixture.projectDir.resolve("scripts/hooks/worktree-after-create.sh").toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true)
        builder.environment().keys.removeIf { it.startsWith("GIT_") || it.startsWith("LOOP_") }
        builder.environment().putAll(repo.env + mapOf("LOOP_WORKFLOW" to fixture.workflow.toString(), "LOOP_WORKSPACE_KEY" to key))
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "after_create did not exit")
        assertEquals(0, process.exitValue(), output)
    }

    private companion object {
        const val EARLIER = "00000000-0000-4000-8000-000000000412"
    }
}
