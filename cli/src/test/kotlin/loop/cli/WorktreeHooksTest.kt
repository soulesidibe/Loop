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
            val recorder = Recorder()
            val start = CountDownLatch(1)

            val prepared =
                runBlocking {
                    keys
                        .map { key ->
                            async(Dispatchers.IO) {
                                start.await()
                                val keyEnv = fresh.env + hookEnv(fresh.dir.resolve("WORKFLOW.md"), "id-${key.value}", key.value, key)
                                workspaces.prepare(key, keyEnv, recorder)
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
            recorder.assertNoRetry(round)
        }
    }

    @Test
    fun `parallel hooks create and remove workspaces of one repository at once`() {
        // bl-239: git worktree add writes another worktree's commondir while a parallel git reads it.
        repeat(MIXED_ROUNDS) { round ->
            val fresh = TestRepo(root.resolve("mixed-$round"), root.resolve("home").createDirectories()).init()
            val workspaces = WorkspaceManager(Workspaces(fresh.dir.resolve("workspaces")), hooks, ShellHookRunner())

            fun keyEnv(key: WorkspaceKey) = fresh.env + hookEnv(fresh.dir.resolve("WORKFLOW.md"), "id-${key.value}", key.value, key)
            val old = listOf(WorkspaceKey("p-1"), WorkspaceKey("p-2"))
            val new = listOf(WorkspaceKey("p-3"), WorkspaceKey("p-4"))
            old.forEach { key -> runBlocking { workspaces.prepare(key, keyEnv(key)) }.getOrThrow() }
            val recorder = Recorder()
            val start = CountDownLatch(1)

            val results =
                runBlocking {
                    val created =
                        new.map { key ->
                            async(Dispatchers.IO) {
                                start.await()
                                workspaces.prepare(key, keyEnv(key), recorder)
                            }
                        }
                    val removed =
                        old.map { key ->
                            async(Dispatchers.IO) {
                                start.await()
                                workspaces.remove(key, keyEnv(key), recorder)
                            }
                        }
                    start.countDown()
                    created.awaitAll() to removed.awaitAll()
                }

            results.first.forEachIndexed { i, result ->
                val workspace =
                    result.getOrElse {
                        fail("round $round, ${new[i].value}: ${it.message}\n${(it as? WorkspaceError.Hook)?.result?.output}")
                    }
                assertEquals("A project.\n", workspace.path.resolve("README.md").readText(), "round $round")
                assertTrue(workspace.path.toString() in fresh.worktrees(), "round $round: ${fresh.worktrees()}")
            }
            results.second.forEachIndexed { i, result -> assertTrue(result.getOrThrow(), "round $round, ${old[i].value}") }
            recorder.results.forEach { assertTrue(it.ok, "round $round: ${it.reason}\n${it.output}") }
            recorder.assertNoRetry(round)
            old.forEach { key ->
                assertFalse(
                    fresh.dir.resolve("workspaces/${key.value}").toString() in fresh.worktrees(),
                    "round $round: ${fresh.worktrees()}",
                )
                assertTrue(fresh.hasBranch("loop/${key.value}"), "round $round")
            }
            assertFalse(fresh.dir.resolve(".git/$LOCK").exists(), "round $round")
        }
    }

    @Test
    fun `a lock left by a dead process is taken over`() {
        val lock = repo.dir.resolve(".git/$LOCK").createDirectories()
        lock.resolve("pid").writeText("${deadPid()}\n")

        val workspace = prepare()

        assertTrue(workspace.path.resolve("README.md").exists())
        assertFalse(lock.exists())
    }

    @Test
    fun `a hook that cannot take the lock in time fails and names its holder`() {
        val holder = ProcessBuilder("sleep", "60").start()
        try {
            val lock = repo.dir.resolve(".git/$LOCK").createDirectories()
            lock.resolve("pid").writeText("${holder.pid()}\n")
            workspacePath.createDirectories()

            val result =
                runBlocking {
                    ShellHookRunner().run(
                        HookName.AFTER_CREATE,
                        hooks.afterCreate!!,
                        workspacePath,
                        env + ("LOOP_HOOK_LOCK_TIMEOUT" to "1"),
                        30_000,
                    )
                }

            assertEquals(1, result.exitCode, result.output)
            assertTrue("could not take the lock $lock within 1 s, held by pid ${holder.pid()}" in result.output, result.output)
            assertEquals("${holder.pid()}\n", lock.resolve("pid").readText())
            assertFalse(workspacePath.toString() in repo.worktrees(), repo.worktrees().toString())
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `a failing step under the lock keeps git's exit code and frees the lock`() {
        workspacePath.createDirectories()

        val result =
            runBlocking {
                ShellHookRunner().run(
                    HookName.AFTER_CREATE,
                    hooks.afterCreate!!,
                    workspacePath,
                    env + ("LOOP_BASE_REF" to "no-such-ref"),
                    30_000,
                )
            }

        assertEquals(128, result.exitCode, result.output)
        assertFalse(repo.dir.resolve(".git/$LOCK").exists())
    }

    @Test
    fun `eight hooks take over a dead process's lock together`() {
        // bl-239 recheck: waiters taking over one stale lock at once must not move a lock another just took.
        repeat(TAKEOVER_ROUNDS) { round ->
            val fresh = TestRepo(root.resolve("takeover-$round"), root.resolve("home").createDirectories()).init()
            val workspaces = WorkspaceManager(Workspaces(fresh.dir.resolve("workspaces")), hooks, ShellHookRunner())
            val keys = (1..8).map { WorkspaceKey("t-$it") }
            fresh.dir
                .resolve(".git/$LOCK")
                .createDirectories()
                .resolve("pid")
                .writeText("${deadPid()}\n")
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
                result.getOrElse { fail("round $round, ${keys[i].value}: ${it.message}\n${(it as? WorkspaceError.Hook)?.result?.output}") }
            }
            assertEquals(9, fresh.worktrees().size, "round $round: ${fresh.worktrees()}")
            assertFalse(fresh.dir.resolve(".git/$LOCK").exists(), "round $round")
            assertFalse(fresh.dir.resolve(".git/$LOCK.takeover").exists(), "round $round")
        }
    }

    @Test
    fun `a hook does not touch the worktrees while another holds the lock`() {
        val holder = ProcessBuilder("sleep", "60").start()
        try {
            val lock = repo.dir.resolve(".git/$LOCK").createDirectories()
            lock.resolve("pid").writeText("${holder.pid()}\n")
            workspacePath.createDirectories()

            runBlocking {
                val hook =
                    async(Dispatchers.IO) { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspacePath, env, 30_000) }
                Thread.sleep(1_500)
                assertFalse(workspacePath.toString() in repo.worktrees(), repo.worktrees().toString())
                lock.toFile().deleteRecursively()
                val result = hook.await()

                assertTrue(result.ok, "${result.reason}\n${result.output}")
            }
            assertTrue(workspacePath.toString() in repo.worktrees(), repo.worktrees().toString())
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `a TERM sent to the holder frees the lock`() {
        val gitDir = repo.dir.resolve(".git")
        val holder =
            ProcessBuilder("sh", "-c", ". '${hooksDir.resolve("worktree-lock.sh")}'; loop_lock_take '$gitDir'; sleep 30 & wait \$!").start()
        try {
            val pid = gitDir.resolve("$LOCK/pid")
            val deadline = System.nanoTime() + 10_000_000_000
            while (!(pid.exists() && pid.readText().trim() == holder.pid().toString())) {
                if (System.nanoTime() > deadline) fail("the holder never took the lock")
                Thread.sleep(50)
            }

            holder.destroy()

            assertTrue(holder.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(143, holder.exitValue())
            assertFalse(gitDir.resolve(LOCK).exists())
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `a lock timeout that is not a number fails with a message`() {
        workspacePath.createDirectories()

        val result =
            runBlocking {
                ShellHookRunner().run(
                    HookName.AFTER_CREATE,
                    hooks.afterCreate!!,
                    workspacePath,
                    env + ("LOOP_HOOK_LOCK_TIMEOUT" to "soon"),
                    30_000,
                )
            }

        assertEquals(1, result.exitCode, result.output)
        assertTrue("LOOP_HOOK_LOCK_TIMEOUT must be a whole number of seconds, not 'soon'" in result.output, result.output)
        assertFalse(workspacePath.toString() in repo.worktrees(), repo.worktrees().toString())
    }

    @Test
    fun `before_remove run in the main checkout deletes no file`() {
        val result = runBlocking { ShellHookRunner().run(HookName.BEFORE_REMOVE, hooks.beforeRemove!!, repo.dir, env, 30_000) }

        assertFalse(result.ok, result.output)
        assertEquals("A project.\n", repo.dir.resolve("README.md").readText())
        assertTrue(repo.dir.resolve(".loop/issues/m1-04.md").exists())
    }

    /** Records every hook result, to check the output of hooks that passed. */
    private class Recorder : HookListener {
        val results: MutableList<HookResult> = java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun finished(
            script: String,
            result: HookResult,
        ) {
            results += result
        }

        /** Under the lock the sparse pattern never collides, so its retry never fires. */
        fun assertNoRetry(round: Int) {
            results.forEach { assertFalse("sparse-checkout set failed on try" in it.output, "round $round: the retry fired\n${it.output}") }
        }
    }

    /** The PID of a process that has exited and been reaped. */
    private fun deadPid(): Long {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        process.waitFor()
        return process.pid()
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
        const val PARALLEL_ROUNDS = 30

        /** Each round makes 2 workspaces first, then 2 more while removing those: twice the work of a parallel round. */
        const val MIXED_ROUNDS = 10

        /** Each round has 8 hooks take over one stale lock. */
        const val TAKEOVER_ROUNDS = 5
        const val LOCK = "loop-worktree-hooks.lock"
    }
}
