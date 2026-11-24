package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import loop.api.IssueComment
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TurnId
import loop.core.log.KeyValueLog
import loop.core.workflow.AgentConfig
import loop.core.workflow.RetriesConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The no progress counter (m1-27): consecutive normal exits that change neither the issue
 * state nor its labels park the issue at `retries.max_continuations`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorNoProgressTest {
    private val root: Path = createTempDirectory("loop-orchestrator-no-progress").toRealPath()
    private val logLines = mutableListOf<String>()

    private fun workflow(
        maxContinuations: Int = 3,
        maxTurns: Int = 20,
        maxBackoffMs: Long = 1_000L,
        maxConcurrentAgents: Int = 10,
    ): WorkflowFile =
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
            promptTemplate = PROMPT,
            config =
                WorkflowConfig(
                    agent =
                        AgentConfig(
                            maxConcurrentAgents = maxConcurrentAgents,
                            maxTurns = maxTurns,
                            maxRetryBackoffMs = maxBackoffMs,
                        ),
                    retries = RetriesConfig(maxContinuations = maxContinuations),
                ),
        )

    private fun TestScope.orchestrator(
        tracker: FakeTracker,
        runner: FakeRunner,
        sink: FakeSink,
        parked: FakeParkedIssues = FakeParkedIssues(),
        workflow: WorkflowFile = workflow(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = { logLines += it }, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = root.resolve("home"),
            hostPath = "/usr/bin",
            parked = parked,
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
        )
    }

    /**
     * A runner without a session: attempt n (from 1) runs [onAttempt] during its turn, then
     * fails when [fails] says so, else exits normally.
     */
    private fun runner(
        fails: (Int) -> Boolean = { false },
        onAttempt: suspend (Int) -> Unit = {},
    ): FakeRunner {
        var attempts = 0
        return FakeRunner { _, _ ->
            val attempt = ++attempts
            flow {
                onAttempt(attempt)
                emitAll(if (fails(attempt)) failingTurn() else succeedingTurn())
            }
        }
    }

    /** Lets [times] retries of 1 s fall due, one tick each. */
    private suspend fun TestScope.fireRetries(
        orchestrator: Orchestrator,
        times: Int,
    ) {
        repeat(times) {
            advanceTimeBy(1_000)
            orchestrator.tick()
        }
    }

    private fun FakeSink.ofKind(kind: String): List<RecordedEvent> = events.filter { it.kind == kind }

    /** The reason of the one parked record. */
    private fun FakeParkedIssues.reason(): ParkReason = records.values.single().reason

    /** The `no_progress` of every continuation `retry_scheduled`, in order. */
    private fun FakeSink.noProgress(): List<String?> =
        ofKind("retry_scheduled").filter { it.data["kind"] == "continuation" }.map { it.data["no_progress"] }

    @Test
    fun `normal exits without progress park the issue at max_continuations`() =
        runTest {
            val runner = runner()
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 2)

            val scheduled = sink.ofKind("retry_scheduled")
            assertEquals(listOf("1", "2"), scheduled.map { it.data["no_progress"] })
            assertEquals(listOf("3", "3"), scheduled.map { it.data["max_continuations"] })
            val event = sink.ofKind("issue_parked").single()
            assertEquals(mapOf("reason" to "max_continuations", "no_progress" to "3", "max_continuations" to "3"), event.data)
            assertEquals("a3", event.attemptId?.value)
            assertEquals(
                ParkedIssue(
                    projectId = PROJECT,
                    issueId = ISSUE_ID,
                    identifier = "m1-02",
                    reason = ParkReason.MaxContinuations,
                    state = "todo",
                    labels = setOf("ready-for-agent"),
                    parkedAt = FIXED_NOW + 2_000.milliseconds,
                    runId = RunId("r1"),
                ),
                parked.records.values.single(),
            )
            val parkLine = logLines.single { "park_reason=max_continuations" in it }
            assertTrue("level=warn" in parkLine, parkLine)

            advanceTimeBy(3_600_000)
            orchestrator.tick()
            orchestrator.tick()
            assertEquals(3, runner.calls.size)
        }

    @Test
    fun `a normal exit that moves the issue resets the count`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = runner { attempt -> if (attempt == 2) tracker.issues[0] = issue("m1-02", state = "in_progress") }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(tracker, runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 2)

            assertEquals(listOf("1", "0", "1"), sink.noProgress())
            assertEquals(3, runner.calls.size)
            assertTrue(parked.records.isEmpty())
        }

    @Test
    fun `a normal exit that changes only labels resets the count`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                runner { attempt ->
                    if (attempt == 2) tracker.issues[0] = issue("m1-02", labels = listOf("ready-for-agent", "backend"))
                }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(tracker, runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 3)
            assertEquals(listOf("1", "0", "1", "2"), sink.noProgress())
            assertTrue(parked.records.isEmpty())

            fireRetries(orchestrator, 1)

            assertEquals("3", sink.ofKind("issue_parked").single().data["no_progress"])
            assertEquals(ParkReason.MaxContinuations, parked.reason())
            assertEquals(5, runner.calls.size)
        }

    @Test
    fun `a normal exit that changes only the description or adds a comment does not reset the count`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                runner { attempt ->
                    tracker.issues[0] = tracker.issues[0].copy(description = "body $attempt")
                    tracker.addComment(ISSUE_ID, IssueComment("agent", FIXED_NOW, "note $attempt")).getOrThrow()
                }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(tracker, runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 2)

            assertEquals(3, tracker.comments.size)
            assertEquals(listOf("1", "2"), sink.noProgress())
            assertEquals("3", sink.ofKind("issue_parked").single().data["no_progress"])
            assertEquals(ParkReason.MaxContinuations, parked.reason())
        }

    @Test
    fun `a failure between normal exits neither increments nor resets the count`() =
        runTest {
            val runner = runner(fails = { it % 2 == 0 })
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 2)
            assertTrue(parked.records.isEmpty(), "parked after attempt 3")
            fireRetries(orchestrator, 1)
            assertTrue(parked.records.isEmpty(), "parked after attempt 4")
            fireRetries(orchestrator, 1)

            val scheduled = sink.ofKind("retry_scheduled")
            assertEquals(listOf("continuation", "error", "continuation", "error"), scheduled.map { it.data["kind"] })
            assertEquals(listOf("1", "2"), sink.noProgress())
            assertEquals(listOf("1", "1"), scheduled.filter { it.data["kind"] == "error" }.map { it.data["failures"] })
            val event = sink.ofKind("issue_parked").single()
            assertEquals("max_continuations", event.data["reason"])
            assertEquals("3", event.data["no_progress"])
            assertEquals(5, runner.calls.size)
        }

    @Test
    fun `an attempt that reaches max_turns without progress counts once`() =
        runTest {
            val runner = FakeRunner().apply { sessionScript = { turn, _ -> sessionTurn(turn) } }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator =
                orchestrator(
                    FakeTracker(listOf(issue("m1-02"))),
                    runner,
                    sink,
                    parked,
                    workflow(maxContinuations = 2, maxTurns = 3),
                )

            orchestrator.tick()
            fireRetries(orchestrator, 1)

            assertEquals(listOf("1"), sink.noProgress())
            assertEquals("2", sink.ofKind("retry_scheduled").single().data["max_continuations"])
            assertEquals(
                mapOf("reason" to "max_continuations", "no_progress" to "2", "max_continuations" to "2"),
                sink.ofKind("issue_parked").single().data,
            )
            advanceTimeBy(3_600_000)
            orchestrator.tick()
            assertEquals(2, runner.sessionsOpened)
            assertEquals(6, runner.turnInputs.size)
        }

    @Test
    fun `a not_found exit neither increments nor resets the count`() =
        runTest {
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = FakeRunner()
            // The third attempt's issue disappears during its one turn: the closing refresh finds nothing.
            runner.sessionScript = { turn, _ ->
                flow {
                    if (runner.sessionsOpened == 3) tracker.issues.clear()
                    emitAll(sessionTurn(turn))
                }
            }
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(tracker, runner, sink, parked, workflow(maxTurns = 1))

            orchestrator.tick()
            fireRetries(orchestrator, 2)
            assertTrue(parked.records.isEmpty(), "parked after the not_found exit")
            tracker.issues += issue("m1-02")
            fireRetries(orchestrator, 1)

            assertEquals(4, runner.sessionsOpened)
            assertEquals(listOf("1", "2", "2"), sink.noProgress())
            assertEquals("3", sink.ofKind("issue_parked").single().data["no_progress"])
            assertEquals(ParkReason.MaxContinuations, parked.reason())
        }

    @Test
    fun `unparking as loop resume does gives a fresh no progress count`() =
        runTest {
            val runner = runner()
            val sink = FakeSink()
            val parked = FakeParkedIssues()
            val orchestrator = orchestrator(FakeTracker(listOf(issue("m1-02"))), runner, sink, parked)

            orchestrator.tick()
            fireRetries(orchestrator, 2)
            assertNotNull(parked.unpark(PROJECT, ISSUE_ID).getOrThrow())

            orchestrator.tick()
            fireRetries(orchestrator, 1)
            assertTrue(parked.records.isEmpty(), "parked again before three more normal exits")
            fireRetries(orchestrator, 1)

            assertEquals(6, runner.calls.size)
            assertEquals(listOf("1", "2", "1", "2"), sink.noProgress())
            assertEquals(listOf("3", "3"), sink.ofKind("issue_parked").map { it.data["no_progress"] })
            assertEquals(ParkReason.MaxContinuations, parked.reason())
        }

    @Test
    fun `a continuation requeued twice for lack of a slot still carries the no progress count`() =
        runTest {
            // m1-03 starts blocked on a missing label, then takes the only slot and holds it before the
            // continuation is due: since bl-34 a due retry would take the slot before a new candidate.
            val tracker = FakeTracker(listOf(issue("m1-02", priority = 2), issue("m1-03", priority = 1, labels = listOf("other"))))
            val runner =
                FakeRunner { _, prompt ->
                    if (prompt.startsWith("m1-03")) {
                        flow {
                            emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
                            awaitCancellation()
                        }
                    } else {
                        succeedingTurn()
                    }
                }
            val sink = FakeSink()
            val orchestrator = orchestrator(tracker, runner, sink, workflow = workflow(maxConcurrentAgents = 1))

            orchestrator.tick()
            tracker.issues[1] = issue("m1-03", priority = 1)
            val holder = launch { orchestrator.tick() }
            runCurrent()
            advanceTimeBy(1_000)
            orchestrator.tick()
            advanceTimeBy(1_000)
            orchestrator.tick()

            // Since m4-15 (bl-77) the second requeue, for the same reason, records nothing.
            val requeue = sink.ofKind("retry_scheduled").single { it.issueId == ISSUE_ID && it.data["error"] != null }
            assertEquals("no available orchestrator slots", requeue.data["error"])
            assertEquals("1", requeue.data["no_progress"], requeue.data.toString())
            assertEquals("3", requeue.data["max_continuations"], requeue.data.toString())
            holder.cancelAndJoin()

            // The count survived both requeues: the continuation runs once the slot frees and counts on from it.
            advanceTimeBy(30_000)
            orchestrator.tick()
            assertEquals(listOf("1", "2"), sink.noProgress(), sink.events.toString())
        }

    /**
     * bl-136: a runner declaring occupancy whose session `s` runs [onTurn] in its one turn, then reports the occupancy
     * `report(s)` of the Opus model. At or above the hard limit it ends the turn once Loop interrupts it, as Claude Code
     * does, else it completes the turn.
     */
    private fun occupancyRunner(
        onTurn: (Int) -> Unit,
        report: (Int) -> Long,
    ): FakeRunner =
        FakeRunner().also { runner ->
            val interrupted = mutableMapOf<Int, CompletableDeferred<Unit>>()
            runner.onInterrupt = { session -> interrupted.getOrPut(session) { CompletableDeferred() }.complete(Unit) }
            runner.occupancy = true
            runner.sessionScript = { turn, _ ->
                val session = runner.sessionsOpened
                flow {
                    val turnId = TurnId("s${session}t$turn")
                    val sessionId = SessionId("th$session-${turnId.value}")
                    emit(RunnerEvent.SessionStarted(sessionId, "th$session", turnId, runtimeSessionId = "runtime-$session"))
                    onTurn(session)
                    val tokens = report(session)
                    emit(RunnerEvent.ContextUsage(sessionId, turnId, tokens, OPUS))
                    if (tokens >= HARD) {
                        interrupted.getOrPut(session) { CompletableDeferred() }.await()
                        emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = "turn_failed: error_during_execution"))
                    } else {
                        emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                    }
                }
            }
        }

    /** The workspace of m1-02, a git work tree with one commit that ignores `.scratch/`, before the attempt reuses it. */
    private fun gitWorkspace(): Path {
        val workspace = root.resolve("workspaces").resolve("m1-02").createDirectories()
        git(workspace, "init", "-q")
        workspace.resolve(".gitignore").writeText(".scratch/\n")
        git(workspace, "add", ".gitignore")
        git(workspace, "commit", "-q", "-m", "first")
        return workspace
    }

    private fun git(
        workspace: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
    }

    @Test
    fun `bl-136 two rotations at the same head with a working tree changed between them do not park with split_issue`() =
        runTest {
            val workspace = gitWorkspace()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            // Session 1 changes nothing, session 2 writes a file it does not commit, session 3 hands off.
            val runner =
                occupancyRunner(
                    onTurn = { session ->
                        if (session == 2) workspace.resolve("notes.txt").writeText("work in progress\n")
                        if (session == 3) tracker.issues[0] = issue("m1-02", state = "review")
                    },
                ) { session -> if (session < 3) 300_000L else 30_000L }
            val sink = FakeSink()
            val parked = FakeParkedIssues()

            orchestrator(tracker, runner, sink, parked).tick()

            assertEquals(3, runner.sessionsOpened)
            assertEquals(listOf("1", "2"), sink.ofKind("session_rotated").map { it.data["rotation"] })
            assertTrue(sink.ofKind("issue_parked").isEmpty(), sink.events.toString())
            assertTrue(parked.records.isEmpty())
        }

    @Test
    fun `bl-136 a change under the gitignored scratch folder is no progress and still parks with split_issue`() =
        runTest {
            val workspace = gitWorkspace()
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                occupancyRunner(
                    onTurn = { session ->
                        val notes = workspace.resolve(".scratch/m1-02").createDirectories().resolve("progress.md")
                        notes.writeText("session $session\n")
                    },
                ) { 300_000L }
            val sink = FakeSink()
            val parked = FakeParkedIssues()

            orchestrator(tracker, runner, sink, parked).tick()

            assertEquals(2, runner.sessionsOpened)
            assertEquals("split_issue", sink.ofKind("issue_parked").single().data["reason"])
            assertEquals(ParkReason.SplitIssue, parked.reason())
        }

    /** The operator log lines of the `progress` event that name [fact]. */
    private fun progressLines(fact: String): List<String> = logLines.filter { "event=progress" in it && "fact=$fact" in it }

    @Test
    fun `bl-149 a readable HEAD with a working tree git cannot read counts as progress and is logged once per attempt`() =
        runTest {
            val workspace = gitWorkspace()
            // A corrupt index fails git status, while rev-parse still reads HEAD.
            workspace.resolve(".git/index").writeText("not an index")
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner = occupancyRunner(onTurn = {}) { 300_000L }
            val sink = FakeSink()
            val parked = FakeParkedIssues()

            orchestrator(tracker, runner, sink, parked).tick()

            // Two rotations without a commit would park with split_issue; the unread working tree counts as progress.
            assertTrue(runner.sessionsOpened > 2, sink.events.toString())
            assertTrue(sink.ofKind("issue_parked").none { it.data["reason"] == "split_issue" }, sink.events.toString())
            val lines = progressLines("working_tree")
            assertEquals(1, lines.size, logLines.joinToString("\n"))
            val line = lines.single()
            assertTrue("level=warn" in line, line)
            assertTrue("outcome=failed" in line, line)
            assertTrue("attempt_id=a1" in line, line)
            assertTrue("git status" in line, line)
            assertTrue(progressLines("head").isEmpty(), logLines.joinToString("\n"))
        }

    @Test
    fun `bl-149 a HEAD git cannot read is logged once per attempt`() =
        runTest {
            // A repository with no commit: HEAD cannot be read, the working tree can.
            val workspace = root.resolve("workspaces").resolve("m1-02").createDirectories()
            git(workspace, "init", "-q")
            val tracker = FakeTracker(listOf(issue("m1-02")))
            val runner =
                occupancyRunner(onTurn = { session -> if (session == 3) tracker.issues[0] = issue("m1-02", state = "review") }) { session ->
                    if (session < 3) 300_000L else 30_000L
                }
            val sink = FakeSink()

            orchestrator(tracker, runner, sink).tick()

            assertEquals(3, runner.sessionsOpened)
            val lines = progressLines("head")
            assertEquals(1, lines.size, logLines.joinToString("\n"))
            assertTrue("level=warn" in lines.single(), lines.single())
            assertTrue(progressLines("working_tree").isEmpty(), logLines.joinToString("\n"))
        }

    private companion object {
        val PROJECT = ProjectId("p1")
        const val ISSUE_ID = "id-m1-02"
        const val OPUS = "anthropic/claude-opus-5-5"

        /** The default hard limit, 0.25, of the Opus window of 1 000 000 tokens. */
        const val HARD = 250_000L
        const val PROMPT = "{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
    }
}
