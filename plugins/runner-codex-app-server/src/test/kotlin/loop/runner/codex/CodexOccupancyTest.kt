package loop.runner.codex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.ModelRerouteReason
import loop.runner.codex.protocol.v2.ModelReroutedNotification
import loop.runner.codex.protocol.v2.ThreadTokenUsage
import loop.runner.codex.protocol.v2.ThreadTokenUsageUpdatedNotification
import loop.runner.codex.protocol.v2.TokenUsageBreakdown
import loop.runner.codex.protocol.v2.TurnStatus
import loop.runner.codex.schema.PinnedSchema
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * m3-23, story 42 with plan 8.3 on Codex: the runner reports the occupancy of its main thread as `context_usage`, read
 * from the `last` usage of `thread/tokenUsage/updated` (the protocol's `last_token_usage`), never from its cumulative
 * `total`, with the `context.windows` key `<modelProvider>/<model>` of the thread; it declares occupancy, and
 * [AgentSession.interrupt] ends the running turn with `turn/interrupt`, for the hard limit. Every server is a
 * [FakeAppServer], and every message is checked against the pinned schema.
 */
class CodexOccupancyTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val schema = PinnedSchema.load()

    @Test
    fun `m3-23 context_usage follows the last request's input, cached tokens included, not the cumulative totals`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(
                    tokenUsage(last = breakdown(input = 30_000, cached = 20_000), total = breakdown(input = 30_000, cached = 20_000)),
                )
                notification(
                    tokenUsage(last = breakdown(input = 45_000, cached = 40_000), total = breakdown(input = 75_000, cached = 60_000)),
                )
                // A smaller request after a larger one, as after the runtime trimmed its context: it replaces the report.
                notification(
                    tokenUsage(last = breakdown(input = 12_000, cached = 2_000), total = breakdown(input = 87_000, cached = 62_000)),
                )
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>()
        assertEquals(listOf(30_000L, 45_000L, 12_000L), occupancy.map { it.occupancyTokens }, "$events")
        occupancy.forEach { assertEquals(MODEL, it.model) }
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        occupancy.forEach { assertEquals(started.sessionId to started.turnId, it.sessionId to it.turnId) }
        // Spend still reads the totals: the last usage is the third total, 25,000 uncached and 62,000 cached.
        val usage = events.filterIsInstance<RunnerEvent.Usage>().last()
        assertEquals(25_000L to 62_000L, usage.inputTokens to usage.cacheReadInputTokens)
        // Each report follows the usage of its notification.
        val kinds = events.filter { it is RunnerEvent.Usage || it is RunnerEvent.ContextUsage }.map { it::class.simpleName }
        assertEquals(List(3) { listOf("Usage", "ContextUsage") }.flatten(), kinds)
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `m3-23 the runner declares occupancy`() {
        assertTrue(runner("codex app-server").capabilities.occupancy)
    }

    @Test
    fun `m3-23 a subagent thread's requests are not the session's occupancy, and a repeated report counts once`() {
        val main = breakdown(input = 10_000, cached = 0)
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(last = main, total = main))
                notification(tokenUsage(last = main, total = main))
                notification(
                    tokenUsage(
                        last = breakdown(input = 90_000, cached = 0),
                        total = breakdown(input = 90_000, cached = 0),
                        threadId = SUBAGENT_THREAD,
                        turnId = "sub-turn",
                    ),
                )
                turnCompleted()
            }

        val events = runAll(runner(server.command))

        assertEquals(listOf(10_000L), events.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens }, "$events")
        // The subagent's spend still counts.
        assertEquals(100_000L, events.filterIsInstance<RunnerEvent.Usage>().last().inputTokens)
    }

    @Test
    fun `m3-23 a continuation turn reports its own occupancy with its ids, on the same model`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(last = breakdown(input = 10_000, cached = 0), total = breakdown(input = 10_000, cached = 0)))
                turnCompleted()
                continuation(4, TURN_2)
                notification(
                    tokenUsage(
                        last = breakdown(input = 14_000, cached = 9_000),
                        total = breakdown(input = 24_000, cached = 9_000),
                        turnId = TURN_2,
                    ),
                )
                turnCompleted(TURN_2)
            }

        val (first, second) = runSession(runner(server.command), "Work on c-01", "Go on")

        assertEquals(listOf(10_000L), first.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens })
        val report = second.filterIsInstance<RunnerEvent.ContextUsage>().single()
        val started = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(RunnerEvent.ContextUsage(started.sessionId, started.turnId, 14_000, MODEL), report)
    }

    @Test
    fun `m3-23 a rerouted request reports the model it was rerouted to, and the next turn starts on the thread's model`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(rerouted(toModel = "gpt-5.1-codex-mini"))
                notification(tokenUsage(last = breakdown(input = 8_000, cached = 0), total = breakdown(input = 8_000, cached = 0)))
                turnCompleted()
                continuation(4, TURN_2)
                notification(
                    tokenUsage(last = breakdown(input = 9_000, cached = 0), total = breakdown(input = 17_000, cached = 0), turnId = TURN_2),
                )
                turnCompleted(TURN_2)
            }

        val (first, second) = runSession(runner(server.command), "Work on c-01", "Go on")

        assertEquals(listOf("openai/gpt-5.1-codex-mini"), first.filterIsInstance<RunnerEvent.ContextUsage>().map { it.model })
        assertEquals(listOf(MODEL), second.filterIsInstance<RunnerEvent.ContextUsage>().map { it.model })
    }

    @Test
    fun `m3-23 interrupt asks Codex to end the running turn once, and its usage after the interrupt is still reported`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(last = breakdown(input = 300_000, cached = 0), total = breakdown(input = 300_000, cached = 0)))
                // The interrupt request, then Codex's last usage of the turn and its end.
                awaitClient()
                response(4, JsonObject(emptyMap()))
                notification(tokenUsage(last = breakdown(input = 301_000, cached = 0), total = breakdown(input = 601_000, cached = 0)))
                turnCompleted(status = TurnStatus.INTERRUPTED)
            }
        val runner = runner(server.command)

        val events =
            onSession(runner) { session ->
                session
                    .runTurn("Work on c-01", 1)
                    .onEach { event ->
                        if (event is RunnerEvent.ContextUsage) {
                            session.interrupt()
                            session.interrupt()
                        }
                    }.collectAll()
            }

        val interrupts = received(server).filter { it["method"]?.jsonPrimitive?.content == "turn/interrupt" }
        assertEquals(1, interrupts.size, "${received(server)}")
        val request = interrupts.single()
        assertEquals(emptyList(), schema.problems("ClientRequest", request), "$request")
        val params = request.getValue("params").jsonObject
        assertEquals(FakeAppServer.THREAD_ID, params.getValue("threadId").jsonPrimitive.content)
        assertEquals(FakeAppServer.TURN_ID, params.getValue("turnId").jsonPrimitive.content)
        assertEquals("4", request.getValue("id").jsonPrimitive.content)
        assertEquals(601_000L, events.filterIsInstance<RunnerEvent.Usage>().last().inputTokens, "$events")
        val end = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertTrue(end.reason.startsWith("turn_cancelled"), end.reason)
    }

    @Test
    fun `m3-23 a late answer to an interrupt does not fail the next turn's start`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(tokenUsage(last = breakdown(input = 300_000, cached = 0), total = breakdown(input = 300_000, cached = 0)))
                awaitClient()
                turnCompleted(status = TurnStatus.INTERRUPTED)
                // The next turn/start, request 5, is answered after the interrupt's request 4.
                awaitClient()
                response(4, JsonObject(emptyMap()))
                response(5, FakeAppServer.turnStartResult(TURN_2))
                turnCompleted(TURN_2)
            }
        val runner = runner(server.command)

        val (first, second) =
            onSession(runner) { session ->
                val first =
                    session
                        .runTurn("Work on c-01", 1)
                        .onEach { if (it is RunnerEvent.ContextUsage) session.interrupt() }
                        .collectAll()
                first to session.runTurn("Go on", 2).collectAll()
            }

        assertIs<RunnerEvent.TurnFailed>(first.last())
        assertIs<RunnerEvent.TurnCompleted>(second.last(), "$second")
    }

    // Helpers.

    private fun tokenUsage(
        last: TokenUsageBreakdown,
        total: TokenUsageBreakdown,
        threadId: String = FakeAppServer.THREAD_ID,
        turnId: String = FakeAppServer.TURN_ID,
    ): ServerNotification =
        ServerNotification
            .ThreadTokenUsageUpdated(
                params = ThreadTokenUsageUpdatedNotification(threadId, ThreadTokenUsage(last = last, total = total), turnId),
            ).also(::checkSchema)

    private fun rerouted(toModel: String): ServerNotification =
        ServerNotification
            .ModelRerouted(
                params =
                    ModelReroutedNotification(
                        fromModel = "gpt-5.1-codex-max",
                        reason = ModelRerouteReason.HIGH_RISK_CYBER_ACTIVITY,
                        threadId = FakeAppServer.THREAD_ID,
                        toModel = toModel,
                        turnId = FakeAppServer.TURN_ID,
                    ),
            ).also(::checkSchema)

    private fun checkSchema(notification: ServerNotification) {
        val json = CodexJson.format.encodeToJsonElement(ServerNotification.serializer(), notification)
        assertEquals(emptyList(), schema.problems("ServerNotification", json), "$json")
    }

    /** A usage breakdown whose [input] holds its [cached] part, as the protocol writes it, with 100 output tokens. */
    private fun breakdown(
        input: Long,
        cached: Long,
    ) = TokenUsageBreakdown(
        cachedInputTokens = cached,
        inputTokens = input,
        outputTokens = 100,
        reasoningOutputTokens = 0,
        totalTokens = input + 100,
    )

    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

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
        onSession(runner) { session ->
            inputs.mapIndexed {
                index,
                input,
                ->
                session.runTurn(input, index + 1).collectAll()
            }
        }

    /** Runs [block] on a new session of [runner], stopped afterwards, within [TIMEOUT]. */
    private fun <T> onSession(
        runner: CodexAppServerRunner,
        block: suspend (AgentSession) -> T,
    ): T =
        CompletableFuture
            .supplyAsync {
                runBlocking {
                    val session = checkNotNull(runner.openSession(context()))
                    try {
                        block(session)
                    } finally {
                        session.stop()
                    }
                }
            }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private suspend fun Flow<RunnerEvent>.collectAll(): List<RunnerEvent> = toList()

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
        /** The `context.windows` key of the recorded `thread/start` answer: `modelProvider` `openai`, `model` `gpt-5.1-codex-max`. */
        const val MODEL = "openai/gpt-5.1-codex-max"
        const val TURN_2 = "019a0000-0000-7000-8000-000000000003"
        const val SUBAGENT_THREAD = "019a0000-0000-7000-8000-0000000000aa"
        val TIMEOUT = 30.seconds
    }
}
