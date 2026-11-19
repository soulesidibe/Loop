package loop.cli

import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** m1-15: what a reload of `WORKFLOW.md` cannot apply live, said once per reload. */
class ReloadWarningsTest {
    @TempDir
    lateinit var root: Path

    private fun workflow(
        dir: String = "./issues",
        provider: String = "",
        env: Map<String, String> = emptyMap(),
    ): WorkflowFile {
        val file = root.resolve("WORKFLOW.md")
        file.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: files\n" +
                "  provider:\n" +
                "    dir: $dir\n" +
                provider +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: agent.sh\n" +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
        return WorkflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    @Test
    fun `an unchanged tracker and no new short secret give no warning`() {
        assertEquals(emptyList(), reloadWarnings(workflow(), workflow(), emptyMap()))
    }

    @Test
    fun `a changed tracker folder needs a restart`() {
        val warnings = reloadWarnings(workflow(), workflow(dir = "./other"), emptyMap())

        assertEquals(
            listOf(
                "tracker.provider.dir changed to ${root.toRealPath().resolve("other")}, " +
                    "Loop keeps reading ${root.toRealPath().resolve("issues")} until loop run is restarted",
            ),
            warnings,
        )
    }

    @Test
    fun `a new short secret is named once and an old one is not repeated`() {
        val env = mapOf("OLD_TOKEN" to "ab", "NEW_TOKEN" to "cd")
        val before = workflow(provider = "    token: \$OLD_TOKEN\n", env = env)
        val after = workflow(provider = "    token: \$OLD_TOKEN\n    other: \$NEW_TOKEN\n", env = env)

        assertEquals(listOf("\$NEW_TOKEN is under 4 characters, so Loop does not redact it"), reloadWarnings(before, after, env))
    }
}
