package loop.runner.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.v2.AccountUpdatedNotification
import loop.runner.codex.protocol.v2.AuthMode
import loop.runner.codex.protocol.v2.CodexErrorInfo
import loop.runner.codex.protocol.v2.TurnStatus
import loop.runner.codex.schema.PinnedSchema
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * m3-17: the Codex runner launches the app server in the workspace, by argv since m3-20, with no shell, reads its
 * stdout with the protocol framing and keeps its stderr apart, as diagnostics. Every server here is a [FakeAppServer].
 */
class CodexAppServerRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    /** Every frame the runner's parser received, in order. */
    private val frames: MutableList<Frame> = Collections.synchronizedList(mutableListOf())

    /** Pids of hanging fakes and their children, killed after each test. */
    private val pids = mutableListOf<Long>()

    @AfterEach
    fun `no app server outlives its test`() {
        val alive = pids.filter { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        alive.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        assertTrue(alive.isEmpty(), "app servers still alive after the test, now killed: $alive")
    }

    /**
     * The name is kept from m3-17, when the launch was `bash -lc <command>` as spec 10.1 has it. Since m3-20 the
     * command is split into words and run by argv, with no shell, so a login profile cannot change the agent env.
     */
    @Test
    fun `17_5 the app server runs in the workspace cwd through bash -lc with the configured command`() {
        val report = dir.resolve("report.txt")
        val server =
            FakeAppServer.write(dir) {
                shell("{ pwd -P; printf '%s\\n' \"\$@\"; } > '$report'")
                exit(0)
            }
        val command = "${server.command} app-server 'two words'"
        val launched = mutableListOf<LaunchSpec>()
        val launcher = ProcessGroupLauncher()
        val runner =
            runner(command) { spec ->
                launched += spec
                launcher.launch(spec)
            }

        runAll(runner)

        val spec = launched.single()
        assertEquals(listOf(server.script.toString(), "app-server", "two words"), spec.argv)
        assertEquals(workspace, spec.cwd)
        assertEquals(listOf(workspace.toRealPath().toString(), "app-server", "two words"), report.readLines())
    }

    @Test
    fun `m3-20 a name the env policy removed stays out even when the login profile exports it`() {
        val report = dir.resolve("profile-env.txt")
        listOf(".bash_profile", ".profile").forEach {
            home.resolve(it).writeText("export GH_TOKEN=from-profile\nexport LOOP_HOLDOUT=from-profile\n")
        }
        val server =
            FakeAppServer.write(dir) {
                shell("printf '%s\\n' \"\${GH_TOKEN-unset}\" \"\${LOOP_HOLDOUT-unset}\" > '$report'")
                exit(0)
            }
        val runner =
            CodexAppServerRunner(
                CodexAppServerSettings(server.command, readTimeout = FakeAppServer.READ_TIMEOUT, strictMcp = false),
                hostEnv = hostEnv() + mapOf("GH_TOKEN" to "from-host"),
                removedEnvNames = setOf("GH_TOKEN"),
                addedEnv = emptyMap(),
            )

        runAll(runner)

        assertEquals(listOf("unset", "unset"), report.readLines())
    }

    @Test
    fun `17_5 the app server gets the shared agent env, removed names out, added env and the context env in`() {
        val report = dir.resolve("env.txt")
        val server =
            FakeAppServer.write(dir) {
                shell(
                    "printf '%s\\n' \"\$KEPT_VALUE\" \"\${GONE_SECRET-unset}\" \"\$ADDED_VALUE\" \"\$PROBE\" \"\${ANTHROPIC_API_KEY-unset}\" > '$report'",
                )
                exit(0)
            }
        val runner =
            CodexAppServerRunner(
                CodexAppServerSettings(server.command, readTimeout = FakeAppServer.READ_TIMEOUT),
                hostEnv = hostEnv() + mapOf("KEPT_VALUE" to "kept", "GONE_SECRET" to "secret", "ANTHROPIC_API_KEY" to "key"),
                removedEnvNames = setOf("GONE_SECRET"),
                addedEnv = mapOf("ADDED_VALUE" to "{workspace}/added"),
            )

        runAll(runner, env = mapOf("PROBE" to "probe-value"))

        assertEquals(listOf("kept", "unset", "$workspace/added", "probe-value", "unset"), report.readLines())
    }

    @Test
    fun `17_5 a malformed line mid stream does not fail the turn, the frames after it still reach the parser`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                raw("""{"method": "account/updated", """)
                shell("sleep 0.3")
                notification(accountUpdated())
                exit(0)
            }

        val events = runAll(runner(server.command))

        val turnFrames = frames.drop(STARTUP_RESPONSES)
        assertEquals(listOf("Malformed", "Notification"), turnFrames.map { it::class.simpleName })
        assertEquals("not JSON", assertIs<Frame.Malformed>(turnFrames[0]).reason)
        assertEquals("account/updated", assertIs<Frame.Notification>(turnFrames[1]).message.method)
        // The malformed line is kept as output and logged by its reason, the line itself never quoted in the log.
        assertTrue(RunnerEvent.Output(Stream.Stdout, """{"method": "account/updated", """) in events, "$events")
        assertTrue(
            RunnerEvent.Output(Stream.Stderr, "loop: an app server stdout line is not a protocol message (not JSON), skipped") in events,
            "$events",
        )
        // One terminal event, the server's exit after its last line, not the malformed line.
        assertEquals(1, events.count(::isTerminal), "$events")
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("port_exit: exit code 0, no turn result", failed.reason)
        assertEquals(0, failed.exitCode)
    }

    @Test
    fun `17_5 a line split across two reads is read as one message`() {
        val notification = accountUpdated()
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(notification, split = true)
                exit(0)
            }

        val events = runAll(runner(server.command))

        val frame = assertIs<Frame.Notification>(frames.drop(STARTUP_RESPONSES).single())
        assertEquals("account/updated", frame.message.method)
        assertEquals(STARTUP_RESPONSES + 1, events.filter { it is RunnerEvent.Output && it.stream == Stream.Stdout }.size, "$events")
        assertTrue(events.none { it is RunnerEvent.Output && "not a protocol message" in it.line }, "$events")
        assertEquals(1, events.count(::isTerminal), "$events")
    }

    @Test
    fun `17_5 stderr never reaches the protocol parser and is kept as diagnostics`() {
        val lookalike = """{"method":"account/updated","params":{"authMode":"apikey"}}"""
        val server =
            FakeAppServer.write(dir) {
                stderr("codex: starting app server")
                handshake()
                stderr(lookalike)
                notification(accountUpdated())
                stderr("not json at all {")
                exit(0)
            }

        val events = runAll(runner(server.command))

        // Only the stdout message was parsed: the stderr lookalike and the stderr garbage never were.
        val frame = assertIs<Frame.Notification>(frames.drop(STARTUP_RESPONSES).single())
        assertEquals(CodexJson.format.encodeToString(ServerNotification.serializer(), accountUpdated()), frame.line)
        val stderr = events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stderr }.map { it.line }
        assertEquals(listOf("codex: starting app server", lookalike, "not json at all {"), stderr.filterNot { it.startsWith("loop: ") })
        assertTrue(stderr.none { "not a protocol message" in it }, "a stderr line was parsed: $stderr")
        val stdout = events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stdout }.map { it.line }
        assertEquals(frames.map { it.line }, stdout)
    }

    @Test
    fun `17_5 item 2 startup writes initialize, initialized, thread start and turn start in order, each after the answer before it`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command))

        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start"), sent.map { it.string("method") })
        assertEquals(listOf("1", null, "2", "3"), sent.map { it["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `17_5 item 2 a server that never answers initialize gets nothing after it`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                shell("sleep 0.5")
                exit(5)
            }

        val events = runAll(runner(server.command))

        assertEquals(listOf("initialize"), received(server).map { it.string("method") })
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("port_exit: exit code 5, before the initialize response", failed.reason)
        assertEquals(5, failed.exitCode)
    }

    @Test
    fun `17_5 item 2 an error answer fails the start with response_error, writes nothing more and stops the server`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(1, FakeAppServer.initializeResult())
                awaitClient()
                awaitClient()
                error(2, -32600, "thread/start is not available")
                // A server that stays: the runner must stop it, not wait for it.
                shell("sleep 1000")
            }

        val events = runAll(runner(server.command))

        assertEquals(listOf("initialize", "initialized", "thread/start"), received(server).map { it.string("method") })
        assertEquals(1, events.count(::isTerminal), "$events")
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: thread/start failed with code -32600: thread/start is not available", failed.reason)
        assertNull(failed.exitCode)
        assertNull(failed.category)
        // The start failed, so the ids are Loop's own, still composed as the spec says and on the terminal event.
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("${started.threadId}-${started.turnId.value}", started.sessionId.value)
        assertEquals(started.sessionId to started.turnId, failed.sessionId to failed.turnId)
    }

    @Test
    fun `17_5 item 2 an answer out of order fails the start with response_error naming both requests`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(2, FakeAppServer.threadStartResult(FakeAppServer.THREAD_ID))
                shell("sleep 1000")
            }

        val events = runAll(runner(server.command))

        assertEquals(listOf("initialize"), received(server).map { it.string("method") })
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: an answer to request 2 arrived while waiting for the initialize response", failed.reason)
        assertNull(failed.exitCode)
    }

    @Test
    fun `17_5 item 2 an answer that is not the method's response type fails the start with response_error`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(1, buildJsonObject { put("ok", true) })
                shell("sleep 1000")
            }

        val events = runAll(runner(server.command))

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: the initialize response does not match the pinned schema", failed.reason)
    }

    @Test
    fun `17_5 item 3 initialize names the client loop with Loop's version and asks for the stable api only`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command, loopVersion = "0123456789ab"))

        val params = received(server).first().obj("params")
        assertEquals(
            CodexJson.format.parseToJsonElement("""{"name":"loop","version":"0123456789ab"}"""),
            params["clientInfo"],
        )
        assertEquals(CodexJson.format.parseToJsonElement("""{"experimentalApi":false}"""), params["capabilities"])
    }

    @Test
    fun `17_5 item 4 thread start and turn start carry the documented approval and sandbox settings and the workspace cwd`() {
        val server =
            FakeAppServer.write(dir) {
                handshake(threadId = "thread-a")
                turnCompleted(threadId = "thread-a")
            }

        runAll(runner(server.command))

        val (_, _, thread, turn) = received(server)
        val threadParams = thread.obj("params")
        assertEquals("untrusted", threadParams.string("approvalPolicy"))
        assertEquals("danger-full-access", threadParams.string("sandbox"))
        assertEquals(workspace.toAbsolutePath().toString(), threadParams.string("cwd"))
        val turnParams = turn.obj("params")
        assertEquals("untrusted", turnParams.string("approvalPolicy"))
        assertEquals(CodexJson.format.parseToJsonElement("""{"type":"dangerFullAccess"}"""), turnParams["sandboxPolicy"])
        assertEquals(workspace.toAbsolutePath().toString(), turnParams.string("cwd"))
        assertEquals("thread-a", turnParams.string("threadId"))
        assertEquals(CodexJson.format.parseToJsonElement("""[{"type":"text","text":"Work on c-01"}]"""), turnParams["input"])
    }

    @Test
    fun `17_5 items 3 and 4 every startup message Loop writes validates against the pinned schema`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                turnCompleted()
            }

        runAll(runner(server.command))

        val schema = PinnedSchema.load()
        val (initialize, initialized, thread, turn) = received(server)
        assertEquals(emptyList(), schema.problems("ClientRequest", initialize))
        assertEquals(emptyList(), schema.problems("InitializeParams", initialize.obj("params")))
        assertEquals(emptyList(), schema.problems("ClientNotification", initialized))
        assertEquals(emptyList(), schema.problems("ClientRequest", thread))
        assertEquals(emptyList(), schema.problems("v2/ThreadStartParams", thread.obj("params")))
        assertEquals(emptyList(), schema.problems("ClientRequest", turn))
        assertEquals(emptyList(), schema.problems("v2/TurnStartParams", turn.obj("params")))
    }

    @Test
    fun `17_5 item 5 session_started carries the thread id and the turn id of the answers, before the output read during startup`() {
        val server =
            FakeAppServer.write(dir) {
                stderr("codex: starting app server")
                handshake(threadId = "thread-a", turnId = "turn-b")
                notification(accountUpdated())
                exit(0)
            }

        val events = runAll(runner(server.command))

        assertEquals(
            RunnerEvent.SessionStarted(SessionId("thread-a-turn-b"), "thread-a", TurnId("turn-b")),
            events.first(),
        )
        assertEquals(1, events.count { it is RunnerEvent.SessionStarted }, "$events")
        // What was read before the ids were known is held and comes after SessionStarted, none of it lost.
        val output = events.filterIsInstance<RunnerEvent.Output>()
        assertEquals(listOf("codex: starting app server"), output.filter { it.stream == Stream.Stderr }.map { it.line })
        assertEquals(frames.map { it.line }, output.filter { it.stream == Stream.Stdout }.map { it.line })
        assertEquals(STARTUP_RESPONSES + 1, frames.size)
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(SessionId("thread-a-turn-b") to TurnId("turn-b"), failed.sessionId to failed.turnId)
        assertEquals("port_exit: exit code 0, no turn result", failed.reason)
    }

    @Test
    fun `a command the shell cannot find fails with agent_not_found before any protocol line`() {
        val events = runAll(runner("'${dir.resolve("no-such-codex")}' app-server"))

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(127, failed.exitCode)
        assertEquals(
            "agent_not_found: '${dir.resolve("no-such-codex")}' app-server: the launcher exited 127, the command could not be run",
            failed.reason,
        )
        // No shell runs the command since m3-20: the launcher's own line says why it could not run.
        assertTrue(events.any { it is RunnerEvent.Output && it.stream == Stream.Stderr && "loop: exec failed" in it.line }, "$events")
        assertNull(failed.category)
    }

    @Test
    fun `a launcher that cannot spawn fails with agent_not_found`() {
        val runner = runner("codex app-server") { _ -> flowOf(ProcessEvent.SpawnFailed("loop requires perl on PATH")) }

        val events = runAll(runner)

        assertEquals(1, events.count(::isTerminal), "$events")
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("agent_not_found: codex app-server: loop requires perl on PATH", failed.reason)
        assertNull(failed.exitCode)
    }

    @Test
    fun `a workspace that is not a directory fails with invalid_workspace_cwd and starts nothing`() {
        val file = dir.resolve("not-a-dir").apply { writeText("") }
        var launched = false
        val runner =
            runner("codex app-server") { spec ->
                launched = true
                ProcessGroupLauncher().launch(spec)
            }

        val events = runAll(runner, workspace = file)

        assertFalse(launched)
        assertEquals("invalid_workspace_cwd: $file", assertIs<RunnerEvent.TurnFailed>(events.last()).reason)
    }

    /**
     * m3-19: it now declares continuation, client tools and no cost, occupancy or sandbox, and opens a session. Since
     * m3-21 it declares usage, and still no cost, which Codex does not report. Since m3-23 it declares occupancy. The name predates m3-19 and is kept because the `deleted_tests` guard refutes any test name that disappears; the
     * maintainer renames it after the merge.
     */
    @Test
    fun `before a handshake it declares no continuation, client tools and no usage, cost, occupancy or sandbox`() {
        val runner = runner("codex app-server")

        assertEquals(
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.ClientTools,
                usage = true,
                cost = false,
                occupancy = true,
                sandbox = false,
            ),
            runner.capabilities,
        )
        assertNotNull(runner.openSession(context()))
    }

    @Test
    fun `m4-46 it resumes no session across attempts, so after a network hold Loop opens a fresh one with the brief`() {
        val runner = runner("codex app-server")

        assertNull(runner.resumeSession(context(), "thread-of-the-held-attempt"))
    }

    @Test
    fun `contract before a handshake, SessionStarted first, ids on every event, one TurnFailed last and nothing undeclared`() {
        val server =
            FakeAppServer.write(dir) {
                notification(accountUpdated())
                stderr("codex: shutting down")
                exit(3)
            }

        val events = runAll(runner(server.command))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("${started.threadId}-${started.turnId.value}", started.sessionId.value)
        assertEquals(1, events.count { it is RunnerEvent.SessionStarted }, "$events")
        assertEquals(1, events.count(::isTerminal), "$events")
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(started.sessionId to started.turnId, failed.sessionId to failed.turnId)
        assertEquals("port_exit: exit code 3, before the initialize response", failed.reason)
        assertNull(failed.category)
        assertTrue(events.none { it is RunnerEvent.Usage || it is RunnerEvent.UsageEstimate || it is RunnerEvent.ContextUsage }, "$events")
        // The output read before the end is all there, in order, between the start and the terminal event.
        val output = events.filterIsInstance<RunnerEvent.Output>()
        assertEquals(listOf(Stream.Stdout, Stream.Stderr), output.map { it.stream })
    }

    @Test
    fun `contract before a handshake, cancelling the collection kills the app server and its children`() {
        val pidFile = dir.resolve("server.pid")
        val server = FakeAppServer.write(dir) { shell("sleep 1000 &\necho \$\$ > '$pidFile'\nwait") }
        val runner = runner(server.command)
        var children = emptyList<ProcessHandle>()

        runBlocking {
            withTimeout(TIMEOUT) {
                val collection = launch(Dispatchers.IO) { runner.run(context(), "").collect() }
                while (pid(pidFile) == null) delay(POLL_MS)
                val pid = checkNotNull(pid(pidFile))
                pids += pid
                while (children.isEmpty()) {
                    children = ProcessHandle.of(pid).map { it.descendants().toList() }.orElse(emptyList())
                    delay(POLL_MS)
                }
                pids += children.map { it.pid() }
                collection.cancelAndJoin()
            }
        }

        (listOf(checkNotNull(pid(pidFile))) + children.map { it.pid() }).forEach { pid ->
            assertTrue(awaitDead(pid), "process $pid is gone after cancel")
        }
    }

    @Test
    fun `17_5 item 6 a real app server that never answers within the read timeout is killed and the turn fails with response_timeout`() {
        val pidFile = dir.resolve("server.pid")
        val server =
            FakeAppServer.write(dir) {
                shell("echo \$\$ > '$pidFile'")
                awaitClient()
                shell("exec sleep 1000")
            }

        val events = runAll(runner(server.command, readTimeout = 300.milliseconds))

        val pid = checkNotNull(pid(pidFile)).also { pids += it }
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_timeout: no initialize response within 300 ms", failed.reason)
        assertNull(failed.exitCode)
        assertTrue(awaitDead(pid), "the app server is gone")
    }

    @Test
    fun `17_5 item 7 a real app server silent for the turn timeout is killed and the turn fails with turn_timeout`() {
        val pidFile = dir.resolve("server.pid")
        val server =
            FakeAppServer.write(dir) {
                shell("echo \$\$ > '$pidFile'")
                handshake()
                shell("exec sleep 1000")
            }

        val events = runAll(runner(server.command, turnTimeout = 500.milliseconds))

        val pid = checkNotNull(pid(pidFile)).also { pids += it }
        assertIs<RunnerEvent.SessionStarted>(events.first())
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("turn_timeout: no protocol message for 500 ms", failed.reason)
        assertNull(failed.exitCode)
        assertTrue(awaitDead(pid), "the app server is gone")
    }

    @Test
    fun `m3-19 turn completed ends the turn with TurnCompleted and run stops the app server`() {
        val server =
            FakeAppServer.write(dir) {
                handshake(threadId = "thread-a", turnId = "turn-b")
                notification(accountUpdated())
                turnCompleted(turnId = "turn-b", threadId = "thread-a")
            }

        val events = runAll(runner(server.command))

        assertEquals(RunnerEvent.SessionStarted(SessionId("thread-a-turn-b"), "thread-a", TurnId("turn-b")), events.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("thread-a-turn-b"), TurnId("turn-b"), 0), events.last())
        assertEquals(1, events.count(::isTerminal), "$events")
    }

    @Test
    fun `m3-19 a failed or interrupted turn fails with turn_failed or turn_cancelled and the error message`() {
        mapOf(
            TurnStatus.FAILED to "turn_failed: the model is overloaded",
            TurnStatus.INTERRUPTED to "turn_cancelled: the model is overloaded",
        ).forEach { (status, reason) ->
            val server =
                FakeAppServer.write(dir.resolve(status.name).createDirectories()) {
                    handshake()
                    turnCompleted(status = status, error = "the model is overloaded")
                }

            val events = runAll(runner(server.command))

            val failed = assertIs<RunnerEvent.TurnFailed>(events.last(), "$status")
            assertEquals(reason, failed.reason)
            assertNull(failed.exitCode)
            assertNull(failed.category)
        }
    }

    @Test
    fun `m4-08 a failed turn whose error is a transport failure with no HTTP answer is runner_unavailable`() {
        listOf(
            CodexErrorInfo.HttpConnectionFailed(),
            CodexErrorInfo.ResponseStreamConnectionFailed(),
            CodexErrorInfo.ResponseStreamDisconnected(),
            CodexErrorInfo.ResponseTooManyFailedAttempts(),
        ).forEachIndexed { index, info ->
            val server =
                FakeAppServer.write(dir.resolve("transport-$index").createDirectories()) {
                    handshake()
                    turnCompleted(status = TurnStatus.FAILED, error = "stream disconnected before completion", errorInfo = info)
                }

            val failed = assertIs<RunnerEvent.TurnFailed>(runAll(runner(server.command)).last(), "$info")

            assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category, "$info")
            assertEquals("turn_failed: stream disconnected before completion", failed.reason)
            assertNull(failed.retryAfter)
        }
    }

    @Test
    fun `m4-08 a transport failure the server answered, another typed error or an interrupted turn keeps a null category`() {
        listOf(
            TurnStatus.FAILED to CodexErrorInfo.HttpConnectionFailed(httpStatusCode = 400),
            TurnStatus.FAILED to CodexErrorInfo.ResponseStreamDisconnected(httpStatusCode = 500),
            TurnStatus.FAILED to CodexErrorInfo.ModelNotFound,
            TurnStatus.FAILED to CodexErrorInfo.Other,
            TurnStatus.INTERRUPTED to CodexErrorInfo.ResponseStreamDisconnected(),
        ).forEachIndexed { index, (status, info) ->
            val server =
                FakeAppServer.write(dir.resolve("other-$index").createDirectories()) {
                    handshake()
                    turnCompleted(status = status, error = "the turn failed", errorInfo = info)
                }

            val failed = assertIs<RunnerEvent.TurnFailed>(runAll(runner(server.command)).last(), "$status $info")

            assertNull(failed.category, "$status $info")
        }
    }

    @Test
    fun `17_5 a continuation turn starts on the thread of the first turn, in the same app server, and keeps its thread id`() {
        val server =
            FakeAppServer.write(dir) {
                handshake(threadId = "thread-a", turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = "thread-a")
                continuation(id = 4, turnId = "turn-2")
                notification(accountUpdated())
                turnCompleted(turnId = "turn-2", threadId = "thread-a")
            }
        val launches = mutableListOf<LaunchSpec>()
        val launcher = ProcessGroupLauncher()
        val runner =
            runner(server.command) { spec ->
                launches += spec
                launcher.launch(spec)
            }

        val (first, second) = runSession(runner, "Work on c-01", "Continue with c-01")

        assertEquals(RunnerEvent.SessionStarted(SessionId("thread-a-turn-1"), "thread-a", TurnId("turn-1")), first.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("thread-a-turn-1"), TurnId("turn-1"), 0), first.last())
        assertEquals(RunnerEvent.TurnStarted(SessionId("thread-a-turn-2"), "thread-a", TurnId("turn-2"), 2), second.first())
        assertEquals(RunnerEvent.TurnCompleted(SessionId("thread-a-turn-2"), TurnId("turn-2"), 0), second.last())
        assertTrue(second.none { it is RunnerEvent.SessionStarted }, "$second")
        // One app server, one thread: the second turn is a turn/start on thread-a, never a second thread/start.
        assertEquals(1, launches.size)
        val sent = received(server)
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start", "turn/start"), sent.map { it.string("method") })
        val continuation = sent.last()
        assertEquals("4", continuation.string("id"))
        val params = continuation.obj("params")
        assertEquals("thread-a", params.string("threadId"))
        assertEquals(CodexJson.format.parseToJsonElement("""[{"type":"text","text":"Continue with c-01"}]"""), params["input"])
        assertEquals("untrusted", params.string("approvalPolicy"))
        assertEquals(workspace.toAbsolutePath().toString(), params.string("cwd"))
        assertEquals(emptyList(), PinnedSchema.load().problems("ClientRequest", continuation))
    }

    @Test
    fun `17_5 the app server exiting mid turn fails the turn with port_exit at once, not at the turn timeout`() {
        val server =
            FakeAppServer.write(dir) {
                handshake()
                notification(accountUpdated())
                exit(3)
            }

        // The default turn timeout is an hour: only the exit can end this turn within the test's time.
        val events = runAll(runner(server.command))

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("port_exit: exit code 3, no turn result", failed.reason)
        assertEquals(3, failed.exitCode)
        assertEquals(1, events.count(::isTerminal), "$events")
    }

    @Test
    fun `17_5 the app server exiting mid continuation turn fails that turn, and a later turn fails at once on the gone server`() {
        val server =
            FakeAppServer.write(dir) {
                handshake(threadId = "thread-a", turnId = "turn-1")
                turnCompleted(turnId = "turn-1", threadId = "thread-a")
                continuation(id = 4, turnId = "turn-2")
                exit(4)
            }

        val (_, second, third) = runSession(runner(server.command), "Work on c-01", "Continue", "Continue again")

        assertEquals(TurnId("turn-2"), assertIs<RunnerEvent.TurnStarted>(second.first()).turnId)
        val exited = assertIs<RunnerEvent.TurnFailed>(second.last())
        assertEquals("port_exit: exit code 4, no turn result", exited.reason)
        assertEquals(4, exited.exitCode)
        val started = assertIs<RunnerEvent.TurnStarted>(third.first())
        assertEquals("thread-a" to 3, started.threadId to started.turn)
        val gone = assertIs<RunnerEvent.TurnFailed>(third.last())
        assertEquals("port_exit: the app server of this session is gone, turn 3 cannot start on thread thread-a", gone.reason)
        assertEquals(started.sessionId to started.turnId, gone.sessionId to gone.turnId)
        assertEquals(listOf("turn/start", "turn/start"), received(server).drop(3).map { it.string("method") })
    }

    @Test
    fun `m3-19 a turn completed whose params are not the pinned type kills the app server and fails with response_error`() {
        val pidFile = dir.resolve("server.pid")
        val server =
            FakeAppServer.write(dir) {
                shell("echo \$\$ > '$pidFile'")
                handshake()
                raw("""{"method":"turn/completed","params":{"threadId":"${FakeAppServer.THREAD_ID}","turn":"not a turn"}}""")
                shell("exec sleep 1000")
            }

        // The turn itself kills the server: it is dead before the stop, which would kill it too.
        val (events, deadBeforeStop) = firstTurnThenStop(runner(server.command), pidFile, ::awaitDead)

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: the turn/completed notification does not match the pinned schema", failed.reason)
        assertNull(failed.exitCode)
        assertEquals(1, events.count(::isTerminal), "$events")
        assertTrue(deadBeforeStop, "the app server is gone once the turn failed")
    }

    @Test
    fun `m3-19 a turn completed that says the turn is still in progress kills the app server and fails with response_error`() {
        val pidFile = dir.resolve("server.pid")
        val server =
            FakeAppServer.write(dir) {
                shell("echo \$\$ > '$pidFile'")
                handshake()
                turnCompleted(status = TurnStatus.IN_PROGRESS)
                shell("exec sleep 1000")
            }

        // The turn itself kills the server: it is dead before the stop, which would kill it too.
        val (events, deadBeforeStop) = firstTurnThenStop(runner(server.command), pidFile, ::awaitDead)

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("response_error: the turn/completed notification says the turn is still in progress", failed.reason)
        assertNull(failed.exitCode)
        assertEquals(1, events.count(::isTerminal), "$events")
        assertTrue(deadBeforeStop, "the app server is gone once the turn failed")
    }

    @Test
    fun `m3-19 a turn completed for another turn id is passed over and the turn ends on its own`() {
        val server =
            FakeAppServer.write(dir) {
                handshake(threadId = "thread-a", turnId = "turn-b")
                turnCompleted(turnId = "turn-other", status = TurnStatus.FAILED, error = "not this turn", threadId = "thread-a")
                notification(accountUpdated())
                turnCompleted(turnId = "turn-b", threadId = "thread-a")
            }

        val events = runAll(runner(server.command))

        assertEquals(RunnerEvent.TurnCompleted(SessionId("thread-a-turn-b"), TurnId("turn-b"), 0), events.last())
        assertEquals(1, events.count(::isTerminal), "$events")
        // The other turn's completion is still read and kept as output, and the frames after it reach the parser.
        val stdout = events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stdout }.map { it.line }
        assertTrue(stdout.any { "turn-other" in it }, "$stdout")
        assertTrue(frames.any { it is Frame.Notification && it.message.method == "account/updated" }, "$frames")
    }

    @Test
    fun `m3-19 stop kills the group of an app server that ignores its closed stdin once the grace is over`() {
        val pidFile = dir.resolve("server.pid")
        val server =
            FakeAppServer.write(dir) {
                shell("echo \$\$ > '$pidFile'")
                handshake()
                turnCompleted()
                // Sleeps past its closed stdin: only the kill ends it.
                shell("exec sleep 1000")
            }
        val grace = 500.milliseconds

        val (events, aliveBeforeStop, stopTook) =
            firstTurnThenStop(runner(server.command, stopGrace = grace), pidFile) { pid ->
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertTrue(aliveBeforeStop, "the app server outlives its completed turn until the stop")
        assertTrue(stopTook >= grace, "stop waits the grace before the kill, took $stopTook")
        assertTrue(stopTook < TIMEOUT / 4, "stop does not wait for the server, took $stopTook")
        assertTrue(awaitDead(checkNotNull(pid(pidFile))), "the app server is gone after stop")
    }

    private fun runner(
        command: String,
        loopVersion: String = "test-build",
        readTimeout: Duration = FakeAppServer.READ_TIMEOUT,
        turnTimeout: Duration = CodexAppServerSettings().turnTimeout,
        stopGrace: Duration = 5.seconds,
        launch: ((LaunchSpec) -> Flow<ProcessEvent>)? = null,
    ): CodexAppServerRunner =
        CodexAppServerRunner(
            // Strict MCP (m3-20) adds config/read to the startup; these tests pin the startup without it.
            CodexAppServerSettings(command, turnTimeout = turnTimeout, readTimeout = readTimeout, strictMcp = false),
            hostEnv = hostEnv(),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = launch ?: ProcessGroupLauncher()::launch,
            onFrame = { frames += it },
            loopVersion = loopVersion,
            stopGrace = stopGrace,
        )

    /** The client lines the fake read, as JSON objects. */
    private fun received(server: FakeAppServer): List<JsonObject> =
        server.received.readLines().map { CodexJson.format.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    /** The host env of the tests: PATH, and a HOME of its own so `bash -l` reads no profile of the developer. */
    private fun hostEnv(): Map<String, String> = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString())

    private fun runAll(
        runner: CodexAppServerRunner,
        env: Map<String, String> = emptyMap(),
        workspace: Path = this.workspace,
    ): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(env, workspace), "Work on c-01").toList() } }
            .get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    /** Runs one turn per input on one session of [runner], then stops it; the events of each turn. */
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

    /** What [firstTurnThenStop] saw: the events of turn 1, what its check said before the stop, and how long the stop took. */
    private data class FirstTurn(
        val events: List<RunnerEvent>,
        val beforeStop: Boolean,
        val stopTook: Duration,
    )

    /**
     * Runs turn 1 on a session of [runner], runs [check] on the pid the fake wrote in [pidFile] while the session is
     * still open, then stops the session.
     */
    private fun firstTurnThenStop(
        runner: CodexAppServerRunner,
        pidFile: Path,
        check: (Long) -> Boolean,
    ): FirstTurn =
        CompletableFuture
            .supplyAsync {
                runBlocking {
                    val session = checkNotNull(runner.openSession(context()))
                    val took: Duration
                    val events: List<RunnerEvent>
                    val beforeStop: Boolean
                    try {
                        events = session.runTurn("Work on c-01", 1).toList()
                        beforeStop = check(checkNotNull(pid(pidFile)).also { pids += it })
                    } finally {
                        took = measureTime { session.stop() }
                    }
                    FirstTurn(events, beforeStop, took)
                }
            }.get(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

    private fun context(
        env: Map<String, String> = emptyMap(),
        workspace: Path = this.workspace,
    ): AttemptContext =
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

    private fun accountUpdated(): ServerNotification =
        ServerNotification.AccountUpdated(params = AccountUpdatedNotification(authMode = AuthMode.CHATGPT))

    private fun isTerminal(event: RunnerEvent): Boolean =
        event is RunnerEvent.TurnCompleted || event is RunnerEvent.TurnFailed || event is RunnerEvent.TurnInputRequired

    private fun pid(file: Path): Long? = if (file.exists()) file.readText().trim().toLongOrNull() else null

    private fun awaitDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private companion object {
        val TIMEOUT = 20.seconds

        /** The responses to `initialize`, `thread/start` and `turn/start` a handshake reads before the turn. */
        const val STARTUP_RESPONSES = 3
        const val POLL_MS = 50L
    }
}
