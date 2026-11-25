package loop.runner.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What one stdout line of `claude -p --input-format stream-json --output-format stream-json --verbose` means to the runner. */
internal sealed interface StreamLine {
    /** The `system` `init` event: the session started, with Claude Code's own session id and its [model] (m3-08). */
    data class Init(
        val runtimeSessionId: String?,
        val model: String? = null,
    ) : StreamLine

    /**
     * The final `result` event of the turn; [text] is its `result` field. [wake] is true for the result of a wake,
     * a run Claude Code started on its own for a background task's notification: its `origin.kind` is
     * `task-notification` (m4-03). [apiError] is the result's `api_error`, such as `connection_failed` for a lost
     * network (m4-08).
     */
    data class Result(
        val subtype: String?,
        val isError: Boolean,
        val usage: UsageTotals?,
        val permissionDenials: List<Denial> = emptyList(),
        val text: String? = null,
        val sessionId: String? = null,
        val wake: Boolean = false,
        val apiError: String? = null,
    ) : StreamLine

    /** A background task run (a subagent, a background command) started: the `system` `task_started` line (m4-03). */
    data class TaskStarted(
        val run: TaskRun,
    ) : StreamLine

    /** A background task run ended and the session was told: the `system` `task_notification` line (m4-03). */
    data class TaskNotification(
        val run: TaskRun,
    ) : StreamLine

    /** One run of a background task. A restarted task keeps its [taskId] and gets a new [runId]. */
    data class TaskRun(
        val taskId: String?,
        val runId: String?,
    )

    /**
     * A tool call Claude Code refused, from the result's `permission_denials`: a Bash [command], or the [path] a
     * file tool was given (`file_path`, `notebook_path` or `path` of its input) and the [pattern] of a Grep or Glob.
     */
    data class Denial(
        val toolName: String,
        val command: String?,
        val path: String? = null,
        val pattern: String? = null,
    ) {
        /**
         * True for a Bash command that holds a command the generated deny rules block anywhere in its text, a policy
         * denial, see [ClaudeSettingsFile.isDenied].
         */
        val isDenyListed: Boolean
            get() = toolName == BASH && command?.let { ClaudeSettingsFile.isDenied(it) } == true

        private companion object {
            const val BASH = "Bash"
        }
    }

    /**
     * Token totals: those of one `result` line's segment as parsed, subagents included when Claude Code reports them
     * (bl-71), or a sum the runner made of them. The cost is Claude Code's `total_cost_usd`, the session's so far, never summed.
     */
    data class UsageTotals(
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadInputTokens: Long,
        val cacheCreationInputTokens: Long,
        val totalCostUsd: Double?,
    )

    /**
     * An `assistant` message line with its per message [usage] (m1-38). Claude Code writes one line per
     * content block, each with the message's [messageId]. [parentToolUseId] is set for a subagent's
     * message, null for the main session's.
     */
    data class Assistant(
        val messageId: String?,
        val parentToolUseId: String?,
        val usage: UsageTotals?,
    ) : StreamLine

    /**
     * A `rate_limit_event` line (bl-209): the `status` of its `rate_limit_info`, such as `allowed` or `rejected`, and
     * its `unifiedWindows` by name, such as `five_hour` or `seven_day`. A window that is not an object is left out.
     */
    data class RateLimit(
        val status: String?,
        val windows: Map<String, Window>,
    ) : StreamLine {
        /** One unified window: its [utilization], 0 to 1, and the Unix second it [resetsAt], each null when not given. */
        data class Window(
            val utilization: Double?,
            val resetsAt: Long?,
        )
    }

    /** A line that is not a JSON object. It is kept as output and never fails the turn. */
    data object Malformed : StreamLine

    /** Any other event, such as `user` messages. */
    data object Other : StreamLine
}

/**
 * Recognizes a runner access failure in the text of an error result (bl-39): an authentication or access refusal
 * (401, 403, a Cloudflare block), an invalid key or an expired login, a rate limit (429, a usage limit), an
 * overload (529), or a lost network (m4-08, bl-123): the API server out of reach, a DNS lookup that failed
 * (`ENOTFOUND`, `EAI_AGAIN`), a refused, timed out or reset connection. A failure after an idle sleep surfaces as
 * one of these network errors (bl-156). Claude Code types only the lost network, as `api_error` `connection_failed`,
 * so the text is read too, and only on a `result` line with `is_error` set, never on the agent's own text or a tool
 * output.
 */
