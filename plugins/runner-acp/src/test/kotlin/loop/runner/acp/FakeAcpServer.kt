package loop.runner.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Scripted fake ACP server written as an executable bash script (named `agy` by default so it passes
 * the `preset: agy` command allowlist). Every line read from stdin is recorded in [received].
 */
class FakeAcpServer private constructor(
    val script: Path,
    val received: Path,
) {
    /** Command for generic ACP (`strict_mcp: false`). */
    val command: String get() = "'$script'"

    /** Command for `preset: agy` (`<path>/agy acp`). */
    val agyCommand: String get() = "'$script' acp"

    class Script internal constructor(
        private val dir: Path,
        private val received: Path,
    ) {
        private val body = StringBuilder()
        private var files = 0

        /** Reads `initialize` and responds with [protocolVersion] and [agentVersion]. */
        fun initialize(
            id: Long = 1,
            protocolVersion: Int = 1,
            agentName: String = "Antigravity ACP Server",
            agentVersion: String? = AcpSettings.PINNED_AGY_VERSION,
        ) {
            awaitClient()
            response(
                id,
                buildJsonObject {
                    put("protocolVersion", protocolVersion)
                    put(
                        "agentInfo",
                        buildJsonObject {
                            put("name", agentName)
                            if (agentVersion != null) put("version", agentVersion)
                        },
                    )
                },
            )
        }

        /** Reads `session/new` and responds with [sessionId]. */
        fun sessionNew(
            id: Long = 2,
            sessionId: String = SESSION_ID,
            currentModelId: String? = null,
        ) {
            awaitClient()
            response(
                id,
                buildJsonObject {
                    put("sessionId", sessionId)
                    if (currentModelId != null) {
                        put("models", buildJsonObject { put("currentModelId", currentModelId) })
                    }
                },
            )
        }

        /** Reads `session/load` and responds with [sessionId]. */
        fun sessionLoad(
            id: Long = 2,
            sessionId: String = SESSION_ID,
        ) {
            awaitClient()
            response(
                id,
                buildJsonObject {
                    put("sessionId", sessionId)
                },
            )
        }

        /** Reads `session/set_model`, optionally emits `config_option_update`, and responds. */
        fun sessionSetModel(
            id: Long = 3,
            modelId: String? = null,
            configUpdateModelId: String? = null,
            sessionId: String = SESSION_ID,
        ) {
            awaitClient()
            if (configUpdateModelId != null) {
                notification(
                    AcpHandshake.METHOD_SESSION_UPDATE,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put(
                            "update",
                            buildJsonObject {
                                put("sessionUpdate", "config_option_update")
                                put(
                                    "configOptions",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "model")
                                                put("currentValue", configUpdateModelId)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
            response(
                id,
                buildJsonObject {
                    if (modelId != null) put("modelId", modelId)
                },
            )
        }

        /** Reads `session/prompt` and responds with [stopReason]. */
        fun promptTurn(
            id: Long,
            stopReason: String = "end_turn",
            beforeResponse: Script.() -> Unit = {},
        ) {
            awaitClient()
            beforeResponse()
            response(id, buildJsonObject { put("stopReason", stopReason) })
        }

        /** Full startup for generic ACP: `initialize` (1), `session/new` (2), then awaits `session/prompt` (3). */
        fun genericHandshake(
            sessionId: String = SESSION_ID,
            stopReason: String? = "end_turn",
        ) {
            initialize(id = 1)
            sessionNew(id = 2, sessionId = sessionId)
            if (stopReason != null) {
                promptTurn(id = 3, stopReason = stopReason)
            } else {
                awaitClient()
            }
        }

        /** Full startup for `preset: agy`: `initialize` (1), `session/new` (2), `session/set_model` (3), then `session/prompt` (4). */
        fun agyHandshake(
            sessionId: String = SESSION_ID,
            modelId: String = DEFAULT_MODEL,
            stopReason: String? = "end_turn",
        ) {
            initialize(id = 1)
            sessionNew(id = 2, sessionId = sessionId)
            sessionSetModel(id = 3, configUpdateModelId = modelId, sessionId = sessionId)
            if (stopReason != null) {
                promptTurn(id = 4, stopReason = stopReason)
            } else {
                awaitClient()
            }
        }

        fun notification(
            method: String,
            params: JsonObject,
            split: Boolean = false,
        ) = stdout(AcpFraming.notification(method, params).trimEnd('\n'), split)

        fun request(
            id: Long,
            method: String,
            params: JsonObject,
            split: Boolean = false,
        ) = stdout(AcpFraming.request(id, method, params).trimEnd('\n'), split)

        fun response(
            id: Long,
            result: JsonElement,
            split: Boolean = false,
        ) = stdout(AcpFraming.response(kotlinx.serialization.json.JsonPrimitive(id), result).trimEnd('\n'), split)

        fun error(
            id: Long,
            code: Long,
            message: String,
        ) = stdout(AcpFraming.errorResponse(kotlinx.serialization.json.JsonPrimitive(id), code, message).trimEnd('\n'), split = false)

        fun raw(
            line: String,
            split: Boolean = false,
        ) = stdout(line, split)

        fun stderr(line: String) {
            body.append("cat '${file(line + "\n")}' >&2\n")
        }

        fun awaitClient() {
            body.append("IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$received'\n")
        }

        fun shell(command: String) {
            body.append(command).append('\n')
        }

        fun exit(code: Int) {
            body.append("exit $code\n")
        }

        private fun stdout(
            line: String,
            split: Boolean,
        ) {
            if (!split) {
                body.append("cat '${file(line + "\n")}'\n")
                return
            }
            val at = line.length / 2
            body.append("cat '${file(line.substring(0, at))}'\nsleep $SPLIT_PAUSE\ncat '${file(line.substring(at) + "\n")}'\n")
        }

        private fun file(text: String): Path = dir.resolve("line-${++files}.txt").also { it.writeText(text) }

        internal fun text(): String =
            "#!/bin/bash\n" + body +
                "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$received'; done\nexit 0\n"
    }

    companion object {
        const val SESSION_ID: String = "acp-session-0001"
        const val DEFAULT_MODEL: String = "gemini-4-neon-high"
        val READ_TIMEOUT: Duration = 60.seconds
        private const val SPLIT_PAUSE = "0.2"

        fun write(
            dir: Path,
            name: String = "agy",
            steps: Script.() -> Unit,
        ): FakeAcpServer {
            val home = dir.resolve("fake-acp-$name").createDirectories()
            val received = home.resolve("received.jsonl")
            val script = home.resolve(name)
            script.writeText(Script(home, received).apply(steps).text())
            script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            return FakeAcpServer(script, received)
        }
    }
}
