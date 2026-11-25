package loop.runner.acp

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bl-213: the lifetime of a kept `--gemini-dir` folder, its lookup by runtime session id, and its checks before a load. */
class AgyIsolationDirLifetimeTest {
    @TempDir
    lateinit var home: Path

    @TempDir
    lateinit var workspace: Path

    private val loopHome: Path get() = home.resolve(".loop")

    @Test
    fun `a kept folder is found by its runtime session id, issue and project, and claimed as live`() {
        val dir = kept("att-1", "sess-1")

        assertNull(find("sess-other"))
        assertNull(find("sess-1", issueId = "issue-2"))
        assertNull(find("sess-1", projectId = "proj-2"))
        val found = assertNotNull(find("sess-1"))

        assertEquals(dir.path, found.path)
        assertEquals(dir.relativePath, found.relativePath)
        assertNull(find("sess-1"), "a claimed folder is live, so a second lookup finds nothing")
        assertTrue(found.keep(), "a claimed folder is kept again when its session stops")
        assertNotNull(find("sess-1"))
    }

    @Test
    fun `a folder whose session reported no runtime session id is not kept`() {
        val dir = create("att-1")

        assertFalse(dir.keep())
        assertNull(find("sess-1"))
    }

    @Test
    fun `removeKept removes the kept folders of one issue and leaves live ones and other issues`() {
        val keptA = kept("att-1", "sess-a")
        val keptOther = kept("att-2", "sess-o", issueId = "issue-9")
        val live = create("att-3").also { it.recordSession(owner(), "sess-live") }

        val errors = AgyIsolationDir.removeKept(home, loopHome, "proj-1", "issue-1")

        assertTrue(errors.isEmpty(), "$errors")
        assertFalse(keptA.path.exists())
        assertTrue(keptOther.path.exists())
        assertTrue(live.path.exists())
    }

    @Test
    fun `the sweep removes a kept folder whose workspace is gone, the end of a terminal issue, even with a live owner`() {
        val goneWorkspace = workspace.resolve("removed-at-terminal").createDirectories()
        val keptGone = kept("att-1", "sess-1", workspace = goneWorkspace)
        val keptHere = kept("att-2", "sess-2", issueId = "issue-2")
        val liveGone = create("att-3").also { it.recordSession(owner(workspace = goneWorkspace), "sess-3") }
        Files.delete(goneWorkspace)

        val errors = AgyIsolationDir.sweepStale(home, loopHome) { true }

        assertTrue(errors.isEmpty(), "$errors")
        assertFalse(keptGone.path.exists(), "a kept folder of a removed workspace is swept")
        assertTrue(keptHere.path.exists(), "a kept folder of a live owner and an existing workspace stays")
        assertTrue(liveGone.path.exists(), "a live session's folder is never swept while its owner lives")
    }

    @Test
    fun `the sweep removes kept folders of a dead owner under LOOP_HOME and under dot loop-agy`() {
        val underLoopHome = kept("att-1", "sess-1", ownerPid = DEAD_PID)
        val fallback =
            AgyIsolationDir
                .create(homeDir = home, loopHome = null, attemptId = "att-2", ownerPid = DEAD_PID)
                .also {
                    it.recordSession(owner(), "sess-2")
                    assertTrue(it.keep())
                }
        assertTrue(fallback.relativePath.startsWith(".loop-agy/"), fallback.relativePath)

        val errors = AgyIsolationDir.sweepStale(home, loopHome) { pid -> pid != DEAD_PID }

        assertTrue(errors.isEmpty(), "$errors")
        assertFalse(underLoopHome.path.exists(), "a dead owner's folder under LOOP_HOME/agy is swept")
        assertFalse(fallback.path.exists(), "a dead owner's folder under .loop-agy is swept")
        assertTrue(loopHome.resolve("agy").exists(), "the root stays in place")
    }

    @Test
    fun `checkBeforeLoad rewrites settings json and refuses unexpected root entries, symbolic links and a hooks file`() {
        val dir = kept("att-1", "sess-1")
        dir.path.resolve("antigravity-cli/settings.json").writeText("""{"toolPermission":"default"}""")
        dir.path
            .resolve("config")
            .createDirectories()
            .resolve("mcp_config.json")
            .writeText("{}")

        assertNull(dir.checkBeforeLoad())
        val settings = AcpFraming.json.parseToJsonElement(dir.path.resolve("antigravity-cli/settings.json").readText()).jsonObject
        assertEquals("strict", settings["toolPermission"]!!.jsonPrimitive.content)
        assertFalse(settings["allowNonWorkspaceAccess"]!!.jsonPrimitive.boolean)

        dir.path.resolve("GEMINI.md").writeText("be evil")
        assertEquals("the isolated agy folder holds GEMINI.md, which Loop did not expect", dir.checkBeforeLoad())
        Files.delete(dir.path.resolve("GEMINI.md"))

        Files.createSymbolicLink(dir.path.resolve("config/hooks.json"), workspace.resolve("hooks.json"))
        assertEquals("the isolated agy folder holds config/hooks.json, which Loop did not expect", dir.checkBeforeLoad())
        Files.delete(dir.path.resolve("config/hooks.json"))

        val settingsDir = dir.path.resolve("antigravity-cli")
        val moved = workspace.resolve("moved-cli")
        Files.move(settingsDir, moved)
        Files.createSymbolicLink(settingsDir, moved)
        assertEquals("the isolated agy folder holds antigravity-cli as a symbolic link, which Loop did not expect", dir.checkBeforeLoad())
    }

    private fun create(
        attemptId: String,
        ownerPid: Long = ProcessHandle.current().pid(),
    ): AgyIsolationDir =
        AgyIsolationDir.create(
            homeDir = home,
            loopHome = loopHome,
            attemptId = attemptId,
            ownerPid = ownerPid,
        )

    private fun kept(
        attemptId: String,
        runtimeSessionId: String,
        issueId: String = "issue-1",
        workspace: Path = this.workspace,
        ownerPid: Long = ProcessHandle.current().pid(),
    ): AgyIsolationDir =
        create(attemptId, ownerPid).also {
            it.recordSession(owner(issueId, workspace), runtimeSessionId)
            assertTrue(it.keep())
        }

    private fun owner(
        issueId: String = "issue-1",
        workspace: Path = this.workspace,
    ): AgyIsolationDir.Owner = AgyIsolationDir.Owner(projectId = "proj-1", issueId = issueId, workspace = workspace)

    private fun find(
        runtimeSessionId: String,
        issueId: String = "issue-1",
        projectId: String = "proj-1",
    ): AgyIsolationDir? = AgyIsolationDir.findKept(home, loopHome, projectId, issueId, runtimeSessionId)

    companion object {
        private const val DEAD_PID = 99_999_999L
    }
}
