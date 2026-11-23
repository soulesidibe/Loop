package loop.core.merge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerTokens
import loop.api.MergeMethod
import loop.api.Mergeability
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RiskReview
import loop.api.RiskReviewRequest
import loop.api.RiskReviewer
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.AutoMerge
import loop.core.delivery.MergeCheck
import loop.core.events.AutoMergeFallback
import loop.core.events.BaseFetched
import loop.core.events.BranchPushed
import loop.core.events.CiFinished
import loop.core.events.CiWaiting
import loop.core.events.DeliveryFailed
import loop.core.events.HumanTouched
import loop.core.events.MergeCheckFinished
import loop.core.events.MergeCheckStarted
import loop.core.events.MergeRiskAssessed
import loop.core.events.PullRequestMerged
import loop.core.log.KeyValueLog
import loop.core.orchestrator.FIXED_NOW
import loop.core.orchestrator.FakeSink
import loop.core.orchestrator.FakeTracker
import loop.core.orchestrator.FakeWaitingIssues
import loop.core.orchestrator.LedgerMeter
import loop.core.orchestrator.SchedulerClock
import loop.core.orchestrator.issue
import loop.core.verify.DiffEntry
import loop.core.verify.Fingerprinter
import loop.core.verify.WorkspaceGit
import loop.core.waiting.MergePhase
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.waiting.Waits
import loop.core.workflow.AgentConfig
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.MergeConfig
import loop.core.workflow.PreflightResult
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.RiskConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * bl-85: the merge module on its own, with no tick and no real git. The executor, the workspace git, the fingerprints,
 * the recheck, the reviewer and the tracker are fakes; the waits go through [Waits] over an in memory store, so each
 * test reads what the module saved, and when.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergesTest {
    private val root: Path = createTempDirectory("loop-merges").toRealPath()
    private val workspace: Path = root.resolve("workspaces/t-1")

    private fun clean(sha: String) = MergeCheck.cleanFingerprint(sha)

    private val everyGuard = CheckConfig.DiffPolicy(listOf("protected/**"), base = "main", deny = DenyGuard.entries.toList())

    private fun workflow(
        policy: DeliveryPolicy = DeliveryPolicy.Human,
        ci: CiMode = CiMode.None,
        autoMerge: AutoMergeConfig = AutoMergeConfig(enabled = policy == DeliveryPolicy.Auto),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1, maxConcurrentAgents = 10),
                    kinds =
                        KindsConfig(
                            KindConfig(
                                listOf(CheckConfig.Command("./gradlew test"), everyGuard),
                                delivery =
                                    DeliveryConfig(
                                        policy,
                                        PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", merge = MergeConfig(MergeMethod.Merge, ci)),
                                        onDeliveredMoveTo = "done",
                                        autoMerge = autoMerge,
                                        risk = RiskConfig(reviewer = "claude-sonnet-5-5"),
                                    ),
                            ),
                        ),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled", "closed"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work on {{ issue.identifier }}.",
        )

    /** A remote in memory: the pull request of `loop/t-1`, the bases a fetch gives in turn, the base tip. */
    private class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pulls = mutableMapOf("loop/t-1" to PullRequest(7, URL, created = false, headSha = VERIFIED))
        val pushes = mutableListOf<String>()
        val merges = mutableListOf<Pair<Long, String>>()
        val bases = ArrayDeque<String>()
        var lastBase: String? = null
        var tip: String? = null
        var fetchFailure: DeliveryError? = null
        var fetches = 0
        var finds = 0

        /** How many pull request reads after a push still show the old head, the provider's delayed update. */
        var headLag = 0
        private var lagReads = 0
        private var staleHead: String? = null

        var mergeable: Result<Mergeability> = Result.success(Mergeability(true, "clean"))
        var runs: Result<ActionsRuns> = Result.success(ActionsRuns(0, emptyList()))

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushes += sha
            if (headLag > 0 && pulls[branch]?.headSha != sha) {
                staleHead = pulls[branch]?.headSha
                lagReads = headLag
            }
            pulls[branch]?.let { pulls[branch] = it.copy(headSha = sha) }
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> {
            finds++
            if (lagReads > 0) {
                lagReads--
                return Result.success(pulls[branch]?.copy(headSha = staleHead))
            }
            return Result.success(pulls[branch])
        }

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> = fail("the merge module opens no pull request")

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = fail("the merge module updates no pull request")

        override suspend fun fetchBase(): Result<String> {
            fetches++
            fetchFailure?.let { return Result.failure(it) }
            val sha = bases.removeFirstOrNull() ?: checkNotNull(lastBase)
            lastBase = sha
            return Result.success(sha)
        }

        override suspend fun baseTip(): Result<String> = Result.success(tip ?: checkNotNull(lastBase))

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            merges += number to sha
            pulls["loop/t-1"] = pulls.getValue("loop/t-1").copy(state = PullRequestState.Merged, headSha = sha)
            return Result.success("feedface".repeat(5))
        }

        override suspend fun mergeability(number: Long): Result<Mergeability> = mergeable

        override suspend fun actionsRuns(sha: String): Result<ActionsRuns> = runs
    }

    /**
     * The workspace git in memory. A merge makes the next merged head, a commit whose parents are the head and the base,
     * and moves the fingerprint to it, unless [onMerge] says otherwise.
     */
    private inner class FakeGit(
        val fingerprints: Fingerprints,
    ) : MergeGit {
        var ancestor = false
        var top = VERIFIED
        val parentsOf = mutableMapOf<String, List<String>>()
        val merged = mutableListOf<String>()
        var heads = ArrayDeque(listOf(MERGED, MERGED_AGAIN))
        var onMerge: (String) -> Unit = {}
        var diff = listOf(DiffEntry("src/main/kotlin/App.kt", 1, 0), DiffEntry("src/test/kotlin/AppTest.kt", 1, 0))

        override fun isAncestor(
            root: Path,
            ancestor: String,
            commit: String,
        ): Boolean = this.ancestor

        override fun merge(
            root: Path,
            sha: String,
            message: String,
        ): WorkspaceGit.MergeOutcome {
            merged += sha
            val head = heads.removeFirst()
            parentsOf[head] = listOf(top, sha)
            top = head
            fingerprints.now = clean(head)
            onMerge(sha)
            return WorkspaceGit.MergeOutcome.Merged(head)
        }

        override fun mergeTree(
            root: Path,
            base: String,
            head: String,
        ): WorkspaceGit.MergeTreeOutcome = WorkspaceGit.MergeTreeOutcome.Clean

        override fun abortStrandedMerge(
            root: Path,
            head: String,
        ): Boolean = false

        override fun head(root: Path): String = top

        override fun parents(
            root: Path,
            sha: String,
        ): List<String> = parentsOf[sha].orEmpty()

        override fun diffStat(
            root: Path,
            base: String,
            head: String,
        ): List<DiffEntry> = diff

        override fun diffText(
            root: Path,
            base: String,
            head: String,
            maxChars: Int,
        ): String = "diff --git a/src/main/kotlin/App.kt b/src/main/kotlin/App.kt\n+fun app() = 1\n"

        override fun changedFiles(
            root: Path,
            from: String,
            to: String,
            sinceMergeBase: Boolean,
        ): List<String> = diff.map { it.path }

        override fun files(
            root: Path,
            commit: String,
            dir: String,
        ): List<String> = emptyList()
    }

    /** The workspace fingerprint as the fake git leaves it. */
    private class Fingerprints : Fingerprinter {
        var now: String = MergeCheck.cleanFingerprint(VERIFIED)

        override fun of(workspace: Path): Result<String> = Result.success(now)
    }

    /** Answers each recheck with [outcome], noting the base and whether only `diff_policy` ran. */
    private class FakeRecheck : Recheck {
        var outcome: RecheckOutcome = RecheckOutcome.Passed
        var during: () -> Unit = {}
        val runs = mutableListOf<Pair<String, Boolean>>()

        override suspend fun run(
            wait: Wait.Merge,
            issue: Issue,
            workflow: WorkflowFile,
            workspace: Path,
            base: String,
            diffPolicyOnly: Boolean,
        ): RecheckOutcome {
            runs += base to diffPolicyOnly
            during()
            return outcome
        }
    }

    private class FakeReviewer(
        var text: String,
        var during: suspend () -> Unit = {},
    ) : RiskReviewer {
        val requests = mutableListOf<RiskReviewRequest>()

        override suspend fun review(request: RiskReviewRequest): Result<RiskReview> {
            requests += request
            during()
            return Result.success(RiskReview(text, LedgerTokens(100, 20, 0, 0), 0.05))
        }
    }

    private class FakeLedger : AttemptLedger {
        val recorded = mutableListOf<LedgerAttempt>()

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            recorded += attempt
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> = Result.success(emptyList())

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: kotlin.time.Instant,
        ): Result<List<LedgerRow>> = Result.success(emptyList())
    }

    /** The reviewer's answer: every dimension at its best verdict unless [verdicts] says otherwise. */
    private fun answer(vararg verdicts: Pair<String, String>): String {
        val all =
            mapOf(
                "alignment" to "aligned",
                "impact" to "low",
                "verification" to "strong",
                "rollback_cost" to "two_way",
                "error_detection" to "loud",
            ) + verdicts
        return "{\"dimensions\": {" +
            all.entries.joinToString(", ") { (key, value) ->
                "\"$key\": {\"verdict\": \"$value\", \"reason\": \"$key looks $value\", \"files\": []}"
            } +
            "}}"
    }

    private fun actionsRun(
        status: String,
        conclusion: String?,
    ): ActionsRun = ActionsRun(10, "build", 1, "push", 1, 1, status, conclusion, "https://github.com/acme/widgets/actions/runs/10")

    private fun listed(vararg runs: ActionsRun): Result<ActionsRuns> = Result.success(ActionsRuns(runs.size.toLong(), runs.toList()))

    /** The merge wait of pull request #7 in phase `check`, approved by [approvedBy], the result verified at [VERIFIED]. */
    private fun mergeWait(approvedBy: String = "soule"): WaitingIssue =
        Wait
            .approval(
                PROJECT,
                "id-t-1",
                "t-1",
                AttemptId("dead1"),
                RunId("r0"),
                FIXED_NOW,
                to = "review",
                fingerprint = clean(VERIFIED),
                checks = "- check 0, ./gradlew test: passed with exit code 0 in 1.2s\n- check 1, diff_policy: passed",
                judgmentCalls = 0,
                approvedBy = approvedBy,
                approvedAt = FIXED_NOW,
            ).merging(7, URL, FIXED_NOW)
            .toRecord()

    /** Everything one module needs, its fakes kept for the assertions. */
    private inner class Setup(
        var workflow: WorkflowFile = workflow(),
        record: WaitingIssue = mergeWait(),
        reviewer: RiskReviewer? = FakeReviewer(answer()),
    ) {
        val tracker = FakeTracker(listOf(issue("t-1")))
        val remote = FakeRemote().apply { bases += BASE }
        val fingerprints = Fingerprints()
        val git = FakeGit(fingerprints)
        val recheck = FakeRecheck()
        val waiting = FakeWaitingIssues(record)
        val sink = FakeSink()
        val ledger = FakeLedger()
        val findings = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()
        var located: Result<Path?> = Result.success(workspace)
        var rejected: PreflightResult.Failed? = null
        var reviewerOf: RiskReviewer? = reviewer
        var woken = 0

        /** The verdict of the verification the approval rests on, for the gate's `verified` condition. */
        val verdict =
            RecordedEvent(
                FIXED_NOW,
                PROJECT,
                "verdict",
                attemptId = AttemptId("dead1"),
                data = mapOf("verdict" to "verified", "fingerprint" to clean(VERIFIED)),
            )

        val stored: WaitingIssue get() = waiting.records.values.single()

        fun TestScope.module(): Merges {
            val clock = SchedulerClock(testScheduler)
            val redactor = SecretRedactor()
            var next = 0
            val gate =
                AutoMergeGate(
                    git = git,
                    attemptEvents = { Result.success(listOf(verdict)) },
                    reviewerFor = { reviewerOf },
                    attemptLedger = ledger,
                    budgetPassed = { _, _ -> null },
                    setupOf = LedgerMeter::setupOf,
                    attemptsFolder = root.resolve("attempts"),
                    redactor = redactor,
                    clock = clock,
                    io = EmptyCoroutineContext,
                    newId = { "r${++next}" },
                    projectId = PROJECT,
                    runId = RunId("r1"),
                )
            return Merges(
                waits = Waits(waiting, PROJECT),
                tracker = tracker,
                executorFor = { remote },
                locate = { _, _ -> located },
                git = git,
                fingerprinter = fingerprints,
                recheck = recheck,
                gate = gate,
                workflow = { workflow },
                rejected = { rejected },
                onFindings = { issueId, text -> findings[issueId] = text },
                removeWorkspace = { removed += it.identifier },
                wake = { woken++ },
                sink = sink,
                sinkName = "fake",
                log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                redactor = redactor,
                clock = clock,
                io = EmptyCoroutineContext,
                projectId = PROJECT,
                runId = RunId("r1"),
            )
        }

        /** The merge wait as the store holds it now, read through a fresh [Waits], as the next tick reads it. */
        suspend fun current(): Wait.Merge {
            val waits = Waits(waiting, PROJECT)
            waits.read().getOrThrow()
            return waits.current(WaitReason.Merge).single() as Wait.Merge
        }

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }

        fun one(kind: String): Map<String, String> = all(kind).single()

        val kinds: List<String> get() = sink.events.map { it.kind }
    }

    private suspend fun TestScope.runOnce(setup: Setup): MergeWait {
        val merges = with(setup) { module() }
        return merges.run(setup.current(), issue("t-1"))
    }

    // A pass.

    @Test
    fun `a pass merges the base, rechecks, pushes, merges at the merged head, moves the issue and ends the wait`() =
        runTest {
            val setup = Setup()
            var intent: String? = null
            setup.git.onMerge = { intent = setup.stored.data[WaitKeys.BASE_SHA] }

            val result = runOnce(setup)

            assertEquals(MergeWait.Ended, result)
            assertEquals(BASE, intent, "the base is saved as the intent before the merge commit")
            assertEquals(listOf(BASE), setup.git.merged)
            assertEquals(listOf(BASE to false), setup.recheck.runs)
            // bl-174: the early push before the checks, then the merge phase's, a no op.
            assertEquals(listOf(MERGED, MERGED), setup.remote.pushes)
            assertEquals(listOf(7L to MERGED), setup.remote.merges)
            assertEquals(MergeCheck.MERGED, setup.one(MergeCheckFinished.name)["outcome"])
            assertEquals(MERGED, setup.one(PullRequestMerged.name)["sha"])
            assertEquals(listOf("id-t-1" to "done"), setup.tracker.moves)
            assertTrue(setup.waiting.records.isEmpty())
            assertEquals(listOf("t-1"), setup.removed)
            assertTrue(setup.woken > 0, "the end of the wait wakes the poll sleep")
            assertEquals(
                listOf(BaseFetched.name, MergeCheckStarted.name, BranchPushed.name, MergeCheckFinished.name, PullRequestMerged.name),
                setup.kinds.filter { it != "issue_moved" },
            )
        }

    @Test
    fun `a base already in the branch merges nothing in the workspace, reruns only diff_policy and merges the verified head`() =
        runTest {
            val setup = Setup()
            setup.git.ancestor = true
            setup.recheck.outcome = RecheckOutcome.Skipped

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertTrue(setup.git.merged.isEmpty())
            assertEquals(listOf(BASE to true), setup.recheck.runs)
            assertEquals(MergeCheck.UP_TO_DATE, setup.one(MergeCheckFinished.name)["outcome"])
            assertEquals(listOf(7L to VERIFIED), setup.remote.merges)
        }

    // The intent, saved before the merge commit.

    @Test
    fun `a stop after the intent is saved and before the merge commit is recorded resumes on restart from the base it merged`() =
        runTest {
            val setup = Setup()
            setup.git.onMerge = { throw CancellationException("stopped") }

            assertFailsWith<CancellationException> { runOnce(setup) }

            val stopped = setup.stored
            assertEquals(WaitReason.Merge, stopped.reason)
            assertEquals(WaitKeys.PHASE_CHECK, stopped.data[WaitKeys.PHASE])
            assertEquals(clean(VERIFIED), stopped.data[WaitKeys.FINGERPRINT])
            assertEquals(BASE, stopped.data[WaitKeys.BASE_SHA])

            // The restart: a fresh module over the same store. Even a fetch refused for good cannot strand the merge commit.
            setup.remote.fetchFailure = DeliveryError.Rejected("Cannot fetch main: 401 bad credentials")
            setup.git.onMerge = { fail("a second merge commit") }

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(1, setup.remote.fetches, "the resumed check fetches nothing")
            val resumed = setup.all(MergeCheckStarted.name).last()
            assertEquals("true", resumed[MergeCheckStarted.RESUMED])
            assertEquals(BASE, resumed["base_sha"])
            assertEquals(listOf(BASE to false), setup.recheck.runs, "the merged result is rechecked once, after the restart")
            assertEquals(listOf(7L to MERGED), setup.remote.merges)
        }

    // A refuted recheck.

    @Test
    fun `a refuted recheck comments, hands the findings over, ends the wait and merges nothing`() =
        runTest {
            val setup = Setup()
            setup.recheck.outcome = RecheckOutcome.Failed("check 0, ./gradlew test failed with exit code 1", "Check 0 failed on the merge.")

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(MergeCheck.REFUTED, setup.one(MergeCheckFinished.name)["outcome"])
            assertEquals(MERGED, setup.one(MergeCheckFinished.name)["head"])
            val comment =
                setup.tracker.comments
                    .single()
                    .second.text
            assertContains(comment, "the checks failed on the result (check 0, ./gradlew test failed with exit code 1)")
            assertContains(setup.findings.getValue("id-t-1"), "Check 0 failed on the merge.")
            assertTrue(setup.waiting.records.isEmpty())
            // bl-174: the merge commit was pushed before the checks ran, the refusal merges nothing.
            assertEquals(listOf(MERGED), setup.remote.pushes)
            assertTrue(setup.remote.merges.isEmpty())
        }

    @Test
    fun `a recheck that leaves the tree dirty refutes the merged result`() =
        runTest {
            val setup = Setup()
            setup.recheck.during = { setup.fingerprints.now = "git:$MERGED:dirty:dirty" }

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(MergeCheck.REFUTED, setup.one(MergeCheckFinished.name)["outcome"])
            assertContains(setup.findings.getValue("id-t-1"), "the work tree is not clean after the checks")
        }

    // A moved base.

    @Test
    fun `a base that moves before the merge redoes the check in the same run and merges the second merged head`() =
        runTest {
            val setup = Setup()
            setup.remote.bases += MOVED_BASE
            setup.remote.tip = MOVED_BASE
            val saved = mutableListOf<String?>()
            setup.git.onMerge = { saved += setup.stored.data[WaitKeys.ROUND] }

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(listOf(BASE, MOVED_BASE), setup.git.merged)
            assertEquals(listOf<String?>("1", "2"), saved, "the moved base is saved as round 2 before the second merge")
            assertEquals(listOf("1", "2"), setup.all(MergeCheckStarted.name).map { it["round"] })
            // bl-174: each round pushes its merge commit early, then again at the merge.
            assertEquals(listOf(MERGED, MERGED, MERGED_AGAIN, MERGED_AGAIN), setup.remote.pushes)
            assertEquals(listOf(7L to MERGED_AGAIN), setup.remote.merges)
            assertTrue(setup.waiting.records.isEmpty())
        }

    @Test
    fun `a base that keeps moving refuses after 3 rounds, an unapproved approval wait holding the merged fingerprint`() =
        runTest {
            val setup = Setup()
            setup.git.heads = ArrayDeque(listOf("1".repeat(40), "2".repeat(40), "3".repeat(40)))
            setup.remote.bases += listOf("e".repeat(40), "f".repeat(40))
            setup.remote.tip = "9".repeat(40)

            assertEquals(MergeWait.Continues, runOnce(setup))

            assertEquals(3, setup.all(MergeCheckStarted.name).size)
            assertEquals(MergeCheck.STEP_MERGE, setup.one(DeliveryFailed.name)["step"])
            assertTrue(setup.remote.merges.isEmpty())
            assertEquals(WaitReason.Approval, setup.stored.reason)
            assertTrue(!setup.stored.approved)
            assertEquals(clean("3".repeat(40)), setup.stored.data[WaitKeys.FINGERPRINT])
        }

    // A head that lags.

    @Test
    fun `a head that lags behind the push is read again until it shows the merged head, then merged`() =
        runTest {
            val setup = Setup()
            setup.remote.headLag = 3

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(1 + 4, setup.remote.finds, "the lookup, then three stale reads and the one showing the merged head")
            assertEquals(listOf(7L to MERGED), setup.remote.merges)
        }

    @Test
    fun `a head still stale after every read leaves the merge to its sha guard`() =
        runTest {
            val setup = Setup()
            setup.remote.headLag = Merges.HEAD_READS + 5

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(1 + Merges.HEAD_READS, setup.remote.finds)
            assertEquals(listOf(7L to MERGED), setup.remote.merges)
        }

    // The auto merge gate.

    private fun autoSetup(reviewer: RiskReviewer? = FakeReviewer(answer())): Setup =
        Setup(workflow(DeliveryPolicy.Auto), mergeWait(AutoMerge.AUTO), reviewer)

    /** Runs an auto merge to its fallback and gives the `auto_merge_fallback` data and the comment. */
    private suspend fun TestScope.fallBack(setup: Setup): Pair<Map<String, String>, String> {
        assertEquals(MergeWait.Continues, runOnce(setup))
        assertTrue(setup.remote.merges.isEmpty(), "an auto merge that falls back merges nothing")
        assertEquals(WaitReason.Approval, setup.stored.reason)
        assertTrue(!setup.stored.approved)
        assertEquals(clean(MERGED), setup.stored.data[WaitKeys.FINGERPRINT])
        assertTrue(setup.kinds.none { it == DeliveryFailed.name }, "a fallback is not a delivery_failed")
        val comment =
            setup.tracker.comments
                .last()
                .second
        assertEquals(AutoMerge.COMMENT_REASON, setup.all("issue_commented").last()["reason"])
        return setup.one(AutoMergeFallback.name) to comment.text
    }

    @Test
    fun `an auto merge whose conditions hold and whose band is low merges and writes the reviewer's ledger row`() =
        runTest {
            val setup = autoSetup()

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertEquals(listOf(7L to MERGED), setup.remote.merges)
            assertEquals("low", setup.one(MergeRiskAssessed.name)["band"])
            assertEquals(
                AutoMergeGate.REVIEW_REASON,
                setup.ledger.recorded
                    .single()
                    .reason,
            )
            assertTrue(setup.kinds.none { it == AutoMergeFallback.name || it == HumanTouched.name })
        }

    @Test
    fun `fallback reason the workspace is gone`() =
        runTest {
            val setup = autoSetup()
            setup.recheck.during = { setup.located = Result.success(null) }

            val (data, _) = fallBack(setup)

            assertEquals("the workspace is gone", data[AutoMergeFallback.REASON])
        }

    @Test
    fun `fallback reason a hard condition failed, the reviewer never runs`() =
        runTest {
            val reviewer = FakeReviewer(answer())
            val setup = autoSetup(reviewer)
            setup.git.diff = listOf(DiffEntry("src/main/kotlin/App.kt", 5_000, 0), DiffEntry("src/test/kotlin/AppTest.kt", 1, 0))

            val (data, comment) = fallBack(setup)

            assertEquals("diff_size", data[AutoMergeFallback.CONDITIONS])
            assertNull(data[AutoMergeFallback.BAND])
            assertContains(comment, "diff_size")
            assertTrue(reviewer.requests.isEmpty())
        }

    @Test
    fun `fallback reason the ci condition, read after the review`() =
        runTest {
            val setup = autoSetup()
            setup.remote.mergeable = Result.success(Mergeability(false, "dirty"))

            val (data, _) = fallBack(setup)

            assertEquals("ci", data[AutoMergeFallback.CONDITIONS])
            assertEquals("low", data[AutoMergeFallback.BAND])
        }

    @Test
    fun `fallback reason no reviewer`() =
        runTest {
            val (data, comment) = fallBack(autoSetup(reviewer = null))

            val reason = "this Loop cannot run its merge risk reviewer, so the reviewer setup, not the result, is why the merge waits"
            assertContains(data.getValue(AutoMergeFallback.REASON), reason)
            assertContains(comment, "Merge risk: not assessed, $reason")
        }

    @Test
    fun `fallback reason a medium band`() =
        runTest {
            val (data, comment) = fallBack(autoSetup(FakeReviewer(answer("impact" to "medium", "verification" to "partial"))))

            assertEquals("medium", data[AutoMergeFallback.BAND])
            assertContains(comment, "Merge risk: band medium")
        }

    @Test
    fun `fallback reason a reload rejected while the reviewer runs`() =
        runTest {
            lateinit var setup: Setup
            setup =
                autoSetup(
                    FakeReviewer(answer()) {
                        setup.rejected = PreflightResult.Failed("workflow_invalid", "auto_merge.enabled is false under policy auto")
                    },
                )

            val (data, comment) = fallBack(setup)

            assertContains(data.getValue(AutoMergeFallback.REASON), "its last change was rejected, workflow_invalid")
            assertContains(comment, "the workflow no longer allows an auto merge")
        }

    @Test
    fun `fallback reason a workflow that no longer allows an auto merge`() =
        runTest {
            val setup = autoSetup()
            setup.workflow = workflow(DeliveryPolicy.Human)

            val (data, _) = fallBack(setup)

            assertContains(data.getValue(AutoMergeFallback.REASON), "kinds.code.delivery.policy is human")
        }

    @Test
    fun `an issue a human moves out of the active states while the reviewer runs ends the wait with a human touch`() =
        runTest {
            lateinit var setup: Setup
            setup = autoSetup(FakeReviewer(answer()) { setup.tracker.issues[0] = issue("t-1", state = "canceled") })

            assertEquals(MergeWait.Ended, runOnce(setup))

            assertTrue(setup.remote.merges.isEmpty())
            assertEquals("move", setup.one(HumanTouched.name)["touch"])
            assertTrue(setup.waiting.records.isEmpty())
        }

    // m3-15's CI phase.

    @Test
    fun `under ci actions a passing check pushes the merged head and leaves the wait in phase ci for the tick`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.Actions))

            assertEquals(MergeWait.Continues, runOnce(setup))

            assertEquals(listOf(MERGED, MERGED), setup.remote.pushes, "the early push, then the push that starts the CI wait")
            assertEquals(WaitKeys.PHASE_CI, setup.stored.data[WaitKeys.PHASE])
            assertEquals(MERGED, setup.one(CiWaiting.name)["sha"])
            assertEquals(MERGED, setup.one(BranchPushed.name)["sha"])
            assertTrue(setup.remote.merges.isEmpty())
        }

    @Test
    fun `advance polls a ci wait with no slot, keeps it while runs are pending, then hands it on in phase merge`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.Actions))
            val merges = with(setup) { module() }
            merges.run(setup.current(), issue("t-1"))
            setup.remote.runs = listed(actionsRun("in_progress", null))

            advanceTimeBy(30_000)
            assertNull(merges.advance(setup.current()), "pending runs keep the wait")
            assertTrue(setup.kinds.none { it == CiFinished.name })

            setup.remote.runs = listed(actionsRun("completed", "success"))
            advanceTimeBy(30_000)
            val next = merges.advance(setup.current())

            assertEquals(MergePhase.Merge, next?.phase)
            assertEquals(CiFinished.GREEN, setup.one(CiFinished.name)["outcome"])
            assertEquals(WaitKeys.PHASE_MERGE, setup.stored.data[WaitKeys.PHASE])
            assertEquals(MergeWait.Ended, merges.run(checkNotNull(next), issue("t-1")))
            assertEquals(listOf(7L to MERGED), setup.remote.merges)
            assertEquals(1, setup.all(BranchPushed.name).size, "the head the CI wait pushed is recorded once")
        }

    @Test
    fun `advance refuses a ci wait whose runs failed, naming them, the wait an unapproved approval`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.Actions))
            val merges = with(setup) { module() }
            merges.run(setup.current(), issue("t-1"))
            setup.remote.runs = listed(actionsRun("completed", "failure"))

            advanceTimeBy(30_000)
            assertNull(merges.advance(setup.current()))

            assertEquals(CiFinished.RED, setup.one(CiFinished.name)["outcome"])
            assertEquals("ci", setup.one(DeliveryFailed.name)["step"])
            assertContains(
                setup.tracker.comments
                    .last()
                    .second.text,
                "build",
            )
            assertEquals(WaitReason.Approval, setup.stored.reason)
            assertTrue(!setup.stored.approved)
        }

    @Test
    fun `advance polls at most once every 30 seconds`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.Actions))
            val merges = with(setup) { module() }
            merges.run(setup.current(), issue("t-1"))
            setup.remote.runs = listed(actionsRun("completed", "success"))

            advanceTimeBy(10_000)
            assertNull(merges.advance(setup.current()), "a poll 10 seconds after the push lists nothing")
            assertTrue(setup.kinds.none { it == CiFinished.name })
        }

    @Test
    fun `advance hands a wait in phase check or merge on as it is, its next step needs a slot`() =
        runTest {
            val setup = Setup()
            val merges = with(setup) { module() }
            val wait = setup.current()

            assertEquals(wait, merges.advance(wait))
            assertTrue(setup.sink.events.isEmpty())
        }

    // A wait whose issue is no longer active.

    @Test
    fun `settling a wait whose pull request a human merged at the stored head moves the issue with a human touch`() =
        runTest {
            val setup = Setup()
            setup.remote.pulls["loop/t-1"] = PullRequest(7, URL, created = false, headSha = VERIFIED, state = PullRequestState.Merged)
            val merges = with(setup) { module() }

            assertEquals(MergeWait.Ended, merges.settleInactive(setup.current(), issue("t-1", state = "review")))

            assertEquals(listOf("id-t-1" to "done"), setup.tracker.moves)
            assertEquals(MergeCheck.HUMAN_MERGE, setup.one(HumanTouched.name)["touch"])
            assertEquals(listOf("t-1"), setup.removed)
        }

    @Test
    fun `settling a wait whose issue a human moved ends it with a human touch and leaves the pull request open`() =
        runTest {
            val setup = Setup()
            val merges = with(setup) { module() }

            assertEquals(MergeWait.Ended, merges.settleInactive(setup.current(), issue("t-1", state = "canceled")))

            assertEquals("move", setup.one(HumanTouched.name)["touch"])
            assertTrue(setup.tracker.moves.isEmpty())
            assertTrue(setup.waiting.records.isEmpty())
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val URL = "https://github.com/acme/widgets/pull/7"
        val VERIFIED = "a".repeat(40)
        val BASE = "b".repeat(40)
        val MERGED = "c".repeat(40)
        val MOVED_BASE = "d".repeat(40)
        val MERGED_AGAIN = "e".repeat(40)
    }
}
