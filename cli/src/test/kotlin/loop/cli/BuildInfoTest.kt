package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The build's version (m1-12): `build-info.properties` at the app home root, read by `loop --version`. */
class BuildInfoTest {
    @TempDir
    lateinit var root: Path

    private fun fileWith(text: String): Path = root.resolve("build-info.properties").apply { writeText(text) }

    @Test
    fun `a build info file gives its version`() {
        assertEquals("3f2a9c1d7e4b-dirty", BuildInfo.versionOf(fileWith("version=3f2a9c1d7e4b-dirty\n")))
    }

    @Test
    fun `no property and a missing file give unknown`() {
        assertEquals("unknown", BuildInfo.versionOf(null))
        assertEquals("unknown", BuildInfo.versionOf(root.resolve("absent.properties")))
    }

    @Test
    fun `a malformed file gives unknown`() {
        listOf("", "commit=3f2a9c1d7e4b\n", "version=\n", "version=   \n", "version=3f2a 9c1d\n", "version=a\\nb\n").forEach { text ->
            assertEquals("unknown", BuildInfo.versionOf(fileWith(text)), "for ${text.trim()}")
        }
    }

    @Test
    fun `a folder in place of the file gives unknown`() {
        assertEquals("unknown", BuildInfo.versionOf(root))
    }

    /** The `version` of the installed launcher's `build-info.properties`, as Gradle wrote it. */
    private fun installedVersion(): String =
        Path(System.getProperty("loop.launcher"))
            .parent
            .parent
            .resolve("build-info.properties")
            .readText()
            .lines()
            .single { it.startsWith("version=") }
            .removePrefix("version=")

    /** The `loop_version` of an event's json `data`, null without one. */
    private fun loopVersionOf(data: String): String? {
        val fields = Json.parseToJsonElement(data).jsonObject
        return fields["loop_version"]?.jsonPrimitive?.content
    }

    @Test
    fun `loop --version prints the version of the installed build`() {
        val installed = installedVersion()

        val result = LoopFixture(root).loop("--version")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("loop $installed", result.stdout.trim())
    }

    @Test
    fun `a run through the installed launcher records the installed version on its attempt`() {
        val installed = installedVersion()
        // Built from a git checkout: never unknown, a 12 hex commit with an optional -dirty.
        assertTrue(Regex("[0-9a-f]{12}(-dirty)?").matches(installed), installed)
        val fixture = LoopFixture(root)
        fixture.issue("t-1")

        val run = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, run.exitCode, run.toString())
        val recorded =
            fixture
                .query("SELECT data FROM events WHERE kind = 'attempt_started' AND issue_id = 't-1'")
                .map { row -> loopVersionOf(row["data"]!!) }
        assertEquals(listOf<String?>(installed), recorded)
    }
}
