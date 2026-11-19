package loop.core.orchestrator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import loop.api.AttemptId
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
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
import loop.core.verify.CoreCheckRunner
import loop.core.verify.Fingerprinter
import loop.core.verify.UncommittedFiles
import loop.core.verify.WorkspaceFingerprint
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m2-25: pull request delivery in the Orchestrator. Under `human` the tick that sees an approved `Waiting(approval)`
 * delivers it from the facts the wait holds, so a delivery after a restart builds the full body; under `auto` the drain
 * delivers at once. A step that can be tried again keeps the wait for the next tick; a permanent failure records
 * `delivery_failed` and clears the approval.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestDeliveryOrchestratorTest {
    private val root: Path = createTempDirectory("loop-pr-delivery").toRealPath()
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val empty = WorkspaceFingerprint.EMPTY_HASH
    private val clean = "git:$sha:$empty:$empty"
    private val checkLines =
        "- check 0, ./gradlew test: passed with exit code 0 in 1.2s, evidence dead1.evidence-1.log\n- hidden check 1: passed"

    private fun workflow(
        policy: DeliveryPolicy = DeliveryPolicy.Human,
        pullRequest: PullRequestConfig? = PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN"),
        template: String = "Work on {{ issue.identifier }}",
        checks: List<CheckConfig> = listOf(CheckConfig.Command("./gradlew test")),
        holdout: List<CheckConfig> = emptyList(),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds =
                        KindsConfig(
                            KindConfig(checks, holdout = holdout, delivery = DeliveryConfig(policy, pullRequest)),
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

    /** A remote that keeps one pull request per branch, like GitHub: the first delivery opens it, later ones update it. */
    private class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<Pair<String, String>>()
        val bodies = mutableListOf<String>()
        val open = mutableMapOf<String, PullRequest>()
        var pushFailure: DeliveryError? = null
        var pullRequestFailure: DeliveryError? = null

        /** What [fetchBase] answers (bl-32), refused by default like an executor that cannot fetch. */
        var fetched: Result<String> = Result.failure(DeliveryError.Rejected("this delivery cannot fetch the base"))
        var fetches = 0

        override suspend fun fetchBase(): Result<String> {
            fetches++
            return fetched
        }

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            pushFailure?.let { return Result.failure(it) }
            pushes += sha to branch
            return Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = Result.success(open[branch]?.copy(created = false))

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            pullRequestFailure?.let { return Result.failure(it) }
            bodies += body
            val opened = PullRequest(open.size + 7L, "https://github.com/acme/widgets/pull/${open.size + 7}", created = true)
            open[branch] = opened
            return Result.success(opened)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> {
            pullRequestFailure?.let { return Result.failure(it) }
            bodies += body
            return Result.success(open.values.single { it.number == number }.copy(created = false))
        }
    }

    private fun approvedWait(
        fingerprint: String = clean,
        extra: Map<String, String> = emptyMap(),
    ) = WaitingIssue(
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
                WaitKeys.CHECKS to checkLines,
                WaitKeys.JUDGMENT_CALLS to "1",
                WaitingIssue.APPROVED_BY to "soule",
                WaitingIssue.APPROVED_AT to FIXED_NOW.toString(),
            ) + extra,
    )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        remote: DeliveryExecutor?,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        runner: FakeRunner = FakeRunner(),
        checks: CheckRunner = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1200) },
        fingerprinter: Fingerprinter = Fingerprinter { Result.success(clean) },
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
            checkRunnerFor = { checks },
            waiting = waiting,
            fingerprinter = fingerprinter,
            deliveryFor = { remote },
        )
    }

    private fun FakeSink.kinds(): List<String> = events.map { it.kind }.filter { it in DELIVERY_EVENTS }

    private fun FakeSink.data(kind: String): Map<String, String> = events.single { it.kind == kind }.data

    @Test
    fun `an approved wait is pushed, opened, commented and moved on the next tick, with the full body from the wait`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()

            // A new orchestrator, as after a restart: the body comes from the wait alone.
            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(listOf(sha to "loop/t-1"), remote.pushes)
            val body = remote.bodies.single()
            assertContains(body, "Verdict: verified")
            assertContains(body, "Fingerprint: $clean")
            assertContains(body, checkLines)
            assertContains(body, "Judgment calls left to a human: 1")
            assertTrue(body.endsWith("Refs t-1"), body)
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented", "issue_moved"), sink.kinds())
            assertEquals(AttemptId("dead1"), sink.events.single { it.kind == "pull_request_opened" }.attemptId)
            assertEquals(
                "Loop opened pull request #7 for this issue: https://github.com/acme/widgets/pull/7",
                tracker.comments
                    .single()
                    .second.text,
            )
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertContains(sink.data("issue_moved"), "approved")
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `without pull_request an approved wait is moved as in m2-24, nothing is pushed`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues(approvedWait())

            orchestrator(tracker, remote, waiting, FakeSink(), workflow(pullRequest = null)).tick()

            assertEquals(emptyList(), remote.pushes)
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
        }

    @Test
    fun `a 403 on the pull request records delivery_failed, clears the approval and keeps the wait`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { pullRequestFailure = DeliveryError.Rejected("GitHub answered 403: Resource not accessible") }
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink)

            orchestrator.tick()

            assertEquals(listOf("branch_pushed", "delivery_failed"), sink.kinds())
            assertEquals("pull_request", sink.data("delivery_failed")["step"])
            val kept = assertNotNull(waiting.records.values.singleOrNull())
            assertFalse(kept.approved, "the approval is cleared")
            assertEquals(checkLines, kept.data[WaitKeys.CHECKS])
            assertEquals(emptyList(), tracker.moves)
            assertEquals("todo", tracker.issues.single().state)

            // Nothing is tried again until a new approval.
            orchestrator.tick()
            assertEquals(1, remote.pushes.size)
            assertEquals(1, sink.kinds().count { it == "delivery_failed" })
        }

    @Test
    fun `a rejected push records delivery_failed, clears the approval and keeps the wait`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { pushFailure = DeliveryError.Rejected("the push was rejected, not a fast forward") }
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(listOf("delivery_failed"), sink.kinds())
            assertEquals("push", sink.data("delivery_failed")["step"])
            assertFalse(
                waiting.records.values
                    .single()
                    .approved,
            )
            assertEquals(emptyList(), remote.bodies)
        }

    @Test
    fun `uncommitted verified changes deliver nothing and record delivery_failed`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues(approvedWait(fingerprint = "git:$sha:00000000deadbeef:$empty"))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(listOf("delivery_failed"), sink.kinds())
            assertEquals("fingerprint", sink.data("delivery_failed")["step"])
            assertEquals(emptyList(), remote.pushes)
            assertFalse(
                waiting.records.values
                    .single()
                    .approved,
            )
        }

    @Test
    fun `a network error keeps the approved wait and the next tick delivers`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { pushFailure = DeliveryError.Unavailable("could not reach the remote") }
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink)

            orchestrator.tick()

            assertEquals(emptyList(), sink.kinds(), "a retry records no delivery_failed")
            assertTrue(
                waiting.records.values
                    .single()
                    .approved,
            )

            remote.pushFailure = null
            orchestrator.tick()

            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented", "issue_moved"), sink.kinds())
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `a failed move after the pull request opened opens no second one and comments once`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1"))).apply { moveFailure = TrackerError.Status(503, "unavailable") }
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink)

            orchestrator.tick()

            val kept = waiting.records.values.single()
            assertTrue(kept.approved)
            assertEquals("https://github.com/acme/widgets/pull/7", kept.data[WaitKeys.PULL_REQUEST])

            tracker.moveFailure = null
            orchestrator.tick()

            assertEquals(1, remote.open.size)
            assertEquals(
                listOf("branch_pushed", "pull_request_opened", "issue_commented", "branch_pushed", "pull_request_updated", "issue_moved"),
                sink.kinds(),
            )
            assertEquals(1, tracker.comments.size)
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `a move refused for good after the pull request opened clears the approval`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1"))).apply { moveFailure = TrackerError.Rejected("no such state") }
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()

            orchestrator(tracker, FakeRemote(), waiting, sink).tick()

            assertEquals("move", sink.data("delivery_failed")["step"])
            assertFalse(
                waiting.records.values
                    .single()
                    .approved,
            )
        }

    @Test
    fun `a pull request already merged at the verified commit counts as delivered, the comment and the move still run`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val merged = PullRequest(9, "https://github.com/acme/widgets/pull/9", false, PullRequestState.Merged, sha)
            val remote = FakeRemote().apply { open["loop/t-1"] = merged }
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyList(), remote.pushes)
            assertEquals(emptyList(), remote.bodies)
            assertEquals(listOf("pull_request_found", "issue_commented", "issue_moved"), sink.kinds())
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `a host with no executor fails the delivery for good and keeps the wait`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues(approvedWait())
            val sink = FakeSink()

            orchestrator(tracker, null, waiting, sink).tick()

            assertEquals("pull_request", sink.data("delivery_failed")["step"])
            assertFalse(
                waiting.records.values
                    .single()
                    .approved,
            )
        }

    /** A sessionless runner whose one turn queues a move to review, the agent's result. */
    private fun submitting(): FakeRunner =
        FakeRunner().apply {
            onCall =
                { ctx -> ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review")) }
        }

    @Test
    fun `under auto the drain delivers at once and moves the issue`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink, workflow(DeliveryPolicy.Auto), submitting()).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(sha to "loop/t-1"), remote.pushes)
            assertContains(remote.bodies.single(), "- check 0, ./gradlew test: passed with exit code 0 in 1.2s")
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented", "issue_moved"), sink.kinds())
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `under auto a permanent failure falls back to Waiting(approval) with the facts for a later delivery`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { pushFailure = DeliveryError.Rejected("the push was rejected, not a fast forward") }
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink, workflow(DeliveryPolicy.Auto), submitting()).tick()
            testScheduler.advanceUntilIdle()

            // The fallback is the human policy's wait, with its evidence summary on the issue.
            assertEquals(listOf("delivery_failed", "issue_commented", "approval_requested"), sink.kinds())
            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Approval, wait.reason)
            assertFalse(wait.approved)
            assertContains(wait.data[WaitKeys.CHECKS].orEmpty(), "- check 0, ./gradlew test: passed")
            assertEquals("0", wait.data[WaitKeys.JUDGMENT_CALLS])
            assertEquals(emptyList(), tracker.moves)
        }

    /** A work tree whose fingerprint has an uncommitted change and an untracked file, as after a regenerating build. */
    private val dirty =
        object : Fingerprinter {
            override fun of(workspace: Path): Result<String> = Result.success("git:$sha:00000000deadbeef:11111111cafef00d")

            override fun uncommitted(workspace: Path): Result<UncommittedFiles> =
                Result.success(UncommittedFiles(listOf("Config/Version.xcconfig"), listOf("App.xcodeproj/new.pbxproj")))
        }

    @Test
    fun `with pull_request a verified but uncommitted work tree is refuted, naming the files, and the next attempt gets the finding`() =
        runTest {
            for (policy in listOf(DeliveryPolicy.Human, DeliveryPolicy.Auto)) {
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote = FakeRemote()
                val waiting = FakeWaitingIssues()
                val sink = FakeSink()
                val runner = submitting()
                val template = "Work on {{ issue.identifier }}{% if loop.verify_findings %}\n{{ loop.verify_findings }}{% endif %}"
                val orchestrator =
                    orchestrator(tracker, remote, waiting, sink, workflow(policy, template = template), runner, fingerprinter = dirty)

                val first = orchestrator.tick().attempts.single()
                advanceTimeBy(10_000)
                orchestrator.tick()

                assertEquals("refuted", first.reason, "$policy")
                assertEquals(listOf("refuted", "refuted"), sink.events.filter { it.kind == "verdict" }.map { it.data["verdict"] })
                assertEquals("2", sink.events.first { it.kind == "verdict" }.data["uncommitted"])
                assertEquals(emptyList(), sink.kinds(), "no approval, no delivery, no delivery_failed under $policy")
                assertEquals(emptyMap(), waiting.records)
                assertEquals(emptyList(), remote.pushes)
                assertEquals(emptyList(), tracker.moves)
                assertEquals(emptyList(), tracker.comments)
                val next = runner.calls[1].second
                assertContains(next, "Work on t-1\nYour last result was refuted: every check passed, but the work tree holds files")
                assertContains(next, "Uncommitted changes:\n- Config/Version.xcconfig")
                assertContains(next, "Untracked files:\n- App.xcodeproj/new.pbxproj")
            }
        }

    @Test
    fun `without pull_request an uncommitted work tree is verified and waits for approval as in m2-24`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()

            orchestrator(tracker, null, waiting, sink, workflow(pullRequest = null), submitting(), fingerprinter = dirty).tick()
            testScheduler.advanceUntilIdle()

            assertEquals("verified", sink.events.single { it.kind == "verdict" }.data["verdict"])
            assertEquals(listOf("issue_commented", "approval_requested"), sink.kinds())
            assertEquals(
                WaitReason.Approval,
                waiting.records.values
                    .single()
                    .reason,
            )
        }

    @Test
    fun `under auto a network error leaves an approved wait that the next tick delivers`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { pullRequestFailure = DeliveryError.Unavailable("GitHub did not answer") }
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(DeliveryPolicy.Auto), submitting())

            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            val wait = waiting.records.values.single()
            assertEquals("auto", wait.data[WaitingIssue.APPROVED_BY])
            assertNull(wait.data[WaitKeys.PULL_REQUEST])

            remote.pullRequestFailure = null
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals(1, remote.open.size)
            assertEquals(emptyMap(), waiting.records)
        }

    /** Runs git in [dir] as the agent would; fails the test on a non zero exit. Returns stdout, trimmed. */
    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Loop agent", "-c", "user.email=agent@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    /** The real command checks, on a real dispatcher so their timeout runs in real time, not the test's virtual time. */
    private val realChecks = CheckRunner { check, cwd, env -> withContext(Dispatchers.IO) { CoreCheckRunner().run(check, cwd, env) } }

    @Test
    fun `with pull_request the files a real check writes are restored, so the next verification passes and delivers`() =
        runTest {
            // The workspace is a git work tree with a tracked file a check regenerates, like Config/Version.xcconfig.
            val workspace = createDirectories(root.resolve("workspaces/t-1"))
            git(workspace, "init", "-q", "--initial-branch=loop/t-1")
            workspace.resolve(".gitignore").writeText("build/\n")
            workspace.resolve("version.txt").writeText("v1\n")
            git(workspace, "add", ".")
            git(workspace, "commit", "-q", "-m", "first")
            val checks =
                listOf(
                    CheckConfig.Command(
                        "printf 'regenerated\\n' > version.txt && echo log > generated.log && mkdir -p build && echo x > build/out",
                    ),
                    CheckConfig.Command("test -f fix.txt"),
                )
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote()
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val statusAtStart = mutableListOf<String>()
            val runner =
                submitting().apply {
                    val queue = onCall
                    onCall = { ctx ->
                        statusAtStart += git(ctx.workspace, "status", "--porcelain")
                        if (statusAtStart.size == 1) {
                            ctx.workspace.resolve("work.txt").writeText("work\n")
                            git(ctx.workspace, "add", "work.txt")
                            git(ctx.workspace, "commit", "-q", "-m", "work")
                        } else {
                            ctx.workspace.resolve("fix.txt").writeText("fix\n")
                            git(ctx.workspace, "add", "fix.txt")
                            git(ctx.workspace, "commit", "-q", "-m", "fix")
                        }
                        queue(ctx)
                    }
                }
            val orchestrator =
                orchestrator(
                    tracker,
                    remote,
                    waiting,
                    sink,
                    workflow(DeliveryPolicy.Auto, checks = checks),
                    runner,
                    checks = realChecks,
                    fingerprinter = WorkspaceFingerprint,
                )

            orchestrator.tick()
            testScheduler.advanceUntilIdle()
            advanceTimeBy(10_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            val verdicts = sink.events.filter { it.kind == "verdict" }.map { it.data }
            assertEquals(listOf("refuted", "verified"), verdicts.map { it["verdict"] }, verdicts.toString())
            assertEquals(listOf("", ""), statusAtStart, "each attempt starts on a clean tree")
            val head = git(workspace, "rev-parse", "HEAD")
            assertEquals(listOf(head to "loop/t-1"), remote.pushes)
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals("v1\n", workspace.resolve("version.txt").readText())
            assertFalse(workspace.resolve("generated.log").exists())
            assertTrue(workspace.resolve("build/out").exists(), "an ignored file a check wrote stays")
        }

    @Test
    fun `without pull_request the files a check writes stay, as in m2-24`() =
        runTest {
            val workspace = createDirectories(root.resolve("workspaces/t-1"))
            git(workspace, "init", "-q", "--initial-branch=loop/t-1")
            workspace.resolve("version.txt").writeText("v1\n")
            git(workspace, "add", ".")
            git(workspace, "commit", "-q", "-m", "first")
            val checks = listOf(CheckConfig.Command("printf 'regenerated\\n' > version.txt && echo log > generated.log"))
            val tracker = FakeTracker(listOf(issue("t-1")))
            val sink = FakeSink()

            orchestrator(
                tracker,
                null,
                FakeWaitingIssues(),
                sink,
                workflow(DeliveryPolicy.Auto, pullRequest = null, checks = checks),
                submitting(),
                checks = realChecks,
                fingerprinter = WorkspaceFingerprint,
            ).tick()
            testScheduler.advanceUntilIdle()

            val verdicts = sink.events.filter { it.kind == "verdict" }.map { it.data }
            assertEquals(listOf("verified"), verdicts.map { it["verdict"] }, verdicts.toString())
            assertEquals("regenerated\n", workspace.resolve("version.txt").readText())
            assertTrue(workspace.resolve("generated.log").exists())
        }

    // bl-32: every diff_policy of a pull request kind is judged against a base commit fetched host side.

    private val testFile = "src/test/kotlin/calc/CalcTest.kt"

    private val guard = CheckConfig.DiffPolicy(listOf("protected/**"), deny = listOf(DenyGuard.DELETED_TESTS))

    /** A git workspace on `loop/t-1` cut from `main`, which holds a protected file and a test. Returns `main`'s commit. */
    private fun guardedWorkspace(): String {
        val workspace = createDirectories(root.resolve("workspaces/t-1"))
        git(workspace, "init", "-q", "--initial-branch=main")
        createDirectories(workspace.resolve("protected")).resolve("config.txt").writeText("config\n")
        createDirectories(workspace.resolve(testFile).parent).resolve("CalcTest.kt").writeText(
            "class CalcTest {\n    @Test\n    fun adds() {\n        assertEquals(4, 2 + 2)\n    }\n}\n",
        )
        git(workspace, "add", ".")
        git(workspace, "commit", "-q", "-m", "first")
        val main = git(workspace, "rev-parse", "HEAD")
        git(workspace, "checkout", "-q", "-b", "loop/t-1")
        return main
    }

    /**
     * The agent's shortcut in [workspace]: edits the protected file, deletes the test and commits, then with [moveBase]
     * moves local `main` and `refs/loop/base/main` to its own head, which empties a diff against either.
     */
    private fun shortcut(
        workspace: Path,
        moveBase: Boolean,
    ) {
        workspace.resolve("protected/config.txt").writeText("changed\n")
        git(workspace, "rm", "-q", testFile)
        git(workspace, "commit", "-q", "-am", "make it pass")
        if (moveBase) {
            git(workspace, "-C", ".", "update-ref", "refs/heads/main", "HEAD")
            git(workspace, "update-ref", "refs/loop/base/main", "HEAD")
        }
    }

    /** A runner whose one turn applies [shortcut], then submits the result. */
    private fun shortcutting(moveBase: Boolean): FakeRunner =
        submitting().apply {
            val queue = onCall
            onCall = { ctx ->
                shortcut(ctx.workspace, moveBase)
                queue(ctx)
            }
        }

    /** The real checks, recording each check it ran with its result. */
    private class Recording : CheckRunner {
        val runs = mutableListOf<Pair<CheckConfig, CheckResult>>()

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult = withContext(Dispatchers.IO) { CoreCheckRunner().run(check, cwd, env) }.also { runs += check to it }

        val diffPolicies: List<Pair<CheckConfig.DiffPolicy, CheckResult>>
            get() = runs.mapNotNull { (check, result) -> (check as? CheckConfig.DiffPolicy)?.let { it to result } }
    }

    private fun FakeSink.verdicts(): List<String> = events.filter { it.kind == "verdict" }.map { it.data["verdict"].orEmpty() }

    @Test
    fun `with pull_request every diff_policy is based on a fetched commit, so moving main and refs loop base hides nothing`() =
        runTest {
            for ((checks, holdout) in listOf(listOf(guard) to emptyList(), listOf(CheckConfig.Command("true")) to listOf(guard))) {
                root.resolve("workspaces").toFile().deleteRecursively()
                val main = guardedWorkspace()
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote = FakeRemote().apply { fetched = Result.success(main) }
                val waiting = FakeWaitingIssues()
                val sink = FakeSink()
                val recording = Recording()

                orchestrator(
                    tracker,
                    remote,
                    waiting,
                    sink,
                    workflow(checks = checks, holdout = holdout),
                    shortcutting(moveBase = true),
                    checks = recording,
                    fingerprinter = WorkspaceFingerprint,
                ).tick()
                testScheduler.advanceUntilIdle()

                val workspace = root.resolve("workspaces/t-1")
                val head = git(workspace, "rev-parse", "HEAD")
                assertEquals(head, git(workspace, "rev-parse", "main"), "the agent moved main")
                assertEquals(head, git(workspace, "rev-parse", "refs/loop/base/main"), "the agent moved refs/loop/base/main")
                // One fetch before the attempt's hooks (bl-59), one for the verification's diff_policy (bl-32).
                assertEquals(2, remote.fetches)
                assertEquals(
                    List(2) { mapOf("sha" to main, "base" to "main") },
                    sink.events.filter { it.kind == "base_fetched" }.map { it.data },
                )
                assertEquals(listOf("refuted"), sink.verdicts(), "holdout: ${holdout.isNotEmpty()}")
                val guards = recording.diffPolicies
                assertTrue(guards.isNotEmpty())
                guards.forEach { (check, result) ->
                    assertEquals(main, check.base)
                    assertFalse(result.passed)
                    assertContains(result.output, "modified protected/config.txt (protected by protected/**)")
                    assertContains(result.output, "deleted_tests: deleted test file $testFile")
                }
                assertEquals(emptyMap(), waiting.records, "a refuted result waits for no approval")
                assertTrue(sink.events.none { it.kind == "diff_base_unpinned" })
            }
        }

    @Test
    fun `a verification restored from Waiting(verify) bases diff_policy on a fetched commit too`() =
        runTest {
            val main = guardedWorkspace()
            shortcut(root.resolve("workspaces/t-1"), moveBase = true)
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { fetched = Result.success(main) }
            val wait =
                WaitingIssue(
                    PROJECT,
                    "id-t-1",
                    "t-1",
                    WaitReason.Verify,
                    AttemptId("dead1"),
                    RunId("r0"),
                    FIXED_NOW,
                    mapOf(WaitKeys.TO to "review"),
                )
            val sink = FakeSink()
            val recording = Recording()

            orchestrator(
                tracker,
                remote,
                FakeWaitingIssues(wait),
                sink,
                workflow(checks = listOf(guard)),
                checks = recording,
                fingerprinter = WorkspaceFingerprint,
            ).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(1, remote.fetches)
            assertEquals(listOf("refuted"), sink.verdicts())
            val guards = recording.diffPolicies
            assertTrue(guards.isNotEmpty())
            guards.forEach { (check, result) ->
                assertEquals(main, check.base)
                assertContains(result.output, "deleted_tests: deleted test file $testFile")
            }
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a failed fetch falls back to guards base and records diff_base_unpinned with the redacted reason`() =
        runTest {
            guardedWorkspace()
            val token = "ghp_" + "Xy7".repeat(12)
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { fetched = Result.failure(DeliveryError.Unavailable("cannot reach GitHub with $token")) }
            val sink = FakeSink()
            val recording = Recording()

            orchestrator(
                tracker,
                remote,
                FakeWaitingIssues(),
                sink,
                workflow(checks = listOf(guard)),
                shortcutting(moveBase = false),
                checks = recording,
                fingerprinter = WorkspaceFingerprint,
            ).tick()
            testScheduler.advanceUntilIdle()

            // One fetch before the attempt's hooks (bl-59), one for the verification's diff_policy (bl-32).
            assertEquals(2, remote.fetches)
            assertContains(sink.data("base_fetch_failed")["reason"].orEmpty(), "cannot reach GitHub")
            val unpinned = sink.data("diff_base_unpinned")
            assertContains(unpinned["reason"].orEmpty(), "cannot reach GitHub")
            assertFalse(token in sink.events.toString(), sink.events.toString())
            assertTrue(sink.events.none { it.kind == "base_fetched" })
            assertEquals(listOf("refuted"), sink.verdicts(), "the check still runs, against guards.base")
            recording.diffPolicies.forEach { (check, result) ->
                assertEquals("main", check.base)
                assertContains(result.output, "modified protected/config.txt")
            }
        }

    @Test
    fun `a kind without pull_request never fetches and bases diff_policy on guards base`() =
        runTest {
            guardedWorkspace()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { fetched = Result.success("0".repeat(40)) }
            val sink = FakeSink()
            val recording = Recording()

            orchestrator(
                tracker,
                remote,
                FakeWaitingIssues(),
                sink,
                workflow(pullRequest = null, checks = listOf(guard)),
                shortcutting(moveBase = false),
                checks = recording,
                fingerprinter = WorkspaceFingerprint,
            ).tick()
            testScheduler.advanceUntilIdle()

            assertEquals(0, remote.fetches)
            assertTrue(sink.events.none { it.kind == "base_fetched" || it.kind == "diff_base_unpinned" })
            assertEquals(listOf("refuted"), sink.verdicts())
            recording.diffPolicies.forEach { (check, _) -> assertEquals("main", check.base) }
        }

    private companion object {
        val PROJECT = ProjectId("p1")

        val DELIVERY_EVENTS =
            setOf(
                "pull_request_found",
                "branch_pushed",
                "pull_request_opened",
                "pull_request_updated",
                "issue_commented",
                "delivery_failed",
                "issue_moved",
                "approval_requested",
            )
    }
}
