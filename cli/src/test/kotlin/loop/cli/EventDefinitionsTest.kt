package loop.cli

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.core.events.EventDefinition
import loop.core.events.EventDefinitions
import loop.core.events.HumanShow
import loop.core.events.TraceShow
import loop.core.log.HumanLog
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * bl-80: every event kind has one definition, and each renders in `loop trace` and in the human log with its sample data,
 * unless it is marked silent there. A kind left out of the trace or the human log fails here instead of vanishing.
 */
class EventDefinitionsTest {
    private val ts = Instant.parse("2026-11-15T10:20:30Z")

    private fun sample(
        definition: EventDefinition,
        attempt: Boolean = true,
    ): RecordedEvent =
        definition.event(
            ts = ts,
            projectId = ProjectId("p1"),
            data = definition.sample,
            runId = RunId("r1"),
            issueId = "bl-1",
            issueIdentifier = "bl-1",
            attemptId = if (attempt) AttemptId("a1") else null,
            phase = definition.samplePhase,
        )

    private fun humanLines(event: RecordedEvent): List<String> {
        val lines = mutableListOf<String>()
        HumanLog(out = { lines += it }, clock = Clock.System, redactor = Redactor.NONE, zone = ZoneOffset.UTC).recorded(event)
        return lines
    }

    /** True when [line] is the trace line of [name]: the time, two spaces, the name, then nothing or a space and details. */
    private fun traceLineOf(
        line: String,
        name: String,
    ): Boolean = line == "10:20:30  $name" || line.startsWith("10:20:30  $name ")

    @Test
    fun `each kind has one definition, found by its name`() {
        val names = EventDefinitions.all.map { it.name }
        assertEquals(names.distinct(), names)
        EventDefinitions.all.forEach { assertSame(it, EventDefinitions.of(it.name)) }
    }

    @Test
    fun `every definition shown in the trace timeline renders there with its sample data`() {
        val missing =
            EventDefinitions.all
                .filter { it.trace.inTimeline }
                .filterNot { definition ->
                    val event = sample(definition)
                    TraceLines.timeline(listOf(event), fullIds = false).any { traceLineOf(it, definition.traceName(event)) }
                }
        assertEquals(emptyList(), missing.map { it.name })
    }

    @Test
    fun `every issue level definition renders as its own trace line with its sample data`() {
        val missing =
            EventDefinitions.all
                .filter { it.trace.issueLevel }
                .filterNot { definition ->
                    val event = sample(definition, attempt = false)
                    traceLineOf(TraceLines.line(event, fullIds = false), definition.traceName(event))
                }
        assertEquals(emptyList(), missing.map { it.name })
    }

    @Test
    fun `a definition marked silent in the trace stays out of the timeline`() {
        val shown =
            EventDefinitions.all
                .filter { it.trace == TraceShow.SILENT }
                .filter { TraceLines.timeline(listOf(sample(it)), fullIds = false).isNotEmpty() }
        assertEquals(emptyList(), shown.map { it.name })
    }

    @Test
    fun `every definition with a human sentence writes one line with its sample data`() {
        val missing =
            EventDefinitions.all
                .filter { it.human != HumanShow.SILENT }
                .filterNot { definition ->
                    val lines = humanLines(sample(definition))
                    val prefix = if (definition.human == HumanShow.ISSUE) "10:20:30  bl-1  " else "10:20:30  "
                    lines.size == 1 && lines[0].startsWith(prefix) && lines[0].length > prefix.length
                }
        assertEquals(emptyList(), missing.map { it.name })
    }

    @Test
    fun `a definition marked silent in the human log writes no line`() {
        val written =
            EventDefinitions.all
                .filter { it.human == HumanShow.SILENT }
                .filter { humanLines(sample(it)).isNotEmpty() }
        assertEquals(emptyList(), written.map { it.name })
    }
}
