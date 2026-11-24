package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m4-17, story 117, bl-10: the area lock at the dispatch. A candidate whose area overlaps the area of a claimed issue,
 * running, waiting while its verification runs or queued for a retry, is skipped with `candidate_skipped`
 * `area_busy <holder>`, keeps its place, and is dispatched on the first tick its area is free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AreaLockOrchestratorTest {
    private val root: Path = createTempDirectory("loop-area-lock").toRealPath()
    private val sink = FakeSink()
    private val tracker = FakeTracker(emptyList())

    /** Identifiers whose first attempt fails; every other attempt holds until its identifier is released. */
    private val failFirst = mutableSetOf<String>()
    private val calls = mutableListOf<String>()
    private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()

    private fun hold(identifier: String) = holds.getOrPut(identifier) { CompletableDeferred() }

    private val holding =
        FakeRunner { ctx, _ ->
            val identifier = ctx.issue.identifier
            val first = identifier !in calls
            calls += identifier
            if (first && identifier in failFirst) {
                failingTurn()
            } else {
                flow {
                    emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                    hold(identifier).await()
                    emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                }
            }
        }

    private fun workflow(kinds: Boolean = false): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = POLL_MS,
            promptTemplate = "Work on {{ issue.identifier }}",
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxConcurrentAgents = 10, maxTurns = 1),
                    kinds =
                        if (kinds) {
                            KindsConfig(KindConfig(listOf(CheckConfig.Command("./gradlew test")), delivery = DeliveryConfig(DeliveryPolicy.Auto)))
                        } else {
                            null
                        },
                ),
        )

    /** A check runner that suspends each run until [release] completes, then passes. */
    private class HeldChecks : CheckRunner {
        val calls = mutableListOf<Path>()
        val release = CompletableDeferred<Unit>()

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            calls.add(cwd)
            release.await()
            return CheckResult(0, timedOut = false, durationMs = 1200)
        }
    }

    private fun TestScope.orchestrator(
        runner: FakeRunner = holding,
        workflow: WorkflowFile = workflow(),
        checks: CheckRunner? = null,
        waiting: FakeWaitingIssues = FakeWaitingIssues(),
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
            waiting = waiting,
            checkRunnerFor = { checks ?: error("no checks in this test") },
        )
    }

    /** A sessionless runner whose one turn queues `loop tool move review`. */
    private fun submitting(): FakeRunner {
        val runner = FakeRunner()
        runner.onCall = { ctx ->
            calls += ctx.issue.identifier
            ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
        }
        return runner
    }

    private fun area(
        identifier: String,
        vararg paths: String,
        priority: Int = 2,
    ) = issue(identifier, priority = priority, area = paths.toList())

    /** The reason of every `candidate_skipped` of [identifier], in the order recorded. */
    private fun skips(identifier: String): List<String?> =
        sink.events.filter { it.kind == "candidate_skipped" && it.issueIdentifier == identifier }.map { it.data["reason"] }

    @Test
    fun `a candidate overlapping a running issue is skipped naming it, other areas and issues with no area run`() =
        runTest {
            tracker.issues += area("a", "spec-core", priority = 1)
            tracker.issues += issue("n", priority = 1)
            val orchestrator = orchestrator()
            val first = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(listOf("a", "n"), calls)

            tracker.issues += area("b", "spec-core/src/main")
            tracker.issues += issue("c")
            tracker.issues += area("d", "docs")
            tracker.issues += area("e", "spec-core-x")
            val second = launch { orchestrator.tick() }
            runCurrent()

            assertEquals(listOf("a", "n", "c", "d", "e"), calls)
            assertEquals(listOf("area_busy a (spec-core)"), skips("b"))
            assertEquals(1, sink.events.count { it.kind == "candidate_skipped" }, "nothing else is skipped")
            holds.values.forEach { it.complete(Unit) }
            first.join()
            second.join()
        }

    @Test
    fun `the area frees when the claim is released and the skipped candidates dispatch on the first free tick in their order`() =
        runTest {
            tracker.issues += area("a", "spec-core", priority = 1)
            val orchestrator = orchestrator()
            val first = launch { orchestrator.tick() }
            runCurrent()

            tracker.issues += area("b", "spec-core/src", priority = 2)
            tracker.issues += area("c", "spec-core", priority = 3)
            val second = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(listOf("a"), calls)
            assertEquals(listOf("area_busy a (spec-core)"), skips("b"))
            assertEquals(listOf("area_busy a (spec-core)"), skips("c"))

            // A human moves a to done: reconciliation stops its attempt and releases its claim.
            tracker.issues[0] = tracker.issues[0].copy(state = "done")
            val third = launch { orchestrator.tick() }
            runCurrent()
            first.join()
            val fourth = launch { orchestrator.tick() }
            runCurrent()

            // b comes first in the dispatch order and runs; c keeps its place behind it and now waits for b.
            assertEquals(listOf("a", "b"), calls)
            assertEquals(listOf("area_busy a (spec-core)", "area_busy b (spec-core/src)"), skips("c"))
            holds.values.forEach { it.complete(Unit) }
            listOf(second, third, fourth).forEach { it.join() }
        }

    @Test
    fun `the area stays busy while its issue is queued for a retry and frees when the retry is dropped`() =
        runTest {
            failFirst += "a"
            tracker.issues += area("a", "plugins/tracker-files", priority = 1)
            val orchestrator = orchestrator()
            orchestrator.tick()
            assertEquals(listOf("a"), calls)

            tracker.issues += area("b", "plugins/tracker-files/src/main/kotlin/loop/tracker/files/FilesTracker.kt")
            advanceTimeBy(POLL_MS)
            orchestrator.tick()
            assertEquals(listOf("a"), calls, "b waits while a's retry is queued")
            assertEquals(listOf("area_busy a (plugins/tracker-files)"), skips("b"))

            // a ends in the tracker; at its due time its retry is dropped, which releases its claim, and b runs that tick.
            tracker.issues[0] = tracker.issues[0].copy(state = "canceled")
            advanceTimeBy(RetryQueue.BASE_BACKOFF_MS)
            val due = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(listOf("a", "b"), calls)
            holds.values.forEach { it.complete(Unit) }
            due.join()
        }

    @Test
    fun `the area stays busy while the holder waits for its verification and frees once the result is delivered`() =
        runTest {
            tracker.issues += area("a", "docs", priority = 1)
            val checks = HeldChecks()
            val waiting = FakeWaitingIssues()
            val orchestrator = orchestrator(submitting(), workflow(kinds = true), checks, waiting)
            val first = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(WaitReason.Verify, waiting.records.values.single().reason)

            tracker.issues += area("b", "docs/agents/issue-tracker.md")
            val second = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(listOf("a"), calls)
            assertEquals(listOf("area_busy a (docs)"), skips("b"))

            // The checks pass and the auto policy delivers a; its claim is released.
            checks.release.complete(Unit)
            runCurrent()
            first.join()
            second.join()
            assertEquals(listOf("id-a" to "review"), tracker.moves)
            tracker.issues[0] = tracker.issues[0].copy(state = "review")
            orchestrator.tick()
            assertEquals(listOf("a", "b"), calls)
        }

    @Test
    fun `a verification a dead process left holds its area while its checks run again`() =
        runTest {
            Files.createDirectories(root.resolve("workspaces/a"))
            tracker.issues += area("a", "cli", priority = 1)
            tracker.issues += area("b", "cli/src/main")
            val checks = HeldChecks()
            val left =
                WaitingIssue(
                    projectId = PROJECT,
                    issueId = "id-a",
                    identifier = "a",
                    reason = WaitReason.Verify,
                    attemptId = AttemptId("dead1"),
                    runId = RunId("r0"),
                    since = FIXED_NOW,
                    data = mapOf("to" to "review"),
                )
            val orchestrator = orchestrator(submitting(), workflow(kinds = true), checks, FakeWaitingIssues(left))
            val first = launch { orchestrator.tick() }
            runCurrent()

            assertEquals(1, checks.calls.size, "a's checks run again")
            assertEquals(emptyList(), calls, "no agent runs while a verifies")
            assertEquals(listOf("area_busy a (cli)"), skips("b"))
            checks.release.complete(Unit)
            first.join()
        }

    private companion object {
        const val POLL_MS = 1000L
        val PROJECT = ProjectId("p1")
        val SESSION = loop.api.SessionId("th1-tu1")
        val TURN = loop.api.TurnId("tu1")
    }
}
