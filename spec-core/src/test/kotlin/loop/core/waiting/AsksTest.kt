package loop.core.waiting

import kotlin.test.Test
import kotlin.test.assertEquals

/** m4-31: the comment Loop posts on the issue for an agent's `ask`, and the reason its notice gives. */
class AsksTest {
    @Test
    fun `the comment holds the kind, the question, the options and the recommendation`() {
        val text = Asks.comment("product", "Should the export include archived items?", listOf("include them", "skip them"), "include them")

        assertEquals(
            """
            |Question for a human (product): Should the export include archived items?
            |
            |Options:
            |- include them
            |- skip them
            |
            |Recommendation: include them
            """.trimMargin(),
            text,
        )
    }

    @Test
    fun `without options or a recommendation the comment is the question alone`() {
        assertEquals("Question for a human (human): Which account?", Asks.comment("human", "Which account?", emptyList(), null))
    }

    @Test
    fun `blank options and a blank recommendation are left out`() {
        assertEquals("Question for a human (design): Which layout?", Asks.comment("design", "Which layout?", listOf(" ", ""), "  "))
    }

    @Test
    fun `the notice reason names the kind`() {
        assertEquals("the agent asks a product question", Asks.noticeReason("product"))
        assertEquals("the agent asks an engineering question", Asks.noticeReason("engineering"))
    }
}