internal object RunnerAccess {
    private val unavailable =
        listOf(
            """\bAPI Error:?\s*(401|403|429|529)\b""",
            """failed to authenticate""",
            """authentication_error""",
            """invalid api key""",
            """please run /login""",
            """\b(token|login|session|credentials?) (has )?expired\b""",
            """rate_limit_error|\brate limit""",
            """usage limit reached""",
            """overloaded""",
            """\b(ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ECONNRESET|ETIMEDOUT)\b""",
            """can.t reach the api server""",
            """\bAPI Error:?\s*Connection error\b""",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val retryAfter = Regex("""\bretry[- ]after:?\s*(\d{1,6})""", RegexOption.IGNORE_CASE)

    /** The `api_error` of a result that could not reach the API server at all (m4-08). */
    private const val CONNECTION_FAILED = "connection_failed"

    /** True when [text] says the runner cannot use its model right now. */
    private fun isUnavailable(text: String?): Boolean = text != null && unavailable.any { it.containsMatchIn(text) }

    /** True when the error [result] says the runner cannot use its model right now, by its `api_error` or its text. */
    fun isUnavailable(result: StreamLine.Result): Boolean =
        result.isError && (result.apiError == CONNECTION_FAILED || isUnavailable(result.text))

    /** The wait the error asked for, from a `retry-after: <seconds>` in [text], null when it gives none. */
    fun retryAfter(text: String?): Duration? =
        text
            ?.let(retryAfter::find)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.seconds
}

/**
 * Reads stream-json lines defensively: every field is optional, unknown fields are ignored,
 * and a line that is not JSON is [StreamLine.Malformed].
 */
internal object StreamJsonMapper {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(line: String): StreamLine {
        val event = parseObject(line) ?: return StreamLine.Malformed
        val type = event.string("type")
        val subtype = event.string("subtype")
        return when {
            type == "system" && subtype == "init" -> StreamLine.Init(event.string("session_id"), event.string("model"))
            type == "result" ->
                StreamLine.Result(
                    subtype = subtype,
                    isError = event.boolean("is_error") ?: false,
                    usage = usageOf(event),
                    permissionDenials = denialsOf(event),
                    text = event.string("result"),
                    sessionId = event.string("session_id"),
                    wake = (event["origin"] as? JsonObject)?.string("kind") == WAKE_ORIGIN,
                    apiError = event.string("api_error"),
                )
            type == RATE_LIMIT_EVENT -> rateLimitOf(event)
            type == "system" && subtype == "task_started" -> StreamLine.TaskStarted(taskRun(event))
            type == "system" && subtype == "task_notification" -> StreamLine.TaskNotification(taskRun(event))
            type == "assistant" -> {
                val message = event["message"] as? JsonObject
                StreamLine.Assistant(
                    messageId = message?.string("id"),
                    parentToolUseId = event.string("parent_tool_use_id"),
                    usage = (message?.get("usage") as? JsonObject)?.let { totalsOf(it, totalCostUsd = null) },
                )
            }
            else -> StreamLine.Other
        }
    }

    /**
     * The events that end the turn [result] ends. [exitCode] is the process's, null while it still runs, as a long
     * lived process does at the end of a turn (m3-07): a completed turn then reports 0, a failed one null. [hidden]
     * holds the folders the generated settings deny reads and edits under (m2-14).
     */
    fun turnEvents(
        result: StreamLine.Result,
        sessionId: SessionId,
        turnId: TurnId,
        exitCode: Int?,
        hidden: HiddenFolders = HiddenFolders.NONE,
    ): List<RunnerEvent> {
        val failed = result.isError || result.subtype != SUCCESS
        // A denied deny-listed command, or a denial that touches a hidden folder, never needs input:
        // the agent saw the denial, and the result alone decides whether the turn failed.
        val needingInput = result.permissionDenials.firstOrNull { !it.isDenyListed && !hidden.covers(it) }
        val terminal =
            when {
                needingInput != null -> RunnerEvent.TurnInputRequired(sessionId, turnId, reason = needingInput.toolName)
                failed -> {
                    // bl-39: only Claude Code's own error result can say the runner cannot reach its model.
                    val unavailable = RunnerAccess.isUnavailable(result)
                    RunnerEvent.TurnFailed(
                        sessionId,
                        turnId,
                        exitCode,
                        reason = "turn_failed: ${failureDetail(result)}",
                        category = if (unavailable) RunnerEvent.TurnFailed.Category.RunnerUnavailable else null,
                        retryAfter = if (unavailable) RunnerAccess.retryAfter(result.text) else null,
                    )
                }
                else -> RunnerEvent.TurnCompleted(sessionId, turnId, exitCode ?: 0)
            }
        return listOfNotNull(result.usage?.let { usageEvent(it, sessionId, turnId) }, terminal)
    }

