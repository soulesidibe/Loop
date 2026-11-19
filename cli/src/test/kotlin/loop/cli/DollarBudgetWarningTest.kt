package loop.cli

import loop.api.RunnerCapabilities
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * m3-21: a runner that reports no cost, the Codex runner among them, spends nothing against a dollar budget, so a
 * workflow that sets one with such a runner is warned at startup. Token budgets still hold.
 */
class DollarBudgetWarningTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private fun host(): Host =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()),
            launcherProperty = null,
            userHome = "/tmp",
        )

    /** Sets `runner.kind: codex-app-server` and [budgets] under `budgets`. */
    private fun useCodex(budgets: String) {
        val text = fixture.workflow.readText()
        val start = text.indexOf("runner:\n")
        val end = text.indexOf("polling:\n")
        val runner = "runner:\n  kind: codex-app-server\nrunners:\n  codex:\n    command: codex app-server\nbudgets:\n$budgets"
        fixture.workflow.writeText(text.substring(0, start) + runner + text.substring(end))
    }

    private fun codexWarning(): String? {
        val host = host()
        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()
        val runner = runnerFor(workflow, host.runnerPlugins.getOrThrow(), host.rawEnv, agentEnvRemovedNames(workflow))
        return dollarBudgetWarning(workflow, runner.capabilities)
    }

    @Test
    fun `m3-21 startup with the Codex runner and dollar budgets warns that dollar budgets do not hold for it`() {
        useCodex("  per_attempt_usd: 5\n  per_day_usd: 50\n  per_attempt_tokens: 20000000\n")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(
            result.stderr,
            "loop run: warning: budgets.per_attempt_usd and budgets.per_day_usd are set, but the codex-app-server runner " +
                "reports no cost, so dollar budgets do not hold for it: its attempts spend nothing against them. " +
                "Token budgets still hold.",
        )
    }

    @Test
    fun `m3-21 the warning names every dollar budget set and only those`() {
        useCodex("  per_issue_usd: 20\n")

        assertEquals(
            "budgets.per_issue_usd is set, but the codex-app-server runner reports no cost, so dollar budgets do not " +
                "hold for it: its attempts spend nothing against them. Token budgets still hold",
            codexWarning(),
        )
    }

    @Test
    fun `m3-21 the Codex runner with token budgets only gets no warning`() {
        useCodex("  per_attempt_tokens: 20000000\n  per_day_tokens: 200000000\n")

        assertNull(codexWarning())
        val result = fixture.loop("run", "--once")
        assertEquals(0, result.exitCode, result.toString())
        assertFalse("dollar budgets" in result.stderr, result.stderr)
    }

    @Test
    fun `m3-21 a runner that reports a cost gets no warning for its dollar budgets`() {
        useCodex("  per_attempt_usd: 5\n")
        val workflow = host().workflowLoader().load(fixture.workflow).getOrThrow()
        val withCost =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Mcp,
                usage = true,
                cost = true,
                occupancy = true,
                sandbox = false,
            )

        assertNull(dollarBudgetWarning(workflow, withCost))
    }
}
