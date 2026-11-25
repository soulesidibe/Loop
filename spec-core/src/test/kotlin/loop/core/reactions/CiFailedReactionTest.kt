package loop.core.reactions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.ActionsJob
import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.ActionsStep
import loop.api.AttemptId
import loop.api.DeliveryExecutor
import loop.api.MergeMethod
import loop.api.ParkReason
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.CiVerdict
import loop.core.delivery.CiWait
import loop.core.delivery.MergeCheck
import loop.core.events.CiFinished
import loop.core.events.CiWaiting
import loop.core.events.DeliveryFailed
import loop.core.events.IssueParked
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
import loop.core.events.RetryScheduled
import loop.core.log.KeyValueLog
import loop.core.orchestrator.FIXED_NOW
import loop.core.orchestrator.FakeHookRunner
import loop.core.orchestrator.FakeParkedIssues
import loop.core.orchestrator.FakeRunner
import loop.core.orchestrator.FakeSink
import loop.core.orchestrator.FakeTracker
import loop.core.orchestrator.FakeWaitingIssues
import loop.core.orchestrator.Orchestrator
import loop.core.orchestrator.SchedulerClock
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.core.orchestrator.issue
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.WorkspaceFingerprinter
import loop.core.verify.WorkspaceGit
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
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-35: the `ci_failed` reaction. A red verdict of the CI wait (m3-15) under `ci_failed: { action: send_to_agent }`
 * ends the CI wait with no `delivery_failed` and sends the issue back to its agent, `loop.retry_kind` = `reaction`, with
 * findings naming the failed runs, their URLs and the failed job and step names, on a `max_attempts` slot; the result is
 * verified again. Past `retries`, counted per issue in the run store, the red verdict keeps today's path. A run GitHub
 * refused to start, `not_started`, never triggers it. Every Actions answer is recorded, never a live run: the hand
 * written red run `actions-runs-red` with its jobs, and the run 43779352010 GitHub refused for billing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CiFailedReactionTest {
    private val root: Path = createTempDirectory("loop-ci-failed").toRealPath()
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

    /** The project repository on `main`, the worktree of `loop/t-1` with one verified commit V on it. Returns V. */
    private fun verifiedWorktree(): String {
        git(project, "init", "-q", "-b", "main")
        project.resolve("a.txt").writeText("one\n")
        git(project, "add", "a.txt")
        git(project, "commit", "-q", "-m", "one")
        git(project, "worktree", "add", "-q", "-b", "loop/t-1", workspace.toString())
        workspace.resolve("work.txt").writeText("work\n")
        git(workspace, "add", "work.txt")
        git(workspace, "commit", "-q", "-m", "work")
        return git(workspace, "rev-parse", "HEAD")
    }

    private val actions = MergeConfig(MergeMethod.Merge, CiMode.Actions, ciTimeoutMs = 600_000, ciNoRunGraceMs = 60_000)

    private val sendTwice = ReactionsConfig(ciFailed = Reaction(ReactionAction.SendToAgent, retries = 2))

    private fun workflow(
        reactions: ReactionsConfig = sendTwice,
        merge: MergeConfig = actions,
        maxAttempts: Int = 5,
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                    kinds =
                        KindsConfig(
                            KindConfig(
                                listOf(CheckConfig.Command("./gradlew test")),
                                delivery =
                                    DeliveryConfig(
                                        DeliveryPolicy.Human,
                                        PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", merge = merge),
                                        onDeliveredMoveTo = "done",
                                    ),
                            ),
                        ),
                    reactions = reactions,
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
            promptTemplate =
                "Work on {{ issue.identifier }}.{% if loop.retry_kind %} Retry kind: {{ loop.retry_kind }}.{% endif %}" +
                    "{% if loop.verify_findings %}\n{{ loop.verify_findings }}{% endif %}",
        )

    /** A GitHub like remote whose Actions listing answers [answers] in turn, the last one again once the others are used. */
    private class FakeRemote(
        base: String,
    ) : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<String>()
        val merges = mutableListOf<String>()
        val pulls = mutableMapOf<String, PullRequest>()
        val answers = ArrayDeque<Result<ActionsRuns>>()
        val listed = mutableListOf<String>()
        val jobs = mutableMapOf<Long, Result<ActionsJobs>>()
        val jobsRead = mutableListOf<Long>()
        var opened = 0
        val updated = mutableListOf<Long>()
        private val tip = base

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushes += sha
            pulls[branch]?.let { pulls[branch] = it.copy(headSha = sha) }
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(pulls[branch]?.copy(created = false))

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            opened++
            val pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = pushes.lastOrNull())
            pulls[branch] = pull
            return Result.success(pull)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> {
            updated += number
            return Result.success(pulls.values.single { it.number == number })
        }

        override suspend fun fetchBase(): Result<String> = Result.success(tip)

        override suspend fun baseTip(): Result<String> = Result.success(tip)

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            merges += sha
            val branch = pulls.entries.single { it.value.number == number }.key
            pulls[branch] = pulls.getValue(branch).copy(state = PullRequestState.Merged, headSha = sha)
            return Result.success("feedface".repeat(5))
        }

        override suspend fun actionsRuns(sha: String): Result<ActionsRuns> {
            listed += sha
            val answer = if (answers.size > 1) answers.removeFirst() else answers.firstOrNull()
            return answer ?: Result.success(ActionsRuns(0, emptyList()))
        }

        override suspend fun actionsJobs(runId: Long): Result<ActionsJobs> {
            jobsRead += runId
            return jobs[runId] ?: Result.failure(IllegalStateException("no jobs recorded for run $runId"))
        }
    }

    private fun approvedWait(fingerprint: String): WaitingIssue =
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
                    WaitKeys.CHECKS to "- check 0, ./gradlew test: passed with exit code 0 in 1.2s",
                    WaitKeys.JUDGMENT_CALLS to "0",
                    WaitingIssue.APPROVED_BY to "soule",
                    WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
                ),
        )

    private val passing = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 5) }

    /** Everything one test drives: the fakes, shared by the orchestrators of one test, as the store is across a restart. */
    private inner class Setup(
        val workflow: WorkflowFile = workflow(),
    ) {
        val verified = verifiedWorktree()
        val tracker = FakeTracker(listOf(issue("t-1")))
        val remote = FakeRemote(git(project, "rev-parse", "main"))
        val waiting = FakeWaitingIssues(approvedWait(MergeCheck.cleanFingerprint(verified)))
        val sink = FakeSink()
        val parked = FakeParkedIssues()
        val runner =
            FakeRunner().apply {
                onCall = { ctx ->
                    ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                }
            }

        val wait: WaitingIssue? get() = waiting.records.values.singleOrNull()

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }

        fun comments(): List<String> = tracker.comments.map { it.second.text }

        /** Approves the unapproved approval wait the last attempt left, as `loop approve` writes the store. */
        fun approve() {
            val unapproved = assertNotNull(wait, "an approval wait")
            assertEquals(WaitReason.Approval, unapproved.reason)
            waiting.records[PROJECT.value to "id-t-1"] =
                unapproved.copy(
                    data = unapproved.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()),
                )
        }

        /**
         * The orchestrator of one process. [reactionsBefore] reads the store's `reaction_triggered` events, here the
         * sink's, which the store keeps across a restart.
         */
        fun TestScope.orchestrator(reactionsBefore: (suspend () -> Result<List<RecordedEvent>>)? = null): Orchestrator {
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
                checkRunnerFor = { passing },
                waiting = waiting,
                fingerprinter = fingerprinter,
                deliveryFor = { remote },
                workspaceGit = workspaceGit,
                reactionsBefore = reactionsBefore ?: { Result.success(emptyList()) },
            )
        }
    }

    /** One tick and every job it started, after [after] milliseconds of the test clock. */
    private suspend fun TestScope.tick(
        orchestrator: Orchestrator,
        after: Long = 0,
    ) {
        if (after > 0) advanceTimeBy(after)
        orchestrator.tick()
        testScheduler.runCurrent()
    }

    /** From an approved wait: the delivery, the merge check and the CI wait, then the poll 30 seconds after the push. */
    private suspend fun TestScope.deliverToVerdict(orchestrator: Orchestrator) {
        tick(orchestrator, after = 1_000)
        tick(orchestrator, after = CiWait.POLL_FLOOR_MS)
    }

    /** The retry a reaction scheduled, run to its end: the attempt, its verification and its approval wait. */
    private suspend fun TestScope.runRetry(
        setup: Setup,
        orchestrator: Orchestrator,
    ) {
        val delay =
            setup
                .all(RetryScheduled.name)
                .last()
                .getValue("delay_ms")
                .toLong()
        tick(orchestrator, after = delay)
        testScheduler.advanceUntilIdle()
    }

    private fun recordedJson(name: String): JsonObject = Json.parseToJsonElement(RECORDED.resolve("$name.json").toFile().readText()).jsonObject

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    /** A recorded runs listing, read as the github tracker reads it. */
    private fun recordedRuns(name: String): ActionsRuns {
        val listing = recordedJson(name)
        val runs =
            listing.getValue("workflow_runs").jsonArray.map {
                val run = it.jsonObject
                ActionsRun(
                    id = run.getValue("id").jsonPrimitive.long,
                    name = checkNotNull(run.text("name")),
                    workflowId = run.getValue("workflow_id").jsonPrimitive.long,
                    event = checkNotNull(run.text("event")),
                    runNumber = run.getValue("run_number").jsonPrimitive.long,
                    runAttempt = run.getValue("run_attempt").jsonPrimitive.long,
                    status = checkNotNull(run.text("status")),
                    conclusion = run.text("conclusion"),
                    url = checkNotNull(run.text("html_url")),
                )
            }
        return ActionsRuns(listing.getValue("total_count").jsonPrimitive.long, runs)
    }

    /** A recorded jobs listing, read as the github tracker reads it, the steps with their names. */
    private fun recordedJobs(name: String): ActionsJobs {
        val listing = recordedJson(name)
        val jobs =
            listing.getValue("jobs").jsonArray.map {
                val job = it.jsonObject
                val steps = job.getValue("steps") as JsonArray
                ActionsJob(
                    id = job.getValue("id").jsonPrimitive.long,
                    name = checkNotNull(job.text("name")),
                    status = checkNotNull(job.text("status")),
                    conclusion = job.text("conclusion"),
                    steps = steps.size,
                    stepList = steps.map { step -> ActionsStep(checkNotNull(step.jsonObject.text("name")), step.jsonObject.text("conclusion")) },
                )
            }
        return ActionsJobs(listing.getValue("total_count").jsonPrimitive.long, jobs)
    }

    /** The hand written red run: `build` failed in job `test` at step `Run ./gradlew test`, `lint` passed. */
    private fun Setup.answerRed() {
        remote.answers += Result.success(recordedRuns("actions-runs-red"))
        remote.jobs[RED_RUN] = Result.success(recordedJobs("actions-run-$RED_RUN-jobs"))
    }

    @Test
    fun `a red verdict resumes the workspace with retry kind reaction and findings naming the failed runs, jobs and steps, then verifies again`() =
        runTest {
            val setup = Setup()
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)

            assertEquals(listOf(RED_RUN), setup.remote.jobsRead, "the jobs read of m4-07, no other request")
            assertEquals(mapOf("outcome" to "red", "runs" to "$RED_RUN,$GREEN_RUN"), setup.all(CiFinished.name).single())
            assertEquals(
                listOf(
                    mapOf(
                        "reaction" to "ci_failed",
                        "action" to "send_to_agent",
                        "issue" to "t-1",
                        "pull_request" to "7",
                        "retry" to "1",
                        "retries" to "2",
                    ),
                ),
                setup.all(ReactionTriggered.name),
            )
            assertTrue(setup.all(DeliveryFailed.name).isEmpty(), "the reaction replaces delivery_failed")
            assertNull(setup.wait, "the reaction ended the CI wait")
            val comment = setup.comments().last()
            assertContains(comment, "Loop did not merge pull request #7, ci: Actions runs did not pass")
            assertContains(comment, "The ci_failed reaction sends the issue back to its agent, reaction 1 of 2.")
            assertContains(comment, "- build (failure): https://github.com/octocat/Hello-World/actions/runs/$RED_RUN")
            assertEquals("ci", setup.all("issue_commented").last()["reason"])
            val retry = setup.all(RetryScheduled.name).single()
            assertEquals("reaction", retry["error"])
            assertEquals("1", retry["failures"], "the reaction takes a max_attempts slot")

            runRetry(setup, orchestrator)

            val (context, prompt) = setup.runner.calls.single()
            assertEquals(workspace.toString(), context.workspace.toString(), "the issue's workspace resumes")
            assertContains(prompt, "Work on t-1. Retry kind: reaction.\n")
            assertContains(prompt, "- build (failure): https://github.com/octocat/Hello-World/actions/runs/$RED_RUN")
            assertContains(prompt, "- build, job test (failure), failed step Run ./gradlew test (failure)")
            assertContains(prompt, "You cannot open the run URLs")
            assertFalse(prompt.contains("lint"), "a run that passed is not named")
            assertFalse(prompt.contains("assemble"), "a job that passed is not named")
            assertFalse(prompt.contains("ktlintCheck"), "a skipped step is not named")
            val again = assertNotNull(setup.wait, "the result was verified again and waits for approval")
            assertEquals(WaitReason.Approval, again.reason)
            assertFalse(again.approved)
            assertEquals(emptyList(), setup.remote.merges)
            assertEquals(PullRequestState.Open, setup.remote.pulls.values.single().state)
        }

    @Test
    fun `after retries reactions a red verdict gives delivery_failed, an unapproved approval and a comment saying the retries are spent`() =
        runTest {
            val setup = Setup()
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)
            runRetry(setup, orchestrator)
            setup.approve()
            deliverToVerdict(orchestrator)
            assertEquals(listOf("1", "2"), setup.all(ReactionTriggered.name).map { it["retry"] })
            runRetry(setup, orchestrator)
            assertEquals(2, setup.runner.calls.size)
            setup.approve()
            deliverToVerdict(orchestrator)

            assertEquals(2, setup.all(ReactionTriggered.name).size, "no third reaction")
            assertEquals(
                mapOf(
                    "reaction" to "ci_failed",
                    "action" to "send_to_agent",
                    "issue" to "t-1",
                    "pull_request" to "7",
                    "retry" to "2",
                    "retries" to "2",
                    "reason" to "retries_spent",
                ),
                setup.all(ReactionSkipped.name).single(),
            )
            assertEquals(3, setup.all(CiFinished.name).count { it["outcome"] == "red" })
            val failed = setup.all(DeliveryFailed.name).single()
            assertEquals("ci", failed["step"])
            val comment = setup.comments().last()
            assertContains(comment, "Loop did not merge pull request #7, ci: Actions runs did not pass")
            assertContains(comment, "The ci_failed reaction sent the issue back 2 times, its retries are spent.")
            assertContains(comment, "then approve again with loop approve")
            val wait = assertNotNull(setup.wait)
            assertEquals(WaitReason.Approval, wait.reason)
            assertFalse(wait.approved)
            assertEquals(2, setup.all(RetryScheduled.name).size, "no third retry")
            testScheduler.advanceUntilIdle()
            tick(orchestrator, after = 60_000)
            assertEquals(2, setup.runner.calls.size, "no dispatch past the retries")
        }

    @Test
    fun `the count of reactions survives a restart, read from the run store`() =
        runTest {
            val setup = Setup(workflow(ReactionsConfig(ciFailed = Reaction(ReactionAction.SendToAgent, retries = 1))))
            setup.answerRed()
            val first = with(setup) { orchestrator() }
            deliverToVerdict(first)
            runRetry(setup, first)
            assertEquals(1, setup.all(ReactionTriggered.name).size)
            setup.approve()

            // A new process: nothing in memory, the store's events and waits as the first one left them.
            val stored = setup.sink.events.toList()
            val second = with(setup) { orchestrator(reactionsBefore = { Result.success(stored) }) }
            deliverToVerdict(second)

            assertEquals(1, setup.all(ReactionTriggered.name).size, "no second reaction after the restart")
            assertEquals("retries_spent", setup.all(ReactionSkipped.name).single()["reason"])
            assertEquals("ci", setup.all(DeliveryFailed.name).single()["step"])
            assertContains(setup.comments().last(), "The ci_failed reaction sent the issue back 1 time, its retries are spent.")
            assertFalse(assertNotNull(setup.wait).approved)
            assertEquals(1, setup.runner.calls.size)
        }

    @Test
    fun `a store that cannot be read keeps today's path`() =
        runTest {
            val setup = Setup()
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator(reactionsBefore = { Result.failure(IllegalStateException("database is locked")) }) }

            deliverToVerdict(orchestrator)

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals("ci", setup.all(DeliveryFailed.name).single()["step"])
            assertContains(setup.comments().last(), "The ci_failed reaction did not send the issue back, Loop could not read its count")
            assertFalse(assertNotNull(setup.wait).approved)
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
        }

    @Test
    fun `the recorded run 43779352010 GitHub refused to start ends not_started and never triggers ci_failed`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += Result.success(recordedRuns("actions-runs-43779352010"))
            setup.remote.jobs[REFUSED_RUN] = Result.success(recordedJobs("actions-run-43779352010-jobs"))
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)

            assertEquals(mapOf("outcome" to "not_started", "runs" to "$REFUSED_RUN"), setup.all(CiFinished.name).single())
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertEquals("ci", setup.all(DeliveryFailed.name).single()["step"])
            val comment = setup.comments().last()
            assertContains(comment, "GitHub did not start the Actions runs")
            assertFalse(comment.contains("ci_failed"), comment)
            assertFalse(assertNotNull(setup.wait).approved)
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
            testScheduler.advanceUntilIdle()
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `without the reaction a red verdict keeps today's path unchanged`() =
        runTest {
            val setup = Setup(workflow(ReactionsConfig()))
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)

            assertEquals("red", setup.all(CiFinished.name).single()["outcome"])
            val failed = setup.all(DeliveryFailed.name).single()
            assertEquals("ci", failed["step"])
            val run = recordedRuns("actions-runs-red").runs.first()
            val verdict = CiVerdict.Red(emptyList(), listOf(run))
            assertEquals(
                CiWait.failedComment("7", "Actions runs did not pass: build (failure)", CiWait.failedRunLines(verdict.failed) { it }),
                setup.comments().last(),
            )
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertFalse(assertNotNull(setup.wait).approved)
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
            testScheduler.advanceUntilIdle()
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `notify_human records reaction_skipped and keeps today's path`() =
        runTest {
            val setup = Setup(workflow(ReactionsConfig(ciFailed = Reaction(ReactionAction.NotifyHuman))))
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)

            assertEquals("notify_human", setup.all(ReactionSkipped.name).single()["reason"])
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals("ci", setup.all(DeliveryFailed.name).single()["step"])
            assertFalse(assertNotNull(setup.wait).approved)
        }

    @Test
    fun `a reaction attempt takes a max_attempts slot, the reaction that takes the last one parks the issue`() =
        runTest {
            val setup = Setup(workflow(maxAttempts = 1))
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)
            testScheduler.advanceUntilIdle()

            assertEquals(1, setup.all(ReactionTriggered.name).size)
            val parked = setup.all(IssueParked.name).single()
            assertEquals(ParkReason.MaxAttempts.wire, parked["reason"])
            assertEquals("reaction", parked["error"])
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `a pull request a human merged during the CI wait is never sent back`() =
        runTest {
            val setup = Setup()
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }
            tick(orchestrator, after = 1_000)
            val (branch, pull) = setup.remote.pulls.entries.single()
            setup.remote.pulls[branch] = pull.copy(state = PullRequestState.Merged, headSha = setup.verified)

            tick(orchestrator, after = CiWait.POLL_FLOOR_MS)
            testScheduler.advanceUntilIdle()

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
            assertEquals(listOf("id-t-1" to "done"), setup.tracker.moves, "delivered as merged")
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `under ci none no Actions run is read and the reaction never fires`() =
        runTest {
            val setup = Setup(workflow(merge = MergeConfig(MergeMethod.Merge, CiMode.None)))
            setup.answerRed()
            val orchestrator = with(setup) { orchestrator() }

            deliverToVerdict(orchestrator)

            assertEquals(emptyList(), setup.remote.listed)
            assertTrue(setup.all(CiWaiting.name).isEmpty())
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertEquals(listOf(setup.verified), setup.remote.merges)
        }

    @Test
    fun `the findings name each failed run, its failed jobs and steps, and a run whose jobs were not read`() {
        val red = recordedRuns("actions-runs-red").runs.first()
        val unread = red.copy(id = 99, name = "docs", url = "https://github.com/acme/widgets/actions/runs/99")
        val findings =
            CiFailedReaction.findings(
                number = "7",
                head = "abc123",
                reason = "Actions runs did not pass: build (failure), docs (failure)",
                failed = listOf(red, unread),
                jobs = mapOf(RED_RUN to recordedJobs("actions-run-$RED_RUN-jobs")),
                redact = { it.replace("abc123", "[redacted]") },
            )

        assertEquals(
            """
            |The Actions runs of pull request #7 failed on its merged head [redacted]: Actions runs did not pass: build (failure), docs (failure).
            |
            |Failed runs:
            |- build (failure): https://github.com/octocat/Hello-World/actions/runs/$RED_RUN
            |- docs (failure): https://github.com/acme/widgets/actions/runs/99
            |
            |Failed jobs and steps:
            |- build, job test (failure), failed step Run ./gradlew test (failure)
            |- docs, its jobs were not read
            |
            |You cannot open the run URLs or read the CI logs. Find the cause from the job and step names, reproduce it in the workspace, fix it and commit.
            """.trimMargin(),
            findings,
        )
    }

    private companion object {
        val PROJECT = ProjectId("p1")

        /** The github tracker's recorded answers, read in place; Gradle runs spec-core's tests in its project folder. */
        val RECORDED: Path = Path.of("../plugins/tracker-github/src/test/resources/github/recorded").toAbsolutePath().normalize()

        /** The failed run of the hand written `actions-runs-red`, and its run that passed. */
        const val RED_RUN = 30433700L
        const val GREEN_RUN = 30433701L

        /** The run GitHub refused for billing on 2026-11-16, recorded in m4-00 (bl-98). */
        const val REFUSED_RUN = 43779352010L
    }
}
