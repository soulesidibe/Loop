package loop.core.workflow

import loop.api.Issue
import loop.core.template.TemplateEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** m1-17: the size of every prompt slot of a rendered prompt (plan 8.5), measured and never cut. */
class PromptSlotsTest {
    private val engine = TemplateEngine()

    private val issue: Issue =
        Issue(
            id = "id-m1-17",
            identifier = "m1-17",
            title = "Slots",
            state = "todo",
            priority = 2,
            labels = listOf("ready-for-agent"),
            blockedBy = listOf("m1-03"),
            description = "Record the slot sizes.",
            createdAt = null,
        )

    private fun measure(
        source: String,
        issue: Issue = this.issue,
        attempt: Int? = null,
        findings: String? = null,
        workspaceReused: Boolean = false,
        sessionBrief: String? = null,
    ): Pair<String, PromptSlotSizes?> {
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()
        val rendered = template.render(PromptVariables.of(issue, attempt, findings, workspaceReused, sessionBrief)).getOrThrow()
        return rendered to PromptSlots.measure(template, issue, attempt, rendered, findings, workspaceReused, sessionBrief)
    }

    @Test
    fun `m3-13 each slot has its size in tokens beside its characters, and the token slots add up to the prompt's tokens`() {
        val source = "Rules for {{ issue.identifier }}.{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()
        val rendered = template.render(PromptVariables.of(issue, null, "exit 1", false)).getOrThrow()
        val counter = TokenCounters.forModel(null)

        val measured = PromptSlots.measure(template, issue, null, rendered, "exit 1", counter = counter)

        // "Rules for m1-17.\nFindings: exit 1" is 33 characters, 11 tokens; without findings 16, 6 tokens; without the
        // issue "Rules for ." is 11, 4 tokens.
        assertEquals(PromptSlotSizes(template = 11, issue = 5, verifyFindings = 17), measured?.chars)
        assertEquals(PromptSlotSizes(template = 4, issue = 2, verifyFindings = 5), measured?.tokens)
        assertEquals(counter.count(rendered), measured?.tokens?.total)
        assertEquals(
            listOf(
                "slot_template_tokens",
                "slot_issue_tokens",
                "slot_verify_findings_tokens",
                "slot_session_brief_tokens",
                "slot_lessons_tokens",
                "slot_context_sources_tokens",
            ),
            measured
                ?.tokens
                ?.tokenData
                ?.keys
                ?.toList(),
        )
    }

    @Test
    fun `m3-13 the budget data holds the prompt's tokens, the slot tokens, the limit, over_limit and the counter's method`() {
        val counter = TokenCounters.forModel(null)
        val slots = PromptSlotSizes(template = 4, issue = 2)
        val limit = PromptLimit(tokens = 5, window = 100)

        val data = PromptBudget.data(prompt = "Rules for m1-17.", tokenSlots = slots, limit = limit, counter = counter)

        assertEquals(
            linkedMapOf(
                "prompt_tokens" to "6",
                "slot_template_tokens" to "4",
                "slot_issue_tokens" to "2",
                "slot_verify_findings_tokens" to "0",
                "slot_session_brief_tokens" to "0",
                "slot_lessons_tokens" to "0",
                "slot_context_sources_tokens" to "0",
                "prompt_limit_tokens" to "5",
                "over_limit" to "true",
                "token_counter" to "estimate",
            ),
            data,
        )
        assertEquals("false", PromptBudget.data("Rules.", slots, PromptLimit(tokens = 2, window = 40), counter)["over_limit"])
        val unmeasured = PromptBudget.data("Rules.", null, null, counter)
        assertEquals(mapOf("prompt_tokens" to "2", "token_counter" to "estimate"), unmeasured)
    }

    @Test
    fun `the session brief slot is what the brief adds, and the slots still add up (m3-10)`() {
        val source =
            "Rules for {{ issue.identifier }}.{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}" +
                "{% if loop.session_brief %}\n# Session brief\n{{ loop.session_brief }}{% endif %}"

        val (rendered, slots) = measure(source, attempt = 1, findings = "exit 1", workspaceReused = true, sessionBrief = "### Goal")

        assertEquals("Rules for m1-17.\nFindings: exit 1\n# Session brief\n### Goal", rendered)
        assertEquals(
            PromptSlotSizes(
                template = "Rules for .".length,
                issue = 5,
                verifyFindings = "\nFindings: exit 1".length,
                sessionBrief = "\n# Session brief\n### Goal".length,
            ),
            slots,
        )
        assertEquals(rendered.length, slots?.total)
        assertEquals(0, measure(source, attempt = 1).second?.sessionBrief)
    }

