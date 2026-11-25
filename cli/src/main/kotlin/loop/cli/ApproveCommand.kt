package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.HumanTouch
import loop.api.Issue
import loop.core.events.ApprovalGiven
import loop.core.events.Delivery
import loop.core.events.HumanTouched
import loop.core.kinds.KindLookup
import loop.core.kinds.PinnedKind
import loop.core.waiting.Wait
import loop.core.waiting.Waits
import loop.core.workflow.WorkflowFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * `loop approve <issue> [--workflow WORKFLOW.md]`: approves the delivery of a verified result that waits as
 * `Waiting(approval)` under the `human` delivery policy (m2-24, story 78). It marks the wait approved in the store
 * and records `approval_given` with the approver, then a `human_touch`; the running Loop, or the next one to start,
 * delivers it on its next tick, so the approval survives a restart. Under `delivery.pull_request.merge` the messages and
 * `approval_given` name `on_delivered.move_to`, where the merged issue ends (bl-69). The project and the issue are resolved like
 * `loop resume`. It refuses to run inside an attempt, see [insideAttempt]. Exit codes: 1 startup, 2 unknown issue,
 * 3 store, 4 not waiting for approval, 5 inside an attempt.
 */
class ApproveCommand(
    private val host: Host,
) : GuardedCommand("approve", host) {
    private val issue by argument(name = "issue", help = "The issue identifier")
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Approve the delivery of a verified result waiting for approval"

    override fun body(scope: RedactorScope) {
        // Before anything is read: an agent must not approve its own result, whatever path reached this command.
        if (insideAttempt(host.rawEnv)) {
            fail("refused inside a Loop attempt, only an operator approves a delivery. Nothing changed", EXIT_IN_ATTEMPT)
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
        // A tracker that cannot be read is not fatal: the waiting records can still name the issue.
        lookup.exceptionOrNull()?.let { error ->
            echo(
                redact(
                    "loop approve: cannot read \"$issue\" in ${trackerLocation(workflow)}: ${error.sentence()}. " +
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
            val waiting = record ?: fail("$identifier is not waiting for approval. Nothing changed", EXIT_NOT_WAITING)
            val current = checkApproval(identifier, waiting)
            // bl-69: under `merge` the issue never stops at the wait's handoff state, it ends in `on_delivered.move_to`.
            val mergeTo = mergeMoveTo(workflow, Wait.of(waiting).kind, found)
            if (current.approved) {
                echo("$identifier is already approved, ${outcome(current.to.orEmpty(), mergeTo)}. Nothing changed.")
                return
            }
            val by = approver()
            val now = Clock.System.now()
            // Null when the running Loop ended the wait in between, for example after a human moved the issue.
            val approved =
                runBlocking { waits.approve(current.issueId, by, now) }
                    .getOrElse { fail("cannot write the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                    ?.let { checkApproval(identifier, it) }
                    ?: fail("$identifier is not waiting for approval. Nothing changed", EXIT_NOT_WAITING)
            val to = mergeTo ?: approved.to.orEmpty()
            val event =
                ApprovalGiven.event(
                    ts = now,
                    projectId = projectId,
                    data = mapOf(Delivery.BY to by, Delivery.TO to to),
                    issueId = approved.issueId,
                    issueIdentifier = approved.identifier,
                    attemptId = approved.attemptId,
                )
            // An approval is a human touch, counted by the next ledger row of the issue (m2-19).
            val touch =
                HumanTouched.event(
                    ts = now,
                    projectId = projectId,
                    data = mapOf(HumanTouched.TOUCH to HumanTouch.APPROVE),
                    issueId = approved.issueId,
                    issueIdentifier = approved.identifier,
                    attemptId = approved.attemptId,
                )
            val recorded = runBlocking { store.record(event).mapCatching { store.record(touch).getOrThrow() } }
            recorded.exceptionOrNull()?.let { error ->
                echo(redact("loop approve: the approval was not recorded in the store at $storeFile: ${error.sentence()}."), err = true)
            }
            val since = approved.since.toString().take(SECONDS_PREFIX) + "Z"
            echo("Approved ${ids.name(approved.issueId, approved.identifier)}, waiting since $since. ${outcome(to, mergeTo)}.")
        }
    }

    /**
     * The state an approved issue ends in under `delivery.pull_request.merge` (bl-69): `delivery.on_delivered.move_to`,
     * which the loader requires with `merge`. Null without `merge`, where the delivery moves it to the wait's own state.
     * Since m4-18 the delivery is that of the kind [pinned] the issue was dispatched under, which the wait holds; for a
     * wait stored without one, the kind [issue] matches now, the code kind's when the tracker did not give the issue. A
     * kind that left the workflow is warned about: Loop keeps the wait until it is back.
     */
    private fun mergeMoveTo(
        workflow: WorkflowFile,
        pinned: String?,
        issue: Issue?,
    ): String? {
        val kind =
            when (val found = KindLookup.pinned(workflow, pinned, issue)) {
                is PinnedKind.Found -> found.kind
                is PinnedKind.Gone -> {
                    echo(
                        "loop approve: the kind ${found.name} the issue was dispatched under is no longer in the workflow, " +
                            "Loop delivers it once the kind is back.",
                        err = true,
                    )
                    null
                }
            }
        val delivery = kind?.config?.delivery ?: return null
        return delivery.onDeliveredMoveTo.takeIf { delivery.pullRequest?.merge != null }
    }

    /** What Loop does with the approved issue: the move to [to], or with [mergeTo] the pull request, merged, then that move. */
    private fun outcome(
        to: String,
        mergeTo: String?,
    ): String =
        if (mergeTo == null) {
            "Loop moves it to $to on its next tick"
        } else {
            "Loop delivers its pull request on its next tick, runs the merge check and merges it, then moves it to $mergeTo"
        }

    /** Who approves: the OS user of the environment, or [UNKNOWN_HUMAN] when it names none. */
    private fun approver(): String =
        listOf("USER", "LOGNAME")
            .firstNotNullOfOrNull { name -> host.rawEnv[name]?.takeIf { it.isNotBlank() } }
            ?: UNKNOWN_HUMAN

    /** Fails unless [wait] waits for approval: a wait for its checks is not a result anyone can approve yet. */
    private fun checkApproval(
        identifier: String,
        wait: Wait,
    ): Wait.Approval =
        wait as? Wait.Approval
            ?: fail("$identifier is waiting for ${wait.reason.wire}, not for approval. Nothing changed", EXIT_NOT_WAITING)

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop approve: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        /** The approver when the environment names no user. */
        const val UNKNOWN_HUMAN = "human"
        const val SECONDS_PREFIX = 19
        const val EXIT_ISSUE = 2
        const val EXIT_STORE = 3
        const val EXIT_NOT_WAITING = 4
        const val EXIT_IN_ATTEMPT = 5
    }
}
