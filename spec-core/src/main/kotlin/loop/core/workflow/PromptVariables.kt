package loop.core.workflow

import loop.api.Issue
import loop.core.template.TemplateSchema
import loop.core.trackers.NamespacedId

/**
 * The variables the prompt template renders with: the spec's `issue` and
 * `attempt` (5.4), and Loop's own under `loop` (plan 8.9), so far
 * `loop.verify_findings` (m2-06), `loop.workspace_reused` (bl-19), `loop.session_brief` (m3-10), `loop.retry_kind`
 * (m3-11), `loop.context_sources` (m4-19) and `loop.answer` (m4-52), and Loop's `issue.tracker` (m4-12). The Orchestrator
 * and [Preflight] both build
 * them here, and the static check's [schema] is derived from the same map, so
 * they cannot drift.
 */
object PromptVariables {
    /** A sample with every field set except `created_at`, which the files tracker never has. */
    val SAMPLE_ISSUE: Issue =
        Issue(
            id = "default:sample-id",
            identifier = "SAMPLE-1",
            title = "Sample title",
            state = "todo",
            priority = 1,
            labels = listOf("sample"),
            blockedBy = listOf("SAMPLE-2"),
            description = "Sample description",
            createdAt = null,
        )

    /** A sample of `loop.verify_findings`, so preflight also renders the branch a template shows after a refutation. */
    const val SAMPLE_VERIFY_FINDINGS: String = "Check: ./gradlew test\nExit code: 1\nIts output was empty."

    /** A sample of `loop.session_brief`, so preflight also renders the branch a template shows on a retry (m3-10). */
    const val SAMPLE_SESSION_BRIEF: String =
        "### Goal and acceptance criteria\nSAMPLE-1: Sample title\n\n### Agent notes, unverified\nNo notes."

    /** A sample of `loop.context_sources`, so preflight also renders the branch a template shows with sources (m4-19). */
    const val SAMPLE_CONTEXT_SOURCES: String = "- docs/sample.md\n- docs/samples/ (missing from the workspace)"

    /** `loop.retry_kind` after a continuation retry, a normal exit with the issue still active (m3-11). */
    const val RETRY_CONTINUATION: String = "continuation"

    /** `loop.retry_kind` in the first prompt of a new session after a rotation inside the attempt (m3-11). */
    const val RETRY_ROTATION: String = "rotation"

    /** `loop.retry_kind` after an error retry, a failed attempt (m3-11). */
    const val RETRY_ERROR: String = "error"

    /** `loop.retry_kind` after an error retry whose failure was a refutation of the submitted result (m3-11). */
    const val RETRY_REFUTED: String = "refuted"

    /** `loop.retry_kind` in the first prompt after `loop answer` answered the agent's question (m4-52). */
    const val RETRY_ANSWER: String = "answer"

    /**
     * `loop.retry_kind` in the first prompt after `loop send-back` (m4-32): the human's reason is `loop.verify_findings`,
     * the path a refuted merge check takes. The dispatch takes no `max_attempts` slot.
     */
    const val RETRY_SENT_BACK: String = "sent_back"

    /**
     * `loop.retry_kind` on the retry a reaction of `reactions:` scheduled (m4-34, PLAN 6.7): the reaction's findings are
     * `loop.verify_findings`, the conflict findings of the merge check for `merge_conflict`. It takes a `max_attempts` slot.
     */
    const val RETRY_REACTION: String = "reaction"

    /** Every value of `loop.retry_kind` Loop produces, which preflight renders (m3-11); null on a first dispatch. */
    val RETRY_KINDS: List<String> =
        listOf(RETRY_CONTINUATION, RETRY_ROTATION, RETRY_ERROR, RETRY_REFUTED, RETRY_ANSWER, RETRY_SENT_BACK, RETRY_REACTION)

    /** A sample of `loop.answer`, so preflight also renders the branch a template shows after an answer (m4-52). */
    const val SAMPLE_ANSWER: String = "Question for a human (product): Sample question?\n\nAnswer from sample: Sample answer."

    val schema: TemplateSchema =
        TemplateSchema(
            mapOf(
                ISSUE to issueFields(SAMPLE_ISSUE).keys,
                ATTEMPT to null,
                LOOP to loopFields(null, false, null, null, null, null).keys,
            ),
        )

    /**
     * [attempt] is null on a first run, else the retry attempt (set by the retry queue, m1-07). [verifyFindings]
     * is the text of the latest refutation for the attempt after it, null otherwise (m2-06). A null value renders
     * as nothing and is false in a condition, so a template that does not use it renders exactly as before.
     * [workspaceReused] is true when the issue's workspace already existed when the attempt prepared it, false
     * when the attempt created it (bl-19): a fact about the folder, whatever [attempt] is. [sessionBrief] is the
     * [SessionBrief] of a retry, a reused workspace or a rotation, null on a fresh first dispatch (m3-10). [retryKind]
     * is one of [RETRY_KINDS], null on a first dispatch (m3-11). [contextSources] is the list of the kind's context
     * sources, null when the kind declares none (m4-19). [answer] is the agent's question and the human's answer in the
     * first prompt after `loop answer`, null otherwise (m4-52).
     */
    fun of(
        issue: Issue,
        attempt: Int?,
        verifyFindings: String? = null,
        workspaceReused: Boolean = false,
        sessionBrief: String? = null,
        retryKind: String? = null,
        contextSources: String? = null,
        answer: String? = null,
    ): Map<String, Any?> =
        mapOf(
            ISSUE to issueFields(issue),
            ATTEMPT to attempt,
            LOOP to loopFields(verifyFindings, workspaceReused, sessionBrief, retryKind, contextSources, answer),
        )

    private fun loopFields(
        verifyFindings: String?,
        workspaceReused: Boolean,
        sessionBrief: String?,
        retryKind: String?,
        contextSources: String?,
        answer: String?,
    ): Map<String, Any?> =
        mapOf(
            VERIFY_FINDINGS to verifyFindings,
            WORKSPACE_REUSED to workspaceReused,
            SESSION_BRIEF to sessionBrief,
            RETRY_KIND to retryKind,
            CONTEXT_SOURCES to contextSources,
            ANSWER to answer,
        )

    /**
     * The `issue` fields. m4-12: `issue.id` and `issue.identifier` stay native, as the tracker wrote them, and
     * `issue.tracker` is the tracker id of the namespaced id the core carries, null for a bare id, which only a test
     * hands the core.
     */
    private fun issueFields(issue: Issue): Map<String, Any?> =
        mapOf(
            "id" to NamespacedId.nativeOf(issue.id),
            "identifier" to issue.identifier,
            "tracker" to NamespacedId.trackerOf(issue.id),
            "title" to issue.title,
            "description" to issue.description,
            "priority" to issue.priority,
            "state" to issue.state,
            "labels" to issue.labels,
            "blocked_by" to issue.blockedBy,
            "created_at" to issue.createdAt?.toString(),
        )

    private const val ISSUE = "issue"
    private const val ATTEMPT = "attempt"
    private const val VERIFY_FINDINGS = "verify_findings"
    private const val WORKSPACE_REUSED = "workspace_reused"
    private const val SESSION_BRIEF = "session_brief"
    private const val RETRY_KIND = "retry_kind"
    private const val ANSWER = "answer"

    /** The root of Loop's own variables. */
    const val LOOP = "loop"

    /** `loop.context_sources`, the kind's context sources (m4-19). */
    const val CONTEXT_SOURCES = "context_sources"
}
