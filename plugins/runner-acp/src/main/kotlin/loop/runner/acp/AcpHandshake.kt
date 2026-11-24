package loop.runner.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Why an ACP startup request failed (`response_error`).
 */
internal sealed interface AcpHandshakeFailure {
    val reason: String

    data class ErrorAnswer(
        val method: String,
        val code: Long,
        val message: String,
    ) : AcpHandshakeFailure {
        override val reason: String get() = "response_error: $method failed with code $code: $message"
    }

    data class OutOfOrder(
        val method: String,
        val id: JsonElement,
    ) : AcpHandshakeFailure {
        override val reason: String
            get() = "response_error: an answer to request $id arrived while waiting for the $method response"
    }

    data class InvalidAnswer(
        val method: String,
        val detail: String,
    ) : AcpHandshakeFailure {
        override val reason: String get() = "response_error: the $method response $detail"
    }

    data object UnknownAgyVersion : AcpHandshakeFailure {
        override val reason: String get() = "response_error: the initialize response names no agy version in agentInfo.version"
    }

    data class WrongAgyMajorVersion(
        val found: String,
        val pinned: String,
    ) : AcpHandshakeFailure {
        override val reason: String
            get() = "response_error: agy $found is not of the pinned major version ${pinned.substringBefore('.')} (pinned $pinned)"
    }

    data class ModelMismatch(
        val expected: String,
        val actual: String,
    ) : AcpHandshakeFailure {
        override val reason: String get() = "response_error: session/set_model selected $actual instead of $expected"
    }
}

/**
 * Client state machine for the ACP session startup and turn prompt requests:
 * 1. `initialize` (`protocolVersion: 1`, `clientInfo`, `clientCapabilities` all false)
 * 2. `session/new` (or `session/load` when resuming [resumeFrom])
 * 3. `session/set_model` (under `preset: agy`, verified against `config_option_update` and response)
 * 4. `session/prompt` (turn 1 and each continuation turn on the same session)
 */
