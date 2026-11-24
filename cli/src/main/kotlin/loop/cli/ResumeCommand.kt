package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.HumanTouch
import loop.api.Issue
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.core.events.AcceptanceCommands
import loop.core.events.AcceptanceCommandsAccepted
import loop.core.events.HumanTouched
import loop.core.events.IssueParked
import loop.core.events.IssueUnparked
import loop.core.verify.AcceptanceCriteria
import loop.core.verify.commands
import loop.store.SqliteEventStore
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * `loop resume <issue> [--workflow WORKFLOW.md]`: unparks an issue Loop parked, whatever
 * the reason (`max_attempts`, `budget`, `max_continuations`, `unverified`, or `token_cap` stored by a
 * Loop before m2-20), so it can be dispatched again on the next tick (m1-07). Its counters start again from zero. The project is
 * resolved like `loop trace`. The issue is looked up in the tracker first, then by
 * identifier among the parked issues, so an issue the tracker no longer has can still
 * be resumed. Records `issue_unparked` with reason `resume`, then a `human_touch` (m2-19). An issue parked because its
 * acceptance criteria no longer hold the commands frozen at its first dispatch (m2-09) has its changed list accepted as
 * the frozen one, recorded as `acceptance_commands_accepted`, only when the issue as read now still holds that
 * list; the accepted commands are printed, redacted. A resume of an issue parked for another reason leaves the
 * frozen list as it is. It refuses to run inside an attempt, see [insideAttempt] (m2-24). Exit codes: 1 startup,
 * 2 unknown issue, 3 store, 4 not parked, 5 inside an attempt.
 */
