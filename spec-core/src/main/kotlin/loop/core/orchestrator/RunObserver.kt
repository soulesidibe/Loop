package loop.core.orchestrator

import loop.api.EventSink
import loop.api.RecordedEvent

/**
 * Follows what a run does, for the human readable run log (m1-39). The orchestrator calls it on its
 * own thread; an implementation never throws and never blocks for long.
 */
interface RunObserver {
    /** Every event the run records, as the store gets it, also when the store write fails. */
    fun recorded(event: RecordedEvent) = Unit

    /**
     * One runner line in full, already redacted, with the `runner_output` [event] that records it. The
     * recorded event's line is cut at 2000 characters; [line] is not, so a long tool result keeps its end.
     */
    fun runnerLine(
        event: RecordedEvent,
        line: String,
    ) = Unit

    /**
     * A poll found the issues [eligible] (identifiers, dispatch order) while [running] attempts ran, out of
     * [maxRunning] at once (`agent.max_concurrent_agents`).
     */
    fun polled(
        eligible: List<String>,
        running: Int,
        maxRunning: Int,
    ) = Unit

    companion object {
        /** Follows nothing. */
        val NONE: RunObserver = object : RunObserver {}
    }
}

/** Shows each event to [observer], then records it in [delegate]; the observer never changes the result. */
internal class ObservedSink(
    private val delegate: EventSink,
    private val observer: RunObserver,
) : EventSink {
    override suspend fun record(event: RecordedEvent): Result<Unit> {
        observer.recorded(event)
        return delegate.record(event)
    }
}
