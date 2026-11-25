package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m4-18, end to end with the files tracker and the command runner: a project with two kinds dispatches a labelled
 * issue under the second kind and an unlabelled one under `code`. Each attempt's hooks get `LOOP_KIND`, each is
 * verified by its own kind's checks, and the ledger rows and lines of each hold its kind.
 */
class KindsRunCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val ledgerFile: Path get() = fixture.loopHome.resolve("ledger").resolve("${fixture.projectId()}.jsonl")

    /**
     * Two kinds: `code`, the default, and `content` for the `content` label. The `after_create` hook writes `LOOP_KIND`
     * to `kind.txt`, and each kind's checks need its own name there, so an issue verified under the other kind fails.
     */
    private fun twoKinds() =
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |hooks:
                |  after_create: echo "${'$'}LOOP_KIND" > kind.txt
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                |        - { type: file_matches, path: kind.txt, pattern: "^code${'$'}" }
                |  content:
                |    match: { label: content }
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: file_matches, path: kind.txt, pattern: "^content${'$'}" }
                """.trimMargin(),
        )

    @Test
    fun `a labelled issue runs under the second kind, an unlabelled one under code, and the ledger holds each kind`() {
        fixture.issue("c-1")
        fixture.issue("p-1", labels = listOf("ready-for-agent", "content"))
        twoKinds()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(
            "code",
            fixture
                .workspace("c-1")
                .resolve("kind.txt")
                .readText()
                .trim(),
        )
        assertEquals(
            "content",
            fixture
                .workspace("p-1")
                .resolve("kind.txt")
                .readText()
                .trim(),
        )
        // Each issue passed its own kind's checks, which the other kind's would refute.
        assertEquals("review", fixture.stateOf("c-1"), result.toString())
        assertEquals("review", fixture.stateOf("p-1"), result.toString())

        val rows = fixture.query("SELECT identifier, kind FROM attempts").associate { it["identifier"] to it["kind"] }
        assertEquals<Map<String?, String?>>(mapOf("c-1" to "code", "p-1" to "content"), rows)
        val lines = ledgerFile.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(lines.isNotEmpty(), "no ledger line")
        lines.forEach { line ->
            val identifier = line.getValue("issue_identifier").jsonPrimitive.content
            val expected = if (identifier == "p-1") "content" else "code"
            assertEquals(expected, line.getValue("kind").jsonPrimitive.content, "$line")
        }
    }
}
