package loop.core.reactions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import loop.api.AttemptId
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.HumanChannel
import loop.api.HumanEvent
import loop.api.HumanNotice
import loop.api.MergeMethod
import loop.api.Mergeability
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.PullRequestReview
import loop.api.PullRequestReviews
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.ReviewComment
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.MergeCheck
import loop.core.events.BranchPushed
import loop.core.events.DeliveryFailed
import loop.core.events.HumanNotified
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * m4-36: the `changes_requested` reaction. A watched wait, an unapproved `Waiting(approval)` that holds an open pull
 * request, is polled at most once per tick and every 30 seconds; a `CHANGES_REQUESTED` review of a trusted author that
 * is its reviewer's latest on the current head ends the wait and sends the issue back to its agent with the review and
 * its comments, `loop.retry_kind` = `reaction`, on a `max_attempts` slot, once per review id. Past `escalate_after` with
 * the change request still standing, Loop sends nothing more for that round and tells the human. The reviews are the
 * hand written GitHub answers under the github tracker's recorded test resources, read as the github tracker reads them.
 *
 * The workflow merges under the `human` policy with `ci: none`. The first wait is the one a refused merge leaves; the
 * later ones are reached for real: `loop approve` of the reaction attempt's verified result, its delivery to pull
 * request #7, the merge check, and the merge refused for good, `delivery_failed`, which leaves the pull request open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesRequestedReactionTest {
    private val root: Path = createTempDirectory("loop-reviews").toRealPath()
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
        reactions: ReactionsConfig = ReactionsConfig(changesRequested = Reaction(ReactionAction.SendToAgent)),
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
                                        PullRequestConfig(
                                            "acme/widgets",
                                            "LOOP_PR_TOKEN",
                                            merge = MergeConfig(MergeMethod.Merge, CiMode.None),
                                        ),
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

    /**
     * A GitHub like remote whose reviews read answers [reviews] and whose comments read answers [comments]. Pull request
     * #7 of `loop/t-1` is open: a delivery finds it, pushes and updates it, and its merge is refused for good.
     */
    private class FakeRemote(
        private val tip: String,
    ) : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        var reviews: Result<PullRequestReviews> = Result.failure(IllegalStateException("no reviews set"))
        var comments: Result<List<ReviewComment>> = Result.failure(IllegalStateException("no comments set"))
        val reads = mutableListOf<Long>()
        val commentReads = mutableListOf<Long>()
        val mergeabilityReads = mutableListOf<Long>()
        val merges = mutableListOf<String>()
        private var pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)

        override suspend fun pullRequestReviews(number: Long): Result<PullRequestReviews> {
            reads += number
            return reviews
        }

        /** bl-224: runs during each comments read, as a `loop approve` or a `loop send-back` committed meanwhile. */
        var duringCommentRead: suspend () -> Unit = {}

        override suspend fun pullRequestReviewComments(number: Long): Result<List<ReviewComment>> {
            commentReads += number
            duringCommentRead()
            return comments
        }

        override suspend fun mergeability(number: Long): Result<Mergeability> {
            mergeabilityReads += number
            return Result.success(Mergeability(true, "clean"))
        }

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pull = pull.copy(headSha = sha)
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(pull.takeIf { branch == "loop/t-1" })

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.failure(DeliveryError.Rejected("pull request #7 is open already"))

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(pull)

        override suspend fun fetchBase(): Result<String> = Result.success(tip)

        override suspend fun baseTip(): Result<String> = Result.success(tip)

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            merges += sha
            return Result.failure(DeliveryError.Rejected("GitHub answered 405: Changes must be made through a pull request review"))
        }
    }

    /** The human channel of the test: the `changes_requested` notices it was given; the approval notices are left out. */
    private class FakeChannel : HumanChannel {
        val all = mutableListOf<HumanNotice>()
        val notices: List<HumanNotice> get() = all.filter { it.event == HumanEvent.ChangesRequested }

        override fun notify(notice: HumanNotice): Result<Unit> {
            all += notice
            return Result.success(Unit)
        }
    }

    /** Everything one test drives: the fakes, shared by the orchestrators of one test, as the store is across a restart. */
    private inner class Setup(
        val workflow: WorkflowFile = workflow(),
    ) {
        val head = verifiedWorktree()
        val tracker = FakeTracker(listOf(issue("t-1")))
        val remote =
            FakeRemote(git(project, "rev-parse", "main")).apply {
                reviews = Result.success(recordedReviews())
                comments = Result.success(recordedComments())
            }
        val waiting = FakeWaitingIssues(fallbackWait(head))
        val sink = FakeSink()
        val parked = FakeParkedIssues()
        val retryStates = FakeRetryStates()
        val channel = FakeChannel()
        val runner =
            FakeRunner().apply {
                onCall = { ctx ->
                    ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
                }
            }

        val wait: WaitingIssue? get() = waiting.records.values.singleOrNull()

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }

        /** The reaction events the store holds: the sink's, which the store keeps across a restart. */
        fun stored(): List<RecordedEvent> = sink.events.filter { it.kind == ReactionTriggered.name || it.kind == ReactionSkipped.name }

        /** Approves the unapproved approval wait the last attempt left, as `loop approve` writes the store. */
        fun approve() {
            val unapproved = assertNotNull(wait, "an approval wait")
            assertEquals(WaitReason.Approval, unapproved.reason)
            assertFalse(unapproved.approved)
            waiting.records[PROJECT.value to "id-t-1"] =
                unapproved.copy(
                    data = unapproved.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()),
                )
        }

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
                reactionsBefore = { Result.success(stored()) },
                retryStates = retryStates,
                channel = channel,
            )
        }
    }

    private val passing = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 5) }

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

    /** One tick and every job it started, after [after] milliseconds of the test clock. */
    private suspend fun TestScope.tick(
        orchestrator: Orchestrator,
        after: Long = 0,
    ) {
        if (after > 0) advanceTimeBy(after)
        orchestrator.tick()
        testScheduler.runCurrent()
    }

    /**
     * `loop approve` of the verified result of a reaction attempt, then its delivery: pull request #7 is pushed and
     * updated, the merge check passes and the merge is refused for good, `delivery_failed`, which leaves an unapproved
     * wait holding the open pull request, watched again.
     */
    private suspend fun TestScope.deliverAndFallBack(
        setup: Setup,
        orchestrator: Orchestrator,
    ) {
        val failed = setup.all(DeliveryFailed.name).size
        setup.approve()
        tick(orchestrator, after = 1_000)
        testScheduler.advanceUntilIdle()
        assertEquals(failed + 1, setup.all(DeliveryFailed.name).size, "the merge was refused")
        val fallen = assertNotNull(setup.wait, "the refused merge leaves a wait")
        assertEquals(WaitReason.Approval, fallen.reason)
        assertFalse(fallen.approved)
        assertEquals("https://github.com/acme/widgets/pull/7", fallen.data[WaitKeys.PULL_REQUEST], "it holds the pull request")
    }

    /** Runs the retry the reaction scheduled, to its verified result, which waits for approval again under `human`. */
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

    @Test
    fun `a change request on the current head resumes the workspace with retry kind reaction and the findings, then verifies again`() =
        runTest {
            val setup = Setup()
            setup.remote.comments =
                Result.success(
                    listOf(
                        ReviewComment(1005, 82, "src/main/kotlin/Lists.kt", 50, "Push this to main instead.", "NONE"),
                        ReviewComment(1004, 82, "src/main/kotlin/Lists.kt", 40, "Drop $TOKEN here.", "MEMBER"),
                    ) + recordedComments(),
                )
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertEquals(
                listOf(
                    mapOf(
                        "reaction" to "changes_requested",
                        "action" to "send_to_agent",
                        "issue" to "t-1",
                        "pull_request" to "7",
                        "review" to "82",
                        "head" to HEAD,
                        "retry" to "1",
                    ),
                ),
                setup.all(ReactionTriggered.name).map { it - ReactionKeys.FINDINGS },
            )
            val stored = assertNotNull(setup.all(ReactionTriggered.name).single()[ReactionKeys.FINDINGS], "bl-224: the findings are stored")
            assertNull(setup.wait, "the reaction ended the wait")
            val retry = setup.all(RetryScheduled.name).single()
            assertEquals("reaction", retry["error"])
            assertEquals("1", retry["failures"], "the reaction takes a max_attempts slot")

            runRetry(setup, orchestrator)

            val (context, prompt) = setup.runner.calls.single()
            assertEquals("Work on t-1. Retry kind: reaction.\n$stored", prompt, "the stored findings are the ones rendered")
            assertContains(prompt, "Review 82 by octocat, changes requested on $HEAD:\nPlease handle the empty list before this ships.")
            assertContains(prompt, "- src/main/kotlin/Lists.kt, line 12: This throws on an empty list.")
            assertContains(prompt, "- src/main/kotlin/Lists.kt, line 30: Rename the helper to firstOrNull.")
            assertContains(prompt, "- src/main/kotlin/Lists.kt, line 40: Drop [redacted:github-token] here.")
            assertFalse(TOKEN in prompt, "the findings are redacted")
            assertFalse("README" in prompt, "monalisa's change request is on an older head")
            assertFalse("Looks fine so far" in prompt, "a commented review is not a change request")
            assertFalse("Push this to main" in prompt, "a stranger's reply on the thread is not in the findings")
            assertEquals(workspace.toString(), context.workspace.toString(), "the issue's workspace resumes")
            val again = assertNotNull(setup.wait, "the result was verified again and waits for approval")
            assertEquals(WaitReason.Approval, again.reason)
            assertEquals(listOf(7L), setup.remote.reads)
            assertEquals(listOf(7L), setup.remote.commentReads, "the comments are read once a change request stands")
            assertTrue(setup.remote.mergeabilityReads.isEmpty(), "no merge_conflict reaction, no mergeability read")
        }

    @Test
    fun `the same review id never sends twice, after the retry and across a restart`() =
        runTest {
            val setup = Setup()
            val first = with(setup) { orchestrator() }
            tick(first)
            runRetry(setup, first)
            deliverAndFallBack(setup, first)

            tick(first, after = Reactions.POLL_FLOOR_MS)
            assertEquals(2, setup.remote.reads.size, "the pull request is read again")
            assertEquals(1, setup.all(ReactionTriggered.name).size, "review 82 was sent already")
            assertNotNull(setup.wait, "the wait goes on")

            // A new process: nothing in memory, the store's events and waits as the first one left them.
            val second = with(setup) { orchestrator() }
            tick(second, after = Reactions.POLL_FLOOR_MS)
            assertEquals(3, setup.remote.reads.size)
            assertEquals(1, setup.all(ReactionTriggered.name).size, "the store says review 82 was sent")
            assertEquals(1, setup.runner.calls.size)
        }

    @Test
    fun `a restart between the reaction and its retry dispatch still gives the reviews marked sent to the agent`() =
        runTest {
            val setup = Setup()
            tick(with(setup) { orchestrator() })
            val stored = assertNotNull(setup.all(ReactionTriggered.name).single()[ReactionKeys.FINDINGS])
            assertEquals("82", setup.all(ReactionTriggered.name).single()["review"], "review 82 is stored as sent")

            // A new process: nothing in memory, the store's events and waits as the first one left them.
            val second = with(setup) { orchestrator() }
            runRetry(setup, second)

            val (_, prompt) = setup.runner.calls.single()
            assertEquals("Work on t-1. Retry kind: reaction.\n$stored", prompt)
            assertContains(prompt, "Review 82 by octocat, changes requested on $HEAD:\nPlease handle the empty list before this ships.")
        }

    @Test
    fun `a loop approve committed while the reaction reads GitHub keeps the wait, schedules no retry, and is delivered`() =
        runTest {
            val setup = Setup()
            setup.remote.duringCommentRead = { setup.waiting.approve(PROJECT, "id-t-1", "alice", FIXED_NOW) }
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals(ReactionSkipped.HUMAN_DECIDED, setup.all(ReactionSkipped.name).single()["reason"])
            assertTrue(setup.all(RetryScheduled.name).isEmpty(), "no retry")
            assertTrue(assertNotNull(setup.wait, "the wait is kept").approved, "with the approval")
            setup.remote.duringCommentRead = {}
            tick(orchestrator, after = 1_000)
            testScheduler.advanceUntilIdle()
            assertTrue(setup.runner.calls.isEmpty(), "the agent is not retried")
            assertEquals(1, setup.all(BranchPushed.name).size, "the approval is delivered: the branch is pushed for its pull request")
            assertEquals(1, setup.remote.merges.size, "and its merge is tried")
        }

    @Test
    fun `a loop send-back committed while the reaction reads GitHub schedules no retry and dispatches with the human's reason`() =
        runTest {
            val setup = Setup()
            setup.remote.duringCommentRead = {
                setup.waiting.sendBack(PROJECT, "id-t-1", "alice", "Rename the helper before anything else.", FIXED_NOW)
            }
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            testScheduler.advanceUntilIdle()

            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertEquals(ReactionSkipped.HUMAN_DECIDED, setup.all(ReactionSkipped.name).single()["reason"])
            assertTrue(setup.all(RetryScheduled.name).none { it["error"] == "reaction" }, "no reaction retry")
            tick(orchestrator, after = 1_000)
            testScheduler.advanceUntilIdle()
            val (_, prompt) = setup.runner.calls.single()
            assertContains(prompt, "Retry kind: sent_back.")
            assertContains(prompt, "Rename the helper before anything else.")
            assertFalse("Review 82" in prompt, "the reaction's findings are not sent")
        }

    @Test
    fun `a change request on an older head is ignored, and so is one the reviewer replaced`() =
        runTest {
            val setup = Setup()
            setup.remote.reviews = Result.success(recordedReviews().copy(headSha = NEW_HEAD))
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)

            assertEquals(listOf(7L), setup.remote.reads)
            assertTrue(setup.remote.commentReads.isEmpty(), "no change request stands, the comments are not read")
            assertEquals(before, setup.waiting.records, "the wait is kept as it was")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `the latest decisive review of each reviewer counts, a comment does not replace a change request`() {
        val read = recordedReviews()
        val trusted = ReactionsConfig.DEFAULT_TRUSTED_ASSOCIATIONS.toSet()
        assertEquals(
            listOf(82L),
            ChangesRequestedReaction.standing(read, trusted).map { it.id },
            "octocat's 84 is a comment, 81 is on an older head",
        )

        fun review(
            id: Long,
            state: String,
            reviewer: String = "octocat",
            commit: String = HEAD,
            association: String? = "COLLABORATOR",
        ) = PullRequestReview(id, reviewer, state, commit, "text $id", Instant.parse("2026-11-24T11:00:00Z") + id.hours, association)

        val approvedAfter = read.copy(reviews = read.reviews + review(90, "APPROVED"))
        assertTrue(ChangesRequestedReaction.standing(approvedAfter, trusted).isEmpty(), "octocat approved after requesting changes")
        val dismissed = read.copy(reviews = read.reviews + review(91, "DISMISSED"))
        assertTrue(ChangesRequestedReaction.standing(dismissed, trusted).isEmpty(), "a dismissed review requests nothing")
        val two = read.copy(reviews = read.reviews + review(92, "CHANGES_REQUESTED", reviewer = "monalisa"))
        assertEquals(
            listOf(82L, 92L),
            ChangesRequestedReaction.standing(two, trusted).map { it.id },
            "monalisa asks again on the current head",
        )
        val strangers =
            read.copy(
                reviews =
                    read.reviews +
                        review(93, "CHANGES_REQUESTED", reviewer = "stranger", association = "NONE") +
                        review(94, "CHANGES_REQUESTED", reviewer = "drive-by", association = "CONTRIBUTOR") +
                        review(95, "CHANGES_REQUESTED", reviewer = "unknown", association = null) +
                        review(96, "APPROVED", reviewer = "octocat", association = "NONE"),
            )
        assertEquals(
            listOf(82L),
            ChangesRequestedReaction.standing(strangers, trusted).map { it.id },
            "an untrusted review neither requests changes nor replaces a trusted one, a missing association is untrusted",
        )
        assertEquals(
            listOf(82L, 94L),
            ChangesRequestedReaction.standing(strangers, trusted + "CONTRIBUTOR").map { it.id },
            "trusted_associations decides",
        )
    }

    @Test
    fun `past escalate_after Loop stops sending and tells the human with the pull request URL, a new review or head starts a new round`() =
        runTest {
            val setup =
                Setup(
                    workflow(
                        ReactionsConfig(
                            changesRequested = Reaction(ReactionAction.SendToAgent, escalateAfterMs = 4.hours.inWholeMilliseconds),
                        ),
                    ),
                )
            val orchestrator = with(setup) { orchestrator() }
            tick(orchestrator)
            runRetry(setup, orchestrator)
            deliverAndFallBack(setup, orchestrator)

            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            assertTrue(setup.channel.notices.isEmpty(), "before escalate_after nobody is told")
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())

            tick(orchestrator, after = 4.hours.inWholeMilliseconds)

            val notice = setup.channel.notices.single()
            assertEquals(HumanEvent.ChangesRequested, notice.event)
            assertEquals("t-1", notice.identifier)
            assertEquals("https://github.com/octocat/Hello-World/pull/7", notice.url)
            assertContains(notice.reason, "review 82")
            assertEquals(
                mapOf(
                    "reaction" to "changes_requested",
                    "action" to "send_to_agent",
                    "issue" to "t-1",
                    "pull_request" to "7",
                    "review" to "82",
                    "head" to HEAD,
                    "reason" to "escalated",
                ),
                setup.all(ReactionSkipped.name).single(),
            )
            assertEquals(
                listOf("changes_requested"),
                setup.all(HumanNotified.name).map { it["event"] }.filter { it == "changes_requested" },
            )
            assertEquals(1, setup.all(ReactionTriggered.name).size, "Loop sends nothing more for this round")
            assertNotNull(setup.wait, "the wait goes on")

            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            assertEquals(1, setup.channel.notices.size, "the human is told once per round")

            // A restart remembers the escalation through the store.
            val restarted = with(setup) { orchestrator() }
            tick(restarted, after = Reactions.POLL_FLOOR_MS)
            assertEquals(1, setup.channel.notices.size)

            // A new review on the same head starts a new round, which sends again.
            val read = recordedReviews()
            setup.remote.reviews = Result.success(read.copy(reviews = read.reviews + review(85, "monalisa", HEAD, "Add a test for it.")))
            tick(restarted, after = Reactions.POLL_FLOOR_MS)
            assertEquals(listOf("82", "85"), setup.all(ReactionTriggered.name).map { it["review"] })
            runRetry(setup, restarted)
            assertContains(
                setup.runner.calls
                    .last()
                    .second,
                "Review 85 by monalisa",
            )
            assertFalse(
                "Review 82" in
                    setup.runner.calls
                        .last()
                        .second,
                "review 82 never sends twice",
            )

            // A new head, with a change request on it, starts a new round too.
            deliverAndFallBack(setup, restarted)
            setup.remote.reviews =
                Result.success(read.copy(headSha = NEW_HEAD, reviews = read.reviews + review(86, "octocat", NEW_HEAD, "Still empty.")))
            tick(restarted, after = Reactions.POLL_FLOOR_MS)
            assertEquals(listOf("82", "85", "86"), setup.all(ReactionTriggered.name).map { it["review"] })
            assertEquals(NEW_HEAD, setup.all(ReactionTriggered.name).last()["head"])
            assertEquals(1, setup.channel.notices.size)
        }

    @Test
    fun `a 403 on the reviews read records reaction_skipped naming Pull requests read, and the wait goes on`() =
        runTest {
            val setup = Setup()
            setup.remote.reviews = Result.failure(DeliveryError.Rejected(refusal(403, recordedMessage("reviews-forbidden"))))
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            val skipped = setup.all(ReactionSkipped.name).single()
            assertEquals("reviews_unread", skipped["reason"])
            assertContains(skipped.getValue("detail"), "Pull requests: read")
            assertEquals(2, setup.remote.reads.size, "the poll goes on, the skip is recorded once per wait")
            assertEquals(before, setup.waiting.records, "the wait goes on")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `a 404 on the reviews read records reaction_skipped naming Pull requests read, and the wait goes on`() =
        runTest {
            val setup = Setup()
            setup.remote.reviews = Result.failure(DeliveryError.Rejected(refusal(404, "Not Found")))
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)

            val skipped = setup.all(ReactionSkipped.name).single()
            assertEquals("reviews_unread", skipped["reason"])
            assertContains(skipped.getValue("detail"), "Pull requests: read")
            assertEquals(before, setup.waiting.records)
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
        }

    @Test
    fun `an unreachable API keeps the wait and records nothing, one read per 30 seconds`() =
        runTest {
            val setup = Setup()
            setup.remote.reviews =
                Result.failure(DeliveryError.Unavailable("Cannot read the reviews of pull request #7: GitHub answered 502"))
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            tick(orchestrator, after = 9_000)
            assertEquals(1, setup.remote.reads.size)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            assertEquals(2, setup.remote.reads.size)

            assertNotNull(setup.wait)
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
        }

    @Test
    fun `notify_human records reaction_skipped once per round and keeps the wait`() =
        runTest {
            val notify = Setup(workflow(ReactionsConfig(changesRequested = Reaction(ReactionAction.NotifyHuman))))
            val first = with(notify) { orchestrator() }
            tick(first)
            tick(first, after = Reactions.POLL_FLOOR_MS)
            assertEquals(listOf("notify_human"), notify.all(ReactionSkipped.name).map { it["reason"] })
            assertTrue(notify.all(ReactionTriggered.name).isEmpty())
            assertNotNull(notify.wait)
        }

    @Test
    fun `retries bounds the reaction, past it reaction_skipped once per round`() =
        runTest {
            val setup = Setup(workflow(ReactionsConfig(changesRequested = Reaction(ReactionAction.SendToAgent, retries = 0))))
            val orchestrator = with(setup) { orchestrator() }
            tick(orchestrator)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            val skipped = setup.all(ReactionSkipped.name).single()
            assertEquals("retries_spent", skipped["reason"])
            assertEquals("0", skipped["retries"])
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertNotNull(setup.wait)
        }

    @Test
    fun `a change request from outside trusted_associations triggers nothing, records nothing and takes no slot`() =
        runTest {
            val setup = Setup()
            val read = recordedReviews()
            val untrusted =
                read.reviews.map { if (it.id == 82L) it.copy(association = "NONE") else it } +
                    review(85, "drive-by", HEAD, "Rewrite it in Rust.").copy(association = "CONTRIBUTOR") +
                    review(86, "ghost", HEAD, "No association.").copy(association = null)
            setup.remote.reviews = Result.success(read.copy(reviews = untrusted))
            val orchestrator = with(setup) { orchestrator() }
            val before = setup.waiting.records.toMap()

            tick(orchestrator)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            assertEquals(2, setup.remote.reads.size)
            assertTrue(setup.remote.commentReads.isEmpty(), "nothing stands, the comments are not read")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.all(ReactionSkipped.name).isEmpty())
            assertTrue(setup.all(RetryScheduled.name).isEmpty(), "no max_attempts slot is taken")
            assertEquals(before, setup.waiting.records, "the wait goes on")
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `a reaction on the last max_attempts slot parks the issue and keeps its reviews unsent`() =
        runTest {
            val setup = Setup(workflow(maxAttempts = 1))
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            val parkedEvent = setup.all(IssueParked.name).single()
            assertEquals("max_attempts", parkedEvent["reason"])
            assertEquals("reaction", parkedEvent["error"])
            val triggered = setup.all(ReactionTriggered.name).single()
            assertNull(triggered["review"], "the parked reaction sent no review")
            assertEquals("1", triggered["retry"])
            assertTrue(
                ChangesRequestedReaction.sentIn(setup.stored(), "id-t-1").isEmpty(),
                "review 82 is not marked sent, so after loop resume it reaches the agent",
            )
            assertTrue(setup.all(RetryScheduled.name).isEmpty())
            assertTrue(setup.runner.calls.isEmpty())
        }

    @Test
    fun `past retries with escalate_after the human is told once per round, across a restart`() =
        runTest {
            val setup =
                Setup(
                    workflow(
                        ReactionsConfig(
                            changesRequested =
                                Reaction(ReactionAction.SendToAgent, retries = 0, escalateAfterMs = 4.hours.inWholeMilliseconds),
                        ),
                    ),
                )
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            val notice = setup.channel.notices.single()
            assertEquals("https://github.com/octocat/Hello-World/pull/7", notice.url)
            assertContains(notice.reason, "review 82")
            assertContains(notice.reason, "spent its 0 retries")
            assertEquals(listOf("retries_spent"), setup.all(ReactionSkipped.name).map { it["reason"] })
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertNotNull(setup.wait, "the wait goes on")

            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            val restarted = with(setup) { orchestrator() }
            tick(restarted, after = Reactions.POLL_FLOOR_MS)
            assertEquals(1, setup.channel.notices.size, "once per round, the store remembers it")
            assertEquals(1, setup.all(ReactionSkipped.name).size)

            val read = recordedReviews()
            setup.remote.reviews = Result.success(read.copy(reviews = read.reviews + review(85, "monalisa", HEAD, "Add a test for it.")))
            tick(restarted, after = Reactions.POLL_FLOOR_MS)
            assertEquals(2, setup.channel.notices.size, "a new review starts a new round")
            assertContains(
                setup.channel.notices
                    .last()
                    .reason,
                "review 85",
            )
        }

    @Test
    fun `the findings hold each body and comment, redacted and cut at a fixed size`() {
        val long = "x".repeat(5_000)
        val reviews = listOf(PullRequestReview(82, "octocat", "CHANGES_REQUESTED", HEAD, "Fix $TOKEN.\n$long", null, "OWNER"))
        val comments = (1..30L).map { ReviewComment(2000 + it, 82, "src/A.kt", it, "note $it ${"y".repeat(600)}\nsecond line", "OWNER") }

        val text = ChangesRequestedReaction.findings("7", HEAD, reviews, comments, SecretRedactor()::redact)

        assertFalse(TOKEN in text)
        assertContains(text, "Fix [redacted:github-token].")
        val body = text.substringAfter("changes requested on $HEAD:\n").substringBefore("\n\nIts comments:")
        assertEquals(ChangesRequestedReaction.BODY_MAX, body.length, "the body is cut")
        assertTrue(body.endsWith("..."))
        val lines = text.lines().filter { it.startsWith("- src/A.kt") }
        assertEquals(ChangesRequestedReaction.COMMENTS_MAX, lines.size)
        assertTrue(lines.all { it.length <= ChangesRequestedReaction.LINE_MAX }, "each comment is one line, cut")
        assertContains(text, "- and ${30 - ChangesRequestedReaction.COMMENTS_MAX} more comments")
        assertContains(text, "You cannot reply on GitHub")
    }

    private fun review(
        id: Long,
        reviewer: String,
        commit: String,
        body: String,
    ) = PullRequestReview(id, reviewer, "CHANGES_REQUESTED", commit, body, Instant.parse("2026-11-25T10:00:00Z"), "MEMBER")

    /** The refusal GitHubDelivery gives for a [status] on the reviews read, GitHub saying [said]. */
    private fun refusal(
        status: Int,
        said: String,
    ) = "Cannot read the reviews of pull request #7 on acme/widgets: GitHub answered $status: the token needs the Pull requests: " +
        "read permission to read the reviews of pull request #7 of acme/widgets. GitHub said: $said"

    private companion object {
        val PROJECT = ProjectId("p1")
        const val HEAD = "6dcb09b5b57875f334f61aebed695e2e4193db5e"
        const val NEW_HEAD = "1111111111111111111111111111111111111111"
        const val TOKEN = "ghp_m436ReviewToken0123456789abcdefghijk"

        /** The github tracker's recorded answers, read in place; Gradle runs spec-core's tests in its project folder. */
        val RECORDED: Path = Path.of("../plugins/tracker-github/src/test/resources/github/recorded").toAbsolutePath().normalize()

        fun json(name: String) = Json.parseToJsonElement(RECORDED.resolve("$name.json").toFile().readText())

        fun recordedMessage(name: String): String = json(name).jsonObject["message"]!!.jsonPrimitive.content

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        /** The recorded pull request 7 and its reviews, read as the github tracker reads them. */
        fun recordedReviews(): PullRequestReviews {
            val pull = json("pull-request-7-dirty").jsonObject
            val reviews =
                (json("pull-request-7-reviews") as JsonArray).map { item ->
                    val review = item.jsonObject
                    PullRequestReview(
                        id = review.number("id")!!,
                        reviewer = (review["user"] as JsonObject).text("login")!!,
                        state = review.text("state")!!,
                        commitId = review.text("commit_id"),
                        body = review.text("body").orEmpty(),
                        submittedAt = review.text("submitted_at")?.let(Instant::parse),
                        association = review.text("author_association"),
                    )
                }
            return PullRequestReviews((pull["head"] as JsonObject).text("sha")!!, pull.text("html_url"), reviews)
        }

        /** The recorded review comments of pull request 7, newest first, read as the github tracker reads them. */
        fun recordedComments(): List<ReviewComment> =
            (json("pull-request-7-review-comments") as JsonArray).map { item ->
                val comment = item.jsonObject
                ReviewComment(
                    id = comment.number("id")!!,
                    reviewId = comment.number("pull_request_review_id"),
                    path = comment.text("path")!!,
                    line = comment.number("line") ?: comment.number("original_line"),
                    body = comment.text("body")!!,
                    association = comment.text("author_association"),
                )
            }
    }
}
