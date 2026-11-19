package loop.runner.claudecode

import loop.api.AgentRunner
import loop.api.AgentRunnerContract
import java.nio.file.Files
import java.nio.file.Path

/** Runs the runner neutral AgentRunner contract against ClaudeCodeRunner, with long lived fake `claude` scripts (m3-07). */
class ClaudeCodeRunnerContractTest : AgentRunnerContract() {
    override fun succeedingRunner(report: Path): AgentRunner =
        runner(
            fixture = "success",
            before = "pwd -P > '$report'; printf '%s\\n' \"\$$PROBE_ENV\" >> '$report'",
        )

    override fun failingRunner(): AgentRunner = runner(fixture = "error_during_execution", after = "exit 1")

    override fun hangingRunner(pidFile: Path): AgentRunner = runner(fixture = null, before = "sleep 68 & echo \$\$ > '$pidFile'; wait")

    // The process exits after its transcript, which has no result line.
    override fun exitsWithoutResultRunner(): AgentRunner = runner(fixture = "no_result", after = "exit 0")

    override fun inputRequiredRunner(): AgentRunner = runner(fixture = "input_required")

    override fun unavailableRunner(): AgentRunner = runner(fixture = "unavailable_429")

    private fun runner(
        fixture: String?,
        before: String = "",
        after: String = "",
    ): AgentRunner {
        val claude = FakeClaude(Files.createTempDirectory(scratch, "claude"), fixture, before, after)
        val host = mapOf("PATH" to System.getenv("PATH"), "HOME" to System.getProperty("user.home"))
        return ClaudeCodeRunner(ClaudeCodeSettings(command = claude.script.toString()), host)
    }
}