    /**
     * The stream-json user message that carries [text] to a process started with `--input-format stream-json`, on
     * one line: newlines in [text] are escaped (m3-07).
     */
    fun userMessage(text: String): String =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("user"),
                "message" to
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("user"),
                            "content" to
                                JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                        ),
                    ),
            ),
        ).toString()

    /**
     * The stream-json control request that interrupts the turn a process started with `--input-format stream-json`
     * runs (m3-12). Claude Code answers with a `control_response` line, then ends the turn with a `result` line of
     * subtype `error_during_execution` that carries the turn's usage and the session's cost (seen with Claude Code
     * 2.1.319).
     */
    fun interruptRequest(): String =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("control_request"),
                "request_id" to JsonPrimitive("loop-interrupt-${UUID.randomUUID()}"),
                "request" to JsonObject(mapOf("subtype" to JsonPrimitive("interrupt"))),
            ),
        ).toString()

    /** The real usage report of [totals], absolute for the session so far. */
    fun usageEvent(
        totals: StreamLine.UsageTotals,
        sessionId: SessionId,
        turnId: TurnId,
    ): RunnerEvent.Usage =
        RunnerEvent.Usage(
            sessionId = sessionId,
            turnId = turnId,
            inputTokens = totals.inputTokens,
            outputTokens = totals.outputTokens,
            cacheReadInputTokens = totals.cacheReadInputTokens,
            cacheCreationInputTokens = totals.cacheCreationInputTokens,
            totalCostUsd = totals.totalCostUsd,
        )

    /**
     * The result's token totals for one segment of the process, the work since its previous `result` line, never a
     * sum of the per message usage. Each turn of a long lived process, and each wake of an agent by a background
     * task, ends a segment with its own `result` line, so the runner sums them across the session (bl-25, m3-07).
     * They are `model_usage_since_last_result` summed over its models when it names one, which counts every model
     * and every subagent of the segment (bl-71), else the result's `usage`, which leaves the subagents out. Not
     * `modelUsage`: it is the session's so far, restored by `--resume`. Its `total_cost_usd` is the session's so far
     * and covers the subagents. Null when the result has neither.
     */
    fun usageOf(result: JsonObject): StreamLine.UsageTotals? {
        val cost = result.number("total_cost_usd")
        val models = (result["model_usage_since_last_result"] as? JsonObject)?.values?.filterIsInstance<JsonObject>().orEmpty()
        if (models.isNotEmpty()) {
            return StreamLine.UsageTotals(
                inputTokens = models.sumOf { it.long("inputTokens") ?: 0 },
                outputTokens = models.sumOf { it.long("outputTokens") ?: 0 },
                cacheReadInputTokens = models.sumOf { it.long("cacheReadInputTokens") ?: 0 },
                cacheCreationInputTokens = models.sumOf { it.long("cacheCreationInputTokens") ?: 0 },
                totalCostUsd = cost,
            )
        }
        val usage = result["usage"] as? JsonObject ?: return null
        return totalsOf(usage, totalCostUsd = cost)
    }

    private fun totalsOf(
        usage: JsonObject,
        totalCostUsd: Double?,
    ): StreamLine.UsageTotals =
        StreamLine.UsageTotals(
            inputTokens = usage.long("input_tokens") ?: 0,
            outputTokens = usage.long("output_tokens") ?: 0,
            cacheReadInputTokens = usage.long("cache_read_input_tokens") ?: 0,
            cacheCreationInputTokens = usage.long("cache_creation_input_tokens") ?: 0,
            totalCostUsd = totalCostUsd,
        )

    private fun rateLimitOf(event: JsonObject): StreamLine.RateLimit {
        val info = event["rate_limit_info"] as? JsonObject
        val windows = info?.get("unifiedWindows") as? JsonObject
        return StreamLine.RateLimit(
            status = info?.string("status"),
            windows =
                windows
                    .orEmpty()
                    .mapNotNull { (name, window) ->
                        (window as? JsonObject)?.let { name to StreamLine.RateLimit.Window(it.number("utilization"), it.long("resetsAt")) }
                    }.toMap(),
        )
    }

    private fun denialsOf(result: JsonObject): List<StreamLine.Denial> {
        val denials = result["permission_denials"] as? JsonArray ?: return emptyList()
        return denials.filterIsInstance<JsonObject>().map { denial ->
            val input = denial["tool_input"] as? JsonObject
            StreamLine.Denial(
                toolName = denial.string("tool_name") ?: "unknown",
                command = input?.string("command"),
                path = input?.let { it.string("file_path") ?: it.string("notebook_path") ?: it.string("path") },
                pattern = input?.string("pattern"),
            )
        }
    }

    /**
     * What went wrong in a failed result: for an error result, the first line of its text,
     * capped at [MAX_DETAIL] characters (an auth failure has subtype `success` and says
     * "Invalid API key" there), else the subtype.
     */
    private fun failureDetail(result: StreamLine.Result): String {
        val firstLine =
            result.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.take(MAX_DETAIL)
        return firstLine?.takeIf { result.isError && it.isNotEmpty() } ?: result.subtype ?: "unknown"
    }

    private const val SUCCESS = "success"
    private const val RATE_LIMIT_EVENT = "rate_limit_event"

    /** The `origin.kind` Claude Code gives the result of a wake it ran for a background task's notification. */
    private const val WAKE_ORIGIN = "task-notification"
    private const val MAX_DETAIL = 200

    private fun parseObject(line: String): JsonObject? =
        try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private fun taskRun(event: JsonObject): StreamLine.TaskRun = StreamLine.TaskRun(event.string("task_id"), event.string("run_id"))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)
            ?.takeIf {
                !it.isString
            }?.contentOrNull
            ?.toDoubleOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)
            ?.takeIf {
                !it.isString
            }?.contentOrNull
            ?.toBooleanStrictOrNull()
}
