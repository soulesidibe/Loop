package loop.core.waiting

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** m4-52: what `loop answer` writes and what the attempt after it is shown. */
class AnswersTest {
    private val project = ProjectId("p1")
    private val since = Instant.parse("2026-11-25T09:00:00Z")

    private val asked =
        Wait
            .ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "product")
            .asking("Question for a human (product): Archived items?\n\nOptions:\n- Include them\n- Leave them out")

    private fun answered(wait: Wait.Ask = asked): Wait.Ask =
        Wait.of(
            wait.toRecord().copy(
                data =
                    wait.toRecord().data +
                        mapOf(
                            WaitingIssue.ANSWER to "Leave them out.",
                            WaitingIssue.ANSWERED_BY to "soule",
                            WaitingIssue.ANSWERED_AT to since.toString(),
                        ),
            ),
        ) as Wait.Ask

    @Test
    fun `the comment names who answered and holds the answer`() {
        assertEquals("Answer from soule: Leave them out.", Answers.comment("soule", "  Leave them out.\n"))
    }

    @Test
    fun `the prompt text is the question as posted, then the answer`() {
        assertEquals(
            "Question for a human (product): Archived items?\n\nOptions:\n- Include them\n- Leave them out\n\n" +
                "Answer from soule: Leave them out.",
            Answers.prompt(answered()),
        )
    }

    @Test
    fun `a wait stored before m4-52 holds no question, so the prompt text is the answer alone`() {
        val old = Wait.ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "human")
        assertEquals("Answer from soule: Leave them out.", Answers.prompt(answered(old)))
    }

    @Test
    fun `an unanswered wait has no prompt text`() {
        assertNull(Answers.prompt(asked))
    }

    @Test
    fun `only an unanswered ask wait takes an answer`() {
        assertNull(Answers.refusal(asked))
        assertEquals("is already answered, Loop dispatches it with the first answer", Answers.refusal(answered()))
        val approval =
            Wait.of(WaitingIssue(project, "id-1", "t-1", WaitReason.Approval, null, null, since, mapOf("to" to "review")))
        assertEquals("is waiting for approval, not for an answer", Answers.refusal(approval))
    }
}
