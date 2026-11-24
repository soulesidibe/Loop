package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.runBlocking
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-11, story 111: `loop run` with extra trackers under `trackers:` works the primary tracker's issues as before, with
 * no line saying the extra trackers are not polled, since m4-13 polls them; the host builds each tracker's adapter from
 * its own entry. Two trackers polled together are tested in [MultiTrackerRunCommandTest].
 */
class ExtraTrackersRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val trackers =
        "trackers:\n" +
            "  - id: side\n" +
            "    kind: files\n" +
            "    provider: { dir: ./side-issues }\n" +
            "    required_labels: [side-ready]\n"

    // m4-13 polls the extra trackers and removed the startup line, so this test now checks that no such line is
    // logged. It keeps its m4-11 name, since a renamed test counts as a deleted one for the `deleted_tests` guard.
    @Test
    fun `loop run with extra trackers logs one line that they are not polled yet and works the primary tracker`() {
        fixture.writeWorkflow(extra = trackers)
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        assertTrue(result.stderr.lines().none { "not poll" in it }, result.stderr)
    }

    @Test
    fun `loop run with no extra tracker logs no such line`() {
        fixture.issue("t-1")

        val result = LoopCommand(host()).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.stderr.lines().none { "not poll" in it }, result.stderr)
    }

    @Test
    fun `the host builds each tracker's adapter from its own entry`() {
        fixture.writeWorkflow(extra = trackers)
        fixture.issue("t-1")
        val side = fixture.projectDir.resolve("side-issues").createDirectories()
        side.resolve("s-1.md").writeText("---\nid: s-1\ntitle: Side work\nstate: todo\nlabels: [side-ready]\n---\nBody\n")
        val workflow = workflowLoader(env = emptyMap(), home = fixture.homeDir).load(fixture.workflow).getOrThrow()

        val registry = trackerRegistryFor(workflow, fixture.loopHome)

        assertEquals(listOf("default", "side"), registry.config.ids)
        assertEquals(listOf("side-ready"), registry.entry("side")!!.requiredLabels)
        assertEquals(listOf("ready-for-agent"), registry.primary.requiredLabels)
        val primaryIds = runBlocking { registry.primaryAdapter.fetchIssuesByStates(listOf("todo")) }.getOrThrow().map { it.identifier }
        val sideIds = runBlocking { registry.adapter("side")!!.fetchIssuesByStates(listOf("todo")) }.getOrThrow().map { it.identifier }
        assertEquals(listOf("t-1"), primaryIds)
        assertEquals(listOf("s-1"), sideIds)
    }

    private fun host(): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = { PreflightResult() },
            awakeLauncher = null,
        )
}
