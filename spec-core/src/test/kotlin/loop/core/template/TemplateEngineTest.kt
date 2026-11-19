package loop.core.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TemplateEngineTest {
    private val engine = TemplateEngine()

    @Test
    fun `known variables and the upcase filter render`() {
        val template = engine.parse("{{ issue.identifier }}: {{ issue.title | upcase }}").getOrThrow()

        val output =
            template
                .render(mapOf("issue" to mapOf("identifier" to "LOOP-1", "title" to "fix the build")))
                .getOrThrow()

        assertEquals("LOOP-1: FIX THE BUILD", output)
    }

    @Test
    fun `unknown top level variable fails with a render error`() {
        val template = engine.parse("hello {{ nope }}").getOrThrow()

        val error = template.render(emptyMap()).exceptionOrNull()

        assertIs<TemplateError.Render>(error)
    }

    @Test
    fun `unknown nested property fails with a render error`() {
        val template = engine.parse("{{ issue.nope }}").getOrThrow()

        val error = template.render(mapOf("issue" to mapOf("identifier" to "LOOP-1"))).exceptionOrNull()

        assertIs<TemplateError.Render>(error)
    }

    @Test
    fun `unknown filter fails with a parse error`() {
        val error = engine.parse("{{ issue.title | nope }}").exceptionOrNull()

        assertIs<TemplateError.Parse>(error)
    }

    @Test
    fun `attempt bound to null takes the else branch`() {
        val template = engine.parse("[{{ attempt }}]{% if attempt %}x{% else %}first{% endif %}").getOrThrow()

        val output = template.render(mapOf("attempt" to null)).getOrThrow()

        assertEquals("[]first", output)
    }

    @Test
    fun `issue labels join into one string`() {
        val template = engine.parse("{{ issue.labels | join: \", \" }}").getOrThrow()

        val output = template.render(mapOf("issue" to mapOf("labels" to listOf("bug", "ready")))).getOrThrow()

        assertEquals("bug, ready", output)
    }

    @Test
    fun `tablerow is not supported and fails with a parse error`() {
        val error = engine.parse("{% tablerow x in xs %}{{ nope }}{% endtablerow %}").exceptionOrNull()

        assertIs<TemplateError.Parse>(error)
    }

    @Test
    fun `errors from one render do not leak into the next`() {
        val template = engine.parse("{{ issue.title }}").getOrThrow()

        val first = template.render(emptyMap()).exceptionOrNull()
        val second = template.render(mapOf("issue" to mapOf("title" to "ok"))).getOrThrow()

        assertIs<TemplateError.Render>(first)
        assertEquals("ok", second)
    }

    @Test
    fun `issue description bound to null renders empty`() {
        val template = engine.parse("[{{ issue.description }}]").getOrThrow()

        val output = template.render(mapOf("issue" to mapOf("description" to null))).getOrThrow()

        assertEquals("[]", output)
    }

    @Test
    fun `unknown filter in assign fails with a parse error`() {
        val error = engine.parse("{% assign t = issue.title | nope %}").exceptionOrNull()

        assertIs<TemplateError.Parse>(error)
    }

    @Test
    fun `unknown filter in a for loop fails with a parse error`() {
        val error = engine.parse("{% for l in issue.labels %}{{ l | nope }}{% endfor %}").exceptionOrNull()

        assertIs<TemplateError.Parse>(error)
    }

    @Test
    fun `unknown variable in an if condition fails with a render error`() {
        val template = engine.parse("{% if nope %}x{% endif %}").getOrThrow()

        val error = template.render(emptyMap()).exceptionOrNull()

        assertIs<TemplateError.Render>(error)
    }

    @Test
    fun `deeply nested template fails with a parse error`() {
        val depth = 100_000
        val source = "{% if a %}".repeat(depth) + "{% endif %}".repeat(depth)

        val error = engine.parse(source).exceptionOrNull()

        assertIs<TemplateError.Parse>(error)
    }

    @Test
    fun `known gap - a null field on a loop variable fails with a render error`() {
        val template = engine.parse("{% for b in issue.blocked_by %}{{ b.state }}{% endfor %}").getOrThrow()
        val vars = mapOf("issue" to mapOf("blocked_by" to listOf(mapOf("identifier" to "LOOP-2", "state" to null))))

        val error = template.render(vars).exceptionOrNull()

        assertIs<TemplateError.Render>(error)
    }

    @Test
    fun `bl-06 uses finds a variable in output, in a tag, in a filter argument and in a branch not taken`() {
        val sources =
            listOf(
                "{{ issue.description }}",
                "{{ issue.description | upcase }}",
                "{% if attempt %}{{ issue.description }}{% endif %}",
                "{% if issue.description %}yes{% endif %}",
                "{{ issue.title | append: issue.description }}",
                "{{ issue[\"description\"] }}",
                "{{ issue['description'] }}",
                "{{ issue }}",
            )

        sources.forEach { source ->
            assertTrue(engine.parse(source).getOrThrow().uses("issue", "description"), source)
        }
    }

    @Test
    fun `bl-06 uses is false when only other keys, other roots or plain text name the variable`() {
        val sources =
            listOf(
                "{{ issue.title }} {{ issue.identifier }}",
                "{{ loop.description }}",
                "issue.description in plain text",
                "{% raw %}{{ issue.description }}{% endraw %}",
                "{% comment %}{{ issue.description }}{% endcomment %}",
            )

        sources.forEach { source ->
            assertFalse(engine.parse(source).getOrThrow().uses("issue", "description"), source)
        }
    }
}
