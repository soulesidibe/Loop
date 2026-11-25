package loop.core.replay

import loop.api.AttemptId
import loop.api.RecordedEvent
import loop.core.events.AttemptStarted
import loop.core.events.Delivery
import loop.core.events.HookFinished
import loop.core.events.QuestionAnswered
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.events.SentBack
import loop.core.waiting.SendBacks
import loop.core.workflow.PromptVariables
import loop.core.workspace.HookName

/**
 * The prompt variables of one recorded attempt, as far as the store recorded them (m4-40, story 137). A variable that
 * could not be recovered holds what renders as nothing, null or false, and its template name is in [missing], in the
 * order of [PromptVariables].
 */
data class RecoveredVariables(
    val attempt: Int?,
    val retryKind: String?,
    val verifyFindings: String?,
    val workspaceReused: Boolean,
    val sessionBrief: String?,
    val contextSources: String?,
    val answer: String?,
    val missing: List<String>,
)

/**
 * Recovers the prompt variables an attempt rendered its turn 1 prompt with, from the recorded events of its issue,
 * for `loop replay --prompt` (m4-40). It reads only what the orchestrator already records and guesses nothing: a value
 * no event holds is named as missing. The rules, over the issue's events recorded after the previous attempt of the
 * issue started and before this one did:
 *
 * - `attempt` is the spec retry count of a `retry_scheduled` that no `retry_dropped` followed, else null.
 * - `loop.retry_kind` is `answer` after `question_answered`, `sent_back` after `sent_back`, else the kind of that
 *   retry as the retry coordinator maps it, else null.
 * - `loop.verify_findings` is the reason of a `sent_back` as the orchestrator words it. A refuted or reaction retry
 *   recorded no findings text, so it is missing; any other dispatch had none.
 * - `loop.workspace_reused` is false when this attempt ran `after_create`, which runs only in a new workspace;
 *   otherwise it is missing.
 * - `loop.session_brief` is null on a first dispatch in a new workspace; it is built from the workspace at dispatch
 *   and never recorded, so it is missing otherwise.
 * - `loop.answer` is missing after `question_answered`, which records no text, null otherwise.
 * - `loop.context_sources` is missing when the kind declares context sources, null otherwise.
 */
object RecordedPromptVariables {
    const val ATTEMPT: String = "attempt"
    const val RETRY_KIND: String = "loop.retry_kind"
    const val VERIFY_FINDINGS: String = "loop.verify_findings"
    const val WORKSPACE_REUSED: String = "loop.workspace_reused"
    const val SESSION_BRIEF: String = "loop.session_brief"
    const val CONTEXT_SOURCES: String = "loop.context_sources"
    const val ANSWER: String = "loop.answer"

    /**
     * The variables of [attemptId], from [issueEvents], every recorded event of its issue in recording order, this
     * attempt's included. [declaresContextSources] is true when the kind the attempt rendered for declares context sources.
     */
    fun recover(
        attemptId: AttemptId,
        issueEvents: List<RecordedEvent>,
        declaresContextSources: Boolean,
    ): RecoveredVariables {
        val own = issueEvents.filter { it.attemptId == attemptId }
        val before = window(attemptId, issueEvents)
        val retry =
            before.lastOrNull { it.kind == RetryScheduled.name || it.kind == RetryDropped.name }?.takeIf {
                it.kind ==
                    RetryScheduled.name
            }
        val sentBack = before.lastOrNull { it.kind == SentBack.name }
        val answered = before.any { it.kind == QuestionAnswered.name }
        val missing = mutableListOf<String>()

        val attempt = retry?.data?.get(RetryScheduled.ATTEMPT)?.toIntOrNull()
        val retryKind =
            when {
                answered -> PromptVariables.RETRY_ANSWER
                sentBack != null -> PromptVariables.RETRY_SENT_BACK
                retry != null -> retryKindOf(retry)
                else -> null
            }
        val findings =
            when {
                sentBack != null ->
                    SendBacks.comment(
                        sentBack.data[Delivery.BY].orEmpty().ifBlank {
                            UNKNOWN_HUMAN
                        },
                        sentBack.data[Delivery.REASON].orEmpty(),
                    )
                retryKind == PromptVariables.RETRY_REFUTED || retryKind == PromptVariables.RETRY_REACTION ->
                    null.also {
                        missing +=
                            VERIFY_FINDINGS
                    }
                else -> null
            }
        val createdNow = own.any { it.kind == HookFinished.name && it.data[HookFinished.HOOK] == HookName.AFTER_CREATE.id }
        if (!createdNow) missing += WORKSPACE_REUSED
        if (attempt != null || !createdNow) missing += SESSION_BRIEF
        if (answered) missing += ANSWER
        if (declaresContextSources) missing += CONTEXT_SOURCES
        return RecoveredVariables(
            attempt = attempt,
            retryKind = retryKind,
            verifyFindings = findings,
            workspaceReused = false,
            sessionBrief = null,
            contextSources = null,
            answer = null,
            missing = ORDER.filter { it in missing },
        )
    }

    /**
     * The events of the issue recorded before the first event of [attemptId], after the `attempt_started` of the
     * attempt before it, if any.
     */
    private fun window(
        attemptId: AttemptId,
        issueEvents: List<RecordedEvent>,
    ): List<RecordedEvent> {
        val first = issueEvents.indexOfFirst { it.attemptId == attemptId }.takeIf { it >= 0 } ?: return emptyList()
        val earlier = issueEvents.subList(0, first)
        val previous = earlier.indexOfLast { it.kind == AttemptStarted.name }
        return earlier.subList(previous + 1, earlier.size)
    }

    /** The retry kind of a scheduled retry, as the retry coordinator maps its entry (m3-11). */
    private fun retryKindOf(retry: RecordedEvent): String =
        when {
            retry.data[RetryScheduled.KIND] == RetryScheduled.CONTINUATION -> PromptVariables.RETRY_CONTINUATION
            retry.data[RetryScheduled.ERROR] == PromptVariables.RETRY_REFUTED -> PromptVariables.RETRY_REFUTED
            retry.data[RetryScheduled.ERROR] == PromptVariables.RETRY_REACTION -> PromptVariables.RETRY_REACTION
            else -> PromptVariables.RETRY_ERROR
        }

    /** Who sent it back when the event does not say, as [SendBacks.findings] words it. */
    private const val UNKNOWN_HUMAN = "a human"

    private val ORDER = listOf(ATTEMPT, RETRY_KIND, VERIFY_FINDINGS, WORKSPACE_REUSED, SESSION_BRIEF, CONTEXT_SOURCES, ANSWER)
}
