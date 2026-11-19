package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
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
import loop.api.TrackerError
import loop.core.log.KeyValueLog
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Evidence
import loop.core.verify.EvidenceWriter
import loop.core.verify.Fingerprinter
import loop.core.verify.VerifyFindings
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.ContextConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.TokenCounters
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m2-03: in Loop mode a move out of the active states is intercepted at the drain and verified first. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopModeDrainTest {
    private val root: Path = createTempDirectory("loop-mode").toRealPath()

    private fun workflow(
        checks: List<CheckConfig>? = listOf(CheckConfig.Command("./gradlew test")),
        maxTurns: Int = 1,
        template: String = "Work on {{ issue.identifier }}",
        maxAttempts: Int = 5,
        holdout: List<CheckConfig> = emptyList(),
        toolOutputTokens: Int = ContextConfig().toolOutputTokens,
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = maxTurns),
                    kinds = checks?.let { KindsConfig(KindConfig(it, holdout = holdout, delivery = DeliveryConfig(DeliveryPolicy.Auto))) },
                    retries = RetriesConfig(maxAttempts = maxAttempts),
                    context = ContextConfig(toolOutputTokens = toolOutputTokens),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = template,
        )

    /** A scripted check runner: exit codes in order (the last one repeats), every call recorded. */
    private class ScriptedChecks(
        vararg val exitCodes: Int?,
    ) : CheckRunner {
        val calls = mutableListOf<Triple<CheckConfig, Path, Map<String, String>>>()
        var onRun: suspend () -> Unit = {}
        var startError: String? = null
        var timedOut: Boolean = false
        var crash: Exception? = null
        var output: String = "check output\n"

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            calls += Triple(check, cwd, env)
            onRun()
            crash?.let { throw it }
            val code = exitCodes.getOrElse(calls.size - 1) { exitCodes.last() }
            return CheckResult(code, timedOut = timedOut, durationMs = 1200, startError = startError, output = output)
        }
    }

    /** Keeps every evidence it is given and names it like the evidence files do; [failure] makes each write throw. */
    private class RecordedEvidence : EvidenceWriter {
        val written = mutableListOf<Triple<ProjectId, AttemptId, Evidence>>()
        var failure: Exception? = null

        override fun write(
            projectId: ProjectId,
            attemptId: AttemptId,
            evidence: Evidence,
        ): String {
            failure?.let { throw it }
            written += Triple(projectId, attemptId, evidence)
            return "${attemptId.value}.evidence-${written.size}.log"
        }
    }

    private val evidence = RecordedEvidence()

    private val parked = FakeParkedIssues()

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        checks: CheckRunner,
        sink: FakeSink = FakeSink(),
        workflow: WorkflowFile = workflow(),
        fingerprints: Fingerprinter = Fingerprinter { Result.success(FINGERPRINT) },
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
            projectId = ProjectId("p1"),
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
            evidence = evidence,
            fingerprinter = fingerprints,
        )
    }

    /** A runner with a session whose turn 1 queues [requests] the way `loop tool` does, in this order. */
    private fun queueing(vararg requests: ToolRequest): FakeRunner {
        val runner = FakeRunner()
        var config: Path? = null
        runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
        var now = 1_000L
        runner.sessionScript = { turn, _ ->
            if (turn == 1) {
                val queue = ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ })
                requests.forEach { queue.enqueue(it) }
            }
            sessionTurn(turn)
        }
        return runner
    }

    private fun FakeSink.kinds(): List<String> =
        events.map { it.kind }.filter {
            it in
                setOf(
                    "result_submitted",
                    "check_started",
                    "check_finished",
                    "verdict",
                    "issue_moved",
                    "issue_commented",
                    "tool_request_failed",
                )
        }

    @Test
    fun `a passing check moves the issue the agent asked for, after the check events`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink).tick()

            assertEquals(listOf("id-m2-03" to "review"), tracker.moves)
            assertEquals(listOf("result_submitted", "check_started", "check_finished", "verdict", "issue_moved"), sink.kinds())
            val finished = sink.events.single { it.kind == "check_finished" }
            assertEquals(
                mapOf(
                    "check" to "0",
                    "command" to "./gradlew test",
                    "exit_code" to "0",
                    "duration_ms" to "1200",
                    "timed_out" to "false",
                    "fingerprint" to FINGERPRINT,
                    "evidence" to "a1.evidence-1.log",
                ),
                finished.data,
            )
            assertEquals(mapOf("to" to "review"), sink.events.single { it.kind == "result_submitted" }.data)
            val (_, cwd, env) = checks.calls.single()
            assertEquals(root.resolve("workspaces/m2-03"), cwd)
            assertEquals("m2-03", env["LOOP_ISSUE_IDENTIFIER"])
            assertFalse("LOOP_TOOL_CONFIG" in env, "a check cannot queue tool requests")
            assertFalse("LOOP_LAUNCHER" in env)
            assertFalse("PATH" in env, "the cli adds the host PATH, never the launcher's folder")
        }

    @Test
    fun `a failing check leaves the issue active and records no failed request, a comment still applies`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()

            val summary =
                orchestrator(tracker, queueing(ToolRequest.Move("review"), ToolRequest.Comment("ready")), ScriptedChecks(1), sink)
                    .tick()
                    .attempts
                    .single()

            assertEquals(emptyList(), tracker.moves)
            assertEquals("todo", summary.refreshedIssue?.state)
            assertEquals(
                listOf(
                    "result_submitted",
                    "check_started",
                    "check_finished",
                    "check_started",
                    "check_finished",
                    "verdict",
                    "issue_commented",
                ),
                sink.kinds(),
            )
            assertEquals(listOf("1", "1"), sink.events.filter { it.kind == "check_finished" }.map { it.data["exit_code"] })
        }

    @Test
    fun `a move to a terminal state is intercepted, a move to needs_info or an active state is not`() =
        runTest {
            val terminal = FakeTracker(listOf(issue("m2-03")))
            orchestrator(terminal, queueing(ToolRequest.Move("done")), ScriptedChecks(1)).tick()
            assertEquals(emptyList(), terminal.moves)

            val checks = ScriptedChecks(1)
            val needsInfo = FakeTracker(listOf(issue("m2-03")))
            orchestrator(needsInfo, queueing(ToolRequest.Move("Needs_Info")), checks).tick()
            assertEquals(listOf("id-m2-03" to "Needs_Info"), needsInfo.moves)

            val active = FakeTracker(listOf(issue("m2-03")))
            orchestrator(active, queueing(ToolRequest.Move("in_progress")), checks).tick()
            assertEquals(listOf("id-m2-03" to "in_progress"), active.moves)
            assertEquals(0, checks.calls.size)
        }

    @Test
    fun `checks stop at the first failure and a second move in the same drain reuses the result`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()
            val checks = ScriptedChecks(1)
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            orchestrator(tracker, queueing(ToolRequest.Move("review"), ToolRequest.Move("done")), checks, sink, workflow).tick()

            assertEquals(
                listOf("./a.sh", "./a.sh"),
                checks.calls.map {
                    (it.first as CheckConfig.Command).run
                },
                "the failing check and its rerun, never ./b.sh",
            )
            assertEquals(emptyList(), tracker.moves)
            assertEquals(2, sink.events.count { it.kind == "result_submitted" })
        }

    @Test
    fun `no check, a check that cannot start and a missing check runner all refuse the move`() =
        runTest {
            val empty = FakeTracker(listOf(issue("m2-03")))
            orchestrator(empty, queueing(ToolRequest.Move("review")), ScriptedChecks(0), workflow = workflow(emptyList())).tick()
            assertEquals(emptyList(), empty.moves)

            val sink = FakeSink()
            // Each case parks its issue as `unverified` (m2-08), so each has its own.
            val broken = FakeTracker(listOf(issue("m2-03b")))
            val checks = ScriptedChecks(null).also { it.startError = "requires perl on PATH: not found" }
            orchestrator(broken, queueing(ToolRequest.Move("review")), checks, sink).tick()
            assertEquals(emptyList(), broken.moves)
            val finished = sink.events.single { it.kind == "check_finished" }.data
            assertEquals("requires perl on PATH: not found", finished["reason"])
            assertFalse("exit_code" in finished)

            val none = FakeTracker(listOf(issue("m2-03c")))
            orchestrator(none, queueing(ToolRequest.Move("review")), CheckRunner.NONE).tick()
            assertEquals(emptyList(), none.moves)
        }

    @Test
    fun `a human change during the check wins over a passing check`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            checks.onRun = { tracker.issues[0] = tracker.issues[0].copy(state = "canceled") }

            orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink).tick()

            assertEquals(emptyList(), tracker.moves)
            assertEquals(
                mapOf("request" to "move", "to" to "review", "reason" to "the issue is no longer active, it is in canceled"),
                sink.events.single { it.kind == "tool_request_failed" }.data,
            )
        }

    @Test
    fun `without kinds the move applies as before and no check runs`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val checks = ScriptedChecks(1)

            orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, workflow = workflow(checks = null)).tick()

            assertEquals(listOf("id-m2-03" to "review"), tracker.moves)
            assertEquals(0, checks.calls.size)
        }

    @Test
    fun `a sessionless turn is verified inside the attempt, so a cancel stops the check`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()
            val runner = FakeRunner()
            runner.onCall =
                { ctx -> ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review")) }
            val checks = ScriptedChecks(0).also { it.onRun = { awaitCancellation() } }
            val orchestrator = orchestrator(tracker, runner, checks, sink)

            val job = launch { orchestrator.tick() }
            runCurrent()
            assertEquals(1, checks.calls.size)
            job.cancelAndJoin()

            assertEquals(emptyList(), tracker.moves)
            val finished = sink.events.single { it.kind == "check_finished" }.data
            assertEquals("true", finished["canceled"])
            assertEquals(
                listOf(mapOf("request" to "move", "to" to "review")),
                sink.events.filter { it.kind == "tool_request_dropped" }.map { it.data },
            )
        }

    @Test
    fun `reconciliation during a check stops it and the move is dropped`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-03")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0).also { it.onRun = { awaitCancellation() } }
            val job = launch { orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink).runUntilCancelled() }
            runCurrent()
            assertEquals(1, checks.calls.size)

            tracker.issues[0] = tracker.issues[0].copy(state = "canceled")
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(emptyList(), tracker.moves)
            assertTrue(sink.events.any { it.kind == "check_finished" && it.data["canceled"] == "true" }, sink.kinds().toString())
            assertEquals(1, sink.events.count { it.kind == "tool_request_dropped" })
            job.cancelAndJoin()
        }

    @Test
    fun `a verification whose checks all pass records a verified verdict with the fingerprint and every evidence`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-05")))
            val sink = FakeSink()
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            orchestrator(tracker, queueing(ToolRequest.Move("review")), ScriptedChecks(0), sink, workflow).tick()

            assertEquals(
                mapOf(
                    "verdict" to "verified",
                    "fingerprint" to FINGERPRINT,
                    "checks" to "2",
                    "evidence" to "a1.evidence-1.log,a1.evidence-2.log",
                ),
                sink.events.single { it.kind == "verdict" }.data,
            )
            assertEquals(listOf("id-m2-05" to "review"), tracker.moves)
        }

    @Test
    fun `a failing check gives a refuted verdict that names only the checks that ran`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-05")))
            val sink = FakeSink()
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            orchestrator(tracker, queueing(ToolRequest.Move("review")), ScriptedChecks(1), sink, workflow).tick()

            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("refuted", verdict["verdict"])
            assertEquals("1", verdict["checks"])
            assertEquals("a1.evidence-1.log,a1.evidence-2.log", verdict["evidence"], "the failing run and its rerun")
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a check that cannot start gives an unverified verdict`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(null).also { it.startError = "requires perl on PATH: not found" }

            orchestrator(FakeTracker(listOf(issue("m2-05"))), queueing(ToolRequest.Move("review")), checks, sink).tick()

            assertEquals("unverified", sink.events.single { it.kind == "verdict" }.data["verdict"])
            assertEquals(
                "requires perl on PATH: not found",
                evidence.written
                    .single()
                    .third.reason,
            )
        }

    @Test
    fun `each check run hands its full output, command, result and fingerprint to the evidence writer`() =
        runTest {
            val output = (1..3000).joinToString("\n") { "line $it" }
            val checks = ScriptedChecks(2).also { it.output = output }

            orchestrator(FakeTracker(listOf(issue("m2-05"))), queueing(ToolRequest.Move("review")), checks).tick()

            assertEquals(2, evidence.written.size, "the failing run and its rerun (m2-08)")
            val (project, attempt, saved) = evidence.written.first()
            assertEquals(ProjectId("p1"), project)
            assertEquals(AttemptId("a1"), attempt)
            assertEquals(
                Evidence(
                    check = 0,
                    command = "./gradlew test",
                    exitCode = 2,
                    timedOut = false,
                    durationMs = 1200,
                    fingerprint = FINGERPRINT,
                    output = output,
                    outputTruncated = false,
                ),
                saved,
            )
        }

    @Test
    fun `the fingerprint is taken once per verification, before its first check`() =
        runTest {
            val sink = FakeSink()
            var taken = 0
            val checks = ScriptedChecks(0)
            val fingerprints =
                Fingerprinter { workspace ->
                    taken++
                    assertEquals(0, checks.calls.size, "taken before the checks run")
                    assertEquals(root.resolve("workspaces/m2-05"), workspace)
                    Result.success("tree:$taken")
                }
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            orchestrator(FakeTracker(listOf(issue("m2-05"))), queueing(ToolRequest.Move("review")), checks, sink, workflow, fingerprints)
                .tick()

            assertEquals(1, taken)
            assertEquals(listOf("tree:1", "tree:1"), evidence.written.map { it.third.fingerprint })
            assertEquals("tree:1", sink.events.single { it.kind == "verdict" }.data["fingerprint"])
        }

    @Test
    fun `a fingerprint that cannot be taken is unknown and the checks still run`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m2-05")))
            val fingerprints = Fingerprinter { Result.failure(java.io.IOException("git diff exited with code 128")) }

            orchestrator(tracker, queueing(ToolRequest.Move("review")), ScriptedChecks(0), sink, fingerprints = fingerprints).tick()

            assertEquals("unknown", sink.events.single { it.kind == "verdict" }.data["fingerprint"])
            assertEquals(listOf("id-m2-05" to "review"), tracker.moves)
        }

    @Test
    fun `a failed evidence write is recorded, redacted, and never stops the verification`() =
        runTest {
            val sink = FakeSink()
            val tracker = FakeTracker(listOf(issue("m2-05")))
            evidence.failure = java.io.IOException("disk full near ghp_abcdefghijklmnopqrstuvwxyz0123456789")

            orchestrator(tracker, queueing(ToolRequest.Move("review")), ScriptedChecks(0), sink).tick()

            val failed = sink.events.single { it.kind == "evidence_record_failed" }.data
            assertEquals("0", failed["check"])
            assertTrue("[redacted:github-token]" in failed["reason"].orEmpty(), failed.toString())
            assertFalse("evidence" in sink.events.single { it.kind == "check_finished" }.data)
            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("verified", verdict["verdict"])
            assertFalse("evidence" in verdict, verdict.toString())
            assertEquals(listOf("id-m2-05" to "review"), tracker.moves)
        }

    @Test
    fun `a canceled check records no verdict`() =
        runTest {
            val sink = FakeSink()
            val runner = FakeRunner()
            runner.onCall =
                { ctx -> ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review")) }
            val checks = ScriptedChecks(0).also { it.onRun = { awaitCancellation() } }
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-05"))), runner, checks, sink)

            val job = launch { orchestrator.tick() }
            runCurrent()
            job.cancelAndJoin()

            assertEquals(0, sink.events.count { it.kind == "verdict" })
            assertEquals(emptyList(), evidence.written)
        }

    /** A runner with a session that queues a move to review on each of [turns], in every attempt. */
    private fun submitting(vararg turns: Int): FakeRunner {
        val runner = FakeRunner()
        var config: Path? = null
        runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
        var now = 1_000L
        runner.sessionScript = { turn, _ ->
            if (turn in turns) ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ }).enqueue(ToolRequest.Move("review"))
            sessionTurn(turn)
        }
        return runner
    }

    /** The m1-06 continuation guidance of turn [n] of [max] for m2-06, as the spec decisions word it. */
    private fun guidance(
        n: Int,
        max: Int,
    ): String =
        "Continue working on m2-06. This is turn $n of $max. The issue is still in todo. Build on your previous turns, " +
            "do not start over. When the work is done, move the issue with loop tool move review. If you are blocked, " +
            "comment and move it to needs_info."

    /** The findings the agent reads after a refutation of the scripted check, the way the orchestrator builds them. */
    private fun findingsOf(
        checks: ScriptedChecks,
        exitCode: Int = 1,
    ): String =
        VerifyFindings
            .of("./gradlew test", CheckResult(exitCode, timedOut = false, durationMs = 1200, output = checks.output), Redactor.NONE)
            .text

    @Test
    fun `a refuted move on turn n is followed by turn n+1 whose guidance carries the findings (m2-06)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            val checks =
                ScriptedChecks(1, 1, 0).also {
                    it.output =
                        (1..30).joinToString("\n", postfix = "\nFAILED: 1 test\n") { "line $it" }
                }
            val runner = submitting(1, 2)

            val summary = orchestrator(tracker, runner, checks, workflow = workflow(maxTurns = 3)).tick().attempts.single()

            val (turn, guidance) = runner.turnInputs[1]
            assertEquals(2, turn)
            val base = guidance(2, 3)
            assertEquals(base + "\n\n" + findingsOf(checks), guidance)
            assertContains(guidance, "Check: ./gradlew test\nExit code: 1\nLast 20 lines of its output:\n")
            assertContains(guidance, "line 30\nFAILED: 1 test")
            assertFalse("line 11\n" in guidance, "only the last lines")
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
            assertEquals(listOf("id-m2-06" to "review"), tracker.moves)
        }

    @Test
    fun `m3-14 the findings the next turn reads are trimmed to tool_output_tokens, the evidence keeps the full output`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            val output = (1..30).joinToString("\n", postfix = "\nFAILED: 1 test") { "failing item $it with a line of detail" }
            val checks = ScriptedChecks(1, 1, 0).also { it.output = output }
            val runner = submitting(1, 2)

            orchestrator(tracker, runner, checks, workflow = workflow(maxTurns = 3, toolOutputTokens = 150)).tick()

            val findings = runner.turnInputs[1].second.removePrefix(guidance(2, 3) + "\n\n")
            assertTrue(TokenCounters.estimate.count(findings) <= 150, "${TokenCounters.estimate.count(findings)} tokens: $findings")
            assertContains(findings, "Check: ./gradlew test\nExit code: 1\n")
            assertTrue(findings.endsWith("failing item 30 with a line of detail\nFAILED: 1 test"), findings)
            assertFalse("failing item 20 " in findings, findings)
            assertEquals(
                output,
                evidence.written
                    .first()
                    .third.output,
                "the evidence file gets the full output",
            )
        }

    @Test
    fun `a turn after one without a refutation gets the usual guidance (m2-06)`() =
        runTest {
            val runner = submitting(1)

            orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, ScriptedChecks(1), workflow = workflow(maxTurns = 3)).tick()

            assertEquals(3, runner.turnInputs.size)
            assertContains(runner.turnInputs[1].second, "Exit code: 1")
            assertEquals(guidance(3, 3), runner.turnInputs[2].second)
        }

    @Test
    fun `an attempt whose last verdict is refuted fails as refuted and the next attempt renders loop verify_findings (m2-06)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(1)
            val template = "Work on {{ issue.identifier }}{% if loop.verify_findings %}\n\n{{ loop.verify_findings }}{% endif %}"
            val runner = submitting(1)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, checks, sink, workflow(template = template))

            val first = orchestrator.tick().attempts.single()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(AttemptOutcome.Failed, first.outcome)
            assertEquals("refuted", first.reason)
            assertEquals(1, first.turns)
            val prompts = runner.turnInputs.filter { it.first == 1 }.map { it.second }
            assertEquals(listOf("Work on m2-06", "Work on m2-06\n\n" + findingsOf(checks)), prompts)
            val slots =
                sink.events
                    .filter {
                        it.kind == "phase" && it.phase == "BuildingPrompt"
                    }.map { it.data["slot_verify_findings_chars"] }
            assertEquals(listOf("0", ("\n\n" + findingsOf(checks)).length.toString()), slots)
        }

    @Test
    fun `a template that does not use loop verify_findings renders the next attempt exactly as before (m2-06)`() =
        runTest {
            val runner = submitting(1)
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, ScriptedChecks(1))

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals(listOf(1 to "Work on m2-06", 1 to "Work on m2-06"), runner.turnInputs)
        }

    @Test
    fun `refutations count against max_turns and max_attempts, so an agent that never passes stops (m2-06)`() =
        runTest {
            val checks = ScriptedChecks(1)
            val runner = submitting(1, 2)
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, checks, workflow = workflow(maxTurns = 2, maxAttempts = 1))

            val summary = orchestrator.tick().attempts.single()
            advanceTimeBy(60_000)
            val later = orchestrator.tick()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("refuted", summary.reason)
            assertEquals(2, summary.turns)
            assertEquals(4, checks.calls.size, "each turn's check and its rerun")
            assertEquals(emptyList(), later.attempts, "parked after max_attempts")
        }

    @Test
    fun `a refutation fixed on a later turn of the same attempt is a normal exit (m2-06)`() =
        runTest {
            val runner = submitting(1, 2)

            val summary =
                orchestrator(
                    FakeTracker(listOf(issue("m2-06"))),
                    runner,
                    ScriptedChecks(1, 1, 0),
                    workflow = workflow(maxTurns = 2),
                ).tick()

            assertEquals(AttemptOutcome.Succeeded, summary.attempts.single().outcome)
        }

    @Test
    fun `a sessionless runner refuted on its one turn fails as refuted (m2-06)`() =
        runTest {
            val runner = FakeRunner()
            runner.onCall =
                { ctx -> ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review")) }

            val summary = orchestrator(FakeTracker(listOf(issue("m2-06"))), runner, ScriptedChecks(1)).tick().attempts.single()

            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("refuted", summary.reason)
        }

    private val holdoutCommand = "\$LOOP_HOLDOUT/acceptance.sh"

    private fun holdoutWorkflow(
        checks: List<CheckConfig> = listOf(CheckConfig.Command("./gradlew test")),
        maxTurns: Int = 1,
    ) = workflow(
        checks,
        maxTurns = maxTurns,
        template = "Work on {{ issue.identifier }}",
        holdout = listOf(CheckConfig.Command(holdoutCommand)),
    )

    @Test
    fun `holdouts run after every visible check passed, with LOOP_HOLDOUT, and count in the verdict (m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-14")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink, holdoutWorkflow()).tick()

            assertEquals(listOf("./gradlew test", holdoutCommand), checks.calls.map { (it.first as CheckConfig.Command).run })
            val (_, cwd, env) = checks.calls[1]
            assertEquals(root.resolve("workspaces/m2-14"), cwd)
            assertEquals(root.resolve("home/holdout").toString(), env["LOOP_HOLDOUT"])
            assertFalse("LOOP_HOLDOUT" in checks.calls[0].third, "a visible check never learns the holdout folder")
            val finished = sink.events.filter { it.kind == "check_finished" }.map { it.data }
            assertEquals(listOf("0" to null, "1" to "true"), finished.map { it["check"] to it["holdout"] })
            assertEquals("true", sink.events.filter { it.kind == "check_started" }[1].data["holdout"])
            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("verified", verdict["verdict"])
            assertEquals("2", verdict["checks"])
            assertEquals(listOf("id-m2-14" to "review"), tracker.moves)
        }

    @Test
    fun `a failing holdout refutes the result, keeps its output out of the evidence and the findings (m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-14")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0, 1, 1, 0, 0).also { it.output = "expected 42, got 41 in the hidden test\n" }
            val runner = submitting(1, 2)

            orchestrator(tracker, runner, checks, sink, holdoutWorkflow(maxTurns = 3)).tick()

            val verdicts = sink.events.filter { it.kind == "verdict" }.map { it.data["verdict"] }
            assertEquals(listOf("refuted", "verified"), verdicts)
            val rerun = sink.events.filter { it.kind == "check_finished" }[2].data
            assertEquals("true" to "true", rerun["holdout"] to rerun["rerun"], "a failed holdout is rerun once like a visible check")
            for (hidden in evidence.written.subList(1, 3).map { it.third }) {
                assertTrue(hidden.holdout)
                assertEquals("", hidden.output, "a holdout's output is never saved where the agent can read it")
            }
            assertEquals("expected 42, got 41 in the hidden test\n", evidence.written[0].third.output)
            val (turn, guidance) = runner.turnInputs[1]
            assertEquals(2, turn)
            assertTrue(guidance.endsWith("\n\n" + VerifyFindings.hidden().text), guidance)
            assertFalse("expected 42" in guidance, guidance)
            assertFalse("acceptance.sh" in guidance, guidance)
            assertEquals(listOf("id-m2-14" to "review"), tracker.moves)
        }

    @Test
    fun `a holdout's records say passed or failed only, never its exit code or duration (m2-14)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(0, 3, 5, 0, 0)

            orchestrator(FakeTracker(listOf(issue("m2-14"))), submitting(1, 2), checks, sink, holdoutWorkflow(maxTurns = 3)).tick()

            val finished = sink.events.filter { it.kind == "check_finished" }.map { it.data }
            val hidden = finished.filter { it["holdout"] == "true" }
            assertEquals(listOf("failed", "failed", "passed"), hidden.map { it["result"] })
            for (data in hidden) {
                assertEquals(emptySet(), data.keys.intersect(setOf("exit_code", "duration_ms", "timed_out", "reason")), data.toString())
            }
            assertEquals("0" to "1200", finished[0]["exit_code"] to finished[0]["duration_ms"], "a visible check keeps both")
            for (saved in evidence.written.map { it.third }.filter { it.holdout }) {
                assertEquals(null, saved.exitCode)
                assertEquals(0L, saved.durationMs)
                assertFalse(saved.timedOut)
            }
            assertEquals(
                listOf("failed", "failed", "passed"),
                evidence.written
                    .map { it.third }
                    .filter { it.holdout }
                    .map { it.result },
            )
        }

    @Test
    fun `a holdout that could not run is unverified with a reason that tells nothing of it (m2-14)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(0, 127)

            orchestrator(FakeTracker(listOf(issue("m2-14"))), queueing(ToolRequest.Move("review")), checks, sink, holdoutWorkflow()).tick()

            val hidden =
                sink.events
                    .filter { it.kind == "check_finished" }
                    .map { it.data }
                    .single { it["holdout"] == "true" }
            assertEquals("could_not_run", hidden["result"])
            assertEquals(null, hidden["reason"])
            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("unverified", verdict["verdict"])
            assertEquals("hidden check 1 could not run", verdict["reason"])
        }

    @Test
    fun `a failing visible check stops before the holdouts (m2-14)`() =
        runTest {
            val checks = ScriptedChecks(1)

            orchestrator(FakeTracker(listOf(issue("m2-14"))), queueing(ToolRequest.Move("review")), checks, workflow = holdoutWorkflow())
                .tick()

            assertEquals(listOf("./gradlew test", "./gradlew test"), checks.calls.map { (it.first as CheckConfig.Command).run })
        }

    @Test
    fun `holdouts alone are enough to verify a result (m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-14")))
            val checks = ScriptedChecks(0)

            orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, workflow = holdoutWorkflow(checks = emptyList())).tick()

            assertEquals(1, checks.calls.size)
            assertEquals(listOf("id-m2-14" to "review"), tracker.moves)
        }

    @Test
    fun `a check that fails then passes on its rerun gives verified and keeps both runs as evidence (m2-08)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-08")))
            val sink = FakeSink()
            val checks = ScriptedChecks(1, 0)
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            val summary = orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink, workflow).tick().attempts.single()

            assertEquals(listOf("./a.sh", "./a.sh", "./b.sh"), checks.calls.map { (it.first as CheckConfig.Command).run })
            assertEquals(
                listOf("result_submitted", "check_started", "check_finished", "check_started", "check_finished") +
                    listOf("check_started", "check_finished", "verdict", "issue_moved"),
                sink.kinds(),
            )
            val finished = sink.events.filter { it.kind == "check_finished" }.map { it.data }
            assertEquals(listOf("1", "0", "0"), finished.map { it["exit_code"] })
            assertEquals(listOf(null, "true", null), finished.map { it["rerun"] })
            assertEquals("true", sink.events.filter { it.kind == "check_started" }[1].data["rerun"])
            assertEquals(
                mapOf(
                    "verdict" to "verified",
                    "fingerprint" to FINGERPRINT,
                    "checks" to "2",
                    "evidence" to "a1.evidence-1.log,a1.evidence-2.log,a1.evidence-3.log",
                ),
                sink.events.single { it.kind == "verdict" }.data,
            )
            assertEquals(listOf(1, 0, 0), evidence.written.map { it.third.exitCode })
            assertEquals(listOf(0, 0, 1), evidence.written.map { it.third.check })
            assertEquals(listOf("id-m2-08" to "review"), tracker.moves)
            assertEquals(AttemptOutcome.Succeeded, summary.outcome)
        }

    @Test
    fun `a check that fails twice gives refuted with the findings of the rerun (m2-08)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-08")))
            val sink = FakeSink()
            val checks = ScriptedChecks(1, 2)

            val summary = orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink).tick().attempts.single()

            assertEquals(2, checks.calls.size)
            val verdict = sink.events.single { it.kind == "verdict" }.data
            assertEquals("refuted", verdict["verdict"])
            assertEquals("a1.evidence-1.log,a1.evidence-2.log", verdict["evidence"])
            assertEquals(emptyList(), tracker.moves)
            assertEquals(AttemptOutcome.Failed, summary.outcome)
            assertEquals("refuted", summary.reason)
            assertEquals(emptyMap(), parked.records, "a refutation is retried, not parked")
        }

    /** Runs one attempt that submits a move to review with [checks] and [workflow]; its summary. */
    private suspend fun TestScope.unverified(
        checks: CheckRunner,
        sink: FakeSink,
        workflow: WorkflowFile = workflow(),
        tracker: FakeTracker = FakeTracker(listOf(issue("m2-08"))),
    ): AttemptSummary {
        val summary = orchestrator(tracker, queueing(ToolRequest.Move("review")), checks, sink, workflow).tick().attempts.single()
        assertEquals(emptyList(), tracker.moves, "an unverified result is never delivered")
        return summary
    }

    private fun assertParkedUnverified(
        summary: AttemptSummary,
        sink: FakeSink,
        error: String,
    ) {
        assertEquals(AttemptOutcome.Failed, summary.outcome)
        assertEquals("unverified", summary.reason)
        assertEquals("unverified", sink.events.single { it.kind == "verdict" }.data["verdict"])
        assertEquals(error, sink.events.single { it.kind == "verdict" }.data["reason"])
        assertEquals(mapOf("reason" to "unverified", "error" to error), sink.events.single { it.kind == "issue_parked" }.data)
        assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
    }

    @Test
    fun `a kind with no check gives unverified and parks the issue (m2-08)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            val summary = unverified(checks, sink, workflow(emptyList()))

            assertEquals(0, checks.calls.size)
            assertEquals("0", sink.events.single { it.kind == "verdict" }.data["checks"])
            assertParkedUnverified(summary, sink, "the kind has no check")
        }

    @Test
    fun `a command that is not found gives unverified without a rerun and parks the issue (m2-08)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(127)
            val workflow = workflow(listOf(CheckConfig.Command("./a.sh"), CheckConfig.Command("./b.sh")))

            val summary = unverified(checks, sink, workflow)

            assertEquals(1, checks.calls.size, "a check that could not run is not rerun")
            assertParkedUnverified(summary, sink, "check 0 could not run: command not found, exit code 127")
        }

    @Test
    fun `a check over its timeout gives unverified without a rerun and parks the issue (m2-08)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(null).also { it.timedOut = true }

            val summary = unverified(checks, sink)

            assertEquals(1, checks.calls.size)
            assertParkedUnverified(summary, sink, "check 0 could not run: timed out after 1.2 s")
        }

    @Test
    fun `a check runner that crashes gives unverified and parks the issue (m2-08)`() =
        runTest {
            val sink = FakeSink()
            val checks = ScriptedChecks(0).also { it.crash = IllegalStateException("boom") }

            val summary = unverified(checks, sink)

            val finished = sink.events.single { it.kind == "check_finished" }.data
            assertEquals("check runner error: boom", finished["reason"])
            assertParkedUnverified(summary, sink, "check 0 could not run: check runner error: boom")
        }

    @Test
    fun `an unverified attempt with turns left stops after the drain and runs no further turn (m2-08)`() =
        runTest {
            val sink = FakeSink()
            val runner = submitting(1, 2)
            val checks = ScriptedChecks(127)

            val summary =
                orchestrator(FakeTracker(listOf(issue("m2-08"))), runner, checks, sink, workflow(maxTurns = 3)).tick().attempts.single()

            assertEquals(1, runner.turnInputs.size)
            assertEquals(1, summary.turns)
            assertEquals("unverified", summary.reason)
        }

    @Test
    fun `a parked unverified issue is not dispatched again (m2-08)`() =
        runTest {
            val runner = submitting(1)
            val orchestrator =
                orchestrator(FakeTracker(listOf(issue("m2-08"))), runner, ScriptedChecks(127), workflow = workflow(emptyList()))

            orchestrator.tick()
            advanceTimeBy(60_000)
            val later = orchestrator.tick()

            assertEquals(emptyList(), later.attempts)
            assertEquals(1, runner.turnInputs.size)
        }

    /** A runner whose turn 1 queues [requests] the way `loop tool` does and, when [result] is set, writes it to `.loop/result.json`. */
    private fun queueingWithResult(
        result: String?,
        vararg requests: ToolRequest,
    ): FakeRunner {
        val runner = FakeRunner()
        var config: Path? = null
        var workspace: Path? = null
        runner.onCall = { ctx ->
            config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) }
            workspace = ctx.workspace
        }
        var now = 1_000L
        runner.sessionScript = { turn, _ ->
            if (turn == 1) {
                val queue = ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ })
                requests.forEach { queue.enqueue(it) }
                result?.let {
                    val file = checkNotNull(workspace).resolve(".loop/result.json")
                    file.parent.createDirectories()
                    file.writeText(it)
                }
            }
            sessionTurn(turn)
        }
        return runner
    }

    private val doneResult = """{ "status": "done", "summary": "Done.", "acceptance": [] }"""

    private val failedComment =
        "Loop could not apply your last request (comment): comments are closed. Run it again if it is still needed."

    @Test
    fun `a failed request follows the findings of a refutation in the same drain (m2-06, m2-07)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            tracker.commentFailure = TrackerError.Unavailable(IOException("comments are closed"))
            val checks = ScriptedChecks(1)
            val runner = queueing(ToolRequest.Comment("on it"), ToolRequest.Move("review"))

            orchestrator(tracker, runner, checks, workflow = workflow(maxTurns = 2)).tick()

            assertEquals(guidance(2, 2) + "\n\n" + findingsOf(checks) + "\n\n" + failedComment, runner.turnInputs[1].second)
        }

    @Test
    fun `a failed request follows the hidden finding of a refuted holdout (m2-14, m2-07)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-14")))
            tracker.commentFailure = TrackerError.Unavailable(IOException("comments are closed"))
            val checks = ScriptedChecks(0, 1, 1)
            val runner = queueing(ToolRequest.Comment("on it"), ToolRequest.Move("review"))

            orchestrator(tracker, runner, checks, workflow = holdoutWorkflow(maxTurns = 2)).tick()

            val guidance = runner.turnInputs[1].second
            assertTrue(guidance.endsWith("\n\n" + VerifyFindings.hidden().text + "\n\n" + failedComment), guidance)
        }

    @Test
    fun `a rotation right after a refuted verdict hands the new session its findings and the failed request notices (m3-11)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m3-11")))
            tracker.commentFailure = TrackerError.Unavailable(IOException("comments are closed"))
            val checks = ScriptedChecks(1)
            val runner = queueing(ToolRequest.Comment("on it"), ToolRequest.Move("review"))
            runner.occupancy = true
            // The first session's turn crosses the soft limit (0.24 of the window), the new session never does.
            runner.sessionScript = rotatingAfterFirstTurn(runner.sessionScript!!) { runner.sessionsOpened }
            val template =
                "Work on {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                    "{% if loop.verify_findings %}\nFindings: {{ loop.verify_findings }}{% endif %}" +
                    "{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
            val sink = FakeSink()

            orchestrator(tracker, runner, checks, sink, workflow(maxTurns = 2, template = template)).tick()

            assertEquals(1, sink.events.count { it.kind == "session_rotated" })
            assertEquals(2, runner.sessionsOpened)
            val (turn, rotated) = runner.turnInputs[1]
            assertEquals(1, turn, "the new session starts at its own turn 1")
            val findings = findingsOf(checks)
            assertTrue(rotated.startsWith("Work on m3-11 kind=rotation\nFindings: $findings\n### Goal"), rotated)
            assertContains(rotated, "Findings of the last refutation:\n$findings")
            assertTrue(rotated.endsWith("\n\n" + failedComment), rotated)
        }

    @Test
    fun `a verification with fewer failing checks between two rotations is progress, so the issue does not park (m3-12)`() =
        runTest {
            // Session 1: check 1 fails, so 2 checks do not pass. Session 2: check 1 passes and check 2 fails, 1 does not
            // pass, fewer than before. Session 3: the same, no better. Each failing check runs twice, its rerun.
            val sink = splittingRun(ScriptedChecks(1, 1, 0, 1, 1, 0, 1, 1))

            assertEquals(3, sink.events.count { it.kind == "session_rotated" })
            assertTrue(sink.events.none { it.kind == "issue_parked" && it.data["reason"] == "split_issue" }, sink.events.toString())
        }

    @Test
    fun `verifications that never fail fewer checks do not count as progress, so the issue parks with split_issue (m3-12)`() =
        runTest {
            val sink = splittingRun(ScriptedChecks(1))

            assertEquals(1, sink.events.count { it.kind == "session_rotated" })
            val park = sink.events.single { it.kind == "issue_parked" }
            assertEquals("split_issue", park.data["reason"])
        }

    /**
     * Runs one attempt of `m3-12` in a git workspace whose commit never moves, with two checks run by [checks] and at
     * most 4 turns: each of the first 3 sessions submits a move to review in its only turn and crosses the soft limit.
     */
    private suspend fun TestScope.splittingRun(checks: ScriptedChecks): FakeSink {
        val workspace = root.resolve("workspaces").resolve("m3-12").createDirectories()
        listOf(listOf("init", "-q"), listOf("commit", "-q", "--allow-empty", "-m", "first")).forEach { args ->
            val process =
                ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        }
        val tracker = FakeTracker(listOf(issue("m3-12")))
        val runner = FakeRunner()
        runner.occupancy = true
        var config: Path? = null
        runner.onCall = { ctx -> config = ctx.env["LOOP_TOOL_CONFIG"]?.let { Path.of(it) } }
        var now = 1_000L
        runner.sessionScript = { turn, _ ->
            val session = runner.sessionsOpened
            if (session < 4) {
                ToolRequestQueue(checkNotNull(config).parent, nowMillis = { now++ }).enqueue(ToolRequest.Move("review"))
            }
            sessionTurn(turn)
        }
        runner.sessionScript = rotatingAfterFirstTurn(runner.sessionScript!!, every = true) { runner.sessionsOpened < 4 }
        val sink = FakeSink()
        val two = listOf(CheckConfig.Command("./check-1"), CheckConfig.Command("./check-2"))
        orchestrator(tracker, runner, checks, sink, workflow(checks = two, maxTurns = 4)).tick()
        return sink
    }

    /** [rotatingAfterFirstTurn] for every first turn of a session while [crossing] holds. */
    private fun rotatingAfterFirstTurn(
        script: (Int, String) -> Flow<RunnerEvent>,
        every: Boolean,
        crossing: () -> Boolean,
    ): (Int, String) -> Flow<RunnerEvent> = rotatingAfterFirstTurn(script) { if (every && crossing()) 1 else 2 }

    /**
     * Wraps [script] so that the first turn of the first session, numbered by [session], also reports an occupancy of
     * 240 000 tokens of the 1 000 000 token window of its model, past the soft limit and below the hard one.
     */
    private fun rotatingAfterFirstTurn(
        script: (Int, String) -> Flow<RunnerEvent>,
        session: () -> Int,
    ): (Int, String) -> Flow<RunnerEvent> =
        { turn, input ->
            val crossing = session() == 1 && turn == 1
            flow {
                script(turn, input).collect { event ->
                    if (crossing && event is RunnerEvent.TurnCompleted) {
                        emit(RunnerEvent.ContextUsage(event.sessionId, event.turnId, 240_000L, "anthropic/claude-opus-5-5"))
                    }
                    emit(event)
                }
            }
        }

    @Test
    fun `a failed request reaches the guidance when the turn also wrote a refuted result file (m2-15, m2-07)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            tracker.commentFailure = TrackerError.Unavailable(IOException("comments are closed"))
            val checks = ScriptedChecks(1)
            val runner = queueingWithResult(doneResult, ToolRequest.Comment("on it"))

            orchestrator(tracker, runner, checks, workflow = workflow(maxTurns = 2)).tick()

            assertEquals(guidance(2, 2) + "\n\n" + findingsOf(checks) + "\n\n" + failedComment, runner.turnInputs[1].second)
        }

    @Test
    fun `a done result file whose move the tracker refuses is named as a move in the next turn's guidance (m2-15, m2-07)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            tracker.moveFailure = TrackerError.Unavailable(IOException("the tracker is read only"))
            val sink = FakeSink()
            val runner = queueingWithResult(doneResult)

            orchestrator(tracker, runner, ScriptedChecks(0), sink, workflow(maxTurns = 2)).tick()

            assertEquals("result", sink.events.single { it.kind == "tool_request_failed" }.data["request"])
            assertEquals(
                guidance(2, 2) + "\n\n" +
                    "Loop could not apply your last request (move to review): the tracker is read only. Run it again if it is still needed.",
                runner.turnInputs[1].second,
            )
        }

    @Test
    fun `a queued move and a done result file refused in the same drain give one line (m2-15, m2-07)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            tracker.moveFailure = TrackerError.Unavailable(IOException("the tracker is read only"))
            val runner = queueingWithResult(doneResult, ToolRequest.Move("review"))

            orchestrator(tracker, runner, ScriptedChecks(0), workflow = workflow(maxTurns = 2)).tick()

            assertEquals(
                guidance(2, 2) + "\n\n" +
                    "Loop could not apply your last request (move to review): the tracker is read only. Run it again if it is still needed.",
                runner.turnInputs[1].second,
            )
        }

    @Test
    fun `a queued submit_result runs the verdict path of a done result file, with source mcp (m2-16)`() =
        runTest {
            val fileTracker = FakeTracker(listOf(issue("m2-15")))
            val fileSink = FakeSink()
            orchestrator(fileTracker, queueingWithResult(doneResult), ScriptedChecks(0), fileSink).tick()
            val mcpTracker = FakeTracker(listOf(issue("m2-16")))
            val mcpSink = FakeSink()

            orchestrator(mcpTracker, queueing(ToolRequest.Result(doneResult)), ScriptedChecks(0), mcpSink).tick()

            assertEquals(listOf("result_submitted", "check_started", "check_finished", "verdict", "issue_moved"), mcpSink.kinds())
            assertEquals(fileSink.kinds(), mcpSink.kinds())
            assertEquals(listOf("id-m2-16" to "review"), mcpTracker.moves)
            val fileData = fileSink.events.single { it.kind == "result_submitted" }.data
            val mcpData = mcpSink.events.single { it.kind == "result_submitted" }.data
            assertEquals(fileData + ("source" to "mcp"), mcpData)
            assertEquals(mapOf("to" to "review", "status" to "done", "source" to "mcp", "acceptance" to "0"), mcpData)
        }

    @Test
    fun `a queued submit_result is saved beside the evidence like a result file (m2-16)`() =
        runTest {
            val saved = mutableListOf<String>()
            val keeping =
                object : EvidenceWriter by evidence {
                    override fun writeResult(
                        projectId: ProjectId,
                        attemptId: AttemptId,
                        text: String,
                    ): String {
                        saved += text
                        return "${attemptId.value}.result-${saved.size}.json"
                    }
                }
            val sink = FakeSink()
            val clock = SchedulerClock(testScheduler)
            val workflow = workflow()
            Orchestrator(
                workflow = workflow,
                tracker = FakeTracker(listOf(issue("m2-16"))),
                runner = queueing(ToolRequest.Result(doneResult)),
                sink = sink,
                log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
                clock = clock,
                workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), HooksConfig(), FakeHookRunner()),
                newId = { "a1" },
                runId = RunId("r1"),
                projectId = ProjectId("p1"),
                launcher = root.resolve("install/bin/loop"),
                loopHome = root.resolve("home"),
                hostPath = "/usr/bin",
                parked = parked,
                redactor = SecretRedactor(),
                secretsOf = { emptyMap() },
                checkRunnerFor = { ScriptedChecks(0) },
                evidence = keeping,
                fingerprinter = { Result.success(FINGERPRINT) },
            ).tick()

            assertEquals(listOf(doneResult), saved)
            assertEquals("a1.result-1.json", sink.events.single { it.kind == "result_submitted" }.data["result"])
        }

    @Test
    fun `a queued needs_info submit_result comments its question and moves to needs_info, unverified (m2-16)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-16")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val result = """{ "status": "needs_info", "summary": "Which port should it use?" }"""

            orchestrator(tracker, queueing(ToolRequest.Result(result)), checks, sink).tick()

            assertEquals(listOf("id-m2-16" to "needs_info"), tracker.moves)
            assertEquals(
                "Which port should it use?",
                tracker.comments
                    .single()
                    .second.text,
            )
            assertEquals(0, checks.calls.size)
            assertEquals("mcp", sink.events.single { it.kind == "result_submitted" }.data["source"])
        }

    @Test
    fun `a queued blocked submit_result fails the attempt with its summary, like the result file (m2-16)`() =
        runTest {
            val sink = FakeSink()
            val result = """{ "status": "blocked", "summary": "The emulator is gone." }"""

            orchestrator(FakeTracker(listOf(issue("m2-16"))), queueing(ToolRequest.Result(result)), ScriptedChecks(0), sink).tick()

            val submitted = sink.events.single { it.kind == "result_submitted" }.data
            assertEquals("blocked", submitted["status"])
            assertEquals("mcp", submitted["source"])
            assertEquals("The emulator is gone.", submitted["reason"])
            val finished = sink.events.single { it.kind == "attempt_finished" }.data
            assertEquals("result_blocked", finished["reason"], "$finished")
            assertEquals("The emulator is gone.", finished["error"], "$finished")
        }

    @Test
    fun `an invalid queued submit_result records result_invalid with source mcp and fails the attempt (m2-16)`() =
        runTest {
            val sink = FakeSink()

            orchestrator(
                FakeTracker(listOf(issue("m2-16"))),
                queueing(ToolRequest.Result("""{ "status": "maybe" }""")),
                ScriptedChecks(0),
                sink,
            ).tick()

            val invalid = sink.events.single { it.kind == "result_invalid" }.data
            assertEquals("mcp", invalid["source"])
            assertContains(invalid["reason"].orEmpty(), "status must be one of")
            assertEquals("invalid_result", sink.events.single { it.kind == "attempt_finished" }.data["reason"])
        }

    @Test
    fun `without kinds a queued submit_result is a failed request, never applied, named in the next guidance (m2-16)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-06")))
            val sink = FakeSink()
            val runner = queueing(ToolRequest.Result(doneResult))

            orchestrator(tracker, runner, ScriptedChecks(0), sink, workflow(checks = null, maxTurns = 2)).tick()

            assertEquals(emptyList(), tracker.moves)
            val failed = sink.events.single { it.kind == "tool_request_failed" }.data
            assertEquals("result", failed["request"])
            assertEquals(
                guidance(2, 2) + "\n\n" +
                    "Loop could not apply your last request (submit_result): submit_result needs Loop mode, " +
                    "a kinds: key in WORKFLOW.md. Run it again if it is still needed.",
                runner.turnInputs[1].second,
            )
        }

    private companion object {
        const val FINGERPRINT = "git:0123456789abcdef0123456789abcdef01234567:1111111111111111:2222222222222222"
    }
}
