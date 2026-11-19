package loop.core.orchestrator

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * m3-21, 17.5 item 13 and the rate limit half of 17.6 item 4: a runner's rate limit report is recorded as a
 * `rate_limit` event of its attempt, with the ids of its turn, so `loop trace` shows it; it never stops the turn.
 */
class OrchestratorRateLimitTest {
    private val root: Path = createTempDirectory("loop-orchestrator-rate-limit").toRealPath()
    private val sink = FakeSink()

    private fun workflow(): WorkflowFile =
        WorkflowFile(
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Do {{ issue.identifier }}",
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 1)),
        )

    private fun orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
    ): Orchestrator {
        val workflow = workflow()
        var next = 0
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    @Test
    fun `m3-21 a rate limit report is recorded as a rate_limit event of the attempt and its turn, and the turn goes on`() =
        runTest {
            val sessionId = SessionId("th1-tu1")
            val turnId = TurnId("tu1")
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
                        emit(
                            RunnerEvent.RateLimit(
                                sessionId,
                                turnId,
                                limitId = "codex",
                                limitName = "Codex",
                                primary = RunnerEvent.RateLimit.Window(42, 300, Instant.parse("2026-11-17T12:00:00Z")),
                                secondary = RunnerEvent.RateLimit.Window(7, 10_080, null),
                                reached = null,
                            ),
                        )
                        emit(RunnerEvent.RateLimit(sessionId, turnId, null, null, null, null, reached = "rate_limit_reached"))
                        emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                    }
                }

            orchestrator(FakeTracker(listOf(issue("m3-21"))), runner).tick()

            val recorded = sink.events.filter { it.kind == "rate_limit" }
            assertEquals(
                listOf(
                    mapOf(
                        "limit_id" to "codex",
                        "limit_name" to "Codex",
                        "primary_used_percent" to "42",
                        "primary_window_minutes" to "300",
                        "primary_resets_at" to "2026-11-17T12:00:00Z",
                        "secondary_used_percent" to "7",
                        "secondary_window_minutes" to "10080",
                    ),
                    mapOf("reached" to "rate_limit_reached"),
                ),
                recorded.map { it.data },
            )
            recorded.forEach {
                assertEquals(AttemptId("a1"), it.attemptId)
                assertEquals(sessionId, it.sessionId)
                assertEquals(turnId, it.turnId)
            }
            assertEquals(listOf("succeeded"), sink.events.filter { it.kind == "attempt_finished" }.map { it.data["outcome"] })
        }
}
