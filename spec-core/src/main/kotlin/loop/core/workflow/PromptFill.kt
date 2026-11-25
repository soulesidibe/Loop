package loop.core.workflow

import loop.api.Issue
import loop.core.template.Template

/**
 * What a prompt renders from that the fill may cut (m3-14): the issue, the verify findings, the session brief, and since
 * m4-19 the list of the kind's context sources.
 */
data class PromptContent(
    val issue: Issue,
    val verifyFindings: String?,
    val sessionBrief: String?,
    val contextSources: String? = null,
)

/**
 * A prompt as Loop sends it (m3-14): the [prompt] rendered from [content], the content after the cuts. [cuts] holds the
 * tokens each slot lost, null when the rendered prompt was within its limit and nothing was cut.
 */
data class FilledPrompt(
    val prompt: String,
    val content: PromptContent,
    val cuts: PromptSlotSizes?,
)

/**
 * Fills a prompt slot by slot in the priority order of plan 8.5 (m3-14). A prompt within its limit is sent as rendered.
 * Over it, each slot takes the room the slots before it left, and a slot that does not fit is cut by its rule:
 *
 * 1. the template and its rules are never cut (preflight fails when they alone are over the limit, m3-13);
 * 2. the issue's description is cut, at a line end when one fits, with a note giving how much is left out and a
 *    pointer to the full text;
 * 3. the verify findings keep their head (the lines up to the one that opens their list, the check and its exit code)
 *    and their newest lines, the last ones, and the older lines are summarized in one line; findings whose head does
 *    not fit become [FINDINGS_LEFT_OUT];
 * 4. the session brief keeps every part's heading and, each part cut to the same share of its length, the first lines
 *    of its body, or its first line and its last lines for a part whose newest facts come last
 *    ([SessionBrief.NEWEST_LAST_PARTS]: the latest findings, the newest progress);
 * 5. the lessons stay empty until M6, so they measure 0 and are never cut;
 * 6. the context sources keep their first lines, one source each, then a line saying how many are left out; when not
 *    even that line fits they are left out (m4-19).
 *
 * A prompt whose slots are all at their smallest can still be over its limit; it is sent so, and the caller records it.
 */
object PromptFill {
    /** The verify findings when not even their head fits. */
    const val FINDINGS_LEFT_OUT: String =
        "Your last result was refuted. Its findings are left out to fit the prompt limit: run the checks to see what failed."

    /** The line that ends a part of the session brief cut to fit. */
    const val BRIEF_CUT: String = "(cut to fit the prompt limit)"

    /** The line that stands for the older lines of a part of the session brief whose newest lines are kept. */
    const val BRIEF_OLDER_CUT: String = "(older lines cut to fit the prompt limit)"

    /** The line that ends a list of context sources cut to fit, [n] sources being left out. */
    fun sourcesLeftOut(n: Int): String =
        if (n == 1) "(1 more context source is left out to fit the prompt limit.)" else "($n more context sources are left out to fit the prompt limit.)"

    /** How many lines of the findings may form their head, at most. */
    private const val FINDINGS_HEAD_MAX = 5

    private const val PERMILLE = 1000

