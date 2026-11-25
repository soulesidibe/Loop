package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.AttemptId
import loop.api.AttemptRecord
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.RunnerOutput
import loop.core.events.SessionRotated
import loop.core.events.Usage
import loop.core.orchestrator.attemptFile
import loop.core.orchestrator.attemptsFolder
import loop.core.trackers.IssueIdDisplay
import loop.core.verify.EvidenceFiles
import loop.store.AttemptRecordLine
import loop.store.GzipAttemptRecords
import loop.store.StoredEvent

/**
 * `loop show <attempt> --prompt | --events | --cost | --evidence [--check <n>]`: one attempt of the project
 * in depth (m1-13). The attempt is its full id or a unique prefix of it, such as the short id `loop trace`
 * prints. `--prompt` reads the Attempt file, `--evidence` an evidence file beside it (m2-05), the other
 * views read the store, and `--events` takes the full runner lines from the Attempt file (m2-18).
 */
class ShowCommand(
    private val host: Host,
) : GuardedCommand("show", host) {
    private val attempt by argument(name = "attempt", help = "The attempt id, or a unique prefix of it")
    private val view by option(help = "The view to print, one per call")
        .switch(
            "--prompt" to View.PROMPT,
            "--events" to View.EVENTS,
            "--cost" to View.COST,
            "--evidence" to View.EVIDENCE,
        )
    private val check by option("--check", help = "With --evidence, the check whose latest evidence to print").int()
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Print one recorded attempt in depth"

    override fun body(scope: RedactorScope) {
        val chosen = view ?: throw UsageError("pick a view: --prompt, --events, --cost or --evidence")
        if (check != null && chosen != View.EVIDENCE) throw UsageError("--check goes with --evidence only")
        // As `loop trace`: only the messages go through the project's redactor; the store is read only and keeps Redactor.NONE.
        val project = host.openProject(workflowPath, Access.READ, scope)
        val projectId = project.projectId
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        val (attemptId, events) =
            store.use {
                val attemptId = resolveAttempt(store, projectId, attempt, storeFile, ::fail)
                attemptId to
                    runBlocking { store.eventsForAttempt(attemptId) }
                        .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                        // An attempt id is a UUID, but the store is shared by every project: keep this project's rows only.
                        .filter { it.event.projectId == projectId }
            }
        when (chosen) {
            View.PROMPT -> echo(prompt(projectId, attemptId))
            View.EVENTS -> {
                val full = fullRunnerLines(projectId, attemptId, events)
                events.forEach { echo(jsonLine(it, full, project.ids)) }
            }
            View.COST -> echo(cost(events.map { it.event }, project.ids))
            View.EVIDENCE -> echo(evidence(projectId, events.map { it.event }))
        }
    }

    /**
     * The evidence file of the last check run in the attempt, or of the latest run of [check] when given,
     * as written: redacted before it was written, so printed as is (m2-05). The `check_finished` events of
     * the store name the files.
     */
    private fun evidence(
        projectId: ProjectId,
        events: List<RecordedEvent>,
    ): String {
        val name =
            events
                .filter { it.kind == CheckFinished.name && it.data[CheckEvent.EVIDENCE] != null }
                .lastOrNull { check == null || it.data["check"] == check.toString() }
                ?.data
                ?.get(CheckEvent.EVIDENCE)
                ?: return check?.let { "No evidence recorded for check $it of this attempt." } ?: "No evidence recorded for this attempt."
        val file = attemptsFolder(host.loopHome, projectId).resolve(name)
        return EvidenceFiles(host.loopHome, Redactor.NONE)
            .read(projectId, name)
            .getOrElse { fail("cannot read the evidence file at $file: ${it.sentence()}", EXIT_STORE) }
            .removeSuffix("\n")
    }

    /**
     * The turn 1 rendered prompt from the Attempt file, as written: redacted before it was written, so
     * printed as is. An attempt recorded before the prompt was (or whose write failed) has none.
     */
    private fun prompt(
        projectId: ProjectId,
        attemptId: AttemptId,
    ): String {
        val prompt =
            records(projectId, attemptId, damagedNote = "left out")
                .map { it.record }
                .filterIsInstance<AttemptRecord.Prompt>()
                .firstOrNull()
        return prompt?.text ?: "No prompt recorded for this attempt."
    }

    /**
     * The full text of each cut `runner_output` event, by its `record_seq`, from the `runner_output` records of
     * the Attempt file (m2-18). Empty when no event was cut, so the file is read only when it matters.
     */
    private fun fullRunnerLines(
        projectId: ProjectId,
        attemptId: AttemptId,
        events: List<StoredEvent>,
    ): Map<Long, String> {
        if (events.none { it.event.kind == RunnerOutput.name && it.event.data[RunnerOutput.TRUNCATED] == "true" }) return emptyMap()
        return records(projectId, attemptId, damagedNote = "a cut runner line whose record is missing prints its stored preview")
            .mapNotNull { line -> (line.record as? AttemptRecord.RunnerOutput)?.let { line.seq to it.line } }
            .toMap()
    }

    /**
     * The records of the Attempt file, as written; a file that cannot be read at all exits 3. Cut or unreadable
     * records, as a kill, a full disk or a live attempt leave, are left out with a note on stderr that ends with
     * [damagedNote], and the view goes on with the records that read (m2-18).
     */
    private fun records(
        projectId: ProjectId,
        attemptId: AttemptId,
        damagedNote: String,
    ): List<AttemptRecordLine> {
        val file = attemptFile(host.loopHome, projectId, attemptId)
        // Read only: the redactor is never used on read.
        val read =
            GzipAttemptRecords(Redactor.NONE) { project, attempt -> attemptFile(host.loopHome, project, attempt) }
                .read(projectId, attemptId)
                .getOrElse { fail("cannot read the attempt file at $file: ${it.sentence()}", EXIT_STORE) }
        if (read.damaged > 0) {
            val parts = if (read.damaged == 1) "1 cut or unreadable part" else "${read.damaged} cut or unreadable parts"
            echo(redact("loop show: the attempt file at $file has $parts, $damagedNote."), err = true)
        }
        return read.records
    }

    /**
     * One recorded event as a JSON object on one line, every stored field, null fields left out. A cut
     * `runner_output` whose record is in [fullLines] prints that full line, without its `truncated` flag;
     * one whose record is gone prints as stored. The issue id is shown by [ids] (m4-47), bare with no `trackers:`.
     */
    private fun jsonLine(
        stored: StoredEvent,
        fullLines: Map<Long, String>,
        ids: IssueIdDisplay,
    ): String {
        val event = stored.event.withFullLine(fullLines)
        val fields =
            listOf(
                "seq" to JsonPrimitive(stored.seq),
                "ts" to JsonPrimitive(event.ts.toString()),
                "run_id" to event.runId?.value?.let(::JsonPrimitive),
                "project_id" to JsonPrimitive(event.projectId.value),
                "issue_id" to event.issueId?.let { JsonPrimitive(ids.id(it)) },
                "issue_identifier" to event.issueIdentifier?.let(::JsonPrimitive),
                "attempt_id" to event.attemptId?.value?.let(::JsonPrimitive),
                "session_id" to event.sessionId?.value?.let(::JsonPrimitive),
                "turn_id" to event.turnId?.value?.let(::JsonPrimitive),
                "kind" to JsonPrimitive(event.kind),
                "phase" to event.phase?.let(::JsonPrimitive),
                "data" to event.data.takeIf { it.isNotEmpty() }?.let { data -> JsonObject(data.mapValues { JsonPrimitive(it.value) }) },
            )
        return JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()).toString()
    }

    private fun RecordedEvent.withFullLine(fullLines: Map<Long, String>): RecordedEvent {
        if (kind != RunnerOutput.name || data[RunnerOutput.TRUNCATED] != "true") return this
        val line = data[RunnerOutput.RECORD_SEQ]?.toLongOrNull()?.let(fullLines::get) ?: return this
        return copy(data = (data - RunnerOutput.TRUNCATED) + (RunnerOutput.LINE to line))
    }

    /**
     * The attempt line, then the latest `usage` event: absolute totals that replace the earlier ones,
     * never summed (spec-decisions, token accounting). Then the peak occupancy of each session of the attempt
     * (m3-09) and its rotations (m3-11), when it recorded a session. The issue is shown by [ids] (m4-47).
     */
    private fun cost(
        events: List<RecordedEvent>,
        ids: IssueIdDisplay,
    ): String =
        buildString {
            val first = events.first()
            val issue = first.issueIdentifier?.let { ids.name(first.issueId, it) } ?: first.issueId?.let(ids::id)
            appendLine("Attempt ${first.attemptId?.value}  issue $issue")
            val usage = events.lastOrNull { it.kind == Usage.name }?.data
            if (usage == null) {
                append("No usage recorded for this attempt.")
            } else {
                val cap = usage[Usage.MAX_TOKENS]
                val total = usage[Usage.ATTEMPT_TOKENS]?.let { tokens -> cap?.let { "$tokens of a $it token cap" } ?: tokens }
                val rows =
                    listOf(
                        "Tokens" to total,
                        "Input" to usage["input_tokens"],
                        "Output" to usage["output_tokens"],
                        "Cache read" to usage["cache_read_input_tokens"],
                        "Cache creation" to usage["cache_creation_input_tokens"],
                        "Spend" to usage["total_cost_usd"]?.let { "$it USD" },
                    )
                append(rows.joinToString("\n") { (label, value) -> label.padEnd(LABEL_WIDTH) + (value ?: NOT_REPORTED) })
            }
            val sessions = sessionsOf(events)
            if (sessions.isEmpty()) return@buildString
            append("\n\nPeak occupancy per session")
            sessions.forEachIndexed { index, session -> append("\n" + "Session ${index + 1}".padEnd(LABEL_WIDTH) + peak(session)) }
            append("\n" + "Rotations".padEnd(LABEL_WIDTH) + rotations(events))
        }

    /** m3-11: the attempt's rotations, its `session_rotated` events, with the cap the latest one names. */
    private fun rotations(events: List<RecordedEvent>): String {
        val rotated = events.filter { it.kind == SessionRotated.name }
        val cap = rotated.lastOrNull()?.data?.get(SessionRotated.MAX_ROTATIONS) ?: return rotated.size.toString()
        return "${rotated.size} of at most $cap"
    }

    /** One session's peak: its tokens, then its fraction of the window or that the window is unknown, then its thread. */
    private fun peak(session: SessionOccupancy): String {
        val peak = session.peak ?: return "$NOT_REPORTED, thread ${session.thread}"
        val window = session.peakFraction?.let { "$it of a ${session.window} token window" } ?: "window unknown"
        return "$peak tokens, $window, thread ${session.thread}"
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop show: $message."), err = true)
        throw ProgramResult(code)
    }

    private enum class View { PROMPT, EVENTS, COST, EVIDENCE }

    private companion object {
        const val NOT_REPORTED = "not reported"
        const val LABEL_WIDTH = 16
    }
}
