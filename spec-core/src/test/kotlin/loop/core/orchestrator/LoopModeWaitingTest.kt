package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.Redactor
import loop.api.RetryState
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.verify.AcceptanceCriteria
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * m2-04: an issue whose checks run is `Waiting(verify)`, in the store and in memory, and is not dispatched
 * while it waits. A wait a dead process left runs its checks again on the first tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopModeWaitingTest {
    private val root: Path = createTempDirectory("loop-waiting").toRealPath()

    private fun workflow(
        kinds: Boolean = true,
        template: String = "Work on {{ issue.identifier }}",
        checks: List<CheckConfig> = listOf(CheckConfig.Command("./gradlew test")),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds = if (kinds) KindsConfig(KindConfig(checks, delivery = DeliveryConfig(DeliveryPolicy.Auto))) else null,
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = POLL_MS,
            promptTemplate = template,
        )

    /** A check runner that suspends each run until [release] completes, then exits with [exitCode]. */
    private class HeldChecks(
        var exitCode: Int = 0,
    ) : CheckRunner {
        val calls = mutableListOf<Pair<Path, Map<String, String>>>()
        val checks = mutableListOf<CheckConfig>()
        var release = CompletableDeferred<Unit>()

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            calls += cwd to env
            checks += check
            release.await()
            return CheckResult(exitCode, timedOut = false, durationMs = 1200)
        }
    }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        checks: CheckRunner,
        waiting: FakeWaitingIssues,
        sink: FakeSink = FakeSink(),
        workflow: WorkflowFile = workflow(),
        retryStates: FakeRetryStates = FakeRetryStates(),
        parked: FakeParkedIssues = FakeParkedIssues(),
        frozen: FakeFrozenCommands = FakeFrozenCommands(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
            retryStates = retryStates,
            waiting = waiting,
            frozenCommands = frozen,
        )
    }

    /** A sessionless runner whose one turn queues `loop tool move review`. */
    private fun submitting(): FakeRunner {
        val runner = FakeRunner()
        runner.onCall =
            { ctx -> ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review")) }
        return runner
    }

    /** A wait a dead process left for [identifier]: the checks of attempt `dead1` of run `r0`, to `review`. */
    private fun leftBehind(identifier: String = "m2-04") =
        WaitingIssue(
            projectId = PROJECT,
            issueId = "id-$identifier",
            identifier = identifier,
            reason = WaitReason.Verify,
            attemptId = AttemptId("dead1"),
            runId = RunId("r0"),
            since = FIXED_NOW,
            data = mapOf("to" to "review"),
        )

    private fun workspaceOf(identifier: String): Path = Files.createDirectories(root.resolve("workspaces/$identifier"))

    @Test
    fun `while its check runs the issue waits for verify and later ticks do not dispatch it, though it stays todo`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val runner = submitting()
            val checks = HeldChecks()
            val waiting = FakeWaitingIssues()
            val job = launch { orchestrator(tracker, runner, checks, waiting).runUntilCancelled() }
            runCurrent()
            assertEquals(1, checks.calls.size)

            val record = waiting.records.values.single()
            assertEquals(WaitReason.Verify, record.reason)
            assertEquals("id-m2-04", record.issueId)
            assertEquals("m2-04", record.identifier)
            assertEquals(AttemptId("a1"), record.attemptId)
            assertEquals(RunId("r1"), record.runId)
            assertEquals(mapOf("to" to "review"), record.data)

            advanceTimeBy(POLL_MS * 5)
            runCurrent()
            assertEquals(1, runner.calls.size, "no second attempt while the check runs")
            assertEquals("todo", tracker.issues.single().state)
            assertEquals(1, waiting.records.size)

            checks.release.complete(Unit)
            runCurrent()
            assertEquals(listOf("id-m2-04" to "review"), tracker.moves)
            assertEquals(emptyMap(), waiting.records, "the wait ends once the move is applied")
            job.cancelAndJoin()
        }

    @Test
    fun `a failed check ends the wait and the issue is dispatched again by the usual paths`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val checks = HeldChecks(exitCode = 1).also { it.release.complete(Unit) }
            val waiting = FakeWaitingIssues()

            orchestrator(tracker, submitting(), checks, waiting).tick()

            assertEquals(1, waiting.history.size)
            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a stopped run keeps the wait, so the check runs again at the next start`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val checks = HeldChecks()
            val waiting = FakeWaitingIssues()
            val job = launch { orchestrator(tracker, submitting(), checks, waiting).runUntilCancelled() }
            runCurrent()
            assertEquals(1, checks.calls.size)

            job.cancelAndJoin()

            assertEquals(
                AttemptId("a1"),
                waiting.records.values
                    .single()
                    .attemptId,
            )
        }

    @Test
    fun `reconciliation during the check ends the wait, the human change wins`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val checks = HeldChecks()
            val waiting = FakeWaitingIssues()
            val job = launch { orchestrator(tracker, submitting(), checks, waiting).runUntilCancelled() }
            runCurrent()
            assertEquals(1, waiting.records.size)

            tracker.issues[0] = tracker.issues[0].copy(state = "needs_info")
            advanceTimeBy(POLL_MS)
            runCurrent()

            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), tracker.moves)
            job.cancelAndJoin()
        }

    @Test
    fun `a wait left by a dead process runs its checks again on the first tick and applies the move, no agent runs`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val runner = FakeRunner()
            val checks = HeldChecks().also { it.release.complete(Unit) }
            val waiting = FakeWaitingIssues(leftBehind())
            val sink = FakeSink()
            val workspace = workspaceOf("m2-04")

            orchestrator(tracker, runner, checks, waiting, sink).tick()

            assertEquals(0, runner.calls.size, "the issue is not dispatched to an agent")
            assertEquals(workspace, checks.calls.single().first)
            assertEquals("m2-04", checks.calls.single().second["LOOP_ISSUE_IDENTIFIER"])
            assertEquals("dead1", checks.calls.single().second["LOOP_ATTEMPT_ID"])
            assertEquals(listOf("id-m2-04" to "review"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
            val kinds =
                sink.events.map { it.kind }.filter {
                    it in
                        setOf("result_submitted", "check_started", "check_finished", "issue_moved")
                }
            assertEquals(listOf("result_submitted", "check_started", "check_finished", "issue_moved"), kinds)
            val submitted = sink.events.single { it.kind == "result_submitted" }
            assertEquals(mapOf("to" to "review", "restored" to "true"), submitted.data)
            assertEquals(AttemptId("dead1"), submitted.attemptId)
            assertEquals(RunId("r1"), submitted.runId)
            assertEquals("true", sink.events.single { it.kind == "issue_moved" }.data["verified"])
        }

    @Test
    fun `a restored wait runs the acceptance commands of the issue as it reads now (m2-09)`() =
        runTest {
            val description = "## Acceptance criteria\n\n- [ ] `make lint` passes.\n- [ ] A human reads it."
            val tracker = FakeTracker(listOf(issue("m2-04", description = description)))
            val checks = HeldChecks().also { it.release.complete(Unit) }
            val sink = FakeSink()
            workspaceOf("m2-04")
            val workflow = workflow(checks = listOf(CheckConfig.AcceptanceCommands(5000)))

            orchestrator(tracker, FakeRunner(), checks, FakeWaitingIssues(leftBehind()), sink, workflow).tick()

            assertEquals(listOf<CheckConfig>(CheckConfig.Command("make lint", 5000)), checks.checks)
            assertEquals("1", sink.events.single { it.kind == "judgment_calls" }.data["count"])
            assertEquals(listOf("id-m2-04" to "review"), tracker.moves)
        }

    @Test
    fun `a restored wait whose criteria differ from the frozen list parks unverified and runs none of them (m2-09)`() =
        runTest {
            // The first dispatch froze two commands; the issue now reads with one, as an agent could have left it.
            val description = "## Acceptance criteria\n\n- [ ] `make lint` passes.\n- [ ] A human reads it."
            val first = AcceptanceCriteria.digest(listOf("make lint", "./extra.sh"))
            val frozen = FakeFrozenCommands(FrozenCommands(PROJECT, "id-m2-04", "m2-04", first, 2, FIXED_NOW))
            val tracker = FakeTracker(listOf(issue("m2-04", description = description)))
            val checks = HeldChecks().also { it.release.complete(Unit) }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            workspaceOf("m2-04")
            val workflow = workflow(checks = listOf(CheckConfig.AcceptanceCommands(5000)))

            orchestrator(
                tracker,
                FakeRunner(),
                checks,
                FakeWaitingIssues(leftBehind()),
                sink,
                workflow,
                parked = parked,
                frozen = frozen,
            ).tick()

            assertEquals(emptyList(), checks.checks)
            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("unverified", verdict["verdict"])
            assertEquals("acceptance commands changed", verdict["reason"])
            assertEquals(mapOf("frozen" to "2", "count" to "1"), sink.events.single { it.kind == "acceptance_commands_changed" }.data)
            assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
            assertEquals("acceptance commands changed", sink.events.single { it.kind == "issue_parked" }.data["error"])
            val record = frozen.records.values.single()
            assertEquals(first, record.digest)
            assertEquals(AcceptanceCriteria.digest(listOf("make lint")), record.changedDigest)
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `while a restored check runs, later ticks do not dispatch the issue nor fire its retry, a failure then frees it`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val runner = FakeRunner()
            val checks = HeldChecks(exitCode = 1)
            val waiting = FakeWaitingIssues(leftBehind())
            workspaceOf("m2-04")
            val due = RetryState(PROJECT, "id-m2-04", "m2-04", QueuedRetry(1, FIXED_NOW, null), failures = 0, noProgress = 0)
            val job =
                launch { orchestrator(tracker, runner, checks, waiting, retryStates = FakeRetryStates(due)).runUntilCancelled() }
            runCurrent()
            assertEquals(1, checks.calls.size)

            advanceTimeBy(POLL_MS * 5)
            runCurrent()
            assertEquals(0, runner.calls.size, "a waiting issue is never dispatched")
            assertEquals(1, waiting.records.size)

            checks.release.complete(Unit)
            runCurrent()
            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), tracker.moves)
            advanceTimeBy(POLL_MS)
            runCurrent()
            assertEquals(1, runner.calls.size, "once the wait ended the issue is dispatched again")
            job.cancelAndJoin()
        }

    @Test
    fun `a restored check that refutes the result hands its findings to the next attempt (m2-06)`() =
        runTest {
            val runner = FakeRunner()
            val checks = HeldChecks(exitCode = 1).also { it.release.complete(Unit) }
            workspaceOf("m2-04")
            val template = "Work on {{ issue.identifier }}{% if loop.verify_findings %}\n{{ loop.verify_findings }}{% endif %}"
            val orchestrator =
                orchestrator(
                    FakeTracker(listOf(issue("m2-04"))),
                    runner,
                    checks,
                    FakeWaitingIssues(leftBehind()),
                    workflow = workflow(template = template),
                )

            orchestrator.tick()
            orchestrator.tick()

            val prompt = runner.calls.single().second
            assertContains(prompt, "Work on m2-04\nYour last result was refuted")
            assertContains(prompt, "Check: ./gradlew test\nExit code: 1\nIts output was empty.")
        }

    @Test
    fun `a restored check that could not run parks the issue as unverified before its wait ends (m2-08)`() =
        runTest {
            val runner = FakeRunner()
            val checks = HeldChecks(exitCode = 127).also { it.release.complete(Unit) }
            val waiting = FakeWaitingIssues(leftBehind())
            val parked = FakeParkedIssues()
            val sink = FakeSink()
            workspaceOf("m2-04")
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val orchestrator = orchestrator(tracker, runner, checks, waiting, sink, parked = parked)

            orchestrator.tick()
            advanceTimeBy(POLL_MS * 5)
            orchestrator.tick()

            assertEquals(1, checks.calls.size, "a check that could not run is not rerun")
            assertEquals(emptyList(), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
            assertEquals(0, runner.calls.size, "a parked issue is not dispatched")
            assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
            val event = sink.events.single { it.kind == "issue_parked" }
            assertEquals(mapOf("reason" to "unverified", "error" to "check 0 could not run: command not found, exit code 127"), event.data)
            assertEquals(AttemptId("dead1"), event.attemptId)
        }

    @Test
    fun `a stopped restored check keeps the wait for the next start`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val checks = HeldChecks().also { it.release = CompletableDeferred() }
            val waiting = FakeWaitingIssues(leftBehind())
            workspaceOf("m2-04")
            val job = launch { orchestrator(tracker, FakeRunner(), checks, waiting).runUntilCancelled() }
            runCurrent()
            assertEquals(1, checks.calls.size)

            job.cancelAndJoin()

            assertEquals(listOf(leftBehind()), waiting.records.values.toList())
        }

    @Test
    fun `a wait whose workspace is gone, whose issue left the active states or whose workflow has no kinds is dropped`() =
        runTest {
            // No workspace: nothing to check, the wait ends and the issue is dispatched as a candidate.
            val noWorkspace = FakeWaitingIssues(leftBehind())
            val gone = FakeRunner()
            val checks = HeldChecks().also { it.release.complete(Unit) }
            orchestrator(FakeTracker(listOf(issue("m2-04"))), gone, checks, noWorkspace).tick()
            assertEquals(emptyMap(), noWorkspace.records)
            assertEquals(0, checks.calls.size)
            assertEquals(1, gone.calls.size, "dispatched in the same tick")

            // The issue is in review now: a human moved it, nothing to check.
            workspaceOf("m2-05")
            val notActive = FakeWaitingIssues(leftBehind("m2-05"))
            val tracker = FakeTracker(listOf(issue("m2-05", state = "review")))
            orchestrator(tracker, FakeRunner(), checks, notActive).tick()
            assertEquals(emptyMap(), notActive.records)
            assertEquals(0, checks.calls.size)
            assertEquals(emptyList(), tracker.moves)

            // Symphony mode now: the wait ends and the agent works the issue again.
            workspaceOf("m2-06")
            val symphony = FakeWaitingIssues(leftBehind("m2-06"))
            val runner = FakeRunner()
            orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, checks, symphony, workflow = workflow(kinds = false)).tick()
            assertEquals(emptyMap(), symphony.records)
            assertEquals(0, checks.calls.size)
            assertEquals(1, runner.calls.size)
        }

    @Test
    fun `a store that cannot be read dispatches nothing until it can`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04")))
            val runner = FakeRunner()
            val waiting = FakeWaitingIssues(leftBehind()).also { it.readFailure = IllegalStateException("disk") }
            workspaceOf("m2-04")
            val orchestrator = orchestrator(tracker, runner, HeldChecks().also { it.release.complete(Unit) }, waiting)

            orchestrator.tick()
            assertEquals(0, runner.calls.size)
            assertEquals(emptyList(), tracker.moves)

            waiting.readFailure = null
            orchestrator.tick()
            assertEquals(listOf("id-m2-04" to "review"), tracker.moves)
            assertEquals(0, runner.calls.size)
        }

    @Test
    fun `each tick reads the waits of the store once, for the restore, the approvals and the merges`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-04"), issue("m2-24")))
            val approval =
                leftBehind("m2-24").copy(reason = WaitReason.Approval, data = mapOf("to" to "review", "fingerprint" to "git:abc:e:e"))
            val merge = leftBehind("m2-26").copy(reason = WaitReason.Merge, data = mapOf("to" to "review", "phase" to "check"))
            val waiting = FakeWaitingIssues(leftBehind(), approval, merge)
            workspaceOf("m2-04")
            val orchestrator = orchestrator(tracker, FakeRunner(), HeldChecks().also { it.release.complete(Unit) }, waiting)

            orchestrator.tick()
            assertEquals(1, waiting.reads)
            orchestrator.tick()
            orchestrator.tick()
            assertEquals(3, waiting.reads)
            assertEquals(listOf("id-m2-04" to "review"), tracker.moves)
        }

    private companion object {
        const val POLL_MS = 1000L
        val PROJECT = ProjectId("p1")
    }
}
