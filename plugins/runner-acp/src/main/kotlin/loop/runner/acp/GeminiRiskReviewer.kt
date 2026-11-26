package loop.runner.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.RiskReview
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.api.RiskReviewer
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * The merge risk reviewer of Gemini (bl-205), the `agy` twin of the Codex one: one `agy acp` turn under `preset: agy` in
 * the issue workspace, run by [AcpRunner] with [settings], so the version pin, the isolated `--gemini-dir`, `--sandbox`,
 * strict MCP, the protected roots check and the env policy ([hostEnv] minus [removedEnvNames]) are the runner's. Each
 * review sets the model of the request with `session/set_model` and runs [AcpSettings.readOnly]: every command and every
 * write, edit, delete or move is declined, reads and searches are allowed except under the holdout folder of [settings],
 * and no Loop tool is passed. A review never resumes, and its isolated folder is removed when it ends.
 *
 * The answer is the text of the turn's last agent message, as for the Codex reviewer, with the session's tokens and no
 * cost, which ACP does not report. A message is a run of `agent_message_chunk` texts; a tool call, an update of one or a
 * chunk with another `messageId` ends it, and a message with no text is skipped. A turn past [RiskReviewRequest.timeoutMs] is stopped,
 * with its `agy`, and is a [RiskReviewError.Timeout]; a turn that fails, asks for input, cannot start or ends with no
 * agent message, or with no terminal event, is a [RiskReviewError.Crashed].
 */
class GeminiRiskReviewer(
    private val settings: AcpSettings,
    private val hostEnv: Map<String, String> = System.getenv(),
    private val removedEnvNames: Set<String> = emptySet(),
) : RiskReviewer {
    override suspend fun review(request: RiskReviewRequest): Result<RiskReview> {
        val reviewSettings =
            settings.copy(
                preset = AcpSettings.PRESET_AGY,
                model = request.model.trim().lowercase(Locale.ROOT),
                strictMcp = true,
                readOnly = true,
                turnTimeout = request.timeoutMs.coerceIn(1, AcpSettings.MAX_TIMEOUT_MS).milliseconds,
            )
        val runner = AcpRunner(reviewSettings, hostEnv, removedEnvNames)
        val answer = Answer()
        val ended =
            try {
                withTimeoutOrNull(request.timeoutMs) {
                    var terminal: RunnerEvent? = null
                    runner.run(context(request), request.prompt).collect { event ->
                        answer.read(event)
                        if (event.isTerminal()) terminal = event
                    }
                    Ended(terminal)
                } ?: return Result.failure(RiskReviewError.Timeout("no answer within ${request.timeoutMs} ms"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(RiskReviewError.Crashed("agy cannot run the review: ${e.message}", e))
            }
        return when (val end = ended.terminal) {
            is RunnerEvent.TurnCompleted ->
                answer.text?.let { Result.success(RiskReview(it, answer.tokens, null)) }
                    ?: Result.failure(RiskReviewError.Crashed("agy ended the turn with no answer"))
            is RunnerEvent.TurnFailed -> Result.failure(RiskReviewError.Crashed("agy ended in error: ${end.reason.take(DETAIL_MAX)}"))
            is RunnerEvent.TurnInputRequired ->
                Result.failure(RiskReviewError.Crashed("agy asked for input, which a review cannot give: ${end.reason}"))
            else -> Result.failure(RiskReviewError.Crashed("agy ended the turn with no terminal event"))
        }
    }

    /** How the turn's events ended: with the [terminal] event, or null when the runner sent none. */
    private class Ended(
        val terminal: RunnerEvent?,
    )

    /** What the review collected from the runner's events: the agent's last message and the session's tokens. */
    private class Answer {
        private val message = StringBuilder()
        private var messageId: String? = null
        private var last: String? = null
        var tokens: LedgerTokens? = null
            private set

        /** The text of the last agent message that has any; null when the agent wrote none. */
        val text: String? get() = message.toString().takeIf { it.isNotBlank() } ?: last

        fun read(event: RunnerEvent) {
            when (event) {
                is RunnerEvent.Usage ->
                    tokens = LedgerTokens(event.inputTokens, event.outputTokens, event.cacheReadInputTokens, event.cacheCreationInputTokens)
                is RunnerEvent.Output -> if (event.stream == Stream.Stdout) update(event.line)
                else -> Unit
            }
        }

        /**
         * Reads a `session/update` on [line], a protocol line of `agy`: a message chunk adds to the current message, a
         * tool call, an update of one or a chunk of another message ends it.
         */
        private fun update(line: String) {
            if (SESSION_UPDATE !in line) return
            val frame = AcpFraming.decode(line) as? AcpFrame.Notification ?: return
            if (frame.method != AcpHandshake.METHOD_SESSION_UPDATE) return
            val update = (frame.params as? JsonObject)?.get("update") as? JsonObject ?: return
            when (update.string("sessionUpdate")) {
                AGENT_MESSAGE_CHUNK -> {
                    val id = update.string("messageId")
                    if (id != null && id != messageId) endMessage()
                    messageId = id
                    (update["content"] as? JsonObject)?.string("text")?.let { message.append(it) }
                }
                TOOL_CALL, TOOL_CALL_UPDATE -> endMessage()
            }
        }

        /** Ends the current message, kept as the last one when it has any text. */
        private fun endMessage() {
            message.toString().takeIf { it.isNotBlank() }?.let { last = it }
            message.clear()
            messageId = null
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun RunnerEvent.isTerminal(): Boolean =
        this is RunnerEvent.TurnCompleted || this is RunnerEvent.TurnFailed || this is RunnerEvent.TurnInputRequired

    /** The attempt the runner needs: the review's workspace, ids of its own and an empty env, so no Loop tool is passed. */
    private fun context(request: RiskReviewRequest): AttemptContext =
        AttemptContext(
            runId = RunId(REVIEW_ID),
            projectId = ProjectId(REVIEW_ID),
            issue = Issue(REVIEW_ID, REVIEW_ID, "merge risk review", "review", null, emptyList(), emptyList(), null, null),
            attemptId = AttemptId(REVIEW_ID),
            workspace = request.workspace,
            env = emptyMap(),
        )

    override fun toString(): String = "GeminiRiskReviewer(command=${settings.command})"

    private companion object {
        const val REVIEW_ID = "merge-risk-review"
        const val SESSION_UPDATE = "session/update"
        const val AGENT_MESSAGE_CHUNK = "agent_message_chunk"
        const val TOOL_CALL = "tool_call"
        const val TOOL_CALL_UPDATE = "tool_call_update"
        const val DETAIL_MAX = 300
    }
}
