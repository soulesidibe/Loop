package loop.core.workspace

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
        HookRunner { hook, _, cwd, _, timeoutMs ->
            action(cwd)
            HookResult(hook, exitCode, timedOut = false, timeoutMs = timeoutMs, durationMs = 1, output = "")
        }

    private fun manager(
        hooks: HooksConfig,
        runner: HookRunner,
    ): WorkspaceManager = WorkspaceManager(Workspaces(root), hooks, runner)

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
