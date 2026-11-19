package loop.runner.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCResponse
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.AccountUpdatedNotification
import loop.runner.codex.protocol.v2.AuthMode
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * m3-19, 17.5 items 6 and 7: the read timeout and the turn timeout of the Codex runner, on controlled time. The app
 * server is a [MemoryServer], a scripted server on the test's virtual clock, so an hour of silence takes no real time
 * and the time a turn failed at is exact. Killing the app server is the cancellation of its launch, which
 * [loop.runner.process.ProcessGroupLauncher] turns into a kill of the process group; the tests on a real process are in
 * [CodexAppServerRunnerTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexAppServerRunnerTimeoutTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `17_5 item 6 no thread start response within the read timeout kills the app server and fails the turn with response_timeout`() =
        runTest {
            val server =
                MemoryServer {
                    read()
                    respond(1, FakeAppServer.initializeResult())
                    read()
                    read()
                    awaitCancellation()
                }

            val events = runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).run(context(), PROMPT).toList()

            val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
            assertEquals("response_timeout: no thread/start response within 5000 ms", failed.reason)
            assertNull(failed.exitCode)
            assertEquals(1, events.count(::isTerminal), "$events")
            assertTrue(server.killed, "the app server is killed")
            assertEquals(5.seconds.inWholeMilliseconds, currentTime)
        }

    @Test
    fun `17_5 item 6 no answer to a continuation turn start within the read timeout fails that turn with response_timeout`() =
        runTest {
            val server =
                MemoryServer {
                    handshake()
                    notify(FakeAppServer.turnCompletedNotification())
                    read()
                    awaitCancellation()
                }
            val session = checkNotNull(runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).openSession(context()))

            val first = session.runTurn(PROMPT, 1).toList()
            val second = session.runTurn("Continue", 2).toList()
            session.stop()

            assertIs<RunnerEvent.TurnCompleted>(first.last())
            // Codex named no turn, so the turn id is Loop's own, on the thread of turn 1.
            val started = assertIs<RunnerEvent.TurnStarted>(second.first())
            assertEquals(FakeAppServer.THREAD_ID to 2, started.threadId to started.turn)
            val failed = assertIs<RunnerEvent.TurnFailed>(second.last())
            assertEquals("response_timeout: no turn/start response within 5000 ms", failed.reason)
            assertEquals(started.sessionId to started.turnId, failed.sessionId to failed.turnId)
            assertTrue(server.killed, "the app server is killed")
            assertEquals(5.seconds.inWholeMilliseconds, currentTime)
        }

    @Test
    fun `17_5 item 7 a turn silent for the turn timeout fails with turn_timeout and a kill, each protocol message restarting it`() =
        runTest {
            val server =
                MemoryServer {
                    handshake()
                    delay(30.minutes)
                    notify(accountUpdated())
                    // Stderr is diagnostics, not the protocol stream: it does not restart the silence.
                    delay(50.minutes)
                    stderr("codex: still thinking")
                    awaitCancellation()
                }

            val events = runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).run(context(), PROMPT).toList()

            val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
            assertEquals("turn_timeout: no protocol message for 3600000 ms", failed.reason)
            assertNull(failed.exitCode)
            assertEquals(SessionId("${FakeAppServer.THREAD_ID}-${FakeAppServer.TURN_ID}"), failed.sessionId)
            assertEquals(1, events.count(::isTerminal), "$events")
            assertTrue(RunnerEvent.Output(Stream.Stderr, "codex: still thinking") in events, "$events")
            assertTrue(server.killed, "the app server is killed")
            assertEquals((30.minutes + 1.hours).inWholeMilliseconds, currentTime)
        }

    @Test
    fun `17_5 item 7 a silent continuation turn fails with turn_timeout`() =
        runTest {
            val server =
                MemoryServer {
                    handshake()
                    notify(FakeAppServer.turnCompletedNotification())
                    read()
                    respond(4, FakeAppServer.turnStartResult("turn-2"))
                    awaitCancellation()
                }
            val session = checkNotNull(runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).openSession(context()))

            session.runTurn(PROMPT, 1).toList()
            val second = session.runTurn("Continue", 2).toList()
            session.stop()

            assertEquals(TurnId("turn-2"), assertIs<RunnerEvent.TurnStarted>(second.first()).turnId)
            assertEquals("turn_timeout: no protocol message for 3600000 ms", assertIs<RunnerEvent.TurnFailed>(second.last()).reason)
            assertTrue(server.killed, "the app server is killed")
            assertEquals(1.hours.inWholeMilliseconds, currentTime)
        }

    @Test
    fun `an app server idle between turns is left alone past both timeouts`() =
        runTest {
            val server =
                MemoryServer {
                    handshake()
                    notify(FakeAppServer.turnCompletedNotification())
                    read()
                    respond(4, FakeAppServer.turnStartResult("turn-2"))
                    notify(FakeAppServer.turnCompletedNotification(turnId = "turn-2"))
                }
            val session = checkNotNull(runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).openSession(context()))

            session.runTurn(PROMPT, 1).toList()
            delay(3.hours)
            val second = session.runTurn("Continue", 2).toList()
            session.stop()

            assertEquals(RunnerEvent.TurnCompleted(SessionId("${FakeAppServer.THREAD_ID}-turn-2"), TurnId("turn-2"), 0), second.last())
            assertEquals(1, server.launches)
        }

    @Test
    fun `17_5 item 12 m3-20 a user input request ends the turn at once with input_required as its only terminal event`() =
        runTest {
            val server =
                MemoryServer {
                    handshake()
                    delay(2.minutes)
                    raw(
                        FakeAppServer.serverRequestLine(
                            7,
                            "item/tool/requestUserInput",
                            CodexJson.format
                                .parseToJsonElement(
                                    """{"itemId":"item-1","threadId":"${FakeAppServer.THREAD_ID}","turnId":"${FakeAppServer.TURN_ID}",""" +
                                        """"isBlocking":true,"questions":[{"id":"q1","header":"Branch","question":"Which branch?"}]}""",
                                ).jsonObject,
                        ),
                    )
                    // A client that waited for an answer would stall here until the turn timeout.
                    awaitCancellation()
                }

            val events = runner(server, readTimeout = 5.seconds, turnTimeout = 1.hours).run(context(), PROMPT).toList()

            val ended = assertIs<RunnerEvent.TurnInputRequired>(events.last(), "$events")
            assertEquals("item/tool/requestUserInput", ended.reason)
            assertEquals(SessionId("${FakeAppServer.THREAD_ID}-${FakeAppServer.TURN_ID}"), ended.sessionId)
            assertEquals(1, events.count(::isTerminal), "$events")
            assertTrue(server.killed, "the app server is stopped, the request never answered")
            // The turn ended when the request arrived, two minutes in, not at the turn timeout an hour later.
            assertEquals(2.minutes.inWholeMilliseconds, currentTime)
        }

    private fun TestScope.runner(
        server: MemoryServer,
        readTimeout: Duration,
        turnTimeout: Duration,
    ): CodexAppServerRunner =
        CodexAppServerRunner(
            // Strict MCP (m3-20) adds config/read to the startup, which the timeout cases leave out.
            CodexAppServerSettings("codex app-server", turnTimeout = turnTimeout, readTimeout = readTimeout, strictMcp = false),
            hostEnv = emptyMap(),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = server::launch,
            loopVersion = "test-build",
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        )

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

    private fun accountUpdated(): ServerNotification =
        ServerNotification.AccountUpdated(params = AccountUpdatedNotification(authMode = AuthMode.CHATGPT))

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

    /**
     * An app server in memory: [script] reads the client's lines and writes the server's, on the clock of the
     * collector, so in [runTest] its delays are virtual. After the script it reads stdin until it closes, then exits 0,
     * as a real server waits for its client. A cancelled launch is a killed server, [killed].
     */
    private class MemoryServer(
        private val script: suspend Io.() -> Unit,
    ) {
        @Volatile var killed = false

        @Volatile var launches = 0

        fun launch(spec: LaunchSpec): Flow<ProcessEvent> =
            flow {
                launches++
                emit(ProcessEvent.Started(PID))
                try {
                    val io = Io(this, checkNotNull(spec.stdinLines))
                    io.script()
                    while (true) io.read() ?: break
                    emit(ProcessEvent.Exited(0))
                } catch (e: CancellationException) {
                    killed = true
                    throw e
                }
            }

        /** What the script can do: read a client line, write a message on stdout or a line on stderr. */
        class Io(
            private val out: FlowCollector<ProcessEvent>,
            private val stdin: ReceiveChannel<String>,
        ) {
            /** The next client message, null once stdin closed. */
            suspend fun read(): JsonObject? =
                stdin.receiveCatching().getOrNull()?.let { CodexJson.format.parseToJsonElement(it.trim()).jsonObject }

            suspend fun respond(
                id: Long,
                result: JsonElement,
            ) = stdout(CodexJson.format.encodeToString(JSONRPCResponse.serializer(), JSONRPCResponse(JsonPrimitive(id), result)))

            suspend fun notify(notification: ServerNotification) =
                stdout(CodexJson.format.encodeToString(ServerNotification.serializer(), notification))

            suspend fun stderr(line: String) = out.emit(ProcessEvent.Line(Stream.Stderr, line))

            /** Answers the startup as [FakeAppServer.Script.handshake] does, with its default ids. */
            suspend fun handshake() {
                check(read()?.get("method")?.jsonPrimitive?.content == "initialize")
                respond(1, FakeAppServer.initializeResult())
                read()
                read()
                respond(2, FakeAppServer.threadStartResult(FakeAppServer.THREAD_ID))
                read()
                respond(3, FakeAppServer.turnStartResult(FakeAppServer.TURN_ID))
            }

            /** Writes [line] on stdout as it is. */
            suspend fun raw(line: String) = stdout(line)

            private suspend fun stdout(line: String) = out.emit(ProcessEvent.Line(Stream.Stdout, line))
        }

        private companion object {
            const val PID = 4242L
        }
    }

    private companion object {
        const val PROMPT = "Work on c-01"
    }
}
