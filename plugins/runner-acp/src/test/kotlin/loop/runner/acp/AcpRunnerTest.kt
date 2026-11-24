package loop.runner.acp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AcpRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val frames: MutableList<AcpFrame> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `declares ACP runner capabilities`() {
        val runner = AcpRunner(AcpSettings(command = "my-agent", strictMcp = false))

        assertEquals(
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Mcp,
                usage = true,
                cost = false,
                occupancy = true,
                sandbox = false,
            ),
            runner.capabilities,
        )
    }

    @Test
    fun `agy preset performs initialize, session new, session set_model, and session prompt in order and escapes leading slash`() {
        val server =
            FakeAcpServer.write(dir) {
                agyHandshake(sessionId = "agy-sess-1", modelId = "gemini-4-neon-high")
            }

        val events = runAll(agyRunner(server), prompt = "/tdd implement feature")

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("agy-sess-1", started.runtimeSessionId)
        assertEquals("${started.threadId}-${started.turnId.value}", started.sessionId.value)
        val completed = assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertEquals(0, completed.exitCode)
        assertTrue(
            RunnerEvent.Output(Stream.Stderr, "loop: agy ACP server version ${AcpSettings.PINNED_AGY_VERSION}") in events,
            "$events",
        )

        val sent = received(server)
        assertEquals(listOf("initialize", "session/new", "session/set_model", "session/prompt"), sent.map { it.string("method") })
        assertEquals(listOf("1", "2", "3", "4"), sent.map { it["id"]!!.jsonPrimitive.content })

        val initParams = sent[0]["params"]!!.jsonObject
        assertEquals(1, initParams["protocolVersion"]!!.jsonPrimitive.int)
        assertEquals("loop", initParams["clientInfo"]!!.jsonObject.string("name"))
        assertEquals("test-version", initParams["clientInfo"]!!.jsonObject.string("version"))
        val caps = initParams["clientCapabilities"]!!.jsonObject
        assertFalse(caps["fs"]!!.jsonObject["readTextFile"]!!.jsonPrimitive.boolean)
        assertFalse(caps["fs"]!!.jsonObject["writeTextFile"]!!.jsonPrimitive.boolean)
        assertFalse(caps["terminal"]!!.jsonPrimitive.boolean)

        val newParams = sent[1]["params"]!!.jsonObject
        assertEquals(workspace.toAbsolutePath().toString(), newParams.string("cwd"))

        val setModelParams = sent[2]["params"]!!.jsonObject
        assertEquals("agy-sess-1", setModelParams.string("sessionId"))
        assertEquals("gemini-4-neon-high", setModelParams.string("modelId"))

        val promptParams = sent[3]["params"]!!.jsonObject
        assertEquals("agy-sess-1", promptParams.string("sessionId"))
        assertEquals(" /tdd implement feature", promptParams["prompt"]!!.jsonArray[0].jsonObject.string("text"))
    }

    @Test
    fun `generic ACP skips session set_model and does not escape leading slash`() {
        val server =
            FakeAcpServer.write(dir) {
                genericHandshake(sessionId = "gen-sess-1")
            }

        val events = runAll(genericRunner(server), prompt = "/status check")

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val sent = received(server)
        assertEquals(listOf("initialize", "session/new", "session/prompt"), sent.map { it.string("method") })
        val promptText =
            sent[2]["params"]!!
                .jsonObject["prompt"]!!
                .jsonArray[0]
                .jsonObject
                .string("text")
        assertEquals("/status check", promptText)
    }

    @Test
    fun `agy preset strips Google API keys from host env by default and allows opt-in through agent env`() {
        val report = dir.resolve("env.txt")
        val server =
            FakeAcpServer.write(dir) {
                shell(
                    "printf '%s\\n' \"\${GEMINI_API_KEY-unset}\" \"\${GOOGLE_API_KEY-unset}\" " +
                        "\"\${GOOGLE_APPLICATION_CREDENTIALS-unset}\" \"\${GOOGLE_GENAI_USE_VERTEXAI-unset}\" " +
                        "\"\${KEPT-unset}\" > '$report'",
                )
                agyHandshake()
            }
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = FakeAcpServer.DEFAULT_MODEL,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv =
                    hostEnv() +
                        mapOf(
                            "GEMINI_API_KEY" to "AIzaHostKey",
                            "GOOGLE_API_KEY" to "AIzaOtherHostKey",
                            "GOOGLE_APPLICATION_CREDENTIALS" to "/tmp/creds.json",
                            "GOOGLE_GENAI_USE_VERTEXAI" to "true",
                            "KEPT" to "yes",
                        ),
                removedEnvNames = emptySet(),
                addedEnv = mapOf("GEMINI_API_KEY" to "opt-in-key"),
            )

        val events = runAll(runner)

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertEquals(listOf("opt-in-key", "unset", "unset", "unset", "yes"), report.readLines())
    }

    @Test
    fun `agy preset enforces pinned major version 1 on initialize`() {
        val minorBump =
            FakeAcpServer.write(dir, name = "agy") {
                initialize(agentVersion = "1.9.0")
                sessionNew()
                sessionSetModel(configUpdateModelId = FakeAcpServer.DEFAULT_MODEL)
                promptTurn(id = 4)
            }
        assertIs<RunnerEvent.TurnCompleted>(runAll(agyRunner(minorBump)).last())

        val wrongMajor =
            FakeAcpServer.write(dir.resolve("major"), name = "agy") {
                initialize(agentVersion = "2.0.0")
                shell("sleep 30")
            }
        val wrongMajorEvents = runAll(agyRunner(wrongMajor))
        val wrongMajorFailed = assertIs<RunnerEvent.TurnFailed>(wrongMajorEvents.last())
        assertEquals(
            "response_error: agy 2.0.0 is not of the pinned major version 1 (pinned ${AcpSettings.PINNED_AGY_VERSION})",
            wrongMajorFailed.reason,
        )
        assertNull(wrongMajorFailed.exitCode)
        assertEquals(listOf("initialize"), received(wrongMajor).map { it.string("method") })

        val missingVersion =
            FakeAcpServer.write(dir.resolve("missing"), name = "agy") {
                initialize(agentVersion = null)
                shell("sleep 30")
            }
        val missingFailed = assertIs<RunnerEvent.TurnFailed>(runAll(agyRunner(missingVersion)).last())
        assertEquals("response_error: the initialize response names no agy version in agentInfo.version", missingFailed.reason)
    }

    @Test
    fun `session set_model error or model mismatch fails the turn and stops the server`() {
        val rejected =
            FakeAcpServer.write(dir.resolve("rej"), name = "agy") {
                initialize()
                sessionNew()
                awaitClient()
                error(id = 3, code = -32602, message = "Unknown model ID")
                shell("sleep 30")
            }
        val rejEvents = runAll(agyRunner(rejected))
        val rejFailed = assertIs<RunnerEvent.TurnFailed>(rejEvents.last())
        assertEquals("response_error: session/set_model failed with code -32602: Unknown model ID", rejFailed.reason)

        val mismatched =
            FakeAcpServer.write(dir.resolve("mis"), name = "agy") {
                initialize()
                sessionNew()
                sessionSetModel(id = 3, configUpdateModelId = "gemini-3.8-flash-low")
                shell("sleep 30")
            }
        val misEvents = runAll(agyRunner(mismatched))
        val misFailed = assertIs<RunnerEvent.TurnFailed>(misEvents.last())
        assertEquals(
            "response_error: session/set_model selected gemini-3.8-flash-low instead of gemini-4-neon-high",
            misFailed.reason,
        )
    }

    @Test
    fun `continuation turns reuse the same ACP process and sessionId`() {
        val server =
            FakeAcpServer.write(dir) {
                agyHandshake(sessionId = "agy-sess-multi", stopReason = "end_turn")
                promptTurn(id = 5, stopReason = "end_turn")
            }
        val runner = agyRunner(server)

        runBlocking {
            withTimeout(20.seconds) {
                val session = runner.openSession(context())
                try {
                    val turn1 = session.runTurn("First turn", 1).toList()
                    val turn2 = session.runTurn("/second turn", 2).toList()

                    val started1 = assertIs<RunnerEvent.SessionStarted>(turn1.first())
                    assertEquals("agy-sess-multi", started1.runtimeSessionId)
                    assertIs<RunnerEvent.TurnCompleted>(turn1.last())

                    val started2 = assertIs<RunnerEvent.TurnStarted>(turn2.first())
                    assertEquals(started1.threadId, started2.threadId)
                    assertEquals(2, started2.turn)
                    assertIs<RunnerEvent.TurnCompleted>(turn2.last())
                } finally {
                    session.stop()
                }
            }
        }

        val sent = received(server)
        assertEquals(
            listOf("initialize", "session/new", "session/set_model", "session/prompt", "session/prompt"),
            sent.map { it.string("method") },
        )
        assertEquals("agy-sess-multi", sent[4]["params"]!!.jsonObject.string("sessionId"))
        assertEquals(
            " /second turn",
            sent[4]["params"]!!
                .jsonObject["prompt"]!!
                .jsonArray[0]
                .jsonObject
                .string("text"),
        )
    }

    @Test
    fun `resumeSession sends session load followed by session set_model and session prompt`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionLoad(id = 2, sessionId = "prev-acp-session")
                sessionSetModel(id = 3, configUpdateModelId = "gemini-4-neon-high", sessionId = "prev-acp-session")
                promptTurn(id = 4, stopReason = "end_turn")
            }
        val runner = agyRunner(server)

        val events =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = runner.resumeSession(context(), "prev-acp-session")
                    try {
                        session.runTurn("Continue work", 1).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("prev-acp-session", started.runtimeSessionId)
        assertIs<RunnerEvent.TurnCompleted>(events.last())

        val sent = received(server)
        assertEquals(listOf("initialize", "session/load", "session/set_model", "session/prompt"), sent.map { it.string("method") })
        assertEquals("prev-acp-session", sent[1]["params"]!!.jsonObject.string("sessionId"))
        assertEquals(workspace.toAbsolutePath().toString(), sent[1]["params"]!!.jsonObject.string("cwd"))
    }

    @Test
    fun `malformed stdout lines are logged and skipped, split lines decode whole, and stderr stays diagnostics`() {
        val server =
            FakeAcpServer.write(dir) {
                stderr("agy: booting")
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-stream")
                awaitClient()
                raw("""{"jsonrpc":"2.0","method": """)
                stderr("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
                notification(
                    AcpHandshake.METHOD_SESSION_UPDATE,
                    buildJsonObject { put("sessionId", "s-stream") },
                    split = true,
                )
                response(3, buildJsonObject { put("stopReason", "end_turn") })
            }

        val events = runAll(genericRunner(server))

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertTrue(
            RunnerEvent.Output(Stream.Stderr, "loop: an ACP server stdout line is not a protocol message (not JSON), skipped") in events,
            "$events",
        )
        assertTrue(RunnerEvent.Output(Stream.Stderr, "agy: booting") in events, "$events")
        assertTrue(frames.any { it is AcpFrame.Notification && it.method == AcpHandshake.METHOD_SESSION_UPDATE }, "$frames")
    }

    @Test
    fun `fails cleanly on invalid workspace cwd, missing binary, out-of-order response, and server exit`() {
        val missingWorkspace = dir.resolve("does-not-exist")
        val runner = AcpRunner(AcpSettings(command = "my-agent", strictMcp = false), hostEnv = hostEnv())
        val badCwdEvents = runAll(runner, cwd = missingWorkspace)
        val badCwdFailed = assertIs<RunnerEvent.TurnFailed>(badCwdEvents.last())
        assertEquals("invalid_workspace_cwd: $missingWorkspace", badCwdFailed.reason)

        val missingBinary = AcpRunner(AcpSettings(command = "/no/such/acp-binary", strictMcp = false), hostEnv = hostEnv())
        val notFoundEvents = runAll(missingBinary)
        val notFoundFailed = assertIs<RunnerEvent.TurnFailed>(notFoundEvents.last())
        assertTrue(notFoundFailed.reason.startsWith("agent_not_found:"), notFoundFailed.reason)

        val outOfOrder =
            FakeAcpServer.write(dir.resolve("ooo")) {
                awaitClient()
                response(id = 99, result = buildJsonObject { put("protocolVersion", 1) })
                shell("sleep 30")
            }
        val oooFailed = assertIs<RunnerEvent.TurnFailed>(runAll(genericRunner(outOfOrder)).last())
        assertEquals("response_error: an answer to request 99 arrived while waiting for the initialize response", oooFailed.reason)

        val crash =
            FakeAcpServer.write(dir.resolve("crash")) {
                awaitClient()
                exit(3)
            }
        val crashFailed = assertIs<RunnerEvent.TurnFailed>(runAll(genericRunner(crash)).last())
        assertEquals("port_exit: exit code 3, before the initialize response", crashFailed.reason)
        assertEquals(3, crashFailed.exitCode)
    }

    @Test
    fun `real process hanging past stopGrace emits StopGraceExpired through stop(report)`() {
        val server =
            FakeAcpServer.write(dir) {
                genericHandshake(sessionId = "s-hang", stopReason = "end_turn")
                // Ignore stdin EOF and stay alive past stopGrace.
                shell("sleep 30")
            }
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.command,
                        strictMcp = false,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = hostEnv(),
                removedEnvNames = emptySet(),
                addedEnv = emptyMap(),
                launchProcess = loop.runner.process.ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)::launch,
                stopGrace = 250.milliseconds,
            )

        val stopEvents = mutableListOf<RunnerEvent>()
        val turn1 =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = runner.openSession(context())
                    val events = session.runTurn("Work", 1).toList()
                    session.stop { stopEvents += it }
                    events
                }
            }

        val started = assertIs<RunnerEvent.SessionStarted>(turn1.first())
        val expired = assertIs<RunnerEvent.StopGraceExpired>(stopEvents.single())
        assertEquals(started.sessionId, expired.sessionId)
        assertEquals(started.turnId, expired.turnId)
        assertEquals(250L, expired.graceMs)
        assertFalse(expired.wake)
        assertEquals(0, expired.wakeMessages)
    }

    private fun agyRunner(
        server: FakeAcpServer,
        readTimeout: Duration = FakeAcpServer.READ_TIMEOUT,
    ): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = server.agyCommand,
                    preset = AcpSettings.PRESET_AGY,
                    model = FakeAcpServer.DEFAULT_MODEL,
                    readTimeout = readTimeout,
                ),
            hostEnv = hostEnv(),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = loop.runner.process.ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)::launch,
            onFrame = { frames += it },
            loopVersion = "test-version",
        )

    private fun genericRunner(
        server: FakeAcpServer,
        readTimeout: Duration = FakeAcpServer.READ_TIMEOUT,
    ): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = server.command,
                    strictMcp = false,
                    readTimeout = readTimeout,
                ),
            hostEnv = hostEnv(),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = loop.runner.process.ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)::launch,
            onFrame = { frames += it },
            loopVersion = "test-version",
        )

    private fun hostEnv(): Map<String, String> =
        mapOf(
            "PATH" to System.getenv("PATH"),
            "HOME" to home.toString(),
        )

    private fun runAll(
        runner: AcpRunner,
        prompt: String = "Work on issue",
        cwd: Path = workspace,
    ): List<RunnerEvent> = runBlocking { withTimeout(20.seconds) { runner.run(context(cwd), prompt).toList() } }

    private fun received(server: FakeAcpServer): List<JsonObject> =
        server.received
            .readLines()
            .filter { it.isNotBlank() }
            .map { AcpFraming.json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun context(cwd: Path = workspace): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-01",
                    title = "ACP test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = cwd,
            env = emptyMap(),
        )
}
