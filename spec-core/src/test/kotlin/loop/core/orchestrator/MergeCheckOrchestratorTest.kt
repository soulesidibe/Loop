package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import loop.core.events.BaseFetched
import loop.core.events.MergeCheckFinished
import loop.core.events.MergeCheckStarted
import loop.core.events.PullRequestMerged
import loop.core.log.KeyValueLog
import loop.core.machine.MachineLimits
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.CoreCheckRunner
import loop.core.verify.WorkspaceFingerprinter
import loop.core.verify.WorkspaceGit
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m2-26: the merge check and the merge of an approved pull request. With `merge`, an approved delivery stops after the
 * comment and waits as `Waiting(merge)`; the merge check fetches the base, merges it in the issue's own workspace,
 * reruns every check with `diff_policy` on the fetched base, pushes the merged head and merges with a sha guard. The
 * project repository and its worktree are real temporary repositories; the remote is a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergeCheckOrchestratorTest {
    private val root: Path = createTempDirectory("loop-merge-check").toRealPath()
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

    private var upstream: String? = null

    /** A commit on the base, made on `upstream` so local `main` stays behind, as objects a fetch landed. */
    private fun baseCommit(
        file: String = "c.txt",
        text: String = "base\n",
    ): String {
        git(project, "checkout", "-q", "-B", "upstream", upstream ?: "main")
        val sha = commit(project, file, text)
        git(project, "checkout", "-q", "main")
        upstream = sha
        return sha
    }

    private fun clean(sha: String) = MergeCheck.cleanFingerprint(sha)

    private fun workflow(
        checks: List<CheckConfig> = listOf(CheckConfig.Command("./gradlew test")),
        holdout: List<CheckConfig> = emptyList(),
        method: MergeMethod = MergeMethod.Merge,
        maxAgents: Int = 10,
        tracker: String = "files",
        template: String = "Work on {{ issue.identifier }}. {{ loop.verify_findings }}",
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1, maxConcurrentAgents = maxAgents),
                    kinds =
                        KindsConfig(
                            KindConfig(
                                checks,
                                holdout = holdout,
                                delivery =
                                    DeliveryConfig(
                                        DeliveryPolicy.Human,
                                        PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", merge = MergeConfig(method, CiMode.None)),
                                        onDeliveredMoveTo = "done",
                                    ),
                            ),
                        ),
                ),
            path = project.resolve("WORKFLOW.md"),
            trackerKind = tracker,
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled", "closed"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = template,
        )

    /** A GitHub like remote: pushes need the local branch at the commit, a merge marks the pull request merged at its head. */
    private inner class FakeRemote : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val pushes = mutableListOf<Pair<String, String>>()
        var opened = 0
        val merges = mutableListOf<Triple<Long, String, MergeMethod>>()
        val pulls = mutableMapOf<String, PullRequest>()
        var bases = ArrayDeque<String>()
        var lastBase: String? = null
        var tip: (() -> String)? = null
        var fetchFailure: DeliveryError? = null
        var mergeFailure: DeliveryError? = null

        /** Holds the first fetch only, the merge check's: a later one, like an attempt's (bl-59), goes through. */
        var fetchGate: CompletableDeferred<Unit>? = null
        val fetched = mutableListOf<String>()

        /** Writes `refs/loop/base/main` in the project at the fetched commit, as the real fetch does (send back 2). */
        var writeBaseRef = false

        /** How many pull request reads after a push still show the old head, GitHub's delayed head update (send back 5). */
        var headLag = 0
        private var lagReads = 0
        private var staleHead: String? = null
        var finds = 0

        /** Refusals the next merges return, in order, before [mergeFailure] (send back 5). */
        val mergeFailures = ArrayDeque<DeliveryError>()

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            val local = git(project, "rev-parse", "refs/heads/$branch")
            if (local != sha) return Result.failure(DeliveryError.Rejected("the branch $branch is at $local, not $sha"))
            pushes += sha to branch
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
                return Result.success(pulls[branch]?.copy(created = false, headSha = staleHead))
            }
            return Result.success(pulls[branch]?.copy(created = false))
        }

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            opened++
            val pull = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = pushes.lastOrNull()?.first)
            pulls[branch] = pull
            return Result.success(pull)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = Result.success(pulls.values.single { it.number == number }.copy(created = false))

        var fetchCalls = 0

        override suspend fun fetchBase(): Result<String> {
            fetchCalls++
            if (fetchCalls == 1) fetchGate?.await()
            fetchFailure?.let { return Result.failure(it) }
            val sha = bases.removeFirstOrNull() ?: lastBase ?: error("no base to fetch")
            lastBase = sha
            fetched += sha
            if (writeBaseRef) git(project, "update-ref", "refs/loop/base/main", sha)
            return Result.success(sha)
        }

        override suspend fun baseTip(): Result<String> = Result.success(tip?.invoke() ?: checkNotNull(lastBase))

        override suspend fun mergePullRequest(
            number: Long,
            sha: String,
            method: MergeMethod,
        ): Result<String> {
            mergeFailures.removeFirstOrNull()?.let { return Result.failure(it) }
            mergeFailure?.let { return Result.failure(it) }
            if (lagReads > 0) {
                return Result.failure(
                    DeliveryError.Rejected("409 Head branch was modified. Review and try the merge again.", retryOnce = true),
                )
            }
            merges += Triple(number, sha, method)
            val branch = pulls.entries.single { it.value.number == number }.key
            pulls[branch] = pulls.getValue(branch).copy(state = PullRequestState.Merged, headSha = sha)
            return Result.success("feedface".repeat(5))
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

    private fun mergeWait(
        fingerprint: String,
        extra: Map<String, String> = emptyMap(),
        identifier: String = "t-1",
    ): WaitingIssue =
        approvedWait(fingerprint).let { approval ->
            val merging = (Wait.of(approval) as Wait.Approval).merging(7, "https://github.com/acme/widgets/pull/7", approval.since)
            approval.copy(
                issueId = "id-$identifier",
                identifier = identifier,
                reason = WaitReason.Merge,
                data = merging.toRecord().data + extra,
            )
        }

    private val passing = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 5) }

    /** Counts the checks it runs, and fails them when [fail] says so. */
    private class Counting(
        var fail: Boolean = false,
    ) : CheckRunner {
        var runs = 0
        val checks = mutableListOf<CheckConfig>()

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            runs++
            checks += check
            return CheckResult(if (fail) 1 else 0, timedOut = false, durationMs = 5, output = if (fail) "boom\n" else "")
        }
    }

    private val realChecks = CheckRunner { check, cwd, env -> withContext(Dispatchers.IO) { CoreCheckRunner().run(check, cwd, env) } }

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        remote: DeliveryExecutor,
        waiting: FakeWaitingIssues,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        checks: CheckRunner = passing,
        runner: FakeRunner = FakeRunner(),
        machine: FakeMachine = FakeMachine(),
        parked: FakeParkedIssues = FakeParkedIssues(),
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
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
            waiting = waiting,
            fingerprinter = fingerprinter,
            deliveryFor = { remote },
            workspaceGit = workspaceGit,
            machine = machine,
        )
    }

    private fun FakeSink.data(kind: String): Map<String, String> = events.single { it.kind == kind }.data

    private fun FakeSink.all(kind: String): List<Map<String, String>> = events.filter { it.kind == kind }.map { it.data }

    @Test
    fun `an approved delivery with merge stops after the comment, moves nothing and waits as Waiting(merge)`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { fetchFailure = DeliveryError.Unavailable("network down") }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(1, remote.opened)
            assertEquals(listOf(verified to "loop/t-1"), remote.pushes)
            assertEquals(
                "Loop opened pull request #7 for this issue: https://github.com/acme/widgets/pull/7",
                tracker.comments
                    .single()
                    .second.text,
            )
            assertEquals(emptyList(), tracker.moves)
            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Merge, wait.reason)
            assertEquals("7", wait.data[WaitKeys.PULL_REQUEST_NUMBER])
            assertEquals("check", wait.data[WaitKeys.PHASE])
            assertEquals("", wait.data[WaitKeys.BASE_SHA])
            assertEquals(clean(verified), wait.data[WaitKeys.VERIFIED_FINGERPRINT])
            assertEquals("", wait.data[WaitKeys.MERGED_HEAD])
            assertEquals("1", wait.data[WaitKeys.ROUND])
            assertEquals("soule", wait.data[WaitingIssue.APPROVED_BY])
            assertEquals("https://github.com/acme/widgets/pull/7", wait.data[WaitKeys.PULL_REQUEST])
            assertTrue(sink.events.none { it.kind == "issue_moved" })
        }

    @Test
    fun `a base already in the branch is not merged and only diff_policy runs again, against the fetched commit, then it merges`() =
        runTest {
            val verified = verifiedWorktree()
            val base = git(project, "rev-parse", "main")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = Counting()
            val visible = listOf(CheckConfig.Command("./gradlew test"), CheckConfig.DiffPolicy(listOf("protected/**"), base = "main"))
            val hidden = listOf(CheckConfig.Command("./hidden.sh"), CheckConfig.DiffPolicy(listOf("secret/**"), base = "main"))
            val kind = workflow(checks = visible, holdout = hidden, method = MergeMethod.Squash)

            orchestrator(tracker, remote, waiting, sink, kind, checks).tick()

            assertEquals(
                listOf<CheckConfig>(
                    CheckConfig.DiffPolicy(listOf("protected/**"), base = base),
                    CheckConfig.DiffPolicy(listOf("secret/**"), base = base),
                ),
                checks.checks,
                "only diff_policy runs, against the fetched commit, holdouts included",
            )
            val started = sink.all("check_started")
            assertEquals(listOf("1", "3"), started.map { it["check"] }, "each check keeps its number in the full list")
            assertTrue(started.all { it[MergeCheck.MARK] == "true" })
            val verdict = sink.all("verdict").single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("true", verdict[MergeCheck.MARK])
            assertEquals("2", verdict["checks"])
            assertEquals(verified, git(project, "rev-parse", "loop/t-1"))
            assertEquals(MergeCheck.UP_TO_DATE, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals(base, sink.data(BaseFetched.name)["sha"])
            assertEquals(listOf(Triple(7L, verified, MergeMethod.Squash)), remote.merges)
            assertEquals(
                mapOf("number" to "7", "sha" to verified, "method" to "squash", "merge_sha" to "feedface".repeat(5)),
                sink.data(PullRequestMerged.name),
            )
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertEquals("true", sink.all("issue_moved").single()["merged"])
            assertEquals(emptyMap(), waiting.records)
            assertFalse(workspace.exists(), "the workspace is removed after the merge")
            assertEquals(verified, git(project, "rev-parse", "refs/heads/loop/t-1"), "the local branch stays")
            assertTrue(sink.events.none { it.kind == "human_touch" })
        }

    @Test
    fun `a base already in the branch with no diff_policy in the kind runs no check and merges`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += git(project, "rev-parse", "main") }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = Counting()

            orchestrator(tracker, remote, waiting, sink, workflow(), checks).tick()

            assertEquals(0, checks.runs)
            assertTrue(sink.events.none { it.kind == "verdict" })
            assertEquals(MergeCheck.UP_TO_DATE, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals(1, remote.merges.size)
        }

    @Test
    fun `a merge check takes a machine slot and releases it when it ends`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val machine = FakeMachine(MachineLimits(maxConcurrentAgents = 1))
            val heldAtMerge = mutableListOf<List<String>>()
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    tip = {
                        heldAtMerge += machine.slots.toList()
                        checkNotNull(lastBase)
                    }
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))

            orchestrator(tracker, remote, waiting, FakeSink(), machine = machine).tick()

            assertEquals(1, remote.merges.size)
            val holder = heldAtMerge.first().single()
            assertEquals(listOf("claimed $holder", "released $holder"), machine.log)
            assertEquals(emptyList(), machine.slots)
        }

    @Test
    fun `a merge check with no machine slot waits for a free one`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val machine = FakeMachine(MachineLimits(maxConcurrentAgents = 1)).apply { slots += "another-process" }
            val remote = FakeRemote().apply { bases += git(project, "rev-parse", "main") }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, machine = machine)

            orchestrator.tick()

            assertEquals(0, remote.merges.size)
            assertTrue(sink.events.none { it.kind == MergeCheckStarted.name })
            assertEquals(
                WaitReason.Merge,
                waiting.records.values
                    .single()
                    .reason,
            )

            machine.slots -= "another-process"
            orchestrator.tick()
            assertEquals(1, remote.merges.size)
        }

    @Test
    fun `a diff_policy finding on a base already in the branch refutes, comments, ends the wait and hands the findings over`() =
        runTest {
            val verified = verifiedWorktree()
            val base = git(project, "rev-parse", "main")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val command = Counting()
            val runner = FakeRunner()
            val checks = listOf(CheckConfig.Command("./gradlew test"), CheckConfig.DiffPolicy(listOf("work.txt"), base = "main"))
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), guarded(command), runner)

            orchestrator.tick()

            assertEquals(0, command.runs, "no other check runs again")
            assertEquals(verified, git(workspace, "rev-parse", "HEAD"), "nothing is merged into the branch")
            val finished = sink.data(MergeCheckFinished.name)
            assertEquals(MergeCheck.REFUTED, finished["outcome"])
            assertNull(finished["head"], "no merged head")
            val verdict = sink.all("verdict").single()
            assertEquals("refuted", verdict["verdict"])
            assertEquals("true", verdict[MergeCheck.MARK])
            val comment =
                tracker.comments
                    .last()
                    .second.text
            assertContains(comment, "Loop did not merge pull request #7")
            assertContains(comment, "main at $base is already in loop/t-1")
            assertEquals("merge_check", sink.all("issue_commented").last()["reason"])
            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), remote.merges)
            assertEquals(listOf(verified to "loop/t-1"), remote.pushes)
            assertEquals(emptyList(), tracker.moves)

            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            val prompt = runner.calls.single().second
            assertContains(prompt, "already in your branch")
            assertContains(prompt, "added work.txt (protected by work.txt)")
        }

    private val testFile = "src/test/kotlin/calc/CalcTest.kt"

    @Test
    fun `with local main moved to the branch head after a test deletion the merge check refutes and merges nothing`() =
        runTest {
            for (behind in listOf(false, true)) {
                root.resolve("workspaces").toFile().deleteRecursively()
                project.toFile().deleteRecursively()
                Files.createDirectories(project)
                upstream = null
                git(project, "init", "-q", "-b", "main")
                val fork =
                    commit(project, testFile, "class CalcTest {\n    @Test\n    fun adds() {\n        assertEquals(4, 2 + 2)\n    }\n}\n")
                git(project, "worktree", "add", "-q", "-b", "loop/t-1", workspace.toString())
                val base = if (behind) baseCommit() else fork
                git(workspace, "rm", "-q", testFile)
                git(workspace, "commit", "-q", "-m", "make the tests pass")
                val verified = git(workspace, "rev-parse", "HEAD")
                // bl-32: allowed even while main is checked out in the project, and past a prefix deny rule.
                git(workspace, "-C", ".", "update-ref", "refs/heads/main", "HEAD")
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote = FakeRemote().apply { bases += base }
                val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
                val sink = FakeSink()
                val guard = CheckConfig.DiffPolicy(emptyList(), base = "main", deny = listOf(DenyGuard.DELETED_TESTS))
                val checks = listOf(CheckConfig.Command("./gradlew test"), guard)

                orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), guarded(passing)).tick()

                assertEquals(verified, git(project, "rev-parse", "main"), "the agent moved main")
                assertEquals(MergeCheck.REFUTED, sink.data(MergeCheckFinished.name)["outcome"], "behind: $behind")
                assertEquals("refuted", sink.all("verdict").single()["verdict"])
                assertEquals(emptyList(), remote.merges, "behind: $behind")
                assertEquals(emptyMap(), waiting.records)
                assertEquals(emptyList(), tracker.moves)
                if (behind) {
                    assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"), "the merge commit stays")
                } else {
                    assertEquals(verified, git(workspace, "rev-parse", "HEAD"))
                }
            }
        }

    @Test
    fun `a branch behind the base is merged in the workspace, rechecked, pushed unforced and merged at the merged head`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            // A hook planted in the shared config never runs for Loop's git.
            val hooks = Files.createDirectories(root.resolve("hooks"))
            val marker = root.resolve("hook-ran")
            listOf("post-merge", "prepare-commit-msg", "pre-merge-commit", "reference-transaction").forEach { name ->
                hooks.resolve(name).writeText("#!/bin/sh\necho $name >> '$marker'\n")
                Files.setPosixFilePermissions(hooks.resolve(name), PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            git(project, "config", "core.hooksPath", hooks.toString())
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = Counting()

            orchestrator(
                tracker,
                remote,
                waiting,
                sink,
                workflow(checks = listOf(CheckConfig.Command("a"), CheckConfig.Command("b"))),
                checks,
            ).tick()

            val merged = git(project, "rev-parse", "refs/heads/loop/t-1")
            assertEquals("$verified $base", git(project, "log", "-1", "--format=%P", merged))
            assertEquals(2, checks.runs)
            val finished = sink.data(MergeCheckFinished.name)
            assertEquals(MergeCheck.MERGED, finished["outcome"])
            assertEquals(merged, finished["head"])
            assertEquals(listOf(verified to "loop/t-1", merged to "loop/t-1"), remote.pushes)
            assertEquals(listOf(Triple(7L, merged, MergeMethod.Merge)), remote.merges)
            assertTrue(sink.all("verdict").all { it[MergeCheck.MARK] == "true" } && sink.all("verdict").size == 1)
            assertTrue(sink.all("check_started").all { it[MergeCheck.MARK] == "true" })
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertFalse(marker.exists(), "a planted hook ran")
        }

    @Test
    fun `a replay after a refused merge pushes the merged head again and opens no second pull request`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    mergeFailure = DeliveryError.Rejected("Cannot merge pull request #7: 409 the head moved")
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            val merged = git(project, "rev-parse", "refs/heads/loop/t-1")
            val fallback = waiting.records.values.single()
            assertEquals(WaitReason.Approval, fallback.reason)
            assertFalse(fallback.approved)
            assertEquals(clean(merged), fallback.data[WaitKeys.FINGERPRINT])
            assertEquals("merge", sink.all("delivery_failed").single()["step"])
            assertTrue(remote.pulls.getValue("loop/t-1").state == PullRequestState.Open, "the pull request stays open")

            // A new approval, after a restart: the replay pushes M unforced and updates the same pull request.
            waiting.records[PROJECT.value to "id-t-1"] =
                fallback.copy(
                    data =
                        fallback.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()),
                )
            remote.mergeFailure = null
            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(1, remote.opened)
            assertEquals(merged, remote.merges.single().second)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `a base commit on a protected path does not refute the merge check, diff_policy uses the fetched base`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit("protected/config.txt", "someone else's change\n")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = listOf(CheckConfig.DiffPolicy(listOf("protected/**"), base = "main"))

            orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), realChecks).tick()

            assertEquals("verified", sink.all("verdict").single()["verdict"], sink.events.toString())
            assertEquals(MergeCheck.MERGED, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals(1, remote.merges.size)
        }

    @Test
    fun `the merge check bases changed_line_coverage on the fetched base like diff_policy (bl-117)`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val counting = Counting()
            val coverage = CheckConfig.ChangedLineCoverage(listOf("build/report.xml"), 80.0, base = "main")
            val checks = listOf(CheckConfig.DiffPolicy(listOf("protected/**"), base = "main"), coverage)

            orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), counting).tick()

            assertEquals(listOf(coverage.copy(base = base)), counting.checks.filterIsInstance<CheckConfig.ChangedLineCoverage>())
        }

    @Test
    fun `a conflict leaves the workspace at the verified commit, comments, ends the wait and the next attempt gets the findings`() =
        runTest {
            for (trackerKind in listOf("files", "github")) {
                root.resolve("workspaces").toFile().deleteRecursively()
                project.toFile().deleteRecursively()
                Files.createDirectories(project)
                upstream = null
                val verified = verifiedWorktree()
                val base = baseCommit("work.txt", "conflicting\n")
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote = FakeRemote().apply { bases += base }
                val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
                val sink = FakeSink()
                val runner = FakeRunner()
                val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(tracker = trackerKind), runner = runner)

                orchestrator.tick()

                assertEquals(verified, git(workspace, "rev-parse", "HEAD"), trackerKind)
                assertEquals("", git(workspace, "status", "--porcelain"))
                assertEquals(MergeCheck.CONFLICT, sink.data(MergeCheckFinished.name)["outcome"])
                val comment =
                    tracker.comments
                        .last()
                        .second.text
                assertContains(comment, base)
                assertContains(comment, "refs/loop/base/main")
                assertContains(comment, "work.txt")
                assertEquals(emptyMap(), waiting.records)
                assertEquals(emptyList(), remote.merges)
                assertEquals(emptyList(), tracker.moves)

                advanceTimeBy(5_000)
                orchestrator.tick()
                testScheduler.advanceUntilIdle()

                val prompt = runner.calls.single().second
                assertContains(prompt, "git merge refs/loop/base/main")
                assertContains(prompt, base)
            }
        }

    @Test
    fun `a failing recheck keeps the merge commit, comments, ends the wait and hands the findings over`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, checks = Counting(fail = true), runner = runner)

            orchestrator.tick()

            val head = git(workspace, "rev-parse", "HEAD")
            assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"), "the merge commit stays")
            assertEquals(MergeCheck.REFUTED, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals("refuted", sink.all("verdict").single()["verdict"])
            assertContains(
                tracker.comments
                    .last()
                    .second.text,
                "The merge commit stays on the branch",
            )
            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), remote.merges)
            assertEquals(listOf(verified to "loop/t-1"), remote.pushes, "the refuted merge commit is never pushed")

            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            assertContains(runner.calls.single().second, "boom")
            assertEquals(head, git(workspace, "rev-parse", "HEAD"))
        }

    @Test
    fun `on the github tracker a failing recheck keeps the merge commit, comments, ends the wait and hands the findings over`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator =
                orchestrator(tracker, remote, waiting, sink, workflow(tracker = "github"), checks = Counting(fail = true), runner = runner)

            orchestrator.tick()

            val head = git(workspace, "rev-parse", "HEAD")
            assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"), "the merge commit stays")
            assertEquals(MergeCheck.REFUTED, sink.data(MergeCheckFinished.name)["outcome"])
            val comment =
                tracker.comments
                    .last()
                    .second.text
            assertContains(comment, "The merge commit stays on the branch")
            assertContains(comment, base)
            assertEquals(emptyMap(), waiting.records)
            assertEquals(emptyList(), remote.merges)
            assertEquals(emptyList(), tracker.moves)

            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            assertContains(runner.calls.single().second, "boom")
            assertEquals(head, git(workspace, "rev-parse", "HEAD"))
        }

    @Test
    fun `a check that dirties the tree during the recheck is restored and does not fail it`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = listOf(CheckConfig.Command("printf 'regenerated\\n' > a.txt && echo x > generated.log"))

            orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), realChecks).tick()

            assertEquals(MergeCheck.MERGED, sink.data(MergeCheckFinished.name)["outcome"], sink.events.toString())
            assertEquals(1, remote.merges.size)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
        }

    @Test
    fun `a base that keeps moving redoes the check at most 3 rounds, then delivery_failed and an unapproved approval wait`() =
        runTest {
            val verified = verifiedWorktree()
            val first = baseCommit("c1.txt", "1\n")
            val second = baseCommit("c2.txt", "2\n")
            val third = baseCommit("c3.txt", "3\n")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += listOf(first, second, third)
                    tip = { "f".repeat(40) }
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(listOf(first, second, third), remote.fetched)
            assertEquals(listOf("1", "2", "3"), sink.all(MergeCheckStarted.name).map { it["round"] })
            assertEquals(emptyList(), remote.merges)
            val failed = sink.all("delivery_failed").single()
            assertEquals("merge", failed["step"])
            assertContains(failed["reason"].orEmpty(), "moved during 3 merge checks")
            val fallback = waiting.records.values.single()
            assertEquals(WaitReason.Approval, fallback.reason)
            assertFalse(fallback.approved)
            assertEquals(clean(git(project, "rev-parse", "loop/t-1")), fallback.data[WaitKeys.FINGERPRINT])
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a 403, 405 or 409 on the merge records delivery_failed and leaves the pull request open`() =
        runTest {
            for (status in listOf(403, 405, 409)) {
                root.resolve("workspaces").toFile().deleteRecursively()
                project.toFile().deleteRecursively()
                Files.createDirectories(project)
                upstream = null
                val verified = verifiedWorktree()
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote =
                    FakeRemote().apply {
                        bases += git(project, "rev-parse", "main")
                        mergeFailure = DeliveryError.Rejected("Cannot merge pull request #7 on acme/widgets: $status")
                    }
                val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
                val sink = FakeSink()

                orchestrator(tracker, remote, waiting, sink).tick()

                assertContains(sink.all("delivery_failed").single()["reason"].orEmpty(), "$status")
                assertEquals(PullRequestState.Open, remote.pulls.getValue("loop/t-1").state)
                val fallback = waiting.records.values.single()
                assertEquals(WaitReason.Approval, fallback.reason, "$status")
                assertFalse(fallback.approved)
                assertNull(fallback.data[WaitKeys.PHASE])
                assertContains(
                    tracker.comments
                        .last()
                        .second.text,
                    "waits for a new approval",
                )
                assertEquals(emptyList(), tracker.moves)
            }
        }

    @Test
    fun `no token reaches an event, a comment or the wait, whatever a failure says`() =
        runTest {
            val verified = verifiedWorktree()
            val token = "ghp_" + "Ab3".repeat(12)
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    mergeFailure = DeliveryError.Rejected("Cannot merge pull request #7 with $token: 403")
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            val everything =
                sink.events.joinToString("\n") { "${it.kind} ${it.data}" } +
                    tracker.comments.joinToString("\n") { it.second.text } +
                    waiting.records.values.joinToString("\n") { it.data.toString() }
            assertContains(everything, "delivery_failed")
            assertFalse(token in everything, everything)
        }

    @Test
    fun `a network error on the merge keeps the merge wait for the next tick`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    mergeFailure = DeliveryError.Unavailable("502 bad gateway")
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Merge, wait.reason)
            assertEquals("merge", wait.data[WaitKeys.PHASE])
            assertTrue(sink.events.none { it.kind == "delivery_failed" })

            remote.mergeFailure = null
            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(1, remote.merges.size)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
        }

    @Test
    fun `a restart in the check phase resumes without a second pull request or a lost approval`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(0, remote.opened)
            assertEquals(1, remote.merges.size)
            assertEquals("soule", sink.all("issue_moved").single()["approved_by"])
        }

    @Test
    fun `a restart in the merge phase merges once from the stored merged head`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            git(workspace, "merge", "-q", "--no-ff", "--no-edit", base)
            val merged = git(workspace, "rev-parse", "HEAD")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    lastBase = base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val extra =
                mapOf(
                    WaitKeys.PHASE to "merge",
                    WaitKeys.BASE_SHA to base,
                    WaitKeys.MERGED_HEAD to merged,
                    WaitKeys.FINGERPRINT to clean(merged),
                    WaitKeys.VERIFIED_FINGERPRINT to clean(verified),
                )
            val waiting = FakeWaitingIssues(mergeWait(clean(verified), extra))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyList(), remote.fetched, "no second merge check")
            assertEquals(listOf(merged to "loop/t-1"), remote.pushes)
            assertEquals(listOf(Triple(7L, merged, MergeMethod.Merge)), remote.merges)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
        }

    @Test
    fun `a pull request already merged at the stored head goes straight to the move`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    pulls["loop/t-1"] =
                        PullRequest(
                            7,
                            "https://github.com/acme/widgets/pull/7",
                            created = false,
                            state = PullRequestState.Merged,
                            headSha = verified,
                        )
                }
            val extra = mapOf(WaitKeys.PHASE to "merge", WaitKeys.MERGED_HEAD to verified)
            val waiting = FakeWaitingIssues(mergeWait(clean(verified), extra))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyList(), remote.fetched)
            assertEquals(emptyList(), remote.merges)
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertTrue(sink.events.none { it.kind == "human_touch" })
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `an issue already closed by Closes n is no error and records no human touch`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1", state = "closed"))).apply { filterByStates = true }
            val remote =
                FakeRemote().apply {
                    pulls["loop/t-1"] =
                        PullRequest(
                            7,
                            "https://github.com/acme/widgets/pull/7",
                            created = false,
                            state = PullRequestState.Merged,
                            headSha = verified,
                        )
                }
            val extra = mapOf(WaitKeys.PHASE to "merge", WaitKeys.MERGED_HEAD to verified)
            val waiting = FakeWaitingIssues(mergeWait(clean(verified), extra))
            val sink = FakeSink()
            val workflow = workflow(tracker = "github").let { it.copy(terminalStates = listOf("closed")) }

            orchestrator(tracker, remote, waiting, sink, workflow).tick()

            assertEquals(emptyMap(), waiting.records)
            assertTrue(sink.events.none { it.kind == "human_touch" })
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
            assertFalse(workspace.exists())
        }

    @Test
    fun `a pull request merged by a human at the stored head gives the move and a human touch`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    pulls["loop/t-1"] =
                        PullRequest(
                            7,
                            "https://github.com/acme/widgets/pull/7",
                            created = false,
                            state = PullRequestState.Merged,
                            headSha = verified,
                        )
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            assertEquals(MergeCheck.HUMAN_MERGE, sink.data("human_touch")["touch"])
        }

    @Test
    fun `an issue a human moved out of the active states ends the merge wait with a human touch`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1", state = "canceled"))).apply { filterByStates = true }
            val remote =
                FakeRemote().apply {
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyMap(), waiting.records)
            assertEquals("move", sink.data("human_touch")["touch"])
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `one merge check at a time per project, holding an agent slot`() =
        runTest {
            verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1"), issue("t-2"), issue("t-3")))
            val gate = CompletableDeferred<Unit>()
            val remote =
                FakeRemote().apply {
                    fetchGate = gate
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)
                    pulls["loop/t-2"] = PullRequest(8, "https://github.com/acme/widgets/pull/8", created = false)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean("1".repeat(40))), mergeWait(clean("2".repeat(40)), identifier = "t-2"))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(maxAgents = 1), runner = runner)

            val loop = launch { orchestrator.runUntilCancelled() }
            runCurrent()

            // The merge check of one issue holds the only slot: t-3 waits, t-2's check waits, and the loop goes on polling.
            assertEquals(0, runner.calls.size)
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(tracker.requestedStates.size >= 2, "the poll goes on while the merge check runs")
            assertEquals(0, runner.calls.size)
            assertTrue(sink.events.any { it.kind == "candidate_skipped" && it.issueIdentifier == "t-3" })
            assertTrue(sink.events.none { it.kind == BaseFetched.name })
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
            assertEquals(1, remote.fetchCalls, "one merge check runs")
            assertEquals(listOf(WaitReason.Merge, WaitReason.Merge), waiting.records.values.map { it.reason })
            loop.cancel()
        }

    @Test
    fun `with free slots an attempt runs beside the one merge check, a second merge check still waits`() =
        runTest {
            verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1"), issue("t-2"), issue("t-3")))
            val gate = CompletableDeferred<Unit>()
            val remote =
                FakeRemote().apply {
                    fetchGate = gate
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)
                    pulls["loop/t-2"] = PullRequest(8, "https://github.com/acme/widgets/pull/8", created = false)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean("1".repeat(40))), mergeWait(clean("2".repeat(40)), identifier = "t-2"))
            val runner = FakeRunner()
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(maxAgents = 3), runner = runner)

            val loop = launch { orchestrator.runUntilCancelled() }
            advanceTimeBy(3_000)
            runCurrent()

            // Each attempt of t-3 fetches the base before its hooks and records it (bl-59); the held merge check does not.
            val fetchKinds = setOf(BaseFetched.name, "base_fetch_failed")
            val attemptFetches = sink.events.count { it.kind in fetchKinds && it.issueIdentifier == "t-3" }
            assertTrue(attemptFetches >= 1, sink.events.toString())
            assertEquals(1, remote.fetchCalls - attemptFetches, "one merge check runs")
            assertEquals(listOf("t-3"), runner.calls.map { it.first.issue.identifier }.distinct())
            loop.cancel()
        }

    /** A sessionless runner whose one turn runs [work] in the workspace, then queues a move to review, the agent's result. */
    private fun submitting(work: (Path) -> Unit = {}): FakeRunner =
        FakeRunner().apply {
            onCall = { ctx ->
                work(ctx.workspace)
                ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
            }
        }

    /** Runs `diff_policy` for real and every other check through [others]. */
    private fun guarded(others: CheckRunner): CheckRunner =
        CheckRunner { check, cwd, env ->
            if (check is CheckConfig.DiffPolicy) {
                withContext(
                    Dispatchers.IO,
                ) { CoreCheckRunner().run(check, cwd, env) }
            } else {
                others.run(check, cwd, env)
            }
        }

    /** The verdicts of ordinary verifications, not those of a merge check's rerun. */
    private fun FakeSink.verdicts(): List<String> =
        events.filter { it.kind == "verdict" && it.data[MergeCheck.MARK] == null }.map { it.data["verdict"].orEmpty() }

    @Test
    fun `send back 1, a stop after the merge commit and before the phase write resumes, rechecks and merges at the merged head`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val entered = CompletableDeferred<Unit>()
            val stuck =
                CheckRunner { _, _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }

            // A stop (Ctrl-C, a crash, a cancellation) while the recheck runs, after the merge commit was made.
            val first = launch { orchestrator(tracker, remote, waiting, FakeSink(), checks = stuck).tick() }
            entered.await()
            first.cancel()
            first.join()

            val merged = git(workspace, "rev-parse", "HEAD")
            assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"))
            val stopped = waiting.records.values.single()
            assertEquals(WaitReason.Merge, stopped.reason)
            assertEquals(WaitKeys.PHASE_CHECK, stopped.data[WaitKeys.PHASE])
            assertEquals(clean(verified), stopped.data[WaitKeys.FINGERPRINT])
            assertEquals(base, stopped.data[WaitKeys.BASE_SHA], "the base being merged is stored before the merge")

            // The restart needs no fetch: even a fetch refused for good cannot strand the merge commit.
            remote.fetchFailure = DeliveryError.Rejected("Cannot fetch main: 401 bad credentials")
            val sink = FakeSink()
            val checks = Counting()
            orchestrator(tracker, remote, waiting, sink, checks = checks).tick()

            assertTrue(sink.events.none { it.kind == "delivery_failed" }, sink.events.toString())
            assertEquals(1, remote.fetchCalls, "the resumed check fetches nothing")
            assertEquals("true", sink.data(MergeCheckStarted.name)["resumed"])
            assertEquals(1, checks.runs, "the merged result is rechecked")
            assertEquals(MergeCheck.MERGED, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals(merged, git(project, "rev-parse", "refs/heads/loop/t-1"), "no second merge commit")
            assertEquals(listOf(merged to "loop/t-1"), remote.pushes)
            assertEquals(listOf(Triple(7L, merged, MergeMethod.Merge)), remote.merges)
            assertEquals("soule", sink.all("issue_moved").single()["approved_by"])
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
        }

    @Test
    fun `send back 1, a merge a stop left in progress at the verified commit is aborted and the check goes on`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            git(workspace, "merge", "-q", "--no-ff", "--no-commit", base)
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified), mapOf(WaitKeys.BASE_SHA to base)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertTrue(sink.events.none { it.kind == "delivery_failed" }, sink.events.toString())
            val merged = git(project, "rev-parse", "refs/heads/loop/t-1")
            assertEquals("$verified $base", git(project, "log", "-1", "--format=%P", merged))
            assertEquals(listOf(Triple(7L, merged, MergeMethod.Merge)), remote.merges)
        }

    @Test
    fun `send back 1, a merge commit whose second parent is not the stored base is not taken for Loop's`() =
        runTest {
            val verified = verifiedWorktree()
            val other = baseCommit("other.txt", "other\n")
            val base = baseCommit()
            git(workspace, "merge", "-q", "--no-ff", "--no-edit", other)
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified), mapOf(WaitKeys.BASE_SHA to base)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyList(), remote.merges)
            assertContains(sink.data("delivery_failed")["reason"].orEmpty(), "not at the verified")
        }

    @Test
    fun `send back 2, after a failing recheck the next verification bases diff_policy on the fetched base, not local main`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit("protected/config.txt", "someone else's change\n")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    writeBaseRef = true
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val command = Counting(fail = true)
            val checks = listOf(CheckConfig.Command("./gradlew test"), CheckConfig.DiffPolicy(listOf("protected/**"), base = "main"))
            val runner = submitting { dir -> commit(dir, "fix.txt", "fix\n") }
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), guarded(command), runner)

            orchestrator.tick()
            assertEquals(MergeCheck.REFUTED, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"), "the merge commit stays")
            assertEquals(base, git(project, "rev-parse", "refs/loop/base/main"))

            command.fail = false
            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            assertEquals(1, runner.calls.size)
            assertEquals(listOf("verified"), sink.verdicts(), sink.events.filter { it.kind.startsWith("check") }.toString())
        }

    @Test
    fun `send back 2, after a conflict the agent's own merge of refs loop base does not refute the next verification`() =
        runTest {
            val verified = verifiedWorktree()
            baseCommit("protected/config.txt", "someone else's change\n")
            val base = baseCommit("work.txt", "conflicting\n")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    writeBaseRef = true
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val checks = listOf(CheckConfig.Command("./gradlew test"), CheckConfig.DiffPolicy(listOf("protected/**"), base = "main"))
            val runner =
                submitting { dir ->
                    val merge =
                        ProcessBuilder(
                            "git",
                            "-c",
                            "user.name=t",
                            "-c",
                            "user.email=t@example.com",
                            "-c",
                            "core.hooksPath=/dev/null",
                            "merge",
                            "-q",
                            "--no-edit",
                            "refs/loop/base/main",
                        ).directory(dir.toFile())
                            .redirectErrorStream(true)
                            .apply {
                                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                                environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                            }.start()
                    merge.inputStream.readAllBytes()
                    assertEquals(1, merge.waitFor(), "the agent's merge conflicts")
                    dir.resolve("work.txt").writeText("work\nconflicting\n")
                    git(dir, "add", "work.txt")
                    git(dir, "commit", "-q", "--no-edit")
                }
            val orchestrator = orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), guarded(passing), runner)

            orchestrator.tick()
            assertEquals(MergeCheck.CONFLICT, sink.data(MergeCheckFinished.name)["outcome"])
            assertEquals(verified, git(workspace, "rev-parse", "HEAD"))

            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()

            assertEquals(1, runner.calls.size)
            assertEquals("$verified $base", git(workspace, "log", "-1", "--format=%P"))
            assertEquals(listOf("verified"), sink.verdicts())
        }

    @Test
    fun `send back 4, a merge failure that is not a conflict records delivery_failed, keeps the verified commit and dispatches nothing`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val gitDir = Path.of(git(workspace, "rev-parse", "--absolute-git-dir"))
            val lock = Files.createFile(gitDir.resolve("index.lock"))
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            val runner = FakeRunner()
            val orchestrator = orchestrator(tracker, remote, waiting, sink, runner = runner)

            orchestrator.tick()
            Files.delete(lock)

            val failed = sink.data("delivery_failed")
            assertEquals(MergeCheck.STEP_CHECK, failed["step"])
            assertContains(failed["reason"].orEmpty(), "git merge exited with code 128: error: Unable to create")
            assertTrue(sink.all(MergeCheckFinished.name).none { it["outcome"] == MergeCheck.CONFLICT })
            assertEquals(verified, git(workspace, "rev-parse", "HEAD"))
            val fallback = waiting.records.values.single()
            assertEquals(WaitReason.Approval, fallback.reason)
            assertFalse(fallback.approved)
            assertEquals(clean(verified), fallback.data[WaitKeys.FINGERPRINT])
            assertTrue(tracker.comments.none { "conflicts" in it.second.text })

            advanceTimeBy(5_000)
            orchestrator.tick()
            testScheduler.advanceUntilIdle()
            assertEquals(0, runner.calls.size, "no redispatch")
        }

    @Test
    fun `send back 5, the merge waits for the pull request head to show the pushed merged head`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    headLag = 3
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            val merged = git(project, "rev-parse", "refs/heads/loop/t-1")
            assertEquals(listOf(Triple(7L, merged, MergeMethod.Merge)), remote.merges)
            assertTrue(remote.finds >= 4, "${remote.finds} reads")
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
            assertEquals(listOf("id-t-1" to "done"), tracker.moves)
        }

    @Test
    fun `send back 5, a single base or head branch was modified refusal is tried again at the next tick`() =
        runTest {
            for (refusal in listOf("405 Base branch was modified", "409 Head branch was modified")) {
                root.resolve("workspaces").toFile().deleteRecursively()
                project.toFile().deleteRecursively()
                Files.createDirectories(project)
                upstream = null
                val verified = verifiedWorktree()
                val tracker = FakeTracker(listOf(issue("t-1")))
                val remote =
                    FakeRemote().apply {
                        bases += git(project, "rev-parse", "main")
                        mergeFailures += DeliveryError.Rejected("Cannot merge pull request #7: $refusal", retryOnce = true)
                    }
                val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
                val sink = FakeSink()

                orchestrator(tracker, remote, waiting, sink).tick()

                val kept = waiting.records.values.single()
                assertEquals(WaitReason.Merge, kept.reason, refusal)
                assertEquals(WaitKeys.PHASE_MERGE, kept.data[WaitKeys.PHASE])
                assertTrue(kept.approved)
                assertTrue(sink.events.none { it.kind == "delivery_failed" }, refusal)
                assertEquals(emptyList(), remote.merges)

                orchestrator(tracker, remote, waiting, sink).tick()

                assertEquals(1, remote.merges.size, refusal)
                assertEquals(listOf("id-t-1" to "done"), tracker.moves)
            }
        }

    @Test
    fun `send back 5, the same refusal twice counts as permanent`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val modified = { DeliveryError.Rejected("Cannot merge pull request #7: 409 Head branch was modified", retryOnce = true) }
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    mergeFailures += listOf(modified(), modified())
                }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()
            orchestrator(tracker, remote, waiting, sink).tick()

            assertContains(sink.data("delivery_failed")["reason"].orEmpty(), "Head branch was modified")
            val fallback = waiting.records.values.single()
            assertEquals(WaitReason.Approval, fallback.reason)
            assertFalse(fallback.approved)
            assertNull(fallback.data[WaitKeys.MERGE_RETRY])
            assertEquals(emptyList(), remote.merges)
        }

    @Test
    fun `send back 6, an issue a human moves out of the active states during the merge check is not merged`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()
            val moving =
                CheckRunner { _, _, _ ->
                    tracker.issues[0] = tracker.issues[0].copy(state = "canceled")
                    CheckResult(0, timedOut = false, durationMs = 5)
                }

            orchestrator(tracker, remote, waiting, sink, checks = moving).tick()

            assertEquals(emptyList(), remote.merges)
            assertEquals(PullRequestState.Open, remote.pulls.getValue("loop/t-1").state)
            assertEquals(emptyMap(), waiting.records)
            assertEquals(HUMAN_TOUCH_MOVE, sink.data("human_touch")["touch"])
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `send back 7, a tree still dirty after the restore refutes the merge check`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote = FakeRemote().apply { bases += base }
            val waiting = FakeWaitingIssues(approvedWait(clean(verified)))
            val sink = FakeSink()
            // The restore cannot delete a file in a folder the check made read only.
            val checks = listOf(CheckConfig.Command("mkdir -p locked && echo x > locked/f && chmod 555 locked"))
            try {
                orchestrator(tracker, remote, waiting, sink, workflow(checks = checks), realChecks).tick()
            } finally {
                workspace.resolve("locked").toFile().setWritable(true)
            }

            assertEquals(MergeCheck.REFUTED, sink.data(MergeCheckFinished.name)["outcome"], sink.events.toString())
            assertEquals(emptyList(), remote.merges)
            assertContains(
                tracker.comments
                    .last()
                    .second.text,
                "not clean",
            )
            assertEquals(emptyMap(), waiting.records)
        }

    @Test
    fun `send back 7, a workspace that moved after the verification is not merged`() =
        runTest {
            val verified = verifiedWorktree()
            val base = baseCommit()
            commit(workspace, "late.txt", "a commit after the verification\n")
            val tracker = FakeTracker(listOf(issue("t-1")))
            val remote =
                FakeRemote().apply {
                    bases += base
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()

            orchestrator(tracker, remote, waiting, sink).tick()

            assertEquals(emptyList(), remote.merges)
            assertEquals(emptyList(), remote.pushes)
            val failed = sink.data("delivery_failed")
            assertEquals(MergeCheck.STEP_CHECK, failed["step"])
            assertContains(failed["reason"].orEmpty(), "not at the verified")
            assertTrue(sink.events.none { it.kind == MergeCheckStarted.name })
        }

    @Test
    fun `send back 7, full agent slots keep a merge check from starting`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-3")))
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false, headSha = verified)
                }
            val waiting = FakeWaitingIssues()
            val runner = FakeRunner { _, _ -> flow { awaitCancellation() } }
            val orchestrator = orchestrator(tracker, remote, waiting, FakeSink(), workflow(maxAgents = 1), runner = runner)

            val loop = launch { orchestrator.runUntilCancelled() }
            runCurrent()
            assertEquals(listOf("t-3"), runner.calls.map { it.first.issue.identifier })

            // The attempt of t-3 holds the only slot when the merge wait of t-1 shows up.
            tracker.issues += issue("t-1")
            waiting.startWaiting(mergeWait(clean(verified)))
            advanceTimeBy(3_000)
            runCurrent()

            // The one fetch is the attempt of t-3's, before its hooks (bl-59).
            assertEquals(1, remote.fetchCalls, "no merge check starts while the slots are full")
            assertEquals(
                WaitReason.Merge,
                waiting.records.values
                    .single()
                    .reason,
            )
            loop.cancel()
        }

    @Test
    fun `a tick whose parked read fails still starts the waiting merge check and dispatches nothing (bl-86)`() =
        runTest {
            val verified = verifiedWorktree()
            val tracker = FakeTracker(listOf(issue("t-1"), issue("t-3")))
            val remote =
                FakeRemote().apply {
                    bases += git(project, "rev-parse", "main")
                    pulls["loop/t-1"] = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = false)
                }
            val waiting = FakeWaitingIssues(mergeWait(clean(verified)))
            val sink = FakeSink()
            val runner = FakeRunner()
            val parked = FakeParkedIssues().apply { failure = IOException("store down") }

            orchestrator(tracker, remote, waiting, sink, runner = runner, parked = parked).tick()

            // The merge check is decided before the parked read, so the failed read stops only the attempts.
            assertEquals(1, remote.fetchCalls, "the merge check ran")
            assertEquals(1, sink.all(MergeCheckStarted.name).size)
            assertEquals(0, runner.calls.size, "no attempt without the parked read")
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val HUMAN_TOUCH_MOVE = "move"
    }
}
