package loop.runner.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.api.AgentRunner
import loop.api.AgentRunnerContract
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCResponse
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.CodexErrorInfo
import loop.runner.codex.protocol.v2.ThreadTokenUsage
import loop.runner.codex.protocol.v2.ThreadTokenUsageUpdatedNotification
import loop.runner.codex.protocol.v2.TokenUsageBreakdown
import loop.runner.codex.protocol.v2.TurnStatus
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * The runner neutral [AgentRunnerContract] on [CodexAppServerRunner] (bl-212), now that it overrides
 * [AgentRunner.resumeSession]. The succeeding agent is a bash app server that answers by method, any number of turns,
 * on a started or a resumed thread; the other agents are scripted [FakeAppServer]s.
 */
class CodexAppServerRunnerContractTest : AgentRunnerContract() {
    private var counter = 0

    override fun succeedingRunner(report: Path): AgentRunner {
        val home = scratch.resolve("contract-ok-${++counter}").createDirectories()
        val templates =
            mapOf(
                "initialize" to response(FakeAppServer.initializeResult()),
                "thread-start" to response(FakeAppServer.threadStartResult(THREAD)),
                "thread-resume" to response(FakeAppServer.threadResumeResult(THREAD)),
                "turn-start" to response(FakeAppServer.turnStartResult(TURN)),
                "usage" to
                    CodexJson.format.encodeToString(
                        ServerNotification.serializer(),
                        ServerNotification.ThreadTokenUsageUpdated(
                            params = ThreadTokenUsageUpdatedNotification(THREAD, ThreadTokenUsage(last = TOKENS, total = TOKENS), TURN),
                        ),
                    ),
                "completed" to
                    CodexJson.format.encodeToString(
                        ServerNotification.serializer(),
                        FakeAppServer.turnCompletedNotification(turnId = TURN, threadId = THREAD),
                    ),
            ).mapValues { (name, text) -> home.resolve("$name.json").also { it.writeText(text + "\n") } }
        val script = home.resolve("app-server.sh")
        // Each answer takes the request's id; each turn gets its own id, so turn 2 is a new turn on the same thread.
        script.writeText(
            """
            #!/bin/bash
            { pwd -P; printf '%s\n' "${'$'}{$PROBE_ENV-}"; } > '$report'
            n=0
            answer() { sed -e "s/\"id\":$ID/\"id\":${'$'}2/" -e "s/$TURN/$TURN-${'$'}n/g" "${'$'}1"; }
            while IFS= read -r line; do
              id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
              case "${'$'}line" in
                *'"method":"initialize"'*) answer '${templates.getValue("initialize")}' "${'$'}id" ;;
                *'"method":"thread/start"'*) answer '${templates.getValue("thread-start")}' "${'$'}id" ;;
                *'"method":"thread/resume"'*) answer '${templates.getValue("thread-resume")}' "${'$'}id" ;;
                *'"method":"turn/start"'*)
                  n=${'$'}((n + 1))
                  answer '${templates.getValue("turn-start")}' "${'$'}id"
                  answer '${templates.getValue("usage")}' 0
                  answer '${templates.getValue("completed")}' 0
                  ;;
              esac
            done
            exit 0
            """.trimIndent() + "\n",
        )
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return runner("'$script'")
    }

    override fun failingRunner(): AgentRunner =
        fake {
            handshake()
            turnCompleted(status = TurnStatus.FAILED, error = "the model gave up")
        }

    override fun hangingRunner(pidFile: Path): AgentRunner = fake { shell("sleep 1000 &\necho \$\$ > '$pidFile'\nwait") }

    override fun exitsWithoutResultRunner(): AgentRunner =
        fake {
            handshake()
            exit(0)
        }

    override fun inputRequiredRunner(): AgentRunner =
        fake {
            handshake()
            serverRequest(
                7,
                "item/tool/requestUserInput",
                buildJsonObject {
                    put("itemId", "item-1")
                    put("threadId", FakeAppServer.THREAD_ID)
                    put("turnId", FakeAppServer.TURN_ID)
                    put("isBlocking", true)
                    put(
                        "questions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", "q1")
                                    put("header", "Branch")
                                    put("question", "Which branch?")
                                },
                            )
                        },
                    )
                },
            )
            awaitClient()
        }

    override fun unavailableRunner(): AgentRunner =
        fake {
            handshake()
            turnCompleted(status = TurnStatus.FAILED, error = "stream disconnected", errorInfo = CodexErrorInfo.HttpConnectionFailed())
        }

    private fun fake(steps: FakeAppServer.Script.() -> Unit): AgentRunner =
        runner(FakeAppServer.write(scratch.resolve("contract-${++counter}"), steps).command)

    private fun runner(command: String): CodexAppServerRunner =
        CodexAppServerRunner(
            CodexAppServerSettings(command, turnTimeout = 10.seconds, readTimeout = FakeAppServer.READ_TIMEOUT, strictMcp = false),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to scratch.resolve("home").createDirectories().toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
        )

    private fun response(result: kotlinx.serialization.json.JsonElement): String =
        CodexJson.format.encodeToString(JSONRPCResponse.serializer(), JSONRPCResponse(JsonPrimitive(ID), result))

    private companion object {
        const val THREAD = "019a0000-0000-7000-8000-0000000000c1"
        const val TURN = "019a0000-0000-7000-8000-0000000000c2"

        /** The id the templates are written with, replaced by the id of the request each answers. */
        const val ID = 999_999L

        val TOKENS =
            TokenUsageBreakdown(cachedInputTokens = 0, inputTokens = 1_000, outputTokens = 10, reasoningOutputTokens = 0, totalTokens = 1_010)
    }
}
