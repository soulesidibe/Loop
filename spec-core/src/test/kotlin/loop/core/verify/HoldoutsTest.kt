package loop.core.verify

import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** m2-14: the preflight rule that keeps the holdout folder out of every workspace and out of the repository. */
class HoldoutsTest {
    @TempDir
    lateinit var tmp: Path

    private val root: Path by lazy { tmp.toRealPath() }
    private val repo: Path by lazy { root.resolve("repo").createDirectories() }
    private val loopHome: Path by lazy { root.resolve("loop-home") }

    /** A workflow in [folder] with [workspaceRoot], and one holdout when [holdout] is true. */
    private fun workflow(
        folder: Path = repo,
        workspaceRoot: String = root.resolve("workspaces").toString(),
        holdout: Boolean = true,
        run: String = "\$LOOP_HOLDOUT/acceptance.sh",
    ): WorkflowFile {
        val holdouts = if (holdout) "[{ type: command, run: \"$run\" }]" else "[]"
        val file =
            folder.createDirectories().resolve("WORKFLOW.md").also {
                it.writeText(
                    """
                    |---
                    |tracker:
                    |  kind: files
                    |workspace:
                    |  root: $workspaceRoot
                    |runner:
                    |  kind: command
                    |runners:
                    |  command:
                    |    command: ./agent.sh
                    |kinds:
                    |  code:
                    |    verify:
                    |      checks: []
                    |      holdout: $holdouts
                    |---
                    |Prompt
                    """.trimMargin(),
                )
            }
        return WorkflowLoader(env = emptyMap()).load(file).getOrThrow()
    }

    private fun existing(dir: Path): Path = dir.createDirectories()

    @Test
    fun `the holdout folder is the holdout folder of LOOP_HOME`() {
        assertEquals(loopHome.resolve("holdout"), Holdouts.dir(loopHome))
    }

    @Test
    fun `a holdout folder outside the workspaces and the repository passes`() {
        assertNull(Holdouts.problem(workflow(), existing(Holdouts.dir(loopHome))))
    }

    @Test
    fun `a holdout folder inside the repository fails`() {
        val dir = existing(repo.resolve(".loop/holdout"))

        val problem = assertNotNull(Holdouts.problem(workflow(), dir))

        assertEquals(
            "kinds.code.verify.holdout: the holdout folder $dir is inside the repository $repo, where the agent can read it. " +
                "Set LOOP_HOME outside it",
            problem,
        )
    }

    @Test
    fun `the repository is the git top level, so a holdout folder beside a WORKFLOW_md in a subfolder fails`() {
        repo.resolve(".git").createDirectories()
        val dir = existing(repo.resolve("tools/holdout"))

        val problem = assertNotNull(Holdouts.problem(workflow(folder = repo.resolve("project")), dir))

        assertContains(problem, "is inside the repository $repo,")
    }

    @Test
    fun `a holdout folder inside workspace root fails`() {
        val workspaces = root.resolve("workspaces")
        val dir = existing(workspaces.resolve("holdout"))

        val problem = assertNotNull(Holdouts.problem(workflow(), dir))

        assertContains(problem, "the holdout folder $dir is inside workspace.root $workspaces, where the agent can read it")
    }

    @Test
    fun `a workspace root inside the holdout folder fails too`() {
        val dir = existing(Holdouts.dir(loopHome))
        val workspaces = dir.resolve("workspaces")

        val problem = assertNotNull(Holdouts.problem(workflow(workspaceRoot = workspaces.toString()), dir))

        assertContains(problem, "workspace.root $workspaces is inside the holdout folder $dir")
    }

    @Test
    fun `a holdout folder reached through a symlink is compared by its real path`() {
        val inside = existing(repo.resolve("hidden"))
        val link = Files.createSymbolicLink(root.resolve("holdout-link"), inside)

        val problem = assertNotNull(Holdouts.problem(workflow(), link))

        assertContains(problem, "is inside the repository $repo,")
    }

    @Test
    fun `a missing holdout folder fails, since every holdout would fail where the agent cannot fix it`() {
        val dir = Holdouts.dir(loopHome)

        assertEquals(
            "kinds.code.verify.holdout needs the holdout folder $dir, which does not exist",
            Holdouts.problem(workflow(), dir),
        )
    }

    @Test
    fun `without holdouts nothing is checked`() {
        assertNull(Holdouts.problem(workflow(holdout = false), repo.resolve("anything")))
    }

    @Test
    fun `a command holdout whose run does not reference the holdout folder fails, a script in the repository is not hidden`() {
        val dir = existing(Holdouts.dir(loopHome))

        assertEquals(
            "kinds.code.verify.holdout[0]: run must reference \$LOOP_HOLDOUT or a path in the holdout folder $dir, " +
                "where the agent cannot read the script",
            Holdouts.problem(workflow(run = "./scripts/acceptance.sh"), dir),
        )
        assertNotNull(Holdouts.problem(workflow(run = "sh ${root.resolve("elsewhere/acceptance.sh")}"), dir))
        assertNotNull(Holdouts.problem(workflow(run = "\$LOOP_HOLDOUTS/acceptance.sh"), dir))
    }

    @Test
    fun `a command holdout may reference the folder by LOOP_HOLDOUT in either spelling or by a path under it`() {
        val dir = existing(Holdouts.dir(loopHome))
        val link = Files.createSymbolicLink(root.resolve("holdout-link"), dir)

        assertNull(Holdouts.problem(workflow(run = "sh \${LOOP_HOLDOUT}/acceptance.sh"), dir))
        assertNull(Holdouts.problem(workflow(run = "$dir/project/acceptance.sh --strict"), dir))
        assertNull(Holdouts.problem(workflow(run = "'$link/acceptance.sh'"), dir))
    }
}
