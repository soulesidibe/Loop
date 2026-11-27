package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.MergeMethod
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerError
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
import loop.core.workflow.CiMode
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.MergeConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-250: Loop's own writes of an approval wait never overwrite a `loop send-back` committed since the tick read the
 * wait. A send back that lands before an external step of the delivery stops it, one that lands during a pull request
 * delivery wins over the merge wait, the cleared approval and the stored URL, and the issue dispatches with the reason.
 * One that lands after the final move is too late: the delivery stands and Loop says so at warn. Each send back is
 * committed from a hook in a fake, never by timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentBackDuringDeliveryOrchestratorTest {
    private val root: Path = createTempDirectory("loop-sent-back-delivery").toRealPath()
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val empty = WorkspaceFingerprint.EMPTY_HASH
    private val clean = "git:$sha:$empty:$empty"
    private val key = PROJECT.value to "id-t-1"
    private val logLines = mutableListOf<String>()

    private val pullRequest = PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN")
    private val merging = PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", merge = MergeConfig(ci = CiMode.None))

    private val template =
        "Work on {{ issue.identifier }}. kind=[{{ loop.retry_kind }}]" +
            "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}"
    private val sentBackPrompt = "Work on t-1. kind=[sent_back]\nFindings: Sent back by soule: Use Save."

    private fun workflow(pullRequest: PullRequestConfig?): WorkflowFile =
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

    /** One pull request per branch, like GitHub, with a hook in the push, after the commit is pushed (bl-250). */
    private class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<Pair<String, String>>()
        val open = mutableMapOf<String, PullRequest>()
        val merges = mutableListOf<Long>()
        var pushFailure: DeliveryError? = null

        /** Runs in each push, after the push and before the pull request step, or before a [pushFailure]. */
        var afterPush: suspend () -> Unit = {}

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushFailure?.let {
                afterPush()
                return Result.failure(it)
            }
            pushes += sha to branch
            afterPush()
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(open[branch]?.copy(created = false))

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            val opened = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = pushes.lastOrNull()?.first)
            open[branch] = opened
            return Result.success(opened)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(open.values.single { it.number == number }.copy(created = false))

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            merges += number
            return Result.success("feedface".repeat(5))
        }
    }

    private fun approvedWait() =
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
                    WaitingIssue.APPROVED_BY to "soule",
                    WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
                ),
        )

    /** `loop send-back t-1 --reason "Use Save."`, another process, committed in the store. */
    private suspend fun FakeWaitingIssues.sendBack() {
        val sent = assertNotNull(sendBack(PROJECT, "id-t-1", "soule", "Use Save.", FIXED_NOW).getOrThrow())
        assertTrue(sent.sentBack)
    }

    /** The orchestrator, its runner noting the stored row of `t-1` at each dispatch in [rows]. */
    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        remote: DeliveryExecutor?,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        workflow: WorkflowFile,
        runner: FakeRunner,
        rows: MutableList<WaitingIssue?>,
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        runner.onCall = { rows += waiting.records[key] }
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

    /** Everything a test needs, with one tick run through its dispatch. */
    private inner class Run(
        workflow: WorkflowFile,
        scope: TestScope,
    ) {
        val tracker = FakeTracker(listOf(issue("t-1")))
        val remote = FakeRemote()
        val waiting = FakeWaitingIssues(approvedWait())
        val sink = FakeSink()
        val runner = FakeRunner()
        val rows = mutableListOf<WaitingIssue?>()
        val orchestrator = scope.orchestrator(tracker, remote, waiting, sink, workflow, runner, rows)

        suspend fun tick(scope: TestScope) {
            orchestrator.tick()
            scope.advanceUntilIdle()
        }

        fun kinds(): List<String> = sink.events.map { it.kind }

        /** The row of `t-1` the first dispatch saw: sent back, with its reason, never overwritten. */
        fun assertDispatchedWithReason() {
            val row = assertNotNull(rows.firstOrNull(), "the issue dispatched with the sent back wait in the store")
            assertEquals(WaitReason.Approval, row.reason)
            assertEquals("Use Save.", row.data[WaitingIssue.SENT_BACK_REASON])
            assertNull(row.data[WaitingIssue.APPROVED_AT], "the approval stays dropped")
            assertEquals(sentBackPrompt, runner.calls.first().second)
        }
    }

    /** A hook that runs [block] once, on the first call. */
    private fun once(block: suspend () -> Unit): suspend () -> Unit {
        var fired = false
        return {
            if (!fired) {
                fired = true
                block()
            }
        }
    }

    @Test
    fun `bl-250 plain delivery, a send back after the tick read the wait and before the move stops the move`() =
        runTest {
            val run = Run(workflow(pullRequest = null), this)
            val fire = once { run.waiting.sendBack() }
            run.waiting.afterRead = { fire() }

            run.tick(this)

            assertEquals(emptyList(), run.tracker.moves)
            assertTrue("issue_moved" !in run.kinds())
            run.assertDispatchedWithReason()
        }

    @Test
    fun `bl-250 pull request delivery under merge, a send back after the push starts no merge wait and nothing merges`() =
        runTest {
            val run = Run(workflow(merging), this)
            run.remote.afterPush = once { run.waiting.sendBack() }

            run.tick(this)

            assertEquals(1, run.remote.pushes.size)
            assertTrue(run.waiting.history.none { it.reason == WaitReason.Merge }, "no Waiting(merge) is stored")
            run.assertDispatchedWithReason()

            repeat(3) {
                advanceTimeBy(30_000)
                run.tick(this)
            }
            assertEquals(emptyList(), run.remote.merges, "nothing merges on a later tick")
            assertEquals(
                PullRequestState.Open,
                run.remote.open.values
                    .single()
                    .state,
                "the pull request stays open",
            )
        }

    @Test
    fun `bl-250 a permanent delivery failure keeps a send back committed during the delivery`() =
        runTest {
            val run = Run(workflow(pullRequest), this)
            run.remote.pushFailure = DeliveryError.Rejected("the push was rejected, not a fast forward")
            run.remote.afterPush = once { run.waiting.sendBack() }

            run.tick(this)

            assertContains(run.kinds(), "delivery_failed")
            assertTrue(
                run.waiting.history.none { it.reason == WaitReason.Approval && !it.approved && !it.sentBack },
                "the approval is never cleared over the send back",
            )
            run.assertDispatchedWithReason()
        }

    @Test
    fun `bl-250 a retry with a commented URL keeps a send back committed during the delivery, the next tick delivers nothing`() =
        runTest {
            val run = Run(workflow(pullRequest), this)
            run.tracker.moveFailure = TrackerError.Unavailable(IOException("connection reset"))
            run.tracker.duringMove = once { run.waiting.sendBack() }

            run.tick(this)

            assertEquals(1, run.remote.pushes.size)
            assertContains(run.kinds(), "issue_commented")
            assertTrue(run.waiting.history.none { it.data[WaitKeys.PULL_REQUEST] != null }, "the commented URL is not written over it")
            run.assertDispatchedWithReason()

            run.tracker.moveFailure = null
            advanceTimeBy(1_000)
            run.tick(this)

            assertEquals(1, run.remote.pushes.size, "the next tick delivers nothing")
            assertEquals(emptyList(), run.tracker.moves.filter { it.second == "review" })
        }

    @Test
    fun `bl-250 pull request delivery without merge, a send back after the push and before the handoff move stops the move`() =
        runTest {
            val run = Run(workflow(pullRequest), this)
            run.remote.afterPush = once { run.waiting.sendBack() }

            run.tick(this)

            assertEquals(1, run.remote.pushes.size)
            assertEquals(emptyList(), run.tracker.moves)
            assertTrue("issue_moved" !in run.kinds())
            run.assertDispatchedWithReason()
        }

    @Test
    fun `bl-250 a send back committed inside the move, after it applied, is too late, the delivery stands with a warn line`() =
        runTest {
            val run = Run(workflow(pullRequest = null), this)
            run.tracker.duringMove = once { run.waiting.sendBack() }

            run.tick(this)

            assertEquals(listOf("id-t-1" to "review"), run.tracker.moves)
            assertContains(run.kinds(), "issue_moved")
            assertEquals(emptyMap(), run.waiting.records, "the wait ends anyway")
            assertEquals(emptyList(), run.runner.calls)
            val warn = logLines.single { "sent back after its delivery" in it }
            assertContains(warn, "level=warn")
            assertContains(warn, "moved it to review, so the send back did not apply")
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