class ResumeCommand(
    private val host: Host,
) : GuardedCommand("resume", host) {
    private val issue by argument(name = "issue", help = "The issue identifier")
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Make a parked issue dispatchable again"

    override fun body(scope: RedactorScope) {
        // Before anything is read: an agent must not resume an issue, whatever path reached this command (m2-24).
        if (insideAttempt(host.rawEnv)) {
            fail("refused inside a Loop attempt, only an operator resumes a parked issue. Nothing changed", EXIT_IN_ATTEMPT)
        }
        val project = host.openProject(workflowPath, Access.WRITE, scope)
        val ids = project.ids
        // m4-47: a bare id is the primary tracker's, a namespaced one names a tracker of the project.
        val typed = ids.typed(issue).getOrElse { fail("${it.message}. Nothing changed", EXIT_ISSUE) }
        val workflow = project.workflow
        val tracker = project.tracker()
        val lookup =
            try {
                runBlocking { tracker.fetchIssuesByIds(listOf(typed.value)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        // A tracker that cannot be read is not fatal: the parked records can still name the issue.
        lookup.exceptionOrNull()?.let { error ->
            echo(
                redact(
                    "loop resume: cannot read \"$issue\" in ${trackerLocation(workflow)}: ${error.sentence()}. " +
                        "Looking for it among the parked issues.",
                ),
                err = true,
            )
        }
        val found = lookup.getOrNull()?.firstOrNull()
        val projectId = project.projectId
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        store.use {
            val parked =
                runBlocking { store.list(projectId) }
                    .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
            val record =
                if (found != null) {
                    parked.firstOrNull { it.issueId == found.id }
                } else {
                    parked.firstOrNull { ids.matches(typed, it.issueId, it.identifier) }
                        ?: fail("no issue \"$issue\" in ${trackerLocation(workflow)}", EXIT_ISSUE)
                }
            val identifier = found?.let { ids.name(it.id, it.identifier) } ?: issue
            if (record == null) fail("$identifier is not parked. Nothing changed", EXIT_NOT_PARKED)
            // m2-09: read before anything changes, so a store that cannot say why the issue parked changes nothing.
            val changedPark = parkedForChangedCommands(store, record, storeFile)
            // Null when another process resumed it in between.
            val removed =
                runBlocking { store.unpark(projectId, record.issueId) }
                    .getOrElse { fail("cannot write the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                    ?: fail("$identifier is not parked. Nothing changed", EXIT_NOT_PARKED)
            val event =
                IssueUnparked.event(
                    ts = Clock.System.now(),
                    projectId = projectId,
                    data = mapOf(IssueUnparked.REASON to IssueUnparked.RESUME),
                    issueId = removed.issueId,
                    issueIdentifier = removed.identifier,
                )
            // m2-19: a resume is a human touch, counted by the next ledger row of the issue.
            val touch =
                HumanTouched.event(
                    ts = event.ts,
                    projectId = projectId,
                    data = mapOf(HumanTouched.TOUCH to HumanTouch.RESUME),
                    issueId = removed.issueId,
                    issueIdentifier = removed.identifier,
                )
            val recorded = runBlocking { store.record(event).mapCatching { store.record(touch).getOrThrow() } }
            recorded.exceptionOrNull()?.let { error ->
                echo(redact("loop resume: the resume was not recorded in the store at $storeFile: ${error.sentence()}."), err = true)
            }
            val since = removed.parkedAt.toString().take(SECONDS_PREFIX) + "Z"
            val shown = ids.name(removed.issueId, removed.identifier)
            echo(
                "Resumed $shown, parked since $since (${removed.reason.wire}). " +
                    "It can be dispatched again on the next tick.",
            )
            // m2-09: after the unpark, so a failure here only parks the issue again on its next verification.
            // A park written in between by another process is not the one read above: nothing is accepted.
            if (changedPark && removed.parkedAt == record.parkedAt) acceptChangedCommands(store, removed, shown, found, storeFile)
        }
    }

    /**
     * Whether the park of [record] is the one of an issue whose acceptance criteria differ from its frozen list
     * (m2-09): the `issue_parked` event of this park, the last one recorded since [ParkedIssue.parkedAt], has the
     * reason `unverified` and the error `acceptance commands changed`. False when that event is missing.
     */
    private fun parkedForChangedCommands(
        store: SqliteEventStore,
        record: ParkedIssue,
        storeFile: Path,
    ): Boolean {
        if (record.reason != ParkReason.Unverified) return false
        val events =
            runBlocking { store.eventsForIssue(record.projectId, record.issueId) }
                .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
        val park = events.map { it.event }.lastOrNull { it.kind == IssueParked.name && it.ts >= record.parkedAt } ?: return false
        return park.data["reason"] == ParkReason.Unverified.wire && park.data["error"] == AcceptanceCriteria.CHANGED_REASON
    }

    /**
     * Accepts the changed acceptance command list of [removed] as its frozen list (m2-09), only when [found], the
     * issue as read now, holds that very list, and prints the commands accepted, redacted. Otherwise nothing is
     * accepted: the next verification compares the criteria with the frozen list again. [shown] names the issue in the
     * lines printed (m4-47).
     */
    private fun acceptChangedCommands(
        store: SqliteEventStore,
        removed: ParkedIssue,
        shown: String,
        found: Issue?,
        storeFile: Path,
    ) {
        if (found == null) {
            echo(
                "Cannot read the acceptance criteria of $shown in the tracker, nothing accepted. " +
                    "Its next verification compares them with the frozen list again.",
            )
            return
        }
        val commands = AcceptanceCriteria.of(found.description).commands
        val accepted =
            runBlocking { store.acceptChanged(removed.projectId, removed.issueId, AcceptanceCriteria.digest(commands)) }
                .getOrElse {
                    fail("cannot accept the changed acceptance commands in the store at $storeFile: ${it.sentence()}", EXIT_STORE)
                }
        if (accepted == null) {
            echo(
                "The acceptance criteria of $shown no longer hold the changed list Loop found, nothing accepted. " +
                    "Its next verification compares them with the frozen list again.",
            )
            return
        }
        val acceptedEvent =
            AcceptanceCommandsAccepted.event(
                ts = Clock.System.now(),
                projectId = removed.projectId,
                data = mapOf(AcceptanceCommands.COUNT to accepted.count.toString()),
                issueId = removed.issueId,
                issueIdentifier = removed.identifier,
            )
        runBlocking { store.record(acceptedEvent) }.exceptionOrNull()?.let { error ->
            echo(
                redact("loop resume: the accepted commands were not recorded in the store at $storeFile: ${error.sentence()}."),
                err = true,
            )
        }
        val listed =
            if (commands.isEmpty()) {
                "Accepted the changed acceptance commands of $shown: its acceptance criteria hold no command now."
            } else {
                "Accepted the changed acceptance commands of $shown, checked from its next verification:\n" +
                    commands.withIndex().joinToString("\n") { (index, command) -> "  ${index + 1}. $command" }
            }
        echo(redact(listed))
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop resume: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val SECONDS_PREFIX = 19
        const val EXIT_ISSUE = 2
        const val EXIT_STORE = 3
        const val EXIT_NOT_PARKED = 4
        const val EXIT_IN_ATTEMPT = 5
    }
}
