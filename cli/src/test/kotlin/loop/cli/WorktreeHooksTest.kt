package loop.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import loop.core.workflow.HooksConfig
import loop.core.workspace.HookListener
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.Workspace
import loop.core.workspace.WorkspaceError
import loop.core.workspace.WorkspaceKey
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import loop.core.workspace.hookEnv
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Loop's documented worktree hooks in scripts/hooks/ (docs/workspaces.md), run
 * through the real workspace manager against a temporary git repository.
 */
class WorktreeHooksTest {
    private val root: Path = createTempDirectory("loop-worktree-hooks").toRealPath()
    private val repo = TestRepo(root.resolve("project"), root.resolve("home").createDirectories()).init()
    private val hooksDir: Path = Path.of(System.getProperty("loop.hooksDir") ?: fail("system property loop.hooksDir is not set"))
    private val key = WorkspaceKey("m1-04")
    private val workspacePath: Path = repo.dir.resolve("workspaces/m1-04")

    private fun script(name: String): String = "sh \"${hooksDir.resolve(name)}\""

    private val hooks =
        HooksConfig(
            afterCreate = script("worktree-after-create.sh"),
            beforeRun = script("worktree-before-run.sh"),
            beforeRemove = script("worktree-before-remove.sh"),
            timeoutMs = 30_000,
        )

    private val manager = WorkspaceManager(Workspaces(repo.dir.resolve("workspaces")), hooks, ShellHookRunner())

    private val env: Map<String, String> = repo.env + hookEnv(repo.dir.resolve("WORKFLOW.md"), "id-m1-04", "m1-04", key)

    private fun prepare(): Workspace = runBlocking { manager.prepare(key, env) }.getOrThrow()

    @Test
    fun `after_create makes a worktree on the issue branch without the issues folder`() {
        val workspace = prepare()

        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
        assertTrue(workspace.path.resolve("README.md").exists())
        assertFalse(workspace.path.resolve(".loop/issues").exists())
        assertTrue(workspace.path.toString() in repo.worktrees(), repo.worktrees().toString())
        assertEquals("A project.\n", workspace.path.resolve("README.md").readText())
    }

