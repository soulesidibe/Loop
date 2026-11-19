package loop.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.fail

/**
 * A temporary git repository for hook tests (m1-04 plan R11): branch `main` with
 * a `.loop/issues/` file, a tracked `README.md` and `workspaces/` ignored. Every
 * git command runs with [home] as `HOME` and no system config, so the real user
 * config is never read, and commits pass their identity with `-c`.
 */
class TestRepo(
    val dir: Path,
    private val home: Path,
) {
    /** The environment for git and for hooks: the temp home, no system or global config. */
    val env: Map<String, String> =
        mapOf(
            "HOME" to home.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to home.resolve(".gitconfig").toString(),
        )

    /**
     * Creates the repository with its first commit on `main`, holding what [dir]
     * already has; with [seed], an issue file and a `README.md` are written first.
     */
    fun init(seed: Boolean = true): TestRepo {
        dir.createDirectories()
        git("init", "--quiet", "--initial-branch=main")
        if (seed) {
            dir
                .resolve(".loop/issues")
                .createDirectories()
                .resolve("m1-04.md")
                .writeText("---\nid: m1-04\n---\n")
            dir.resolve("README.md").writeText("A project.\n")
        }
        dir.resolve(".gitignore").writeText("workspaces/\n")
        git("add", ".")
        commit("first commit")
        return this
    }

    fun commit(
        message: String,
        cwd: Path = dir,
    ) {
        git("-c", "user.name=Loop Test", "-c", "user.email=loop@example.com", "commit", "--quiet", "-m", message, cwd = cwd)
    }

    /** Runs git in [cwd]; fails the test on a non zero exit. Returns stdout, trimmed. */
    fun git(
        vararg args: String,
        cwd: Path = dir,
    ): String {
        val result = gitResult(*args, cwd = cwd)
        if (result.exitCode != 0) fail("git ${args.joinToString(" ")} exited ${result.exitCode}\n${result.stdout}\n${result.stderr}")
        return result.stdout.trim()
    }

    fun gitResult(
        vararg args: String,
        cwd: Path = dir,
    ): LoopResult {
        val builder = ProcessBuilder(listOf("git") + args).directory(cwd.toFile())
        builder.environment().keys.removeIf { it.startsWith("GIT_") }
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("git ${args.joinToString(" ")} did not exit in 30s")
        }
        return LoopResult(process.exitValue(), stdout, stderr)
    }

    /** True when the branch exists in the repository. */
    fun hasBranch(name: String): Boolean = gitResult("show-ref", "--verify", "--quiet", "refs/heads/$name").exitCode == 0

    /** The paths of the registered worktrees, as git prints them. */
    fun worktrees(): List<String> =
        git("worktree", "list", "--porcelain")
            .lines()
            .filter { it.startsWith("worktree ") }
            .map { it.removePrefix("worktree ") }
}
