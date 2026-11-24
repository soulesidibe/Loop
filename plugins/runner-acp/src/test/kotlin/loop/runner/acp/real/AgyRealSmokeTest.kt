package loop.runner.acp.real

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
import loop.runner.acp.AcpRunner
import loop.runner.acp.AcpSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Real integration smoke test for `runner-acp` (`preset: agy`) against a live `agy acp` process.
 * Skipped unless `LOOP_REAL_INTEGRATION` names `agy`; then fails when `agy` or the login is missing
 * or when the turn does not complete with occupancy and usage.
 */
class AgyRealSmokeTest {
    @Test
    fun `a real agy acp session completes a turn with occupancy and usage in a throwaway workspace`() {
        val env = System.getenv()
        RealIntegration.require("agy", env) { AgyRealProfile.missing(env) }
        val command = AgyRealProfile.command(env)
        val model = AgyRealProfile.model(env)
        val id = RealIntegration.runId()
        val workspace = Files.createTempDirectory(Path.of(env["TMPDIR"] ?: "/tmp"), "$id-")
        try {
            git(workspace, "init", "-q")
            workspace.resolve("README.md").writeText("A throwaway workspace for Loop's real integration profile ($id).\n")

            val settings =
                AcpSettings(
                    command = command,
                    preset = AcpSettings.PRESET_AGY,
                    model = model,
                    turnTimeout = TURN_TIMEOUT,
                    readTimeout = READ_TIMEOUT,
                    strictMcp = true,
                )
            val events =
                runBlocking {
                    withTimeout(RUN_CAP) {
                        AcpRunner(settings, hostEnv = env)
                            .run(context(id, workspace), PROMPT)
                            .toList()
                    }
                }

            val summary = events.joinToString("\n") { "  $it" }
            val shown = events.filterNot { it is RunnerEvent.Output }
            println(
                "real agy run $id with `$command` ($model), ${events.size} events, ${events.size - shown.size} raw lines left out:\n" +
                    shown.joinToString("\n") { "  $it" },
            )
            val last = events.last()
            if (last !is RunnerEvent.TurnCompleted) {
                val reason = (last as? RunnerEvent.TurnFailed)?.reason ?: last.toString()
                fail("the real agy turn did not complete: $reason\n$summary")
            }
            assertIs<RunnerEvent.SessionStarted>(events.first(), summary)
            val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>().lastOrNull() ?: fail("no occupancy reported\n$summary")
            assertTrue(occupancy.occupancyTokens > 0, "$occupancy")
            assertEquals("google/$model", occupancy.model)
            val usage = events.filterIsInstance<RunnerEvent.Usage>().lastOrNull() ?: fail("no usage reported\n$summary")
            assertTrue(usage.inputTokens + usage.cacheReadInputTokens > 0 && usage.outputTokens > 0, "$usage")
            assertNull(usage.totalCostUsd)
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
        const val PROMPT = "Reply with the single word hi. Do not run any command, do not edit any file."
        val READ_TIMEOUT = 2.minutes
        val TURN_TIMEOUT = 3.minutes
        val RUN_CAP = 5.minutes
    }
}
