package loop.runner.codex

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** m3-18: the version Loop sends as `clientInfo.version` is the build's, read from its build info file. */
class LoopVersionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the version of the build info file, unknown without a file or with a malformed value`() {
        val file = dir.resolve("build-info.properties")
        file.writeText("version=0123456789ab-dirty\n")
        assertEquals("0123456789ab-dirty", LoopVersion.of(file))

        file.writeText("version=two words\n")
        assertEquals("unknown", LoopVersion.of(file))
        assertEquals("unknown", LoopVersion.of(dir.resolve("absent.properties")))
        assertEquals("unknown", LoopVersion.of(null))
    }
}
