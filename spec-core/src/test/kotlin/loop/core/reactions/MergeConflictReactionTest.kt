package loop.core.reactions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.Mergeability
import loop.api.ParkReason
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.MergeCheck
import loop.core.events.BranchPushed
import loop.core.events.IssueParked
import loop.core.events.ReactionKeys
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
import loop.core.events.RetryScheduled
import loop.core.log.KeyValueLog
import loop.core.orchestrator.FIXED_NOW
import loop.core.orchestrator.FakeHookRunner
import loop.core.orchestrator.FakeParkedIssues
import loop.core.orchestrator.FakeRetryStates
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
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
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
 * m4-34: the `merge_conflict` reaction. An unapproved `Waiting(approval)` that holds an open pull request, as after a
 * `delivery_failed` that left it open, is polled at most once per tick and every 30 seconds; `dirty` ends the wait and
 * sends the issue back to its agent with the conflict findings, `loop.retry_kind` = `reaction`, on a `max_attempts`
 * slot, and the result is verified again. The worktree is a real temporary repository; the pull request answers are
 * the hand written GitHub bodies under the github tracker's recorded test resources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergeConflictReactionTest {
    private val root: Path = createTempDirectory("loop-reactions").toRealPath()
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

    /** The project repository on `main`, the worktree of `loop/t-1` with one verified commit on it. Returns it. */
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

    private fun workflow(
        reactions: ReactionsConfig = ReactionsConfig(mergeConflict = Reaction(ReactionAction.SendToAgent)),
        maxAttempts: Int = 3,
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
                                        PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN"),
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

    /** A GitHub like remote whose mergeability reads answer [answers] in turn, the last one again once the others are used. */
    private class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val answers = ArrayDeque<Result<Mergeability>>()
        val reads = mutableListOf<Long>()
        var tip: Result<String> = Result.success(BASE_TIP)

        /** bl-224: runs during each mergeability read, as a `loop approve` or a `loop send-back` committed meanwhile. */
        var duringRead: suspend () -> Unit = {}

        override suspend fun mergeability(number: Long): Result<Mergeability> {
            reads += number
            duringRead()
            return if (answers.size > 1) answers.removeFirst() else answers.first()
        }

        override suspend fun baseTip(): Result<String> = tip

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(null)

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.failure(DeliveryError.Rejected("no pull request is opened in these tests"))

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.failure(DeliveryError.Rejected("no pull request is updated in these tests"))
    }

    /** The mergeability of a recorded pull request body, read as the github tracker reads `mergeable` and `mergeable_state`. */
    private fun recorded(name: String): Result<Mergeability> {
        val body = Json.parseToJsonElement(RECORDED.resolve("$name.json").toFile().readText()).jsonObject
        val mergeable = (body["mergeable"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return Result.success(Mergeability(mergeable, body["mergeable_state"]?.jsonPrimitive?.contentOrNull))
    }

    private val dirty get() = recorded("pull-request-7-dirty")
    private val unknown get() = recorded("pull-request-7-unknown")

    /** The unapproved approval wait a `delivery_failed` leaves, holding the open pull request #7, verified on [head]. */
    private fun fallbackWait(head: String): WaitingIssue =
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
                    WaitKeys.FINGERPRINT to MergeCheck.cleanFingerprint(head),
                    WaitKeys.CHECKS to "- check 0, ./gradlew test: passed with exit code 0 in 1.2s",
                    WaitKeys.JUDGMENT_CALLS to "0",
                    WaitKeys.PULL_REQUEST to "https://github.com/acme/widgets/pull/7",
                ),
        )

    private val passing = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 5) }

    /** Everything one test drives: the fakes and the orchestrator. */
    private inner class Setup(
        val workflow: WorkflowFile = workflow(),
    ) {
        val head = verifiedWorktree()
        val tracker = FakeTracker(listOf(issue("t-1")))
        val remote = FakeRemote()
        val waiting = FakeWaitingIssues(fallbackWait(head))
        val sink = FakeSink()
        val parked = FakeParkedIssues()
        val retryStates = FakeRetryStates()
        val runner =
            FakeRunner().apply {
                onCall = { ctx ->
                    ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                }
            }

        val wait: WaitingIssue? get() = waiting.records.values.singleOrNull()

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }

        fun TestScope.orchestrator(): Orchestrator {
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
                retryStates = retryStates,
                // bl-224: the run store's events as a new process reads them back.
                reactionsBefore = { Result.success(sink.events.filter { it.kind == ReactionTriggered.name || it.kind == ReactionSkipped.name }) },
                retryEventsBefore = { Result.success(sink.events.filter { it.kind in ReactionMarks.KINDS }) },
            )
        }

        /** The stored failure counter of the issue, zero when nothing is stored. */
        val failures: Int get() = retryStates.records[PROJECT.value to "id-t-1"]?.failures ?: 0
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

    @Test
    fun `a dirty waiting pull request ends the wait, resumes the workspace with retry kind reaction and the findings, verifies again`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += dirty
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertEquals(
                listOf(mapOf("reaction" to "merge_conflict", "action" to "send_to_agent", "issue" to "t-1", "pull_request" to "7")),
                setup.all(ReactionTriggered.name).map { it - ReactionKeys.FINDINGS },
            )
            assertNull(setup.wait, "the reaction ended the wait")
            val retry = setup.all(RetryScheduled.name).single()
            assertEquals("reaction", retry["error"])
            assertEquals("1", retry["failures"], "the reaction takes a max_attempts slot")

            tick(orchestrator, after = retry.getValue("delay_ms").toLong())
            testScheduler.advanceUntilIdle()

            val findings =
                MergeCheck.reactionConflictFindings(
                    "main",
                    BASE_TIP,
                    "GitHub reports pull request #7 as dirty, it conflicts with main",
                )
            assertEquals(findings, setup.all(ReactionTriggered.name).single()[ReactionKeys.FINDINGS], "bl-224: the findings are stored")
            val (context, prompt) = setup.runner.calls.single()
            assertEquals("Work on t-1. Retry kind: reaction.\n$findings", prompt)
            assertEquals(workspace.toString(), context.workspace.toString(), "the issue's workspace resumes")
            val again = assertNotNull(setup.wait, "the result was verified again and waits for approval")
            assertEquals(WaitReason.Approval, again.reason)
            assertEquals(listOf(7L), setup.remote.reads)
        }

    @Test
    fun `a reaction attempt takes a max_attempts slot and the last one parks with max_attempts`() =
        runTest {
            val setup = Setup(workflow(maxAttempts = 2))
            setup.remote.answers += dirty
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            val delay =
                setup
                    .all(RetryScheduled.name)
                    .single()
                    .getValue("delay_ms")
                    .toLong()
            tick(orchestrator, after = delay)
            testScheduler.advanceUntilIdle()
            assertEquals(1, setup.runner.calls.size)
            val verified = assertNotNull(setup.wait)
            assertEquals(WaitReason.Approval, verified.reason)
            assertNull(verified.data[WaitKeys.PULL_REQUEST], "a verified result waits for approval before its delivery")

            // Its next delivery falls back, `delivery_failed`, to an unapproved wait that holds pull request #7 again, and
            // the pull request still conflicts: the approval exit of the reaction attempt kept the counter.
            setup.waiting.records[PROJECT.value to "id-t-1"] =
                verified.copy(data = verified.data + (WaitKeys.PULL_REQUEST to "https://github.com/acme/widgets/pull/7"))
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            testScheduler.advanceUntilIdle()

            assertEquals(2, setup.all(ReactionTriggered.name).size)
            val parkedEvent = setup.all(IssueParked.name).single()
            assertEquals(ParkReason.MaxAttempts.wire, parkedEvent["reason"])
            assertEquals("2", parkedEvent["failures"])
            assertEquals("reaction", parkedEvent["error"])
            assertEquals(
                ParkReason.MaxAttempts,
                setup.parked.records.values
                    .single()
                    .reason,
            )
            assertEquals(1, setup.runner.calls.size, "the parked issue is not dispatched again")
            assertEquals(1, setup.all(RetryScheduled.name).size)
        }

    /** Runs the last retry the reaction scheduled, to its verified result, which waits for approval again. */
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

    /** The findings of the reaction to pull request #7 conflicting with main at [BASE_TIP]. */
    private val reactionFindings =
        MergeCheck.reactionConflictFindings("main", BASE_TIP, "GitHub reports pull request #7 as dirty, it conflicts with main")

    @Test
    fun `after a restart the reaction attempt's approval exit keeps the counter and the next reaction parks on the last slot`() =
        runTest {
            val setup = Setup(workflow(maxAttempts = 2))
            setup.remote.answers += dirty
            tick(with(setup) { orchestrator() })
            assertEquals(1, setup.failures, "the reaction takes a max_attempts slot")

            // A new process: nothing in memory, the store's retry states, waits and events as the first one left them.
            val second = with(setup) { orchestrator() }
            runRetry(setup, second)
            assertEquals(1, setup.runner.calls.size)
            val verified = assertNotNull(setup.wait)
            assertEquals(WaitReason.Approval, verified.reason)
            assertEquals(1, setup.failures, "the approval exit of the reaction attempt kept the counter across the restart")

            setup.waiting.records[PROJECT.value to "id-t-1"] =
                verified.copy(data = verified.data + (WaitKeys.PULL_REQUEST to "https://github.com/acme/widgets/pull/7"))
            tick(second, after = Reactions.POLL_FLOOR_MS)
            testScheduler.advanceUntilIdle()

            val parkedEvent = setup.all(IssueParked.name).single()
            assertEquals(ParkReason.MaxAttempts.wire, parkedEvent["reason"])
            assertEquals("2", parkedEvent["failures"])
            assertEquals(1, setup.all(RetryScheduled.name).count { it["restored"] != "true" }, "the second reaction parked")
            assertEquals(1, setup.runner.calls.size)
        }

    @Test
    fun `a restart between the reaction and its retry dispatch still renders the findings`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += dirty
            tick(with(setup) { orchestrator() })

            val second = with(setup) { orchestrator() }
            runRetry(setup, second)

            val (_, prompt) = setup.runner.calls.single()
            assertEquals("Work on t-1. Retry kind: reaction.\n$reactionFindings", prompt)
        }

    @Test
    fun `a reaction on an unapproved wait does not tell the agent its result was approved`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += dirty
            val orchestrator = with(setup) { orchestrator() }
            tick(orchestrator)
            runRetry(setup, orchestrator)

            val (_, prompt) = setup.runner.calls.single()
            assertContains(prompt, "conflicts with main at $BASE_TIP")
            assertFalse("approved" in prompt, prompt)
        }

    @Test
    fun `a loop approve committed while the reaction reads GitHub keeps the wait, schedules no retry, and is delivered`() =
        runTest {
            val setup = Setup()
            // Dirty at the reaction's read, then unknown: the delivery that falls back here leaves a wait no reaction acts on.
            setup.remote.answers += listOf(dirty, unknown)
            setup.remote.duringRead = { setup.waiting.approve(PROJECT, "id-t-1", "alice", FIXED_NOW) }
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals(ReactionSkipped.HUMAN_DECIDED, setup.all(ReactionSkipped.name).single()["reason"])
            assertTrue(setup.all(RetryScheduled.name).isEmpty(), "no retry")
            assertEquals(0, setup.failures)
            setup.remote.duringRead = {}
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            testScheduler.advanceUntilIdle()
            assertTrue(setup.runner.calls.isEmpty(), "the agent is not retried")
            assertEquals(1, setup.all(BranchPushed.name).size, "the approval is delivered: the branch is pushed for its pull request")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
        }

    @Test
    fun `a loop send-back committed while the reaction reads GitHub schedules no retry and dispatches with the human's reason`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += dirty
            setup.remote.duringRead = {
                setup.waiting.sendBack(PROJECT, "id-t-1", "alice", "Rename the helper before anything else.", FIXED_NOW)
            }
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            testScheduler.advanceUntilIdle()

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals(ReactionSkipped.HUMAN_DECIDED, setup.all(ReactionSkipped.name).single()["reason"])
            assertTrue(setup.all(RetryScheduled.name).none { it["error"] == "reaction" }, "no reaction retry")
            assertEquals(0, setup.failures, "a send back takes no slot")
            tick(orchestrator, after = 1_000)
            testScheduler.advanceUntilIdle()
            val (_, prompt) = setup.runner.calls.single()
            assertContains(prompt, "Retry kind: sent_back.")
            assertContains(prompt, "Rename the helper before anything else.")
            assertFalse("conflicts with main" in prompt, "the reaction's findings are not sent")
        }

    @Test
    fun `unknown, a null mergeable, an unreachable API and a rate limit hold keep the wait and record nothing, one poll per 30 seconds`() =
        runTest {
            val setup = Setup()
            setup.remote.answers +=
                listOf(
                    unknown,
                    Result.success(Mergeability(mergeable = null, state = "dirty")),
                    Result.failure(DeliveryError.Unavailable("Cannot read pull request #7 on acme/widgets: GitHub answered 502")),
                    Result.failure(DeliveryError.Unavailable("Cannot read pull request #7 on acme/widgets: rate limited, held")),
                    unknown,
                )
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)
            repeat(3) { tick(orchestrator, after = 9_000) }
            assertEquals(1, setup.remote.reads.size, "one poll at most every 30 seconds, whatever the ticks")

            repeat(3) { tick(orchestrator, after = Reactions.POLL_FLOOR_MS) }
            assertEquals(4, setup.remote.reads.size)

            assertEquals(before, setup.waiting.records, "the wait is kept as it was")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `a failed base read keeps the wait and records nothing`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += dirty
            setup.remote.tip = Result.failure(DeliveryError.Unavailable("GitHub answered 502"))
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertNotNull(setup.wait)
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
        }

    @Test
    fun `with no reactions key a conflicting waiting pull request is left as today`() =
        runTest {
            val setup = Setup(workflow(reactions = ReactionsConfig()))
            setup.remote.answers += dirty
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            testScheduler.advanceUntilIdle()

            assertTrue(setup.remote.reads.isEmpty(), "nothing is read without a reaction")
            assertEquals(before, setup.waiting.records)
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `notify_human records reaction_skipped once per wait and keeps the wait`() =
        runTest {
            val setup = Setup(workflow(reactions = ReactionsConfig(mergeConflict = Reaction(ReactionAction.NotifyHuman))))
            setup.remote.answers += dirty
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            assertEquals(2, setup.remote.reads.size)
            assertEquals(
                listOf(
                    mapOf(
                        "reaction" to "merge_conflict",
                        "action" to "notify_human",
                        "issue" to "t-1",
                        "pull_request" to "7",
                        "reason" to "notify_human",
                    ),
                ),
                setup.all(ReactionSkipped.name),
            )
            assertNotNull(setup.wait)
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `an approved wait, a sent back wait and a wait with no pull request are not watched`() {
        val head = "0123456789abcdef0123456789abcdef01234567"
        val base = Wait.of(fallbackWait(head)) as Wait.Approval
        val active = listOf(issue("t-1"))

        assertEquals(7L, Reactions.watched(listOf(base), active, emptySet()).single().number)
        assertTrue(Reactions.watched(listOf(base), active, setOf("id-t-1")).isEmpty(), "an issue that runs an attempt")
        assertTrue(Reactions.watched(listOf(base), emptyList(), emptySet()).isEmpty(), "an issue not read active")
        val approved =
            Wait.of(
                fallbackWait(head).let { it.copy(data = it.data + (WaitingIssue.APPROVED_AT to FIXED_NOW.toString())) },
            ) as Wait.Approval
        assertTrue(Reactions.watched(listOf(approved), active, emptySet()).isEmpty(), "an approved wait")
        val sentBack =
            Wait.of(
                fallbackWait(head).let { it.copy(data = it.data + (WaitingIssue.SENT_BACK_AT to FIXED_NOW.toString())) },
            ) as Wait.Approval
        assertTrue(Reactions.watched(listOf(sentBack), active, emptySet()).isEmpty(), "a sent back wait")
        val noPull =
            Wait.of(
                fallbackWait(head).let { it.copy(data = it.data - WaitKeys.PULL_REQUEST) },
            ) as Wait.Approval
        assertTrue(Reactions.watched(listOf(noPull), active, emptySet()).isEmpty(), "a wait with no pull request")
        assertTrue(Reactions.conflicts(Mergeability(false, "dirty")))
        assertTrue(!Reactions.conflicts(Mergeability(null, "dirty")))
        assertTrue(!Reactions.conflicts(Mergeability(true, "clean")))
    }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val BASE_TIP = "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d"

        /** The github tracker's recorded answers, read in place; Gradle runs spec-core's tests in its project folder. */
        val RECORDED: Path = Path.of("../plugins/tracker-github/src/test/resources/github/recorded").toAbsolutePath().normalize()
    }
}
