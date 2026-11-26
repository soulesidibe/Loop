package loop.core.orchestrator

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ParkReason
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RetryState
import loop.api.RunId
import loop.core.log.KeyValueLog
import loop.core.reactions.ReactionMarksTest
import loop.core.trackers.TrackerPoll
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * bl-224: the mark that keeps an issue's failure counter through the approval exit of a reaction attempt. Every path that
 * resets the counter clears it, so a later approval exit resets the counter again; and the mark is derived from the
 * stored events when a new process restores the retry states.
 */
class RetryCoordinatorReactionMarkTest {
    private val root: Path = Path.of("/tmp/loop-reaction-mark")
    private val project = ProjectId("p1")

    private class MovingClock : Clock {
        var now: Instant = FIXED_NOW

        override fun now(): Instant = now
    }

    private val workflow: WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Prompt",
            config = WorkflowConfig(),
        )

    private inner class Setup(
        events: List<RecordedEvent> = emptyList(),
        vararg states: RetryState,
    ) {
        val clock = MovingClock()
        val tracker = FakeTracker(listOf(issue("t-1")))
        val retryStates = FakeRetryStates(*states)
        val sink = FakeSink()
        val coordinator =
            RetryCoordinator(
                TrackerPoll.of(tracker, workflow.trackerId),
                FakeParkedIssues(),
                sink,
                "fake",
                KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                clock,
                RunId("r1"),
                project,
                onTerminalRetryDropped = {},
                retryStates = retryStates,
                retryEventsBefore = { Result.success(events) },
            )
        val issue: Issue = issue("t-1")

        /** The stored failure counter of the issue, zero when nothing is stored. */
        val failures: Int get() = retryStates.records[project.value to issue.id]?.failures ?: 0

        fun summary(
            outcome: AttemptOutcome,
            exitReason: String? = null,
            reason: String? = null,
            handedOff: Boolean = false,
            parkReason: ParkReason? = null,
        ) = AttemptSummary(
            issueIdentifier = issue.identifier,
            dispatchedIssue = issue,
            outcome = outcome,
            issueState = issue.state,
            duration = Duration.ZERO,
            attemptId = AttemptId("a1"),
            reason = reason,
            refreshedIssue = issue,
            exitReason = exitReason,
            handedOff = handedOff,
            parkReason = parkReason,
        )

        suspend fun approvalExit() =
            coordinator.afterAttempt(issue, summary(AttemptOutcome.Succeeded, ExitReason.WAITING_APPROVAL), workflow)

        suspend fun failure() = coordinator.afterAttempt(issue, summary(AttemptOutcome.Failed, reason = "boom"), workflow)

        /** After [reset] reset the counter, a failure then an approval exit: the approval exit resets the counter again. */
        suspend fun assertResetClearsTheMark(reset: suspend Setup.() -> Unit) {
            assertEquals(true, coordinator.restore(workflow))
            assertEquals(true, coordinator.onReaction(issue, AttemptId("a0"), workflow))
            assertEquals(1, failures, "the reaction takes a max_attempts slot")
            reset()
            assertEquals(0, failures, "the reset zeroed the counter")
            failure()
            assertEquals(1, failures)
            approvalExit()
            assertEquals(0, failures, "the approval exit after the reset resets the counter")
        }

        /** Fires the reaction's retry once it is due, with the issue read in [state]. */
        suspend fun fireIn(state: String) {
            tracker.issues[0] = issue("t-1", state = state)
            clock.now += 60_000.milliseconds
            coordinator.fireDue(emptyList(), workflow)
        }
    }

    @Test
    fun `without a reset the approval exit of a reaction attempt keeps the counter`() =
        runTest {
            val setup = Setup()
            setup.coordinator.restore(workflow)
            setup.coordinator.onReaction(setup.issue, AttemptId("a0"), workflow)
            setup.approvalExit()
            assertEquals(1, setup.failures)
        }

    @Test
    fun `a park clears the mark`() =
        runTest {
            Setup().assertResetClearsTheMark {
                coordinator.afterAttempt(issue, summary(AttemptOutcome.Failed, reason = "over", parkReason = ParkReason.Budget), workflow)
            }
        }

    @Test
    fun `a terminal retry drop clears the mark`() = runTest { Setup().assertResetClearsTheMark { fireIn("done") } }

    @Test
    fun `a not active retry drop clears the mark`() = runTest { Setup().assertResetClearsTheMark { fireIn("review") } }

    @Test
    fun `a handoff clears the mark`() =
        runTest {
            Setup().assertResetClearsTheMark {
                coordinator.afterAttempt(issue, summary(AttemptOutcome.Succeeded, ExitReason.NO_CONTINUATION, handedOff = true), workflow)
            }
        }

    @Test
    fun `a parked issue's retry drop clears the mark`() =
        runTest {
            Setup().assertResetClearsTheMark { coordinator.fireDue(listOf(issue), workflow, parked = setOf(issue.id)) }
        }

    @Test
    fun `an issue absent from the candidates clears the mark`() =
        runTest {
            Setup().assertResetClearsTheMark {
                // The reaction attempt's approval exit keeps the counter and drops the entry, then the issue leaves the poll.
                approvalExit()
                assertEquals(1, failures)
                coordinator.fireDue(emptyList(), workflow)
            }
        }

    @Test
    fun `a reconciliation reset clears the mark`() =
        runTest { Setup().assertResetClearsTheMark { coordinator.onReconciled(issue.id, ExitReason.NOT_ACTIVE) } }

    private fun reactionState(failures: Int = 1): RetryState =
        RetryState(project, "id-t-1", "t-1", QueuedRetry(2, FIXED_NOW, PromptVariables.RETRY_REACTION), failures, 0)

    @Test
    fun `after a restart a stored reaction keeps the counter through the approval exit`() =
        runTest {
            val events = listOf(ReactionMarksTest.triggered(sent = true))
            val setup = Setup(events, reactionState())
            assertEquals(true, setup.coordinator.restore(workflow))
            setup.approvalExit()
            assertEquals(1, setup.failures, "the stored reaction kept the counter")
        }

    @Test
    fun `after a restart a reaction before a stored reset does not keep the counter`() =
        runTest {
            val events = listOf(ReactionMarksTest.triggered(sent = true), ReactionMarksTest.parked())
            val setup = Setup(events, reactionState())
            assertEquals(true, setup.coordinator.restore(workflow))
            setup.approvalExit()
            assertNull(setup.retryStates.records[project.value to "id-t-1"], "the counter was reset")
        }

    @Test
    fun `a store that cannot give the events restores nothing this tick`() =
        runTest {
            val clock = MovingClock()
            val retryStates = FakeRetryStates(reactionState())
            var fail = true
            val coordinator =
                RetryCoordinator(
                    TrackerPoll.of(FakeTracker(listOf(issue("t-1"))), workflow.trackerId),
                    FakeParkedIssues(),
                    FakeSink(),
                    "fake",
                    KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                    clock,
                    RunId("r1"),
                    project,
                    onTerminalRetryDropped = {},
                    retryStates = retryStates,
                    retryEventsBefore = {
                        if (fail) Result.failure(IllegalStateException("database is locked")) else Result.success(emptyList())
                    },
                )
            assertEquals(false, coordinator.restore(workflow), "a lost mark would reset the counter, so nothing is dispatched")
            fail = false
            assertEquals(true, coordinator.restore(workflow))
        }
}
