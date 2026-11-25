package loop.core.waiting

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RunId
import loop.core.events.EventDefinition
import loop.core.events.IssueCommented
import loop.core.events.QuestionAsked
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

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

    private val wait =
        Wait.ask(ProjectId("p1"), "id-1", "t-1", AttemptId("a1"), RunId("r1"), Instant.parse("2026-11-25T10:00:00Z"), "todo", "product")

    private val posted = mutableListOf<String>()
    private val recorded = mutableListOf<Pair<EventDefinition, Map<String, String>>>()
    private val saved = mutableListOf<Wait.Ask>()

    private suspend fun start(post: Result<Unit> = Result.success(Unit)): String? =
        Asks.start(
            question = Asks.Question("product", "Ship the token ghp_secret too?", listOf("yes", "no"), "no"),
            wait = wait,
            redact = { it.replace("ghp_secret", "[REDACTED]") },
            post = { text -> posted += text; post },
            record = { definition, data -> recorded += definition to data },
            save = { saved += it },
        )

    @Test
    fun `an ask posts its redacted comment, records issue_commented and question_asked, then saves the wait`() =
        runTest {
            assertNull(start())

            val text = posted.single()
            assertEquals(Asks.comment("product", "Ship the token [REDACTED] too?", listOf("yes", "no"), "no"), text)
            assertEquals(listOf(IssueCommented, QuestionAsked), recorded.map { it.first })
            assertEquals(mapOf("chars" to text.length.toString(), "by" to "host", "reason" to "question"), recorded[0].second)
            assertEquals(mapOf("question_kind" to "product", "options" to "2", "chars" to text.length.toString()), recorded[1].second)
            assertEquals(listOf(wait), saved)
        }

    @Test
    fun `an ask whose comment cannot be posted records nothing and saves no wait`() =
        runTest {
            assertEquals("tracker down", start(Result.failure(IOException("tracker down"))))

            assertEquals(emptyList(), recorded)
            assertEquals(emptyList(), saved)
        }

    @Test
    fun `an ask is refused in Symphony mode, after an ask of the same attempt and after a result of the same turn`() {
        assertNull(Asks.refusal(loopMode = true, asked = false, submitted = false))
        assertEquals("ask needs Loop mode, a kinds: key in WORKFLOW.md", Asks.refusal(loopMode = false, asked = false, submitted = false))
        assertEquals(Asks.SECOND_ASK, Asks.refusal(loopMode = true, asked = true, submitted = false))
        assertEquals(
            "the attempt submitted a result in this turn, so it cannot ask a question too",
            Asks.refusal(loopMode = true, asked = false, submitted = true),
        )
    }
}
