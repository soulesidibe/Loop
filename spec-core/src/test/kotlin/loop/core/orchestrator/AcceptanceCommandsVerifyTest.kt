package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.log.KeyValueLog
import loop.core.verify.AcceptanceCriteria
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.Evidence
import loop.core.verify.EvidenceWriter
import loop.core.verify.Fingerprinter
import loop.core.verify.JudgmentCalls
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * m2-09: a kind's `acceptance_commands` check runs the commands of the issue's acceptance criteria as
 * `command` checks, in its place in the check list, with its timeout and the rerun rule of m2-08. The
 * criteria without a command are judgment calls, saved as evidence of their own and never passed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptanceCommandsVerifyTest {
    private val root: Path = createTempDirectory("loop-acceptance").toRealPath()

    private val description =
        """
        |## What
        |
        |- [ ] `./outside.sh` passes, never run: it is not a criterion.
        |
        |## Acceptance criteria
        |
        |- [ ] `make lint` passes.
        |- [ ] The docs state the convention.
        |- [ ] `npm test` passes on a clean checkout.
        |- [ ] A human reads the copy.
        |
        |## Comments
        """.trimMargin()

    private fun workflow(
        checks: List<CheckConfig>,
        trackerKind: String = "files",
        holdout: List<CheckConfig> = emptyList(),
    ): WorkflowFile =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds = KindsConfig(KindConfig(checks, holdout = holdout, delivery = DeliveryConfig(DeliveryPolicy.Auto))),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = trackerKind,
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo", "in_progress"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1000,
            promptTemplate = "Work on {{ issue.identifier }}",
        )

    /** A scripted check runner: exit codes in order (the last one repeats), every call recorded. */
    private class ScriptedChecks(
        vararg val exitCodes: Int?,
    ) : CheckRunner {
        val calls = mutableListOf<Pair<CheckConfig, Path>>()
        val envs = mutableListOf<Map<String, String>>()

        override suspend fun run(
            check: CheckConfig,
            cwd: Path,
            env: Map<String, String>,
        ): CheckResult {
            calls += check to cwd
            envs += env
            val code = exitCodes.getOrElse(calls.size - 1) { exitCodes.last() }
            return CheckResult(code, timedOut = false, durationMs = 1200, output = "output of check ${calls.size}\n")
        }
    }

    /** Keeps the evidence of check runs and of judgment calls, numbered in one sequence like the files. */
    private class RecordedEvidence : EvidenceWriter {
        val checks = mutableListOf<Evidence>()
        val judgmentCalls = mutableListOf<JudgmentCalls>()
        private var next = 0

        override fun write(
            projectId: ProjectId,
            attemptId: AttemptId,
            evidence: Evidence,
        ): String {
            checks += evidence
            return "${attemptId.value}.evidence-${++next}.log"
        }

        override fun writeJudgmentCalls(
            projectId: ProjectId,
            attemptId: AttemptId,
            calls: JudgmentCalls,
        ): String {
            judgmentCalls += calls
            return "${attemptId.value}.evidence-${++next}.log"
        }
    }

    private val evidence = RecordedEvidence()
    private val parked = FakeParkedIssues()
    private val frozen = FakeFrozenCommands()

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        checks: CheckRunner,
        sink: FakeSink,
        workflow: WorkflowFile,
        runner: FakeRunner = queueing(ToolRequest.Move("review")),
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
            fingerprinter = Fingerprinter { Result.success(FINGERPRINT) },
            frozenCommands = frozen,
        )
    }

    /** A runner with a session whose turn 1 queues [requests] the way `loop tool` does. */
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

    /** A runner whose turn 1 writes a `done` result to `.loop/result.json` in the workspace and queues nothing (m2-15). */
    private fun writingDoneResult(): FakeRunner {
        val runner = FakeRunner()
        var workspace: Path? = null
        runner.onCall = { ctx -> workspace = ctx.workspace }
        runner.sessionScript = { turn, _ ->
            if (turn == 1) {
                val file = checkNotNull(workspace).resolve(".loop/result.json")
                file.parent.createDirectories()
                file.writeText("""{ "status": "done", "summary": "Done.", "acceptance": [] }""")
            }
            sessionTurn(turn)
        }
        return runner
    }

    private fun FakeSink.data(kind: String): List<Map<String, String>> = events.filter { it.kind == kind }.map { it.data }

    @Test
    fun `each command of the criteria runs in the workspace as a command check with the declared timeout, in place`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val workflow =
                workflow(
                    listOf(CheckConfig.Command("./gradlew test"), CheckConfig.AcceptanceCommands(600_000), CheckConfig.Command("./z.sh")),
                )

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(
                listOf(
                    CheckConfig.Command("./gradlew test"),
                    CheckConfig.Command("make lint", 600_000),
                    CheckConfig.Command("npm test", 600_000),
                    CheckConfig.Command("./z.sh"),
                ),
                checks.calls.map { it.first },
            )
            assertEquals(setOf(root.resolve("workspaces/m2-09")), checks.calls.map { it.second }.toSet())
            val finished = sink.data("check_finished")
            assertEquals(listOf("0", "1", "2", "3"), finished.map { it["check"] })
            assertEquals(listOf("./gradlew test", "make lint", "npm test", "./z.sh"), finished.map { it["command"] })
            assertEquals(listOf("verified"), sink.data("verdict").map { it["verdict"] })
            assertEquals("4", sink.data("verdict").single()["checks"])
            assertEquals(listOf("id-m2-09" to "review"), tracker.moves)
        }

    @Test
    fun `a failing command runs once more, and a second failure refutes with its findings (m2-08 rule)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0, 1, 1)

            val summary =
                orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()))).tick().attempts.single()

            assertEquals(listOf("make lint", "npm test", "npm test"), checks.calls.map { (it.first as CheckConfig.Command).run })
            assertEquals(listOf(null, null, "true"), sink.data("check_finished").map { it["rerun"] })
            assertEquals("refuted", sink.data("verdict").single()["verdict"])
            assertEquals("refuted", summary.reason)
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a failing command that passes on its rerun counts as passed (m2-08 rule)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(1, 0)

            orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()))).tick()

            assertEquals(listOf("make lint", "make lint", "npm test"), checks.calls.map { (it.first as CheckConfig.Command).run })
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `a command that is not found is not rerun and parks the issue unverified (m2-08 rule)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(127)

            orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()))).tick()

            assertEquals(1, checks.calls.size)
            assertEquals("check 0 could not run: command not found, exit code 127", sink.data("verdict").single()["reason"])
            assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
        }

    @Test
    fun `judgment calls are saved as evidence of their own and named by the verdict, never as passed checks`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()

            orchestrator(tracker, ScriptedChecks(0), sink, workflow(listOf(CheckConfig.AcceptanceCommands()))).tick()

            assertEquals(
                listOf(JudgmentCalls(FINGERPRINT, listOf("The docs state the convention.", "A human reads the copy."))),
                evidence.judgmentCalls,
            )
            assertEquals(listOf(mapOf("count" to "2", "evidence" to "a1.evidence-1.log")), sink.data("judgment_calls"))
            val kinds = sink.events.map { it.kind }
            assertEquals(kinds.indexOf("result_submitted") + 1, kinds.indexOf("judgment_calls"), "before the first check")
            assertEquals(2, sink.data("check_finished").size, "one check per command, none for a judgment call")
            val verdict = sink.data("verdict").single()
            assertEquals("2", verdict["judgment_calls"])
            assertEquals("a1.evidence-2.log,a1.evidence-3.log", verdict["evidence"], "the evidence of the checks that ran")
        }

    @Test
    fun `a kind without acceptance_commands records no judgment calls`() =
        runTest {
            val sink = FakeSink()

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                ScriptedChecks(0),
                sink,
                workflow(listOf(CheckConfig.Command("./gradlew test"))),
            ).tick()

            assertEquals(emptyList(), sink.data("judgment_calls"))
            assertEquals(emptyList(), evidence.judgmentCalls)
            assertNull(sink.data("verdict").single()["judgment_calls"])
        }

    @Test
    fun `criteria with no command and no other check give unverified and park the issue`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = "## Acceptance criteria\n\n- [ ] A human reads it.")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            val summary = orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()))).tick().attempts.single()

            assertEquals(0, checks.calls.size)
            val verdict = sink.data("verdict").single()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("the acceptance criteria of the issue hold no command", verdict["reason"])
            assertEquals("1", verdict["judgment_calls"])
            assertEquals("unverified", summary.reason)
            assertContains(parked.records.values.map { it.reason.wire }, "unverified")
            assertEquals(emptyList(), tracker.moves)
            assertEquals(listOf(mapOf("judgment_calls" to "1")), sink.data("acceptance_commands_empty"))
        }

    @Test
    fun `the commands expand in place next to the core checks of m2-12`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val fileExists = CheckConfig.FileExists("docs/usage.md")
            val fileMatches = CheckConfig.FileMatches("docs/usage.md", "^## Usage$")
            val diffPolicy = CheckConfig.DiffPolicy(listOf("WORKFLOW.md"))
            val workflow = workflow(listOf(fileExists, CheckConfig.AcceptanceCommands(600_000), fileMatches, diffPolicy))

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(
                listOf(
                    fileExists,
                    CheckConfig.Command("make lint", 600_000),
                    CheckConfig.Command("npm test", 600_000),
                    fileMatches,
                    diffPolicy,
                ),
                checks.calls.map { it.first },
            )
            assertEquals(
                listOf(
                    "file_exists docs/usage.md",
                    "make lint",
                    "npm test",
                    "file_matches docs/usage.md /^## Usage$/",
                    "diff_policy base=main protect=WORKFLOW.md",
                ),
                sink.data("check_finished").map { it["command"] },
            )
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `criteria with no command and only the guard's diff_policy left give unverified, a guard alone proves no work`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = "## Acceptance criteria\n\n- [ ] A human reads it.")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val workflow = workflow(listOf(CheckConfig.AcceptanceCommands(), CheckConfig.DiffPolicy(listOf("WORKFLOW.md"))))

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(0, checks.calls.size)
            val verdict = sink.data("verdict").single()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("the acceptance criteria of the issue hold no command", verdict["reason"])
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a done result file runs the acceptance commands like the intercepted move (m2-15)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands())), writingDoneResult()).tick()

            assertEquals("file", sink.data("result_submitted").single()["source"])
            assertEquals(listOf("make lint", "npm test"), checks.calls.map { (it.first as CheckConfig.Command).run })
            assertEquals(listOf(mapOf("count" to "2", "evidence" to "a1.evidence-1.log")), sink.data("judgment_calls"))
            val verdict = sink.data("verdict").single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("2", verdict["judgment_calls"])
            assertEquals(listOf("id-m2-09" to "review"), tracker.moves)
        }

    @Test
    fun `criteria with no command next to a file check record acceptance_commands_empty and verify on the file check alone`() =
        runTest {
            // A typo such as `pass` for `passes` leaves no command: the event makes it visible.
            val criteria = "## Acceptance criteria\n\n- [ ] `./gradlew build` pass.\n- [ ] A human reads it."
            val tracker = FakeTracker(listOf(issue("m2-09", description = criteria)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val fileExists = CheckConfig.FileExists("docs/usage.md")

            orchestrator(tracker, checks, sink, workflow(listOf(fileExists, CheckConfig.AcceptanceCommands()))).tick()

            assertEquals(listOf<CheckConfig>(fileExists), checks.calls.map { it.first })
            assertEquals(listOf(mapOf("judgment_calls" to "2")), sink.data("acceptance_commands_empty"))
            val kinds = sink.events.map { it.kind }
            assertEquals(true, kinds.indexOf("acceptance_commands_empty") < kinds.indexOf("check_started"), "before the first check")
            val verdict = sink.data("verdict").single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("1", verdict["checks"])
            assertEquals("2", verdict["judgment_calls"])
            assertEquals(listOf("id-m2-09" to "review"), tracker.moves)
        }

    @Test
    fun `criteria with a command record no acceptance_commands_empty`() =
        runTest {
            val sink = FakeSink()

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                ScriptedChecks(0),
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            assertEquals(emptyList(), sink.data("acceptance_commands_empty"))
        }

    @Test
    fun `the first dispatch freezes the command list of the issue before the agent runs`() =
        runTest {
            val sink = FakeSink()

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                ScriptedChecks(0),
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            val record = frozen.records.values.single()
            assertEquals("id-m2-09", record.issueId)
            assertEquals("m2-09", record.identifier)
            assertEquals(AcceptanceCriteria.digest(listOf("make lint", "npm test")), record.digest)
            assertEquals(2, record.count)
            assertEquals(listOf(mapOf("count" to "2")), sink.data("acceptance_commands_frozen"))
            val kinds = sink.events.map { it.kind }
            assertEquals(true, kinds.indexOf("acceptance_commands_frozen") < kinds.indexOf("turn_started"), "before the agent runs")
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `a later attempt whose criteria lost the refuting command parks unverified and runs nothing`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            // make lint passes, npm test fails twice: attempt 1 is refuted.
            val checks = ScriptedChecks(0, 1, 1)
            val orchestrator = orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands())))
            orchestrator.tick()
            assertEquals("refuted", sink.data("verdict").single()["verdict"])

            // Between the attempts the issue loses the command that refuted it, as an agent could rewrite it.
            tracker.issues[0] =
                tracker.issues[0].copy(description = description.replace("- [ ] `npm test` passes on a clean checkout.\n", ""))
            testScheduler.advanceTimeBy(RETRY_WAIT_MS)
            val summary = orchestrator.tick().attempts.single()

            assertEquals(3, checks.calls.size, "the second attempt runs no command")
            val verdict = sink.data("verdict").last()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("acceptance commands changed", verdict["reason"])
            assertEquals("0", verdict["checks"])
            assertEquals(listOf(mapOf("frozen" to "2", "count" to "1")), sink.data("acceptance_commands_changed"))
            assertEquals("unverified", summary.reason)
            assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
            assertEquals("acceptance commands changed", sink.data("issue_parked").single()["error"])
            val record = frozen.records.values.single()
            assertEquals(AcceptanceCriteria.digest(listOf("make lint", "npm test")), record.digest, "the first list stays frozen")
            assertEquals(AcceptanceCriteria.digest(listOf("make lint")), record.changedDigest)
            assertEquals(1, record.changedCount)
            assertEquals(1, sink.data("acceptance_commands_frozen").size, "frozen once, at the first dispatch")
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `criteria that match the frozen list run, nothing is frozen again`() =
        runTest {
            frozen.records["p1" to "id-m2-09"] =
                FrozenCommands(
                    ProjectId("p1"),
                    "id-m2-09",
                    "m2-09",
                    AcceptanceCriteria.digest(listOf("make lint", "npm test")),
                    2,
                    FIXED_NOW,
                )
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                checks,
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            assertEquals(2, checks.calls.size)
            assertEquals(emptyList(), sink.data("acceptance_commands_frozen"))
            assertEquals(emptyList(), sink.data("acceptance_commands_changed"))
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `criteria that match the frozen list again forget the changed list an earlier park left`() =
        runTest {
            // An agent dropped npm test, the issue parked, then a human restored the issue and unparked it.
            frozen.records["p1" to "id-m2-09"] =
                FrozenCommands(
                    ProjectId("p1"),
                    "id-m2-09",
                    "m2-09",
                    AcceptanceCriteria.digest(listOf("make lint", "npm test")),
                    2,
                    FIXED_NOW,
                    changedDigest = AcceptanceCriteria.digest(listOf("make lint")),
                    changedCount = 1,
                )
            val sink = FakeSink()

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                ScriptedChecks(0),
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            assertEquals("verified", sink.data("verdict").single()["verdict"])
            val record = frozen.records.values.single()
            assertEquals(AcceptanceCriteria.digest(listOf("make lint", "npm test")), record.digest)
            assertEquals(null, record.changedDigest, "nothing is left for loop resume to accept")
            assertEquals(null, record.changedCount)
        }

    @Test
    fun `a changed list that cannot be forgotten is logged, the verification still runs`() =
        runTest {
            frozen.records["p1" to "id-m2-09"] =
                FrozenCommands(
                    ProjectId("p1"),
                    "id-m2-09",
                    "m2-09",
                    AcceptanceCriteria.digest(listOf("make lint", "npm test")),
                    2,
                    FIXED_NOW,
                    changedDigest = AcceptanceCriteria.digest(listOf("make lint")),
                    changedCount = 1,
                )
            frozen.clearFailure = IllegalStateException("the store is busy")
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                checks,
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            assertEquals(2, checks.calls.size)
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `a store that cannot give the frozen list gives unverified and runs nothing`() =
        runTest {
            frozen.failure = IllegalStateException("the store is gone")
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(
                FakeTracker(listOf(issue("m2-09", description = description))),
                checks,
                sink,
                workflow(listOf(CheckConfig.AcceptanceCommands())),
            ).tick()

            assertEquals(0, checks.calls.size)
            val verdict = sink.data("verdict").single()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("cannot read the frozen acceptance commands: the store is gone", verdict["reason"])
            assertEquals(listOf("unverified"), parked.records.values.map { it.reason.wire })
        }

    @Test
    fun `a tracker other than files never runs the commands of an issue`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)

            orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()), trackerKind = "github")).tick()

            assertEquals(0, checks.calls.size)
            val verdict = sink.data("verdict").single()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("acceptance_commands needs tracker.kind files, not \"github\"", verdict["reason"])
            assertEquals(emptyMap(), frozen.records)
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a holdout runs after the expanded commands and the deny guards, numbered after them (m2-13, m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val guards = CheckConfig.DiffPolicy(listOf("WORKFLOW.md"), deny = listOf(DenyGuard.DELETED_TESTS, DenyGuard.ADDED_SKIPS))
            val hidden = CheckConfig.Command("\$LOOP_HOLDOUT/acceptance.sh")
            val workflow = workflow(listOf(CheckConfig.AcceptanceCommands(600_000), guards), holdout = listOf(hidden))

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(
                listOf(CheckConfig.Command("make lint", 600_000), CheckConfig.Command("npm test", 600_000), guards, hidden),
                checks.calls.map { it.first },
            )
            assertEquals(
                listOf("0" to null, "1" to null, "2" to null, "3" to "true"),
                sink.data("check_finished").map { it["check"] to it["holdout"] },
            )
            assertEquals(listOf(false, false, false, true), checks.envs.map { "LOOP_HOLDOUT" in it })
            val verdict = sink.data("verdict").single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("4", verdict["checks"])
            assertEquals(listOf("id-m2-09" to "review"), tracker.moves)
        }

    @Test
    fun `criteria with no command next to the deny guards and a holdout run both, the holdout proves the work (m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = "## Acceptance criteria\n\n- [ ] A human reads it.")))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val guards = CheckConfig.DiffPolicy(listOf("WORKFLOW.md"), deny = listOf(DenyGuard.DELETED_TESTS))
            val hidden = CheckConfig.Command("\$LOOP_HOLDOUT/acceptance.sh")
            val workflow = workflow(listOf(CheckConfig.AcceptanceCommands(), guards), holdout = listOf(hidden))

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(listOf(guards, hidden), checks.calls.map { it.first }, "the deny guards are never dropped in front of a holdout")
            assertEquals(listOf(mapOf("judgment_calls" to "1")), sink.data("acceptance_commands_empty"))
            assertEquals("verified", sink.data("verdict").single()["verdict"])
        }

    @Test
    fun `a changed command list runs no holdout either (m2-14)`() =
        runTest {
            frozen.records["p1" to "id-m2-09"] =
                FrozenCommands(ProjectId("p1"), "id-m2-09", "m2-09", AcceptanceCriteria.digest(listOf("make lint")), 1, FIXED_NOW)
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val hidden = CheckConfig.Command("\$LOOP_HOLDOUT/acceptance.sh")

            orchestrator(tracker, checks, sink, workflow(listOf(CheckConfig.AcceptanceCommands()), holdout = listOf(hidden))).tick()

            assertEquals(0, checks.calls.size)
            val verdict = sink.data("verdict").single()
            assertEquals("unverified", verdict["verdict"])
            assertEquals("acceptance commands changed", verdict["reason"])
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `a tracker other than files runs no holdout either (m2-14)`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m2-09", description = description)))
            val sink = FakeSink()
            val checks = ScriptedChecks(0)
            val hidden = CheckConfig.Command("\$LOOP_HOLDOUT/acceptance.sh")
            val workflow = workflow(listOf(CheckConfig.AcceptanceCommands()), trackerKind = "github", holdout = listOf(hidden))

            orchestrator(tracker, checks, sink, workflow).tick()

            assertEquals(0, checks.calls.size)
            assertEquals("unverified", sink.data("verdict").single()["verdict"])
        }

    private companion object {
        /** Past the backoff of a first failure, so the retry is due. */
        const val RETRY_WAIT_MS = 600_000L
        const val FINGERPRINT = "git:0123456789abcdef0123456789abcdef01234567:1111111111111111:2222222222222222"
    }
}
