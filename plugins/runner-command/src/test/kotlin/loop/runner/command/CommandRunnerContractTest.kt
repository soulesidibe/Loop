package loop.runner.command

import loop.api.AgentRunner
import loop.api.AgentRunnerContract
import java.nio.file.Path

/** Runs the runner neutral AgentRunner contract against CommandRunner, with shell commands as agents. */
class CommandRunnerContractTest : AgentRunnerContract() {
    override fun succeedingRunner(report: Path): AgentRunner =
        CommandRunner("pwd -P > '$report'; printf '%s\\n' \"\$$PROBE_ENV\" >> '$report'; exit 0")

    override fun failingRunner(): AgentRunner = CommandRunner("echo failing >&2; exit 3")

    override fun hangingRunner(pidFile: Path): AgentRunner = CommandRunner("sleep 68 & echo \$\$ > '$pidFile'; wait")
}
