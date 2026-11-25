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
import kotlinx.serialization.json.longOrNull
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.AttemptId
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.HumanChannel
import loop.api.HumanEvent
import loop.api.HumanNotice
import loop.api.MergeMethod
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
import loop.core.events.Delivery
import loop.core.events.DeliveryFailed
import loop.core.events.HumanNotified
import loop.core.events.MergeCheckFinished
import loop.core.events.ReactionSkipped
import loop.core.events.ReactionTriggered
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * m4-37: the `approved_green` reaction. A watched wait, an unapproved `Waiting(approval)` that holds an open pull
 * request, is polled at most once per tick and every 30 seconds. When the latest decisive review of each trusted
 * reviewer holds an `APPROVED` on the current head and no `CHANGES_REQUESTED`, and the head is green, Loop tells the
 * human once per head through the `HumanChannel`. Green is the CI verdict of the head under `ci: actions` and the merge
 * check passed on the head under `ci: none`. The reviews and the Actions runs are the hand written GitHub answers under
 * the github tracker's recorded test resources, read as the github tracker reads them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovedGreenReactionTest {
    private val root: Path = createTempDirectory("loop-approved-green").toRealPath()
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
        ci: CiMode = CiMode.Actions,
        reactions: ReactionsConfig = ReactionsConfig(approvedGreen = Reaction(ReactionAction.NotifyHuman)),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    retries = RetriesConfig(maxAttempts = 5),
                    kinds =
                        KindsConfig(
                            KindConfig(
                                listOf(CheckConfig.Command("./gradlew test")),
                                delivery =
                                    DeliveryConfig(
                                        DeliveryPolicy.Human,
                                        PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", merge = MergeConfig(MergeMethod.Merge, ci)),
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
            promptTemplate = "Work on {{ issue.identifier }}.",
        )

    /**
     * A GitHub like remote whose reviews read answers [reviews] and whose runs read answers [runs]. Pull request #7 of
     * `loop/t-1` is open: a delivery finds it, pushes and updates it, and its merge is refused for good.
     */
    private class FakeRemote(
        private val tip: String,
    ) : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        var reviews: Result<PullRequestReviews> = Result.failure(IllegalStateException("no reviews set"))
        var runs: Result<ActionsRuns> = Result.failure(IllegalStateException("no runs set"))
        val reads = mutableListOf<Long>()
        val runReads = mutableListOf<String>()
        private var pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)

        override suspend fun pullRequestReviews(number: Long): Result<PullRequestReviews> {
            reads += number
            return reviews
        }

        override suspend fun pullRequestReviewComments(number: Long): Result<List<ReviewComment>> = Result.success(emptyList())

        override suspend fun actionsRuns(sha: String): Result<ActionsRuns> {
            runReads += sha
            return runs
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
        ): Result<String> = Result.failure(DeliveryError.Rejected("GitHub answered 405: At least one approving review is required"))
    }

    /** The human channel of the test: the `approved_green` notices it was given; the approval notices are left out. */
    private class FakeChannel : HumanChannel {
        val all = mutableListOf<HumanNotice>()
        val notices: List<HumanNotice> get() = all.filter { it.event == HumanEvent.ApprovedGreen }

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
                reviews = Result.success(recordedReviews("pull-request-7-reviews-approved"))
                runs = Result.success(recordedRuns("actions-runs-green"))
            }
        val waiting = FakeWaitingIssues(fallbackWait(head))
        val sink = FakeSink()
        val parked = FakeParkedIssues()
        val channel = FakeChannel()

        /** The merge checks of earlier runs the store holds, beside the ones the sink records in this test. */
        val mergeChecksBefore = mutableListOf<RecordedEvent>()
        var storeReadable = true

        val wait: WaitingIssue? get() = waiting.records.values.singleOrNull()

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }

        /** The reaction events the store holds: the sink's, which the store keeps across a restart. */
        fun stored(): List<RecordedEvent> = sink.events.filter { it.kind == ReactionTriggered.name || it.kind == ReactionSkipped.name }

        /** Approves the unapproved approval wait, as `loop approve` writes the store. */
        fun approve() {
            val unapproved = assertNotNull(wait, "an approval wait")
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
                runner = FakeRunner(),
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
                reactionsBefore = {
                    if (storeReadable) Result.success(stored()) else Result.failure(IllegalStateException("the store is locked"))
                },
                mergeChecksBefore = {
                    Result.success(mergeChecksBefore + sink.events.filter { it.kind == MergeCheckFinished.name })
                },
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

    /** A `merge_check_finished` of an earlier run of t-1 with [outcome] on [head], at [at]. */
    private fun mergeCheck(
        outcome: String,
        head: String?,
        at: Instant = FIXED_NOW - 10.minutes,
    ): RecordedEvent =
        RecordedEvent(
            ts = at,
            projectId = PROJECT,
            kind = MergeCheckFinished.name,
            runId = RunId("r0"),
            issueId = "id-t-1",
            issueIdentifier = "t-1",
            data = mapOfNotNull(Delivery.OUTCOME to outcome, Delivery.ROUND to "1", Delivery.HEAD to head),
        )

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

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
    fun `under ci actions an approved head with a green verdict notifies once through the human channel with the pull request URL`() =
        runTest {
            val setup = Setup()
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            val notice = setup.channel.notices.single()
            assertEquals("t-1", notice.identifier)
            assertEquals("https://github.com/octocat/Hello-World/pull/7", notice.url)
            assertEquals("loop approve t-1", notice.nextCommand)
            assertContains(notice.reason, "pull request #7 is approved and green on its head ${HEAD.take(12)}")
            assertContains(notice.reason, "approved by hubot, monalisa")
            assertContains(notice.reason, "its Actions runs passed (ci: actions)")
            assertEquals(listOf(HEAD), setup.remote.runReads, "the runs of the current head are read")
            assertEquals(
                listOf(
                    mapOf(
                        "reaction" to "approved_green",
                        "action" to "notify_human",
                        "issue" to "t-1",
                        "pull_request" to "7",
                        "head" to HEAD,
                        "green" to "actions",
                    ),
                ),
                setup.all(ReactionTriggered.name),
            )
            val notified = setup.all(HumanNotified.name).single { it[HumanNotified.EVENT] == "approved_green" }
            assertEquals("https://github.com/octocat/Hello-World/pull/7", notified[HumanNotified.URL])
            assertEquals("loop approve t-1", notified[HumanNotified.COMMAND])
            assertNotNull(setup.wait, "the wait goes on, the human merges")

            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            assertEquals(2, setup.remote.reads.size, "the pull request is read again")
            assertEquals(1, setup.channel.notices.size, "the same head never notifies twice")
            assertEquals(1, setup.all(ReactionTriggered.name).size)
        }

    @Test
    fun `under ci actions a pending, a red or a missing verdict sends nothing`() =
        runTest {
            val setup = Setup()
            val orchestrator = with(setup) { orchestrator() }
            val answers =
                listOf(
                    recordedRuns("actions-runs"),
                    recordedRuns("actions-runs-red"),
                    ActionsRuns(0, emptyList()),
                )

            answers.forEachIndexed { index, runs ->
                setup.remote.runs = Result.success(runs)
                tick(orchestrator, after = if (index == 0) 0 else Reactions.POLL_FLOOR_MS)
            }

            assertEquals(3, setup.remote.runReads.size, "each poll read the runs")
            assertTrue(setup.channel.notices.isEmpty(), "${setup.channel.notices}")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertNotNull(setup.wait, "the wait goes on")
        }

    @Test
    fun `a change request sends nothing, on the current head or standing from an older one, and an untrusted approval is no approval`() =
        runTest {
            val setup = Setup()
            val orchestrator = with(setup) { orchestrator() }
            val requested = recordedReviews("pull-request-7-reviews")
            val older = requested.copy(reviews = requested.reviews.filter { it.id == 81L || it.id == 83L })
            val stranger =
                requested.copy(
                    reviews = requested.reviews.filter { it.id == 83L }.map { it.copy(association = "NONE") },
                )

            listOf(requested, older, stranger).forEachIndexed { index, reviews ->
                setup.remote.reviews = Result.success(reviews)
                tick(orchestrator, after = if (index == 0) 0 else Reactions.POLL_FLOOR_MS)
            }

            assertEquals(3, setup.remote.reads.size)
            assertTrue(setup.remote.runReads.isEmpty(), "a head that is not approved reads no run")
            assertTrue(setup.channel.notices.isEmpty(), "${setup.channel.notices}")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
        }

    @Test
    fun `under ci none an approved head whose merge check passed on it notifies`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.None))
            setup.remote.reviews = Result.success(recordedReviews("pull-request-7-reviews-approved", head = setup.head))
            val orchestrator = with(setup) { orchestrator() }

            // loop approve, then the delivery: the merge check passes on the head and the merge is refused for good.
            setup.approve()
            tick(orchestrator, after = 1_000)
            testScheduler.advanceUntilIdle()
            assertEquals(1, setup.all(DeliveryFailed.name).size, "the merge was refused")
            val passed = setup.all(MergeCheckFinished.name).single()
            assertEquals(MergeCheck.UP_TO_DATE to setup.head, passed[Delivery.OUTCOME] to passed[Delivery.HEAD])
            assertEquals("https://github.com/acme/widgets/pull/7", setup.wait?.data?.get(WaitKeys.PULL_REQUEST), "a watched wait")

            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            val notice = setup.channel.notices.single()
            assertContains(notice.reason, "the merge check passed on it (ci: none)")
            assertEquals("https://github.com/octocat/Hello-World/pull/7", notice.url)
            assertTrue(setup.remote.runReads.isEmpty(), "ci: none reads no Actions run")
            assertEquals("merge_check", setup.all(ReactionTriggered.name).single()["green"])
            assertEquals(setup.head, setup.all(ReactionTriggered.name).single()["head"])
        }

    @Test
    fun `under ci none an approved head whose merge check did not pass sends nothing`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.None))
            val orchestrator = with(setup) { orchestrator() }

            // No merge check at all.
            tick(orchestrator)
            // The latest merge check of the head conflicted, after an earlier one that passed.
            setup.mergeChecksBefore += mergeCheck(MergeCheck.UP_TO_DATE, HEAD, FIXED_NOW - 20.minutes)
            setup.mergeChecksBefore += mergeCheck(MergeCheck.CONFLICT, null, FIXED_NOW - 10.minutes)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            // The checks failed on the merged result of the head.
            setup.mergeChecksBefore.clear()
            setup.mergeChecksBefore += mergeCheck(MergeCheck.REFUTED, HEAD)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            assertEquals(3, setup.remote.reads.size)
            assertTrue(setup.channel.notices.isEmpty(), "${setup.channel.notices}")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())

            setup.mergeChecksBefore += mergeCheck(MergeCheck.MERGED, HEAD, FIXED_NOW - 5.minutes)
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)

            assertEquals(1, setup.channel.notices.size, "a merge check that passed on the head is green")
        }

    @Test
    fun `a head pushed after the merge check passed is not green and sends nothing`() =
        runTest {
            val setup = Setup(workflow(ci = CiMode.None))
            setup.mergeChecksBefore += mergeCheck(MergeCheck.MERGED, HEAD)
            setup.remote.reviews = Result.success(recordedReviews("pull-request-7-reviews-approved", head = NEW_HEAD))
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)

            assertEquals(1, setup.remote.reads.size)
            assertTrue(setup.channel.notices.isEmpty(), "${setup.channel.notices}")
            assertTrue(setup.all(ReactionTriggered.name).isEmpty())
            assertTrue(setup.remote.runReads.isEmpty())
        }

    @Test
    fun `the same head never notifies twice, across a restart, and a new approved and green head notifies again`() =
        runTest {
            val setup = Setup()
            val first = with(setup) { orchestrator() }
            tick(first)
            assertEquals(1, setup.channel.notices.size)

            // A new process: nothing in memory, the store's events and waits as the first one left them.
            val second = with(setup) { orchestrator() }
            tick(second, after = Reactions.POLL_FLOOR_MS)
            assertEquals(2, setup.remote.reads.size, "the restarted run polls again")
            assertEquals(1, setup.channel.notices.size, "the store says this head was told")

            setup.remote.reviews = Result.success(recordedReviews("pull-request-7-reviews-approved", head = NEW_HEAD))
            tick(second, after = Reactions.POLL_FLOOR_MS)

            assertEquals(2, setup.channel.notices.size, "a new approved and green head notifies again")
            val short = NEW_HEAD.take(12)
            assertContains(
                setup.channel.notices
                    .last()
                    .reason,
                "on its head $short",
            )
            assertEquals(listOf(HEAD, NEW_HEAD), setup.all(ReactionTriggered.name).map { it["head"] })
            assertEquals(NEW_HEAD, setup.remote.runReads.last())
        }

    @Test
    fun `a store that cannot be read sends nothing, and the next poll that reads it notifies`() =
        runTest {
            val setup = Setup()
            setup.storeReadable = false
            val orchestrator = with(setup) { orchestrator() }

            tick(orchestrator)
            assertTrue(setup.channel.notices.isEmpty(), "no notice without the proof it was not sent before")

            setup.storeReadable = true
            tick(orchestrator, after = Reactions.POLL_FLOOR_MS)
            assertEquals(1, setup.channel.notices.size)
        }

    @Test
    fun `with changes_requested set too the reviews are read once per poll`() =
        runTest {
            val setup =
                Setup(
                    workflow(
                        reactions =
                            ReactionsConfig(
                                changesRequested = Reaction(ReactionAction.SendToAgent),
                                approvedGreen = Reaction(ReactionAction.NotifyHuman),
                            ),
                    ),
                )

            tick(with(setup) { orchestrator() })

            assertEquals(listOf(7L), setup.remote.reads, "one reviews read serves both reactions")
            assertEquals(1, setup.channel.notices.size)
        }

    @Test
    fun `with no approved_green reaction nothing is read or told`() =
        runTest {
            val setup = Setup(workflow(reactions = ReactionsConfig()))

            tick(with(setup) { orchestrator() })

            assertTrue(setup.remote.reads.isEmpty(), "no reaction, nothing read")
            assertTrue(setup.remote.runReads.isEmpty())
            assertTrue(setup.channel.notices.isEmpty())
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val HEAD = "6dcb09b5b57875f334f61aebed695e2e4193db5e"
        const val NEW_HEAD = "1111111111111111111111111111111111111111"

        /** The github tracker's recorded answers, read in place; Gradle runs spec-core's tests in its project folder. */
        val RECORDED: Path = Path.of("../plugins/tracker-github/src/test/resources/github/recorded").toAbsolutePath().normalize()

        fun json(name: String) = Json.parseToJsonElement(RECORDED.resolve("$name.json").toFile().readText())

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        /**
         * The recorded pull request 7 and the reviews of [name], read as the github tracker reads them. With [head], the
         * pull request's head and the reviews left on the recorded head are moved to [head].
         */
        fun recordedReviews(
            name: String,
            head: String? = null,
        ): PullRequestReviews {
            val pull = json("pull-request-7-dirty").jsonObject
            val recordedHead = (pull["head"] as JsonObject).text("sha")!!
            val moved = head ?: recordedHead
            val reviews =
                (json(name) as JsonArray).map { item ->
                    val review = item.jsonObject
                    val commit = review.text("commit_id")
                    PullRequestReview(
                        id = review.number("id")!!,
                        reviewer = (review["user"] as JsonObject).text("login")!!,
                        state = review.text("state")!!,
                        commitId = if (commit == recordedHead) moved else commit,
                        body = review.text("body").orEmpty(),
                        submittedAt = review.text("submitted_at")?.let(Instant::parse),
                        association = review.text("author_association"),
                    )
                }
            return PullRequestReviews(moved, pull.text("html_url"), reviews)
        }

        /** A recorded runs listing, read as the github tracker reads it. */
        fun recordedRuns(name: String): ActionsRuns {
            val listing = json(name).jsonObject
            val runs =
                (listing["workflow_runs"] as JsonArray).map { item ->
                    val run = item.jsonObject
                    ActionsRun(
                        id = run.number("id")!!,
                        name = run.text("name")!!,
                        workflowId = run.number("workflow_id")!!,
                        event = run.text("event")!!,
                        runNumber = run.number("run_number")!!,
                        runAttempt = run.number("run_attempt") ?: 1,
                        status = run.text("status")!!,
                        conclusion = run.text("conclusion"),
                        url = run.text("html_url")!!,
                    )
                }
            return ActionsRuns(listing.number("total_count")!!, runs)
        }
    }
}
