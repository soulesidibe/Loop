package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
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
    }
}
