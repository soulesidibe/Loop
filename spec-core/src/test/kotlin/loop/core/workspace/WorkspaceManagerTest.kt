package loop.core.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import loop.core.workflow.HooksConfig
import java.nio.file.Path
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loop's own workspace rules on top of spec 17.2 (docs/spec-decisions.md rows 181
 * and 183), with a hook runner that returns canned results.
 */
class WorkspaceManagerTest {
    private val root: Path = createTempDirectory("loop-workspace-manager").toRealPath()
    private val key = WorkspaceKey("m1-04")

    /** A runner whose hooks all end with [exitCode], after [action] ran in the workspace. */
    private fun runner(
        exitCode: Int,
        action: (Path) -> Unit = {},
    ): HookRunner =
        HookRunner { hook, _, cwd, _, timeoutMs, _ ->
            action(cwd)
            HookResult(hook, exitCode, timedOut = false, timeoutMs = timeoutMs, durationMs = 1, output = "")
        }

    private fun manager(
        hooks: HooksConfig,
        runner: HookRunner,
    ): WorkspaceManager = WorkspaceManager(Workspaces(root), hooks, runner)

    /** A listener that keeps what it is told: the order of the calls and each stopped hook's result. */
    private class Recording : HookListener {
        val calls = mutableListOf<String>()
        val stopped = mutableListOf<Pair<String, HookResult>>()

        override suspend fun started(
            hook: HookName,
            script: String,
        ) {
            calls += "started ${hook.id}"
        }

        override suspend fun stopped(
            script: String,
            result: HookResult,
        ) {
            stopped += script to result
        }
    }

    /** Runs before_run with [runner], which must suspend, and cancels it once started. */
    private fun stopBeforeRun(runner: HookRunner): Recording {
        val manager = manager(HooksConfig(beforeRun = "sync"), runner)
        val workspace = runBlocking { manager.prepare(key, emptyMap()) }.getOrThrow()
        val listener = Recording()
        val started = CompletableDeferred<Unit>()
        runBlocking {
            val job =
                launch {
                    manager.beforeRun(
                        workspace,
                        emptyMap(),
                        object : HookListener by listener {
                            override suspend fun started(
                                hook: HookName,
                                script: String,
                            ) {
                                listener.started(hook, script)
                                started.complete(Unit)
                            }
                        },
                    )
                }
            started.await()
            job.cancelAndJoin()
        }
        return listener
    }

    @Test
    fun `bl-04 a hook stopped by a cancel is reported with the output the runner kept so far`() {
        val listener =
            stopBeforeRun { hook, _, _, _, timeoutMs, stopped ->
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    stopped(HookResult(hook, null, timedOut = false, timeoutMs = timeoutMs, durationMs = 7, output = "half\n", stopped = true))
                    throw e
                }
            }

        val (script, result) = listener.stopped.single()
        assertEquals("sync", script)
        assertEquals(HookName.BEFORE_RUN, result.hook)
        assertTrue(result.stopped)
        assertNull(result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("half\n", result.output)
        assertEquals(7, result.durationMs)
    }

    @Test
    fun `bl-04 a hook stopped by a cancel is reported with an empty output when the runner kept none`() {
        val listener = stopBeforeRun { _, _, _, _, _, _ -> awaitCancellation() }

        val (_, result) = listener.stopped.single()
        assertEquals(HookName.BEFORE_RUN, result.hook)
        assertTrue(result.stopped)
        assertNull(result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("", result.output)
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `bl-04 prepare tells the caller the workspace exists before after_create runs`() {
        val order = mutableListOf<String>()
        val manager = manager(HooksConfig(afterCreate = "populate"), runner(exitCode = 0) { order += "after_create in ${it.fileName}" })

        runBlocking {
            manager.prepare(key, emptyMap()) { created ->
                assertTrue(created.path.exists(), "the folder exists when the caller is told")
                order += "created ${created.path.fileName}"
            }
        }.getOrThrow()

        assertEquals(listOf("created m1-04", "after_create in m1-04"), order)
        val again = mutableListOf<String>()
        runBlocking { manager.prepare(key, emptyMap()) { again += it.path.fileName.toString() } }.getOrThrow()
        assertEquals(listOf("m1-04"), again, "a reused workspace is reported too, with no after_create")
    }

    @Test
    fun `a failed after_create removes the workspace it just created`() {
        val manager = manager(HooksConfig(afterCreate = "populate"), runner(exitCode = 1) { it.resolve("half.txt").writeText("x") })

        val error = runBlocking { manager.prepare(key, emptyMap()) }.exceptionOrNull()

        assertIs<WorkspaceError.Hook>(error)
        assertFalse(root.resolve("m1-04").exists())
    }

    @Test
    fun `a reused workspace is kept as is when before_run fails`() {
        val manager = manager(HooksConfig(beforeRun = "sync"), runner(exitCode = 2))
        val workspace = runBlocking { manager.prepare(key, emptyMap()) }.getOrThrow()
        workspace.path.resolve("work.txt").writeText("in progress")
        val reused = runBlocking { manager.prepare(key, emptyMap()) }.getOrThrow()

        val result = runBlocking { manager.beforeRun(reused, emptyMap()) }

        assertFalse(result!!.ok)
        assertEquals("in progress", reused.path.resolve("work.txt").readText())
    }

    @Test
    fun `removal does not follow symlinks`() {
        val outside = createTempDirectory("loop-outside")
        outside.resolve("precious.txt").writeText("keep me")
        val manager = manager(HooksConfig(beforeRemove = "cleanup"), runner(exitCode = 1))
        val workspace = runBlocking { manager.prepare(key, emptyMap()) }.getOrThrow()
        workspace.path.resolve("link").createSymbolicLinkPointingTo(outside)

        val removed = runBlocking { manager.remove(key, emptyMap()) }.getOrThrow()

        assertTrue(removed)
        assertFalse(workspace.path.exists())
        assertEquals("keep me", outside.resolve("precious.txt").readText())
    }

    @Test
    fun `removal refuses a non directory and keeps it`() {
        root.resolve("m1-04").writeText("not a workspace")
        val manager = manager(HooksConfig(beforeRemove = "cleanup"), runner(exitCode = 0))

        val error = runBlocking { manager.remove(key, emptyMap()) }.exceptionOrNull()

        assertIs<WorkspaceError.Io>(error)
        assertEquals("not a workspace", root.resolve("m1-04").readText())
    }
}
