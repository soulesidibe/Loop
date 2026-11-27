package loop.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import loop.core.verify.WorkspaceGit
import loop.core.workflow.HooksConfig
import loop.core.workspace.HookListener
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.Workspace
import loop.core.workspace.WorkspaceClones
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
import kotlin.io.path.setPosixFilePermissions
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

    /** bl-28: the manager of `workspace.git: clone`, which makes each workspace a clone of the project before the hooks. */
    private val cloneManager =
        WorkspaceManager(
            Workspaces(repo.dir.resolve("workspaces")),
            hooks,
            ShellHookRunner(),
            WorkspaceClones(repo.dir, WorkspaceGit(repo.env + ("PATH" to System.getenv("PATH").orEmpty()))),
        )

    private fun prepareClone(): Workspace = runBlocking { cloneManager.prepare(key, env) }.getOrThrow()

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

    @Test
    fun `in a clone after_create hides the issues folder and leaves the project as it was`() {
        val config = repo.dir.resolve(".git/config").readText()
        val refs = repo.git("for-each-ref", "--format=%(refname) %(objectname)", "refs/heads/", "refs/tags/")

        val workspace = prepareClone()

        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
        assertEquals("A project.\n", workspace.path.resolve("README.md").readText())
        assertFalse(workspace.path.resolve(".loop/issues").exists())
        assertTrue(
            workspace.path
                .resolve(".git")
                .toFile()
                .isDirectory,
        )
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
        assertEquals(config, repo.dir.resolve(".git/config").readText())
        assertEquals(refs, repo.git("for-each-ref", "--format=%(refname) %(objectname)", "refs/heads/", "refs/tags/"))
        assertFalse(repo.dir.resolve(".git/loop-worktree-hooks.lock").exists())
    }

    @Test
    fun `in a clone before_run checks the branch out again, sets the pattern again and is idempotent`() {
        val workspace = prepareClone()
        workspace.path.resolve("work.txt").writeText("agent work\n")
        repo.git("add", "work.txt", cwd = workspace.path)
        repo.commit("agent commit", cwd = workspace.path)
        val tip = repo.git("rev-parse", "loop/m1-04", cwd = workspace.path)
        repo.git("checkout", "--quiet", "-b", "elsewhere", "main", cwd = workspace.path)
        repo.git("sparse-checkout", "disable", cwd = workspace.path)

        val first = runBlocking { cloneManager.beforeRun(workspace, env)!! }
        assertTrue(first.ok, first.output)
        val before = state(workspace)
        val second = runBlocking { cloneManager.beforeRun(workspace, env)!! }

        assertTrue(second.ok, second.output)
        assertEquals(before, state(workspace))
        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
        assertEquals("/*\n!/.loop/issues/", repo.git("sparse-checkout", "list", cwd = workspace.path))
        assertFalse(workspace.path.resolve(".loop/issues").exists())
        assertEquals(tip, repo.git("rev-parse", "HEAD", cwd = workspace.path))
        assertEquals("agent work\n", workspace.path.resolve("work.txt").readText())
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
    }

    @Test
    fun `in a clone before_run fails on a branch switch that would lose local changes`() {
        val workspace = prepareClone()
        repo.git("checkout", "--quiet", "-b", "elsewhere", cwd = workspace.path)
        workspace.path.resolve("README.md").writeText("Elsewhere.\n")
        repo.git("add", "README.md", cwd = workspace.path)
        repo.commit("elsewhere", cwd = workspace.path)
        workspace.path.resolve("README.md").writeText("Not committed.\n")

        val result = runBlocking { cloneManager.beforeRun(workspace, env)!! }

        assertFalse(result.ok, result.output)
        assertEquals("elsewhere", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
        assertEquals("Not committed.\n", workspace.path.resolve("README.md").readText())
    }

    @Test
    fun `before_run heals a clone that never got after_create`() {
        workspacePath.parent.createDirectories()
        cloneManager.clones!!.create(workspacePath, key, null)
        assertTrue(workspacePath.resolve(".loop/issues").exists())
        val workspace = prepareClone()
        assertFalse(workspace.createdNow)

        val result = runBlocking { cloneManager.beforeRun(workspace, env)!! }

        assertTrue(result.ok, result.output)
        assertFalse(workspace.path.resolve(".loop/issues").exists())
        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
    }

    @Test
    fun `after_create and before_run of a clone never wait on the lock, before_remove prunes stale worktrees`() {
        // A lock held by a live process: a hook that took it would time out after one second.
        val lock = repo.dir.resolve(".git/loop-worktree-hooks.lock").createDirectories()
        lock.resolve("pid").writeText("${ProcessHandle.current().pid()}\n")
        val locked = env + ("LOOP_HOOK_LOCK_TIMEOUT" to "1")

        val workspace = runBlocking { cloneManager.prepare(key, locked) }.getOrThrow()
        val run = runBlocking { cloneManager.beforeRun(workspace, locked)!! }

        assertTrue(run.ok, run.output)
        assertEquals("${ProcessHandle.current().pid()}\n", lock.resolve("pid").readText())

        // A worktree deleted without before_remove (before a switch to clones) keeps its entry and holds its branch.
        lock.toFile().deleteRecursively()
        val gone = root.resolve("gone")
        repo.git("worktree", "add", "--quiet", "-b", "loop/gone", gone.toString())
        gone.toFile().deleteRecursively()
        assertTrue(gone.toString() in repo.worktrees(), repo.worktrees().toString())
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
        val removed = runBlocking { cloneManager.remove(key, locked, listener) }.getOrThrow()

        assertTrue(removed)
        assertTrue(events.single().ok, events.single().output)
        assertFalse(workspacePath.exists())
        assertEquals("", repo.git("for-each-ref", "refs/loop/workspace/", "refs/loop/workspace-base/"))
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
        assertFalse(repo.dir.resolve(".git/loop-worktree-hooks.lock").exists())
    }

    @Test
    fun `a core worktree written in the clone cannot point the hooks at the project`() {
        val workspace = prepareClone()
        workspace.path.resolve("README.md").writeText("Agent.\n")
        workspace.path.resolve("planted.txt").writeText("planted\n")
        repo.git("add", "-A", cwd = workspace.path)
        repo.commit("agent", cwd = workspace.path)
        repo.git("checkout", "--quiet", "-b", "elsewhere", "main", cwd = workspace.path)
        repo.git("sparse-checkout", "disable", cwd = workspace.path)
        val config = repo.dir.resolve(".git/config").readText()
        repo.git("config", "core.worktree", repo.dir.toString(), cwd = workspace.path)

        val created = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000) }
        val run = runBlocking { cloneManager.beforeRun(workspace, env)!! }

        assertTrue(created.ok, created.output)
        assertTrue(run.ok, run.output)
        assertEquals("A project.\n", repo.dir.resolve("README.md").readText())
        assertFalse(repo.dir.resolve("planted.txt").exists())
        assertTrue(repo.dir.resolve(".loop/issues/m1-04.md").exists())
        assertEquals("", repo.git("status", "--porcelain", "--untracked-files=no"))
        assertEquals("main", repo.git("symbolic-ref", "--short", "HEAD"))
        assertEquals(config, repo.dir.resolve(".git/config").readText())
        // The hooks worked on the clone itself.
        assertEquals("Agent.\n", workspace.path.resolve("README.md").readText())
        assertFalse(workspace.path.resolve(".loop/issues").exists())
    }

    @Test
    fun `a commondir written in the clone makes the hooks refuse it, before git runs there`() {
        val workspace = prepareClone()
        repo.git("checkout", "--quiet", "-b", "elsewhere", cwd = workspace.path)
        repo.git("sparse-checkout", "disable", cwd = workspace.path)
        val config = repo.dir.resolve(".git/config").readText()
        val refs = repo.git("for-each-ref")
        val main = repo.git("rev-parse", "main")
        workspace.path.resolve(".git/commondir").writeText("${repo.dir.resolve(".git")}\n")

        val created = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000) }
        val run = runBlocking { cloneManager.beforeRun(workspace, env)!! }
        val remove = runBlocking { ShellHookRunner().run(HookName.BEFORE_REMOVE, hooks.beforeRemove!!, workspace.path, env, 30_000) }

        assertFalse(created.ok, created.output)
        assertTrue("is neither a worktree nor a clone of" in created.output, created.output)
        assertFalse(run.ok, run.output)
        assertFalse(remove.ok, remove.output)
        assertTrue("is not a clone of" in remove.output, remove.output)
        assertEquals(config, repo.dir.resolve(".git/config").readText())
        assertEquals(refs, repo.git("for-each-ref"))
        assertEquals(main, repo.git("rev-parse", "main"))
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
    }

    @Test
    fun `a symlink, a hard linked reflog or a ref storage in the clone's git folder makes the hooks refuse it`() {
        val cases =
            listOf<(Workspace) -> Unit>(
                { ws ->
                    val refs = ws.path.resolve(".git/refs")
                    refs.toFile().deleteRecursively()
                    java.nio.file.Files
                        .createSymbolicLink(refs, repo.dir.resolve(".git/refs"))
                },
                { ws ->
                    val sparse = ws.path.resolve(".git/info/sparse-checkout")
                    java.nio.file.Files
                        .delete(sparse)
                    java.nio.file.Files
                        .createSymbolicLink(sparse, repo.dir.resolve(".git/info/sparse-checkout"))
                },
                { ws ->
                    val head = ws.path.resolve(".git/logs/HEAD")
                    java.nio.file.Files
                        .delete(head)
                    java.nio.file.Files
                        .createSymbolicLink(head, repo.dir.resolve(".git/logs/HEAD"))
                },
                { ws ->
                    val head = ws.path.resolve(".git/logs/HEAD")
                    java.nio.file.Files
                        .delete(head)
                    java.nio.file.Files
                        .createLink(head, repo.dir.resolve(".git/logs/HEAD"))
                },
                { ws ->
                    val config = ws.path.resolve(".git/config").toString()
                    repo.git("config", "--file", config, "core.repositoryformatversion", "1")
                    repo.git("config", "--file", config, "extensions.refStorage", "files://${repo.dir.resolve(".git")}")
                },
            )
        cases.forEachIndexed { i, plant ->
            val workspace = prepareClone()
            repo.git("checkout", "--quiet", "-b", "elsewhere", cwd = workspace.path)
            val config = repo.dir.resolve(".git/config").readText()
            val refs = repo.git("for-each-ref")
            val main = repo.git("rev-parse", "main")
            val reflog = repo.dir.resolve(".git/logs/HEAD").readText()
            plant(workspace)

            val created = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000) }
            val run = runBlocking { cloneManager.beforeRun(workspace, env)!! }
            val remove = runBlocking { ShellHookRunner().run(HookName.BEFORE_REMOVE, hooks.beforeRemove!!, workspace.path, env, 30_000) }

            assertFalse(created.ok, "case $i: ${created.output}")
            assertTrue("is neither a worktree nor a clone of" in created.output, "case $i: ${created.output}")
            assertFalse(run.ok, "case $i: ${run.output}")
            assertFalse(remove.ok, "case $i: ${remove.output}")
            assertEquals(config, repo.dir.resolve(".git/config").readText(), "case $i")
            assertEquals(refs, repo.git("for-each-ref"), "case $i")
            assertEquals(main, repo.git("rev-parse", "main"), "case $i")
            assertEquals(reflog, repo.dir.resolve(".git/logs/HEAD").readText(), "case $i")
            assertFalse(repo.dir.resolve(".git/info/sparse-checkout").exists(), "case $i")
            assertEquals(listOf(repo.dir.toString()), repo.worktrees(), "case $i")
            runBlocking { cloneManager.remove(key, env) }.getOrThrow()
        }
    }

    @Test
    fun `a git file naming the project's git folder is refused, and before_remove only prunes`() {
        workspacePath.createDirectories()
        workspacePath.resolve(".git").writeText("gitdir: ${repo.dir.resolve(".git")}\n")
        workspacePath.resolve("agent.txt").writeText("agent work\n")
        val config = repo.dir.resolve(".git/config").readText()
        val refs = repo.git("for-each-ref")
        val index =
            repo.dir
                .resolve(".git/index")
                .toFile()
                .readBytes()
        val status = repo.git("status", "--porcelain")
        val files =
            repo.dir
                .toFile()
                .list()!!
                .sorted()

        val created = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspacePath, env, 30_000) }
        val run = runBlocking { ShellHookRunner().run(HookName.BEFORE_RUN, hooks.beforeRun!!, workspacePath, env, 30_000) }
        val remove = runBlocking { ShellHookRunner().run(HookName.BEFORE_REMOVE, hooks.beforeRemove!!, workspacePath, env, 30_000) }

        assertFalse(created.ok, created.output)
        assertTrue("is neither a worktree nor a clone of" in created.output, created.output)
        assertFalse(run.ok, run.output)
        assertTrue(remove.ok, remove.output)
        assertEquals(config, repo.dir.resolve(".git/config").readText())
        assertEquals(refs, repo.git("for-each-ref"))
        assertTrue(
            index.contentEquals(
                repo.dir
                    .resolve(".git/index")
                    .toFile()
                    .readBytes(),
            ),
        )
        assertEquals(status, repo.git("status", "--porcelain"))
        assertEquals(
            files,
            repo.dir
                .toFile()
                .list()!!
                .sorted(),
        )
        assertFalse(repo.dir.resolve(".git/info/sparse-checkout").exists())
        assertTrue(workspacePath.resolve("agent.txt").exists())
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
    }

    @Test
    fun `a clone whose alternates were deleted is refused, and Loop still removes it`() {
        val workspace = prepareClone()
        java.nio.file.Files
            .delete(workspace.path.resolve(".git/objects/info/alternates"))

        val created = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000) }
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
        val removed = runBlocking { cloneManager.remove(key, env, listener) }.getOrThrow()

        assertFalse(created.ok, created.output)
        assertTrue(removed)
        assertFalse(events.single().ok, events.single().output)
        assertFalse(workspacePath.exists())
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
    }

    @Test
    fun `a worktree workspace from before the switch to clones is refused, then removed with its entry`() {
        val old = prepare()
        old.path.resolve("work.txt").writeText("agent work\n")
        repo.git("add", "work.txt", cwd = old.path)
        repo.commit("agent commit", cwd = old.path)
        val tip = repo.git("rev-parse", "loop/m1-04")

        val reused = runBlocking { cloneManager.prepare(key, env) }
        val removed = runBlocking { cloneManager.remove(key, env) }.getOrThrow()

        val error = reused.exceptionOrNull()
        assertTrue(error is WorkspaceError.Clone, error.toString())
        assertTrue("is not a clone of the project" in error.message.orEmpty(), error.message)
        assertTrue(removed)
        assertFalse(workspacePath.exists())
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
        assertEquals(tip, repo.git("rev-parse", "loop/m1-04"))
    }

    @Test
    fun `clone and worktree hooks run on one repository at once`() {
        fun keyEnv(key: WorkspaceKey) = repo.env + hookEnv(repo.dir.resolve("WORKFLOW.md"), "id-${key.value}", key.value, key)
        val trees = listOf(WorkspaceKey("w-1"), WorkspaceKey("w-2"))
        val cloned = listOf(WorkspaceKey("c-1"), WorkspaceKey("c-2"))
        repeat(MIXED_ROUNDS) { round ->
            val recorder = Recorder()
            val start = CountDownLatch(1)
            val prepared =
                runBlocking {
                    (trees.map { manager to it } + cloned.map { cloneManager to it })
                        .map { (m, k) ->
                            async(Dispatchers.IO) {
                                start.await()
                                m.prepare(k, keyEnv(k), recorder).map { m.beforeRun(it, keyEnv(k), recorder) }
                            }
                        }.also { start.countDown() }
                        .awaitAll()
                }
            prepared.forEach { it.getOrElse { e -> fail("round $round: ${e.message}\n${(e as? WorkspaceError.Hook)?.result?.output}") } }
            trees.forEach { assertTrue(repo.dir.resolve("workspaces/${it.value}").toString() in repo.worktrees(), "round $round") }
            (trees + cloned).forEach { assertFalse(repo.dir.resolve("workspaces/${it.value}/.loop/issues").exists(), "round $round") }

            val stop = CountDownLatch(1)
            val removed =
                runBlocking {
                    (trees.map { manager to it } + cloned.map { cloneManager to it })
                        .map { (m, k) ->
                            async(Dispatchers.IO) {
                                stop.await()
                                m.remove(k, keyEnv(k), recorder)
                            }
                        }.also { stop.countDown() }
                        .awaitAll()
                }
            removed.forEach { assertTrue(it.getOrThrow(), "round $round") }
            recorder.results.forEach { assertTrue(it.ok, "round $round: ${it.hook.id} ${it.reason}\n${it.output}") }
            assertEquals(listOf(repo.dir.toString()), repo.worktrees(), "round $round")
        }
    }

    @Test
    fun `the hooks run no program that the clone's config names`() {
        val workspace = prepareClone()
        val marker = root.resolve("marker")
        val executable =
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------")
        val program = root.resolve("program.sh")
        program.writeText("#!/bin/sh\ntouch \"$marker\"\n")
        program.setPosixFilePermissions(executable)
        val planted = root.resolve("planted-hooks").createDirectories()
        for (name in listOf("post-checkout", "reference-transaction")) {
            planted.resolve(name).writeText("#!/bin/sh\ntouch \"$marker\"\n")
            planted.resolve(name).setPosixFilePermissions(executable)
        }

        fun hooks(): List<HookResult> =
            runBlocking {
                listOf(
                    ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000),
                    cloneManager.beforeRun(workspace, env)!!,
                )
            }

        // A hook and an fsmonitor: the hooks switch the branch back and set the pattern again, and run neither.
        repo.git("checkout", "--quiet", "-b", "elsewhere", cwd = workspace.path)
        repo.git("sparse-checkout", "disable", cwd = workspace.path)
        repo.git("config", "core.hooksPath", planted.toString(), cwd = workspace.path)
        repo.git("config", "core.fsmonitor", program.toString(), cwd = workspace.path)
        val first = hooks()
        first.forEach { assertTrue(it.ok, "${it.hook.id}: ${it.output}") }
        assertEquals("loop/m1-04", repo.git("symbolic-ref", "--short", "HEAD", cwd = workspace.path))
        assertFalse(workspace.path.resolve(".loop/issues").exists())
        assertFalse(marker.exists(), first.joinToString("\n") { it.output })

        // A partial clone promisor on an ext:: URL and loop/m1-04 at an object the clone lacks: the checkout of the
        // branch would fetch it lazily and run the program. Both hooks fail on the missing object instead.
        repo.git("config", "--unset", "core.hooksPath", cwd = workspace.path)
        repo.git("config", "--unset", "core.fsmonitor", cwd = workspace.path)
        repo.git("checkout", "--quiet", "elsewhere", cwd = workspace.path)
        workspace.path.resolve(".git/refs/heads/loop/m1-04").writeText("1111111111111111111111111111111111111111\n")
        repo.git("config", "core.repositoryFormatVersion", "1", cwd = workspace.path)
        repo.git("config", "extensions.partialClone", "ext::sh -c touch% $marker", cwd = workspace.path)
        repo.git("config", "protocol.ext.allow", "always", cwd = workspace.path)
        val second = hooks()
        assertFalse(marker.exists(), second.joinToString("\n") { it.output })
        second.forEach { assertFalse(it.ok, "${it.hook.id}: ${it.output}") }
    }

    @Test
    fun `after_create reads the last line of an alternates file without a newline`() {
        val workspace = prepareClone()
        val alternates = workspace.path.resolve(".git/objects/info/alternates")
        alternates.writeText(alternates.readText().trimEnd('\n'))
        repo.git("sparse-checkout", "disable", cwd = workspace.path)

        val result = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspace.path, env, 30_000) }

        assertTrue(result.ok, result.output)
        assertFalse(workspace.path.resolve(".loop/issues").exists())
    }

    @Test
    fun `after_create refuses a clone of another repository`() {
        val other = TestRepo(root.resolve("other"), root.resolve("home")).init()
        workspacePath.parent.createDirectories()
        repo.git("clone", "--quiet", "--shared", other.dir.toString(), workspacePath.toString())

        val result = runBlocking { ShellHookRunner().run(HookName.AFTER_CREATE, hooks.afterCreate!!, workspacePath, env, 30_000) }

        assertFalse(result.ok, result.output)
        assertTrue("is neither a worktree nor a clone of" in result.output, result.output)
        assertEquals(listOf(repo.dir.toString()), repo.worktrees())
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
