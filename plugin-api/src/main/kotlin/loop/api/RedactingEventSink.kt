package loop.api

/**
 * An [EventSink] that passes each event to [sink] with the text the event store redacts replaced by [redactor]: the
 * issue identifier, the phase and every data value (m4-42). The store redacts inside its own `record`, so a sink beside
 * it, an exporter for one, would otherwise get raw events, runner output and comments included. The ids, the kind and
 * the data keys are Loop's own and pass as they are.
 */
public class RedactingEventSink(
    private val redactor: Redactor,
    private val sink: EventSink,
) : EventSink {
    override suspend fun record(event: RecordedEvent): Result<Unit> =
        sink.record(
            event.copy(
                issueIdentifier = event.issueIdentifier?.let(redactor::redact),
                phase = event.phase?.let(redactor::redact),
                data = event.data.mapValues { redactor.redact(it.value) },
            ),
        )
}
