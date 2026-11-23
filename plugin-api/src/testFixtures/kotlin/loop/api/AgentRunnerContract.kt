package loop.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The behavior every [AgentRunner] must show, whatever agent it drives (m3-06). A runner's test
 * extends this class and supplies the runners below, each driving a fake agent.
 *
 * The cases check that a turn opens with [RunnerEvent.SessionStarted] (turn 1) or
 * [RunnerEvent.TurnStarted] (turns 2 to n) and ends with exactly one terminal event; that
 * [RunnerEvent.Usage] values are absolute and never decrease within a session; that cancelling
 * the flow leaves no agent process or child of it; that the declared [RunnerCapabilities] match
 * what the runner does (continuation, usage, cost, occupancy); that a
 * [RunnerEvent.TurnFailed] category is null or `runner_unavailable` (bl-39); and that a session's
 * [AgentSession.stop] with a report callback reports only usage absolute for the session and at most
 * one [RunnerEvent.StopGraceExpired], last (bl-152).
 */
public abstract class AgentRunnerContract {
    @TempDir
    public lateinit var workspace: Path

    @TempDir
    public lateinit var scratch: Path

    /**
     * A runner whose agent writes its working directory (`pwd -P`) on the first line of
     * [report], the value of the [PROBE_ENV] variable on the second, then succeeds. When the
     * runner declares [RunnerCapabilities.continuation], its session runs every turn this way.
     */
    protected abstract fun succeedingRunner(report: Path): AgentRunner

    /** A runner whose agent fails. */
    protected abstract fun failingRunner(): AgentRunner

    /**
     * A runner whose agent starts a child process that never ends, then writes its own pid to
     * [pidFile], then waits for the child, so it never ends on its own either.
     */
    protected abstract fun hangingRunner(pidFile: Path): AgentRunner

    /**
     * A runner whose agent exits without reporting a turn result, or null when the runner has
     * no notion of a turn result apart from the exit code. A runner may skip this case
     * because for it the case cannot happen: CommandRunner has no result line, its exit code
     * is the turn result, so every exit already ends in TurnCompleted or TurnFailed.
     */
    protected open fun exitsWithoutResultRunner(): AgentRunner? = null

    /** A runner whose agent asks for input it cannot get unattended, or null when the runtime has no such signal. */
    protected open fun inputRequiredRunner(): AgentRunner? = null

    /**
     * A runner whose runtime reports that it cannot reach its model (bl-39), or null when the
     * runtime has no such signal apart from an ordinary failure.
     */
    protected open fun unavailableRunner(): AgentRunner? = null

    @Test
    public fun `SessionStarted comes first and its session id is the thread id and the turn id`() {
        listOf(succeedingRunner(report()), failingRunner()).forEach { runner ->
            val started = assertIs<RunnerEvent.SessionStarted>(runAll(runner).first())
            assertEquals("${started.threadId}-${started.turnId.value}", started.sessionId.value)
        }
    }

    @Test
    public fun `a succeeding agent ends with exactly one terminal event, TurnCompleted, last`() {
        val events = runAll(succeedingRunner(report()))

        assertEquals(1, events.count(::isTerminal), "terminal events in $events")
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    public fun `a failing agent ends with exactly one terminal event, TurnFailed, last`() {
        val events = runAll(failingRunner())

        assertEquals(1, events.count(::isTerminal), "terminal events in $events")
        assertIs<RunnerEvent.TurnFailed>(events.last())
    }

    @Test
    public fun `an agent that exits without a turn result ends with exactly one TurnFailed, last`() {
        val runner = exitsWithoutResultRunner()
        assumeTrue(runner != null, "the runner has no notion of a turn result")
        val events = runAll(checkNotNull(runner))

        assertEquals(1, events.count(::isTerminal), "terminal events in $events")
        assertIs<RunnerEvent.TurnFailed>(events.last())
    }

    @Test
    public fun `an agent that asks for input ends with exactly one terminal event, TurnInputRequired, last`() {
        val runner = inputRequiredRunner()
        assumeTrue(runner != null, "the runtime has no input required signal")
        val events = runAll(checkNotNull(runner))

        assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals(1, events.count(::isTerminal), "terminal events in $events")
        assertIs<RunnerEvent.TurnInputRequired>(events.last())
    }

    @Test
    public fun `a runtime that cannot reach its model fails the turn with runner_unavailable`() {
        val runner = unavailableRunner()
        assumeTrue(runner != null, "the runtime has no runner unavailable signal")
        val events = runAll(checkNotNull(runner))

        assertEquals(1, events.count(::isTerminal), "terminal events in $events")
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category, "category of $failed")
    }

