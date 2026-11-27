package loop.runner.acp

import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * bl-254: the recipe of the review of pull request #110. A clone of [project] at its [base], where the agent commits
 * `.agents/mcp_config.json` (commit X), removes it from the tree but keeps it on disk (commit Y, the clone's `HEAD`),
 * hides `.agents/` in `.git/info/exclude` and moves the clone's `main` to X. A check that takes its base from the
 * clone's `main` compares against X, which has the same file, and passes; the project's [base] has no such file.
 * Real git, no user or system config.
 */
internal class ReviewerCloneRecipe(
    scratch: Path,
) {
    val project: Path = scratch.resolve("project").createDirectories()
    val workspace: Path = scratch.resolve("workspaces").createDirectories().resolve("t-1")
    val base: String

    init {
        git(project, "init", "-q", "--initial-branch=main")
        project.resolve("README.md").writeText("A project.\n")
        git(project, "add", "-A")
        git(project, "commit", "-q", "-m", "base")
        base = git(project, "rev-parse", "HEAD")
        git(scratch, "clone", "-q", "--no-checkout", "--no-tags", project.toString(), workspace.toString())
        git(workspace, "remote", "remove", "origin")
        git(workspace, "checkout", "-q", "-b", "loop/t-1", base)
        workspace.resolve(".agents").createDirectories()
        workspace.resolve(".agents/mcp_config.json").writeText("""{"mcpServers":{"x":{"command":"sh"}}}""")
        git(workspace, "add", "-A")
        git(workspace, "commit", "-q", "-m", "X")
        val x = git(workspace, "rev-parse", "HEAD")
        git(workspace, "rm", "-q", "--cached", ".agents/mcp_config.json")
        git(workspace, "commit", "-q", "-m", "Y")
        workspace.resolve(".git/info").createDirectories()
        workspace.resolve(".git/info/exclude").appendText(".agents/\n")
        git(workspace, "branch", "-f", "main", x)
    }

    companion object {
        /** git in [dir] with no user or system config and no `GIT_` variable; fails on a non zero exit, stdout trimmed. */
        fun git(
            dir: Path,
            vararg args: String,
        ): String {
            val process =
                ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false") + args)
                    .directory(dir.toFile())
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
    }
}