    @Test
    fun `the documented hooks are idempotent and keep the branch commits`() {
        val workspace = prepare()
        workspace.path.resolve("work.txt").writeText("agent work\n")
        repo.git("add", "work.txt", cwd = workspace.path)
        repo.commit("agent commit", cwd = workspace.path)
        val tip = repo.git("rev-parse", "loop/m1-04")
        val before = state(workspace)

        val results =
            runBlocking {
                listOf(
                    ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000),
                    ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000),
                    manager.beforeRun(workspace, env)!!,
                    manager.beforeRun(workspace, env)!!,
                )
            }

        results.forEach { assertTrue(it.ok, "${it.hook.id}: ${it.reason}\n${it.output}") }
        assertEquals(before, state(workspace))
        assertEquals(tip, repo.git("rev-parse", "loop/m1-04"))
        assertEquals("agent work\n", workspace.path.resolve("work.txt").readText())
    }

    @Test
    fun `before_remove removes the worktree and keeps the branch`() {
        val workspace = prepare()
        workspace.path.resolve("work.txt").writeText("agent work\n")
        repo.git("add", "work.txt", cwd = workspace.path)
        repo.commit("agent commit", cwd = workspace.path)
        val tip = repo.git("rev-parse", "loop/m1-04")
        val events = mutableListOf<HookResult>()
        val listener =
            object : HookListener {
                override suspend fun finished(
                    script: String,
                    result: HookResult,
                ) {
                    events += result
                }
            }

        val removed = runBlocking { manager.remove(key, env, listener) }.getOrThrow()

        assertTrue(removed)
        assertTrue(events.single().ok, events.single().output)
        assertFalse(workspacePath.exists())
        assertFalse(workspacePath.toString() in repo.worktrees(), repo.worktrees().toString())
        assertTrue(repo.hasBranch("loop/m1-04"))
        assertEquals(tip, repo.git("rev-parse", "loop/m1-04"))
        // Again on a plain folder at the same place: nothing to remove, still a success.
        workspacePath.createDirectories()
        val again = runBlocking { ShellHookRunner().run(HookName.BEFORE_REMOVE, hooks.beforeRemove!!, workspacePath, env, 30_000) }
        assertTrue(again.ok, again.output)
        assertTrue(repo.hasBranch("loop/m1-04"))
    }

    @Test
    fun `an existing issue branch is reused, not reset`() {
        repo.git("branch", "loop/m1-04")
        val branchTip = repo.git("rev-parse", "loop/m1-04")
        repo.dir.resolve("NEWS.md").writeText("Later work on main.\n")
        repo.git("add", "NEWS.md")
        repo.commit("later commit on main")

        val workspace = prepare()

        assertEquals(branchTip, repo.git("rev-parse", "HEAD", cwd = workspace.path))
        assertEquals(branchTip, repo.git("rev-parse", "loop/m1-04"))
        assertFalse(workspace.path.resolve("NEWS.md").exists())
    }

    @Test
    fun `before_run repairs a worktree left with an empty index by a hard kill`() {
        repo.git("worktree", "add", "--quiet", "--no-checkout", "-b", "loop/m1-04", workspacePath.toString(), "main")
        val workspace = Workspace(key, workspacePath, createdNow = false)

        val result = runBlocking { manager.beforeRun(workspace, env)!! }

        assertTrue(result.ok, "${result.reason}\n${result.output}")
        assertTrue(workspacePath.resolve("README.md").exists())
        assertFalse(workspacePath.resolve(".loop/issues").exists())
    }

    @Test
    fun `after_create survives two parallel workspaces on a fresh repository`() {
        // bl-08: on a repository without extensions.worktreeConfig, the first sparse-checkout set
        // writes it to the shared config, and two hooks doing it at once collide on the config lock.
        repeat(PARALLEL_ROUNDS) { round ->
            val fresh = TestRepo(root.resolve("parallel-$round"), root.resolve("home").createDirectories()).init()
            val workspaces = WorkspaceManager(Workspaces(fresh.dir.resolve("workspaces")), hooks, ShellHookRunner())
            val keys = listOf(WorkspaceKey("p-1"), WorkspaceKey("p-2"))
            val start = CountDownLatch(1)

            val prepared =
                runBlocking {
                    keys
                        .map { key ->
                            async(Dispatchers.IO) {
                                start.await()
                                val keyEnv = fresh.env + hookEnv(fresh.dir.resolve("WORKFLOW.md"), "id-${key.value}", key.value, key)
                                workspaces.prepare(key, keyEnv)
                            }
                        }.also { start.countDown() }
                        .awaitAll()
                }

            prepared.forEachIndexed { i, result ->
                val workspace =
                    result.getOrElse {
                        fail("round $round, ${keys[i].value}: ${it.message}\n${(it as? WorkspaceError.Hook)?.result?.output}")
                    }
                assertEquals("loop/${keys[i].value}", fresh.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path), "round $round")
                assertEquals("A project.\n", workspace.path.resolve("README.md").readText(), "round $round")
                assertFalse(workspace.path.resolve(".loop/issues").exists(), "round $round")
                assertTrue(workspace.path.toString() in fresh.worktrees(), "round $round: ${fresh.worktrees()}")
            }
        }
    }

    /** What the hooks control: branch, sparse patterns, worktrees, files at the top. */
    private fun state(workspace: Workspace): List<String> =
        listOf(
            repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path),
            repo.git("sparse-checkout", "list", cwd = workspace.path),
            repo.worktrees().joinToString(","),
            workspace.path
                .toFile()
                .list()!!
                .sorted()
                .joinToString(","),
            workspace.path
                .resolve(".loop/issues")
                .exists()
                .toString(),
        )

    private companion object {
        const val PARALLEL_ROUNDS = 10
    }
}
