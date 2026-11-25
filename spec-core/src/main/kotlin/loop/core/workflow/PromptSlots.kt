package loop.core.workflow

import loop.api.Issue
import loop.core.template.Template

/**
 * The size of every slot of a rendered prompt, in the plan's priority order (8.5), in characters since m1-17 and in
 * tokens since m3-13. The verify findings slot is measured since m2-06, the session brief slot since m3-10, the context
 * sources slot since m4-19; until M6 fills it, the lessons slot is empty. Recorded so the context limits can be
 * tuned from data. Since m3-14 the same shape holds the tokens each slot lost when [PromptFill] cut it.
 */
data class PromptSlotSizes(
    /** The template and its rules: everything in the rendered prompt the issue did not add. */
    val template: Int,
    /** What the issue's fields added to the rendered prompt. */
    val issue: Int,
    /** What `loop.verify_findings` added to the rendered prompt (m2-06). */
    val verifyFindings: Int = 0,
    /** What `loop.session_brief` added to the rendered prompt (m3-10). */
    val sessionBrief: Int = 0,
    val lessons: Int = 0,
    /** What `loop.context_sources` added to the rendered prompt (m4-19). */
    val contextSources: Int = 0,
) {
    val total: Int get() = template + issue + verifyFindings + sessionBrief + lessons + contextSources

    /** The `slot_<name>_chars` event data of sizes in characters, in priority order. */
    val data: Map<String, String> get() = data(::key)

    /** The `slot_<name>_tokens` event data of sizes in tokens, in priority order (m3-13). */
    val tokenData: Map<String, String> get() = data(::tokenKey)

    /** The `cut_<name>_tokens` event data of the tokens each slot lost to the fill, in priority order (m3-14). */
    val cutData: Map<String, String> get() = data(::cutKey)

    private fun data(key: (String) -> String): Map<String, String> =
        NAMES
            .zip(listOf(template, issue, verifyFindings, sessionBrief, lessons, contextSources))
            .associateTo(linkedMapOf()) { (name, size) -> key(name) to size.toString() }

    companion object {
        /** The slot names, in priority order, as `loop trace` prints them. */
        val NAMES: List<String> = listOf("template", "issue", "verify_findings", "session_brief", "lessons", "context_sources")

        /** The event data key of the slot [name] in characters. */
        fun key(name: String): String = "slot_${name}_chars"

        /** The event data key of the slot [name] in tokens (m3-13). */
        fun tokenKey(name: String): String = "slot_${name}_tokens"

        /** The event data key of the tokens the fill cut from the slot [name] (m3-14). */
        fun cutKey(name: String): String = "cut_${name}_tokens"
    }
}

/** The slots of one prompt in [chars] and in [tokens], the tokens counted by the counter the measure was given (m3-13). */
data class PromptMeasure(
    val chars: PromptSlotSizes,
    val tokens: PromptSlotSizes,
)

/** The prompt budget data of a `BuildingPrompt` event (m3-13): the prompt's tokens against its limit, and its cuts (m3-14). */
object PromptBudget {
    const val PROMPT_TOKENS = "prompt_tokens"
    const val PROMPT_LIMIT_TOKENS = "prompt_limit_tokens"
    const val OVER_LIMIT = "over_limit"
    const val TOKEN_COUNTER = "token_counter"

    /**
     * `prompt_tokens`, then the `slot_<name>_tokens` of [tokenSlots] when the slots were measured, then
     * `prompt_limit_tokens` and `over_limit` when a [limit] is known, then `token_counter`, the method of [counter], then
     * the `cut_<name>_tokens` of [cuts] when the fill cut the prompt (m3-14).
     */
    fun data(
        prompt: String,
        tokenSlots: PromptSlotSizes?,
        limit: PromptLimit?,
        counter: TokenCounter,
        cuts: PromptSlotSizes? = null,
    ): Map<String, String> {
        val tokens = counter.count(prompt)
        return linkedMapOf<String, String>().apply {
            put(PROMPT_TOKENS, tokens.toString())
            tokenSlots?.let { putAll(it.tokenData) }
            limit?.let {
                put(PROMPT_LIMIT_TOKENS, it.tokens.toString())
                put(OVER_LIMIT, (tokens > it.tokens).toString())
            }
            put(TOKEN_COUNTER, counter.method)
            cuts?.let { putAll(it.cutData) }
        }
    }
}

