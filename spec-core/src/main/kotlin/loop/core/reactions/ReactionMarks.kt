package loop.core.reactions

import loop.api.RecordedEvent
import loop.core.events.IssueParked
import loop.core.events.ReactionKeys
import loop.core.events.ReactionTriggered
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.orchestrator.ExitReason

/**
 * bl-224: what a new process reads back of the reactions from the stored events, with no store schema change. A
 * `reaction_triggered` that holds [ReactionKeys.FINDINGS] is a reaction whose retry was scheduled: it marks its issue,
 * so the approval exit of the reaction attempt keeps the failure counter, until a counter reset the events show, and
 * its findings reach that retry after a restart.
 */
object ReactionMarks {
    /** The event kinds [reacted] reads, in recording order: the reactions and the counter resets they show. */
    val KINDS: Set<String> = setOf(ReactionTriggered.name, IssueParked.name, RetryDropped.name, RetryScheduled.name)

    /**
     * The ids of the issues whose last reaction that scheduled a retry was recorded after their last counter reset in
     * [events], read in recording order. The resets the events show are a park, a retry dropped because its issue was
     * terminal or not active, and a continuation, which follows a normal exit. A handoff, an issue that left the poll and
     * a reconciliation reset the counter with no such event: the caller also asks for a stored failure counter above
     * zero, and a mark kept past such a reset only parks the issue earlier.
     */
    fun reacted(events: List<RecordedEvent>): Set<String> {
        val marked = mutableSetOf<String>()
        for (event in events) {
            val issueId = event.issueId ?: continue
            when {
                sentBack(event) -> marked += issueId
                resets(event) -> marked -= issueId
            }
        }
        return marked
    }

    /** The findings of the last reaction of [issueId] in [events] whose retry was scheduled, null when it has none. */
    fun findings(
        events: List<RecordedEvent>,
        issueId: String,
    ): String? = events.lastOrNull { it.issueId == issueId && sentBack(it) }?.data?.get(ReactionKeys.FINDINGS)

    /** True when [event] is a reaction whose retry was scheduled. */
    private fun sentBack(event: RecordedEvent): Boolean = event.kind == ReactionTriggered.name && ReactionKeys.FINDINGS in event.data

    /** True when [event] shows a reset of its issue's failure counter. */
    private fun resets(event: RecordedEvent): Boolean =
        when (event.kind) {
            IssueParked.name -> true
            RetryDropped.name -> event.data[RetryDropped.REASON].let { it == ExitReason.TERMINAL || it == ExitReason.NOT_ACTIVE }
            RetryScheduled.name -> event.data[RetryScheduled.KIND] == RetryScheduled.CONTINUATION
            else -> false
        }
}
