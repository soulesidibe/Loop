package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.workflow.ContextConfig
import loop.core.workflow.HooksConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.TokenCounters
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.HookRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private val root: Path = createTempDirectory("loop-orchestrator").toRealPath()
    private val launcher: Path = root.resolve("install/bin/loop")
    private val loopHome: Path = root.resolve("home")
    private val logLines = mutableListOf<String>()

    private fun workflow(
        active: List<String> = listOf("todo"),
        terminal: List<String> = listOf("done", "canceled"),
        labels: List<String> = listOf("ready-for-agent"),
        prompt: String = "Work on {{ issue.identifier }}: {{ issue.title }}",
        context: ContextConfig = ContextConfig(),
    ): WorkflowFile =
        WorkflowFile(
            config = WorkflowConfig(context = context),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = active,
            terminalStates = terminal,
            requiredLabels = labels,
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = prompt,
        )

    private fun orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner = FakeRunner(),
        sink: FakeSink = FakeSink(),
        workflow: WorkflowFile = workflow(),
        workspaces: WorkspaceManager = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
        preflight: PreflightCheck = PreflightCheck { PreflightResult.Ok(workflow) },
        parked: FakeParkedIssues = FakeParkedIssues(),
    ): Orchestrator {
        var next = 0
        val clock = FixedClock()
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = workspaces,
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = ProjectId("p1"),
            launcher = launcher,
            loopHome = loopHome,
            hostPath = "/usr/bin",
            preflight = preflight,
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    private fun dispatched(runner: FakeRunner): List<String> = runner.calls.map { it.first.issue.identifier }

    private fun List<RecordedEvent>.phases(): List<RecordedEvent> = filter { it.kind == "phase" }

    /** The events of attempts, without the run level preflight events. */
    private fun List<RecordedEvent>.attemptEvents(): List<RecordedEvent> = filter { it.kind != "preflight" }

    private fun List<RecordedEvent>.phase(name: AttemptPhase): RecordedEvent = single { it.phase == name.name }

    // Preflight, spec 6.3 and 16.2, plan D8 and D15.

    @Test
    fun `failed preflight skips dispatch for that tick`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner()
            val sink = FakeSink()
            val failed = PreflightResult.Failed("invalid_config", "agent.max_turns must be a positive integer, got 0")

            orchestrator(tracker, runner, sink, preflight = PreflightCheck { failed }).tick()

            assertEquals(emptyList(), tracker.requestedStates)
            assertEquals(emptyList(), dispatched(runner))
            val event = sink.events.single { it.kind == "preflight" }
            assertEquals(RunId("r1"), event.runId)
            assertNull(event.issueId)
            assertNull(event.attemptId)
            assertEquals(
                mapOf(
                    "result" to "failed",
                    "error" to "invalid_config",
                    "message" to "agent.max_turns must be a positive integer, got 0",
                ),
                event.data,
            )
            assertTrue(
                logLines.any {
                    "level=error event=preflight outcome=failed" in it &&
                        "error=invalid_config" in it &&
                        "reason=\"agent.max_turns must be a positive integer, got 0\"" in it
                },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `preflight runs at the start of each tick and is recorded`() =
        runTest {
            val tracker = FakeTracker(emptyList())
            val sink = FakeSink()
            var checks = 0
            val fetchesAtCheck = mutableListOf<Int>()
            val preflight =
                PreflightCheck {
                    checks++
                    fetchesAtCheck += tracker.requestedStates.size
                    PreflightResult.Ok(workflow())
                }
            val orchestrator = orchestrator(tracker, sink = sink, preflight = preflight)

            orchestrator.tick()
            orchestrator.tick()

            assertEquals(2, checks)
            assertEquals(listOf(0, 1), fetchesAtCheck)
            val events = sink.events.filter { it.kind == "preflight" }
            assertEquals(listOf(mapOf("result" to "ok"), mapOf("result" to "ok")), events.map { it.data })
            assertTrue(logLines.none { "event=preflight" in it }, logLines.joinToString("\n"))
        }

    @Test
    fun `dispatch resumes after a failed preflight`() =
        runTest {
            val runner = FakeRunner()
            val results =
                ArrayDeque(
                    listOf(
                        PreflightResult.Failed("workflow_parse_error", "front matter has no closing ---"),
                        PreflightResult.Failed("workflow_parse_error", "front matter has no closing ---"),
                        PreflightResult.Ok(workflow()),
                    ),
                )
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, preflight = PreflightCheck { results.removeFirst() })

            orchestrator.tick()
            orchestrator.tick()
            assertEquals(emptyList(), dispatched(runner))
            orchestrator.tick()

            assertEquals(listOf("m1-02"), dispatched(runner))
            assertTrue(
                logLines.any { "level=info event=preflight outcome=completed" in it && "skipped_ticks=2" in it },
                logLines.joinToString("\n"),
            )
        }

    // Eligibility, spec 8.2 and plan D13.

    @Test
    fun `candidates are fetched by the active states`() =
        runTest {
            val tracker = FakeTracker(emptyList())

            orchestrator(tracker).tick()

            assertEquals(listOf(listOf("todo")), tracker.requestedStates)
        }

    @Test
    fun `an issue in an active state with the required labels is dispatched`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            assertEquals(listOf("m1-02"), dispatched(runner))
        }

    @Test
    fun `an issue without a required label is not dispatched`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02", labels = listOf("ready-for-human")))), runner).tick()

            assertEquals(emptyList(), dispatched(runner))
        }

    @Test
    fun `an issue in a non active state is not dispatched`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02", state = "review"))), runner).tick()

            assertEquals(emptyList(), dispatched(runner))
        }

    @Test
    fun `an issue in a terminal state is not dispatched even when the state is also active`() =
        runTest {
            val runner = FakeRunner()
            val workflow = workflow(active = listOf("todo", "done"))

            orchestrator(FakeTracker(listOf(issue("m1-02", state = "done"))), runner, workflow = workflow).tick()

            assertEquals(emptyList(), dispatched(runner))
        }

    @Test
    fun `states match case insensitively`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02", state = "Todo"))), runner).tick()

            assertEquals(listOf("m1-02"), dispatched(runner))
        }

    @Test
    fun `required labels match case insensitively`() =
        runTest {
            val runner = FakeRunner()
            val workflow = workflow(labels = listOf(" Ready-For-Agent "))

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, workflow = workflow).tick()

            assertEquals(listOf("m1-02"), dispatched(runner))
        }

    @Test
    fun `an issue returned twice is dispatched once in a tick`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"), issue("m1-02"))), runner).tick()

            assertEquals(listOf("m1-02"), dispatched(runner))
        }

    @Test
    fun `a running issue is not dispatched by another tick`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        gate.await()
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner)

            val first = async { orchestrator.tick() }
            runCurrent()
            val second = orchestrator.tick()
            gate.complete(Unit)
            first.await()

            assertEquals(emptyList(), second.attempts)
            assertEquals(listOf("m1-02"), dispatched(runner))
        }

    // Sorting, spec 8.2 and plan D14.

    @Test
    fun `issues are dispatched by priority bucket then created at then identifier`() =
        runTest {
            val early = Instant.parse("2026-01-01T00:00:00Z")
            val late = Instant.parse("2026-02-01T00:00:00Z")
            val runner = FakeRunner()
            val issues =
                listOf(
                    issue("a", priority = null),
                    issue("b", priority = 2),
                    issue("c", priority = 1, createdAt = late),
                    issue("d", priority = 1, createdAt = early),
                    issue("e", priority = 5),
                    issue("f", priority = 1),
                )

            orchestrator(FakeTracker(issues), runner).tick()

            assertEquals(listOf("d", "c", "f", "b", "a", "e"), dispatched(runner))
        }

    // Attempt, spec 7.2 and 16.5.

    @Test
    fun `attempt started is recorded before the runner is called`() =
        runTest {
            val sink = FakeSink()
            val kindsAtCall = mutableListOf<String>()
            val runner = FakeRunner()
            runner.onCall = { kindsAtCall += sink.events.attemptEvents().map { it.kind } }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            assertEquals("attempt_started", kindsAtCall.first())
        }

    @Test
    fun `phases are recorded in the spec order`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                listOf(
                    "PreparingWorkspace",
                    "BuildingPrompt",
                    "LaunchingAgentProcess",
                    "InitializingSession",
                    "StreamingTurn",
                    "Finishing",
                    "Succeeded",
                ),
                sink.events.phases().map { it.phase },
            )
        }

    @Test
    fun `attempt started comes first and attempt finished last`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                "attempt_started",
                sink.events
                    .attemptEvents()
                    .first()
                    .kind,
            )
            // The retry_scheduled that follows carries the attempt id too (spec 16.6).
            assertEquals(
                "attempt_finished",
                sink.events
                    .attemptEvents()
                    .last { it.kind != "retry_scheduled" }
                    .kind,
            )
        }

    @Test
    fun `every event carries the run project issue and attempt ids`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertTrue(sink.events.attemptEvents().isNotEmpty())
            sink.events.attemptEvents().forEach {
                assertEquals(RunId("r1"), it.runId, it.toString())
                assertEquals(ProjectId("p1"), it.projectId, it.toString())
                assertEquals("id-m1-02", it.issueId, it.toString())
                assertEquals("m1-02", it.issueIdentifier, it.toString())
                assertEquals("a1", it.attemptId?.value, it.toString())
            }
        }

    @Test
    fun `phases before the session started carry no session or turn id`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            sink.events.phases().take(2).forEach {
                assertNull(it.sessionId, it.toString())
                assertNull(it.turnId, it.toString())
            }
        }

    @Test
    fun `initializing session carries the session id from the runner`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                SESSION,
                sink.events
                    .phases()
                    .phase(AttemptPhase.InitializingSession)
                    .sessionId,
            )
        }

    @Test
    fun `streaming turn carries the session id`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                SESSION,
                sink.events
                    .phases()
                    .phase(AttemptPhase.StreamingTurn)
                    .sessionId,
            )
        }

    @Test
    fun `finishing carries the turn id from the turn result`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                TURN,
                sink.events
                    .phases()
                    .phase(AttemptPhase.Finishing)
                    .turnId,
            )
        }

    @Test
    fun `finishing records the exit code`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                "0",
                sink.events
                    .phases()
                    .phase(AttemptPhase.Finishing)
                    .data["exit_code"],
            )
        }

    @Test
    fun `preparing workspace creates the workspace under the root by key`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1 02"))), runner).tick()

            val workspace =
                runner.calls
                    .single()
                    .first.workspace
            assertTrue(workspace.isDirectory())
            assertEquals(root.resolve("workspaces"), workspace.parent)
            assertTrue(workspace.fileName.toString().startsWith("m1_02-"), workspace.toString())
        }

    @Test
    fun `17_2 a workspace path that escapes the root fails the attempt before the agent launches`() =
        runTest {
            val runner = FakeRunner()
            val sink = FakeSink()
            val outside = root.resolve("outside").createDirectories()
            root
                .resolve("workspaces")
                .createDirectories()
                .resolve("m1-02")
                .createSymbolicLinkPointingTo(outside)

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            assertEquals(emptyList(), runner.calls)
            val phases = sink.events.phases()
            assertEquals(listOf(AttemptPhase.PreparingWorkspace.name, "Failed"), phases.map { it.phase })
            assertEquals("workspace m1-02 is not inside the root ${root.resolve("workspaces")}", phases.last().data["reason"])
            assertEquals(emptyList(), outside.listDirectoryEntries())
        }

    @Test
    fun `the runner receives the rendered prompt`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            assertEquals("Work on m1-02: Title of m1-02", runner.calls.single().second)
        }

    @Test
    fun `building prompt records the prompt length and not its content`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val building = sink.events.phases().phase(AttemptPhase.BuildingPrompt)
            assertEquals("Work on m1-02: Title of m1-02".length.toString(), building.data["prompt_chars"])
            assertTrue(sink.events.none { event -> event.data.values.any { "Work on" in it } })
        }

    @Test
    fun `building prompt records the size of every prompt slot, in priority order (m1-17)`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val building = sink.events.phases().phase(AttemptPhase.BuildingPrompt)
            assertEquals(
                listOf(
                    "prompt_chars" to "29",
                    "slot_template_chars" to "Work on : ".length.toString(),
                    "slot_issue_chars" to "m1-02Title of m1-02".length.toString(),
                    "slot_verify_findings_chars" to "0",
                    "slot_session_brief_chars" to "0",
                    "slot_lessons_chars" to "0",
                    "slot_context_sources_chars" to "0",
                ),
                building.data.toList().take(7),
            )
        }

    @Test
    fun `m3-13 building prompt records the tokens of the prompt and of every slot, the limit, over_limit and the counter`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val building = sink.events.phases().phase(AttemptPhase.BuildingPrompt)
            // 29 characters, 10 tokens; "Work on : " is 10 characters, 4 tokens. The fake runner names no model, so the
            // limit is 0.05 of the smallest window, the 475000 of the defaults (m4-02).
            assertEquals(
                listOf(
                    "prompt_tokens" to "10",
                    "slot_template_tokens" to "4",
                    "slot_issue_tokens" to "6",
                    "slot_verify_findings_tokens" to "0",
                    "slot_session_brief_tokens" to "0",
                    "slot_lessons_tokens" to "0",
                    "slot_context_sources_tokens" to "0",
                    "prompt_limit_tokens" to "23750",
                    "over_limit" to "false",
                    "token_counter" to "estimate",
                ),
                building.data.toList().drop(7),
            )
        }

    @Test
    fun `m3-14 a prompt over its limit is sent cut, and building prompt records the cut of every slot`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner()
            val issuesDir = root.resolve(".loop/issues").createDirectories()
            val file = issuesDir.resolve("m3-14.md").also { it.writeText("---\nid: m3-14\n---\n") }
            val description = (1..200).joinToString("\n") { "Description line $it of the issue." }
            val prompt = "Work on {{ issue.identifier }}: {{ issue.title }}\n{{ issue.description }}"
            // The fake runner names no model, so the limit is 0.05 of the smallest window: 200 tokens.
            val workflow = workflow(prompt = prompt, context = ContextConfig(windows = mapOf("test/small" to 4000L)))

            orchestrator(FakeTracker(listOf(issue("m3-14", description = description))), runner, sink, workflow).tick()

            val sent = runner.calls.single().second
            val counter = TokenCounters.estimate
            assertTrue(counter.count(sent) <= 200, "${counter.count(sent)} tokens: $sent")
            assertTrue(sent.startsWith("Work on m3-14: Title of m3-14\nDescription line 1 of the issue.\n"), sent)
            assertTrue(sent.endsWith("left out. Read the full text in the issue file $file.)"), sent)
            val data =
                sink.events
                    .phases()
                    .phase(AttemptPhase.BuildingPrompt)
                    .data
            val whole = "Work on m3-14: Title of m3-14\n$description"
            assertEquals(sent.length.toString(), data["prompt_chars"])
            assertEquals(counter.count(sent).toString(), data["prompt_tokens"])
            assertEquals("200", data["prompt_limit_tokens"])
            assertEquals("false", data["over_limit"], "the sent prompt fits")
            assertEquals(
                listOf(
                    "cut_template_tokens" to "0",
                    "cut_issue_tokens" to (counter.count(whole) - counter.count(sent)).toString(),
                    "cut_verify_findings_tokens" to "0",
                    "cut_session_brief_tokens" to "0",
                    "cut_lessons_tokens" to "0",
                    "cut_context_sources_tokens" to "0",
                ),
                data.toList().takeLast(6),
            )
        }

    @Test
    fun `slots that cannot be measured are logged and the attempt runs its prompt as rendered (m1-17)`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner()
            val workflow = workflow(prompt = "First label: {{ issue.labels[0] }}")

            val summary = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, workflow).tick().attempts.single()

            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals("First label: ready-for-agent", runner.calls.single().second)
            val building = sink.events.phases().phase(AttemptPhase.BuildingPrompt)
            assertEquals(
                mapOf(
                    "prompt_chars" to "28",
                    "prompt_tokens" to "10",
                    "prompt_limit_tokens" to "23750",
                    "over_limit" to "false",
                    "token_counter" to "estimate",
                ),
                building.data,
                "m3-13: the prompt's tokens are counted even when its slots cannot be measured",
            )
            assertTrue(
                logLines.any { "event=prompt_slots outcome=failed" in it && "the template does not render without the issue" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a prompt that fails to render fails the attempt without launching the runner`() =
        runTest {
            val runner = FakeRunner()

            val workflow = workflow(prompt = "{{ nope }}")

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, workflow = workflow).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            assertEquals(emptyList(), runner.calls)
        }

    @Test
    fun `runner output is recorded with its stream and its length`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val output = sink.events.single { it.kind == "runner_output" }
            assertEquals(mapOf("stream" to "stdout", "line" to "hello", "chars" to "5"), output.data)
        }

    @Test
    fun `a long runner output line is truncated to 2000 characters`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stderr, "x".repeat(5000)))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            assertEquals(
                2000,
                sink.events
                    .single { it.kind == "runner_output" }
                    .data["line"]
                    ?.length,
            )
        }

    @Test
    fun `a completed turn gives a succeeded attempt with the issue state after it`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                FakeRunner { ctx, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        tracker.moveIssue(ctx.issue.id, "review")
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            val result = orchestrator(tracker, runner).tick()

            val summary = result.attempts.single()
            assertEquals("m1-02", summary.issueIdentifier)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals("review", summary.issueState)
        }

    @Test
    fun `a failed turn gives a failed phase with the exit code reason`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
                    }
                }

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            assertEquals(
                "exit_code=1",
                sink.events
                    .phases()
                    .last()
                    .data["reason"],
            )
            assertEquals(
                "Failed",
                sink.events
                    .phases()
                    .last()
                    .phase,
            )
        }

    @Test
    fun `a failed turn leaves the issue where it is`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
                    }
                }

            orchestrator(tracker, runner).tick()

            assertEquals(emptyList(), tracker.moves)
            assertEquals("todo", tracker.issues.single().state)
        }

    @Test
    fun `a runner flow that ends without a turn result fails the attempt`() =
        runTest {
            val runner = FakeRunner { _, _ -> flow { emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN)) } }

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
        }

    // Runner env, plan D7 and D10.

    @Test
    fun `the runner env carries the attempt ids and paths`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            val env =
                runner.calls
                    .single()
                    .first.env
            assertEquals(loopHome.toString(), env["LOOP_HOME"])
            assertEquals(root.resolve("WORKFLOW.md").toString(), env["LOOP_WORKFLOW"])
            assertEquals("p1", env["LOOP_PROJECT_ID"])
            assertEquals("r1", env["LOOP_RUN_ID"])
            assertEquals("id-m1-02", env["LOOP_ISSUE_ID"])
            assertEquals("m1-02", env["LOOP_ISSUE_IDENTIFIER"])
            assertEquals("a1", env["LOOP_ATTEMPT_ID"])
            assertEquals("m1-02", env["LOOP_WORKSPACE_KEY"])
            assertEquals(launcher.toString(), env["LOOP_LAUNCHER"])
        }

    @Test
    fun `the runner env path starts with the launcher folder`() =
        runTest {
            val runner = FakeRunner()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).tick()

            assertEquals(
                "${launcher.parent}${File.pathSeparator}/usr/bin",
                runner.calls
                    .single()
                    .first.env["PATH"],
            )
        }

    // Sink failures, plan U3.

    @Test
    fun `a sink that fails does not stop the attempt`() =
        runTest {
            val sink = FakeSink().apply { failure = IOException("disk full") }

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(AttemptOutcome.Succeeded, result.attempts.single().outcome)
        }

    @Test
    fun `a sink that throws does not stop the attempt`() =
        runTest {
            val sink = FakeSink().apply { throwOnRecord = IllegalStateException("disk full") }

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(AttemptOutcome.Succeeded, result.attempts.single().outcome)
        }

    @Test
    fun `a sink failure is logged as a warning`() =
        runTest {
            val sink = FakeSink().apply { failure = IOException("disk full") }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertTrue(
                logLines.any { "level=warn event=sink outcome=failed" in it && "sink=sqlite" in it },
                logLines.joinToString("\n"),
            )
        }

    // Logs, plan U3 and acceptance criterion 5.

    @Test
    fun `an idle tick logs outcome idle with zero candidates`() =
        runTest {
            orchestrator(FakeTracker(emptyList())).tick()

            assertTrue(
                logLines.any { "event=poll outcome=idle" in it && "project_id=p1" in it && "candidates=0" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `an attempt log line carries the issue id the identifier and the session id`() =
        runTest {
            orchestrator(FakeTracker(listOf(issue("m1-02")))).tick()

            assertTrue(
                logLines.any { "issue_id=id-m1-02" in it && "issue_identifier=m1-02" in it && "session_id=th1-tu1" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a tracker failure skips dispatch and logs a failed poll`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            tracker.fetchFailure = TrackerError.Unavailable(IOException("gone"))
            val runner = FakeRunner()

            orchestrator(tracker, runner).tick()

            assertEquals(emptyList(), runner.calls)
            assertTrue(logLines.any { "event=poll outcome=failed" in it }, logLines.joinToString("\n"))
        }

    // Daemon, spec 16.2 and plan D15.

    @Test
    fun `run until cancelled ticks at once then every polling interval`() =
        runTest {
            val tracker = FakeTracker(emptyList())
            val job = launch { orchestrator(tracker).runUntilCancelled() }

            runCurrent()
            assertEquals(1, tracker.requestedStates.size)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(2, tracker.requestedStates.size)

            job.cancelAndJoin()
        }

    @Test
    fun `cancelling run until cancelled cancels the runner flow`() =
        runTest {
            var cancelled = false
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                }
            val job = launch { orchestrator(FakeTracker(listOf(issue("m1-02"))), runner).runUntilCancelled() }

            runCurrent()
            job.cancelAndJoin()

            assertTrue(cancelled)
        }

    // Closing bookkeeping, review round 1 (R1 to R3).

    private fun assertClosedWith(
        sink: FakeSink,
        phase: String,
        outcome: String,
    ) {
        // A failed attempt is followed by its retry bookkeeping (m1-07), which is not part of the closing.
        val closing = sink.events.last { it.kind != "retry_scheduled" }
        assertEquals("attempt_finished", closing.kind, sink.events.joinToString("\n"))
        assertEquals(outcome, closing.data["outcome"])
        assertEquals(
            phase,
            sink.events
                .phases()
                .last()
                .phase,
        )
    }

    private fun hangingTurn(): FakeRunner =
        FakeRunner { _, _ ->
            flow {
                emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                awaitCancellation()
            }
        }

    @Test
    fun `a tracker that throws on the post run refresh still closes the attempt as succeeded`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            tracker.onFetchByIds = { throw IllegalStateException("tracker broke") }

            val result = orchestrator(tracker, sink = sink).tick()

            assertNull(result.attempts.single().issueState)
            assertClosedWith(sink, phase = "Succeeded", outcome = "succeeded")
        }

    @Test
    fun `a failed post run refresh is logged as a tracker warning`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            tracker.onFetchByIds = { throw IllegalStateException("tracker broke") }

            orchestrator(tracker).tick()

            assertTrue(
                logLines.any { "level=warn event=tracker outcome=failed" in it && "tracker broke" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a hook runner that throws fails the attempt with an internal error`() =
        runTest {
            val sink = FakeSink()
            val workflow = workflow()
            val workspaces =
                WorkspaceManager(
                    Workspaces(workflow.workspaceRoot),
                    HooksConfig(afterCreate = "populate"),
                    HookRunner { _, _, _, _, _, _ -> throw IllegalArgumentException("bad path") },
                )

            val result = orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink, workspaces = workspaces).tick()

            assertEquals(AttemptOutcome.Failed, result.attempts.single().outcome)
            assertEquals(
                "internal error: bad path",
                sink.events
                    .phases()
                    .last()
                    .data["reason"],
            )
            assertClosedWith(sink, phase = "Failed", outcome = "failed")
        }

    @Test
    fun `a cancel during the turn records failed with reason canceled then attempt finished`() =
        runTest {
            val sink = FakeSink()
            val job = launch { orchestrator(FakeTracker(listOf(issue("m1-02"))), hangingTurn(), sink).tick() }

            runCurrent()
            job.cancelAndJoin()

            assertEquals(
                "canceled",
                sink.events
                    .phases()
                    .last()
                    .data["reason"],
            )
            assertClosedWith(sink, phase = "Failed", outcome = "canceled")
        }

    @Test
    fun `a cancel during the post run refresh still closes the attempt`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            tracker.onFetchByIds = { awaitCancellation() }
            val job = launch { orchestrator(tracker, sink = sink).tick() }

            runCurrent()
            job.cancelAndJoin()

            assertEquals(
                "canceled",
                sink.events
                    .phases()
                    .last()
                    .data["reason"],
            )
            assertClosedWith(sink, phase = "Failed", outcome = "canceled")
        }

    @Test
    fun `streaming turn carries the turn id from session started`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            assertEquals(
                TURN,
                sink.events
                    .phases()
                    .phase(AttemptPhase.StreamingTurn)
                    .turnId,
            )
        }

    @Test
    fun `runner output carries the session id and the turn id`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val output = sink.events.single { it.kind == "runner_output" }
            assertEquals(SESSION, output.sessionId)
            assertEquals(TURN, output.turnId)
        }

    @Test
    fun `a failed turn with a runner reason finishes the attempt with that reason`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "turn_failed: error_max_turns"))
                    }
                }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            val finished = sink.events.single { it.kind == "attempt_finished" }
            assertEquals("turn_failed: error_max_turns", finished.data["reason"])
        }

    @Test
    fun `runner usage is recorded as a usage event with its totals`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                        emit(
                            RunnerEvent.Usage(
                                SESSION,
                                TURN,
                                inputTokens = 120,
                                outputTokens = 45,
                                cacheReadInputTokens = 3000,
                                cacheCreationInputTokens = 800,
                                totalCostUsd = 0.25,
                            ),
                        )
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            val usage = sink.events.single { it.kind == "usage" }
            assertEquals(
                mapOf(
                    "input_tokens" to "120",
                    "output_tokens" to "45",
                    "cache_read_input_tokens" to "3000",
                    "cache_creation_input_tokens" to "800",
                    "total_cost_usd" to "0.25",
                    // No ceiling by default (m1-38), so no max_tokens.
                    "attempt_tokens" to "3965",
                ),
                usage.data,
            )
            assertEquals(SESSION, usage.sessionId)
            assertEquals(TURN, usage.turnId)
        }

    @Test
    fun `initializing session records the runtime session id when the runner has one`() =
        runTest {
            val sink = FakeSink()
            val runner =
                FakeRunner { _, _ ->
                    flow {
                        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN, runtimeSessionId = "claude-42"))
                        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
                    }
                }

            orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink).tick()

            val initializing = sink.events.phases().phase(AttemptPhase.InitializingSession)
            assertEquals(mapOf("runtime_session_id" to "claude-42"), initializing.data)
        }

    @Test
    fun `initializing session has no runtime session id when the runner has none`() =
        runTest {
            val sink = FakeSink()

            orchestrator(FakeTracker(listOf(issue("m1-02"))), sink = sink).tick()

            val initializing = sink.events.phases().phase(AttemptPhase.InitializingSession)
            assertEquals(emptyMap(), initializing.data)
        }
}
