package loop.runner.codex

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
import loop.runner.codex.protocol.CodexJson
import kotlin.time.Duration.Companion.milliseconds

/**
 * The merge risk reviewer of Codex (bl-214), the twin of the Claude Code one: one turn of the Codex app server in the
 * issue workspace, run by [CodexAppServerRunner] with [settings], so the startup, the version check, strict MCP, the
 * exec policy rules check and the env policy ([hostEnv] minus [removedEnvNames]) are the runner's. Each review asks for
 * the model of the request and runs [CodexAppServerSettings.readOnly]: the thread is on the `read-only` sandbox, every
 * command and file change approval is declined, and the holdout folder of [settings] is refused by the runner's rules.
 * Codex still runs the commands it holds as known safe (`cat`, `rg`, `git diff`, `git show`) without asking, which is
 * all a reviewer reads with; such a read of the holdout folder is not refused, the gap the Codex runner has (m3-20).
 * No Loop tool is declared, since the review's attempt env is empty.
 *
 * The answer is the text of the turn's last completed agent message, its `final_answer` phase preferred, with the
 * session's tokens from `thread/tokenUsage/updated` and no cost, which Codex does not report. A turn past
 * [RiskReviewRequest.timeoutMs] is stopped, with its app server, and is a [RiskReviewError.Timeout]; a turn that fails,
 * asks for input, cannot start or ends with no agent message is a [RiskReviewError.Crashed].
 */
class CodexRiskReviewer(
    private val settings: CodexAppServerSettings,
    private val hostEnv: Map<String, String> = System.getenv(),
    private val removedEnvNames: Set<String> = emptySet(),
) : RiskReviewer {
    override suspend fun review(request: RiskReviewRequest): Result<RiskReview> {
        val reviewSettings =
            settings.copy(
                model = request.model,
                readOnly = true,
                turnTimeout = request.timeoutMs.coerceIn(1, CodexAppServerSettings.MAX_TIMEOUT_MS).milliseconds,
            )
        val runner = CodexAppServerRunner(reviewSettings, hostEnv, removedEnvNames)
        val answer = Answer()
        val end =
            try {
                withTimeoutOrNull(request.timeoutMs) {
                    var terminal: RunnerEvent? = null
                    runner.run(context(request), request.prompt).collect { event ->
                        answer.read(event)
                        if (event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired) {
                            terminal = event
                        }
                    }
                    terminal
                } ?: return Result.failure(RiskReviewError.Timeout("no answer within ${request.timeoutMs} ms"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(RiskReviewError.Crashed("codex cannot run the review: ${e.message}", e))
            }
        return when (end) {
            is RunnerEvent.TurnCompleted ->
                answer.text?.let { Result.success(RiskReview(it, answer.tokens, null)) }
                    ?: Result.failure(RiskReviewError.Crashed("codex ended the turn with no answer"))
            is RunnerEvent.TurnFailed -> Result.failure(RiskReviewError.Crashed("codex ended in error: ${end.reason.take(DETAIL_MAX)}"))
            is RunnerEvent.TurnInputRequired ->
                Result.failure(RiskReviewError.Crashed("codex asked for input, which a review cannot give: ${end.reason}"))
            else -> Result.failure(RiskReviewError.Crashed("codex ended the turn with no terminal event"))
        }
    }

    /** What the review collected from the runner's events: the agent's answer and the session's tokens. */
    private class Answer {
        private var final: String? = null
        private var last: String? = null
        var tokens: LedgerTokens? = null
            private set

        /** The `final_answer` message when there is one, else the last agent message; null when none completed. */
        val text: String? get() = final ?: last

        fun read(event: RunnerEvent) {
            when (event) {
                is RunnerEvent.Usage ->
                    tokens = LedgerTokens(event.inputTokens, event.outputTokens, event.cacheReadInputTokens, event.cacheCreationInputTokens)
                is RunnerEvent.Output -> if (event.stream == Stream.Stdout) message(event.line)
                else -> Unit
            }
        }

        /** Keeps the text of an `item/completed` agent message on [line], a protocol line of the app server. */
        private fun message(line: String) {
            if (AGENT_MESSAGE !in line) return
            val json = runCatching { CodexJson.format.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
            if (json.string("method") != ITEM_COMPLETED) return
            val item = (json["params"] as? JsonObject)?.get("item") as? JsonObject ?: return
            if (item.string("type") != AGENT_MESSAGE) return
            val text = item.string("text") ?: return
            last = text
            if (item.string("phase") == FINAL_ANSWER) final = text
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The attempt the runner needs: the review's workspace, ids of its own and an empty env, so no Loop tool is declared. */
    private fun context(request: RiskReviewRequest): AttemptContext =
        AttemptContext(
            runId = RunId(REVIEW_ID),
            projectId = ProjectId(REVIEW_ID),
            issue = Issue(REVIEW_ID, REVIEW_ID, "merge risk review", "review", null, emptyList(), emptyList(), null, null),
            attemptId = AttemptId(REVIEW_ID),
            workspace = request.workspace,
            env = emptyMap(),
        )

    override fun toString(): String = "CodexRiskReviewer(command=${settings.command})"

    private companion object {
        const val REVIEW_ID = "merge-risk-review"
        const val ITEM_COMPLETED = "item/completed"
        const val AGENT_MESSAGE = "agentMessage"
        const val FINAL_ANSWER = "final_answer"
        const val DETAIL_MAX = 300
    }
}