    @Test
    fun `the earlier work text of a reused workspace counts in the template slot, findings or not (bl-19)`() {
        val source =
            "Rules for {{ issue.identifier }}.{% if loop.workspace_reused %}\nEarlier work.{% endif %}" +
                "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"

        val (rendered, slots) = measure(source, workspaceReused = true)
        val (withFindings, findingSlots) = measure(source, findings = "exit 1", workspaceReused = true)

        assertEquals("Rules for m1-17.\nEarlier work.", rendered)
        assertEquals(PromptSlotSizes(template = "Rules for .\nEarlier work.".length, issue = 5), slots)
        assertEquals(
            PromptSlotSizes(template = "Rules for .\nEarlier work.".length, issue = 5, verifyFindings = "\nFindings: exit 1".length),
            findingSlots,
        )
        assertEquals(withFindings.length, findingSlots?.total)
    }

    @Test
    fun `the verify findings slot is what the findings add (m2-06)`() {
        val source = "Rules for {{ issue.identifier }}.{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"

        val (rendered, slots) = measure(source, findings = "exit 1")

        assertEquals("Rules for m1-17.\nFindings: exit 1", rendered)
        assertEquals(PromptSlotSizes(template = "Rules for .".length, issue = 5, verifyFindings = "\nFindings: exit 1".length), slots)
        assertEquals(rendered.length, slots?.total)
        assertEquals(PromptSlotSizes(template = "Rules for .".length, issue = 5), measure(source).second)
    }

    @Test
    fun `the issue slot is what the issue adds, the rest is the template and its rules`() {
        val (rendered, slots) = measure("Rules.\n{{ issue.identifier }}: {{ issue.title }}\n{{ issue.description }}")

        assertEquals("Rules.\nm1-17: Slots\nRecord the slot sizes.", rendered)
        assertEquals(PromptSlotSizes(template = "Rules.\n: \n".length, issue = "m1-17Slots".length + 22), slots)
        assertEquals(rendered.length, slots?.total)
    }

    @Test
    fun `an issue field used twice counts twice`() {
        val (_, slots) = measure("Work on {{ issue.identifier }} on the branch loop/{{ issue.identifier }}.")

        assertEquals(PromptSlotSizes(template = "Work on  on the branch loop/.".length, issue = 10), slots)
    }

    @Test
    fun `a template that never uses the issue has an empty issue slot`() {
        val (rendered, slots) = measure("Just rules.")

        assertEquals(PromptSlotSizes(template = rendered.length, issue = 0), slots)
    }

    @Test
    fun `lists and the attempt are measured, the attempt as part of the template`() {
        val source =
            "{{ issue.labels | join: \", \" }} after {{ issue.blocked_by | join: \", \" }}" +
                "{% if attempt %} retry {{ attempt }}{% endif %}"
        val (rendered, slots) = measure(source, attempt = 3)

        assertEquals("ready-for-agent after m1-03 retry 3", rendered)
        assertEquals(PromptSlotSizes(template = " after  retry 3".length, issue = "ready-for-agentm1-03".length), slots)
    }

    @Test
    fun `text a template shows only for a present field counts in the issue slot`() {
        val (rendered, slots) = measure("Rules.{% if issue.description %} Description: {{ issue.description }}{% endif %}")

        assertEquals("Rules. Description: Record the slot sizes.", rendered)
        assertEquals(PromptSlotSizes(template = "Rules.".length, issue = rendered.length - "Rules.".length), slots)
    }

    @Test
    fun `an issue without a description has the size of its other fields`() {
        val (_, slots) = measure("{{ issue.title }}|{{ issue.description }}", issue = issue.copy(description = null))

        assertEquals(PromptSlotSizes(template = 1, issue = "Slots".length), slots)
    }

    @Test
    fun `the future slots are empty until later milestones fill them`() {
        val (_, slots) = measure("{{ issue.title }}")

        assertEquals(0, slots?.verifyFindings)
        assertEquals(0, slots?.sessionBrief)
        assertEquals(0, slots?.lessons)
        assertEquals(0, slots?.contextSources)
    }

    @Test
    fun `the event data lists every slot in priority order`() {
        val data = PromptSlotSizes(template = 100, issue = 20).data

        assertEquals(
            listOf(
                "slot_template_chars" to "100",
                "slot_issue_chars" to "20",
                "slot_verify_findings_chars" to "0",
                "slot_session_brief_chars" to "0",
                "slot_lessons_chars" to "0",
                "slot_context_sources_chars" to "0",
            ),
            data.toList(),
        )
    }

    @Test
    fun `a template that renders only with the issue gives no sizes`() {
        // An index lookup on an emptied list is a miss, not a present null, so the strict render fails.
        val (rendered, slots) = measure("First label: {{ issue.labels[0] }}")

        assertEquals("First label: ready-for-agent", rendered)
        assertEquals(null, slots)
    }
}
