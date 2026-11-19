package loop.core.workspace

import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspacesTest {
    private val root: Path = createTempDirectory("loop-workspaces").toRealPath()
    private val workspaces = Workspaces(root)

    @Test
    fun `workspace path is the key under the root`() {
        val workspace = workspaces.prepare(WorkspaceKey("m1-02")).getOrThrow()

        assertEquals(root.resolve("m1-02"), workspace.path)
    }

    @Test
    fun `an absent workspace is created as a directory`() {
        val workspace = workspaces.prepare(WorkspaceKey("m1-02")).getOrThrow()

        assertTrue(workspace.path.isDirectory())
        assertTrue(workspace.createdNow)
    }

    @Test
    fun `an existing workspace is reused with its contents`() {
        root
            .resolve("m1-02")
            .createDirectory()
            .resolve("notes.txt")
            .writeText("kept")

        val workspace = workspaces.prepare(WorkspaceKey("m1-02")).getOrThrow()

        assertFalse(workspace.createdNow)
        assertEquals("kept", workspace.path.resolve("notes.txt").readText())
    }

    @Test
    fun `a key that climbs out of the root is rejected`() {
        val error = workspaces.prepare(WorkspaceKey("..")).exceptionOrNull()

        assertIs<WorkspaceError.OutsideRoot>(error)
    }

    @Test
    fun `a key that resolves to the root itself is rejected`() {
        val error = workspaces.prepare(WorkspaceKey(".")).exceptionOrNull()

        assertIs<WorkspaceError.OutsideRoot>(error)
    }

    @Test
    fun `a symlink at the workspace path pointing outside the root is rejected`() {
        val outside = createTempDirectory("loop-outside")
        root.resolve("m1-02").createSymbolicLinkPointingTo(outside)

        val error = workspaces.prepare(WorkspaceKey("m1-02")).exceptionOrNull()

        assertIs<WorkspaceError.OutsideRoot>(error)
    }

    @Test
    fun `a regular file at the workspace path fails`() {
        root.resolve("m1-02").writeText("not a directory")

        val error = workspaces.prepare(WorkspaceKey("m1-02")).exceptionOrNull()

        assertIs<WorkspaceError.Io>(error)
    }

    @Test
    fun `an absent root is created`() {
        val nested = root.resolve("a/b")

        val workspace = Workspaces(nested).prepare(WorkspaceKey("m1-02")).getOrThrow()

        assertTrue(workspace.path.isDirectory())
        assertEquals(nested.resolve("m1-02"), workspace.path)
    }
}
