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
import loop.core.events.HumanTouched
import loop.core.events.QuestionAnswered
import loop.core.events.Tracker
import loop.core.waiting.Answers
import loop.core.waiting.Wait
import loop.core.waiting.Waits
import loop.store.SqliteEventStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `loop answer <issue> "<text>" [--workflow WORKFLOW.md]`: answers the question of an issue waiting as `Waiting(ask)`
 * (m4-52, story 128). It marks the wait answered in the store, records `question_answered` and a `human_touch`, then
 * comments the answer on the issue. The running Loop, or the next one to start, dispatches the issue again in its kept
 * workspace, and that attempt's first prompt carries the question and the answer as `loop.answer` with
 * `loop.retry_kind` = `answer`. The project and the issue are resolved like `loop approve`: a bare id is the primary
 * tracker's, a namespaced one names its tracker. It refuses to run inside an attempt, see [insideAttempt]. Exit codes:
 * 1 startup, 2 unknown issue, 3 store, 4 not waiting for an answer, 5 inside an attempt, 6 empty answer.
 */
class AnswerCommand(
    private val host: Host,
) : GuardedCommand("answer", host) {
    private val issue by argument(name = "issue", help = "The issue identifier")
    private val text by argument(name = "answer", help = "The answer to the agent's question")
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Answer the question of an issue waiting for an answer"

    override fun body(scope: RedactorScope) {
        // Before anything is read: an agent must not answer its own question, whatever path reached this command.
        if (insideAttempt(host.rawEnv)) {
            fail("refused inside a Loop attempt, only an operator answers a question. Nothing changed", EXIT_IN_ATTEMPT)
        }
        val answer = text.trim().ifEmpty { fail("the answer is empty. Nothing changed", EXIT_EMPTY) }
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
        // A tracker that cannot be read is not fatal: the waiting records can still name the issue.
        lookup.exceptionOrNull()?.let { error ->
            echo(
                redact(
                    "loop answer: cannot read \"$issue\" in ${trackerLocation(workflow)}: ${error.sentence()}. " +
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
            val waiting = record ?: fail("$identifier is not waiting for an answer. Nothing changed", EXIT_NOT_WAITING)
            checkAsk(identifier, waiting)
            val by = operator()
            val now = Clock.System.now()
            // Null when the running Loop ended the wait in between, for example after a human moved the issue.
            val answered =
                runBlocking { waits.answer(waiting.issueId, by, answer, now) }
                    .getOrElse { fail("cannot write the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                    ?.let { checkAsk(identifier, it, answeredAt = now) }
                    ?: fail("$identifier is not waiting for an answer. Nothing changed", EXIT_NOT_WAITING)
            val name = ids.name(answered.issueId, answered.identifier)
            record(store, answered, by, answer.length, now)?.let { error ->
                echo(redact("loop answer: the answer was not recorded in the store at $storeFile: ${error.sentence()}."), err = true)
            }
            // The answer is in the store, so it reaches the agent even when the comment cannot be posted.
            val comment = IssueComment(by, now, redact(Answers.comment(by, answer)))
            postComment(tracker, answered.issueId, comment)?.let { error ->
                echo(redact("loop answer: the answer was not commented on $name: ${error.sentence()}."), err = true)
            }
            echo("Answered $name. Loop dispatches it again with the answer on its next tick.")
        }
    }

    /** Records `question_answered` and the human touch of the answer, under the attempt that asked. The failure, null once both are in. */
    private fun record(
        store: SqliteEventStore,
        wait: Wait.Ask,
        by: String,
        chars: Int,
        now: Instant,
    ): Throwable? {
        val event =
            QuestionAnswered.event(
                ts = now,
                projectId = wait.projectId,
                data = mapOf(Tracker.BY to by, Tracker.CHARS to chars.toString()),
                issueId = wait.issueId,
                issueIdentifier = wait.identifier,
                attemptId = wait.attemptId,
            )
        // An answer is a human touch, counted by the next ledger row of the issue (m2-19).
        val touch =
            HumanTouched.event(
                ts = now,
                projectId = wait.projectId,
                data = mapOf(HumanTouched.TOUCH to HumanTouch.ANSWER),
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

    /** Who answers: the OS user of the environment, or [UNKNOWN_HUMAN] when it names none. */
    private fun operator(): String =
        listOf("USER", "LOGNAME")
            .firstNotNullOfOrNull { name -> host.rawEnv[name]?.takeIf { it.isNotBlank() } }
            ?: UNKNOWN_HUMAN

    /**
     * Fails unless [wait] is an ask wait not answered yet. With [answeredAt], [wait] is as the store returned it after
     * the write, and it must be answered at that instant: an answer written in between by another `loop answer` wins.
     */
    private fun checkAsk(
        identifier: String,
        wait: Wait,
        answeredAt: Instant? = null,
    ): Wait.Ask {
        val ours = answeredAt != null && wait is Wait.Ask && wait.toRecord().data[WaitingIssue.ANSWERED_AT] == answeredAt.toString()
        val refusal = Answers.refusal(wait)
        if (refusal != null && !ours) fail("$identifier $refusal. Nothing changed", EXIT_NOT_WAITING)
        return wait as Wait.Ask
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop answer: $message."), err = true)
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
    }
}
