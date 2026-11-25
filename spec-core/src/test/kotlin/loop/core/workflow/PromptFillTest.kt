package loop.core.workflow

import loop.api.Issue
import loop.core.template.TemplateEngine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m3-14: an over budget prompt is filled slot by slot in priority order, each slot cut by its rule (plan 8.5). */
class PromptFillTest {
    private val engine = TemplateEngine()
    private val counter = TokenCounters.estimate

    private val rules = "Rules: work on the issue below, keep the tests green, commit with the identifier. ".repeat(4).trim()

    private val source =
        "$rules\n# {{ issue.identifier }}: {{ issue.title }}\n{{ issue.description }}" +
            "{% if loop.verify_findings %}\n# Findings\n{{ loop.verify_findings }}{% endif %}" +
            "{% if loop.session_brief %}\n# Session brief\n{{ loop.session_brief }}{% endif %}"

    private val template = engine.parse(source, PromptVariables.schema).getOrThrow()

    private val issue: Issue =
        Issue(
            id = "id-m3-14",
            identifier = "m3-14",
            title = "Slots",
            state = "todo",
            priority = 2,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = "Fill the slots in order.",
            createdAt = null,
        )

    private val pointer = "at https://example.test/issues/14"

    private fun fill(
        content: PromptContent,
        limit: Long,
    ): FilledPrompt {
        val filled = PromptFill.fill(template, content, attempt = 1, workspaceReused = true, retryKind = null, counter, limit, pointer)
        return assertNotNull(filled)
    }

    private fun tokensOf(content: PromptContent): PromptSlotSizes {
        val rendered = render(content)
        return assertNotNull(
            PromptSlots
                .measure(
                    template,
                    content.issue,
                    1,
                    rendered,
                    content.verifyFindings,
                    true,
                    content.sessionBrief,
                    null,
                    counter,
                )?.tokens,
        )
    }

    private fun render(content: PromptContent): String =
        template
            .render(PromptVariables.of(content.issue, 1, content.verifyFindings, true, content.sessionBrief))
            .getOrThrow()

    private val findings =
        "Your last result was refuted.\nCheck: ./gradlew test\nExit code: 1\nLast 30 lines of its output:\n" +
            (1..30).joinToString("\n") { "output line $it of the failing test run" }

    private val brief =
        listOf("Goal and acceptance criteria", "Workspace state", "Counters")
            .joinToString("\n\n") { title -> "### $title\n" + (1..15).joinToString("\n") { "$title fact $it, newest first" } }

    @Test
    fun `a prompt within its limit is sent as rendered, with no cut`() {
        val content = PromptContent(issue, findings, brief)

        val filled = fill(content, limit = 100_000)

        assertEquals(render(content), filled.prompt)
        assertEquals(content, filled.content)
        assertNull(filled.cuts)
    }

    @Test
    fun `the template and its rules are never cut, even when nothing else fits`() {
        val long = issue.copy(description = "A very long description line.\n".repeat(200))
        val content = PromptContent(long, findings, brief)
        val templateTokens = tokensOf(content).template

        val filled = fill(content, limit = templateTokens.toLong())

        assertContains(filled.prompt, rules)
        assertContains(filled.prompt, "# m3-14: Slots")
        assertEquals(0, filled.cuts?.template)
        assertEquals(templateTokens, tokensOf(filled.content).template)
    }

    @Test
    fun `the issue description is cut at a line end, with a pointer to the full text`() {
        val description = (1..200).joinToString("\n") { "Description line $it of the issue." }
        val content = PromptContent(issue.copy(description = description), null, null)
        val limit = tokensOf(content).template + 300L

        val filled = fill(content, limit)

        assertTrue(counter.count(filled.prompt) <= limit, "${counter.count(filled.prompt)} tokens over $limit")
        val cut = assertNotNull(filled.content.issue.description)
        assertTrue(cut.startsWith("Description line 1 of the issue.\n"), cut.take(80))
        val kept = cut.substringBefore("\n\n(")
        assertTrue(kept.endsWith("of the issue."), "the cut is not at a line end: ${kept.takeLast(40)}")
        val left = description.length - kept.length
        assertContains(
            cut,
            "(The description is cut here to fit the prompt limit: $left of its characters are left out. " +
                "Read the full text at https://example.test/issues/14.)",
        )
        assertEquals("m3-14", filled.content.issue.identifier)
        assertEquals("Slots", filled.content.issue.title)
        assertTrue(assertNotNull(filled.cuts).issue > 0, "${filled.cuts}")
        assertEquals(tokensOf(content).issue - tokensOf(filled.content).issue, filled.cuts.issue)
    }

