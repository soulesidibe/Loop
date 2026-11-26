package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.HumanTouch
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.WaitingIssue
import loop.core.events.Delivery
import loop.core.events.HumanTouched
import loop.core.events.SentBack
import loop.core.waiting.SendBacks
import loop.core.waiting.Wait
import loop.core.waiting.Waits
import loop.store.SqliteEventStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `loop send-back <issue> --reason "<text>" [--workflow WORKFLOW.md]`: sends back an issue waiting as
 * `Waiting(approval)`, approved or not (m4-32, story 129, bl-89's `loop reject`, bl-99). It marks the wait sent back in
 * the store, which drops its approval, records `sent_back` and a `human_touch` `send_back`, then comments the reason on
 * the issue. The running Loop, or the next one to start, dispatches the issue again in its kept workspace with no
 * `max_attempts` slot, and that attempt's prompt carries the reason as `loop.verify_findings` with `loop.retry_kind` =
 * `sent_back`. An open pull request stays open, and the next delivery updates it. The project and the issue are resolved
 * like `loop approve`: a bare id is the primary tracker's, a namespaced one names its tracker. It refuses to run inside
 * an attempt, see [insideAttempt]. Exit codes: 1 startup, 2 unknown issue, 3 store, 4 not waiting for approval, 5 inside
 * an attempt, 6 empty reason, 7 waiting for its merge, 8 waiting for an answer.
 */
class SendBackCommand(
    private val host: Host,
) : GuardedCommand("send-back", host) {
    private val issue by argument(name = "issue", help = "The issue identifier")
    private val text by option("--reason", help = "Why the work goes back, shown to the next attempt").default("")
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Send back an issue waiting for approval, with a reason for the next attempt"

    override fun body(scope: RedactorScope) {
        // Before anything is read: an agent must not send its own work back, whatever path reached this command.
        if (insideAttempt(host.rawEnv)) {
            fail(
                "refused inside a Loop attempt, only an operator sends an issue back, " +
                    "an agent hands its issue off with loop tool move. Nothing changed",
                EXIT_IN_ATTEMPT,
            )
        }
        val reason = text.trim().ifEmpty { fail("the reason is empty. Nothing changed", EXIT_EMPTY) }
        val project = host.openProject(workflowPath, Access.WRITE, scope)
        val ids = project.ids
        // m4-47: a bare id is the primary tracker's, a namespaced one names a tracker of the project.
        val typed = ids.typed(issue).getOrElse { fail("${it.message}. Nothing changed", EXIT_ISSUE) }
        val workflow = project.workflow
        // bl-221: every tracker of the project, so the reason is commented on the tracker the issue belongs to.
        val tracker = project.trackerPoll()
        val lookup =
            try {
                runBlocking { tracker.fetchIssuesByIds(listOf(typed.value)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        // A tracker that cannot be read is not fatal: the waiting records can still name the issue.
        lookup.exceptionOrNull()?.let { error ->
            echo(
                redact(
                    "loop send-back: cannot read \"$issue\" in ${trackerLocation(workflow)}: ${error.sentence()}. " +
                        "Looking for it among the waiting issues.",
                ),
                err = true,
            )
        }
        val found = lookup.getOrNull()?.firstOrNull()
        val projectId = project.projectId
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        store.use {
            val waits = Waits(store, projectId)
            val records =
                runBlocking { waits.read() }
                    .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
            val record =
                if (found != null) {
                    records.firstOrNull { it.issueId == found.id }
                } else {
                    records.firstOrNull { ids.matches(typed, it.issueId, it.identifier) }
                        ?: fail("no issue \"$issue\" in ${trackerLocation(workflow)}", EXIT_ISSUE)
                }
            val identifier = found?.let { ids.name(it.id, it.identifier) } ?: issue
            val waiting = record ?: fail("$identifier ${SendBacks.NOT_WAITING}. Nothing changed", EXIT_NOT_WAITING)
            checkApproval(identifier, waiting)
            val by = operator()
            val now = Clock.System.now()
            // Null when the running Loop ended the wait in between, for example after it delivered it.
            val sent =
                runBlocking { waits.sendBack(waiting.issueId, by, reason, now) }
                    .getOrElse { fail("cannot write the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                    ?.let { checkApproval(identifier, it, sentBackAt = now) }
                    ?: fail("$identifier ${SendBacks.NOT_WAITING}. Nothing changed", EXIT_NOT_WAITING)
            val name = ids.name(sent.issueId, sent.identifier)
            record(store, sent, by, now)?.let { error ->
                echo(redact("loop send-back: the send back was not recorded in the store at $storeFile: ${error.sentence()}."), err = true)
            }
            // The reason is in the store, so it reaches the agent even when the comment cannot be posted.
            val comment = IssueComment(by, now, redact(SendBacks.comment(by, reason)))
            postComment(tracker, sent.issueId, comment)?.let { error ->
                echo(redact("loop send-back: the reason was not commented on $name: ${error.sentence()}."), err = true)
            }
            echo("Sent back $name. Loop dispatches it again with the reason on its next tick.")
        }
    }

    /**
     * Records `sent_back` and the human touch of the send back, under the attempt that asked for approval, with the
     * reason as the store kept it, redacted. The failure, null once both are in.
     */
    private fun record(
        store: SqliteEventStore,
        wait: Wait.Approval,
        by: String,
        now: Instant,
    ): Throwable? {
        val event =
            SentBack.event(
                ts = now,
                projectId = wait.projectId,
                data = mapOf(Delivery.BY to by, Delivery.REASON to wait.sentBackReason.orEmpty()),
                issueId = wait.issueId,
                issueIdentifier = wait.identifier,
                attemptId = wait.attemptId,
            )
        // A send back is a human touch, counted by the next ledger row of the issue (m2-19).
        val touch =
            HumanTouched.event(
                ts = now,
                projectId = wait.projectId,
                data = mapOf(HumanTouched.TOUCH to HumanTouch.SEND_BACK),
                issueId = wait.issueId,
                issueIdentifier = wait.identifier,
                attemptId = wait.attemptId,
            )
        return runBlocking { store.record(event).mapCatching { store.record(touch).getOrThrow() } }.exceptionOrNull()
    }

    /** Posts [comment] on [issueId] through [tracker]. The failure, null once it is posted. */
    private fun postComment(
        tracker: TrackerAdapter,
        issueId: String,
        comment: IssueComment,
    ): Throwable? =
        try {
            runBlocking { tracker.addComment(issueId, comment) }.exceptionOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

    /** Who sends it back: the OS user of the environment, or [UNKNOWN_HUMAN] when it names none. */
    private fun operator(): String =
        listOf("USER", "LOGNAME")
            .firstNotNullOfOrNull { name -> host.rawEnv[name]?.takeIf { it.isNotBlank() } }
            ?: UNKNOWN_HUMAN

    /**
     * Fails unless [wait] is an approval wait not sent back yet, with the exit code of its refusal. With [sentBackAt],
     * [wait] is as the store returned it after the write, and it must be sent back at that instant: a send back written
     * in between by another `loop send-back` wins.
     */
    private fun checkApproval(
        identifier: String,
        wait: Wait,
        sentBackAt: Instant? = null,
    ): Wait.Approval {
        val ours =
            sentBackAt != null && wait is Wait.Approval && wait.toRecord().data[WaitingIssue.SENT_BACK_AT] == sentBackAt.toString()
        val refusal = SendBacks.refusal(wait)
        if (refusal != null && !ours) {
            val code =
                when (refusal.kind) {
                    SendBacks.Kind.NotWaiting -> EXIT_NOT_WAITING
                    SendBacks.Kind.Merge -> EXIT_MERGE
                    SendBacks.Kind.Ask -> EXIT_ASK
                }
            fail("$identifier ${refusal.message}. Nothing changed", code)
        }
        return wait as Wait.Approval
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop send-back: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        /** The operator when the environment names no user. */
        const val UNKNOWN_HUMAN = "human"
        const val EXIT_ISSUE = 2
        const val EXIT_STORE = 3
        const val EXIT_NOT_WAITING = 4
        const val EXIT_IN_ATTEMPT = 5
        const val EXIT_EMPTY = 6
        const val EXIT_MERGE = 7
        const val EXIT_ASK = 8
    }
}