internal class AcpHandshake(
    private val workspace: Path,
    private val loopVersion: String,
    private val preset: String?,
    private val configuredModel: String?,
    private val resumeFrom: String? = null,
    private val mcpServers: JsonArray = JsonArray(emptyList()),
    private val pinnedAgyVersion: String = AcpSettings.PINNED_AGY_VERSION,
) {
    sealed interface Step {
        /** Continue reading; write [lines] (if any) and log [notes] on stderr (if any). */
        data class Continue(
            val lines: List<String> = emptyList(),
            val notes: List<String> = emptyList(),
        ) : Step

        /** Startup succeeded and [promptLine] (`session/prompt`) is ready to be written for [acpSessionId]. */
        data class ReadyForPrompt(
            val acpSessionId: String,
            val promptLine: String,
            val notes: List<String> = emptyList(),
        ) : Step

        /** A startup request failed; kill the process and fail the turn. */
        data class Failed(
            val failure: AcpHandshakeFailure,
        ) : Step
    }

    private enum class Phase {
        Idle,
        WaitingInitialize,
        WaitingSession,
        WaitingSetModel,
        WaitingPrompt,
        Ready,
    }

    private val nextId = AtomicLong(1)
    private var phase: Phase = Phase.Idle
    private var pendingId: Long? = null
    private var pendingMethod: String? = null
    private var pendingPromptText: String? = null
    private var observedModelDuringSetModel: String? = null

    /** The ACP session id established by `session/new` or `session/load`. */
    var acpSessionId: String? = null
        private set

    /** The active `session/prompt` request id while a turn is running, else null. */
    var activePromptId: Long? = null
        private set

    /** The startup request method currently awaiting a response, or null once startup finished. */
    val pendingHandshakeMethod: String?
        get() = if (phase == Phase.WaitingPrompt || phase == Phase.Ready || phase == Phase.Idle) null else pendingMethod

    /** True once the ACP session is established and idle between turns. */
    val isReadyForContinuation: Boolean
        get() = phase == Phase.Ready && acpSessionId != null

    /** Begins turn 1 startup for [input]; returns the `initialize` JSON-RPC request line to write. */
    fun start(input: String): String {
        pendingPromptText = escapePrompt(input)
        val id = nextId.getAndIncrement()
        phase = Phase.WaitingInitialize
        pendingId = id
        pendingMethod = METHOD_INITIALIZE
        return AcpFraming.request(
            id = id,
            method = METHOD_INITIALIZE,
            params =
                buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", CLIENT_NAME)
                            put("version", loopVersion)
                        },
                    )
                    put(
                        "clientCapabilities",
                        buildJsonObject {
                            put(
                                "fs",
                                buildJsonObject {
                                    put("readTextFile", false)
                                    put("writeTextFile", false)
                                },
                            )
                            put("terminal", false)
                        },
                    )
                },
        )
    }

    /** Starts a continuation turn (2..n) on the already established [acpSessionId]. */
    fun continuePrompt(input: String): String {
        val sid = checkNotNull(acpSessionId) { "cannot send continuation session/prompt before session is established" }
        val id = nextId.getAndIncrement()
        phase = Phase.WaitingPrompt
        pendingId = null
        pendingMethod = null
        activePromptId = id
        return promptRequest(id, sid, escapePrompt(input))
    }

    /** Marks the active `session/prompt` turn complete so continuation turns can run. */
    fun completePrompt() {
        activePromptId = null
        if (acpSessionId != null) {
            phase = Phase.Ready
        }
    }

    /** Consumes one [frame] during startup phases; ignores notifications except `config_option_update`. */
    fun readStartup(frame: AcpFrame): Step {
        if (phase == Phase.WaitingPrompt || phase == Phase.Ready || phase == Phase.Idle) {
            return Step.Continue()
        }
        if (frame is AcpFrame.Notification) {
            if (phase == Phase.WaitingSetModel) {
                extractModelFromSessionUpdate(frame)?.let { observedModelDuringSetModel = it }
            }
            return Step.Continue()
        }
        if (frame is AcpFrame.Request || frame is AcpFrame.Malformed) {
            return Step.Continue()
        }

        val expectedId = pendingId ?: return Step.Continue()
        val method = pendingMethod ?: return Step.Continue()

        val responseId =
            when (frame) {
                is AcpFrame.Response -> frame.id
                is AcpFrame.Error -> frame.id
            }
        if (responseId == null || !matchesId(responseId, expectedId)) {
            return Step.Failed(AcpHandshakeFailure.OutOfOrder(method, responseId ?: JsonPrimitive("null")))
        }
        if (frame is AcpFrame.Error) {
            return Step.Failed(AcpHandshakeFailure.ErrorAnswer(method, frame.code, frame.message))
        }

        val result = (frame as AcpFrame.Response).result
        val obj =
            result as? JsonObject
                ?: return Step.Failed(AcpHandshakeFailure.InvalidAnswer(method, "is not a JSON object"))

        return when (phase) {
            Phase.WaitingInitialize -> handleInitialize(obj)
            Phase.WaitingSession -> handleSession(method, obj)
            Phase.WaitingSetModel -> handleSetModel(obj)
            else -> Step.Continue()
        }
    }

    private fun handleInitialize(result: JsonObject): Step {
        val protocolVersion = result["protocolVersion"]
        if (protocolVersion != null) {
            val v = (protocolVersion as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (v != PROTOCOL_VERSION) {
                return Step.Failed(
                    AcpHandshakeFailure.InvalidAnswer(METHOD_INITIALIZE, "reported unsupported protocolVersion"),
                )
            }
        }

        val agentInfo = result["agentInfo"] as? JsonObject
        val agentVersion =
            (agentInfo?.get("version") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val notes = mutableListOf<String>()
        if (preset == AcpSettings.PRESET_AGY) {
            if (agentVersion == null) {
                return Step.Failed(AcpHandshakeFailure.UnknownAgyVersion)
            }
            val pinnedMajor = pinnedAgyVersion.substringBefore('.').toInt()
            val foundMajor = agentVersion.substringBefore('.').toIntOrNull()
            if (foundMajor == null || foundMajor != pinnedMajor) {
                return Step.Failed(AcpHandshakeFailure.WrongAgyMajorVersion(agentVersion, pinnedAgyVersion))
            }
            notes += "agy ACP server version $agentVersion"
        } else if (agentVersion != null) {
            val agentName =
                (agentInfo["name"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "ACP server"
            notes += "$agentName version $agentVersion"
        }

        val id = nextId.getAndIncrement()
        phase = Phase.WaitingSession
        pendingId = id
        val line =
            if (resumeFrom != null) {
                pendingMethod = METHOD_SESSION_LOAD
                AcpFraming.request(
                    id = id,
                    method = METHOD_SESSION_LOAD,
                    params =
                        buildJsonObject {
                            put("sessionId", resumeFrom)
                            put("cwd", workspace.toAbsolutePath().toString())
                            put("mcpServers", mcpServers)
                        },
                )
            } else {
                pendingMethod = METHOD_SESSION_NEW
                AcpFraming.request(
                    id = id,
                    method = METHOD_SESSION_NEW,
                    params =
                        buildJsonObject {
                            put("cwd", workspace.toAbsolutePath().toString())
                            put("mcpServers", mcpServers)
                        },
                )
            }
        return Step.Continue(lines = listOf(line), notes = notes)
    }

    private fun handleSession(
        method: String,
        result: JsonObject,
    ): Step {
        val reportedSessionId =
            (result["sessionId"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val sid =
            if (resumeFrom != null) {
                reportedSessionId ?: resumeFrom
            } else {
                reportedSessionId
                    ?: return Step.Failed(AcpHandshakeFailure.InvalidAnswer(method, "is missing sessionId"))
            }
        acpSessionId = sid

        if (preset == AcpSettings.PRESET_AGY) {
            val modelId =
                checkNotNull(configuredModel) { "runners.acp.model is required when runners.acp.preset is agy" }
            val id = nextId.getAndIncrement()
            phase = Phase.WaitingSetModel
            pendingId = id
            pendingMethod = METHOD_SESSION_SET_MODEL
            observedModelDuringSetModel = null
            val line =
                AcpFraming.request(
                    id = id,
                    method = METHOD_SESSION_SET_MODEL,
                    params =
                        buildJsonObject {
                            put("sessionId", sid)
                            put("modelId", modelId)
                        },
                )
            return Step.Continue(lines = listOf(line))
        }

        return sendFirstPrompt(sid)
    }

    private fun handleSetModel(result: JsonObject): Step {
        val expected = checkNotNull(configuredModel) { "runners.acp.model is required when runners.acp.preset is agy" }
        val reported = extractModelFromSetModelResult(result) ?: observedModelDuringSetModel
        if (reported != null && reported != expected) {
            return Step.Failed(AcpHandshakeFailure.ModelMismatch(expected = expected, actual = reported))
        }
        val sid = checkNotNull(acpSessionId)
        return sendFirstPrompt(sid)
    }

    private fun sendFirstPrompt(sid: String): Step {
        val text = checkNotNull(pendingPromptText)
        pendingPromptText = null
        val id = nextId.getAndIncrement()
        phase = Phase.WaitingPrompt
        pendingId = null
        pendingMethod = null
        activePromptId = id
        return Step.ReadyForPrompt(
            acpSessionId = sid,
            promptLine = promptRequest(id, sid, text),
        )
    }

    private fun promptRequest(
        id: Long,
        sessionId: String,
        text: String,
    ): String =
        AcpFraming.request(
            id = id,
            method = METHOD_SESSION_PROMPT,
            params =
                buildJsonObject {
                    put("sessionId", sessionId)
                    put(
                        "prompt",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
        )

    /**
     * Escapes a leading `/` under `preset: agy` so ACP does not interpret the prompt as a slash skill
     * invocation (Spike 0 proved `/tdd` expands skills in `agy acp`).
     */
    private fun escapePrompt(input: String): String =
        if (preset == AcpSettings.PRESET_AGY && input.startsWith("/")) {
            " $input"
        } else {
            input
        }

    companion object {
        const val PROTOCOL_VERSION: Int = 1
        const val CLIENT_NAME: String = "loop"
        const val METHOD_INITIALIZE: String = "initialize"
        const val METHOD_SESSION_NEW: String = "session/new"
        const val METHOD_SESSION_LOAD: String = "session/load"
        const val METHOD_SESSION_SET_MODEL: String = "session/set_model"
        const val METHOD_SESSION_PROMPT: String = "session/prompt"
        const val METHOD_SESSION_UPDATE: String = "session/update"
        const val METHOD_REQUEST_PERMISSION: String = "session/request_permission"

        fun matchesId(
            element: JsonElement,
            expected: Long,
        ): Boolean {
            val primitive = element as? JsonPrimitive ?: return false
            return !primitive.isString && primitive.content == expected.toString()
        }

        private fun extractModelFromSessionUpdate(frame: AcpFrame.Notification): String? {
            if (frame.method != METHOD_SESSION_UPDATE) return null
            val params = frame.params as? JsonObject ?: return null
            val update = params["update"] as? JsonObject ?: return null
            val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNull
            if (kind != "config_option_update") return null
            val options = update["configOptions"] as? JsonArray ?: return null
            return modelFromConfigOptions(options)
        }

        private fun extractModelFromSetModelResult(result: JsonObject): String? {
            (result["modelId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { return it }
            (result["currentModelId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { return it }
            val models = result["models"] as? JsonObject
            (models?.get("currentModelId") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { return it }
            val options = result["configOptions"] as? JsonArray
            return options?.let(::modelFromConfigOptions)
        }

        private fun modelFromConfigOptions(options: JsonArray): String? =
            options
                .mapNotNull { it as? JsonObject }
                .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == "model" }
                ?.get("currentValue")
                ?.let { it as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.contentOrNull
    }
}
