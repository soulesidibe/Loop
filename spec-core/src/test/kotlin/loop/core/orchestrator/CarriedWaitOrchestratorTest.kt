package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ParkReason
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.waiting.Wait
import loop.core.workflow.AgentConfig
import loop.core.workflow.BudgetsConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * bl-221: an answered ask wait (m4-52) and a sent back approval wait (m4-32) stay in the store, with the answer or the
 * reason, until the attempt that carries them completed a turn. An attempt that ends before, at a budget breach before
 * its first turn or at a session that fails to open, leaves the wait, so the next dispatch renders the same answer or
 * reason and the same retry kind. A completed turn ends the wait, so the dispatch after it carries neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarriedWaitOrchestratorTest {
    private val root: Path = createTempDirectory("loop-carried-wait").toRealPath()
    private val project = ProjectId("p1")
    private val parked = FakeParkedIssues()
    private val ledger = Ledger()

    private val template =
        "Work on {{ issue.identifier }}. kind=[{{ loop.retry_kind }}]" +
            "{% if loop.answer %}\n{{ loop.answer }}{% endif %}" +
            "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"

    private fun workflow(budgets: BudgetsConfig = BudgetsConfig()): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = template,
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1, maxRetryBackoffMs = 1000),
                    retries = RetriesConfig(maxAttempts = 5),
                    budgets = budgets,
                ),
        )

    private fun TestScope.orchestrator(
        runner: FakeRunner,
        waiting: FakeWaitingIssues,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("t-1"))),
            runner = runner,
            sink = FakeSink(),
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = project,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            attemptLedger = ledger,
            waiting = waiting,
        )
    }

    /** A session runner whose every turn completes; [failOpens] first session opens throw instead. */
    private fun runner(failOpens: Int = 0): FakeRunner {
        var opens = 0
        return FakeRunner().apply {
            sessionScript = { turn, _ -> completed(turn) }
            onCall = { if (++opens <= failOpens) throw IllegalStateException("the session cannot open") }
        }
    }

    private fun completed(turn: Int): Flow<RunnerEvent> =
        flow {
            val session = SessionId("th1-tu$turn")
            val id = TurnId("tu$turn")
            emit(RunnerEvent.SessionStarted(session, threadId = "th1", turnId = id))
            emit(RunnerEvent.TurnCompleted(session, id, exitCode = 0))
        }

    /** `t-1` waits as an answered `Waiting(ask)`, as `loop answer` leaves it in the store. */
    private suspend fun answered(): FakeWaitingIssues {
        val asked =
            Wait
                .ask(project, "id-t-1", "t-1", AttemptId("a0"), RunId("r0"), SINCE, state = "todo", questionKind = "product")
                .asking("Question for a human (product): Archived items?")
        return FakeWaitingIssues(asked.toRecord()).also {
            assertNotNull(it.answer(project, "id-t-1", "soule", "Leave them out.", SINCE).getOrThrow())
        }
    }

    /** `t-1` waits as a sent back `Waiting(approval)`, as `loop send-back` leaves it in the store. */
    private suspend fun sentBack(): FakeWaitingIssues {
        val approval =
            WaitingIssue(
                project,
                "id-t-1",
                "t-1",
                WaitReason.Approval,
                AttemptId("a0"),
                RunId("r0"),
                SINCE,
                mapOf(
                    "to" to "review",
                ),
            )
        return FakeWaitingIssues(approval).also {
            assertNotNull(it.sendBack(project, "id-t-1", "soule", "Use Save.", SINCE).getOrThrow())
        }
    }

    private val answerPrompt =
        "Work on t-1. kind=[answer]\nQuestion for a human (product): Archived items?\n\nAnswer from soule: Leave them out."
    private val sentBackPrompt = "Work on t-1. kind=[sent_back]\nFindings: Sent back by soule: Use Save."

    /** The ledger row of an earlier attempt of `t-1` that spent 3000 tokens, past a per issue budget of 2000. */
    private fun spentBefore() {
        ledger.rows += LedgerRow(attempt("old", 3_000), 1, 0)
    }

    private fun FakeWaitingIssues.stored(): WaitingIssue? = records[project.value to "id-t-1"]

    /**
     * The attempt carrying the wait parks at the issue budget before its first turn; the wait stays. After a resume and
     * with the budget no longer breached, the next dispatch carries the same text with the same retry kind.
     */
    private fun budgetThenResume(
        waiting: suspend () -> FakeWaitingIssues,
        expected: String,
    ) = runTest {
        val store = waiting()
        val before = store.stored()
        spentBefore()
        val runner = runner()
        val orchestrator = orchestrator(runner, store, workflow(BudgetsConfig(perIssueTokens = 2_000)))

        val first = orchestrator.tick().attempts.single()

        assertEquals(ParkReason.Budget, first.parkReason)
        assertEquals(0, runner.sessionsOpened)
        assertEquals(before, store.stored(), "the wait stays in the store until a turn completed")

        // `loop resume` and a spend no longer past the budget.
        ledger.rows.clear()
        parked.records.clear()
        advanceTimeBy(1_000)
        orchestrator.tick()

        assertEquals(listOf(expected), runner.turnInputs.map { it.second })
        assertNull(store.stored(), "the completed turn ended the wait")
    }

    /** The first session fails to open; the wait stays and the error retry carries the same text with the same retry kind. */
    private fun failedOpenThenRetry(
        waiting: suspend () -> FakeWaitingIssues,
        expected: String,
    ) = runTest {
        val store = waiting()
        val before = store.stored()
        val runner = runner(failOpens = 1)
        val orchestrator = orchestrator(runner, store)

        orchestrator.tick()

        assertEquals(emptyList(), runner.turnInputs)
        assertEquals(before, store.stored(), "the wait stays in the store until a turn completed")

        advanceTimeBy(1_000)
        orchestrator.tick()

        assertEquals(listOf(expected), runner.turnInputs.map { it.second })
        assertNull(store.stored(), "the completed turn ended the wait")
    }

    @Test
    fun `bl-221 an answered issue parked at its budget before its first turn carries the answer on its next dispatch`() =
        budgetThenResume(::answered, answerPrompt)

    @Test
    fun `bl-221 an answered issue whose session fails to open carries the answer on its retry`() =
        failedOpenThenRetry(::answered, answerPrompt)

    @Test
    fun `bl-221 a sent back issue parked at its budget before its first turn carries the reason on its next dispatch`() =
        budgetThenResume(::sentBack, sentBackPrompt)

    @Test
    fun `bl-221 a sent back issue whose session fails to open carries the reason on its retry`() =
        failedOpenThenRetry(::sentBack, sentBackPrompt)

    @Test
    fun `bl-221 a completed first turn ends the answered wait, so the next dispatch carries no answer`() =
        runTest {
            val store = answered()
            val runner = runner()
            val orchestrator = orchestrator(runner, store)

            orchestrator.tick()
            assertNull(store.stored())
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(listOf(answerPrompt, "Work on t-1. kind=[continuation]"), runner.turnInputs.map { it.second })
        }

    @Test
    fun `bl-221 a completed first turn ends the sent back wait, so the next dispatch carries no reason`() =
        runTest {
            val store = sentBack()
            val runner = runner()
            val orchestrator = orchestrator(runner, store)

            orchestrator.tick()
            assertNull(store.stored())
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(listOf(sentBackPrompt, "Work on t-1. kind=[continuation]"), runner.turnInputs.map { it.second })
        }

    private fun attempt(
        id: String,
        tokens: Long,
    ): LedgerAttempt {
        val finishedAt = FIXED_NOW - 1.hours
        return LedgerAttempt(
            projectId = project,
            runId = RunId("r0"),
            issueId = "id-t-1",
            identifier = "t-1",
            attemptId = AttemptId(id),
            setup = LedgerSetup("files", null, "command", null, "t".repeat(64), null),
            startedAt = finishedAt - 10.minutes,
            finishedAt = finishedAt,
            outcome = "succeeded",
            reason = null,
            turns = 1,
            issueState = "todo",
            tokens = LedgerTokens(tokens, 0, 0, 0),
            costUsd = null,
            checks = null,
            guards = null,
            verdict = null,
        )
    }

    /** The ledger rows of the store, which the issue budget reads. */
    private class Ledger : AttemptLedger {
        val rows = mutableListOf<LedgerRow>()

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            rows += LedgerRow(attempt, rows.size + 1, 0)
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> = Result.success(rows.filter { it.attempt.projectId == projectId && it.attempt.issueId == issueId })

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> = Result.success(rows.filter { it.attempt.projectId == projectId && it.attempt.finishedAt > since })
    }

    private companion object {
        val SINCE: Instant = Instant.parse("2026-11-06T18:00:00Z")
    }
}
