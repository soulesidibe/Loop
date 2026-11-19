package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.ProjectId
import loop.api.PullRequest
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bl-59: before every attempt of a kind with pull request delivery, Loop fetches the base host side through the
 * delivery executor's `fetchBase` and hands the fetched commit to the attempt's hooks as `LOOP_BASE_REF`. A failed
 * fetch falls back to the last fetched `refs/loop/base/<base>` with a warning, or leaves `LOOP_BASE_REF` unset, and
 * never holds the attempt. A kind without pull request delivery fetches nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseFetchOrchestratorTest {
    private val root: Path = createTempDirectory("loop-base-fetch").toRealPath()
    private val logLines = mutableListOf<String>()
    private val hookRunner = FakeHookRunner()
    private val sink = FakeSink()
    private val fetchedSha = "0123456789abcdef0123456789abcdef01234567"

    private val hooks =
        HooksConfig(
            afterCreate = "sh after_create.sh",
            beforeRun = "sh before_run.sh",
            afterRun = "sh after_run.sh",
            timeoutMs = 30_000,
        )

    private fun workflow(pullRequest: PullRequestConfig?): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    hooks = hooks,
                    kinds =
                        KindsConfig(
                            KindConfig(
                                listOf(CheckConfig.Command("true")),
                                delivery = DeliveryConfig(pullRequest = pullRequest),
                            ),
                        ),
                ),
        )

    /** A delivery whose [fetchBase] answers [fetched] and records, at each call, how many hooks had run before it. */
    private inner class FakeBase(
        var fetched: Result<String>,
    ) : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val hooksBeforeFetch = mutableListOf<Int>()

        override suspend fun fetchBase(): Result<String> {
            hooksBeforeFetch += hookRunner.calls.size
            return fetched
        }

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> = error("no push in this test")

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> = error("no pull request in this test")

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> = error("no pull request in this test")

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> = error("no pull request in this test")
    }

    private fun TestScope.orchestrator(
        remote: DeliveryExecutor?,
        pullRequest: PullRequestConfig? = PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN"),
        runner: FakeRunner = FakeRunner(),
    ): Orchestrator {
        val workflow = workflow(pullRequest)
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = FakeTracker(listOf(issue("t-1"))),
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), hooks, hookRunner),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            deliveryFor = { remote },
        )
    }

    /** `LOOP_BASE_REF` of each call of [hook], null where it is unset. */
    private fun baseRefs(hook: HookName): List<String?> = hookRunner.calls.filter { it.hook == hook }.map { it.env["LOOP_BASE_REF"] }

    /** Runs git in [dir]; fails the test on a non zero exit. Returns stdout, trimmed. */
    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Loop test", "-c", "user.email=test@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    /** Makes [root], the folder of `WORKFLOW.md`, a git repository with one commit on `main`; that commit. */
    private fun projectRepository(): String {
        git(root, "init", "-q", "--initial-branch=main")
        root.resolve("README.md").writeText("project\n")
        git(root, "add", "README.md")
        git(root, "commit", "-q", "-m", "first")
        return git(root, "rev-parse", "HEAD")
    }

    @Test
    fun `every attempt, first and retry, fetches the base before its hooks and both hooks get the fetched commit`() =
        runTest {
            val remote = FakeBase(Result.success(fetchedSha))
            val runner = FakeRunner()
            val orchestrator = orchestrator(remote, runner = runner)

            orchestrator.tick()
            // The base moved on GitHub between the attempts: the retry fetches again and gets the new tip.
            val moved = "fedcba9876543210fedcba9876543210fedcba98"
            remote.fetched = Result.success(moved)
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(2, runner.calls.size, "the continuation retry did not start a second attempt")
            assertEquals(listOf(HookName.AFTER_CREATE, HookName.BEFORE_RUN), hookRunner.hooks().take(2))
            assertEquals(1, hookRunner.hooks().count { it == HookName.AFTER_CREATE }, "the retry reuses the workspace")
            val afterFirst = hookRunner.calls.indexOfFirst { it.hook == HookName.AFTER_RUN }
            assertEquals(listOf(0, afterFirst + 1), remote.hooksBeforeFetch, "each fetch runs before the hooks of its attempt")
            assertEquals(listOf<String?>(fetchedSha), baseRefs(HookName.AFTER_CREATE))
            assertEquals(listOf<String?>(fetchedSha, moved), baseRefs(HookName.BEFORE_RUN))
            val fetched = sink.events.filter { it.kind == "base_fetched" }
            assertEquals(listOf(fetchedSha, moved), fetched.map { it.data["sha"] })
            assertTrue(fetched.all { it.data["base"] == "main" && it.attemptId != null }, fetched.toString())
            assertFalse(
                "LOOP_BASE_REF" in
                    runner.calls
                        .first()
                        .first.env,
                "the agent's env does not get it, only the hooks",
            )
        }

    @Test
    fun `a failed fetch starts the attempt from the last fetched base ref and records a warning`() =
        runTest {
            val earlier = projectRepository()
            git(root, "update-ref", "refs/loop/base/main", earlier)
            val remote = FakeBase(Result.failure(DeliveryError.Unavailable("could not reach github.com")))
            val runner = FakeRunner()

            orchestrator(remote, runner = runner).tick()

            assertEquals(1, runner.calls.size, "the attempt ran")
            assertEquals(listOf<String?>(earlier), baseRefs(HookName.AFTER_CREATE))
            assertEquals(listOf<String?>(earlier), baseRefs(HookName.BEFORE_RUN))
            val warning = sink.events.single { it.kind == "base_fetch_failed" }
            assertEquals("main", warning.data["base"])
            assertEquals(earlier, warning.data["sha"])
            assertContains(warning.data["reason"].orEmpty(), "could not reach github.com")
            assertTrue(sink.events.none { it.kind == "base_fetched" })
            assertTrue(logLines.any { "level=warn" in it && "event=base_fetch_failed" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `a failed fetch with no earlier base ref leaves LOOP_BASE_REF unset and still runs the attempt`() =
        runTest {
            projectRepository()
            val remote = FakeBase(Result.failure(DeliveryError.Rejected("GitHub answered 401")))
            val runner = FakeRunner()

            orchestrator(remote, runner = runner).tick()

            assertEquals(1, runner.calls.size, "the attempt ran")
            assertEquals(listOf<String?>(null), baseRefs(HookName.AFTER_CREATE))
            assertEquals(listOf<String?>(null), baseRefs(HookName.BEFORE_RUN))
            val warning = sink.events.single { it.kind == "base_fetch_failed" }
            assertFalse("sha" in warning.data, warning.data.toString())
            assertContains(warning.data["reason"].orEmpty(), "GitHub answered 401")
        }

    @Test
    fun `a fetch that throws falls back like a failed one`() =
        runTest {
            val remote =
                object : DeliveryExecutor by FakeBase(Result.success(fetchedSha)) {
                    override suspend fun fetchBase(): Result<String> = throw IllegalStateException("git crashed")
                }
            val runner = FakeRunner()

            orchestrator(remote, runner = runner).tick()

            assertEquals(1, runner.calls.size, "the attempt ran")
            assertEquals(listOf<String?>(null), baseRefs(HookName.BEFORE_RUN))
            assertContains(
                sink.events
                    .single { it.kind == "base_fetch_failed" }
                    .data["reason"]
                    .orEmpty(),
                "git crashed",
            )
        }

    @Test
    fun `a kind without pull request delivery fetches nothing and sets no LOOP_BASE_REF`() =
        runTest {
            val remote = FakeBase(Result.success(fetchedSha))
            val runner = FakeRunner()

            orchestrator(remote, pullRequest = null, runner = runner).tick()

            assertEquals(1, runner.calls.size)
            assertEquals(emptyList(), remote.hooksBeforeFetch, "no fetch")
            assertTrue(hookRunner.calls.none { "LOOP_BASE_REF" in it.env }, hookRunner.calls.toString())
            assertTrue(sink.events.none { it.kind == "base_fetched" || it.kind == "base_fetch_failed" })
        }

    @Test
    fun `the fetch and the fallback move no local branch of the project`() =
        runTest {
            val first = projectRepository()
            git(root, "branch", "feature")
            git(root, "update-ref", "refs/loop/base/main", first)
            val branches = { git(root, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads") }
            val before = branches()
            val remote = FakeBase(Result.failure(DeliveryError.Unavailable("offline")))
            val orchestrator = orchestrator(remote)

            orchestrator.tick()
            remote.fetched = Result.success(fetchedSha)
            advanceTimeBy(1_000)
            orchestrator.tick()

            assertEquals(before, branches())
            assertEquals(first, git(root, "rev-parse", "main"))
        }
}
