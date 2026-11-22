package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.HumanTouch
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
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.api.RiskReviewer
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.AutoMerge
import loop.core.delivery.MergeCheck
import loop.core.events.AutoMergeFallback
import loop.core.events.MergeCheckStarted
import loop.core.events.MergeRiskAssessed
import loop.core.events.MergeRoundSkipped
import loop.core.events.PullRequestMerged
import loop.core.log.KeyValueLog
import loop.core.merge.AutoMergeGate
import loop.core.template.TemplateEngine
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.WorkspaceFingerprinter
import loop.core.verify.WorkspaceGit
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.BudgetsConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.ContextConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.MergeConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.RiskConfig
import loop.core.workflow.TokenCounters
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-28: conditional auto merge. Under `policy: auto` with `pull_request.merge`, a verified result is delivered as a
 * pull request and merge checked with no approval, then merged only when every hard condition holds and the merge risk
 * band is low with no dimension at its worst; otherwise it falls back to an unapproved `Waiting(approval)` whose comment
 * names why. The project repository and its worktree are real temporary repositories; the remote and the reviewer are
 * fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoMergeOrchestratorTest {
    private val root: Path = createTempDirectory("loop-auto-merge").toRealPath()
    private val home: Path = Files.createDirectories(root.resolve("home-dir"))
    private val project: Path = Files.createDirectories(root.resolve("project"))
    private val workspace: Path = root.resolve("workspaces/t-1")
    private val gitEnv = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to home.toString(), "LANG" to "C")
    private val workspaceGit = WorkspaceGit(gitEnv)
    private val fingerprinter = WorkspaceFingerprinter(workspaceGit)

    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "core.hooksPath=/dev/null") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    private fun commit(
        dir: Path,
        file: String,
        text: String,
    ): String {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        path.writeText(text)
        git(dir, "add", file)
        git(dir, "commit", "-q", "-m", "change $file")
        return git(dir, "rev-parse", "HEAD")
    }

    /** The project on `main`, the worktree of `loop/t-1` with one verified commit V changing [file]. Returns V. */
    private fun verifiedWorktree(
        file: String = "src/main/kotlin/App.kt",
        text: String = "fun app() = 1\n",
        test: Boolean = true,
    ): String {
        git(project, "init", "-q", "-b", "main")
        commit(project, "a.txt", "one\n")
        git(project, "worktree", "add", "-q", "-b", "loop/t-1", workspace.toString())
        if (test) commit(workspace, "src/test/kotlin/AppTest.kt", "class AppTest\n")
        return commit(workspace, file, text)
    }

    private fun clean(sha: String) = MergeCheck.cleanFingerprint(sha)

    private val everyGuard = CheckConfig.DiffPolicy(listOf("protected/**"), base = "main", deny = DenyGuard.entries.toList())

    private fun workflow(
        checks: List<CheckConfig> = listOf(CheckConfig.Command("./gradlew test"), everyGuard),
        holdout: List<CheckConfig> = emptyList(),
        autoMerge: AutoMergeConfig = AutoMergeConfig(enabled = true),
        risk: RiskConfig = RiskConfig(reviewer = "claude-sonnet-5-5"),
        policy: DeliveryPolicy = DeliveryPolicy.Auto,
        budgets: BudgetsConfig = BudgetsConfig(),
        merge: MergeConfig = MergeConfig(MergeMethod.Merge, CiMode.None),
        context: ContextConfig = ContextConfig(),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1, maxConcurrentAgents = 10),
                    budgets = budgets,
                    context = context,
                    kinds =
                        KindsConfig(
                            KindConfig(
                                checks,
                                holdout = holdout,
                                delivery =
                                    DeliveryConfig(
                                        policy,
                                        PullRequestConfig(
                                            "acme/widgets",
                                            "LOOP_PR_TOKEN",
                                            merge = merge,
                                        ),
                                        onDeliveredMoveTo = "done",
                                        autoMerge = autoMerge,
                                        risk = risk,
                                    ),
                            ),
                        ),
                ),
            path = project.resolve("WORKFLOW.md"),
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

    /** A GitHub like remote: pushes need the local branch at the commit, a merge marks the pull request merged. */
    private inner class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<Pair<String, String>>()
        val merges = mutableListOf<Pair<Long, String>>()
        val pulls = mutableMapOf<String, PullRequest>()
        var base: String? = null
        var mergeable: Result<Mergeability> = Result.success(Mergeability(true, "clean"))

        /** A head the pull request keeps showing whatever is pushed, GitHub stuck on an old head. */
        var stuckHead: String? = null

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushes += sha to branch
            pulls[branch]?.let { pulls[branch] = it.copy(headSha = sha) }
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> =
            Result.success(pulls[branch]?.let { pull -> stuckHead?.let { pull.copy(headSha = it) } ?: pull }?.copy(created = false))

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            val pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = pushes.lastOrNull()?.first)
            pulls[branch] = pull
            return Result.success(pull)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(pulls.values.single { it.number == number }.copy(created = false))

        override suspend fun fetchBase(): Result<String> = Result.success(checkNotNull(base))

        override suspend fun baseTip(): Result<String> = Result.success(checkNotNull(base))

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            merges += number to sha
            val branch = pulls.entries.single { it.value.number == number }.key
            pulls[branch] = pulls.getValue(branch).copy(state = PullRequestState.Merged, headSha = sha)
            return Result.success("feedface".repeat(5))
        }

        /** The Actions runs of each commit, none by default; [headRunsOf] answers for any commit but the base (m3-15). */
        val actionsRuns = mutableMapOf<String, Result<ActionsRuns>>()
        var headRunsOf: ((String) -> Result<ActionsRuns>)? = null
        val actionsReads = mutableListOf<String>()
        val mergeabilityReads = mutableListOf<Int>()
        var reviews = 0

        override suspend fun mergeability(number: Long): Result<Mergeability> {
            mergeabilityReads += reviews
            return mergeable
        }

        override suspend fun actionsRuns(sha: String): Result<ActionsRuns> {
            actionsReads += sha
            return actionsRuns[sha] ?: headRunsOf?.takeIf { sha != base }?.invoke(sha) ?: Result.success(ActionsRuns(0, emptyList()))
        }
    }

    /** One Actions run of the workflow [workflow] (m3-15). */
    private fun actionsRun(
        name: String,
        status: String,
        conclusion: String?,
        workflow: Long = 1,
    ): ActionsRun =
        ActionsRun(
            workflow * 10,
            name,
            workflow,
            "push",
            1,
            1,
            status,
            conclusion,
            "https://github.com/acme/widgets/actions/runs/${workflow * 10}",
        )

    private fun listed(vararg runs: ActionsRun): Result<ActionsRuns> = Result.success(ActionsRuns(runs.size.toLong(), runs.toList()))

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
            all.entries.joinToString(
                ", ",
            ) { (key, value) -> "\"$key\": {\"verdict\": \"$value\", \"reason\": \"$key looks $value\", \"files\": []}" } +
            "}}"
    }

    private class FakeReviewer(
        var result: Result<RiskReview>,
        var during: suspend () -> Unit = {},
    ) : RiskReviewer {
        val requests = mutableListOf<RiskReviewRequest>()

        override suspend fun review(request: RiskReviewRequest): Result<RiskReview> {
            requests += request
            during()
            return result
        }
    }

    private fun reviewer(text: String = answer()) = FakeReviewer(Result.success(RiskReview(text, LedgerTokens(100, 20, 0, 0), 0.05)))

    private class FakeLedger : AttemptLedger {
        val recorded = mutableListOf<LedgerAttempt>()
        val previous = mutableListOf<LedgerRow>()

        /** Runs at the start of every [attemptRowsSince] call. */
        var onRowsSince: () -> Unit = {}

        /** When set, every read of the rows fails with it. */
        var readFailure: Throwable? = null

        override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> {
            recorded += attempt
            return Result.success(Unit)
        }

        override suspend fun attemptRows(
            projectId: ProjectId,
            issueId: String,
        ): Result<List<LedgerRow>> =
            readFailure?.let { Result.failure(it) }
                ?: Result.success(previous + recorded.filter { it.issueId == issueId }.map { LedgerRow(it, 0, 0) })

        override suspend fun attemptRowsSince(
            projectId: ProjectId,
            since: Instant,
        ): Result<List<LedgerRow>> {
            onRowsSince()
            readFailure?.let { return Result.failure(it) }
            return Result.success(previous + recorded.map { LedgerRow(it, 0, 0) })
        }
    }

    private val passing = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 5) }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        remote: DeliveryExecutor,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        reviewer: RiskReviewer? = reviewer(),
        ledger: FakeLedger = FakeLedger(),
        runner: FakeRunner = FakeRunner(),
        events: (suspend (AttemptId) -> Result<List<RecordedEvent>>)? = null,
        preflight: PreflightCheck = PreflightCheck { PreflightResult.Ok(workflow) },
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
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { passing },
            waiting = waiting,
            fingerprinter = fingerprinter,
            attemptLedger = ledger,
            deliveryFor = { remote },
            workspaceGit = workspaceGit,
            attemptEvents = events ?: { attempt -> Result.success(sink.events.filter { it.attemptId == attempt }) },
            riskReviewerFor = { reviewer },
            preflight = preflight,
        )
    }

    private fun FakeSink.all(kind: String): List<Map<String, String>> = events.filter { it.kind == kind }.map { it.data }

    /** The events of the ordinary verification of attempt `dead1` that verified [fingerprint]. */
    private fun verifiedEvents(
        fingerprint: String,
        extra: List<RecordedEvent> = emptyList(),
    ): suspend (AttemptId) -> Result<List<RecordedEvent>> =
        {
            Result.success(
                listOf(
                    RecordedEvent(
                        FIXED_NOW,
                        PROJECT,
                        "verdict",
                        attemptId = AttemptId("dead1"),
                        data = mapOf("verdict" to "verified", "fingerprint" to fingerprint),
                    ),
                ) + extra,
            )
        }

    /** The approval wait the drain writes under auto, approved by `auto`, for the verified [fingerprint]. */
    private fun autoWait(
        fingerprint: String,
        checks: String = "- check 0, ./gradlew test: passed with exit code 0 in 1.2s\n- check 1, diff_policy: passed",
    ): WaitingIssue =
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
                    WaitKeys.FINGERPRINT to fingerprint,
                    WaitKeys.CHECKS to checks,
                    WaitKeys.JUDGMENT_CALLS to "0",
                    WaitingIssue.APPROVED_BY to AutoMerge.AUTO,
                    WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
                ),
        )

    /** One auto merge from the auto approved wait of the verified commit, through the merge check to the gate. */
    private class Run(
        val tracker: FakeTracker,
        val remote: FakeRemote,
        val waiting: FakeWaitingIssues,
        val sink: FakeSink,
        val ledger: FakeLedger,
        val reviewer: RiskReviewer?,
    ) {
        val comment: String get() =
            tracker.comments
                .last()
                .second.text

        fun fellBack(): Boolean {
            val wait = waiting.records.values.singleOrNull() ?: return false
            return wait.reason == WaitReason.Approval && !wait.approved && remote.merges.isEmpty()
        }
    }

    private suspend fun TestScope.autoMerge(
        workflow: WorkflowFile = workflow(),
        reviewer: RiskReviewer? = reviewer(),
        prepare: (FakeRemote) -> Unit = {},
        wait: (String) -> WaitingIssue = { autoWait(clean(it)) },
        events: ((String) -> (suspend (AttemptId) -> Result<List<RecordedEvent>>))? = null,
        worktree: () -> String = { verifiedWorktree() },
        ledger: FakeLedger = FakeLedger(),
        preflight: ((WorkflowFile) -> PreflightCheck)? = null,
        tracker: FakeTracker = FakeTracker(listOf(issue("t-1"))),
        started: (Orchestrator) -> Unit = {},
        ciPolls: Int = 0,
    ): Run {
        val verified = worktree()
        val remote = FakeRemote().apply { base = git(project, "rev-parse", "main") }
        prepare(remote)
        val waiting = FakeWaitingIssues(wait(verified))
        val sink = FakeSink()
        val orchestrator =
            orchestrator(
                tracker,
                remote,
                waiting,
                sink,
                workflow,
                reviewer,
                ledger,
                events = (events ?: { sha -> verifiedEvents(clean(sha)) })(verified),
                preflight = preflight?.invoke(workflow) ?: PreflightCheck { PreflightResult.Ok(workflow) },
            )
        started(orchestrator)
        orchestrator.tick()
        testScheduler.advanceUntilIdle()
        // The first tick delivers the pull request and starts the merge wait, the next runs the merge check.
        orchestrator.tick()
        testScheduler.advanceUntilIdle()
        // m3-15: under ci actions each poll of the CI wait comes 30 seconds after the last.
        repeat(ciPolls) {
            advanceTimeBy(30_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()
        }
        return Run(tracker, remote, waiting, sink, ledger, reviewer)
    }

    // When every condition holds.

    @Test
    fun `when every condition holds and the band is low, Loop merges with no human touch and the ledger counts none`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { base = git(project, "rev-parse", "main") }
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val ledger = FakeLedger()
            val reviewer = reviewer()
            val runner =
                FakeRunner().apply {
                    onCall =
                        { ctx ->
                            ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                        }
                }
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(), reviewer, ledger, runner)

            // The attempt verifies and the drain writes the approval of the auto policy, no evidence summary.
            orchestrator.tick()
            testScheduler.advanceUntilIdle()
            val approval = waiting.records.values.single()
            assertEquals(WaitReason.Approval, approval.reason)
            assertEquals(AutoMerge.AUTO, approval.data[WaitingIssue.APPROVED_BY])
            assertTrue(sink.events.none { it.kind == "approval_requested" })
            assertTrue(tracker.comments.isEmpty(), "no evidence summary under auto")

            repeat(3) {
                orchestrator.tick()
                testScheduler.advanceUntilIdle()
            }

            assertEquals(listOf(7L to verified), remote.merges)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
            assertEquals("auto", sink.all("issue_moved").single()["approved_by"])
            assertTrue(sink.events.none { it.kind == "human_touch" }, "no human touch at all")
            assertTrue(sink.events.none { it.kind == AutoMergeFallback.name })
            assertTrue(ledger.recorded.first().reason != AutoMergeGate.REVIEW_REASON, "the attempt's own row comes first")
            val rows = ledger.attemptRows(PROJECT, "id-t-1").getOrThrow()
            assertTrue(rows.all { it.humanTouches == 0 }, "every ledger row of the issue counts zero human touches")
            val review = ledger.recorded.single { it.reason == AutoMergeGate.REVIEW_REASON }
            assertEquals("claude-sonnet-5-5", review.setup.model)
            assertEquals(0.05, review.costUsd)
            assertEquals(1, reviewer.requests.size)
        }

    @Test
    fun `merge_risk_assessed records the verdicts, the floors, the score and the band`() =
        runTest {
            val run = autoMerge()

            val assessed = run.sink.all(MergeRiskAssessed.name).single() - MergeRiskAssessed.PROMPT_TOKENS
            assertEquals(
                mapOf(
                    "alignment" to "aligned",
                    "impact" to "low",
                    "verification" to "strong",
                    "rollback_cost" to "two_way",
                    "error_detection" to "loud",
                    "floors" to "",
                    "score" to "1.00",
                    "band" to "low",
                    "reviewer" to "claude-sonnet-5-5",
                    "diff_cut" to "false",
                    "files_left_out" to "0",
                ),
                assessed,
            )
            assertEquals(1, run.remote.merges.size)
        }

    @Test
    fun `the reviewer reads the issue, the code facts and the diff against the merge base, read only in the workspace`() =
        runTest {
            val reviewer = reviewer()
            autoMerge(reviewer = reviewer)

            val request = reviewer.requests.single()
            assertEquals(workspace, request.workspace)
            assertEquals("claude-sonnet-5-5", request.model)
            assertContains(request.prompt, "## Issue t-1: Title of t-1")
            assertContains(request.prompt, "+fun app() = 1")
            assertContains(request.prompt, "Test files changed: src/test/kotlin/AppTest.kt.")
            assertContains(request.prompt, "change no file")
        }

    // m3-13: the reviewer's prompt is counted against prompt_limit of the reviewer model's window.

    /** A context whose window for the reviewer's model, `anthropic/claude-sonnet-5-5`, gives a limit of [limit] tokens. */
    private fun reviewerContext(limit: Long) =
        ContextConfig(
            promptLimit = 0.05,
            windows =
                mapOf(
                    "anthropic/claude-opus-5-5" to 1_000_000L,
                    "anthropic/claude-sonnet-5-5" to limit * 20,
                ),
        )

    /** 100 lines of about 200 characters: a diff of about 20000 characters, under the line limit of the diff_size condition. */
    private val wideText = (1..100).joinToString("") { "val line%03d = \"%s\"\n".format(it, "x".repeat(180)) }

    @Test
    fun `m3-13 merge_risk_assessed records the tokens of the reviewer's prompt, under the limit of its model's window`() =
        runTest {
            val reviewer = reviewer()
            val run = autoMerge(reviewer = reviewer)

            val prompt = reviewer.requests.single().prompt
            val tokens = run.sink.all(MergeRiskAssessed.name).single()[MergeRiskAssessed.PROMPT_TOKENS]
            assertEquals(TokenCounters.forModel("claude-sonnet-5-5").count(prompt).toString(), tokens)
            assertFalse("is cut to fit" in prompt, "a small diff is shown whole")
            assertEquals(1, run.remote.merges.size)
        }

    @Test
    fun `m3-13 a diff over the reviewer's prompt limit is cut to fit, and the prompt says so with the merge base and head`() =
        runTest {
            val reviewer = reviewer()
            var head = ""
            val run =
                autoMerge(
                    workflow = workflow(context = reviewerContext(limit = 3_000)),
                    reviewer = reviewer,
                    // The base is already in the branch, so the merged head is the verified commit.
                    worktree = { verifiedWorktree(text = wideText).also { head = it } },
                )

            val prompt = reviewer.requests.single().prompt
            val tokens = TokenCounters.forModel("claude-sonnet-5-5").count(prompt)
            assertTrue(tokens <= 3_000, "$tokens tokens for a limit of 3000")
            assertEquals(tokens.toString(), run.sink.all(MergeRiskAssessed.name).single()[MergeRiskAssessed.PROMPT_TOKENS])
            assertContains(prompt, "+val line001")
            assertFalse("+val line100" in prompt, "the end of the diff is left out")
            val base = git(project, "rev-parse", "main")
            assertContains(prompt, "## Diff against the merge base $base (cut)")
            assertTrue(
                Regex(
                    "The diff above is cut to fit the reviewer's prompt limit: \\d+ of its characters are left out\\. " +
                        "It is the diff from the merge base $base to the head $head, the commit the workspace holds",
                ).containsMatchIn(prompt),
                prompt,
            )
            assertEquals(1, reviewer.requests.size, "the review goes on with the cut diff")
            assertTrue(run.fellBack(), "bl-137: a cut diff waits for approval")
        }

    // bl-137: a diff cut to fit the reviewer's prompt floors the band at medium, so it never merges on its own.

    @Test
    fun `bl-137 a diff that must be cut to fit the reviewer's prompt waits for approval, the cut named in the reason`() =
        runTest {
            val reviewer = reviewer()
            val run =
                autoMerge(
                    workflow = workflow(context = reviewerContext(limit = 3_000)),
                    reviewer = reviewer,
                    worktree = { verifiedWorktree(text = wideText) },
                )

            val reason =
                "the reviewer's diff was cut to fit its prompt limit, 2 of 2 changed files left out in whole or in part"
            assertTrue(run.fellBack(), "falls back to an unapproved Waiting(approval), nothing merged")
            val assessed = run.sink.all(MergeRiskAssessed.name).single()
            assertEquals("medium", assessed["band"], "a best review of a cut diff is floored at medium")
            assertEquals("1.00", assessed["score"])
            assertEquals("true", assessed[MergeRiskAssessed.DIFF_CUT])
            assertEquals("2", assessed[MergeRiskAssessed.FILES_LEFT_OUT])
            val fallback = run.sink.all(AutoMergeFallback.name).single()
            assertEquals("medium", fallback["band"])
            assertContains(fallback["reason"].orEmpty(), "the merge risk band is medium, an auto merge needs low")
            assertContains(fallback["reason"].orEmpty(), reason)
            assertContains(run.comment, "Merge risk: band medium, score 1.00.")
            assertContains(run.comment, "- band floor medium: $reason")
            val prompt = reviewer.requests.single().prompt
            assertContains(
                prompt,
                "- src/main/kotlin/App.kt: +100 -0, left out by the cut\n- src/test/kotlin/AppTest.kt: +1 -0, left out by the cut\n",
            )
            assertContains(prompt, "The cut left out 2 of the 2 changed files, in whole or in part")
        }

    @Test
    fun `bl-137 a diff that fits the reviewer's prompt changes nothing, it is listed whole and merges on its own`() =
        runTest {
            val reviewer = reviewer()
            val run = autoMerge(reviewer = reviewer)

            val assessed = run.sink.all(MergeRiskAssessed.name).single()
            assertEquals("low", assessed["band"])
            assertEquals("false", assessed[MergeRiskAssessed.DIFF_CUT])
            assertEquals("0", assessed[MergeRiskAssessed.FILES_LEFT_OUT])
            val prompt = reviewer.requests.single().prompt
            assertContains(prompt, "- src/main/kotlin/App.kt: +1 -0\n- src/test/kotlin/AppTest.kt: +1 -0\n")
            assertFalse("left out" in prompt, prompt)
            assertTrue(run.sink.events.none { it.kind == AutoMergeFallback.name })
            assertEquals(1, run.remote.merges.size)
        }

    @Test
    fun `m3-13 a reviewer prompt that cannot fit even with an empty diff falls back to Waiting(approval) naming the reason`() =
        runTest {
            val reviewer = reviewer()
            val run = autoMerge(workflow = workflow(context = reviewerContext(limit = 100)), reviewer = reviewer)

            assertReviewFellBack(run, "the reviewer's prompt is ")
            val line =
                run.comment
                    .substringAfter("Merge risk: not assessed, ")
                    .lineSequence()
                    .first()
            assertTrue(
                Regex(
                    "^the reviewer's prompt is \\d+ tokens even with an empty diff, over its prompt limit of 100 tokens",
                ).containsMatchIn(line),
                line,
            )
            assertTrue(reviewer.requests.isEmpty(), "no reviewer session runs")
            assertEquals(0, run.remote.merges.size)
        }

    // Each hard condition failing alone.

    private fun assertFellBack(
        run: Run,
        condition: String,
        text: String,
    ) {
        assertTrue(run.fellBack(), "falls back to an unapproved Waiting(approval), nothing merged")
        assertContains(run.comment, "Loop did not merge pull request #7 on its own")
        assertContains(run.comment, "- $condition: $text")
        assertEquals(condition, run.sink.all(AutoMergeFallback.name).single()["conditions"])
        assertEquals(AutoMerge.COMMENT_REASON, run.sink.all("issue_commented").last()["reason"])
        assertTrue(run.sink.events.none { it.kind == "delivery_failed" })
        assertTrue((run.reviewer as? FakeReviewer)?.requests.isNullOrEmpty(), "no reviewer session once a condition failed")
        assertContains(run.comment, "Merge risk: not assessed, a condition failed.")
    }

    @Test
    fun `condition verified, a holdout the verification did not pass falls back`() =
        runTest {
            val run = autoMerge(workflow(holdout = listOf(CheckConfig.Command("./hidden.sh"))))

            assertFellBack(run, "verified", "0 of the 1 hidden checks of the kind passed")
        }

    @Test
    fun `condition merge_check, a pull request showing another head than the checked one falls back`() =
        runTest {
            val run = autoMerge(prepare = { it.stuckHead = "c".repeat(40) })

            assertFellBack(run, "merge_check", "pull request #7 shows the head cccccccccccc")
        }

    /** CI is read after the review (send back 5 of PR #75): the fallback names it and carries the assessed band. */
    private fun assertCiFellBack(
        run: Run,
        text: String,
    ) {
        assertTrue(run.fellBack(), "falls back to an unapproved Waiting(approval), nothing merged")
        assertContains(run.comment, "- ci: $text")
        assertEquals("ci", run.sink.all(AutoMergeFallback.name).single()["conditions"])
        assertEquals(1, (run.reviewer as FakeReviewer).requests.size, "the review ran before CI was read")
        assertContains(run.comment, "Merge risk: band low, score 1.00.")
    }

    @Test
    fun `condition ci, a pull request that is not mergeable and clean falls back`() =
        runTest {
            val run = autoMerge(prepare = { it.mergeable = Result.success(Mergeability(true, "unstable")) })

            assertCiFellBack(run, "pull request #7 is not mergeable and green, mergeable true, state unstable")
        }

    @Test
    fun `send back 5, mergeability is read after the review, so a state that turns while it runs is seen`() =
        runTest {
            lateinit var remote: FakeRemote
            val reviewer =
                reviewer().apply {
                    during = {
                        remote.reviews++
                        remote.mergeable = Result.success(Mergeability(true, "unstable"))
                    }
                }
            val run = autoMerge(reviewer = reviewer, prepare = { remote = it })

            assertTrue(remote.mergeabilityReads.isNotEmpty() && remote.mergeabilityReads.all { it == 1 }, "${remote.mergeabilityReads}")
            assertCiFellBack(run, "pull request #7 is not mergeable and green, mergeable true, state unstable")
        }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, a merged head with no check run yet falls back when the base tip has check runs`() =
        runTest {
            val run =
                autoMerge(prepare = { remote ->
                    remote.actionsRuns[checkNotNull(remote.base)] = listed(actionsRun("build", "completed", "success"))
                })

            assertCiFellBack(run, "no Actions run is registered on the merged head ")
            assertContains(run.comment, "yet, while the base tip has 1 Actions runs")
        }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, a workflow in the merged head expects checks, a running one falls back`() =
        runTest {
            val run =
                autoMerge(
                    worktree = {
                        val verified = verifiedWorktree()
                        commit(project, ".github/workflows/ci.yml", "on: pull_request\n")
                        verified
                    },
                    prepare = { remote ->
                        remote.actionsRuns.clear()
                        remote.headRunsOf = { listed(actionsRun("build", "in_progress", null)) }
                    },
                )

            assertCiFellBack(run, "Actions runs on the merged head ")
            assertContains(run.comment, "are pending: 1 of the 1 kept runs not completed, build (in_progress)")
        }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, completed passing check runs on the merged head merge`() =
        runTest {
            val run =
                autoMerge(
                    worktree = {
                        val verified = verifiedWorktree()
                        commit(project, ".github/workflows/ci.yml", "on: pull_request\n")
                        verified
                    },
                    prepare = { remote -> remote.headRunsOf = { listed(actionsRun("build", "completed", "success")) } },
                )

            assertEquals(1, run.remote.merges.size, run.waiting.records.toString())
        }

    // m3-15: the CI condition under ci actions, read at the gate after the CI wait passed.

    private val actions = MergeConfig(MergeMethod.Merge, CiMode.Actions, ciTimeoutMs = 600_000, ciNoRunGraceMs = 60_000)

    /** The CI wait sees [wait], the gate then sees [gate]: runs can change between the two reads. */
    private fun sequence(
        wait: Result<ActionsRuns>,
        gate: Result<ActionsRuns>,
    ): (FakeRemote) -> Unit =
        { remote ->
            var reads = 0
            remote.headRunsOf = { if (reads++ == 0) wait else gate }
        }

    @Test
    fun `m3-15, under ci actions green at the wait and at the gate merges, reading only the merged head's Actions runs`() =
        runTest {
            val green = listed(actionsRun("build", "completed", "success"))
            val run = autoMerge(workflow(merge = actions), prepare = sequence(green, green), ciPolls = 1)

            assertEquals(1, run.remote.merges.size, run.waiting.records.toString())
            val head =
                run.remote.merges
                    .single()
                    .second
            assertEquals(listOf(head, head), run.remote.actionsReads, "the wait, then the gate, never the base tip")
            assertEquals("green", run.sink.all("ci_finished").single()["outcome"])
        }

    @Test
    fun `m3-15, under ci actions a red CI at the gate fails the condition and falls back naming it`() =
        runTest {
            val run =
                autoMerge(
                    workflow(merge = actions),
                    prepare =
                        sequence(
                            listed(actionsRun("build", "completed", "success")),
                            listed(actionsRun("build", "completed", "failure")),
                        ),
                    ciPolls = 1,
                )

            assertCiFellBack(run, "Actions runs on the merged head ")
            assertContains(run.comment, "did not pass: build (failure)")
        }

    @Test
    fun `m3-15, under ci actions a pending CI at the gate fails the condition and falls back naming it`() =
        runTest {
            val run =
                autoMerge(
                    workflow(merge = actions),
                    prepare =
                        sequence(
                            listed(actionsRun("build", "completed", "success")),
                            listed(actionsRun("build", "completed", "success"), actionsRun("lint", "queued", null, workflow = 2)),
                        ),
                    ciPolls = 1,
                )

            assertCiFellBack(run, "Actions runs on the merged head ")
            assertContains(run.comment, "are pending: 1 of the 2 kept runs not completed, lint (queued)")
        }

    @Test
    fun `m3-15, under ci actions a missing CI at the gate fails the condition and falls back naming it`() =
        runTest {
            val run =
                autoMerge(
                    workflow(merge = actions),
                    prepare = sequence(listed(actionsRun("build", "completed", "success")), listed()),
                    ciPolls = 1,
                )

            assertCiFellBack(run, "no Actions run on the merged head ")
            assertContains(run.comment, "and ci_no_run is fail")
        }

    @Test
    fun `m3-15, a 403 on the runs listing fails the ci condition naming Actions read, with no check runs read`() =
        runTest {
            val forbidden =
                Result.failure<ActionsRuns>(
                    loop.api.DeliveryError.Rejected(
                        "Cannot list the Actions runs on acme/widgets: The tracker answered 403: the token needs the Actions: read permission",
                    ),
                )
            val run = autoMerge(prepare = { remote -> remote.headRunsOf = { forbidden } })

            assertCiFellBack(run, "cannot read the Actions runs of the merged head ")
            assertContains(run.comment, "Actions: read")
        }

    // The base moving during the review.

    @Test
    fun `send back 4, a base that moves while the reviewer runs redoes the merge check instead of merging`() =
        runTest {
            lateinit var remote: FakeRemote
            var moved: String? = null
            val reviewer =
                reviewer().apply {
                    during = {
                        if (requests.size == 1) {
                            moved = commit(project, "d.txt", "moved\n")
                            remote.base = moved
                        }
                    }
                }
            val run = autoMerge(reviewer = reviewer, prepare = { remote = it })

            val rounds = run.sink.all(MergeCheckStarted.name).map { it["round"] to it["base_sha"] }
            assertEquals(listOf("1" to rounds.first().second, "2" to moved), rounds, "the check ran again on the moved base")
            assertEquals(2, reviewer.requests.size, "the merged result of the moved base is reviewed again")
            val merged =
                run.remote.merges
                    .single()
                    .second
            git(project, "merge-base", "--is-ancestor", checkNotNull(moved), merged)
            assertTrue(run.sink.events.none { it.kind == AutoMergeFallback.name })
        }

    @Test
    fun `a base that moves while the reviewer runs only in recheck_skip_paths merges with no new round (bl-176)`() =
        runTest {
            lateinit var remote: FakeRemote
            var moved: String? = null
            val reviewer =
                reviewer().apply {
                    during = {
                        if (requests.size == 1) {
                            moved = commit(project, ".loop/issues/t-9.md", "state: done\n")
                            remote.base = moved
                        }
                    }
                }
            val skipping = workflow(merge = MergeConfig(MergeMethod.Merge, CiMode.None, recheckSkipPaths = listOf(".loop/issues/**")))
            val run = autoMerge(workflow = skipping, reviewer = reviewer, prepare = { remote = it })

            assertEquals(listOf("1"), run.sink.all(MergeCheckStarted.name).map { it["round"] }, "no new round")
            assertEquals(1, reviewer.requests.size, "the gate ran once, on the merged head")
            val skipped = run.sink.all(MergeRoundSkipped.name).single()
            assertEquals(moved, skipped[MergeRoundSkipped.TIP])
            assertEquals(".loop/issues/t-9.md", skipped[MergeRoundSkipped.FILES])
            assertEquals(1, run.remote.merges.size)
            assertTrue(run.sink.events.none { it.kind == AutoMergeFallback.name })
        }

    @Test
    fun `round 2 of PR #75, an issue a human moves out of the active states while the reviewer runs is not merged`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val reviewer = reviewer().apply { during = { tracker.issues[0] = issue("t-1", state = "canceled") } }
            val run = autoMerge(reviewer = reviewer, tracker = tracker)

            assertEquals(1, reviewer.requests.size)
            assertEquals(emptyList(), run.remote.merges, "nothing merges")
            assertEquals(emptyMap(), run.waiting.records, "the wait ends")
            assertEquals(HumanTouch.MOVE, run.sink.all(HumanTouch.EVENT).single()[HumanTouch.TOUCH])
            assertTrue(tracker.moves.isEmpty(), "Loop does not move the issue a human moved")
            assertTrue(run.sink.events.none { it.kind == PullRequestMerged.name })
        }

    @Test
    fun `round 2 of PR #75, a reload that turns auto merge off while the reviewer runs merges nothing and falls back`() =
        runTest {
            lateinit var orchestrator: Orchestrator
            var reloaded = false
            val human = workflow(policy = DeliveryPolicy.Human, autoMerge = AutoMergeConfig()).copy(sourceHash = "human")
            val reviewer =
                reviewer().apply {
                    during = {
                        // A tick while the merge check runs reads the changed file, as the poll loop would.
                        reloaded = true
                        orchestrator.tick()
                    }
                }
            val run =
                autoMerge(
                    reviewer = reviewer,
                    preflight = { auto -> PreflightCheck { PreflightResult.Ok(if (reloaded) human else auto) } },
                    started = { orchestrator = it },
                )

            assertEquals(1, reviewer.requests.size)
            assertEquals(1, run.sink.all("config_reloaded").size, "the reload happened during the review")
            assertEquals(emptyList(), run.remote.merges, "nothing merges")
            assertTrue(run.fellBack(), "an unapproved Waiting(approval) waits for a human")
            val fallback = run.sink.all(AutoMergeFallback.name).single()
            assertContains(checkNotNull(fallback["reason"]), "the workflow no longer allows an auto merge")
            assertContains(run.comment, "the workflow no longer allows an auto merge")
        }

    @Test
    fun `round 2 of PR #75, a reload to policy human that keeps the opt in merges nothing and falls back`() =
        runTest {
            lateinit var orchestrator: Orchestrator
            var reloaded = false
            val human = workflow(policy = DeliveryPolicy.Human, autoMerge = AutoMergeConfig(enabled = true)).copy(sourceHash = "human")
            val reviewer =
                reviewer().apply {
                    during = {
                        reloaded = true
                        orchestrator.tick()
                    }
                }
            val run =
                autoMerge(
                    reviewer = reviewer,
                    preflight = { auto -> PreflightCheck { PreflightResult.Ok(if (reloaded) human else auto) } },
                    started = { orchestrator = it },
                )

            assertEquals(emptyList(), run.remote.merges, "policy human, still opted in, merges nothing on its own")
            assertTrue(run.fellBack())
            assertContains(checkNotNull(run.sink.all(AutoMergeFallback.name).single()["reason"]), "policy is human")
        }

    /** A `WORKFLOW.md` on disk under auto merge, opted in or not, read by the real [Preflight] of [diskPreflight]. */
    private fun diskWorkflow(
        enabled: Boolean = true,
        prompt: String = "Work on {{ issue.identifier }}.",
    ): Path =
        Files.createDirectories(root.resolve("config")).resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |kinds:
                |  code:
                |    delivery:
                |      policy: auto
                |      auto_merge: { enabled: $enabled }
                |      risk: { reviewer: claude-sonnet-5-5 }
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}LOOP_PR_TOKEN
                |        merge: { ci: none }
                |      on_delivered: { move_to: done }
                |    verify:
                |      checks: []
                |---
                |$prompt
                """.trimMargin(),
            )
        }

    private fun diskPreflight(file: Path): Preflight =
        Preflight(
            loader = WorkflowLoader(env = mapOf("LOOP_PR_TOKEN" to "ghp_pullRequestToken0123456789abcdefgh")),
            path = file,
            templates = TemplateEngine(),
            trackerCheck = { null },
        )

    /**
     * One auto merge where a tick inside the reviewer reads [file] after [change] rewrote it, through the real
     * [Preflight]; before that the preflight answers with the workflow under test, as an unchanged file would.
     */
    private suspend fun TestScope.autoMergeWithFileChange(
        file: Path,
        change: () -> Unit,
    ): Pair<Run, FakeReviewer> {
        val disk = diskPreflight(file)
        assertIs<PreflightResult.Ok>(disk.check(), "the file loads before the change")
        lateinit var orchestrator: Orchestrator
        var changed = false
        val reviewer =
            reviewer().apply {
                during = {
                    change()
                    changed = true
                    orchestrator.tick()
                }
            }
        val run =
            autoMerge(
                reviewer = reviewer,
                // Blocking, since the real preflight reads on Dispatchers.IO, which would let virtual time run out the reviewer's timeout.
                preflight = { auto -> PreflightCheck { if (changed) runBlocking { disk.check() } else PreflightResult.Ok(auto) } },
                started = { orchestrator = it },
            )
        return run to reviewer
    }

    @Test
    fun `round 3 of PR #75, a file change that only turns auto_merge enabled off while the reviewer runs falls back`() =
        runTest {
            val file = diskWorkflow(enabled = true)
            val (run, reviewer) =
                autoMergeWithFileChange(file) { file.writeText(file.readText().replace("enabled: true", "enabled: false")) }

            assertEquals(1, reviewer.requests.size)
            val rejected = run.sink.all("config_reload_failed").single()
            assertContains(rejected.toString(), "auto_merge.enabled: true", message = "the loader rejects auto without the opt in")
            assertEquals(emptyList(), run.remote.merges, "nothing merges")
            assertTrue(run.sink.events.none { it.kind == PullRequestMerged.name })
            assertTrue(run.fellBack(), "an unapproved Waiting(approval) waits for a human")
            val reason = checkNotNull(run.sink.all(AutoMergeFallback.name).single()["reason"])
            assertContains(reason, "the workflow no longer allows an auto merge, its last change was rejected")
            assertContains(reason, "invalid_config")
            assertContains(run.comment, "the workflow no longer allows an auto merge, its last change was rejected")
        }

    @Test
    fun `round 3 of PR #75, a reload that fails preflight for another reason while the reviewer runs falls back`() =
        runTest {
            val file = diskWorkflow(enabled = true)
            val (run, _) = autoMergeWithFileChange(file) { diskWorkflow(prompt = "Work on {{ issue.nope }}.") }

            assertEquals(1, run.sink.all("config_reload_failed").size)
            assertEquals(emptyList(), run.remote.merges, "nothing merges")
            assertTrue(run.fellBack())
            val reason = checkNotNull(run.sink.all(AutoMergeFallback.name).single()["reason"])
            assertContains(reason, "its last change was rejected, template_render_error")
            assertContains(run.comment, "template_render_error")
        }

    @Test
    fun `condition no_lost_tests, a merge check without every deny guard falls back`() =
        runTest {
            val run = autoMerge(workflow(checks = listOf(CheckConfig.Command("./gradlew test"))))

            assertFellBack(run, "no_lost_tests", "the merge check's diff_policy does not run the deny guards deleted_tests")
        }

    @Test
    fun `condition protected_paths, a touched protected path falls back`() =
        runTest {
            val run = autoMerge(worktree = { verifiedWorktree(file = "scripts/release.sh", text = "echo\n") })

            assertFellBack(run, "protected_paths", "the diff touches scripts/release.sh (protected by scripts/)")
        }

    @Test
    fun `condition diff_size, a diff over the limit falls back`() =
        runTest {
            val run = autoMerge(workflow(autoMerge = AutoMergeConfig(enabled = true, maxDiffLines = 1)))

            assertFellBack(run, "diff_size", "the diff changes 2 lines, over the limit of 1")
        }

    @Test
    fun `condition diff_base_pinned, a diff_base_unpinned event on the delivered attempt falls back`() =
        runTest {
            val unpinned =
                RecordedEvent(
                    FIXED_NOW,
                    PROJECT,
                    "diff_base_unpinned",
                    attemptId = AttemptId("dead1"),
                    data =
                        mapOf(
                            "reason" to "x",
                        ),
                )
            val run = autoMerge(events = { sha -> verifiedEvents(clean(sha), listOf(unpinned)) })

            assertFellBack(run, "diff_base_pinned", "a verification of the attempt could not fetch the base")
        }

    @Test
    fun `events that cannot be read fail the conditions they prove`() =
        runTest {
            val run = autoMerge(events = { _ -> { Result.failure(IllegalStateException("store locked")) } })

            assertTrue(run.fellBack())
            assertContains(run.comment, "- verified: cannot read the events of the attempt: store locked")
            assertContains(run.comment, "- diff_base_pinned: cannot read the events of the attempt: store locked")
        }

    // The merge risk band.

    @Test
    fun `a medium band falls back with the band and each dimension's verdict and reason in the comment`() =
        runTest {
            val run = autoMerge(reviewer = reviewer(answer("impact" to "medium", "verification" to "partial")))

            assertTrue(run.fellBack())
            assertContains(run.comment, "Merge risk: band medium, score 1.53.")
            assertContains(run.comment, "- impact: medium, impact looks medium")
            assertContains(run.comment, "- verification: partial, verification looks partial")
            assertContains(run.comment, "- alignment: aligned, alignment looks aligned")
            assertEquals("medium", run.sink.all(AutoMergeFallback.name).single()["band"])
            assertEquals("medium", run.sink.all(MergeRiskAssessed.name).single()["band"])
        }

    @Test
    fun `a high band falls back`() =
        runTest {
            val run = autoMerge(reviewer = reviewer(answer("impact" to "high", "verification" to "weak", "alignment" to "misaligned")))

            assertTrue(run.fellBack())
            assertContains(run.comment, "Merge risk: band high")
        }

    @Test
    fun `a one way door scores 1_40, low, and still falls back since a dimension is at its worst`() =
        runTest {
            val run = autoMerge(reviewer = reviewer(answer("rollback_cost" to "one_way")))

            assertTrue(run.fellBack())
            assertContains(run.comment, "Merge risk: band low, score 1.40, at its worst verdict: rollback_cost.")
            assertContains(run.comment, "- rollback_cost: one_way")
        }

    @Test
    fun `a code floor wins over the reviewer's best verdict, source changed with no test changed cannot be strong`() =
        runTest {
            val run = autoMerge(worktree = { verifiedWorktree(test = false) })

            val assessed = run.sink.all(MergeRiskAssessed.name).single()
            assertEquals("partial", assessed["verification"], "the reviewer said strong")
            assertEquals("verification:partial", assessed["floors"])
            assertEquals("1.27", assessed["score"])
            assertEquals(1, run.remote.merges.size, "a floor alone can leave the band low")
        }

    @Test
    fun `a touched one way path floors rollback cost at one way, so the reviewer's two way loses and it falls back`() =
        runTest {
            val run =
                autoMerge(worktree = { verifiedWorktree(file = "db/migrations/0002_add.sql", text = "alter table t add c int;\n") })

            val assessed = run.sink.all(MergeRiskAssessed.name).single()
            assertEquals("one_way", assessed["rollback_cost"])
            assertEquals("medium", assessed["impact"], "a migration is a sensitive path too")
            assertTrue(run.fellBack())
            assertContains(run.comment, "- rollback_cost: one_way (floor one_way: touches one way paths db/migrations/0002_add.sql)")
        }

    @Test
    fun `a touched sensitive path floors impact, the reviewer's low loses`() =
        runTest {
            val run = autoMerge(worktree = { verifiedWorktree(file = "app/package.json", text = "{}\n") })

            val assessed = run.sink.all(MergeRiskAssessed.name).single()
            assertEquals("medium", assessed["impact"])
            assertContains(assessed["floors"].orEmpty(), "impact:medium")
        }

    // The reviewer failing closed.

    private fun assertReviewFellBack(
        run: Run,
        reason: String,
    ) {
        assertTrue(run.fellBack())
        assertContains(run.comment, "Merge risk: not assessed, $reason")
        assertContains(
            run.sink
                .all(AutoMergeFallback.name)
                .single()["reason"]
                .orEmpty(),
            reason,
        )
        assertTrue(run.sink.events.none { it.kind == MergeRiskAssessed.name })
    }

    @Test
    fun `a reviewer timeout falls back naming it`() =
        runTest {
            val run = autoMerge(reviewer = FakeReviewer(Result.failure(RiskReviewError.Timeout("no answer within 600000 ms"))))

            assertReviewFellBack(run, "the reviewer timed out: no answer within 600000 ms")
        }

    @Test
    fun `a reviewer crash falls back naming it`() =
        runTest {
            val run = autoMerge(reviewer = FakeReviewer(Result.failure(RiskReviewError.Crashed("claude exited with code 1"))))

            assertReviewFellBack(run, "the reviewer failed: claude exited with code 1")
        }

    @Test
    fun `a reviewer that throws falls back as a crash`() =
        runTest {
            val throwing = RiskReviewer { error("boom") }
            val run = autoMerge(reviewer = throwing)

            assertReviewFellBack(run, "the reviewer failed: boom")
        }

    @Test
    fun `an answer that is not valid JSON falls back naming it`() =
        runTest {
            val run = autoMerge(reviewer = reviewer("I think it is fine."))

            assertReviewFellBack(run, "the reviewer's answer is not valid JSON")
        }

    @Test
    fun `an answer missing a dimension falls back naming it`() =
        runTest {
            val text = answer().replace(Regex(""", "error_detection": \{[^}]*\}"""), "")
            val run = autoMerge(reviewer = reviewer(text))

            assertReviewFellBack(run, "the reviewer gave no verdict for error_detection")
        }

    @Test
    fun `a host with no reviewer falls back`() =
        runTest {
            assertReviewFellBack(autoMerge(reviewer = null), "this Loop has no risk reviewer")
        }

    @Test
    fun `a spend budget already used up runs no reviewer and falls back, the reviewer's spend counts in the ledger`() =
        runTest {
            val ledger =
                FakeLedger().apply {
                    previous +=
                        LedgerRow(
                            LedgerAttempt(
                                PROJECT,
                                null,
                                "id-t-1",
                                "t-1",
                                AttemptId("old"),
                                LedgerMeter.setupOf(workflow()),
                                FIXED_NOW,
                                FIXED_NOW,
                                "succeeded",
                                null,
                                1,
                                "todo",
                                null,
                                2.0,
                                null,
                                null,
                                null,
                            ),
                            1,
                            0,
                        )
                }
            val reviewer = reviewer()
            val run = autoMerge(workflow(budgets = BudgetsConfig(perIssueUsd = 1.0)), reviewer = reviewer, ledger = ledger)

            assertReviewFellBack(run, "the spend budget per_issue_usd is used up")
            assertTrue(reviewer.requests.isEmpty())
        }

    @Test
    fun `a ledger that cannot be read for the spend budgets runs no reviewer and falls back`() =
        runTest {
            val ledger = FakeLedger().apply { readFailure = IllegalStateException("database is locked") }
            val reviewer = reviewer()
            val run = autoMerge(workflow(budgets = BudgetsConfig(perDayTokens = 1_000)), reviewer = reviewer, ledger = ledger)

            assertReviewFellBack(run, "the ledger cannot be read for the spend budgets")
            assertTrue(reviewer.requests.isEmpty())
        }

    @Test
    fun `a per day budget that only the spend of an attempt still running crosses runs no reviewer and falls back`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1"), issue("t-2")))
            val remote = FakeRemote().apply { base = git(project, "rev-parse", "main") }
            val waiting = FakeWaitingIssues(autoWait(clean(verified)))
            val sink = FakeSink()
            val ledger = FakeLedger()
            val reviewer = reviewer()
            // t-2 reports $0.90 of the $1 day, warns, and holds its turn until the gate read the day's ledger rows.
            var armed = false
            val release = CompletableDeferred<Unit>()
            ledger.onRowsSince = { if (armed) release.complete(Unit) }
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.Usage(SESSION, TURN, 100, 0, 0, 0, totalCostUsd = 0.9))
                        release.await()
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val verifiedBy = verifiedEvents(clean(verified))
            // The gate reads the attempt's events before its budget check. It waits there until t-2 checked its budgets,
            // then another attempt's $0.20 row lands: $1.10 with t-2, but only $0.20 in the ledger.
            val events: suspend (AttemptId) -> Result<List<RecordedEvent>> = { attempt ->
                while (sink.events.none { it.kind == "budget_warning" && it.issueId == "id-t-2" }) delay(10)
                ledger.previous += LedgerRow(ledgerAttempt("other", "id-t-3", 0.2), 1, 0)
                armed = true
                verifiedBy(attempt)
            }
            val workflow = workflow(budgets = BudgetsConfig(perDayUsd = 1.0))
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow, reviewer, ledger, runner, events = events)

            repeat(2) {
                orchestrator.tick()
                testScheduler.advanceUntilIdle()
            }

            assertTrue(release.isCompleted, "the gate read the ledger while t-2 was running")
            assertTrue(sink.events.none { it.kind == "budget_reached" }, "t-2 never saw the day budget passed")
            assertTrue(reviewer.requests.isEmpty(), "no reviewer runs past the day budget, t-2's live spend included")
            assertEquals(emptyList(), remote.merges)
            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Approval, wait.reason)
            assertFalse(wait.approved)
            val reason = "the spend budget per_day_usd is used up"
            assertEquals(reason, sink.all(AutoMergeFallback.name).single()["reason"])
            assertContains(
                tracker.comments
                    .last { it.first == "id-t-1" }
                    .second.text,
                "Merge risk: not assessed, $reason",
            )
            assertTrue(sink.events.none { it.kind == MergeRiskAssessed.name })
        }

    /** A finished attempt's ledger row of [issueId] that spent [usd]. */
    private fun ledgerAttempt(
        id: String,
        issueId: String,
        usd: Double,
    ): LedgerAttempt =
        LedgerAttempt(
            PROJECT,
            null,
            issueId,
            issueId.removePrefix("id-"),
            AttemptId(id),
            LedgerMeter.setupOf(workflow()),
            FIXED_NOW,
            FIXED_NOW,
            "succeeded",
            null,
            1,
            "todo",
            null,
            usd,
            null,
            null,
            null,
        )

    // After a fallback.

    @Test
    fun `after a fallback an operator's approval merges with no gate`() =
        runTest {
            val run = autoMerge(prepare = { it.mergeable = Result.success(Mergeability(false, "dirty")) })
            assertTrue(run.fellBack())
            val wait =
                run.waiting.records.values
                    .single()
            val approved =
                wait.copy(
                    data =
                        wait.data + (WaitingIssue.APPROVED_BY to "soule") + (WaitingIssue.APPROVED_AT to FIXED_NOW.toString()),
                )
            run.waiting.records[PROJECT.value to "id-t-1"] = approved
            val orchestrator = orchestrator(run.tracker, run.remote, run.waiting, run.sink)

            repeat(2) {
                orchestrator.tick()
                testScheduler.advanceUntilIdle()
            }

            assertEquals(1, run.remote.merges.size)
            assertEquals(listOf("id-t-1" to "done"), run.tracker.moves)
        }

    @Test
    fun `a human policy merge never runs the gate`() =
        runTest {
            val reviewer = reviewer()
            val run =
                autoMerge(
                    workflow(policy = DeliveryPolicy.Human, autoMerge = AutoMergeConfig()),
                    reviewer = reviewer,
                    wait = { sha -> autoWait(clean(sha)).let { it.copy(data = it.data + (WaitingIssue.APPROVED_BY to "soule")) } },
                    prepare = { it.mergeable = Result.success(Mergeability(false, "dirty")) },
                )

            assertEquals(1, run.remote.merges.size)
            assertTrue(reviewer.requests.isEmpty())
            assertFalse(run.sink.events.any { it.kind == AutoMergeFallback.name })
        }

    @Test
    fun `an unreadable mergeability fails closed`() =
        runTest {
            val run = autoMerge(prepare = { it.mergeable = Result.failure(DeliveryError.Rejected("404 Not Found")) })

            assertCiFellBack(run, "cannot read whether pull request #7 is mergeable: 404 Not Found")
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