    @Test
    fun `the findings keep their head and the newest lines, the older lines summarized in one line`() {
        val content = PromptContent(issue, findings, null)
        val tokens = tokensOf(content)
        val limit = tokens.template + tokens.issue + 120L

        val filled = fill(content, limit)

        assertTrue(counter.count(filled.prompt) <= limit, "${counter.count(filled.prompt)} tokens over $limit")
        val cut = assertNotNull(filled.content.verifyFindings)
        assertTrue(
            cut.startsWith("Your last result was refuted.\nCheck: ./gradlew test\nExit code: 1\nLast 30 lines of its output:\n"),
            cut,
        )
        assertTrue(cut.endsWith("output line 30 of the failing test run"), cut)
        assertFalse("output line 1 of" in cut, cut)
        val summary = cut.lines()[4]
        val older = Regex("""\((\d+) older lines of the findings are left out to fit the prompt limit\.\)""").matchEntire(summary)
        val left = assertNotNull(older, summary).groupValues[1].toInt()
        assertEquals(30, left + cut.lines().size - 5)
        // The issue comes before the findings, so it is whole.
        assertEquals(issue, filled.content.issue)
        assertEquals(0, filled.cuts?.issue)
        assertTrue(assertNotNull(filled.cuts).verifyFindings > 0)
    }

    @Test
    fun `findings that cannot keep their head become one line`() {
        val content = PromptContent(issue, findings, null)
        val tokens = tokensOf(content)

        val filled = fill(content, limit = tokens.template + tokens.issue + 5L)

        assertEquals(PromptFill.FINDINGS_LEFT_OUT, filled.content.verifyFindings)
    }

    @Test
    fun `the session brief keeps the heading and the first lines of every part, each part capped`() {
        val content = PromptContent(issue, findings, brief)
        val tokens = tokensOf(content)
        val limit = tokens.template + tokens.issue + tokens.verifyFindings + 150L

        val filled = fill(content, limit)

        assertTrue(counter.count(filled.prompt) <= limit, "${counter.count(filled.prompt)} tokens over $limit")
        val cut = assertNotNull(filled.content.sessionBrief)
        listOf("Goal and acceptance criteria", "Workspace state", "Counters").forEach { title ->
            val part = cut.substringAfter("### $title\n").substringBefore("\n\n###")
            assertTrue(part.startsWith("$title fact 1, newest first"), part)
            assertTrue(part.endsWith("(cut to fit the prompt limit)"), part)
            assertFalse("$title fact 15," in part, part)
        }
        // The slots before it fit, so they are whole.
        assertEquals(PromptContent(issue, findings, cut), filled.content)
        assertEquals(0, filled.cuts?.issue)
        assertEquals(0, filled.cuts?.verifyFindings)
        assertTrue(assertNotNull(filled.cuts).sessionBrief > 0)
    }

    @Test
    fun `the lessons and context sources slots stay empty and measure 0, cut or not`() {
        val content = PromptContent(issue.copy(description = "Long line.\n".repeat(300)), findings, brief)
        val limit = tokensOf(content).template + 50L

        val filled = fill(content, limit)

        val cuts = assertNotNull(filled.cuts)
        assertEquals(0, cuts.lessons)
        assertEquals(0, cuts.contextSources)
        val sent = tokensOf(filled.content)
        assertEquals(0, sent.lessons)
        assertEquals(0, sent.contextSources)
    }

    @Test
    fun `the cut data names every slot in priority order`() {
        val cuts = PromptSlotSizes(template = 0, issue = 12, verifyFindings = 3, sessionBrief = 40)

        assertEquals(
            linkedMapOf(
                "cut_template_tokens" to "0",
                "cut_issue_tokens" to "12",
                "cut_verify_findings_tokens" to "3",
                "cut_session_brief_tokens" to "40",
                "cut_lessons_tokens" to "0",
                "cut_context_sources_tokens" to "0",
            ),
            cuts.cutData,
        )
    }
    @Test
    fun `m4-19 the context sources are cut first, keeping their first lines and a line saying how many are left out`() {
        val withSources = engine.parse("$source{% if loop.context_sources %}\n# Sources\n{{ loop.context_sources }}{% endif %}", PromptVariables.schema).getOrThrow()
        val sources = (1..40).joinToString("\n") { "- docs/source-$it.md" }
        val content = PromptContent(issue, null, null, sources)

        fun fillTo(limit: Long) =
            assertNotNull(PromptFill.fill(withSources, content, attempt = 1, workspaceReused = true, retryKind = null, counter, limit, pointer))

        val bare = counter.count(withSources.render(PromptVariables.of(issue, 1, null, true)).getOrThrow()).toLong()
        val cut = fillTo(bare + 60)

        assertEquals(issue, cut.content.issue, "the issue keeps its room")
        val kept = assertNotNull(cut.content.contextSources).lines()
        assertTrue(kept.size in 2..39, kept.toString())
        assertEquals("- docs/source-1.md", kept.first())
        assertEquals(PromptFill.sourcesLeftOut(41 - kept.size), kept.last())
        assertTrue(counter.count(cut.prompt) <= bare + 60)
        assertTrue(assertNotNull(cut.cuts).contextSources > 0)
        assertEquals(0, cut.cuts.issue)

        assertNull(fillTo(bare).content.contextSources, "no room for even the note: the sources are left out")
        assertEquals(PromptFill.sourcesLeftOut(1), "(1 more context source is left out to fit the prompt limit.)")
    }
}
