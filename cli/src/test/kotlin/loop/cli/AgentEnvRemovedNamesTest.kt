package loop.cli

import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** m1-23: every `$NAME` WORKFLOW.md references is kept out of the agent env, except the kept set. */
class AgentEnvRemovedNamesTest {
    @TempDir
    lateinit var root: Path

    private fun workflow(
        frontMatter: String,
        env: Map<String, String>,
    ): WorkflowFile {
        val file = root.resolve("WORKFLOW.md")
        file.writeText("---\n$frontMatter---\nWork on {{ issue.identifier }}\n")
        return WorkflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    @Test
    fun `every referenced name is removed except the kept set`() {
        val env =
            mapOf(
                "LOOP_TEST_ISSUES" to root.resolve("issues").toString(),
                "HOME" to root.resolve("home").toString(),
            )
        val frontMatter =
            "tracker:\n" +
                "  kind: files\n" +
                "  provider:\n" +
                "    dir: \$LOOP_TEST_ISSUES\n" +
                "    token: \$LOOP_TEST_TRACKER_TOKEN\n" +
                "    locale: \$LC_ALL\n" +
                "workspace:\n" +
                "  root: \$HOME\n" +
                "hooks:\n" +
                "  before_run: \$LOOP_TEST_HOOK_REF\n" +
                "  after_run: \$PATH\n" +
                "codex:\n" +
                "  turn_sandbox_policy:\n" +
                "    network: \$LOOP_TEST_NESTED_REF\n" +
                "    note: not \$LOOP_TEST_PARTIAL\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: agent.sh\n"

        val removed = agentEnvRemovedNames(workflow(frontMatter, env))

        assertEquals(
            setOf("LOOP_TEST_ISSUES", "LOOP_TEST_TRACKER_TOKEN", "LOOP_TEST_HOOK_REF", "LOOP_TEST_NESTED_REF", "LOOP_HOLDOUT"),
            removed,
        )
    }

    @Test
    fun `LOOP_HOLDOUT is always removed, so a host export never reaches the agent or a visible check (m2-14)`() {
        val frontMatter = "tracker:\n  kind: files\nrunner:\n  kind: command\nrunners:\n  command:\n    command: agent.sh\n"

        assertEquals(setOf("LOOP_HOLDOUT"), agentEnvRemovedNames(workflow(frontMatter, mapOf("LOOP_HOLDOUT" to "/somewhere"))))
    }
}
