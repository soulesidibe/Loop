package loop.runner.codex

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.AccountRateLimitsUpdatedNotification
import loop.runner.codex.protocol.v2.RateLimitReachedType
import loop.runner.codex.protocol.v2.RateLimitSnapshot
import loop.runner.codex.protocol.v2.RateLimitWindow
import loop.runner.codex.protocol.v2.ThreadTokenUsage
import loop.runner.codex.protocol.v2.ThreadTokenUsageUpdatedNotification
import loop.runner.codex.protocol.v2.TokenUsageBreakdown
import loop.runner.codex.schema.PinnedSchema
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m3-21, 17.5 items 13 and 14 and the rate limit half of 17.6 item 4: the runner reads usage from the pinned protocol's
 * `thread/tokenUsage/updated` and rate limits from `account/rateLimits/updated`. Usage is absolute for the session,
 * so a repeated identical update counts once and a later one replaces the earlier; Codex reports no cost. Every server
 * is a [FakeAppServer], and every notification it writes is checked against the pinned schema.
 */
class CodexTelemetryTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val schema = PinnedSchema.load()

    @Test
    fun `17_5 item 13 repeated identical usage updates count once and a later total replaces the earlier`() {
        val first = breakdown(input = 1_000, cached = 600, output = 200)
        val later = breakdown(input = 5_000, cached = 3_000, output = 900)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(first))
                notification(tokenUsage(first))
                notification(tokenUsage(first))
                notification(tokenUsage(later))
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        val usage = events.filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(totals(400, 200, 600, 0), totals(2_000, 900, 3_000, 0)), usage.map { it.totals() }, "$events")
        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        usage.forEach { assertEquals(started.sessionId to started.turnId, it.sessionId to it.turnId) }
    }

    @Test
    fun `m3-21 usage maps the protocol's cached input apart from the input, so the four counts add up to its total tokens`() {
        val total = breakdown(input = 10_000, cached = 7_000, cacheWrite = 500, output = 1_200, reasoning = 800)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(total))
                turnCompleted()
            }

        val usage = runAll(runner(server.command)).filterIsInstance<RunnerEvent.Usage>().single()

        assertEquals(totals(2_500, 1_200, 7_000, 500), usage.totals())
        val sum = usage.inputTokens + usage.outputTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens
        assertEquals(total.totalTokens, sum)
    }

    @Test
    fun `m3-21 a session's usage carries no cost, since Codex reports none`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(breakdown(input = 100, cached = 0, output = 10)))
                turnCompleted()
            }

        val usage = runAll(runner(server.command)).filterIsInstance<RunnerEvent.Usage>().single()

        assertNull(usage.totalCostUsd)
        assertTrue(runner(server.command).capabilities.usage)
        assertFalse(runner(server.command).capabilities.cost)
    }

    @Test
    fun `m3-21 a continuation turn reports the thread's total, which replaces the first turn's instead of adding to it`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(breakdown(input = 1_000, cached = 0, output = 100)))
                turnCompleted()
                continuation(4, TURN_2)
                notification(tokenUsage(breakdown(input = 3_000, cached = 1_000, output = 300), turnId = TURN_2))
                turnCompleted(TURN_2)
            }

        val (first, second) = runSession(runner(server.command), "Work on c-01", "Go on")

        assertEquals(listOf(totals(1_000, 100, 0, 0)), first.filterIsInstance<RunnerEvent.Usage>().map { it.totals() })
        assertEquals(listOf(totals(2_000, 300, 1_000, 0)), second.filterIsInstance<RunnerEvent.Usage>().map { it.totals() })
        val started = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(started.turnId, second.filterIsInstance<RunnerEvent.Usage>().single().turnId)
    }

    @Test
    fun `m3-21 a subagent thread's usage adds to the session's, each thread counted by its latest total`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(breakdown(input = 1_000, cached = 0, output = 100)))
                notification(tokenUsage(breakdown(input = 400, cached = 0, output = 40), threadId = SUBAGENT_THREAD, turnId = "sub-turn"))
                notification(tokenUsage(breakdown(input = 2_000, cached = 0, output = 200)))
                notification(tokenUsage(breakdown(input = 400, cached = 0, output = 40), threadId = SUBAGENT_THREAD, turnId = "sub-turn"))
                turnCompleted()
            }

        val usage = runAll(runner(server.command)).filterIsInstance<RunnerEvent.Usage>()

        assertEquals(
            listOf(totals(1_000, 100, 0, 0), totals(1_400, 140, 0, 0), totals(2_400, 240, 0, 0)),
            usage.map { it.totals() },
        )
    }

    @Test
    fun `m3-21 usage read before the turn started is emitted after SessionStarted with the turn's ids`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(1, FakeAppServer.initializeResult())
                awaitClient()
                awaitClient()
                response(2, FakeAppServer.threadStartResult(FakeAppServer.THREAD_ID))
                awaitClient()
                notification(rateLimits(RateLimitSnapshot(limitId = "codex", primary = RateLimitWindow(usedPercent = 3))))
                response(3, FakeAppServer.turnStartResult(FakeAppServer.TURN_ID))
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val limit = events.filterIsInstance<RunnerEvent.RateLimit>().single()
        assertEquals(started.sessionId to started.turnId, limit.sessionId to limit.turnId)
    }

    @Test
    fun `17_5 item 13 a rate limit update maps to a RateLimit event with its windows, and a repeated identical one counts once`() {
        val snapshot =
            RateLimitSnapshot(
                limitId = "codex",
                limitName = "Codex",
                primary = RateLimitWindow(usedPercent = 42, windowDurationMins = 300, resetsAt = 1_794_816_000),
                secondary = RateLimitWindow(usedPercent = 7, windowDurationMins = 10_080),
            )
        val reached = snapshot.copy(primary = RateLimitWindow(usedPercent = 100, windowDurationMins = 300, resetsAt = 1_794_816_000))
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(rateLimits(snapshot))
                notification(rateLimits(snapshot))
                notification(rateLimits(reached.copy(rateLimitReachedType = RateLimitReachedType.RATE_LIMIT_REACHED)))
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        val limits = events.filterIsInstance<RunnerEvent.RateLimit>()
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val resets = Instant.fromEpochSeconds(1_794_816_000)
        assertEquals(
            listOf(
                RunnerEvent.RateLimit(
                    started.sessionId,
                    started.turnId,
                    limitId = "codex",
                    limitName = "Codex",
                    primary = RunnerEvent.RateLimit.Window(42, 300, resets),
                    secondary = RunnerEvent.RateLimit.Window(7, 10_080, null),
                    reached = null,
                ),
                RunnerEvent.RateLimit(
                    started.sessionId,
                    started.turnId,
                    limitId = "codex",
                    limitName = "Codex",
                    primary = RunnerEvent.RateLimit.Window(100, 300, resets),
                    secondary = RunnerEvent.RateLimit.Window(7, 10_080, null),
                    reached = "rate_limit_reached",
                ),
            ),
            limits,
            "$events",
        )
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `m3-21 a sparse rate limit update keeps what an earlier one of the same limit said and it did not`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(
                    rateLimits(
                        RateLimitSnapshot(
                            limitId = "codex",
                            limitName = "Codex",
                            primary = RateLimitWindow(usedPercent = 10, windowDurationMins = 300),
                            secondary = RateLimitWindow(usedPercent = 2, windowDurationMins = 10_080),
                        ),
                    ),
                )
                notification(rateLimits(RateLimitSnapshot(primary = RateLimitWindow(usedPercent = 11, windowDurationMins = 300))))
                turnCompleted()
            }

        val limits = runAll(runner(server.command)).filterIsInstance<RunnerEvent.RateLimit>()

        val last = limits.last()
        assertEquals(2, limits.size, "$limits")
        assertEquals("codex", last.limitId)
        assertEquals("Codex", last.limitName)
        assertEquals(RunnerEvent.RateLimit.Window(11, 300, null), last.primary)
        assertEquals(RunnerEvent.RateLimit.Window(2, 10_080, null), last.secondary)
    }

    @Test
    fun `m3-21 a telemetry notification that does not match the pinned schema is skipped with a loop line, the turn goes on`() {
        val badUsage = buildJsonObject { put("threadId", FakeAppServer.THREAD_ID) }
        val badLimits = buildJsonObject { put("rateLimits", "full") }
        val server =
            FakeAppServer.write(dir) {
                handshake()
                raw(notificationLine("thread/tokenUsage/updated", badUsage.toString()))
                raw(notificationLine("account/rateLimits/updated", badLimits.toString()))
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        assertTrue(events.none { it is RunnerEvent.Usage || it is RunnerEvent.RateLimit }, "$events")
        val notes = events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stderr && it.line.startsWith("loop: ") }
        assertEquals(
            listOf(
                "loop: the thread/tokenUsage/updated notification does not match the pinned schema, skipped",
                "loop: the account/rateLimits/updated notification does not match the pinned schema, skipped",
            ),
            notes.map { it.line },
        )
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    private fun notificationLine(
        method: String,
        params: String,
    ): String = "{\"method\":\"$method\",\"params\":$params}"

    private fun tokenUsage(
        total: TokenUsageBreakdown,
        threadId: String = FakeAppServer.THREAD_ID,
        turnId: String = FakeAppServer.TURN_ID,
    ): ServerNotification =
        ServerNotification
            .ThreadTokenUsageUpdated(
                params = ThreadTokenUsageUpdatedNotification(threadId, ThreadTokenUsage(last = total, total = total), turnId),
            ).also(::checkSchema)

    private fun rateLimits(snapshot: RateLimitSnapshot): ServerNotification =
        ServerNotification.AccountRateLimitsUpdated(params = AccountRateLimitsUpdatedNotification(snapshot)).also(::checkSchema)

    private fun checkSchema(notification: ServerNotification) {
        val json = CodexJson.format.encodeToJsonElement(ServerNotification.serializer(), notification)
        assertEquals(emptyList(), schema.problems("ServerNotification", json), "$json")
    }

    private fun breakdown(
        input: Long,
        cached: Long,
        output: Long,
        cacheWrite: Long = 0,
        reasoning: Long = 0,
    ) = TokenUsageBreakdown(
        cacheWriteInputTokens = cacheWrite,
        cachedInputTokens = cached,
        inputTokens = input,
        outputTokens = output,
        reasoningOutputTokens = reasoning,
        totalTokens = input + output,
    )

    private data class Totals(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheCreation: Long,
    )

    private fun totals(
        input: Long,
        output: Long,
        cacheRead: Long,
        cacheCreation: Long,
    ) = Totals(input, output, cacheRead, cacheCreation)

    private fun RunnerEvent.Usage.totals() = Totals(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    private fun runner(command: String): CodexAppServerRunner =
        CodexAppServerRunner(
            CodexAppServerSettings(command, strictMcp = false),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = ProcessGroupLauncher()::launch,
            loopVersion = "test-build",
        )

    private fun runAll(runner: CodexAppServerRunner): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(), "Work on c-01").toList() } }
            .get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private fun runSession(
        runner: CodexAppServerRunner,
        vararg inputs: String,
    ): List<List<RunnerEvent>> =
        CompletableFuture
            .supplyAsync {
                runBlocking {
                    val session = checkNotNull(runner.openSession(context()))
                    try {
                        inputs.mapIndexed { index, input -> session.runTurn(input, index + 1).toList() }
                    } finally {
                        session.stop()
                    }
                }
            }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "c-01",
                    title = "Codex",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )

    private companion object {
        const val TURN_2 = "019a0000-0000-7000-8000-000000000003"
        const val SUBAGENT_THREAD = "019a0000-0000-7000-8000-0000000000aa"
        val TIMEOUT = 30.seconds
    }
}
