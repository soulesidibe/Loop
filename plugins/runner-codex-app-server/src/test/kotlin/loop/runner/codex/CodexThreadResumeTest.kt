package loop.runner.codex

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.api.AgentSession
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
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.ThreadTokenUsage
import loop.runner.codex.protocol.v2.ThreadTokenUsageUpdatedNotification
import loop.runner.codex.protocol.v2.TokenUsageBreakdown
import loop.runner.codex.schema.PinnedSchema
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * bl-212: the Codex runner resumes its thread with `thread/resume` on a new app server, when a continuation turn finds
 * the app server of its session gone (as the Claude Code runner respawns with `--resume`, m3-07), and at turn 1 of a
 * session [CodexAppServerRunner.resumeSession] opens after a network hold (m4-46). The resume carries the settings of
 * `thread/start`, a failed resume fails the turn and never starts a new empty thread, and a resumed session is not
 * charged again the usage of the attempt it resumes. Every server is a [FakeAppServer], one per launch.
 */
class CodexThreadResumeTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val schema = PinnedSchema.load()

    /** The specs the runner launched, in order. */
    private val launches: MutableList<LaunchSpec> = Collections.synchronizedList(mutableListOf())

    /** Pid files of fakes, whose processes must be gone after each test. */
    private val pidFiles = mutableListOf<Path>()

    @AfterEach
    fun `no app server outlives its test`() {
        val alive = pidFiles.mapNotNull(::pid).filter(::isAlive)
        alive.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        assertTrue(alive.isEmpty(), "app servers still alive after the test, now killed: $alive")
    }

    @Test
    fun `bl-212 a continuation turn whose app server exited between turns starts a new one and resumes the thread`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                exit(0)
            }
        val second =
            server("b") {
                resumeHandshake(threadId = THREAD, turnId = "turn-2")
                turnCompleted(turnId = "turn-2", threadId = THREAD)
            }

        val (one, two) = runSession(runner(first, second), "Work on c-01", "Continue with c-01")

        assertEquals(RunnerEvent.SessionStarted(SessionId("$THREAD-turn-1"), THREAD, TurnId("turn-1"), THREAD), one.first())
        assertIs<RunnerEvent.TurnCompleted>(one.last())
        assertEquals(RunnerEvent.TurnStarted(SessionId("$THREAD-turn-2"), THREAD, TurnId("turn-2"), 2), two.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("$THREAD-turn-2"), TurnId("turn-2"), 0), two.last())
        assertEquals(1, two.count(::isTerminal), "$two")
        assertTrue(
            "loop: the app server of this session is gone, turn 2 starts a new one and resumes the thread $THREAD" in loopLines(two),
            "${loopLines(two)}",
        )
        assertEquals(2, launches.size)
        val sent = received(second)
        assertEquals(listOf("initialize", "initialized", "thread/resume", "turn/start"), sent.map { it.string("method") })
        assertEquals(listOf("1", "2", "3"), sent.mapNotNull { it.string("id") }, "a new app server counts its request ids from 1")
        val resume = sent[2].obj("params")
        assertEquals(THREAD, resume.string("threadId"))
        assertEquals("untrusted", resume.string("approvalPolicy"))
        assertEquals("danger-full-access", resume.string("sandbox"))
        assertEquals(workspace.toAbsolutePath().toString(), resume.string("cwd"))
        assertEquals(buildJsonObject { put("allow_login_shell", false) }, resume.obj("config"))
        assertEquals("true", resume.string("excludeTurns"))
        val turn = sent[3].obj("params")
        assertEquals(THREAD, turn.string("threadId"))
        assertEquals(CodexJson.format.parseToJsonElement("""[{"type":"text","text":"Continue with c-01"}]"""), turn["input"])
        sent.filter { it["id"] != null }.forEach { assertEquals(emptyList(), schema.problems("ClientRequest", it), "$it") }
        assertFalse(sent.any { it.string("method") == "thread/start" }, "a resume never starts a new thread")
    }

    @Test
    fun `bl-212 after an exit mid turn fails that turn, the next turn resumes the thread on a new app server`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                continuation(id = 4, turnId = "turn-2")
                exit(4)
            }
        val second =
            server("b") {
                resumeHandshake(threadId = THREAD, turnId = "turn-3")
                turnCompleted(turnId = "turn-3", threadId = THREAD)
            }

        val (_, two, three) = runSession(runner(first, second), "Work on c-01", "Continue", "Continue again")

        assertEquals("port_exit: exit code 4, no turn result", assertIs<RunnerEvent.TurnFailed>(two.last()).reason)
        assertEquals(RunnerEvent.TurnStarted(SessionId("$THREAD-turn-3"), THREAD, TurnId("turn-3"), 3), three.first())
        assertIs<RunnerEvent.TurnCompleted>(three.last())
        assertEquals(2, launches.size, "an exit after the turn started fails it, the next turn starts the new app server")
        assertEquals(listOf("initialize", "initialized", "thread/resume", "turn/start"), received(second).map { it.string("method") })
    }

    @Test
    fun `bl-212 a session whose thread never started fails a later turn at once with port_exit and starts no app server`() {
        val first =
            server("a") {
                awaitClient()
                error(1, -32603, "not ready")
            }

        val (one, two) = runSession(runner(first), "Work on c-01", "Continue")

        assertEquals("response_error: initialize failed with code -32603: not ready", assertIs<RunnerEvent.TurnFailed>(one.last()).reason)
        assertNull(assertIs<RunnerEvent.SessionStarted>(one.first()).runtimeSessionId, "no Codex thread to resume")
        val gone = assertIs<RunnerEvent.TurnFailed>(two.last())
        assertTrue(gone.reason.startsWith("port_exit: the app server of this session is gone, turn 2 cannot start on thread "), gone.reason)
        assertEquals(1, launches.size)
    }

    @Test
    fun `bl-212 resumeSession resumes the held attempt's thread at turn 1 on a new app server, with the model of runners codex`() {
        val server =
            server("a") {
                resumeHandshake(threadId = THREAD, turnId = "turn-9")
                turnCompleted(turnId = "turn-9", threadId = THREAD)
            }
        val runner = runner(server, model = "gpt-6.1-sol")

        val session = assertNotNull(runner.resumeSession(context(), THREAD))
        val (events) = runTurns(session, "Work on c-01 again")

        assertEquals(RunnerEvent.SessionStarted(SessionId("$THREAD-turn-9"), THREAD, TurnId("turn-9"), THREAD), events.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("$THREAD-turn-9"), TurnId("turn-9"), 0), events.last())
        assertTrue(
            "loop: turn 1 resumes the thread $THREAD of an earlier attempt on a new app server" in loopLines(events),
            "${loopLines(events)}",
        )
        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "thread/resume", "turn/start"), sent.map { it.string("method") })
        val resume = sent[2].obj("params")
        assertEquals(THREAD, resume.string("threadId"))
        assertEquals("gpt-6.1-sol", resume.string("model"))
        assertEquals("untrusted", resume.string("approvalPolicy"))
        assertEquals("danger-full-access", resume.string("sandbox"))
        assertEquals(
            CodexJson.format.parseToJsonElement("""[{"type":"text","text":"Work on c-01 again"}]"""),
            sent[3].obj("params")["input"],
        )
        sent.filter { it["id"] != null }.forEach { assertEquals(emptyList(), schema.problems("ClientRequest", it), "$it") }
    }

    @Test
    fun `bl-212 under strict MCP the resume disables each configured MCP server, turns apps off and declares Loop's tools`() {
        val loop = FakeLoopTool.write(dir)
        val server =
            server("a") {
                resumeHandshake(threadId = THREAD, turnId = "turn-9", config = FakeAppServer.configReadResult())
                turnCompleted(turnId = "turn-9", threadId = THREAD)
            }
        val runner = runner(server, strictMcp = true)

        val session = assertNotNull(runner.resumeSession(context(attemptEnv(loop.launcher)), THREAD))
        val (events) = runTurns(session, "Work on c-01 again")

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "config/read", "thread/resume", "turn/start"), sent.map { it.string("method") })
        assertEquals("true", sent[0].obj("params").obj("capabilities").string("experimentalApi"))
        val resume = sent[3]
        assertEquals(
            buildJsonObject {
                put("allow_login_shell", false)
                putJsonObject("mcp_servers") { putJsonObject("operator_server") { put("enabled", false) } }
                putJsonObject("features") { put("apps", false) }
            },
            resume.obj("params").obj("config"),
        )
        val tools =
            resume
                .obj("params")
                .getValue("dynamicTools")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(listOf("submit_result", "move", "comment", "ask", "file_followup"), tools.map { it.string("name") })
        tools.forEach { assertEquals(emptyList(), schema.problems("v2/DynamicToolSpec", it), "$it") }
        val stable = JsonObject(resume + ("params" to JsonObject(resume.obj("params") - "dynamicTools")))
        assertEquals(emptyList(), schema.problems("ClientRequest", stable))
    }

    @Test
    fun `bl-212 a thread resume answered with an error fails the turn with response_error, stops the app server and starts no thread`() {
        val pidFile = dir.resolve("resume-error.pid").also { pidFiles.add(it) }
        val server =
            server("a") {
                shell("echo \$\$ > '$pidFile'")
                awaitClient()
                response(1, FakeAppServer.initializeResult())
                awaitClient()
                awaitClient()
                error(2, -32600, "no rollout found for thread id $THREAD")
                shell("sleep 30")
            }

        val session = assertNotNull(runner(server).resumeSession(context(), THREAD))
        val (events) = runTurns(session, "Work on c-01 again")

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertNull(started.runtimeSessionId)
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: thread/resume failed with code -32600: no rollout found for thread id $THREAD", failed.reason)
        assertEquals(1, events.count(::isTerminal), "$events")
        assertTrue(awaitDead(checkNotNull(pid(pidFile))), "the app server is stopped")
        val methods = received(server).map { it.string("method") }
        assertEquals(listOf("initialize", "initialized", "thread/resume"), methods)
        assertEquals(1, launches.size)
    }

    @Test
    fun `bl-212 a respawn whose thread resume answer is not the pinned type or names another thread fails the turn`() {
        val cases =
            listOf(
                CodexJson.format.parseToJsonElement("""{"thread":"not a thread"}""") to
                    "response_error: the thread/resume response does not match the pinned schema",
                FakeAppServer.threadResumeResult("thread-b") to
                    "response_error: the thread/resume response names the thread thread-b, not $THREAD",
            )
        cases.forEachIndexed { i, (answer, reason) ->
            launches.clear()
            val first =
                server("a-$i") {
                    handshake(threadId = THREAD, turnId = "turn-1")
                    turnCompleted(turnId = "turn-1", threadId = THREAD)
                    exit(0)
                }
            val second =
                server("b-$i") {
                    awaitClient()
                    response(1, FakeAppServer.initializeResult())
                    awaitClient()
                    awaitClient()
                    response(2, answer)
                }

            val (_, two) = runSession(runner(first, second), "Work on c-01", "Continue")

            assertIs<RunnerEvent.TurnStarted>(two.first())
            assertEquals(reason, assertIs<RunnerEvent.TurnFailed>(two.last()).reason)
            assertEquals(listOf("initialize", "initialized", "thread/resume"), received(second).map { it.string("method") })
            assertEquals(2, launches.size)
        }
    }

    @Test
    fun `bl-212 a resumed session leaves out the earlier attempt's usage that Codex replays on its thread`() {
        val server =
            server("a") {
                resumeHandshake(threadId = THREAD, turnId = "turn-9")
                // What codex 0.167.0 does: right after the resume it replays the thread's total, of the held attempt's turn.
                notification(tokenUsage(turnId = "turn-of-the-held-attempt", total = tokens(input = 18_000, output = 100)))
                notification(tokenUsage(turnId = "turn-9", total = tokens(input = 36_000, output = 150)))
                turnCompleted(turnId = "turn-9", threadId = THREAD)
            }

        val session = assertNotNull(runner(server).resumeSession(context(), THREAD))
        val (events) = runTurns(session, "Work on c-01 again")

        val usage = events.filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(18_000L to 50L), usage.map { it.inputTokens to it.outputTokens }, "$events")
    }

    @Test
    fun `bl-212 a respawn keeps the session's usage, the total Codex replays on the resumed thread counts once`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                notification(tokenUsage(turnId = "turn-1", total = tokens(input = 18_000, output = 100)))
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                exit(0)
            }
        val second =
            server("b") {
                resumeHandshake(threadId = THREAD, turnId = "turn-2")
                notification(tokenUsage(turnId = "turn-1", total = tokens(input = 18_000, output = 100)))
                notification(tokenUsage(turnId = "turn-2", total = tokens(input = 36_000, output = 150)))
                turnCompleted(turnId = "turn-2", threadId = THREAD)
            }

        val (one, two) = runSession(runner(first, second), "Work on c-01", "Continue")

        val usage = (one + two).filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(18_000L to 100L, 36_000L to 150L), usage.map { it.inputTokens to it.outputTokens })
    }

    @Test
    fun `bl-232 after a started turn, a replay of a turn Loop never learned is an update, so the session's usage never drops`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                notification(tokenUsage(turnId = "turn-1", total = tokens(input = 18_000, output = 100)))
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                // Codex persisted this turn/start and spent tokens on it, then died before it answered.
                awaitClient()
                exit(0)
            }
        val second =
            server("b") {
                resumeHandshake(threadId = THREAD, turnId = "turn-3")
                notification(tokenUsage(turnId = "turn-2", total = tokens(input = 30_000, output = 120)))
                notification(tokenUsage(turnId = "turn-3", total = tokens(input = 40_000, output = 160)))
                turnCompleted(turnId = "turn-3", threadId = THREAD)
            }

        val (one, two) = runSession(runner(first, second), "Work on c-01", "Continue")

        assertIs<RunnerEvent.TurnCompleted>(two.last(), "$two")
        assertEquals(2, launches.size)
        val usage = (one + two).filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(18_000L to 100L, 30_000L to 120L, 40_000L to 160L), usage.map { it.inputTokens to it.outputTokens })
    }

    @Test
    fun `bl-232 a new app server that exits after answering thread resume and before turn start fails the turn, no third one starts`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                exit(0)
            }
        val second =
            server("b") {
                awaitClient()
                response(1, FakeAppServer.initializeResult())
                awaitClient()
                awaitClient()
                response(2, FakeAppServer.threadResumeResult(THREAD))
                awaitClient()
                exit(6)
            }

        val (_, two) = runSession(runner(first, second), "Work on c-01", "Continue")

        val started = assertIs<RunnerEvent.TurnStarted>(two.first())
        val failed = assertIs<RunnerEvent.TurnFailed>(two.last())
        assertEquals("port_exit: exit code 6, before the turn/start response", failed.reason)
        assertEquals(6, failed.exitCode)
        assertEquals(started.sessionId to started.turnId, failed.sessionId to failed.turnId)
        assertEquals(1, two.count(::isTerminal), "$two")
        assertEquals(listOf("initialize", "initialized", "thread/resume", "turn/start"), received(second).map { it.string("method") })
        assertEquals(2, launches.size, "a turn starts one new app server at most")
    }

    @Test
    fun `bl-232 after resumeSession, a later turn whose app server is gone resumes the thread again and keeps the baseline`() {
        val first =
            server("a") {
                resumeHandshake(threadId = THREAD, turnId = "turn-9")
                notification(tokenUsage(turnId = "turn-of-the-held-attempt", total = tokens(input = 18_000, output = 100)))
                notification(tokenUsage(turnId = "turn-9", total = tokens(input = 36_000, output = 150)))
                turnCompleted(turnId = "turn-9", threadId = THREAD)
                exit(0)
            }
        val second =
            server("b") {
                resumeHandshake(threadId = THREAD, turnId = "turn-10")
                notification(tokenUsage(turnId = "turn-9", total = tokens(input = 36_000, output = 150)))
                notification(tokenUsage(turnId = "turn-10", total = tokens(input = 50_000, output = 200)))
                turnCompleted(turnId = "turn-10", threadId = THREAD)
            }

        val session = assertNotNull(runner(first, second).resumeSession(context(), THREAD))
        val (one, two) = runTurns(session, "Work on c-01 again", "Continue")

        assertIs<RunnerEvent.TurnCompleted>(one.last(), "$one")
        assertEquals(RunnerEvent.TurnStarted(SessionId("$THREAD-turn-10"), THREAD, TurnId("turn-10"), 2), two.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("$THREAD-turn-10"), TurnId("turn-10"), 0), two.last())
        assertTrue(
            "loop: the app server of this session is gone, turn 2 starts a new one and resumes the thread $THREAD" in loopLines(two),
            "${loopLines(two)}",
        )
        assertEquals(2, launches.size)
        val sent = received(second)
        assertEquals(listOf("initialize", "initialized", "thread/resume", "turn/start"), sent.map { it.string("method") })
        assertEquals(THREAD, sent[2].obj("params").string("threadId"))
        val usage = (one + two).filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(18_000L to 50L, 32_000L to 100L), usage.map { it.inputTokens to it.outputTokens })
    }

    @Test
    fun `bl-212 an exec policy rule written before a respawn fails the turn with exec_policy_rule and starts no app server`() {
        val first =
            server("a") {
                handshake(threadId = THREAD, turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = THREAD)
                exit(0)
            }
        val rules = workspace.resolve(".codex/rules/agent.rules")
        val runner = runner(first, server("b") { exit(0) })

        val turns =
            CompletableFuture
                .supplyAsync {
                    runBlocking {
                        val session = checkNotNull(runner.openSession(context()))
                        try {
                            val one = session.runTurn("Work on c-01", 1).toList()
                            rules.parent.createDirectories()
                            rules.writeText("prefix_rule(pattern=[\"git\", \"push\"], decision=\"allow\")\n")
                            listOf(one, session.runTurn("Continue", 2).toList())
                        } finally {
                            session.stop()
                        }
                    }
                }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

        val failed = assertIs<RunnerEvent.TurnFailed>(turns[1].last())
        assertTrue(failed.reason.startsWith("exec_policy_rule: $rules allows the denied command git push"), failed.reason)
        assertEquals(1, launches.size, "the guard refused the respawn before a new app server started")
    }

    // Helpers.

    private fun server(
        name: String,
        steps: FakeAppServer.Script.() -> Unit,
    ): FakeAppServer = FakeAppServer.write(dir.resolve(name), steps)

    /** A runner whose nth launch starts the nth of [servers], with strict MCP off unless [strictMcp]. */
    private fun runner(
        vararg servers: FakeAppServer,
        strictMcp: Boolean = false,
        model: String? = null,
    ): CodexAppServerRunner {
        val launcher = ProcessGroupLauncher()
        return CodexAppServerRunner(
            CodexAppServerSettings(
                servers.first().command,
                turnTimeout = 10.seconds,
                readTimeout = FakeAppServer.READ_TIMEOUT,
                strictMcp = strictMcp,
                model = model,
            ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = { spec ->
                val next = servers[launches.size.coerceAtMost(servers.size - 1)]
                launches += spec
                launcher.launch(spec.copy(argv = listOf(next.script.toString())))
            },
            loopVersion = "test-build",
        )
    }

    /** Runs one turn per input on one session of [runner], then stops it; the events of each turn. */
    private fun runSession(
        runner: CodexAppServerRunner,
        vararg inputs: String,
    ): List<List<RunnerEvent>> = runTurns(checkNotNull(runner.openSession(context())), *inputs)

    /** Runs one turn per input on [session], then stops it; the events of each turn. */
    private fun runTurns(
        session: AgentSession,
        vararg inputs: String,
    ): List<List<RunnerEvent>> =
        CompletableFuture
            .supplyAsync {
                runBlocking {
                    try {
                        inputs.mapIndexed { index, input -> session.runTurn(input, index + 1).toList() }
                    } finally {
                        session.stop()
                    }
                }
            }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private fun tokenUsage(
        turnId: String,
        total: TokenUsageBreakdown,
    ): ServerNotification =
        ServerNotification.ThreadTokenUsageUpdated(
            params = ThreadTokenUsageUpdatedNotification(THREAD, ThreadTokenUsage(last = total, total = total), turnId),
        )

    private fun tokens(
        input: Long,
        output: Long,
    ) = TokenUsageBreakdown(
        cachedInputTokens = 0,
        inputTokens = input,
        outputTokens = output,
        reasoningOutputTokens = 0,
        totalTokens = input + output,
    )

    private fun attemptEnv(launcher: Path): Map<String, String> {
        val config = dir.resolve("tool-config.json")
        config.writeText(
            buildJsonObject {
                put("version", 1)
                put("project_id", "project-1")
                put("workflow_dir", dir.toString())
                put("tracker_kind", "files")
                put("defined_states", CodexJson.format.parseToJsonElement("""["todo","review","needs_info","done"]"""))
                putJsonObject("provider") {}
            }.toString(),
        )
        return mapOf(
            "LOOP_LAUNCHER" to launcher.toString(),
            "LOOP_TOOL_CONFIG" to config.toString(),
            "LOOP_ATTEMPT_ID" to "attempt-1",
            "LOOP_ISSUE_ID" to "issue-1",
            "LOOP_ISSUE_IDENTIFIER" to "c-01",
            "LOOP_HOME" to home.resolve("loop-home").toString(),
        )
    }

    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

    private fun loopLines(events: List<RunnerEvent>): List<String> =
        events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stderr && it.line.startsWith("loop: ") }.map { it.line }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

    private fun context(env: Map<String, String> = emptyMap()): AttemptContext =
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
            env = env,
        )

    private fun pid(file: Path): Long? = if (file.exists()) file.readText().trim().toLongOrNull() else null

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun awaitDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return !isAlive(pid)
    }

    private companion object {
        const val THREAD = "019a0000-0000-7000-8000-0000000000a1"
        const val POLL_MS = 50L
        val TIMEOUT = 30.seconds
    }
}
