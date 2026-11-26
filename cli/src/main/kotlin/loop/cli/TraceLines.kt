package loop.cli

import loop.api.RecordedEvent
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.EventDefinitions
import loop.core.events.TraceContext
import loop.core.events.TraceText
import loop.core.events.TurnEvent
import loop.core.events.Usage

/**
 * The lines of `loop trace` (plan U1): the attempt header, the timeline of an attempt and the line of one event. The
 * details of each line come from the event's definition (bl-80); this object only lays out the columns.
 */
internal object TraceLines {
    private const val NAME_WIDTH = 23
    private const val TIME_START = 11
    private const val SECONDS_PREFIX = 19

    /** Under the details column, before the attempt block's indent: the time, two spaces, the name column. */
    private val OUTPUT_INDENT = " ".repeat((SECONDS_PREFIX - TIME_START) + 2 + NAME_WIDTH)

    /** True when an event of [kind] recorded outside any attempt prints as its own line. */
    fun issueLevel(kind: String): Boolean = EventDefinitions.of(kind)?.trace?.issueLevel == true

    /**
     * `Attempt <n>  <id>  run <id>  <start>  loop <version>  config <version>  <status>`: the run, the build (m1-12) and
     * the WORKFLOW.md version (m1-15) when recorded, then how it ended, its turns, its spend (m1-08) and why.
     */
    fun header(
        number: Int,
        events: List<RecordedEvent>,
        fullIds: Boolean,
    ): String {
        val context = TraceContext(fullIds)
        val first = events.first()
        val started = events.firstOrNull { it.kind == AttemptStarted.name } ?: first
        val finished = events.lastOrNull { it.kind == AttemptFinished.name }
        // m1-08: the spend of the attempt is its last usage event; old traces have none and print as before.
        val tokens = events.lastOrNull { it.kind == Usage.name }?.let { Usage.attemptTokens(it.data) }
        val status =
            if (finished == null) {
                listOfNotNull("running", tokens?.let { "$it tokens so far" }).joinToString(", ")
            } else {
                val outcome = finished.data[AttemptFinished.OUTCOME] ?: "finished"
                val turns = finished.data[AttemptFinished.TURNS]?.let { count -> "$count ${if (count == "1") "turn" else "turns"}" }
                listOfNotNull(
                    "$outcome in ${(finished.ts - started.ts).inWholeSeconds}s",
                    turns,
                    tokens?.let { "$it tokens" },
                    AttemptFinished.headerReason(finished.data),
                ).joinToString(", ")
            }
        val attemptId = context.id(first.attemptId?.value ?: "")
        val run = first.runId?.let { "  run ${context.id(it.value)}" } ?: ""
        val startedAt = started.ts.toString().take(SECONDS_PREFIX) + "Z"
        val version = started.data[AttemptStarted.LOOP_VERSION]?.let { "  loop $it" } ?: ""
        val config = started.data[AttemptStarted.CONFIG_VERSION]?.let { "  config ${context.id(it)}" } ?: ""
        return "Attempt $number  $attemptId$run  $startedAt$version$config  $status"
    }

    /**
     * One line per timeline event of the attempt (plan U1, m1-07, m1-04 plan R1), without the attempt block's indent,
     * each followed by its output lines, so far the last output lines of a failed or timed out hook.
     */
    fun timeline(
        events: List<RecordedEvent>,
        fullIds: Boolean,
    ): List<String> {
        // The thread is known once its first turn starts, so the launch line borrows it.
        val thread = events.firstNotNullOfOrNull { it.data[TurnEvent.THREAD_ID] }
        return events.flatMap { event ->
            val definition = EventDefinitions.of(event.kind)
            if (definition == null || !definition.showsInTimeline(event)) {
                emptyList()
            } else {
                listOf(line(event, fullIds, thread)) + definition.traceOutput(event).map { "$OUTPUT_INDENT$it".trimEnd() }
            }
        }
    }

    /**
     * The line of an event recorded outside any attempt, followed by its output lines like a timeline line, so a failed
     * `before_remove` shows its last output lines (bl-04).
     */
    fun issueLevelLines(
        event: RecordedEvent,
        fullIds: Boolean,
    ): List<String> {
        val output = EventDefinitions.of(event.kind)?.traceOutput(event).orEmpty()
        return listOf(line(event, fullIds)) + output.map { "$OUTPUT_INDENT$it".trimEnd() }
    }

    /** `HH:MM:SS  name  details`, the details from the event's definition, every field for a kind Loop does not know. */
    fun line(
        event: RecordedEvent,
        fullIds: Boolean,
        thread: String? = null,
    ): String {
        val context = TraceContext(fullIds, thread)
        val definition = EventDefinitions.of(event.kind)
        val name = definition?.traceName(event) ?: event.kind
        val details =
            definition?.traceDetails(event, context)
                ?: event.data.map { (key, value) -> "$key=${TraceText.quote(value)}" }
        val time = TraceText.clock(event.ts.toString())
        // At least one space after a name as wide as the column or wider (issue_comment_requested, m1-23).
        return "$time  ${name.padEnd(maxOf(NAME_WIDTH, name.length + 1))}${details.joinToString(" ")}".trimEnd()
    }
}
