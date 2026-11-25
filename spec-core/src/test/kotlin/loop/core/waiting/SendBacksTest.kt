package loop.core.waiting

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/** m4-32: what `loop send-back` writes, what it refuses, and what the attempt after it is shown. */
class SendBacksTest {
    private val project = ProjectId("p1")
    private val since = Instant.parse("2026-11-25T09:00:00Z")

    private fun wait(
        reason: WaitReason,
        data: Map<String, String> = mapOf("to" to "review"),
    ): Wait = Wait.of(WaitingIssue(project, "id-1", "t-1", reason, AttemptId("a1"), RunId("r1"), since, data))

    private val sentBack =
        wait(WaitReason.Approval, mapOf("to" to "review") + WaitingIssue.sendBackData("soule", "Use Save.", since)) as Wait.Approval

    @Test
    fun `the comment names who sent it back and holds the reason`() {
        assertEquals("Sent back by soule: Use Save.", SendBacks.comment("soule", "  Use Save.\n"))
    }

    @Test
    fun `the findings of the next attempt are the reason as the comment words it, null before a send back`() {
        assertEquals("Sent back by soule: Use Save.", SendBacks.findings(sentBack))
        assertNull(SendBacks.findings(wait(WaitReason.Approval) as Wait.Approval))
    }

    @Test
    fun `a sent back approval wait holds who and why`() {
        assertEquals(true, sentBack.sentBack)
        assertEquals("soule", sentBack.sentBackBy)
        assertEquals("Use Save.", sentBack.sentBackReason)
        assertEquals(false, (wait(WaitReason.Approval) as Wait.Approval).sentBack)
    }

    @Test
    fun `only an approval wait not sent back yet is sent back, approved or not, each refusal with its kind`() {
        assertNull(SendBacks.refusal(wait(WaitReason.Approval)))
        assertNull(SendBacks.refusal(wait(WaitReason.Approval, mapOf("to" to "review", "approved_by" to "s", "approved_at" to "x"))))
        assertEquals(
            SendBacks.Refusal(SendBacks.Kind.NotWaiting, "is already sent back, Loop dispatches it with the first reason"),
            SendBacks.refusal(sentBack),
        )
        assertEquals(
            SendBacks.Refusal(
                SendBacks.Kind.Merge,
                "is waiting for its merge, not for approval, loop status shows its phase, " +
                    "and a refused merge waits for approval again, which loop send-back ends",
            ),
            SendBacks.refusal(wait(WaitReason.Merge, mapOf("to" to "review", "phase" to "check"))),
        )
        assertEquals(
            SendBacks.Refusal(SendBacks.Kind.Ask, "is waiting for an answer, not for approval, answer it with loop answer"),
            SendBacks.refusal(wait(WaitReason.Ask, mapOf("state" to "todo"))),
        )
        val verify = assertIs<SendBacks.Refusal>(SendBacks.refusal(wait(WaitReason.Verify)))
        assertEquals(SendBacks.Kind.NotWaiting, verify.kind)
        assertEquals("is waiting for verify, not for approval, loop status shows where it stands", verify.message)
    }

    @Test
    fun `the refusal of an issue that does not wait names loop status`() {
        assertEquals("is not waiting for approval, loop status shows where it stands", SendBacks.NOT_WAITING)
    }
}
