package loop.runner.acp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.api.AgentRunner
import loop.api.AgentRunnerContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Runs the full Loop [AgentRunnerContract] against [AcpRunner] with `preset: agy`. */
class AcpRunnerContractTest : AgentRunnerContract() {
    private var counter = 0

    override fun succeedingRunner(report: Path): AgentRunner {
        val dir = scratch.resolve("contract-ok-${++counter}").createDirectories()
        val script = dir.resolve("agy")
        // Small bash server that handles initialize, session/new or session/load, session/set_model,
        // and any number of session/prompt turns (reporting occupancy + usage on each turn).
        script.writeText(
            """
            #!/bin/bash
            { pwd -P; printf '%s\n' "${'$'}{$PROBE_ENV-}"; } > '$report'
            while IFS= read -r line; do
              case "${'$'}line" in
                *'"method":"initialize"'*)
                  printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Antigravity ACP Server","version":"${AcpSettings.PINNED_AGY_VERSION}"},"agentCapabilities":{"loadSession":true}}}'
                  ;;
                *'"method":"session/new"'*)
                  printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"contract-session-1"}}'
                  ;;
                *'"method":"session/load"'*)
                  printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"contract-session-1"}}'
                  ;;
                *'"method":"session/set_model"'*)
                  printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"modelId":"gemini-4-neon-high"}}'
                  ;;
                *'"method":"session/prompt"'*)
                  id=$(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"contract-session-1","update":{"sessionUpdate":"usage_update","used":1024,"size":1048576}}}'
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn","usage":{"inputTokens":500,"cachedReadTokens":500,"cachedWriteTokens":0,"outputTokens":24,"totalTokens":1024}}}\n' "${'$'}id"
                  ;;
              esac
            done
            exit 0
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return agyRunner("'$script' acp")
    }

    override fun failingRunner(): AgentRunner {
        val server =
            FakeAcpServer.write(scratch.resolve("contract-fail-${++counter}")) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "contract-fail")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "contract-fail")
                awaitClient()
                error(id = 4, code = -32603, message = "Internal agent error")
            }
        return agyRunner(server.agyCommand)
    }

    override fun hangingRunner(pidFile: Path): AgentRunner {
        val server =
            FakeAcpServer.write(scratch.resolve("contract-hang-${++counter}")) {
                shell("sleep 60 &")
                shell("echo \$\$ > '$pidFile'")
                shell("wait")
            }
        return agyRunner(server.agyCommand)
    }

    override fun exitsWithoutResultRunner(): AgentRunner {
        val server =
            FakeAcpServer.write(scratch.resolve("contract-exit-${++counter}")) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "contract-exit")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "contract-exit")
                awaitClient()
                exit(0)
            }
        return agyRunner(server.agyCommand)
    }

    override fun inputRequiredRunner(): AgentRunner {
        val server =
            FakeAcpServer.write(scratch.resolve("contract-input-${++counter}")) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "contract-input")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "contract-input")
                awaitClient()
                request(id = 99, method = "session/request_user_input", params = buildJsonObject { put("prompt", "Need input") })
                shell("sleep 30")
            }
        return agyRunner(server.agyCommand)
    }

    override fun unavailableRunner(): AgentRunner {
        val server =
            FakeAcpServer.write(scratch.resolve("contract-unavail-${++counter}")) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "contract-unavail")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "contract-unavail")
                awaitClient()
                error(id = 4, code = -32000, message = "429 Too Many Requests: quota exceeded")
            }
        return agyRunner(server.agyCommand)
    }

    private fun agyRunner(command: String): AcpRunner {
        val fakeHome = scratch.resolve("home-${++counter}").createDirectories()
        return AcpRunner(
            settings =
                AcpSettings(
                    command = command,
                    preset = AcpSettings.PRESET_AGY,
                    model = "gemini-4-neon-high",
                    readTimeout = FakeAcpServer.READ_TIMEOUT,
                ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to fakeHome.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
        )
    }
}
