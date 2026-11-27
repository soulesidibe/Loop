package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.DeliveryExecutor
import loop.api.MergeMethod
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Fingerprinter
import loop.core.verify.WorkspaceFingerprint
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * bl-252: a human who moves an issue waiting for approval to another active state rejects the result. The wait stores
 * the state its issue was in when it started, and the tick that reads the issue in another active state ends it as a
 * human move, through bl-250's conditional end, an approval already given void, and the issue dispatches again. A wait
 * stored without a state, a sent back wait and a wait whose issue still runs are left alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HumanMoveApprovalOrchestratorTest {
    private val root: Path = createTempDirectory("loop-human-move-approval").toRealPath()
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val empty = WorkspaceFingerprint.EMPTY_HASH
    private val clean = "git:$sha:$empty:$empty"
    private val key = PROJECT.value to "id-t-1"
    private val logLines = mutableListOf<String>()

    private val template =
        "Work on {{ issue.identifier }}. kind=[{{ loop.retry_kind }}]" +
            "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"

    private fun workflow(pullRequest: PullRequestConfig? = null): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds =
                        KindsConfig(
                            KindConfig(
                                listOf(CheckConfig.Command("./gradlew test")),
                                delivery = DeliveryConfig(DeliveryPolicy.Human, pullRequest),
                            ),
                        ),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = template,
        )

    /** A remote that records each push and opens one pull request, so a test sees that nothing was pushed. */
    private class RecordingRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<Pair<String, String>>()

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushes += sha to branch
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(null)

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true))

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(PullRequest(number, "https://github.com/acme/widgets/pull/$number", created = false))

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> = Result.success("feedface".repeat(5))
    }

    /** The approval wait of `t-1`, delivered to `review`, holding [state] as the state it started in, none when null. */
    private fun wait(state: String? = "todo") =
        WaitingIssue(
            projectId = PROJECT,
            issueId = "id-t-1",
            identifier = "t-1",
            reason = WaitReason.Approval,
            attemptId = AttemptId("dead1"),
            runId = RunId("r0"),
            since = FIXED_NOW,
            data =
                mapOf(
                    WaitKeys.TO to "review",
                    WaitKeys.FINGERPRINT to clean,
                    WaitKeys.CHECKS to "- check 0, ./gradlew test: passed",
                    WaitKeys.JUDGMENT_CALLS to "0",
                ) + listOfNotNull(state?.let { WaitKeys.STATE to it }),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        runner: FakeRunner,
        workflow: WorkflowFile = workflow(),
        remote: DeliveryExecutor? = null,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1200) } },
            waiting = waiting,
            fingerprinter = Fingerprinter { Result.success(clean) },
            deliveryFor = { remote },
        )
    }

    private suspend fun TestScope.tick(orchestrator: Orchestrator) {
        orchestrator.tick()
        advanceUntilIdle()
    }

    /** A human moves `t-1` to [state] in the tracker. */
    private fun FakeTracker.humanMoves(state: String) {
        issues[0] = issue("t-1", state = state)
    }

    private fun FakeSink.touches(): List<RecordedEvent> = events.filter { it.kind == "human_touch" }

    /** The wait ended as a human move from `todo` to `in_progress`: one `human_touch` and the log line. */
    private fun assertEndedAsHumanMove(
        waiting: FakeWaitingIssues,
        sink: FakeSink,
    ) {
        assertEquals(emptyMap(), waiting.records, "the wait ends")
        val touch = sink.touches().single()
        assertEquals(mapOf("touch" to "move", "from_state" to "todo", "to_state" to "in_progress"), touch.data)
        assertEquals("id-t-1", touch.issueId)
        val line = logLines.single { "a human moved the issue" in it }
        assertContains(line, "event=waiting")
        assertContains(line, "outcome=released")
    }

    @Test
    fun `bl-252 a human move of a waiting issue from todo to in_progress ends the wait and dispatches the issue again`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues(wait())
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, waiting, sink, runner)

            tick(orchestrator)
            assertEquals(wait(), waiting.records[key], "unmoved, the issue keeps waiting")
            assertEquals(emptyList(), runner.calls)

            tracker.humanMoves("in_progress")
            tick(orchestrator)

            assertEndedAsHumanMove(waiting, sink)
            assertEquals(emptyList(), tracker.moves, "nothing is delivered")
            val dispatched = assertNotNull(runner.calls.singleOrNull(), "the issue dispatches again")
            assertEquals("t-1", dispatched.first.issue.identifier)
        }

    /** `loop approve`, the store's approve, then a human move before the tick that would deliver it. */
    private fun approvedThenMoved(pullRequest: PullRequestConfig?) =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues(wait())
            val sink = FakeSink()
            val runner = FakeRunner()
            val remote = RecordingRemote()
            val orchestrator = orchestrator(tracker, waiting, sink, runner, workflow(pullRequest), remote)
            assertNotNull(waiting.approve(PROJECT, "id-t-1", "soule", FIXED_NOW).getOrThrow())

            tracker.humanMoves("in_progress")
            tick(orchestrator)

            assertEquals(emptyList(), tracker.moves.filter { it.second == "review" }, "nothing moves to review")
            assertEquals(emptyList(), remote.pushes, "nothing is pushed")
            assertTrue(sink.events.none { it.kind == "issue_moved" })
            assertEndedAsHumanMove(waiting, sink)
            assertEquals(1, runner.calls.size, "the issue dispatches again")
        }

    @Test
    fun `bl-252 an approval given then a human move before the delivering tick delivers nothing, plain delivery`() =
        approvedThenMoved(pullRequest = null)

    @Test
    fun `bl-252 an approval given then a human move before the delivering tick delivers nothing, pull request delivery`() =
        approvedThenMoved(PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN"))

    @Test
    fun `bl-252 a wait stored without a state, by an older build, keeps waiting after a move to another active state`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1", state = "in_progress")))
            val waiting = FakeWaitingIssues(wait(state = null))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, waiting, sink, runner)

            tick(orchestrator)
            advanceTimeBy(1_000)
            tick(orchestrator)

            assertEquals(wait(state = null), waiting.records[key])
            assertEquals(emptyList(), sink.touches())
            assertEquals(emptyList(), runner.calls)
        }

    @Test
    fun `bl-252 a sent back wait follows the send back path, not the human move`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues(wait())
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, waiting, sink, runner)
            assertNotNull(waiting.sendBack(PROJECT, "id-t-1", "soule", "Use Save.", FIXED_NOW).getOrThrow())

            tracker.humanMoves("in_progress")
            tick(orchestrator)

            assertEquals(emptyList(), sink.touches(), "no human move is recorded")
            assertTrue(logLines.none { "a human moved the issue" in it })
            val dispatched = assertNotNull(runner.calls.singleOrNull(), "the issue dispatches with the reason")
            assertEquals("Work on t-1. kind=[sent_back]\nFindings: Sent back by soule: Use Save.", dispatched.second)
        }

    @Test
    fun `bl-252 a wait whose issue still runs is not ended by a human move`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val release = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        release.await()
                        emitAll(succeedingTurn())
                    }
                }
            val orchestrator = orchestrator(tracker, waiting, sink, runner)
            // A tick waits for the attempts it started, so each runs in its own coroutine while the attempt holds.
            val first = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(1, runner.calls.size, "the attempt runs")

            // The attempt that asked for the approval is still closing when a human moves the issue.
            waiting.records[key] = wait()
            tracker.humanMoves("in_progress")
            val second = launch { orchestrator.tick() }
            runCurrent()

            assertEquals(wait(), waiting.records[key], "the wait is kept while its issue runs")
            assertEquals(emptyList(), sink.touches())
            assertTrue(logLines.none { "a human moved the issue" in it })
            release.complete(Unit)
            first.join()
            second.join()
        }

    @Test
    fun `bl-252 a new orchestrator over the same store ends a restored wait its issue was moved away from`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues(wait())
            val sink = FakeSink()
            val first = FakeRunner()
            tick(orchestrator(tracker, waiting, sink, first))
            assertEquals(wait(), waiting.records[key])

            // A restart: a new orchestrator over the same store, the issue moved while no Loop ran.
            tracker.humanMoves("in_progress")
            val runner = FakeRunner()
            tick(orchestrator(tracker, waiting, sink, runner))

            assertEndedAsHumanMove(waiting, sink)
            assertEquals(emptyList(), first.calls)
            assertEquals(1, runner.calls.size, "the issue dispatches again")
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
