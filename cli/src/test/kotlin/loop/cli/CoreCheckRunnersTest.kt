package loop.cli

import kotlinx.coroutines.runBlocking
import loop.core.workflow.CheckConfig
import loop.core.workflow.RunnerSettings
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkspaceGitMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * bl-254: the core check runner `loop run` and `loop replay` build from the workflow follows the clone rule under
 * `workspace.git: clone`: a clone workspace with no `.git` of its own is refused, its git never walking up to the
 * project. In `hooks` mode the same folder gets today's check. Real git, no user or system config.
 */
class CoreCheckRunnersTest {
    private val folder: Path = createTempDirectory("loop-core-check-runners").toRealPath()
    private val project: Path = Files.createDirectories(folder.resolve("project"))
    private val root: Path = project.resolve(".ws")
    private val workspace: Path = Files.createDirectories(root.resolve("t-1"))
    private val first: String

    init {
        git("init", "-q", "--initial-branch=main")
        project.resolve(".gitignore").writeText(".ws/\n")
        project.resolve("README.md").writeText("A project.\n")
        git("add", "-A")
        git("commit", "-q", "-m", "first")
        first = git("rev-parse", "HEAD")
    }

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false") + args)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private fun workflow(mode: WorkspaceGitMode): WorkflowFile =
        WorkflowFile(
            path = project.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = project.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = emptyList(),
            workspaceRoot = root,
            runner = RunnerSettings("command", mapOf("command" to mapOf("command" to "./agent.sh"))),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work.",
            workspaceGit = mode,
        )

    private val env = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to folder.toString())

    @Test
    fun `bl-254 in clone mode both git checks refuse a clone with no git of its own, in hooks mode they do not`() {
        val missing = "Loop runs no git in the clone $workspace: ${workspace.resolve(".git")} is missing"
        val clone = coreCheckRunnerOf(workflow(WorkspaceGitMode.Clone))
        val policy = runBlocking { clone.run(CheckConfig.DiffPolicy(listOf("scripts/"), base = first), workspace, env) }
        val coverage =
            runBlocking { clone.run(CheckConfig.ChangedLineCoverage(listOf("report.xml"), 100.0, base = first), workspace, env) }
        assertContains(policy.couldNotRun.orEmpty(), missing, message = policy.toString())
        assertContains(coverage.couldNotRun.orEmpty(), missing, message = coverage.toString())

        val hooks = coreCheckRunnerOf(workflow(WorkspaceGitMode.Hooks))
        val plain = runBlocking { hooks.run(CheckConfig.DiffPolicy(listOf("scripts/"), base = first), workspace, env) }
        assertFalse("Loop runs no git in the clone" in plain.couldNotRun.orEmpty(), plain.toString())
    }
}
