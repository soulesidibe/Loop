package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.ActionsRun
import loop.api.ActionsRuns
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
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.delivery.MergeCheck
import loop.core.events.CiFinished
import loop.core.events.CiWaiting
import loop.core.events.DeliveryFailed
import loop.core.events.PullRequestMerged
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.WorkspaceFingerprinter
import loop.core.verify.WorkspaceGit
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertTrue

/**
 * m3-15: the CI wait between the merge check and the merge. With `ci: actions` a passing merge check pushes the merged
 * head and waits as `Waiting(merge)` in phase `ci`; each tick polls its Actions runs at most once every 30 seconds, with
 * no slot and no workspace, and merges only on a green verdict or a passing no run rule. Red CI, a failing no run rule
 * and a timeout give `delivery_failed`, a comment, the pull request left open and an unapproved `Waiting(approval)`.
 * The project repository and its worktree are real temporary repositories; the remote answers recorded like Actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CiWaitOrchestratorTest {
    private val root: Path = createTempDirectory("loop-ci-wait").toRealPath()
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

    /** The project repository on `main`, the worktree of `loop/t-1` with one verified commit V on it. Returns V. */
    private fun verifiedWorktree(): String {
        git(project, "init", "-q", "-b", "main")
        commit(project, "a.txt", "one\n")
        git(project, "worktree", "add", "-q", "-b", "loop/t-1", workspace.toString())
        return commit(workspace, "work.txt", "work\n")
    }

    /** The worktree of `loop/t-2` from `main`, with its own verified commit, after [verifiedWorktree]. Returns it. */
    private fun secondWorktree(): String {
        git(project, "worktree", "add", "-q", "-b", "loop/t-2", root.resolve("workspaces/t-2").toString(), "main")
        return commit(root.resolve("workspaces/t-2"), "work2.txt", "work two\n")
    }

    /** A commit on the base, made on `upstream` so local `main` stays behind, as objects a fetch landed. */
    private fun baseCommit(): String {
        git(project, "checkout", "-q", "-B", "upstream", "main")
        val sha = commit(project, "c.txt", "base\n")
        git(project, "checkout", "-q", "main")
        return sha
    }

    private val actions = MergeConfig(MergeMethod.Merge, CiMode.Actions, ciTimeoutMs = 120_000, ciNoRunGraceMs = 60_000)

    private fun workflow(merge: MergeConfig = actions): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1, maxConcurrentAgents = 10),
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

    /** A GitHub like remote whose Actions listing answers [answers] in turn, the last one again once the others are used. */
    private inner class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<String>()
        val merges = mutableListOf<String>()
        val pulls = mutableMapOf<String, PullRequest>()
        val bases = ArrayDeque<String>()
        var lastBase: String? = null
        var tip: (() -> String)? = null
        val answers = ArrayDeque<Result<ActionsRuns>>()
        val listed = mutableListOf<String>()

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
            val pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = pushes.lastOrNull())
            pulls[branch] = pull
            return Result.success(pull)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(pulls.values.single { it.number == number })

        override suspend fun fetchBase(): Result<String> {
            val sha = bases.removeFirstOrNull() ?: lastBase ?: error("no base to fetch")
            lastBase = sha
            return Result.success(sha)
        }

        override suspend fun baseTip(): Result<String> = Result.success(tip?.invoke() ?: checkNotNull(lastBase))

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
    }

    private fun run(
        id: Long,
        status: String = "completed",
        conclusion: String? = "success",
        name: String = "build",
        workflow: Long = id,
    ) = ActionsRun(id, name, workflow, "push", 1, 1, status, conclusion, "https://github.com/acme/widgets/actions/runs/$id")

    private fun listing(vararg runs: ActionsRun) = Result.success(ActionsRuns(runs.size.toLong(), runs.toList()))

    private val pending = listing(run(11), run(12, status = "in_progress", conclusion = null, name = "lint"))
    private val green = listing(run(11), run(12, name = "lint"))
    private val red = listing(run(11), run(12, conclusion = "failure", name = "lint with ghp_abcdefghijklmnopqrstuvwxyz0123456789"))

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

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        remote: DeliveryExecutor,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
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
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { passing },
            waiting = waiting,
            fingerprinter = fingerprinter,
            deliveryFor = { remote },
            workspaceGit = workspaceGit,
        )
    }

    /** Everything one test drives: the fakes, the orchestrator and V, the verified commit. */
    private inner class Setup(
        val workflow: WorkflowFile = workflow(),
        identifiers: List<String> = listOf("t-1"),
    ) {
        val verified = verifiedWorktree()
        val tracker = FakeTracker(identifiers.map { issue(it) })
        val remote = FakeRemote().apply { bases += git(project, "rev-parse", "main") }
        val waiting = FakeWaitingIssues(approvedWait(MergeCheck.cleanFingerprint(verified)))
        val sink = FakeSink()

        val wait: WaitingIssue? get() = waiting.records.values.singleOrNull()

        fun all(kind: String): List<Map<String, String>> = sink.events.filter { it.kind == kind }.map { it.data }
    }

    /** One tick and every background job it started, after [after] milliseconds of the test clock. */
    private suspend fun TestScope.tick(
        orchestrator: Orchestrator,
        after: Long = 0,
    ) {
        if (after > 0) advanceTimeBy(after)
        orchestrator.tick()
        testScheduler.runCurrent()
    }

    @Test
    fun `a passing merge check under ci actions pushes the merged head, waits in phase ci from the push and records ci_waiting`() =
        runTest {
            val setup = Setup()
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)

            tick(orchestrator)

            val wait = assertNotNull(setup.wait)
            assertEquals(WaitReason.Merge, wait.reason)
            assertEquals("ci", wait.data[WaitKeys.PHASE])
            assertEquals(SchedulerClock(testScheduler).now().toString(), wait.data[WaitKeys.CI_STARTED_AT])
            assertEquals("0", wait.data[WaitKeys.CI_WAITED_MS])
            assertEquals(setup.verified, wait.data[WaitKeys.MERGED_HEAD])
            assertEquals(mapOf("sha" to setup.verified), setup.all(CiWaiting.name).single())
            assertEquals(listOf(setup.verified, setup.verified), setup.remote.pushes, "the delivery push, then the merged head for CI")
            assertEquals(emptyList(), setup.remote.merges)
            assertEquals(emptyList(), setup.remote.listed, "the first poll waits for the next tick")
        }

    @Test
    fun `pending runs keep the wait, all kept runs green merges with ci_finished green`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += listOf(pending, green)
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)

            assertEquals("ci", setup.wait?.data?.get(WaitKeys.PHASE))
            assertEquals("30000", setup.wait?.data?.get(WaitKeys.CI_WAITED_MS))
            assertTrue(setup.all(CiFinished.name).isEmpty())
            assertEquals(emptyList(), setup.remote.merges)

            tick(orchestrator, after = 30_000)

            assertEquals(listOf(setup.verified, setup.verified), setup.remote.listed)
            assertEquals(mapOf("outcome" to "green", "runs" to "11,12"), setup.all(CiFinished.name).single())
            assertEquals(listOf(setup.verified), setup.remote.merges)
            assertEquals(1, setup.all(PullRequestMerged.name).size)
            assertEquals(listOf("id-t-1" to "done"), setup.tracker.moves)
            assertNull(setup.wait)
            assertEquals(1, setup.all("branch_pushed").size, "the merged head is V, recorded once, by the delivery")
        }

    @Test
    fun `a failed run gives delivery_failed, a comment naming the failed runs, the pull request open and an unapproved approval`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += red
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)

            assertEquals(mapOf("outcome" to "red", "runs" to "11,12"), setup.all(CiFinished.name).single())
            val failed = setup.all(DeliveryFailed.name).single()
            assertEquals("ci", failed["step"])
            assertContains(failed["reason"].orEmpty(), "Actions runs did not pass")
            val comment =
                setup.tracker.comments
                    .last()
                    .second.text
            assertContains(comment, "Loop did not merge pull request #7, ci: Actions runs did not pass")
            assertContains(comment, "Failed runs:\n- lint with ")
            assertContains(comment, "(failure): https://github.com/acme/widgets/actions/runs/12")
            assertFalse(comment.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"), "the run names are redacted")
            assertFalse(comment.contains("runs/11"), "a green run is not named")
            assertContains(comment, "then approve again with loop approve")
            assertEquals("ci", setup.all("issue_commented").last()["reason"])
            val wait = assertNotNull(setup.wait)
            assertEquals(WaitReason.Approval, wait.reason)
            assertFalse(wait.approved)
            assertNull(wait.data[WaitKeys.PHASE])
            assertNull(wait.data[WaitKeys.CI_STARTED_AT])
            assertEquals(emptyList(), setup.remote.merges)
            assertEquals(
                PullRequestState.Open,
                setup.remote.pulls.values
                    .single()
                    .state,
            )
            assertEquals(emptyList(), setup.tracker.moves, "no dispatch, the issue is not moved")
        }

    @Test
    fun `zero runs after the grace with ci_no_run fail gives delivery_failed`() =
        runTest {
            val setup = Setup()
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)
            assertEquals("ci", setup.wait?.data?.get(WaitKeys.PHASE), "still in the grace")

            tick(orchestrator, after = 30_000)

            assertEquals(mapOf("outcome" to "no_run_fail", "runs" to ""), setup.all(CiFinished.name).single())
            assertContains(
                setup.all(DeliveryFailed.name).single()["reason"].orEmpty(),
                "no Actions run after 1 minutes, and ci_no_run is fail",
            )
            assertEquals(WaitReason.Approval, setup.wait?.reason)
            assertEquals(emptyList(), setup.remote.merges)
        }

    @Test
    fun `zero runs after the grace with ci_no_run pass merges`() =
        runTest {
            val setup = Setup(workflow(actions.copy(ciNoRun = CiNoRun.Pass)))
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink, setup.workflow)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)
            assertEquals(emptyList(), setup.remote.merges, "still in the grace")
            tick(orchestrator, after = 30_000)

            assertEquals(mapOf("outcome" to "no_run_pass", "runs" to ""), setup.all(CiFinished.name).single())
            assertEquals(listOf(setup.verified), setup.remote.merges)
        }

    @Test
    fun `ci_timeout_ms gives delivery_failed, counted only on answered polls while runs are pending`() =
        runTest {
            val setup = Setup()
            val unavailable = Result.failure<ActionsRuns>(DeliveryError.Unavailable("Cannot list the Actions runs on acme/widgets: 502"))
            val held = Result.failure<ActionsRuns>(DeliveryError.Unavailable("Cannot list the Actions runs on acme/widgets: rate limited"))
            val unreachable =
                Result.failure<ActionsRuns>(
                    DeliveryError.Unavailable("Cannot list the Actions runs on acme/widgets: refused"),
                )
            setup.remote.answers += listOf(pending, unavailable, held, unreachable, pending, pending, pending)
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)
            assertEquals("30000", setup.wait?.data?.get(WaitKeys.CI_WAITED_MS))
            val events = setup.sink.events.size
            val writes = setup.waiting.history.size
            repeat(3) { tick(orchestrator, after = 30_000) }
            assertEquals(
                emptyList(),
                setup.sink.events
                    .drop(events)
                    .map {
                        it.kind
                    }.filter { it != "preflight" },
                "unreachable, 5xx and hold answers record nothing",
            )
            assertEquals(writes, setup.waiting.history.size, "nor write the wait")
            assertEquals("ci", setup.wait?.data?.get(WaitKeys.PHASE))

            tick(orchestrator, after = 30_000)
            assertEquals("30000", setup.wait?.data?.get(WaitKeys.CI_WAITED_MS), "the 90 seconds without an answer never count")
            tick(orchestrator, after = 30_000)
            assertEquals("60000", setup.wait?.data?.get(WaitKeys.CI_WAITED_MS))
            assertTrue(setup.all(CiFinished.name).isEmpty())
            tick(orchestrator, after = 60_000)

            assertEquals("timeout", setup.all(CiFinished.name).single()["outcome"])
            assertContains(setup.all(DeliveryFailed.name).single()["reason"].orEmpty(), "Actions runs still pending after 2 minutes")
            assertEquals(WaitReason.Approval, setup.wait?.reason)
            assertFalse(setup.wait!!.approved)
            assertEquals(emptyList(), setup.remote.merges)
        }

    @Test
    fun `a 403 on the runs listing gives delivery_failed naming Actions read`() =
        runTest {
            val setup = Setup()
            setup.remote.answers +=
                Result.failure(
                    DeliveryError.Rejected(
                        "Cannot list the Actions runs on acme/widgets: The tracker answered 403: the token needs the Actions: read " +
                            "permission to list the Actions runs of acme/widgets. GitHub said: Resource not accessible",
                    ),
                )
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 30_000)

            val failed = setup.all(DeliveryFailed.name).single()
            assertEquals("ci", failed["step"])
            assertContains(failed["reason"].orEmpty(), "Actions: read")
            assertContains(
                setup.tracker.comments
                    .last()
                    .second.text,
                "Actions: read",
            )
            assertTrue(setup.all(CiFinished.name).isEmpty())
            assertEquals(WaitReason.Approval, setup.wait?.reason)
        }

    @Test
    fun `polls are at most one per 30 seconds, whatever the ticks`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += pending
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)

            tick(orchestrator, after = 10_000)
            tick(orchestrator, after = 10_000)
            assertEquals(emptyList(), setup.remote.listed, "30 seconds after the push at the earliest")
            tick(orchestrator, after = 10_000)
            tick(orchestrator, after = 10_000)
            tick(orchestrator, after = 10_000)

            assertEquals(1, setup.remote.listed.size)
            tick(orchestrator, after = 10_000)
            assertEquals(2, setup.remote.listed.size)
        }

    @Test
    fun `phase ci and ci_started_at survive a restart, and the down time never counts`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += listOf(pending, pending, green)
            val first = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(first)
            tick(first, after = 30_000)
            val stored = assertNotNull(setup.wait)
            assertEquals("ci", stored.data[WaitKeys.PHASE])

            val second = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(second, after = 600_000)

            val restored = assertNotNull(setup.wait)
            assertEquals("ci", restored.data[WaitKeys.PHASE])
            assertEquals(stored.data[WaitKeys.CI_STARTED_AT], restored.data[WaitKeys.CI_STARTED_AT])
            assertEquals("30000", restored.data[WaitKeys.CI_WAITED_MS], "ten minutes down count for nothing")
            assertTrue(setup.all(CiFinished.name).isEmpty())

            tick(second, after = 30_000)
            assertEquals(listOf(setup.verified), setup.remote.merges)
        }

    @Test
    fun `a moved base at the merge starts a new round of the check and of the CI wait`() =
        runTest {
            val setup = Setup()
            val moved = baseCommit()
            setup.remote.bases += moved
            setup.remote.tip = { moved }
            setup.remote.answers += green
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)
            val firstStart = setup.wait?.data?.get(WaitKeys.CI_STARTED_AT)

            tick(orchestrator, after = 30_000)

            val wait = assertNotNull(setup.wait)
            assertEquals("ci", wait.data[WaitKeys.PHASE], "checked again on the moved base, then waiting for CI again")
            assertEquals("2", wait.data[WaitKeys.ROUND])
            assertEquals(moved, wait.data[WaitKeys.BASE_SHA])
            val merged = assertNotNull(wait.data[WaitKeys.MERGED_HEAD])
            assertTrue(merged != setup.verified)
            assertTrue(wait.data[WaitKeys.CI_STARTED_AT] != firstStart, "the CI wait starts anew")
            assertEquals("0", wait.data[WaitKeys.CI_WAITED_MS])
            assertEquals(listOf(setup.verified, merged), setup.all(CiWaiting.name).map { it["sha"] })
            assertEquals(emptyList(), setup.remote.merges)

            tick(orchestrator, after = 30_000)
            assertEquals(listOf(merged), setup.remote.merges)
        }

    @Test
    fun `a new approval after a refusal starts the CI wait anew`() =
        runTest {
            val setup = Setup()
            setup.remote.answers += listOf(red, green)
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)
            tick(orchestrator)
            tick(orchestrator, after = 30_000)
            val refused = assertNotNull(setup.wait)
            assertEquals(WaitReason.Approval, refused.reason)
            setup.waiting.records[PROJECT.value to "id-t-1"] =
                refused.copy(
                    data =
                        refused.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()),
                )

            tick(orchestrator, after = 30_000)

            val again = assertNotNull(setup.wait)
            assertEquals("ci", again.data[WaitKeys.PHASE])
            assertEquals("0", again.data[WaitKeys.CI_WAITED_MS])
            assertEquals(2, setup.all(CiWaiting.name).size)
            tick(orchestrator, after = 30_000)
            assertEquals(1, setup.remote.merges.size)
        }

    @Test
    fun `under ci none nothing is listed and the merge follows the check, as in m2-26`() =
        runTest {
            val setup = Setup(workflow(MergeConfig(MergeMethod.Merge, CiMode.None)))
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink, setup.workflow)

            tick(orchestrator)

            assertEquals(listOf(setup.verified), setup.remote.merges)
            assertEquals(emptyList(), setup.remote.listed)
            assertTrue(setup.all(CiWaiting.name).isEmpty())
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }

    /** The approved `Waiting(merge)` of [identifier] on pull request [number] in phase `check`, verified on [head]. */
    private fun mergeWait(
        identifier: String,
        number: Long,
        head: String,
        since: Instant,
    ): Wait.Merge {
        val approval = approvedWait(MergeCheck.cleanFingerprint(head)).copy(issueId = "id-$identifier", identifier = identifier)
        return (Wait.of(approval) as Wait.Approval).merging(number, "https://github.com/acme/widgets/pull/$number", since)
    }

    private fun Setup.store(vararg waits: Wait) {
        waiting.records.clear()
        waits.forEach { waiting.records[PROJECT.value to it.issueId] = it.toRecord() }
    }

    private fun Setup.record(identifier: String): WaitingIssue = waiting.records.getValue(PROJECT.value to "id-$identifier")

    @Test
    fun `a merge wait behind the head is never polled while the head waits for its CI`() =
        runTest {
            val setup = Setup(identifiers = listOf("t-1", "t-2"))
            val second = secondWorktree()
            setup.remote.pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)
            setup.remote.pulls["loop/t-2"] = PullRequest(8, "https://github.com/acme/widgets/pull/8", created = false)
            setup.store(mergeWait("t-1", 7, setup.verified, FIXED_NOW), mergeWait("t-2", 8, second, FIXED_NOW + 1.seconds))
            setup.remote.answers += pending
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)

            tick(orchestrator)
            tick(orchestrator, after = 1_000)
            tick(orchestrator, after = 30_000)
            tick(orchestrator, after = 30_000)

            assertEquals("ci", setup.record("t-1").data[WaitKeys.PHASE])
            assertEquals("check", setup.record("t-2").data[WaitKeys.PHASE])
            assertEquals(listOf(setup.verified, setup.verified), setup.remote.listed, "only the head is polled")
            assertEquals(listOf("t-1"), setup.sink.events.filter { it.kind == CiWaiting.name }.map { it.issueIdentifier })
        }

    @Test
    fun `at startup merge waits behind the head found in phase ci or merge go back to phase check at their round, with merge_requeued`() =
        runTest {
            val setup = Setup(identifiers = listOf("t-1", "t-2", "t-3"))
            val second = secondWorktree()
            val base = git(project, "rev-parse", "main")
            val pushedAt = FIXED_NOW + 5.seconds
            val head = mergeWait("t-1", 7, setup.verified, FIXED_NOW).checked(base, setup.verified).waitingForCi(pushedAt)
            val behindInCi =
                mergeWait("t-2", 8, second, FIXED_NOW + 1.seconds)
                    .again(2)
                    .checked(base, second)
                    .waitingForCi(pushedAt)
                    .waited(30_000)
            val behindInMerge = mergeWait("t-3", 9, "3".repeat(40), FIXED_NOW + 2.seconds).checked(base, "3".repeat(40))
            setup.store(behindInMerge, behindInCi, head)
            setup.remote.pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)
            setup.remote.answers += pending
            val orchestrator = orchestrator(setup.tracker, setup.remote, setup.waiting, setup.sink)

            tick(orchestrator)

            val demoted = setup.record("t-2")
            assertEquals("check", demoted.data[WaitKeys.PHASE])
            assertEquals("2", demoted.data[WaitKeys.ROUND])
            assertNull(demoted.data[WaitKeys.CI_STARTED_AT])
            assertNull(demoted.data[WaitKeys.CI_WAITED_MS])
            assertEquals(FIXED_NOW + 1.seconds, demoted.since, "its place in the queue is kept")
            assertEquals("check", setup.record("t-3").data[WaitKeys.PHASE])
            assertEquals("1", setup.record("t-3").data[WaitKeys.ROUND])
            assertEquals(head.toRecord(), setup.record("t-1"), "the head keeps its phase")
            val requeued = setup.sink.events.filter { it.kind == "merge_requeued" }
            assertEquals(listOf("t-2", "t-3"), requeued.map { it.issueIdentifier })
            assertEquals(listOf("ci", "merge"), requeued.map { it.data["phase"] })
            assertEquals(listOf("2", "1"), requeued.map { it.data["round"] })
            assertEquals(listOf("t-1", "t-1"), requeued.map { it.data["head_issue"] })
            assertTrue(setup.remote.listed.none { it == second }, "a demoted wait is not polled")
        }
}