    /**
     * The prompt [template] renders from [content] with [attempt], [workspaceReused] and [retryKind], filled to [limit]
     * tokens of [counter]. [pointer] says where the full issue is, for example `at <url>`, and ends the note of a cut
     * description: `Read the full text <pointer>.`. Null when a render fails.
     */
    fun fill(
        template: Template,
        content: PromptContent,
        attempt: Int?,
        workspaceReused: Boolean,
        retryKind: String?,
        counter: TokenCounter,
        limit: Long,
        pointer: String,
    ): FilledPrompt? {
        fun render(of: PromptContent): String? =
            template
                .render(PromptVariables.of(of.issue, attempt, of.verifyFindings, workspaceReused, of.sessionBrief, retryKind, of.contextSources))
                .getOrNull()

        val rendered = render(content) ?: return null
        if (counter.count(rendered) <= limit) return FilledPrompt(rendered, content, null)

        val renders = mutableMapOf<PromptContent, Int?>()

        fun tokens(of: PromptContent): Int? = renders.getOrPut(of) { render(of)?.let(counter::count) }

        // Each slot is what it adds to the render of the slots before it, so the slots add up to the prompt's tokens.
        val withoutIssue = tokens(PromptContent(PromptSlots.emptied(content.issue), null, null)) ?: return null
        var room = limit - withoutIssue

        fun fits(
            of: PromptContent,
            before: Int,
        ): Boolean = (tokens(of) ?: Int.MAX_VALUE) - before <= room

        val issue =
            if (fits(PromptContent(content.issue, null, null), withoutIssue)) {
                content.issue
            } else {
                cutDescription(content.issue, pointer) { fits(PromptContent(it, null, null), withoutIssue) }
            }
        val withIssue = tokens(PromptContent(issue, null, null)) ?: return null
        room -= withIssue - withoutIssue

        val findings =
            content.verifyFindings?.let { text ->
                if (fits(PromptContent(issue, text, null), withIssue)) {
                    text
                } else {
                    cutFindings(text) { fits(PromptContent(issue, it, null), withIssue) }
                }
            }
        val withFindings = tokens(PromptContent(issue, findings, null)) ?: return null
        room -= withFindings - withIssue

        val brief =
            content.sessionBrief?.let { text ->
                if (fits(PromptContent(issue, findings, text), withFindings)) {
                    text
                } else {
                    cutBrief(text) { fits(PromptContent(issue, findings, it), withFindings) }
                }
            }
        val withBrief = tokens(PromptContent(issue, findings, brief)) ?: return null
        room -= withBrief - withFindings

        val sources =
            content.contextSources?.let { text ->
                if (fits(PromptContent(issue, findings, brief, text), withBrief)) {
                    text
                } else {
                    cutSources(text) { fits(PromptContent(issue, findings, brief, it), withBrief) }
                }
            }

        val filled = PromptContent(issue, findings, brief, sources)
        val prompt = render(filled) ?: return null
        val before = measure(template, content, rendered, attempt, workspaceReused, retryKind, counter) ?: return null
        val after = measure(template, filled, prompt, attempt, workspaceReused, retryKind, counter) ?: return null
        return FilledPrompt(prompt, filled, before.minus(after))
    }

    private fun measure(
        template: Template,
        content: PromptContent,
        rendered: String,
        attempt: Int?,
        workspaceReused: Boolean,
        retryKind: String?,
        counter: TokenCounter,
    ): PromptSlotSizes? =
        PromptSlots
            .measure(
                template,
                content.issue,
                attempt,
                rendered,
                content.verifyFindings,
                workspaceReused,
                content.sessionBrief,
                retryKind,
                counter,
                content.contextSources,
            )?.tokens

    /** The tokens each slot of this measure has more than [other], 0 for a slot that has no more. */
    private fun PromptSlotSizes.minus(other: PromptSlotSizes): PromptSlotSizes =
        PromptSlotSizes(
            template = (template - other.template).coerceAtLeast(0),
            issue = (issue - other.issue).coerceAtLeast(0),
            verifyFindings = (verifyFindings - other.verifyFindings).coerceAtLeast(0),
            sessionBrief = (sessionBrief - other.sessionBrief).coerceAtLeast(0),
            lessons = (lessons - other.lessons).coerceAtLeast(0),
            contextSources = (contextSources - other.contextSources).coerceAtLeast(0),
        )

    /**
     * [issue] with the longest start of its description that [fits], cut at its last line end when that still fits,
     * followed by the note; only the note when no start fits. An issue without a description is left as it is.
     */
    private fun cutDescription(
        issue: Issue,
        pointer: String,
        fits: (Issue) -> Boolean,
    ): Issue {
        val text = issue.description ?: return issue

        fun cut(kept: String): Issue {
            val start = kept.trimEnd()
            val note =
                "(The description is cut here to fit the prompt limit: ${text.length - start.length} of its characters are " +
                    "left out. Read the full text $pointer.)"
            return issue.copy(description = if (start.isEmpty()) note else "$start\n\n$note")
        }

        val longest = largest(text.length - 1) { fits(cut(text.take(it))) } ?: return cut("")
        val prefix = text.take(longest)
        val atLineEnd = prefix.substringBeforeLast('\n', "").takeIf { it.isNotBlank() }?.let(::cut)
        return atLineEnd?.takeIf(fits) ?: cut(prefix)
    }

