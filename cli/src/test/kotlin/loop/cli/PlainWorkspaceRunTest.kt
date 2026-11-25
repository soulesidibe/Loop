package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * m4-20, end to end with the files tracker and the command runner: a `notes` kind declared `workspace: plain` and
 * `populate: empty` works in a folder Loop creates, which is not a git repository. Its `command` check verifies it, and
 * the verdict and the evidence carry a `tree:` fingerprint. The workflow's `after_create` hook would make the folder a
 * git repository, and never runs for it.
 */
class PlainWorkspaceRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a command check verifies a plain workspace, and the verdict and the evidence carry a tree fingerprint`() {
        fixture.issue("n-1", labels = listOf("ready-for-agent", "notes"))
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |hooks:
                |  after_create: git init -q .
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |  notes:
                |    match: { label: notes }
                |    workspace: plain
                |    populate: empty
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "test -f hello.txt" }
                """.trimMargin(),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("n-1"), result.toString())
        assertFalse(fixture.workspace("n-1").resolve(".git").exists(), "the plain workspace is not a git repository")
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"verified\"")
        val fingerprint = assertNotNull(Regex("\"fingerprint\":\"(tree:[0-9a-f]{16})\"").find(verdict), verdict).groupValues[1]
        val evidence =
            Files.walk(fixture.loopHome.resolve("attempts")).use { files ->
                files.filter { it.name.contains(".evidence-") }.toList()
            }
        assertContains(evidence.single().readText(), "fingerprint: $fingerprint")
    }
}