/**
 * Measures the slots of a prompt the template renders as a whole. The context sources slot is the difference between
 * [rendered] and the same template rendered without the sources (m4-19). The session brief slot is the difference
 * between that render and one without the brief too (m3-10). The verify findings slot is the
 * difference between that render and one without findings too. The issue slot is the difference between that
 * render and one with the issue's fields emptied too: text strings become empty, lists empty and nullable fields
 * null. A field used twice counts twice, and text the template shows only for a present field counts in that
 * field's slot. The template slot is the rest, so the slots always add up to the prompt's length.
 */
object PromptSlots {
    /**
     * The slots of [rendered], the render of [template] for [issue], [attempt], [verifyFindings], [workspaceReused],
     * [sessionBrief] and [contextSources]; null when a render without them fails. Every render keeps [attempt], [workspaceReused] and
     * [retryKind], so what they show counts in the template slot (bl-19, m3-11).
     */
    fun measure(
        template: Template,
        issue: Issue,
        attempt: Int?,
        rendered: String,
        verifyFindings: String? = null,
        workspaceReused: Boolean = false,
        sessionBrief: String? = null,
        retryKind: String? = null,
        contextSources: String? = null,
    ): PromptSlotSizes? =
        renders(template, issue, attempt, rendered, verifyFindings, workspaceReused, sessionBrief, retryKind, contextSources)
            ?.sizes(String::length)

    /**
     * The slots of [rendered] as [measure] finds them, in characters and in tokens of [counter] (m3-13). Each token slot
     * is the difference of the token counts of the same renders, so the token slots add up to the prompt's tokens.
     */
    fun measure(
        template: Template,
        issue: Issue,
        attempt: Int?,
        rendered: String,
        verifyFindings: String? = null,
        workspaceReused: Boolean = false,
        sessionBrief: String? = null,
        retryKind: String? = null,
        counter: TokenCounter,
        contextSources: String? = null,
    ): PromptMeasure? {
        val renders =
            renders(template, issue, attempt, rendered, verifyFindings, workspaceReused, sessionBrief, retryKind, contextSources)
                ?: return null
        return PromptMeasure(renders.sizes(String::length), renders.sizes(counter::count))
    }

    /**
     * The full render and the renders without the context sources, then without the brief, then without the findings,
     * then without the issue too.
     */
    private class Renders(
        val full: String,
        val withoutSources: String,
        val withoutBrief: String,
        val withoutFindings: String,
        val withoutIssue: String,
    ) {
        fun sizes(size: (String) -> Int): PromptSlotSizes {
            val total = size(full)
            val sources = (total - size(withoutSources)).coerceIn(0, total)
            val brief = (size(withoutSources) - size(withoutBrief)).coerceIn(0, total - sources)
            val findings = (size(withoutBrief) - size(withoutFindings)).coerceIn(0, total - sources - brief)
            val issue = (size(withoutFindings) - size(withoutIssue)).coerceIn(0, total - sources - brief - findings)
            return PromptSlotSizes(
                template = total - sources - brief - findings - issue,
                issue = issue,
                verifyFindings = findings,
                sessionBrief = brief,
                contextSources = sources,
            )
        }
    }

    private fun renders(
        template: Template,
        issue: Issue,
        attempt: Int?,
        rendered: String,
        verifyFindings: String?,
        workspaceReused: Boolean,
        sessionBrief: String?,
        retryKind: String?,
        contextSources: String?,
    ): Renders? {
        fun render(
            of: Issue,
            findings: String?,
            brief: String? = null,
        ): String? = template.render(PromptVariables.of(of, attempt, findings, workspaceReused, brief, retryKind)).getOrNull()

        val withoutSources = if (contextSources == null) rendered else render(issue, verifyFindings, sessionBrief) ?: return null
        val withoutBrief = if (sessionBrief == null) withoutSources else render(issue, verifyFindings) ?: return null
        val withoutFindings = if (verifyFindings == null) withoutBrief else render(issue, null) ?: return null
        val withoutIssue = render(emptied(issue), null) ?: return null
        return Renders(rendered, withoutSources, withoutBrief, withoutFindings, withoutIssue)
    }

    /** [issue] with its fields emptied: text strings empty, lists empty and nullable fields null. */
    fun emptied(issue: Issue): Issue =
        issue.copy(
            id = "",
            identifier = "",
            title = "",
            state = "",
            priority = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = null,
            createdAt = null,
        )
}