    @Test
    public fun `a TurnFailed category is null or runner_unavailable`() {
        val allowed = setOf(null, RunnerEvent.TurnFailed.Category.RunnerUnavailable)
        observedRuns().forEach { run ->
            run.events.filterIsInstance<RunnerEvent.TurnFailed>().forEach { failed ->
                assertTrue(failed.category in allowed, "the ${run.name} run failed with category ${failed.category}: $failed")
            }
        }
    }

    @Test
    public fun `every event after SessionStarted carries its session id and turn id`() {
        listOf(succeedingRunner(report()), failingRunner()).forEach { runner ->
            val events = runAll(runner)
            val started = assertIs<RunnerEvent.SessionStarted>(events.first())
            events.drop(1).forEach { event ->
                val ids =
                    when (event) {
                        is RunnerEvent.SessionStarted -> fail("a second SessionStarted: $event")
                        is RunnerEvent.TurnStarted -> fail("TurnStarted in a one turn run: $event")
                        is RunnerEvent.Output -> null
                        is RunnerEvent.Usage -> event.sessionId to event.turnId
                        is RunnerEvent.UsageEstimate -> event.sessionId to event.turnId
                        is RunnerEvent.ContextUsage -> event.sessionId to event.turnId
                        is RunnerEvent.RateLimit -> event.sessionId to event.turnId
                        is RunnerEvent.WakeTurn -> event.sessionId to event.turnId
                        is RunnerEvent.StopGraceExpired -> fail("StopGraceExpired outside a stop: $event")
                        is RunnerEvent.TurnCompleted -> event.sessionId to event.turnId
                        is RunnerEvent.TurnFailed -> event.sessionId to event.turnId
                        is RunnerEvent.TurnInputRequired -> event.sessionId to event.turnId
                    }
                if (ids != null) assertEquals(started.sessionId to started.turnId, ids, "ids of $event")
            }
        }
    }

    @Test
    public fun `openSession matches the declared continuation`() {
        val runner = succeedingRunner(report())
        val session = runner.openSession(context())
        try {
            if (runner.capabilities.continuation) {
                assertNotNull(session, "the runner declares continuation, but openSession returned null")
            } else {
                assertNull(session, "the runner declares no continuation, but openSession returned a session")
            }
        } finally {
            session?.let { runBlocking { it.stop() } }
        }
    }

    @Test
    public fun `a second turn keeps the thread, starts a new turn and stops the session`() {
        val runner = succeedingRunner(report())
        assumeTrue(runner.capabilities.continuation, "the runner declares no continuation")
        val session = assertNotNull(runner.openSession(context()), "the runner declares continuation")

        val first = runBlocking { session.runTurn("first", 1).toList() }
        val second = runBlocking { session.runTurn("second", 2).toList() }
        runBlocking { session.stop() }

        val started = assertIs<RunnerEvent.SessionStarted>(first.first())
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(1, first.count(::isTerminal), "terminal events in $first")
        assertEquals(1, second.count(::isTerminal), "terminal events in $second")
        assertIs<RunnerEvent.TurnCompleted>(first.last())
        assertIs<RunnerEvent.TurnCompleted>(second.last())
        assertEquals(started.threadId, resumed.threadId)
        assertNotEquals(started.turnId, resumed.turnId)
        assertEquals(2, resumed.turn)
        assertEquals("${resumed.threadId}-${resumed.turnId.value}", resumed.sessionId.value)
        assertTrue(second.none { it is RunnerEvent.SessionStarted }, "turn 2 emits no SessionStarted: $second")
    }

