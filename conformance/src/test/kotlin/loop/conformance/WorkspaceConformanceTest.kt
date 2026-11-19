package loop.conformance

import kotlinx.coroutines.runBlocking
import loop.core.workflow.HooksConfig
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.Workspace
import loop.core.workspace.WorkspaceError
import loop.core.workspace.WorkspaceKey
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Symphony spec 17.2, workspace manager and safety, against the public
 * [WorkspaceManager] API with the real shell hook runner. The last item (agent
 * launch cwd) is in spec-core's OrchestratorHooksTest, where the runner is called.
 * Loop's own policies (non-directory fails, partial cleanup) are in docs/spec-decisions.md.
 */
class WorkspaceConformanceTest {
    private val folder: Path = createTempDirectory("loop-workspace-conformance").toRealPath()
    private val root: Path = folder.resolve("workspaces")
    private val home: Path = folder.resolve("home").createDirectories()

    private fun manager(hooks: HooksConfig = HooksConfig()): WorkspaceManager = WorkspaceManager(Workspaces(root), hooks, ShellHookRunner())

    private fun env(key: WorkspaceKey): Map<String, String> = mapOf("HOME" to home.toString(), "LOOP_WORKSPACE_KEY" to key.value)

    private fun prepare(
        identifier: String,
        manager: WorkspaceManager = manager(),
    ): Result<Workspace> {
        val key = WorkspaceKey.of(identifier)
        return runBlocking { manager.prepare(key, env(key)) }
    }

    @Test
    fun `workspace path is deterministic per issue identifier`() {
        val first = prepare("m1-04").getOrThrow()
        val second = prepare("m1-04").getOrThrow()

        assertEquals(root.toRealPath().resolve("m1-04"), first.path)
        assertEquals(first.path, second.path)
    }

    @Test
    fun `a missing workspace directory is created`() {
        val workspace = prepare("m1-04").getOrThrow()

        assertTrue(workspace.path.isDirectory())
        assertTrue(workspace.createdNow)
    }

    @Test
    fun `an existing workspace directory is reused`() {
        val path = prepare("m1-04").getOrThrow().path
        path.resolve("notes.txt").writeText("kept")

        val reused = prepare("m1-04").getOrThrow()

        assertFalse(reused.createdNow)
        assertEquals("kept", reused.path.resolve("notes.txt").readText())
    }

    @Test
    fun `an existing non directory at the workspace path fails and is kept`() {
        root.createDirectories().resolve("m1-04").writeText("not a directory")

        val error = prepare("m1-04").exceptionOrNull()

        assertIs<WorkspaceError.Io>(error)
        assertTrue(root.resolve("m1-04").isRegularFile())
    }

    @Test
    fun `an identifier unchanged by sanitizing keeps its key`() {
        assertEquals("m1-04", prepare("m1-04").getOrThrow().key.value)
    }

    @Test
    fun `identifiers that sanitize to the same text get distinct keys and paths`() {
        val slash = prepare("team/42").getOrThrow()
        val colon = prepare("team:42").getOrThrow()

        assertTrue(slash.key.value.startsWith("team_42-"))
        assertTrue(colon.key.value.startsWith("team_42-"))
        assertNotEquals(slash.key, colon.key)
        assertNotEquals(slash.path, colon.path)
        assertTrue(slash.key.value.all { it.isLetterOrDigit() || it in "._-" })
    }

    @Test
    fun `a key that climbs out of the root is rejected`() {
        val error = runBlocking { manager().prepare(WorkspaceKey(".."), emptyMap()) }.exceptionOrNull()

        assertIs<WorkspaceError.OutsideRoot>(error)
    }

    @Test
    fun `a symlink escaping the root is rejected`() {
        val outside = folder.resolve("outside").createDirectory()
        root.createDirectories().resolve("m1-04").createSymbolicLinkPointingTo(outside)

        val error = prepare("m1-04").exceptionOrNull()

        assertIs<WorkspaceError.OutsideRoot>(error)
    }

