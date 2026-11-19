package loop.core.orchestrator

import loop.api.AttemptId
import loop.api.ProjectId
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** m1-23: the attempt directory path and the host side request queue `loop tool` writes. */
class ToolRequestQueueTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `the attempt directory is attempts, project id, attempt id under LOOP_HOME`() {
        val dir = attemptDirectory(root, ProjectId("p1"), AttemptId("a1"))

        assertEquals(root.resolve("attempts").resolve("p1").resolve("a1"), dir)
        assertEquals(root.resolve("attempts/p1/a1/tool-config.json"), dir.resolve(TOOL_CONFIG_FILE))
    }

    @Test
    fun `requests come back in file name order, temp files ignored`() {
        val attemptDir = root.resolve("attempt")
        attemptDir.resolve(REQUESTS_DIR).createDirectories()
        var now = 2_000L
        val queue = ToolRequestQueue(attemptDir, nowMillis = { now }, pid = 42L)

        // Written first but named later: the reader follows names, not write order.
        queue.enqueue(ToolRequest.Comment("Done, see the diff"))
        now = 1_000L
        queue.enqueue(ToolRequest.Move("review"))
        attemptDir.resolve(REQUESTS_DIR).resolve("0000000000000000500-1-x.tmp").writeText("{}")

        val pending = queue.pending()

        assertEquals(
            listOf(ToolRequest.Move("review"), ToolRequest.Comment("Done, see the diff")),
            pending.map { assertIs<QueuedRequest.Valid>(it).request },
        )
        val names = pending.map { it.file.name }
        assertTrue(names.all { it.endsWith(".json") && it.contains("-42-") }, "names: $names")
        assertTrue(names[0].startsWith("0000000000000001000-"), "names: $names")
        assertEquals(
            3,
            attemptDir.resolve(REQUESTS_DIR).listDirectoryEntries().size,
            "the writer leaves no temp file of its own",
        )
    }

    @Test
    fun `a request file that does not decode is unreadable, the others still read`() {
        val attemptDir = root.resolve("attempt")
        attemptDir.resolve(REQUESTS_DIR).createDirectories()
        val queue = ToolRequestQueue(attemptDir, nowMillis = { 2_000L }, pid = 7L)
        queue.enqueue(ToolRequest.Move("done"))
        attemptDir.resolve(REQUESTS_DIR).resolve("0000000000000001000-1-bad.json").writeText("not json")

        val pending = queue.pending()

        assertEquals(2, pending.size)
        assertIs<QueuedRequest.Unreadable>(pending[0])
        assertEquals(ToolRequest.Move("done"), assertIs<QueuedRequest.Valid>(pending[1]).request)
    }

    @Test
    fun `a requests folder that is a symlink is not followed`() {
        val outside = root.resolve("outside").createDirectories()
        outside.resolve("0000000000000001000-1-x.json").writeText("""{"type":"move","to":"review"}""")
        val attemptDir = root.resolve("attempt").createDirectories()
        Files.createSymbolicLink(attemptDir.resolve(REQUESTS_DIR), outside)

        assertEquals(emptyList(), ToolRequestQueue(attemptDir).pending())
    }

    @Test
    fun `an entry that is not a regular file is unreadable and never opened`() {
        val attemptDir = root.resolve("attempt")
        val requests = attemptDir.resolve(REQUESTS_DIR).createDirectories()
        val fifo = requests.resolve("0.json")
        val made =
            try {
                ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0
            } catch (e: IOException) {
                false
            }
        assumeTrue(made, "mkfifo is not available")
        requests.resolve("1.json").createDirectories()
        val outsideFile = root.resolve("outside.json")
        outsideFile.writeText("""{"type":"move","to":"review"}""")
        Files.createSymbolicLink(requests.resolve("2.json"), outsideFile)

        val pending =
            assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { ToolRequestQueue(attemptDir).pending() })

        assertEquals(listOf("0.json", "1.json", "2.json"), pending.map { it.file.name })
        pending.forEach { assertIs<QueuedRequest.Unreadable>(it) }
    }

    @Test
    fun `no requests folder means nothing pending`() {
        assertEquals(emptyList(), ToolRequestQueue(root.resolve("missing")).pending())
    }

    @Test
    fun `enqueue into an attempt directory that is gone fails with NoSuchFileException and creates nothing`() {
        val gone = root.resolve("gone")

        assertFailsWith<NoSuchFileException> { ToolRequestQueue(gone).enqueue(ToolRequest.Move("review")) }
        assertFalse(gone.exists())
    }
}