    /**
     * [text] with its head, then one line summarizing the older lines left out, then the newest lines that [fits];
     * [FINDINGS_LEFT_OUT] when not even the head and that line fit. The head is the lines up to the first that ends
     * with a colon within the first [FINDINGS_HEAD_MAX] lines, else the first line.
     */
    private fun cutFindings(
        text: String,
        fits: (String) -> Boolean,
    ): String {
        val lines = text.lines()
        val headSize = lines.take(FINDINGS_HEAD_MAX).indexOfFirst { it.endsWith(':') } + 1
        val head = lines.take(headSize.coerceAtLeast(1))
        val body = lines.drop(head.size)
        if (body.isEmpty()) return FINDINGS_LEFT_OUT

        fun cut(newest: Int): String {
            val older = body.size - newest
            val summary =
                if (older == 1) {
                    "(1 older line of the findings is left out to fit the prompt limit.)"
                } else {
                    "($older older lines of the findings are left out to fit the prompt limit.)"
                }
            return (head + summary + body.takeLast(newest)).joinToString("\n")
        }

        val newest = largest(body.size - 1) { fits(cut(it)) } ?: return FINDINGS_LEFT_OUT
        return cut(newest)
    }

    /**
     * [text], the session brief, with every part's body cut to the largest share of its length that [fits], at a line
     * end: its first lines, ended by [BRIEF_CUT], or for a part of [SessionBrief.NEWEST_LAST_PARTS] its first line, then
     * [BRIEF_OLDER_CUT], then its last lines. Each part keeps only that frame when no share fits. A part starts at a
     * `### ` heading after a blank line, as the brief joins them; a part without a body is kept as it is.
     */
    private fun cutBrief(
        text: String,
        fits: (String) -> Boolean,
    ): String {
        val parts = text.split(PART_BREAK)

        fun cut(permille: Int): String =
            parts.joinToString("\n\n") { part ->
                val lines = part.lines()
                val heading = lines.first()
                val body = lines.drop(1)
                if (body.isEmpty()) return@joinToString part
                val share = body.joinToString("\n").length.toLong() * permille / PERMILLE
                if (heading.removePrefix("### ") in SessionBrief.NEWEST_LAST_PARTS) {
                    val lead = body.first()
                    val rest = body.drop(1)
                    val newest = within(rest.asReversed(), share - lead.length - 1).asReversed()
                    if (newest.size == rest.size) part else (listOf(heading, lead, BRIEF_OLDER_CUT) + newest).joinToString("\n")
                } else {
                    val first = within(body, share)
                    if (first.size == body.size) part else (listOf(heading) + first + BRIEF_CUT).joinToString("\n")
                }
            }

        return cut(largest(PERMILLE - 1) { fits(cut(it)) } ?: 0)
    }

    /**
     * [text], the list of context sources, with its first lines that [fits], one source each, then [sourcesLeftOut];
     * null when not even that line fits.
     */
    private fun cutSources(
        text: String,
        fits: (String) -> Boolean,
    ): String? {
        val lines = text.lines()

        fun cut(kept: Int): String = (lines.take(kept) + sourcesLeftOut(lines.size - kept)).joinToString("\n")

        val kept = largest(lines.size - 1) { fits(cut(it)) } ?: return null
        return cut(kept)
    }

    /** The first of [lines] that hold, joined by line ends, in [share] characters. */
    private fun within(
        lines: List<String>,
        share: Long,
    ): List<String> {
        var size = 0L
        return lines.takeWhile { line ->
            size += line.length + 1
            size - 1 <= share
        }
    }

    /** The largest n in 0..[max] for which [holds] is true, assuming it holds up to some n and not after; null when none. */
    private fun largest(
        max: Int,
        holds: (Int) -> Boolean,
    ): Int? {
        if (max < 0 || !holds(0)) return null
        var low = 0
        var high = max
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (holds(mid)) low = mid else high = mid - 1
        }
        return low
    }

    private val PART_BREAK = Regex("\n\n(?=### )")
}
