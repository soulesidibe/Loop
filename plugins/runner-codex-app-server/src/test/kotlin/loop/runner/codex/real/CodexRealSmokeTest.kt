package loop.runner.codex.real

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RealIntegration
import loop.api.RunId
import loop.api.RunnerEvent
import loop.runner.codex.CodexAppServerRunner
import loop.runner.codex.CodexAppServerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * m3-24, story 96, spec 17.8 items 1 to 4: the Codex runner against a real `codex app-server` session, the `codex`
 * target of the real integration profile (see [RealIntegration] and `docs/spec-decisions.md`). Skipped unless
 * `LOOP_REAL_INTEGRATION` names `codex`; then it fails when the login or the program is missing, or when the turn does
 * not complete with usage. It makes one real model call on the codex login.
 *
 * The run is isolated: a fresh [RealIntegration.runId] names the workspace, the issue and the attempt, the workspace
 * is a new git repository under the system temporary folder, outside Loop's checkout, and it is removed at the end.
 * Codex itself keeps a rollout of the session under `$CODEX_HOME/sessions`, which the runner cannot prevent.
 */
class CodexRealSmokeTest {
    @Test
    fun `m3-24 17_8 a real codex session completes a turn with usage in a workspace the test removes`() {
        val env = System.getenv()
        RealIntegration.require("codex", env) { CodexRealProfile.missing(env) }
        val command = CodexRealProfile.command(env)
        val id = RealIntegration.runId()
        val workspace = Files.createTempDirectory(Path.of(env["TMPDIR"] ?: "/tmp"), "$id-")
        try {
            git(workspace, "init", "-q")
            workspace.resolve("README.md").writeText("A throwaway workspace for Loop's real integration profile ($id).\n")

            val events =
                runBlocking {
                    withTimeout(RUN_CAP) {
                        CodexAppServerRunner(CodexAppServerSettings(command, turnTimeout = TURN_TIMEOUT), hostEnv = env)
                            .run(context(id, workspace), PROMPT)
                            .toList()
                    }
                }

            // The raw protocol lines (Output events) carry the home folder, the host name and the installation id, so
            // the printed summary, which a human links in a public issue, leaves them out; a failure shows them all.
            val summary = events.joinToString("\n") { "  $it" }
            val shown = events.filterNot { it is RunnerEvent.Output }
            println(
                "m3-24 real codex run $id with `$command`, ${events.size} events, ${events.size - shown.size} raw lines left out:\n" +
                    shown.joinToString("\n") { "  $it" },
            )
            val last = events.last()
            if (last !is RunnerEvent.TurnCompleted) {
                val reason = (last as? RunnerEvent.TurnFailed)?.reason ?: last.toString()
                fail(
                    "the real codex turn did not complete: $reason. If codex refused the model (modelNotFound), set " +
                        "${CodexRealProfile.COMMAND_VARIABLE}='codex app-server -c model=<a model of the login>' (bl-141).\n$summary",
                )
            }
            assertIs<RunnerEvent.SessionStarted>(events.first(), summary)
            val usage = events.filterIsInstance<RunnerEvent.Usage>().lastOrNull() ?: fail("no usage reported\n$summary")
            assertTrue(usage.inputTokens + usage.cacheReadInputTokens > 0 && usage.outputTokens > 0, "$usage")
            assertEquals("?? README.md", git(workspace, "status", "--porcelain").trim(), "the turn changed the workspace")
        } finally {
            workspace.toFile().deleteRecursively()
        }
        assertFalse(workspace.exists(), "$workspace is still there")
    }

    private fun git(
        workspace: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder("git", *args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git ${args.joinToString(" ")} did not end")
        assertEquals(0, process.exitValue(), "git ${args.joinToString(" ")}: $output")
        return output
    }

    private fun context(
        id: String,
        workspace: Path,
    ): AttemptContext =
        AttemptContext(
            runId = RunId(id),
            projectId = ProjectId(id),
            issue =
                Issue(
                    id = id,
                    identifier = id,
                    title = "Real integration smoke test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId(id),
            workspace = workspace,
            env = emptyMap(),
        )

    private companion object {
        /** The `success` prompt of the m3-03 recordings: one word back, nothing run, nothing edited. */
        const val PROMPT = "Reply with the single word hi. Do not run any command, do not edit any file."
        val TURN_TIMEOUT = 3.minutes
        val RUN_CAP = 5.minutes
    }
}