    @Test
    public fun `a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`() {
        val runner = succeedingRunner(report())
        assumeTrue(runner.capabilities.continuation, "the runner declares no continuation")
        val session = assertNotNull(runner.openSession(context()), "the runner declares continuation")
        val (turn, reported) =
            CompletableFuture
                .supplyAsync {
                    runBlocking {
                        val reported = mutableListOf<RunnerEvent>()
                        val turn =
                            try {
                                session.runTurn("first", 1).toList()
                            } finally {
                                session.stop { reported += it }
                            }
                        turn to reported.toList()
                    }
                }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

        reported.forEach { event ->
            assertTrue(
                event is RunnerEvent.Usage || event is RunnerEvent.StopGraceExpired,
                "its stop reported an event other than Usage and StopGraceExpired: $event",
            )
        }
        val usage = reported.filterIsInstance<RunnerEvent.Usage>()
        if (!runner.capabilities.usage) assertTrue(usage.isEmpty(), "the runner declares no usage, but its stop reported $usage")
        val thread = assertIs<RunnerEvent.SessionStarted>(turn.first()).threadId
        usage.forEach { report ->
            assertTrue(
                report.sessionId.value.startsWith("$thread-"),
                "usage reported at the stop is not of the session's thread $thread: $report",
            )
        }
        val absolute = listOfNotNull(turn.filterIsInstance<RunnerEvent.Usage>().lastOrNull()) + usage
        absolute.zipWithNext().forEach { (before, after) ->
            val down = before.tokens().zip(after.tokens()).any { (b, a) -> a < b }
            val costDown = before.totalCostUsd != null && after.totalCostUsd != null && after.totalCostUsd < before.totalCostUsd
            assertTrue(!down && !costDown, "usage went down at the stop, from $before to $after")
        }
        val graces = reported.filterIsInstance<RunnerEvent.StopGraceExpired>()
        assertTrue(graces.size <= 1, "its stop reported more than one StopGraceExpired: $graces")
        if (graces.isNotEmpty()) assertIs<RunnerEvent.StopGraceExpired>(reported.last(), "StopGraceExpired is not last of $reported")
    }

    @Test
    public fun `Usage is reported exactly when the runner declares usage`() {
        observedRuns().forEach { run ->
            val usage = run.events.filterIsInstance<RunnerEvent.Usage>()
            if (run.declared.usage) {
                if (run.succeeds) assertTrue(usage.isNotEmpty(), "the runner declares usage, but the ${run.name} run reported none")
            } else {
                assertTrue(usage.isEmpty(), "the runner declares no usage, but the ${run.name} run reported $usage")
            }
        }
    }

    @Test
    public fun `a cost is reported exactly when the runner declares cost`() {
        observedRuns().forEach { run ->
            val usage = run.events.filterIsInstance<RunnerEvent.Usage>()
            if (run.declared.cost) {
                if (run.succeeds) {
                    assertNotNull(
                        usage.lastOrNull()?.totalCostUsd,
                        "the runner declares cost, but the ${run.name} run reported none: $usage",
                    )
                }
            } else {
                val costs = usage.mapNotNull { it.totalCostUsd }
                assertTrue(costs.isEmpty(), "the runner declares no cost, but the ${run.name} run reported $costs")
            }
        }
    }

    @Test
    public fun `ContextUsage is reported exactly when the runner declares occupancy`() {
        observedRuns().forEach { run ->
            val occupancy = run.events.filterIsInstance<RunnerEvent.ContextUsage>()
            if (run.declared.occupancy) {
                if (run.succeeds) assertTrue(occupancy.isNotEmpty(), "the runner declares occupancy, but the ${run.name} run reported none")
                occupancy.forEach { assertTrue(it.occupancyTokens >= 0, "a negative occupancy in the ${run.name} run: $it") }
            } else {
                assertTrue(occupancy.isEmpty(), "the runner declares no occupancy, but the ${run.name} run reported $occupancy")
            }
        }
    }

    @Test
    public fun `Usage values are absolute and never decrease within a session`() {
        observedRuns().forEach { run ->
            val usage = run.events.filterIsInstance<RunnerEvent.Usage>()
            usage.forEach { report ->
                assertTrue(report.tokens().all { it >= 0 }, "a negative usage value in the ${run.name} run: $report")
            }
            usage.zipWithNext().forEach { (before, after) ->
                val down = before.tokens().zip(after.tokens()).any { (b, a) -> a < b }
                val costDown = before.totalCostUsd != null && after.totalCostUsd != null && after.totalCostUsd < before.totalCostUsd
                assertTrue(!down && !costDown, "usage went down in the ${run.name} run, from $before to $after")
            }
        }
    }

    @Test
    public fun `the agent runs in the workspace and sees the context env`() {
        val report = report()

        runAll(succeedingRunner(report), env = mapOf(PROBE_ENV to "probe-value"))

        assertEquals(listOf(workspace.toRealPath().toString(), "probe-value"), report.readLines())
    }

