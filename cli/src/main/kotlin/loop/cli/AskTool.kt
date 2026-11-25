package loop.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.mcp.AskCall
import loop.mcp.ToolReply
import java.io.IOException

/*
 * The `ask` tool of `loop tool mcp` (m4-31), for a question a human must answer. Like `comment` it only validates and
 * queues: Loop posts the question and the issue waits as `Waiting(ask)` when the turn ends. Every answer is one JSON
 * object, like the resource tools': `status` is `pending`, or `error` for a structured failure, a tool error with an
 * `error` code and a `message` that queued nothing.
 */

/**
 * Queues [call] for the attempt's issue: its question and options redacted (token patterns) and trimmed, blank options
 * dropped. A second ask of the attempt, one already queued, fails with `already_asked`; an attempt that is not live
 * fails through [AttemptTool.refuse], as for `comment`.
 */
internal suspend fun AttemptTool.queueAsk(
    env: AttemptTool.Env,
    call: AskCall,
): ToolReply {
    val question = redact(call.question).trim()
    if (question.isEmpty()) return structuredFailure("empty_question", "The question is empty. Write the question a human must answer.")
    if (alreadyAsked(env)) {
        return structuredFailure(
            "already_asked",
            "You already asked a question in this attempt, and Loop posts one question per attempt. End your turn now.",
        )
    }
    val options = call.options.map { redact(it).trim() }.filter { it.isNotEmpty() }
    val recommendation = call.recommendation?.let { redact(it).trim() }?.takeIf { it.isNotEmpty() }
    openStore(env).use { store -> liveAttempt(store, env) }
    enqueue(env, ToolRequest.Ask(call.kind, question, options, recommendation), "the question on ${env.issueName}")
    return ToolReply(
        structuredJson(
            STATUS_PENDING,
            "Queued your ${call.kind} question for ${env.issueName}. End your turn now: when it ends, Loop posts the " +
                "question on the issue, ends this attempt without verification and keeps your workspace, and the issue " +
                "waits for a human's answer. Do not call ask again.",
        ),
    )
}

/** True when the attempt's queue already holds an `ask`. A queue that cannot be read holds none. */
private fun alreadyAsked(env: AttemptTool.Env): Boolean =
    try {
        ToolRequestQueue(env.attemptDir).pending().any { it is QueuedRequest.Valid && it.request is ToolRequest.Ask }
    } catch (e: IOException) {
        false
    }

/** A structured failure of a tool that answers JSON (m4-31, m4-33): status `error`, its [code] and [message], nothing queued. */
internal fun AttemptTool.structuredFailure(
    code: String,
    message: String,
): ToolReply = ToolReply(structuredJson(STATUS_ERROR, "$message ${AttemptTool.NOTHING_QUEUED}", "error" to code), isError = true)

/** The JSON answer of a tool that answers JSON (m4-31, m4-33), redacted: [status], the [fields], then [message]. */
internal fun AttemptTool.structuredJson(
    status: String,
    message: String,
    vararg fields: Pair<String, String>,
): String {
    val all = listOf("status" to status) + fields + ("message" to message)
    return redact(JsonObject(all.associate { (key, value) -> key to JsonPrimitive(value) }).toString())
}

private const val STATUS_PENDING = "pending"
private const val STATUS_ERROR = "error"
