package loop.core.kinds

import loop.core.workflow.ReadyConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** m2-11: an issue is ready when its description has every section its kind requires. */
class ReadinessTest {
    private val ready = ReadyConfig(listOf("Acceptance criteria"))

    private fun missing(description: String?) = Readiness.missingSections(description, ready)

    @Test
    fun `a description with the section is ready`() {
        assertEquals(emptyList(), missing("## What\n\nDo it.\n\n## Acceptance criteria\n\n- [ ] it works\n\n## Comments\n"))
    }

    @Test
    fun `no description or no section is not ready`() {
        assertEquals(listOf("Acceptance criteria"), missing(null))
        assertEquals(listOf("Acceptance criteria"), missing("## What\n\nDo it.\n"))
    }

    @Test
    fun `the heading is matched trimmed and case insensitive at any level`() {
        assertEquals(emptyList(), missing("### acceptance CRITERIA  \n- [ ] it works\n"))
        assertEquals(emptyList(), missing("# Acceptance criteria\r\n\r\n- [ ] it works\r\n"))
    }

    @Test
    fun `an empty section counts as missing`() {
        assertEquals(listOf("Acceptance criteria"), missing("## Acceptance criteria\n\n   \n## Comments\n\nsoon\n"))
        assertEquals(listOf("Acceptance criteria"), missing("## What\n\nDo it.\n\n## Acceptance criteria\n"))
    }

    @Test
    fun `a deeper heading inside the section is content`() {
        assertEquals(emptyList(), missing("## Acceptance criteria\n### Tests\n- [ ] it works\n"))
        assertEquals(listOf("Acceptance criteria"), missing("### Acceptance criteria\n## Comments\n- [ ] not here\n"))
    }

    @Test
    fun `a heading inside a code fence or a mention in text does not count`() {
        assertEquals(listOf("Acceptance criteria"), missing("```\n## Acceptance criteria\n- [ ] x\n```\n"))
        assertEquals(listOf("Acceptance criteria"), missing("~~~md\n## Acceptance criteria\n- [ ] x\n~~~\n"))
        assertEquals(listOf("Acceptance criteria"), missing("Add an ## Acceptance criteria section.\n"))
        assertEquals(listOf("Acceptance criteria"), missing("##Acceptance criteria\n- [ ] x\n"))
    }

    @Test
    fun `every missing section is listed in config order`() {
        val config = ReadyConfig(listOf("What", "Acceptance criteria", "Context"))

        assertEquals(listOf("Acceptance criteria", "Context"), Readiness.missingSections("## What\n\nDo it.\n", config))
    }
}
