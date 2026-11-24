package loop.cli

import loop.api.PluginProfile
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * m3-05: each built in plugin's doc, `docs/plugins/runner-<kind>.md` or `tracker-<kind>.md`, has a `## Profile` table
 * that matches the profile the plugin declares: one row per key, its type, required or its default, whether it is a
 * secret and its limits. The docs folder is found beside Loop's own `WORKFLOW.md`, at the repository root.
 */
class PluginProfileDocsTest {
    private val docs: Path =
        Path
            .of(System.getProperty("loop.rootWorkflow") ?: fail("system property loop.rootWorkflow is not set"))
            .parent
            .resolve("docs/plugins")

    @Test
    fun `every built in runner has a doc whose profile table matches its profile`() {
        val runners = RunnerPlugins.classpath
        assertEquals(listOf("acp", "claude-code", "codex-app-server", "command"), runners.kinds.sorted())
        for (kind in runners.kinds) {
            val provider = runners.provider(kind)!!
            assertMatches(docs.resolve("runner-$kind.md"), "runners.${provider.section}", provider.profile)
        }
    }

    @Test
    fun `every built in tracker has a doc whose profile table matches its profile`() {
        val trackers = TrackerPlugins.classpath
        assertEquals(listOf("files", "github"), trackers.kinds.sorted())
        for (kind in trackers.kinds) {
            assertMatches(docs.resolve("tracker-$kind.md"), "tracker.provider", trackers.provider(kind)!!.profile)
        }
    }

    private fun assertMatches(
        doc: Path,
        section: String,
        profile: PluginProfile,
    ) {
        assertTrue(doc.exists(), "$doc does not exist")
        val rows = profileRows(doc)
        val expected =
            profile.keys.map { key ->
                listOf(
                    "$section.${key.name}",
                    key.type.description.substringAfter(' '),
                    if (key.required) "required" else key.default ?: "none",
                    if (key.name in profile.secrets) "yes" else "no",
                    profile.limits[key.name]?.let { "${it.first} to ${it.last}" } ?: "none",
                )
            }
        assertEquals(expected, rows, "the profile table of $doc does not match the profile")
    }

    /** The rows of the first table under `## Profile`, header and separator left out, each cell trimmed and unquoted. */
    private fun profileRows(doc: Path): List<List<String>> {
        val lines = doc.readText().lines()
        val start = lines.indexOf("## Profile").takeIf { it >= 0 } ?: fail("$doc has no ## Profile section")
        val table =
            lines
                .drop(start + 1)
                .takeWhile { !it.startsWith("## ") }
                .dropWhile { !it.startsWith("|") }
                .takeWhile { it.startsWith("|") }
        assertEquals(
            listOf("Key", "Type", "Required or default", "Secret", "Limits"),
            cells(table.firstOrNull() ?: fail("the ## Profile section of $doc has no table")),
        )
        return table.drop(2).map(::cells)
    }

    private fun cells(row: String): List<String> =
        row
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim().replace("`", "") }
}
