package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m2-09: until a trust check on who wrote or edited an issue exists, `acceptance_commands` runs only with the
 * `files` tracker, so a remote issue body can never run `sh -lc` on the host. The loader still accepts only
 * `tracker.kind: files`, so a remote tracker is built here as the parsed file it will be once it is wired.
 */
class AcceptanceCommandsTrustTest {
    private val folder: Path = createTempDirectory("loop-trust").toRealPath()

    private fun parsed(
        trackerKind: String,
        checks: List<CheckConfig>,
    ) = WorkflowFile(
        config = WorkflowConfig(kinds = KindsConfig(KindConfig(checks))),
        path = folder.resolve("WORKFLOW.md"),
        trackerKind = trackerKind,
        trackerDir = folder.resolve(".loop/issues"),
        activeStates = listOf("open"),
        terminalStates = listOf("closed"),
        requiredLabels = emptyList(),
        workspaceRoot = folder.resolve("workspaces"),
        runner = commandRunner("./agent.sh"),
        pollingIntervalMs = 1000,
        promptTemplate = "Work on {{ issue.identifier }}",
    )

    @Test
    fun `acceptance_commands on a tracker other than files is an invalid config naming the check`() {
        val workflow = parsed("github", listOf(CheckConfig.Command("./gradlew build"), CheckConfig.AcceptanceCommands()))

        assertEquals(
            "kinds.code.verify.checks[1] acceptance_commands needs tracker.kind files, not \"github\", until Loop checks who wrote an issue",
            untrustedAcceptanceCommands(workflow),
        )
    }

    @Test
    fun `the files tracker, or a remote tracker without acceptance_commands, passes`() {
        assertNull(untrustedAcceptanceCommands(parsed("files", listOf(CheckConfig.AcceptanceCommands()))))
        assertNull(untrustedAcceptanceCommands(parsed("github", listOf(CheckConfig.Command("./gradlew build")))))
    }

    @Test
    fun `preflight lets acceptance_commands through on the files tracker`() {
        val path =
            folder.resolve("WORKFLOW.md").also {
                it.writeText(
                    """
                    |---
                    |tracker:
                    |  kind: files
                    |runner:
                    |  kind: command
                    |runners:
                    |  command:
                    |    command: ./agent.sh
                    |kinds:
                    |  code:
                    |    verify:
                    |      checks:
                    |        - { type: acceptance_commands }
                    |---
                    |Work on {{ issue.identifier }}
                    """.trimMargin(),
                )
            }

        val result = runBlocking { Preflight(WorkflowLoader(env = emptyMap()), path, TemplateEngine(), trackerCheck = { null }).check() }

        assertIs<PreflightResult.Ok>(result)
    }
}