    @Test
    public fun `cancelling the collection kills the agent process and its children`() {
        val pidFile = scratch.resolve("agent.pid").also { pidFiles.add(it) }
        val runner = hangingRunner(pidFile)
        var children = emptyList<ProcessHandle>()

        runBlocking {
            withTimeout(TIMEOUT) {
                val collection = launch(Dispatchers.IO) { runner.run(context(), "").collect() }
                while (readPid(pidFile) == null) kotlinx.coroutines.delay(POLL_MS)
                children = descendantsOf(checkNotNull(readPid(pidFile))).also { descendants.addAll(it) }
                collection.cancelAndJoin()
            }
        }

        val pid = checkNotNull(readPid(pidFile)) { "the agent wrote its pid" }
        assertTrue(children.isNotEmpty(), "the hanging agent $pid started no child, so the case proves nothing")
        assertTrue(awaitDead(pid), "the agent process $pid is gone after cancel")
        children.forEach { child ->
            assertTrue(awaitDead(child.pid()), "the child process ${child.pid()} of the agent is gone after cancel")
        }
    }

    /** Pid files of agents; @AfterEach kills every survivor, even when the test failed, and fails on it. */
    private val pidFiles = mutableListOf<Path>()

    /** The children of hanging agents, killed by @AfterEach like the agents. */
    private val descendants = mutableListOf<ProcessHandle>()

    @AfterEach
    public fun `no agent outlives its test`() {
        val survivors =
            (pidFiles.mapNotNull(::readPid) + descendants.map { it.pid() }).filter(::isAlive).onEach { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
        assertTrue(survivors.isEmpty(), "agents still alive after the test, now killed: $survivors")
    }

    /** The context of the attempt the runners execute. */
    protected fun context(env: Map<String, String> = emptyMap()): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "c-01",
                    title = "Contract",
                    state = "todo",
                    priority = null,
                    labels = listOf("ready-for-agent"),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = env,
        )

    /**
     * The events of one observed run: [events] in order, every turn of a session included, from a runner that
     * [declared] these capabilities; [succeeds] when its agent succeeds, so a declared report must show.
     */
    private class ObservedRun(
        val name: String,
        val declared: RunnerCapabilities,
        val succeeds: Boolean,
        val events: List<RunnerEvent>,
    )

    /**
     * Every run the capability cases look at: one turn of each runner the test supplies, and two turns of a session
     * of the succeeding runner when it opens one.
     */
    private fun observedRuns(): List<ObservedRun> {
        val single =
            listOfNotNull(
                Triple("succeeding", succeedingRunner(report()), true),
                Triple("failing", failingRunner(), false),
                exitsWithoutResultRunner()?.let { Triple("exits without result", it, false) },
                inputRequiredRunner()?.let { Triple("input required", it, false) },
                unavailableRunner()?.let { Triple("unavailable", it, false) },
            ).map { (name, runner, succeeds) -> ObservedRun(name, runner.capabilities, succeeds, runAll(runner)) }
        val runner = succeedingRunner(report())
        val session =
            runner.openSession(context())?.let { session ->
                val turns =
                    CompletableFuture
                        .supplyAsync {
                            runBlocking {
                                try {
                                    session.runTurn("first", 1).toList() + session.runTurn("second", 2).toList()
                                } finally {
                                    session.stop()
                                }
                            }
                        }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
                ObservedRun("two turn session", runner.capabilities, true, turns)
            }
        return single + listOfNotNull(session)
    }

    /** Collects the whole flow on its own thread, so a hung flow cannot keep @AfterEach from running. */
    private fun runAll(
        runner: AgentRunner,
        env: Map<String, String> = emptyMap(),
    ): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(env), "").toList() } }
            .get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private fun report(): Path = Files.createTempFile(scratch, "report", ".txt")

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

    private fun RunnerEvent.Usage.tokens(): List<Long> = listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    private fun readPid(file: Path): Long? = if (Files.exists(file)) file.readText().trim().toLongOrNull() else null

    /** The live descendants of [pid], waiting up to [CHILD_WAIT] for the first to show. */
    private fun descendantsOf(pid: Long): List<ProcessHandle> {
        val deadline = System.nanoTime() + CHILD_WAIT.inWholeNanoseconds
        while (true) {
            val found = ProcessHandle.of(pid).map { it.descendants().toList() }.orElse(emptyList())
            if (found.isNotEmpty() || System.nanoTime() > deadline) return found
            Thread.sleep(POLL_MS)
        }
    }

    private fun awaitDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return !isAlive(pid)
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    public companion object {
        /** The variable the contract puts in the context env for the succeeding agent to report. */
        public const val PROBE_ENV: String = "LOOP_CONTRACT_PROBE"

        private val TIMEOUT = 10.seconds
        private val CHILD_WAIT = 2.seconds
        private const val POLL_MS = 50L
    }
}
