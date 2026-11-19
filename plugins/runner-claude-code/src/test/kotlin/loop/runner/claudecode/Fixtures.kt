package loop.runner.claudecode

import java.nio.file.Path
import kotlin.io.path.toPath

/** The recorded stream-json transcripts under `src/test/resources/streams`. */
object Fixtures {
    fun path(name: String): Path =
        checkNotNull(Fixtures::class.java.getResource("/streams/$name.jsonl")) { "no fixture $name" }
            .toURI()
            .toPath()

    fun lines(name: String): List<String> = path(name).toFile().readLines()
}
