package loop.core.reactions

import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.core.events.EventDefinition
import loop.core.events.IssueParked
import loop.core.events.ReactionKeys
import loop.core.events.ReactionTriggered
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.orchestrator.ExitReason
import loop.core.orchestrator.FIXED_NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * bl-224: what a new process reads back of a reaction from the stored events: whether the issue's last reaction that
 * scheduled a retry came after its last counter reset, and that reaction's findings.
 */
class ReactionMarksTest {
    @Test
    fun `a reaction that scheduled a retry after the last reset marks the issue`() {
        assertEquals(setOf(ISSUE), ReactionMarks.reacted(listOf(parked(), triggered(sent = true))))
    }

    @Test
    fun `a reaction that parked, a notice and a skip mark nothing`() {
        val notice = event(ReactionTriggered, mapOf(ReactionKeys.REACTION to "approved_green", ReactionKeys.ACTION to "notify_human"))
        assertEquals(emptySet(), ReactionMarks.reacted(listOf(triggered(sent = false), notice)))
    }

    @Test
    fun `each reset trace after the reaction clears the mark`() {
        val resets =
            listOf(
                parked(),
                event(RetryDropped, mapOf(RetryDropped.REASON to ExitReason.TERMINAL)),
                event(RetryDropped, mapOf(RetryDropped.REASON to ExitReason.NOT_ACTIVE)),
                event(RetryScheduled, mapOf(RetryScheduled.KIND to RetryScheduled.CONTINUATION, RetryScheduled.FAILURES to "0")),
            )
        for (reset in resets) {
            assertEquals(emptySet(), ReactionMarks.reacted(listOf(triggered(sent = true), reset)), reset.toString())
        }
    }

    @Test
    fun `a drop that keeps the counter and a retry do not clear the mark`() {
        val kept =
            listOf(
                event(RetryDropped, mapOf(RetryDropped.REASON to ExitReason.NOT_ELIGIBLE)),
                event(RetryScheduled, mapOf(RetryScheduled.KIND to "error", RetryScheduled.FAILURES to "2")),
            )
        assertEquals(setOf(ISSUE), ReactionMarks.reacted(listOf(triggered(sent = true)) + kept))
    }

    @Test
    fun `the marks are per issue`() {
        val other = triggered(sent = true).copy(issueId = "id-t-2")
        assertEquals(setOf("id-t-2"), ReactionMarks.reacted(listOf(triggered(sent = true), other, parked())))
    }

    @Test
    fun `the findings are those of the issue's last reaction that scheduled a retry`() {
        val events =
            listOf(
                triggered(sent = true, findings = "first"),
                triggered(sent = true, findings = "second"),
                triggered(sent = false),
                triggered(sent = true, findings = "other").copy(issueId = "id-t-2"),
            )
        assertEquals("second", ReactionMarks.findings(events, ISSUE))
        assertNull(ReactionMarks.findings(listOf(triggered(sent = false)), ISSUE))
    }

    companion object {
        const val ISSUE = "id-t-1"

        fun event(
            definition: EventDefinition,
            data: Map<String, String>,
        ): RecordedEvent = definition.event(ts = FIXED_NOW, projectId = ProjectId("p1"), data = data, issueId = ISSUE, issueIdentifier = "t-1")

        /** A `merge_conflict` reaction, [sent] when it scheduled a retry, which then holds its [findings]. */
        fun triggered(
            sent: Boolean,
            findings: String = "Resolve the conflict.",
        ): RecordedEvent =
            event(
                ReactionTriggered,
                mapOf(
                    ReactionKeys.REACTION to ReactionsConfig.MERGE_CONFLICT,
                    ReactionKeys.ACTION to "send_to_agent",
                    ReactionKeys.ISSUE to "t-1",
                    ReactionKeys.PULL_REQUEST to "7",
                ) + if (sent) mapOf(ReactionKeys.FINDINGS to findings) else emptyMap(),
            )

        fun parked(): RecordedEvent = event(IssueParked, mapOf(IssueParked.REASON to "max_attempts"))
    }
}