    @Test
    fun `after_create runs only when the workspace is created`() {
        val manager = manager(HooksConfig(afterCreate = "echo created >> after_create.log"))

        val path = prepare("m1-04", manager).getOrThrow().path
        prepare("m1-04", manager).getOrThrow()

        assertEquals(listOf("created"), path.resolve("after_create.log").readLines())
    }

    @Test
    fun `a population failure in after_create is surfaced with its output`() {
        val manager = manager(HooksConfig(afterCreate = "echo cannot clone; exit 7"))

        val error = prepare("m1-04", manager).exceptionOrNull()

        assertIs<WorkspaceError.Hook>(error)
        assertEquals("cannot prepare workspace m1-04: hook after_create failed with exit code 7", error.message)
        assertEquals(7, error.result.exitCode)
        assertTrue("cannot clone" in error.result.output, error.result.output)
    }

    @Test
    fun `before_run runs before each attempt and its failure or timeout aborts the attempt`() {
        val manager =
            manager(HooksConfig(beforeRun = "echo run >> before_run.log; exit \$(cat code 2>/dev/null || echo 0)", timeoutMs = 1_000))
        val workspace = prepare("m1-04", manager).getOrThrow()
        val key = workspace.key

        val first = runBlocking { manager.beforeRun(workspace, env(key)) }
        val second = runBlocking { manager.beforeRun(workspace, env(key)) }
        workspace.path.resolve("code").writeText("4")
        val failed = runBlocking { manager.beforeRun(workspace, env(key)) }

        assertEquals(listOf("run", "run", "run"), workspace.path.resolve("before_run.log").readLines())
        assertTrue(first!!.ok && second!!.ok)
        assertEquals("hook before_run failed with exit code 4", failed!!.reason)
        val slow = manager(HooksConfig(beforeRun = "sleep 30", timeoutMs = 1_000))
        val timedOut = runBlocking { slow.beforeRun(workspace, env(key)) }
        assertTrue(timedOut!!.timedOut)
        assertEquals("hook before_run timed out after 1000 ms", timedOut.reason)
    }

    @Test
    fun `after_run runs after each attempt and its failure or timeout is reported, not thrown`() {
        val manager = manager(HooksConfig(afterRun = "echo ran >> after_run.log; exit 5", timeoutMs = 1_000))
        val workspace = prepare("m1-04", manager).getOrThrow()

        val first = runBlocking { manager.afterRun(workspace, env(workspace.key)) }
        val second = runBlocking { manager.afterRun(workspace, env(workspace.key)) }
        val slow = manager(HooksConfig(afterRun = "sleep 30", timeoutMs = 1_000))
        val timedOut = runBlocking { slow.afterRun(workspace, env(workspace.key)) }

        assertEquals(listOf("ran", "ran"), workspace.path.resolve("after_run.log").readLines())
        assertEquals("hook after_run failed with exit code 5", first!!.reason)
        assertFalse(second!!.ok)
        assertTrue(timedOut!!.timedOut)
    }

    @Test
    fun `before_remove runs on cleanup and its failure is ignored`() {
        val marker = folder.resolve("before_remove.txt")
        val manager = manager(HooksConfig(beforeRemove = "pwd -P > \"$marker\"; exit 9"))
        val workspace = prepare("m1-04", manager).getOrThrow()

        val removed = runBlocking { manager.remove(workspace.key, env(workspace.key)) }

        assertTrue(removed.getOrThrow())
        assertEquals(workspace.path.toString(), marker.readText().trim())
        assertFalse(workspace.path.exists())
    }

    @Test
    fun `17_2 item 9 a before_remove timeout is ignored and the workspace is still removed`() {
        val manager = manager(HooksConfig(beforeRemove = "sleep 30", timeoutMs = 1_000))
        val workspace = prepare("m1-04", manager).getOrThrow()

        val removed = runBlocking { manager.remove(workspace.key, env(workspace.key)) }

        assertTrue(removed.getOrThrow())
        assertFalse(workspace.path.exists())
    }
}
