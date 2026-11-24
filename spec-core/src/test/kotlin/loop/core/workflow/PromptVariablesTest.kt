package loop.core.workflow

import loop.core.template.TemplateEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m2-06: `loop.verify_findings` renders the findings of a refutation, and changes nothing for a template without it.
 * bl-19: `loop.workspace_reused` tells whether the workspace existed before the attempt, with the same guarantee.
 */
class PromptVariablesTest {
    private val engine = TemplateEngine()
    private val issue = PromptVariables.SAMPLE_ISSUE

    private fun render(
        source: String,
        findings: String?,
        attempt: Int? = 1,
    ): String =
        engine
            .parse(source, PromptVariables.schema)
            .getOrThrow()
            .render(PromptVariables.of(issue, attempt, findings))
            .getOrThrow()

    @Test
    fun `a template that uses loop verify_findings renders the findings after a refutation and nothing otherwise`() {
        val source = "Work on {{ issue.identifier }}.{% if loop.verify_findings %}\n\n{{ loop.verify_findings }}{% endif %}"

        assertEquals("Work on SAMPLE-1.\n\nCheck: ./gradlew test", render(source, "Check: ./gradlew test"))
        assertEquals("Work on SAMPLE-1.", render(source, null))
        assertEquals("Work on SAMPLE-1.", render(source, null, attempt = null))
    }

    @Test
    fun `a template that does not use it renders exactly as before, findings or not`() {
        val source = "Work on {{ issue.identifier }}: {{ issue.title }}{% if attempt %} (retry {{ attempt }}){% endif %}"
        val before =
            engine
                .parse(source, PromptVariables.schema)
                .getOrThrow()
                .render(PromptVariables.of(issue, 1))
                .getOrThrow()

        assertEquals("Work on SAMPLE-1: Sample title (retry 1)", before)
        assertEquals(before, render(source, "Check: ./gradlew test"))
        assertEquals(before, render(source, null))
    }

    private fun renderReused(
        source: String,
        workspaceReused: Boolean,
        attempt: Int? = null,
    ): String =
        engine
            .parse(source, PromptVariables.schema)
            .getOrThrow()
            .render(PromptVariables.of(issue, attempt, workspaceReused = workspaceReused))
            .getOrThrow()

    @Test
    fun `bl-19 a template that uses loop workspace_reused renders the earlier work branch only for a reused workspace`() {
        val source = "Work on {{ issue.identifier }}.{% if attempt or loop.workspace_reused %} Earlier work is here.{% endif %}"

        assertEquals("Work on SAMPLE-1. Earlier work is here.", renderReused(source, workspaceReused = true))
        assertEquals("Work on SAMPLE-1.", renderReused(source, workspaceReused = false))
        assertEquals("Work on SAMPLE-1. Earlier work is here.", renderReused(source, workspaceReused = false, attempt = 1))
        assertEquals("reused=true", renderReused("reused={{ loop.workspace_reused }}", workspaceReused = true))
        assertEquals("reused=false", renderReused("reused={{ loop.workspace_reused }}", workspaceReused = false))
    }

    @Test
    fun `bl-19 a template that does not use loop workspace_reused renders exactly as before`() {
        val source = "Work on {{ issue.identifier }}: {{ issue.title }}{% if attempt %} (retry {{ attempt }}){% endif %}"
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()

        for (attempt in listOf(null, 1)) {
            val before = template.render(PromptVariables.of(issue, attempt)).getOrThrow()
            assertEquals(before, renderReused(source, workspaceReused = true, attempt = attempt))
            assertEquals(before, renderReused(source, workspaceReused = false, attempt = attempt))
        }
    }

    @Test
    fun `m3-10 loop session_brief renders the brief when set and nothing when null`() {
        val source = "Work on {{ issue.identifier }}.{% if loop.session_brief %}\n\n{{ loop.session_brief }}{% endif %}"
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()

        val set = template.render(PromptVariables.of(issue, 1, sessionBrief = "### Goal")).getOrThrow()
        val unset = template.render(PromptVariables.of(issue, null)).getOrThrow()

        assertEquals("Work on SAMPLE-1.\n\n### Goal", set)
        assertEquals("Work on SAMPLE-1.", unset)
    }

    @Test
    fun `m3-11 loop retry_kind renders the kind Loop sets and nothing on a first dispatch`() {
        val source = "Work on {{ issue.identifier }}.{% if loop.retry_kind %} Kind: {{ loop.retry_kind }}.{% endif %}"
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()

        for (kind in PromptVariables.RETRY_KINDS) {
            val rendered = template.render(PromptVariables.of(issue, 1, retryKind = kind)).getOrThrow()
            assertEquals("Work on SAMPLE-1. Kind: $kind.", rendered)
        }
        assertEquals("Work on SAMPLE-1.", template.render(PromptVariables.of(issue, null)).getOrThrow())
        assertEquals(listOf("continuation", "rotation", "error", "refuted"), PromptVariables.RETRY_KINDS)
    }

    @Test
    fun `an unknown loop variable fails the parse, like any unknown variable`() {
        val parsed = engine.parse("{{ loop.verify_finding }}", PromptVariables.schema)

        assertTrue(parsed.isFailure)
    }

    @Test
    fun `m4-12 issue id and identifier stay native and issue tracker holds the tracker id`() {
        val github = issue.copy(id = "gh:454", identifier = "example-app-454")
        val source = "{{ issue.id }} {{ issue.identifier }} {{ issue.tracker }}"
        val template = engine.parse(source, PromptVariables.schema).getOrThrow()

        assertEquals("454 example-app-454 gh", template.render(PromptVariables.of(github, null)).getOrThrow())
        assertEquals("sample-id SAMPLE-1 default", template.render(PromptVariables.of(issue, null)).getOrThrow())
    }
}
