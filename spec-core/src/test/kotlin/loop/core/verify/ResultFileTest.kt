package loop.core.verify

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m2-15: `.loop/result.json` is taken once, never through a symlink, and a file Loop cannot read is a failure, not a crash. */
class ResultFileTest {
    private val workspace: Path = createTempDirectory("loop-result").toRealPath()
    private val file: Path = workspace.resolve(".loop/result.json")

    private fun error(taken: Result<String>?): String = checkNotNull(checkNotNull(taken).exceptionOrNull()).message.orEmpty()

    @Test
    fun `no result file is nothing to take`() {
        assertNull(ResultFile.take(workspace))
        workspace.resolve(".loop").createDirectories()
        assertNull(ResultFile.take(workspace))
    }

    @Test
    fun `a result file is read, then deleted so it is taken once`() {
        file.parent.createDirectories()
        file.writeText("{\"status\": \"done\"}\n")

        assertEquals("{\"status\": \"done\"}\n", ResultFile.take(workspace)?.getOrThrow())
        assertFalse(file.exists())
        assertNull(ResultFile.take(workspace))
    }

    @Test
    fun `a symlink is never followed, the link goes and its target stays`() {
        val target = workspace.resolve("secret.txt")
        target.writeText("not a result")
        file.parent.createDirectories()
        file.createSymbolicLinkPointingTo(target)

        assertEquals(".loop/result.json is a symlink", error(ResultFile.take(workspace)))
        assertFalse(Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("not a result", target.readText())
    }

    @Test
    fun `a folder in its place or a file over 1 MiB is a failure`() {
        file.createDirectories()
        assertEquals(".loop/result.json is not a regular file", error(ResultFile.take(workspace)))
        Files.delete(file)

        file.writeText("x".repeat((ResultFile.MAX_BYTES + 1).toInt()))
        assertEquals(".loop/result.json is larger than 1 MiB", error(ResultFile.take(workspace)))
        assertTrue(!file.exists(), "a file too large is taken too")
    }

    @Test
    fun `a stale result file is discarded unread, a symlink without its target, a folder is left (m2-15)`() {
        assertFalse(ResultFile.discard(workspace), "nothing to discard")

        file.parent.createDirectories()
        file.writeText("{\"status\": \"done\"}\n")
        assertTrue(ResultFile.discard(workspace))
        assertFalse(file.exists())

        val target = workspace.resolve("secret.txt")
        target.writeText("not a result")
        file.createSymbolicLinkPointingTo(target)
        assertTrue(ResultFile.discard(workspace))
        assertFalse(Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("not a result", target.readText())

        file.createDirectories()
        assertFalse(ResultFile.discard(workspace), "a folder is left for take to report")
        assertTrue(file.exists())
    }
}
