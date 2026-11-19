package loop.cli

import loop.api.ProjectId
import loop.core.orchestrator.attemptsFolder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** m2-18: `loop run` prunes this Project's attempt files older than `observability.retention_days` at startup. */
class AttemptRetentionTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun folder(projectId: String = fixture.projectId()): Path =
        attemptsFolder(fixture.loopHome, ProjectId(projectId)).createDirectories()

    /** A file in [folder] last modified [age] ago. */
    private fun aged(
        folder: Path,
        name: String,
        age: Duration,
    ): Path =
        folder.resolve(name).also {
            it.writeText("x")
            Files.setLastModifiedTime(it, FileTime.fromMillis(System.currentTimeMillis() - age.inWholeMilliseconds))
        }

    @Test
    fun `attempt files older than 90 days are pruned at startup by default, and nothing else`() {
        val folder = folder()
        val old = aged(folder, "$OLD.jsonl.gz", 90.days + 1.hours)
        val recent = aged(folder, "$RECENT.jsonl.gz", 90.days - 1.hours)
        // Evidence backs a verdict, which is kept forever: never pruned here.
        val evidence = aged(folder, "$OLD.evidence-1.log", 400.days)
        val other = aged(folder("another-project"), "$OLD.jsonl.gz", 400.days)
        val target = aged(root, "target.jsonl.gz", 400.days)
        val link = Files.createSymbolicLink(folder.resolve("$LINKED.jsonl.gz"), target)

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(old.exists(), "an attempt file past the retention is deleted")
        assertTrue(recent.exists(), "an attempt file within the retention is kept")
        assertTrue(evidence.exists(), "an evidence file is kept")
        assertTrue(other.exists(), "another project's attempt files are not this run's to prune")
        assertTrue(Files.isSymbolicLink(link) && target.exists(), "a link is never followed nor deleted")
        assertContains(result.stderr, "event=attempt_retention outcome=completed")
        assertContains(result.stderr, "pruned=1")
        assertContains(result.stderr, "retention_days=90")
    }

    @Test
    fun `observability retention days sets the age`() {
        fixture.writeWorkflow(extra = "observability:\n  retention_days: 30")
        val folder = folder()
        val old = aged(folder, "$OLD.jsonl.gz", 31.days)
        val recent = aged(folder, "$RECENT.jsonl.gz", 29.days)

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `nothing to prune logs nothing`() {
        aged(folder(), "$RECENT.jsonl.gz", 1.days)

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse("attempt_retention" in result.stderr, result.stderr)
    }

    private companion object {
        const val OLD = "3d9a0c1e-0000-4000-8000-000000000001"
        const val RECENT = "3d9a0c1e-0000-4000-8000-000000000002"
        const val LINKED = "3d9a0c1e-0000-4000-8000-000000000003"
    }
}
