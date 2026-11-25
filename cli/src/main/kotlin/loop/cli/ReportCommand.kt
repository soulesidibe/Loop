package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.ProjectId
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.IssueMoved
import loop.core.events.Tracker
import loop.core.events.Verdict
import loop.store.SqliteEventStore
import loop.store.StoredEvent
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * `loop report [--since <duration>] [--workflow WORKFLOW.md]` (m4-38, PLAN 6.8 and 10.4): what the project of the
 * workflow delivered and spent over the window, read from the ledger rows and the events of the store. An issue counts
 * as delivered when a verified move by host, an `issue_moved` with `by` = `host` and `verified` = `true`, falls in the
 * window; its cost is the sum of every attempt's cost, whatever their date. Read only: the store keeps
 * [loop.api.Redactor.NONE] and is never created. Exit 0 printed, 1 startup failure, 2 usage, 3 store failure.
 */
class ReportCommand(
    private val host: Host,
) : GuardedCommand("report", host) {
    private val since by option(
        "--since",
        metavar = "DURATION",
        help = "How far back the report reads, <n>d, <n>h or <n>m, default 7d",
    ).convert { text -> ReportWindow.parse(text) ?: fail("expected <n>d, <n>h or <n>m with n a positive whole number, like 7d or 24h") }
        .default(ReportWindow.DEFAULT, defaultForHelp = ReportWindow.DEFAULT.label)
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Print what the project delivered and spent over a window of time"

    override fun body(scope: RedactorScope) {
        val project = host.openProject(workflowPath, Access.READ, scope)
        val start = Clock.System.now() - since.duration
        val storeFile = project.storeFile
        // A store that does not exist yet holds nothing to report, and opening it would create it.
        if (!Files.exists(storeFile)) {
            echo(LedgerReport(project.projectId, start, since.label, emptyList(), emptyList(), emptyList()).render())
            return
        }
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        val report =
            store.use {
                runBlocking { read(store, project, start) }.getOrElse {
                    fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE)
                }
            }
        echo(report.render())
    }

    /** The facts of the report, every read of the store in one result. */
    private suspend fun read(
        store: SqliteEventStore,
        project: OpenedProject,
        start: Instant,
    ): Result<LedgerReport> =
        runCatching {
            val projectId = project.projectId
            val events = store.eventsOfKindsSince(projectId, setOf(IssueMoved.name, Verdict.name), start).getOrThrow()
            val moves =
                events.filter {
                    val data = it.event.data
                    it.event.kind == IssueMoved.name &&
                        it.event.issueId != null &&
                        data[Tracker.BY] == Tracker.BY_HOST &&
                        data[Tracker.VERIFIED] == "true"
                }
            val delivered =
                moves.distinctBy { it.event.issueId }.map { move ->
                    val issueId = move.event.issueId!!
                    val rows = store.attemptRows(projectId, issueId).getOrThrow()
                    val identifier = move.event.issueIdentifier ?: rows.lastOrNull()?.attempt?.identifier ?: issueId
                    DeliveredIssue(project.ids.name(issueId, identifier), rows)
                }
            val refutations =
                events
                    .filter { it.event.kind == Verdict.name && it.event.data[Verdict.VERDICT] == REFUTED }
                    .map { verdict -> refutationReason(verdict, eventsBefore(store, projectId, verdict)) }
            val attempts = store.attemptRowsSince(projectId, start).getOrThrow()
            LedgerReport(projectId, start, since.label, delivered, refutations, attempts)
        }

    /** The events of the attempt of [verdict], or of its issue outside any attempt for a merge check with none. */
    private suspend fun eventsBefore(
        store: SqliteEventStore,
        projectId: ProjectId,
        verdict: StoredEvent,
    ): List<StoredEvent> {
        val attemptId = verdict.event.attemptId
        val issueId = verdict.event.issueId
        return when {
            attemptId != null -> store.eventsForAttempt(attemptId).getOrThrow()
            issueId != null -> store.eventsForIssue(projectId, issueId).getOrThrow().filter { it.event.attemptId == null }
            else -> emptyList()
        }
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop report: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val EXIT_STORE = 3
        const val REFUTED = "refuted"
    }
}

/** The window of `loop report --since`: [label] as typed, like `7d`, and its [duration]. */
data class ReportWindow(
    val label: String,
    val duration: Duration,
) {
    companion object {
        private val FORM = Regex("""([1-9][0-9]*)([dhm])""")

        val DEFAULT: ReportWindow = ReportWindow("7d", 7.days)

        /** `<n>d`, `<n>h` or `<n>m` with n a positive whole number, null for anything else. */
        fun parse(text: String): ReportWindow? {
            val match = FORM.matchEntire(text) ?: return null
            val count = match.groupValues[1].toIntOrNull() ?: return null
            val duration =
                when (match.groupValues[2]) {
                    "d" -> count.days
                    "h" -> count.hours
                    else -> count.minutes
                }
            return ReportWindow(text, duration)
        }
    }
}

/**
 * Why [verdict], a refuted one, refuted (m4-38), from the [events] of its attempt: the last `check_finished` since the
 * attempt's previous verdict when it failed, since verification stops at the first check that fails twice; else the
 * fields of the verdict itself (files not committed, report findings, no deliverable file). A merge check says so.
 */
internal fun refutationReason(
    verdict: StoredEvent,
    events: List<StoredEvent>,
): String {
    val previous = events.filter { it.event.kind == Verdict.name && it.seq < verdict.seq }.maxOfOrNull { it.seq } ?: 0L
    val last = events.lastOrNull { it.event.kind == CheckFinished.name && it.seq > previous && it.seq < verdict.seq }
    val data = verdict.event.data
    val reason =
        last?.let { failedCheck(it.event.data) }
            ?: data[Verdict.UNCOMMITTED]?.let { if (it == "1") "1 file not committed" else "$it files not committed" }
            ?: data[Verdict.REPORT_FINDINGS]?.let { if (it == "1") "1 report finding" else "$it report findings" }
            ?: "no deliverable file".takeIf { data[Verdict.DELIVERABLE] == "0" }
            ?: "reason not recorded"
    return if (data[Verdict.MERGE_CHECK] == "true") "$reason in a merge check" else reason
}

/** What a failed check's `check_finished` [data] says, null when the check did not fail. */
private fun failedCheck(data: Map<String, String>): String? =
    when {
        data[CheckEvent.CANCELED] == "true" -> null
        data[CheckEvent.HOLDOUT] == "true" -> "a hidden check failed".takeIf { data[CheckEvent.RESULT] == "failed" }
        data[CheckEvent.TIMED_OUT] == "true" -> "check ${data[CheckEvent.COMMAND]} timed out"
        data[CheckEvent.EXIT_CODE].let { it != null && it != "0" } -> "check ${data[CheckEvent.COMMAND]} failed"
        else -> null
    }
