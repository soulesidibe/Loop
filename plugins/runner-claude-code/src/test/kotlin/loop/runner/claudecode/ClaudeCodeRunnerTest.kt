package loop.runner.claudecode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** ClaudeCodeRunner against a long lived fake `claude` script that replays recorded stream-json transcripts, one per turn. */
class ClaudeCodeRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var bin: Path

    /** The pids of the processes spawned by [runner], each recorded as it is spawned. */
    private val started = ConcurrentLinkedQueue<Long>()

    @Test
    fun `a success transcript gives session started with Claude's session id, usage, then turn completed`() {
        val claude = FakeClaude(bin, fixture = "success")

        val events = run(runner(claude))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("claude-session-1", started.runtimeSessionId)
        val tail = events.filterNot { it.isProgress() }.drop(1)
        assertIs<RunnerEvent.Usage>(tail[0])
        assertEquals(RunnerEvent.TurnCompleted(started.sessionId, started.turnId, 0), tail[1])
        assertEquals(2, tail.size, "events after SessionStarted: $tail")
    }

    @Test
    fun `a real transcript replays through the runner, every line in full as output (m2-18)`() {
        val claude = FakeClaude(bin, fixture = "real_read_and_bash")

        val events = run(runner(claude))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("claude-session-real-1", started.runtimeSessionId)
        val output = events.filterIsInstance<RunnerEvent.Output>().map { it.line }
        // The turn ends at the result line; the task summary Claude Code writes after it falls between turns (m3-07).
        val lines = Fixtures.lines("real_read_and_bash").filter { it.isNotBlank() }
        assertEquals(lines.take(lines.indexOfFirst { "\"type\":\"result\"" in it } + 1), output.filter { it.startsWith("{") })
        assertTrue(output.any { it.length > 2000 }, "the long tool result line is emitted uncut")
        val usage = assertIs<RunnerEvent.Usage>(events.filterNot { it.isProgress() }[1])
        assertEquals(57180, usage.cacheReadInputTokens)
        assertEquals(RunnerEvent.TurnCompleted(started.sessionId, started.turnId, 0), events.last())
    }

    @Test
    fun `claude runs headless in bypass mode with the guardrail settings file, which is deleted after exit`() {
        // m3-07: stream-json in and out, one long lived process.
        val claude = FakeClaude(bin)

        run(runner(claude, ClaudeCodeSettings(command = claude.script.toString(), model = "sonnet")), prompt = "Do t-1")

        val settingsFile = Path.of(assertNotNull(claude.argAfter("--settings")))
        assertEquals(
            listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose") +
                listOf("--settings", settingsFile.toString()) +
                listOf("--permission-mode", "bypassPermissions", "--strict-mcp-config", "--model", "sonnet"),
            claude.argv,
        )
        assertEquals(ClaudeSettingsFile.json(), claude.settings, "the settings file while claude ran")
        assertFalse(settingsFile.startsWith(workspace.toRealPath()), "settings file inside the workspace")
        assertFalse(settingsFile.exists(), "settings file left after exit")
        assertEquals(listOf("Do t-1"), claude.prompts)
        assertEquals(workspace.toRealPath().toString(), claude.cwd)
    }

    /** The attempt env the orchestrator gives the runner, with the launcher and the tool config snapshot (m1-23). */
    private fun attemptEnv(): Map<String, String> {
        val home = bin.toRealPath().resolve("loop-home")
        return mapOf(
            "LOOP_WORKFLOW" to "/projects/demo/WORKFLOW.md",
            "LOOP_ISSUE_ID" to "id-t-1",
            "LOOP_ISSUE_IDENTIFIER" to "t-1",
            "LOOP_WORKSPACE_KEY" to "t-1",
            "LOOP_HOME" to home.toString(),
            "LOOP_PROJECT_ID" to "project-1",
            "LOOP_RUN_ID" to "run-1",
            "LOOP_ATTEMPT_ID" to "attempt-1",
            "LOOP_LAUNCHER" to "/opt/loop/bin/loop",
            "LOOP_TOOL_CONFIG" to home.resolve("attempts/project-1/attempt-1/tool-config.json").toString(),
            "PATH" to "/opt/loop/bin:${HOST_ENV.getValue("PATH")}",
        )
    }

    @Test
    fun `each attempt starts with Loop's MCP server, configured from the tool config snapshot and secret free (m2-16)`() {
        val claude = FakeClaude(bin)
        val host = HOST_ENV + mapOf("GITHUB_TOKEN" to "ghp-host-secret", "ANTHROPIC_API_KEY" to "sk-host-secret")
        val env = attemptEnv() + ("ANTHROPIC_AUTH_TOKEN" to "context-secret")

        val events =
            run(
                runner(claude, hostEnv = host, removedEnvNames = setOf("GITHUB_TOKEN"), addedEnv = mapOf("SECRET_VALUE" to "agent-secret")),
                env = env,
            )

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val configFile = Path.of(assertNotNull(claude.argAfter("--mcp-config"), "${claude.argv}"))
        val settingsFile = Path.of(assertNotNull(claude.argAfter("--settings")))
        assertEquals(settingsFile.parent, configFile.parent, "beside the generated settings, outside the workspace")
        assertFalse(configFile.exists(), "mcp config left after exit")
        val expected =
            buildJsonObject {
                putJsonObject("mcpServers") {
                    putJsonObject("loop") {
                        put("type", "stdio")
                        put("command", "/opt/loop/bin/loop")
                        put("args", JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("mcp"))))
                        putJsonObject("env") {
                            put("LOOP_TOOL_CONFIG", env.getValue("LOOP_TOOL_CONFIG"))
                            put("LOOP_ATTEMPT_ID", "attempt-1")
                            put("LOOP_ISSUE_ID", "id-t-1")
                            put("LOOP_ISSUE_IDENTIFIER", "t-1")
                            put("LOOP_HOME", env.getValue("LOOP_HOME"))
                            put("LOOP_SESSION_ID", started.sessionId.value)
                            put("LOOP_TURN_ID", started.turnId.value)
                        }
                    }
                }
            }
        val config = assertNotNull(claude.mcpConfig, "the mcp config while claude ran")
        assertEquals(expected, Json.parseToJsonElement(config))
        listOf("ghp-host-secret", "sk-host-secret", "context-secret", "agent-secret").forEach { secret ->
            assertFalse(secret in config, "$secret is in the mcp config")
        }
    }

    @Test
    fun `the turns of a session share one process, whose MCP server carries the ids of the turn that spawned it (m3-07)`() {
        val claude = FakeClaude(bin)
        val runner = runner(claude)

        val turns =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(attemptEnv())))
                    listOf(session.runTurn("a", 1).toList(), session.runTurn("b", 2).toList()).also { session.stop() }
                }
            }

        assertEquals(1, claude.invocations)
        val first = assertIs<RunnerEvent.SessionStarted>(turns[0].first())
        assertIs<RunnerEvent.TurnStarted>(turns[1].first())
        assertTrue("--mcp-config" in claude.argvOf(0), "${claude.argvOf(0)}")
        val env =
            Json
                .parseToJsonElement(assertNotNull(claude.mcpConfig))
                .jsonObject
                .getValue("mcpServers")
                .jsonObject
                .getValue("loop")
                .jsonObject
                .getValue("env")
                .jsonObject
        assertEquals(first.turnId.value, env.getValue("LOOP_TURN_ID").jsonPrimitive.content)
    }

    @Test
    fun `every turn of a session gets the MCP server with that turn's ids (m2-16)`() {
        // m3-07: the MCP config is fixed when a process spawns. A process that exits after each turn makes every turn
        // spawn its own, and each one carries the ids of its turn.
        val claude = FakeClaude(bin, after = "exit 0")
        val runner = runner(claude)

        val turns =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(attemptEnv())))
                    listOf(session.runTurn("a", 1).toList(), session.runTurn("b", 2).toList()).also { session.stop() }
                }
            }

        assertEquals(2, claude.invocations)
        val first = assertIs<RunnerEvent.SessionStarted>(turns[0].first())
        val second = assertIs<RunnerEvent.TurnStarted>(turns[1].first())
        listOf(first.sessionId to first.turnId, second.sessionId to second.turnId).forEachIndexed { index, (sessionId, turnId) ->
            assertTrue("--mcp-config" in claude.argvOf(index), "${claude.argvOf(index)}")
            val env =
                Json
                    .parseToJsonElement(assertNotNull(claude.mcpConfigOf(index)))
                    .jsonObject
                    .getValue("mcpServers")
                    .jsonObject
                    .getValue("loop")
                    .jsonObject
                    .getValue("env")
                    .jsonObject
            assertEquals(sessionId.value, env.getValue("LOOP_SESSION_ID").jsonPrimitive.content)
            assertEquals(turnId.value, env.getValue("LOOP_TURN_ID").jsonPrimitive.content)
        }
    }

    @Test
    fun `without a tool config snapshot or a launcher no MCP server is configured (m2-16)`() {
        val claude = FakeClaude(bin)

        run(runner(claude), env = attemptEnv() - "LOOP_TOOL_CONFIG")
        assertFalse("--mcp-config" in claude.argv, "${claude.argv}")

        run(runner(claude), env = attemptEnv() - "LOOP_LAUNCHER")
        assertFalse("--mcp-config" in claude.argv, "${claude.argv}")
    }

    @Test
    fun `bl-72 by default only Loop's MCP server is loaded, strict, on every turn of a session`() {
        // m3-07: the turns share one process; every process a session spawns is strict.
        // The process dies after each turn, so the second turn spawns another one.
        val claude = FakeClaude(bin, after = "exit 0")
        val runner = runner(claude)

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(attemptEnv())))
                session.runTurn("a", 1).toList()
                session.runTurn("b", 2).toList()
                session.stop()
            }
        }

        listOf(claude.argvOf(0), claude.argvOf(1)).forEach { argv ->
            val strict = argv.indexOf("--strict-mcp-config")
            assertTrue(strict > argv.indexOf("bypassPermissions"), "$argv")
            assertEquals("--mcp-config", argv[strict + 1], "$argv")
            assertEquals(1, argv.count { it == "--strict-mcp-config" }, "$argv")
        }
    }

    @Test
    fun `bl-72 strict without Loop's MCP server passes the flag alone, so no MCP server is loaded`() {
        val claude = FakeClaude(bin)

        run(runner(claude), env = attemptEnv() - "LOOP_TOOL_CONFIG")

        assertTrue("--strict-mcp-config" in claude.argv, "${claude.argv}")
        assertFalse("--mcp-config" in claude.argv, "${claude.argv}")
    }

    @Test
    fun `bl-72 strict_mcp false keeps the operator's MCP servers beside Loop's, the m2-16 behavior`() {
        val claude = FakeClaude(bin)
        val open = ClaudeCodeSettings(command = claude.script.toString(), strictMcp = false)

        run(runner(claude, open), env = attemptEnv())
        assertFalse("--strict-mcp-config" in claude.argv, "${claude.argv}")
        assertTrue("--mcp-config" in claude.argv, "${claude.argv}")

        run(runner(claude, open))
        assertFalse("--strict-mcp-config" in claude.argv, "${claude.argv}")
    }

    @Test
    fun `the settings file claude reads denies loop approve and loop resume, by name and by the attempt's launcher (m2-24)`() {
        val claude = FakeClaude(bin)

        run(runner(claude), env = attemptEnv())

        val settings = claude.settings.orEmpty()
        assertEquals(ClaudeSettingsFile.json(launcher = "/opt/loop/bin/loop"), settings)
        listOf("loop", "/opt/loop/bin/loop").forEach { loop ->
            assertTrue("\"Bash($loop approve:*)\"" in settings, settings)
            assertTrue("\"Bash($loop resume:*)\"" in settings, settings)
        }
    }

    @Test
    fun `the settings file claude reads denies reads under the holdout folder (m2-14)`() {
        val claude = FakeClaude(bin)
        val holdout = bin.toRealPath().resolve("loop-home/holdout").createDirectories()

        run(runner(claude, ClaudeCodeSettings(command = claude.script.toString(), holdoutDir = holdout)))

        assertEquals(ClaudeSettingsFile.json(hiddenDirs = listOf(holdout)), claude.settings)
        assertTrue("\"Read(/$holdout/**)\"" in claude.settings.orEmpty(), claude.settings)
    }

    @Test
    fun `host secrets are not inherited, a harmless host variable is`() {
        val claude = FakeClaude(bin)
        val host =
            HOST_ENV +
                mapOf(
                    "ANTHROPIC_API_KEY" to "sk-host",
                    "ANTHROPIC_AUTH_TOKEN" to "token-host",
                    "LOOP_TEST_SECRET" to "secret",
                    "GITHUB_TOKEN" to "ghp-host",
                    "LOOP_TEST_HARMLESS" to "kept",
                )

        val events =
            run(
                runner(claude, hostEnv = host, removedEnvNames = setOf("LOOP_TEST_SECRET", "GITHUB_TOKEN")),
                env = mapOf("ANTHROPIC_API_KEY" to "sk-context"),
            )

        val env = claude.env
        listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "LOOP_TEST_SECRET", "GITHUB_TOKEN").forEach { name ->
            assertFalse(name in env, "$name reached the agent")
        }
        assertEquals("kept", env["LOOP_TEST_HARMLESS"])
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals(started.sessionId.value, env["LOOP_SESSION_ID"])
        assertEquals(started.turnId.value, env["LOOP_TURN_ID"])
    }

    @Test
    fun `agent env values reach claude with the workspace placeholder replaced, over the host value`() {
        val claude = FakeClaude(bin)

        run(
            runner(
                claude,
                hostEnv = HOST_ENV + mapOf("GRADLE_OPTS" to "-Dhost"),
                addedEnv = mapOf("GRADLE_OPTS" to "-Dbase={workspace}/daemons", "PLAIN_VALUE" to "plain"),
            ),
        )

        assertEquals("-Dbase=$workspace/daemons", claude.env["GRADLE_OPTS"])
        assertEquals("plain", claude.env["PLAIN_VALUE"])
    }

    @Test
    fun `loop tool resolves on PATH inside the agent`() {
        val launcherDir = bin.resolve("launcher").createDirectories()
        val loop = launcherDir.resolve("loop")
        loop.writeText("#!/bin/sh\necho \"loop \$*\"\n")
        Files.setPosixFilePermissions(loop, PosixFilePermissions.fromString("rwxr-xr-x"))
        val claude = FakeClaude(bin.resolve("claude").createDirectories(), before = "command -v loop > loop-path")
        // The orchestrator puts the launcher folder first on the context PATH.
        val path = "$launcherDir:${HOST_ENV.getValue("PATH")}"

        val events = run(runner(claude), env = mapOf("PATH" to path))

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertEquals(loop.toString(), workspace.resolve("loop-path").readText().trim())
    }

    @Test
    fun `a missing claude executable fails the turn with agent_not_found after SessionStarted`() {
        val missing = bin.resolve("no-such-claude").toString()
        val runner = ClaudeCodeRunner(ClaudeCodeSettings(command = missing), HOST_ENV)

        val events = run(runner).filterNot { it is RunnerEvent.Output }

        assertIs<RunnerEvent.SessionStarted>(events.first())
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertTrue(failed.reason.startsWith("agent_not_found: $missing: exec failed: "), failed.reason)
        assertFalse(failed.reason.endsWith("."), failed.reason)
        assertEquals(2, events.size, "$events")
    }

    @Test
    fun `a workspace that is not a directory fails the turn with invalid_workspace_cwd before any spawn`() {
        val claude = FakeClaude(bin)
        val missing = workspace.resolve("missing")

        val events = run(runner(claude), workspace = missing)

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val failed = RunnerEvent.TurnFailed(started.sessionId, started.turnId, null, "invalid_workspace_cwd: $missing")
        assertEquals(listOf(started, failed), events)
        assertFalse(claude.record.resolve("argv").exists(), "claude was spawned")
    }

    @Test
    fun `an exit or a spawn failure followed by silence past the turn timeout gives exactly one terminal event`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        mapOf(
            "exit" to listOf(ProcessEvent.Started(1)) + success + ProcessEvent.Exited(0),
            "spawn failure" to listOf(ProcessEvent.SpawnFailed("no perl")),
        ).forEach { (case, script) ->
            val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 300.milliseconds)
            val runner = ClaudeCodeRunner(settings, HOST_ENV, emptySet(), fakeLaunch(script), groupAlive = NO_GROUP)

            val events = run(runner)

            val terminal = events.filter { it is RunnerEvent.TurnCompleted || it is RunnerEvent.TurnFailed }
            assertEquals(1, terminal.size, "$case: $events")
            assertEquals(terminal.single(), events.last(), case)
        }
    }

    @Test
    fun `lines still queued when the turn times out are kept as output before the failure`() {
        val init = Fixtures.lines("success").first()
        val launch: (LaunchSpec) -> Flow<ProcessEvent> = {
            flow {
                emit(ProcessEvent.Started(1))
                emit(ProcessEvent.Line(Stream.Stdout, init))
                emit(ProcessEvent.Line(Stream.Stderr, "late 1"))
                emit(ProcessEvent.Line(Stream.Stderr, "late 2"))
                awaitCancellation()
            }
        }
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 300.milliseconds)

        val events = run(ClaudeCodeRunner(settings, HOST_ENV, emptySet(), launch, groupAlive = NO_GROUP))

        assertEquals("claude-session-1", assertIs<RunnerEvent.SessionStarted>(events.first()).runtimeSessionId)
        val output = events.filterIsInstance<RunnerEvent.Output>().map { it.line }
        assertEquals(listOf(init, "late 1", "late 2"), output)
        assertEquals("turn_timeout", assertIs<RunnerEvent.TurnFailed>(events.last()).reason)
    }

    @Test
    fun `a turn woken by a background task sums the usage of every result line, the cost is the latest (bl-25)`() {
        // m3-07: a turn ends at its result line, what the idle process writes next is read by the next turn.
        // A background wake: the first segment ends turn 1, the woken segment comes while the process is idle.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val next = Fixtures.lines("idle_next_turn").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3), next)), idle = wake.drop(3))

        val (first, second) = twoTurns(scriptedRunner(claude), "Fix it", "Keep going", pause = 800.milliseconds)

        val one = first.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(listOf(1000L, 40_000L, 11_500_000L, 149_000L), one.tokens())
        assertEquals(8.5, assertNotNull(one.totalCostUsd), 1e-9)
        assertIs<RunnerEvent.TurnCompleted>(first.last())
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        val output = second.filterIsInstance<RunnerEvent.Output>().map { it.line }
        assertEquals(Fixtures.lines("background_wake").drop(3) + Fixtures.lines("idle_next_turn"), output)
        val afterStart = second.filterNot { it is RunnerEvent.ContextUsage }.drop(1)
        assertTrue(afterStart.take(2).all { it is RunnerEvent.Output }, "the idle lines come right after TurnStarted: $second")
        // bl-108: each result line reports the session total as it is read. The woken segment's comes first, then
        // this turn's own: 1000 + 500 + 100 input tokens and so on, the latest cost.
        val reports = second.filterIsInstance<RunnerEvent.Usage>()
        val woken = listOf(1500L, 49_500L, 13_400_000L, 189_000L)
        assertEquals(listOf(woken, listOf(1600L, 49_550L, 13_400_010L, 189_005L)), reports.map { it.tokens() })
        assertEquals(listOf(9.75, 10.0), reports.map { it.totalCostUsd })
        val results = second.withIndex().filter { (_, e) -> e is RunnerEvent.Output && e.line.contains("\"type\":\"result\"") }
        assertEquals(reports, results.map { second[it.index + 1] }, "each report follows its own result line")
        assertEquals(RunnerEvent.TurnCompleted(resumed.sessionId, resumed.turnId, 0), second.last())
        assertEquals(1, claude.specs.size, "one process for both turns")
    }

    @Test
    fun `an idle process queues at most the bound of lines no turn needs, every usage and result line kept (m4-03, bl-122)`() {
        // A chatty background subagent writes 40 lines while the process is idle, around a wake with its init, its main
        // session answer and its result. With a bound of 5, 35 subagent lines are dropped, nothing the next turn needs.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val next = Fixtures.lines("idle_next_turn").map { ProcessEvent.Line(Stream.Stdout, it) }
        val chatter = (1..20).map { ProcessEvent.Line(Stream.Stdout, subagentLine(it)) }
        val noise = (21..40).map { ProcessEvent.Line(Stream.Stdout, subagentLine(it)) }
        val claude = Scripted(listOf(listOf(wake.take(3), next)), idle = chatter + wake[0] + wake[3] + noise + wake[4])
        val runner =
            ClaudeCodeRunner(
                ClaudeCodeSettings(command = "claude"),
                HOST_ENV,
                emptySet(),
                claude.launch,
                groupAlive = { false },
                idleLineBound = 5,
            )

        val (_, second) = twoTurns(runner, "Fix it", "Keep going", pause = 800.milliseconds)

        val warning =
            "loop: warning: the claude-code process wrote more than 5 lines while idle, 35 of them dropped, its usage and result lines kept"
        assertEquals(RunnerEvent.Output(Stream.Stderr, warning), second[1])
        val kept = chatter.take(5).map { it.text } + wake[0].text + wake[3].text + wake[4].text + next.map { it.text }
        assertEquals(kept, second.filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stdout }.map { it.line })
        val reports = second.filterIsInstance<RunnerEvent.Usage>()
        assertEquals(
            listOf(listOf(1500L, 49_500L, 13_400_000L, 189_000L), listOf(1600L, 49_550L, 13_400_010L, 189_005L)),
            reports.map {
                it.tokens()
            },
        )
        assertEquals(listOf(9.75, 10.0), reports.map { it.totalCostUsd })
        assertIs<RunnerEvent.TurnCompleted>(second.last())
    }

    @Test
    fun `a turn reads every line of the process, past the idle bound (m4-03)`() {
        // The bound holds only between turns: a turn's own subagent lines are never dropped.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val chatter = (1..20).map { ProcessEvent.Line(Stream.Stdout, subagentLine(it)) }
        val claude = Scripted(listOf(listOf(listOf(wake[0]) + chatter + wake.subList(1, 3))))
        val runner =
            ClaudeCodeRunner(
                ClaudeCodeSettings(command = "claude"),
                HOST_ENV,
                emptySet(),
                claude.launch,
                groupAlive = { false },
                idleLineBound = 5,
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(emptyMap()), "Fix it").toList() } }

        assertEquals((listOf(wake[0]) + chatter + wake.subList(1, 3)).map { it.text }, events.outputLines())
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `a turn waits for a wake still running when it starts, then ends at the result line of its own input (m3-07)`() {
        // The wake has written its assistant line when turn 2 starts, its result line comes 700 ms later. Turn 2's input
        // is written only after it, so the wake's result cannot end turn 2.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val next = Fixtures.lines("idle_next_turn").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude =
            Scripted(
                listOf(listOf(wake.take(3), next)),
                idle = listOf(wake[3]),
                idleLate = listOf(wake[4]),
                idleLateDelay = 700.milliseconds,
            )

        val (_, second) = twoTurns(scriptedRunner(claude), "Fix it", "Keep going", pause = 300.milliseconds)

        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(Fixtures.lines("background_wake").drop(3) + Fixtures.lines("idle_next_turn"), second.outputLines())
        val reports = second.filterIsInstance<RunnerEvent.Usage>()
        val woken = listOf(1500L, 49_500L, 13_400_000L, 189_000L)
        assertEquals(listOf(woken, listOf(1600L, 49_550L, 13_400_010L, 189_005L)), reports.map { it.tokens() })
        // The wake's lines are idle lines: no estimate comes from its assistant line.
        assertTrue(second.none { it is RunnerEvent.UsageEstimate }, "$second")
        assertEquals(RunnerEvent.TurnCompleted(resumed.sessionId, resumed.turnId, 0), second.last())
        assertEquals(2, claude.inputs.size)
        assertEquals(1, claude.specs.size)
    }

    @Test
    fun `a wake that goes silent past the turn timeout fails the waiting turn with turn_timeout, its input never written (m3-07)`() {
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3))), idle = listOf(wake[3]), ignoresEof = true)
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 500.milliseconds)

        val (_, second) = twoTurns(scriptedRunner(claude, settings), "Fix it", "Keep going", pause = 300.milliseconds)

        assertEquals("turn_timeout", assertIs<RunnerEvent.TurnFailed>(second.last()).reason)
        assertEquals(listOf(Fixtures.lines("background_wake")[3]), second.outputLines())
        assertEquals(1, claude.inputs.size, "turn 2's input was written to a process still running a wake")
    }

    @Test
    fun `a wake whose process exits before its result line is a process gone between turns, the turn resumes it (m3-07)`() {
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val resumed = Fixtures.lines("resumed").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude =
            Scripted(
                listOf(listOf(wake.take(3)), listOf(resumed)),
                idle = listOf(wake[3]),
                idleLate = listOf(ProcessEvent.Exited(1)),
                idleLateDelay = 400.milliseconds,
            )

        val (_, second) = twoTurns(scriptedRunner(claude), "Fix it", "Keep going", pause = 300.milliseconds)

        assertIs<RunnerEvent.TurnCompleted>(second.last())
        assertEquals(2, claude.specs.size)
        val resume = claude.specs.last().argv[2] to claude.specs.last().argv[3]
        assertEquals("--resume" to "claude-wake-session", resume)
        assertEquals(1, claude.inputs.count { "Keep going" in it }, "turn 2's input went to the new process only")
    }

    @Test
    fun `bl-132 a wake running when the session stops reports the usage it read before the stop`() {
        // The wake's assistant line comes while the process is idle; its result line comes once stdin is closed.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3))), idle = listOf(wake[3]), atExit = listOf(wake[4]))
        val runner = scriptedRunner(claude)

        val reported = mutableListOf<RunnerEvent.Usage>()
        val first =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(emptyMap())))
                    val one = session.runTurn("Fix it", 1).toList()
                    delay(300.milliseconds)
                    session.stop { reported += it }
                    one
                }
            }

        val turn = first.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(listOf(1000L, 40_000L, 11_500_000L, 149_000L), turn.tokens())
        // The session's total, the wake's segment added, with the session's latest cost.
        val usage = reported.single()
        assertEquals(listOf(1500L, 49_500L, 13_400_000L, 189_000L), usage.tokens())
        assertEquals(9.75, usage.totalCostUsd)
        assertEquals(turn.sessionId, usage.sessionId)
        assertEquals(turn.turnId, usage.turnId)
    }

    @Test
    fun `bl-132 a wake that ended while the process was idle reports its usage at the stop, a plain stop reports nothing`() {
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3))), idle = listOf(wake[3], wake[4]))

        val reported = mutableListOf<RunnerEvent.Usage>()
        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(scriptedRunner(claude).openSession(context(emptyMap())))
                session.runTurn("Fix it", 1).toList()
                delay(300.milliseconds)
                session.stop { reported += it }
            }
        }
        assertEquals(listOf(listOf(1500L, 49_500L, 13_400_000L, 189_000L)), reported.map { it.tokens() })

        // Without a wake the stop reads no result line, so it reports no usage.
        val quiet = Scripted(listOf(listOf(wake.take(3))))
        val none = mutableListOf<RunnerEvent.Usage>()
        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(scriptedRunner(quiet).openSession(context(emptyMap())))
                session.runTurn("Fix it", 1).toList()
                session.stop { none += it }
            }
        }
        assertEquals(emptyList(), none)
    }

    @Test
    fun `m3-12 an interrupt asks claude to stop the turn, its result line's usage and cost are reported, then the turn ends`() {
        val wake = Fixtures.lines("background_wake")
        val claude = Interruptible(first = wake.take(2), answer = listOf(INTERRUPTED_RESULT))
        val runner = ClaudeCodeRunner(SETTINGS, HOST_ENV, emptySet(), claude.launch, groupAlive = NO_GROUP)

        val events =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(emptyMap())))
                    try {
                        session.runTurn("Fix it", 1).onEach { if (it is RunnerEvent.ContextUsage) session.interrupt() }.toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val request = claude.inputs.single { INTERRUPT in it }.let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("control_request", request.getValue("type").jsonPrimitive.content)
        val usage = events.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(listOf(1201L, 16L, 0L, 0L), usage.tokens())
        assertEquals(0.42, usage.totalCostUsd)
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertTrue(failed.reason.contains("error_during_execution"), failed.reason)
        assertTrue(events.indexOf(usage) < events.lastIndex)
    }

    @Test
    fun `m3-12 an interrupt while a wake is drained ends the wake, reports its usage and never writes the turn's input`() {
        val wake = Fixtures.lines("background_wake")
        val claude = Interruptible(first = wake.take(3), idle = listOf(wake[3]), answer = listOf(wake[4]))
        val runner = ClaudeCodeRunner(SETTINGS, HOST_ENV, emptySet(), claude.launch, groupAlive = NO_GROUP)

        val second =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(emptyMap())))
                    try {
                        session.runTurn("Fix it", 1).toList()
                        delay(300.milliseconds)
                        session.runTurn("Keep going", 2).onEach { if (it is RunnerEvent.Output) session.interrupt() }.toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertEquals(1, claude.inputs.count { INTERRUPT in it })
        assertTrue(claude.inputs.none { "Keep going" in it }, "${claude.inputs}")
        val woken = listOf(1500L, 49_500L, 13_400_000L, 189_000L)
        assertEquals(listOf(woken), second.filterIsInstance<RunnerEvent.Usage>().map { it.tokens() })
        assertTrue(assertIs<RunnerEvent.TurnFailed>(second.last()).reason.startsWith("interrupted"), "$second")
    }

    @Test
    fun `m3-12 a turn cancelled while a wake is drained stops the claude process group at once`() {
        val wake = Fixtures.lines("background_wake")
        val claude = Interruptible(first = wake.take(3), idle = listOf(wake[3]), answer = emptyList())
        val runner = ClaudeCodeRunner(SETTINGS, HOST_ENV, emptySet(), claude.launch, groupAlive = NO_GROUP)

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                try {
                    session.runTurn("Fix it", 1).toList()
                    delay(300.milliseconds)
                    val draining = CompletableDeferred<Unit>()
                    val turn =
                        launch {
                            session.runTurn("Keep going", 2).collect {
                                if (it is RunnerEvent.Output) {
                                    session.interrupt()
                                    draining.complete(Unit)
                                }
                            }
                        }
                    draining.await()
                    // Like the orchestrator after the interrupt grace: the turn's flow is cancelled.
                    turn.cancelAndJoin()
                    assertTrue(claude.killed, "the process group outlived the cancelled turn")
                } finally {
                    session.stop()
                }
            }
        }
    }

    @Test
    fun `m3-11 a rotation's new session spawns claude without --resume, on a new thread`() {
        val success = stdout(Fixtures.lines("success"))
        val claude = Scripted(listOf(listOf(success), listOf(success)))
        val runner = scriptedRunner(claude)

        val (old, rotated) =
            runBlocking {
                withTimeout(20.seconds) {
                    // The orchestrator stops the session that crossed the soft limit, then opens a new one at its turn 1.
                    val first = checkNotNull(runner.openSession(context(emptyMap())))
                    val one = first.runTurn("Fix it", 1).toList()
                    first.stop()
                    val second = checkNotNull(runner.openSession(context(emptyMap())))
                    try {
                        one to second.runTurn("A new session with a brief", 1).toList()
                    } finally {
                        second.stop()
                    }
                }
            }

        assertEquals(2, claude.specs.size)
        claude.specs.forEach { spec -> assertFalse("--resume" in spec.argv, "${spec.argv}") }
        val oldThread = assertIs<RunnerEvent.SessionStarted>(old.first()).threadId
        val newThread = assertIs<RunnerEvent.SessionStarted>(rotated.first()).threadId
        assertTrue(oldThread != newThread, "a rotation starts a new thread")
        assertIs<RunnerEvent.TurnCompleted>(rotated.last())
        assertEquals(1, claude.inputs.count { "A new session with a brief" in it })
    }

    @Test
    fun `a result line then silence past the turn timeout reports the usage seen before turn_timeout (bl-37)`() {
        // m3-07: a result line ends its turn, so the result line before the silence is an idle one read by the next turn.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val init = ProcessEvent.Line(Stream.Stdout, Fixtures.lines("idle_next_turn").first())
        val claude = Scripted(listOf(listOf(wake.take(3), listOf(init))), idle = wake.drop(3))
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 500.milliseconds)

        val (_, second) = twoTurns(scriptedRunner(claude, settings), "Fix it", "Keep going", pause = 800.milliseconds)

        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        val terminal = second.filterNot { it.isProgress() }.drop(1)
        assertEquals(
            listOf(
                RunnerEvent.Usage(resumed.sessionId, resumed.turnId, 1500, 49_500, 13_400_000, 189_000, totalCostUsd = 9.75),
                RunnerEvent.TurnFailed(resumed.sessionId, resumed.turnId, exitCode = null, reason = "turn_timeout"),
            ),
            terminal,
        )
    }

    @Test
    fun `an idle process is left alone between turns, past both timeouts, and answers the next turn (m3-07)`() {
        val claude = FakeClaude(bin)
        val settings =
            ClaudeCodeSettings(command = claude.script.toString(), turnTimeout = 1.seconds, readTimeout = 1.seconds)

        // Idle for well over both timeouts; each is still long enough for the shell fake to answer a turn.
        val (first, second) = twoTurns(runner(claude, settings), "Fix it", "Keep going", pause = 2500.milliseconds)

        assertIs<RunnerEvent.TurnCompleted>(first.last())
        assertIs<RunnerEvent.TurnCompleted>(second.last())
        assertEquals(1, claude.invocations, "the idle process was killed and a new one spawned")
        assertEquals(listOf("Fix it", "Keep going"), claude.promptsOf(0))
        assertTrue(second.none { it is RunnerEvent.Output && it.line.startsWith("loop: ") }, "$second")
    }

    @Test
    fun `a turn timeout kills the long lived process, the next turn resumes the session in a new one with a warning`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        val resumed = Fixtures.lines("resumed").map { ProcessEvent.Line(Stream.Stdout, it) }
        mapOf(
            // Turn 2 writes its init line, then nothing.
            "turn_timeout" to listOf(success.first()),
            // Turn 2 writes nothing at all.
            "response_timeout" to emptyList(),
        ).forEach { (reason, silentTurn) ->
            val claude = Scripted(listOf(listOf(success, silentTurn), listOf(resumed)))
            val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 500.milliseconds, readTimeout = 300.milliseconds)
            val runner = scriptedRunner(claude, settings)

            val turns =
                runBlocking {
                    withTimeout(20.seconds) {
                        val session = checkNotNull(runner.openSession(context(emptyMap())))
                        try {
                            listOf("a", "b", "c").mapIndexed { index, input -> session.runTurn(input, index + 1).toList() }
                        } finally {
                            session.stop()
                        }
                    }
                }

            assertEquals(reason, assertIs<RunnerEvent.TurnFailed>(turns[1].last(), reason).reason, reason)
            assertEquals(2, claude.specs.size, reason)
            assertEquals(
                listOf("-p", "--resume", "claude-session-1"),
                claude.specs
                    .last()
                    .argv
                    .drop(1)
                    .take(3),
                reason,
            )
            val warning =
                "loop: warning: the claude-code process of this session is gone, turn 3 resumes session claude-session-1 in a new process"
            assertEquals(RunnerEvent.Output(Stream.Stderr, warning), turns[2][1], reason)
            assertIs<RunnerEvent.TurnCompleted>(turns[2].last(), reason)
            assertEquals(listOf(1334L, 617L, 99L, 17L), turns[2].filterIsInstance<RunnerEvent.Usage>().single().tokens(), reason)
        }
    }

    @Test
    fun `a cancelled turn kills the long lived process, the next turn resumes the session in a new one`() {
        val claude = FakeClaude(bin, nextFixture = "no_result")
        val runner = runner(claude)

        val third =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(emptyMap())))
                    try {
                        session.runTurn("a", 1).toList()
                        // The no_result transcript never ends turn 2, so it is cancelled once its first line is in.
                        session.runTurn("b", 2).takeWhile { it !is RunnerEvent.Output }.collect()
                        session.runTurn("c", 3).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertEquals(2, claude.invocations)
        // The cancelled turn's init gave the newest id.
        assertEquals(listOf("--resume", "claude-session-5"), claude.argvOf(1).drop(1).take(2))
        assertIs<RunnerEvent.TurnCompleted>(third.last())
        assertClaudeDead()
    }

    @Test
    fun `usage stays absolute for the session across the turns of one process (m3-07)`() {
        // The real recording: each result line carries its own turn's usage, total_cost_usd is the session's.
        val transcript = Fixtures.lines("real_two_turns_one_process")
        val (turn1, turn2, afterLast) = splitTurns(transcript)
        val claude = Scripted(listOf(listOf(turn1, turn2).map(::stdout)), atExit = stdout(afterLast))

        val (first, second) = twoTurns(scriptedRunner(claude), "Say hi", "Run a command")

        val one = first.filterIsInstance<RunnerEvent.Usage>().single()
        val two = second.filterIsInstance<RunnerEvent.Usage>().single()
        val results = transcript.mapNotNull { StreamJsonMapper.parse(it) as? StreamLine.Result }.map { checkNotNull(it.usage) }
        assertEquals(2, results.size, "a two turn transcript")
        assertEquals(results[0].tokens(), one.tokens())
        assertEquals(results[0].tokens().zip(results[1].tokens()) { a, b -> a + b }, two.tokens())
        assertEquals(results[0].totalCostUsd, one.totalCostUsd)
        assertEquals(results[1].totalCostUsd, two.totalCostUsd)
        assertTrue(two.tokens().zip(one.tokens()).all { (after, before) -> after > before }, "usage did not grow: $one then $two")
        assertEquals(1, claude.specs.size)
    }

    @Test
    fun `bl-71 a subagent's tokens are in the usage of its turn, and a resumed process adds its own segment`() {
        // Real recordings: a turn that ran one subagent, the process exits, then a --resume of that session.
        val claude = FakeClaude(bin, fixture = "real_subagent", after = "exit 0", resumeFixture = "real_subagent_resumed")

        val (first, second) = twoTurns(runner(claude), "Ask a subagent", "Say ok")

        assertEquals(2, claude.invocations)
        val one = first.filterIsInstance<RunnerEvent.Usage>().single()
        // The result's usage says 4, 308, 41,393 and 18,845: the main session only.
        assertEquals(listOf(1226L, 331L, 41393L, 38365L), one.tokens())
        assertEquals(0.006911029999999999, one.totalCostUsd)
        val two = second.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(listOf(1226L + 2, 331L + 4, 41393L + 30452, 38365L + 40), two.tokens())
        assertEquals(0.0072257499999999995, two.totalCostUsd)
    }

    @Test
    fun `the real two turn recording keeps the extra init line and the new line types inside their turns (m3-07)`() {
        val transcript = Fixtures.lines("real_two_turns_one_process")
        val (turn1, turn2, afterLast) = splitTurns(transcript)
        assertEquals(listOf("system task_summary"), afterLast.map(::kind), "one line after the last result")
        val claude = Scripted(listOf(listOf(turn1, turn2).map(::stdout)), atExit = stdout(afterLast))

        val (first, second) = twoTurns(scriptedRunner(claude), "Say hi", "Run a command")

        val started = assertIs<RunnerEvent.SessionStarted>(first.first())
        assertEquals("claude-session-real-2", started.runtimeSessionId)
        assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(1, first.count { it is RunnerEvent.SessionStarted })
        assertTrue(second.none { it is RunnerEvent.SessionStarted }, "the second init line starts no new session: $second")
        assertEquals(turn1, first.outputLines())
        assertEquals(turn2, second.outputLines())
        assertTrue("system init" in turn2.map(::kind) && "system task_summary" in turn2.map(::kind), turn2.map(::kind).toString())
        assertTrue("rate_limit_event" in turn1.map(::kind) && "system post_turn_summary" in turn1.map(::kind), turn1.map(::kind).toString())
        assertTrue(afterLast.single() !in (first + second).outputLines(), "the line after the last result belongs to no turn")
        assertEquals(0, assertIs<RunnerEvent.TurnCompleted>(first.last()).exitCode)
        assertEquals(0, assertIs<RunnerEvent.TurnCompleted>(second.last()).exitCode)
        assertEquals(1, claude.specs.size)
    }

    @Test
    fun `a timeout with no result line reports no usage (bl-37)`() {
        // An assistant line with usage gives an estimate, never a real usage report.
        val noResult = Fixtures.lines("background_wake").take(2).map { ProcessEvent.Line(Stream.Stdout, it) }
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 300.milliseconds)
        val launch = fakeLaunch(listOf(ProcessEvent.Started(1)) + noResult)
        val runner = ClaudeCodeRunner(settings, HOST_ENV, emptySet(), launch, groupAlive = NO_GROUP)

        val events = run(runner)

        assertTrue(events.none { it is RunnerEvent.Usage }, "$events")
        assertTrue(events.any { it is RunnerEvent.UsageEstimate }, "$events")
        assertEquals("turn_timeout", assertIs<RunnerEvent.TurnFailed>(events.last()).reason)
    }

    @Test
    fun `a result line's usage is emitted as soon as it is read, so a cancel after it keeps the spend (bl-108)`() {
        // m3-07: a result line ends its turn, so a result line then silence in one turn is a background task's woken
        // segment, written while the process was idle and read by the next turn. A stall or a loop stop cancels it.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3), emptyList())), idle = wake.drop(3))
        val events = ConcurrentLinkedQueue<RunnerEvent>()

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(scriptedRunner(claude).openSession(context(emptyMap())))
                try {
                    session.runTurn("Fix it", 1).toList()
                    delay(800)
                    val collection = launch { session.runTurn("Keep going", 2).collect { events += it } }
                    while (events.none { it is RunnerEvent.Usage }) delay(10)
                    collection.cancelAndJoin()
                } finally {
                    session.stop()
                }
            }
        }

        val started = assertIs<RunnerEvent.TurnStarted>(events.first())
        val result = Fixtures.lines("background_wake").last()
        val afterResult = events.dropWhile { it != RunnerEvent.Output(Stream.Stdout, result) }.drop(1)
        // The first segment, reported by turn 1, plus the woken one: the session total, the latest cost.
        assertEquals(
            listOf(RunnerEvent.Usage(started.sessionId, started.turnId, 1500, 49_500, 13_400_000, 189_000, totalCostUsd = 9.75)),
            afterResult,
        )
    }

    @Test
    fun `a cancelled turn's usage stays in the session, the next turn adds to it (bl-108)`() {
        // m3-07: turn 2 reads the woken segment written while the process was idle, then is cancelled; turn 3 resumes.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val resumed = Fixtures.lines("resumed").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3), emptyList()), listOf(resumed)), idle = wake.drop(3))

        val third =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(scriptedRunner(claude).openSession(context(emptyMap())))
                    try {
                        session.runTurn("Fix it", 1).toList()
                        delay(800)
                        val seen = ConcurrentLinkedQueue<RunnerEvent>()
                        val collection = launch { session.runTurn("Keep going", 2).collect { seen += it } }
                        while (seen.none { it is RunnerEvent.Usage }) delay(10)
                        collection.cancelAndJoin()
                        session.runTurn("Go on", 3).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val usage = third.filterIsInstance<RunnerEvent.Usage>().single()
        // Both segments of the cancelled process (1500, 49500, 13.4M, 189000) plus the resumed one (100, 50, 10, 5).
        assertEquals(
            listOf(1600L, 49_550L, 13_400_010L, 189_005L),
            listOf(usage.inputTokens, usage.outputTokens, usage.cacheReadInputTokens, usage.cacheCreationInputTokens),
        )
        assertEquals(2, claude.specs.size, "the cancel killed the first process")
        assertIs<RunnerEvent.TurnCompleted>(third.last())
    }

    @Test
    fun `the turn after a timed out turn adds its usage to the segment the timeout reported (bl-37)`() {
        // m3-07: the timed out turn read a woken segment written while the process was idle, then went silent.
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val init = ProcessEvent.Line(Stream.Stdout, Fixtures.lines("idle_next_turn").first())
        val resumed = Fixtures.lines("resumed").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3), listOf(init)), listOf(resumed)), idle = wake.drop(3))
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 500.milliseconds)

        val (second, third) =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(scriptedRunner(claude, settings).openSession(context(emptyMap())))
                    try {
                        session.runTurn("Fix it", 1).toList()
                        delay(800)
                        session.runTurn("Keep going", 2).toList() to session.runTurn("Go on", 3).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertEquals("turn_timeout", assertIs<RunnerEvent.TurnFailed>(second.last()).reason)
        assertEquals(listOf(1500L, 49_500L, 13_400_000L, 189_000L), second.filterIsInstance<RunnerEvent.Usage>().single().tokens())
        val usage = third.filterIsInstance<RunnerEvent.Usage>().single()
        // The segments the timeout reported plus the resumed process (100, 50, 10, 5).
        assertEquals(
            listOf(1600L, 49_550L, 13_400_010L, 189_005L),
            listOf(usage.inputTokens, usage.outputTokens, usage.cacheReadInputTokens, usage.cacheCreationInputTokens),
        )
        assertEquals(0.0521, assertNotNull(usage.totalCostUsd), 1e-9)
        assertEquals("--resume", claude.specs.last().argv[2])
        assertIs<RunnerEvent.TurnCompleted>(third.last())
    }

    @Test
    fun `a cancel before any result line has emitted no usage (bl-108)`() {
        val noResult = Fixtures.lines("background_wake").take(2).map { ProcessEvent.Line(Stream.Stdout, it) }
        val launch = fakeLaunch(listOf(ProcessEvent.Started(1)) + noResult)
        val runner = ClaudeCodeRunner(SETTINGS, HOST_ENV, emptySet(), launch, groupAlive = NO_GROUP)
        val events = ConcurrentLinkedQueue<RunnerEvent>()

        runBlocking {
            withTimeout(20.seconds) {
                val collection = launch { runner.run(context(emptyMap()), "Work on t-1").collect { events += it } }
                while (events.none { it is RunnerEvent.UsageEstimate }) delay(10)
                collection.cancelAndJoin()
            }
        }

        assertTrue(events.none { it is RunnerEvent.Usage }, "$events")
    }

    @Test
    fun `a settings write failure still deletes the settings folder`() {
        val claude = FakeClaude(bin)
        val dir = bin.resolve("settings").createDirectories()
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        val settings = ClaudeCodeSettings(command = claude.script.toString())
        val runner = ClaudeCodeRunner(settings, HOST_ENV, emptySet(), ProcessGroupLauncher()::launch) { dir }

        assertFails { run(runner) }

        assertFalse(dir.exists(), "settings folder left after a write failure")
    }

    @Test
    fun `a settings folder that cannot be deleted is reported on stderr before the terminal event`() {
        val claude = FakeClaude(bin, after = "chmod 500 \"\$(dirname \"\$settings\")\"")

        val events = run(runner(claude))

        val folder = Path.of(assertNotNull(claude.argAfter("--settings"))).parent
        try {
            val warning = RunnerEvent.Output(Stream.Stderr, "loop: could not delete the claude-code settings folder $folder")
            assertTrue(warning in events, "$events")
            assertIs<RunnerEvent.TurnCompleted>(events.last())
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no init within the read timeout kills claude and fails the turn with response_timeout`() {
        val claude = FakeClaude(bin, fixture = null, before = "exec sleep 30")
        val settings = ClaudeCodeSettings(command = claude.script.toString(), readTimeout = 500.milliseconds)

        val events = run(runner(claude, settings))

        assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("response_timeout", assertIs<RunnerEvent.TurnFailed>(events.last()).reason)
        assertClaudeDead()
    }

    @Test
    fun `no stdout line for the turn timeout kills claude and fails the turn with turn_timeout`() {
        val init = Fixtures.lines("success").first()
        val claude = FakeClaude(bin, fixture = null, before = "echo '$init'; exec sleep 30")
        val settings = ClaudeCodeSettings(command = claude.script.toString(), turnTimeout = 700.milliseconds)

        val events = run(runner(claude, settings))

        assertEquals("claude-session-1", assertIs<RunnerEvent.SessionStarted>(events.first()).runtimeSessionId)
        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals("turn_timeout", failed.reason)
        assertEquals(null, failed.exitCode)
        assertClaudeDead()
    }

    @Test
    fun `error and missing results fail the turn after SessionStarted`() {
        mapOf(
            "error_max_turns" to "turn_failed: error_max_turns",
            "no_result" to "port_exit: exit code 0, no result line",
        ).forEach { (fixture, reason) ->
            // The process exits after its transcript, so no_result ends in port_exit.
            val claude = FakeClaude(bin.resolve(fixture).createDirectories(), fixture = fixture, after = "exit 0")

            val events = run(runner(claude))

            assertIs<RunnerEvent.SessionStarted>(events.first(), fixture)
            assertEquals(reason, assertIs<RunnerEvent.TurnFailed>(events.last(), fixture).reason)
        }
    }

    @Test
    fun `bl-39 a 403 error result fails the turn as runner_unavailable, an ordinary one and a missing one keep no category`() {
        val blocked = FakeClaude(bin.resolve("unavailable_403").createDirectories(), fixture = "unavailable_403")

        val failed = assertIs<RunnerEvent.TurnFailed>(run(runner(blocked)).last())

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        listOf("error_max_turns", "no_result").forEach { fixture ->
            val claude = FakeClaude(bin.resolve("plain-$fixture").createDirectories(), fixture = fixture, after = "exit 0")

            assertEquals(null, assertIs<RunnerEvent.TurnFailed>(run(runner(claude)).last(), fixture).category, fixture)
        }
    }

    @Test
    fun `a denied tool outside the deny rules ends the turn with input required as its only terminal event`() {
        val claude = FakeClaude(bin, fixture = "input_required")

        val events = run(runner(claude))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals(RunnerEvent.TurnInputRequired(started.sessionId, started.turnId, reason = "WebFetch"), events.last())
        assertTrue(events.none { it is RunnerEvent.TurnFailed || it is RunnerEvent.TurnCompleted }, "$events")
    }

    @Test
    fun `every stdout line is kept as output, a malformed one included`() {
        val claude = FakeClaude(bin, fixture = "malformed_line", before = "echo oops >&2")

        val events = run(runner(claude))

        val stdout = events.filterIsInstance<RunnerEvent.Output>().filter { it.stream == RunnerEvent.Output.Stream.Stdout }
        assertEquals(Fixtures.lines("malformed_line"), stdout.map { it.line })
        assertTrue(RunnerEvent.Output(RunnerEvent.Output.Stream.Stderr, "oops") in events)
        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `cancelling the attempt deletes the settings file once claude is gone`() {
        // `before` runs once the fake has recorded its argv, which the settings check below reads.
        val claude = FakeClaude(bin, fixture = null, before = "touch ready; exec sleep 30")
        val ready = workspace.resolve("ready")

        runBlocking {
            withTimeout(20.seconds) {
                val collection = launch(Dispatchers.IO) { runner(claude).run(context(emptyMap()), "").collect() }
                while (!ready.exists()) delay(50)
                collection.cancelAndJoin()
            }
        }

        assertClaudeDead()
        assertFalse(Path.of(assertNotNull(claude.argAfter("--settings"))).exists(), "settings file left after cancel")
    }

    @Test
    fun `a continuation turn writes its guidance to the same process, a new user message line, no resume (m3-07)`() {
        val claude = FakeClaude(bin)

        val (first, second) = twoTurns(runner(claude), "Fix it", "Keep going\nand test it")

        assertEquals(1, claude.invocations)
        assertFalse("--resume" in claude.argvOf(0), "${claude.argvOf(0)}")
        assertEquals(listOf("Fix it", "Keep going\nand test it"), claude.promptsOf(0))
        assertEquals(2, claude.stdinLines.size, "one line per turn: ${claude.stdinLines}")
        val started = assertIs<RunnerEvent.SessionStarted>(first.first())
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(started.threadId, resumed.threadId)
        assertEquals(2, resumed.turn)
        // The env is the spawning turn's.
        assertEquals(started.sessionId.value, claude.env["LOOP_SESSION_ID"])
        assertIs<RunnerEvent.TurnCompleted>(second.last())
    }

    @Test
    fun `continuation resumes the runtime session`() {
        // m3-07: a process that died between turns is respawned with resume and a warning, the turn is not a failure.
        val claude = FakeClaude(bin, after = "exit 0")

        val (first, second) = twoTurns(runner(claude), "Fix it", "Keep going")

        assertEquals(2, claude.invocations)
        assertEquals(listOf("-p", "--resume", "claude-session-1"), claude.argvOf(1).take(3))
        assertEquals(listOf("Fix it"), claude.promptsOf(0))
        assertEquals(listOf("Keep going"), claude.promptsOf(1))
        val started = assertIs<RunnerEvent.SessionStarted>(first.first())
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(started.threadId, resumed.threadId)
        val warning =
            "loop: warning: the claude-code process of this session is gone, turn 2 resumes session claude-session-1 in a new process"
        assertEquals(RunnerEvent.Output(Stream.Stderr, warning), second[1])
        // A new process gets the ids of the turn that spawned it.
        assertEquals(resumed.sessionId.value, claude.env["LOOP_SESSION_ID"])
        assertEquals(resumed.turnId.value, claude.env["LOOP_TURN_ID"])
        assertIs<RunnerEvent.TurnCompleted>(second.last())
    }

    @Test
    fun `a resume after a turn woken by a background task adds to the summed usage`() {
        // m3-07: the process writes the woken segment while idle, then exits; turn 2 reads it, then resumes the session.
        val claude = FakeClaude(bin, fixture = "background_wake", after = "exit 0")

        val (_, second) = twoTurns(runner(claude), "Fix it", "Keep going", pause = 800.milliseconds)

        assertEquals(2, claude.invocations)
        assertEquals(listOf("-p", "--resume", "claude-wake-session"), claude.argvOf(1).take(3))
        // m4-03: each field of the sums again, the woken segment's then the resumed one's, and the latest cost.
        val reports = second.filterIsInstance<RunnerEvent.Usage>()
        assertEquals(
            listOf(listOf(1500L, 49_500L, 13_400_000L, 189_000L), listOf(1600L, 49_550L, 13_400_010L, 189_005L)),
            reports.map {
                it.tokens()
            },
        )
        assertEquals(listOf(9.75, 0.0521), reports.map { it.totalCostUsd })
        assertIs<RunnerEvent.TurnCompleted>(second.last())
    }

    @Test
    fun `usage accumulates across resumes, the cost is the session's latest`() {
        // m3-07: across the turns of one process and across a respawn with --resume.
        listOf("", "exit 0").forEach { after ->
            val claude = FakeClaude(bin.resolve("after-${after.length}").createDirectories(), after = after)

            val (first, second) = twoTurns(runner(claude), "Fix it", "Keep going")

            assertEquals(listOf(1234L, 567L, 89L, 12L), first.filterIsInstance<RunnerEvent.Usage>().single().tokens(), after)
            val two = second.filterIsInstance<RunnerEvent.Usage>().single()
            assertEquals(listOf(1334L, 617L, 99L, 17L), two.tokens(), after)
            // The second result's total_cost_usd already holds the first (0.0421 then 0.0521): never summed.
            assertEquals(0.0521, assertNotNull(two.totalCostUsd), 1e-9)
        }
    }

    @Test
    fun `stop closes stdin and the process exits, its settings folder deleted (m3-07)`() {
        val claude = FakeClaude(bin)
        val runner = runner(claude)

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                session.runTurn("a", 1).toList()
                assertTrue(
                    started.all { pid ->
                        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                    },
                    "the process lives after its turn",
                )
                session.stop()
            }
        }

        assertClaudeDead()
        assertFalse(Path.of(assertNotNull(claude.argAfter("--settings"))).exists(), "settings file left after stop")
    }

    @Test
    fun `stop kills a process that does not exit once its stdin is closed`() {
        val claude = FakeClaude(bin, after = "sleep 30")
        val runner = runner(claude, stopGrace = 300.milliseconds)

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                session.runTurn("a", 1).toList()
                session.stop()
            }
        }

        assertClaudeDead()
    }

    @Test
    fun `stop throws when the process group cannot be stopped`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        val stuck = "loop: processes still alive after SIGKILL in process group 7, known pids: [7]"
        mapOf(
            // Ignores the end of stdin and outlives the kill, as the group probe says.
            "probe" to Scripted(listOf(listOf(success)), ignoresEof = true),
            // Exits, but the launcher could not kill a member of its group.
            "launcher" to Scripted(listOf(listOf(success)), atExit = listOf(ProcessEvent.Line(Stream.Stderr, stuck))),
        ).forEach { (case, claude) ->
            val runner = scriptedRunner(claude, stopGrace = 300.milliseconds, groupAlive = { true })

            val error =
                runBlocking {
                    withTimeout(20.seconds) {
                        val session = checkNotNull(runner.openSession(context(emptyMap())))
                        session.runTurn("a", 1).toList()
                        assertFails { session.stop() }
                    }
                }

            val expected = if (case == "probe") "could not stop the claude-code process group 1000" else stuck.removePrefix("loop: ")
            assertEquals(expected, error.message, case)
        }
    }

    @Test
    fun `stop throws when a cancelled or timed out turn left members in the process group (m3-07)`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        listOf("cancelled", "timed out").forEach { case ->
            // Turn 2 writes its init line, then the test cancels it; or it writes nothing and the timeout kills it.
            val claude = Scripted(listOf(listOf(success, if (case == "cancelled") listOf(success.first()) else emptyList())))
            val settings = ClaudeCodeSettings(command = "claude", readTimeout = 300.milliseconds)
            val runner = scriptedRunner(claude, settings, groupAlive = { true })

            val error =
                runBlocking {
                    withTimeout(20.seconds) {
                        val session = checkNotNull(runner.openSession(context(emptyMap())))
                        session.runTurn("a", 1).toList()
                        if (case == "cancelled") {
                            session.runTurn("b", 2).takeWhile { it !is RunnerEvent.Output }.collect()
                        } else {
                            val failed = assertIs<RunnerEvent.TurnFailed>(session.runTurn("b", 2).toList().last())
                            assertEquals("response_timeout", failed.reason)
                        }
                        assertFails { session.stop() }
                    }
                }

            assertEquals("could not stop the claude-code process group 1000", error.message, case)
        }
    }

    @Test
    fun `stop succeeds once the group a cancelled turn left behind is gone (m3-07)`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(success, listOf(success.first()))))
        // Members are still there when the turn retires the process, gone by the time the session stops.
        val probes = ConcurrentLinkedQueue<Long>()
        val runner =
            scriptedRunner(claude, groupAlive = { pid ->
                probes += pid
                probes.size == 1
            })

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                session.runTurn("a", 1).toList()
                session.runTurn("b", 2).takeWhile { it !is RunnerEvent.Output }.collect()
                session.stop()
            }
        }

        assertEquals(listOf(1000L, 1000L), probes.toList())
    }

    @Test
    fun `stop deletes the settings folder when the launcher failed while the process was idle (m3-07)`() {
        val success = Fixtures.lines("success").map { ProcessEvent.Line(Stream.Stdout, it) }
        listOf(false, true).forEach { alive ->
            val dir = bin.resolve("settings-$alive").createDirectories()
            val launch: (LaunchSpec) -> Flow<ProcessEvent> = { spec ->
                flow {
                    emit(ProcessEvent.Started(7))
                    checkNotNull(spec.stdinLines).receive()
                    success.forEach { emit(it) }
                    delay(100)
                    throw IllegalStateException("the launcher broke")
                }
            }
            val runner =
                ClaudeCodeRunner(SETTINGS, HOST_ENV, emptySet(), launch, groupAlive = { alive }, newSettingsDir = { dir })

            val error =
                runBlocking {
                    withTimeout(20.seconds) {
                        val session = checkNotNull(runner.openSession(context(emptyMap())))
                        assertIs<RunnerEvent.TurnCompleted>(session.runTurn("a", 1).toList().last())
                        delay(300)
                        runCatching { session.stop() }.exceptionOrNull()
                    }
                }

            assertFalse(dir.exists(), "settings folder left, group alive: $alive")
            // The group probe decides, as for a process that did not exit in its grace.
            assertEquals(if (alive) "could not stop the claude-code process group 7" else null, error?.message, "group alive: $alive")
        }
    }

    @Test
    fun `assistant line usage feeds a running estimate by message id, without subagent lines, before the turn ends`() {
        val claude = FakeClaude(bin, fixture = "estimate")

        val events = run(runner(claude))

        val estimates = events.filterIsInstance<RunnerEvent.UsageEstimate>()
        // msg_1 twice (its second line replaces the first), the subagent line skipped, then msg_2 on top.
        assertEquals(listOf(4_200_150L, 4_200_180L, 10_200_210L), estimates.map { it.total() })
        assertEquals(
            listOf(110L, 100L, 10_000_000L, 200_000L),
            with(estimates.last()) { listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens) },
        )
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        estimates.forEach {
            assertEquals(started.sessionId, it.sessionId)
            assertEquals(started.turnId, it.turnId)
        }
        val crossing = events.indexOfFirst { it is RunnerEvent.UsageEstimate && it.total() >= 10_000_000 }
        assertTrue(crossing in 0 until events.indexOfFirst { it is RunnerEvent.Usage }, "$events")
        // The estimate follows the line it came from.
        val line = assertIs<RunnerEvent.Output>(events[crossing - 1])
        assertTrue("msg_2" in line.line, line.line)
        // The result line's usage is the turn's real total and replaces the estimate.
        val usage = events.filterIsInstance<RunnerEvent.Usage>().single()
        assertEquals(10_200_210L, usage.inputTokens + usage.outputTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens)
    }

    @Test
    fun `a resumed turn's estimate starts from the real totals of the earlier turns`() {
        val claude = FakeClaude(bin, fixture = "success", resumeFixture = "estimate")

        val (_, second) = twoTurns(runner(claude), "Fix it", "Keep going")

        // The success turn's result is 1234 + 567 + 89 + 12 = 1902 tokens.
        val estimates = second.filterIsInstance<RunnerEvent.UsageEstimate>()
        assertEquals(listOf(4_202_052L, 4_202_082L, 10_202_112L), estimates.map { it.total() })
    }

    @Test
    fun `a transcript without assistant usage gives no estimate`() {
        val events = run(runner(FakeClaude(bin, fixture = "resumed")))

        assertTrue(events.none { it is RunnerEvent.UsageEstimate }, "$events")
    }

    @Test
    fun `m3-08 the runner declares occupancy`() {
        assertTrue(runner(FakeClaude(bin)).capabilities.occupancy)
    }

    @Test
    fun `m3-08 each main session message reports its occupancy, input plus cache read plus cache creation, after its line`() {
        val claude = FakeClaude(bin, fixture = "success")

        val events = run(runner(claude))

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>()
        // msg_1: 10 + 1 + 2, msg_2: 20 + 3 + 0; output tokens never count.
        assertEquals(
            listOf(13L, 23L).map { RunnerEvent.ContextUsage(started.sessionId, started.turnId, it, model = "anthropic/claude-sonnet") },
            occupancy,
        )
        // Each follows its assistant line, after the line's estimate.
        occupancy.forEach { report ->
            val line = events.subList(0, events.indexOf(report)).last { it is RunnerEvent.Output }
            assertTrue("\"type\":\"assistant\"" in assertIs<RunnerEvent.Output>(line).line, "$line")
        }
        assertTrue(events.indexOf(occupancy.last()) < events.indexOfFirst { it is RunnerEvent.Usage }, "$events")
    }

    @Test
    fun `m3-08 the real two turn recording reports one occupancy per main session message, in the turn it belongs to`() {
        val transcript = Fixtures.lines("real_two_turns_one_process")
        val (turn1, turn2, afterLast) = splitTurns(transcript)
        val claude = Scripted(listOf(listOf(turn1, turn2).map(::stdout)), atExit = stdout(afterLast))

        val (first, second) = twoTurns(scriptedRunner(claude), "Say hi", "Run a command")

        // 2 + 24,367 + 18,933, then 2 + 27,830 + 15,519 and 2 + 43,349 + 104, from each message's usage.
        assertEquals(listOf(43_302L), first.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens })
        assertEquals(listOf(43_351L, 43_455L), second.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens })
        val turnStarted = assertIs<RunnerEvent.TurnStarted>(second.first())
        (first + second).filterIsInstance<RunnerEvent.ContextUsage>().forEach {
            assertEquals("anthropic/claude-haiku-5-5", it.model, "the model of the init line")
        }
        second.filterIsInstance<RunnerEvent.ContextUsage>().forEach { assertEquals(turnStarted.turnId, it.turnId) }
    }

    @Test
    fun `m3-08 a subagent message adds no occupancy`() {
        val events = run(runner(FakeClaude(bin, fixture = "estimate")))

        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens }
        // msg_sub_1 has a parent_tool_use_id: its 50,000,005 tokens fill the subagent's own window, not the session's.
        assertTrue(50_000_005L !in occupancy, "$occupancy")
        assertEquals(listOf(4_200_100L, 6_000_010L), occupancy)
    }

    @Test
    fun `m3-08 repeated lines of one message id add no occupancy`() {
        val events = run(runner(FakeClaude(bin, fixture = "estimate")))

        // msg_1 comes on two lines, one per content block, with the same usage: it counts once.
        val lines = events.filterIsInstance<RunnerEvent.Output>().count { "\"id\":\"msg_1\"" in it.line }
        assertEquals(2, lines)
        assertEquals(1, events.filterIsInstance<RunnerEvent.ContextUsage>().count { it.occupancyTokens == 4_200_100L })
    }

    @Test
    fun `m3-08 a wake's message read by the next turn reports its occupancy in that turn`() {
        val wake = Fixtures.lines("background_wake").map { ProcessEvent.Line(Stream.Stdout, it) }
        val next = Fixtures.lines("idle_next_turn").map { ProcessEvent.Line(Stream.Stdout, it) }
        val claude = Scripted(listOf(listOf(wake.take(3), next)), idle = wake.drop(3))

        val (first, second) = twoTurns(scriptedRunner(claude), "Fix it", "Keep going", pause = 300.milliseconds)

        // msg_1: 1,000 + 11,500,000 + 149,000; the wake's msg_2: 500 + 1,900,000 + 40,000.
        assertEquals(listOf(11_650_000L), first.filterIsInstance<RunnerEvent.ContextUsage>().map { it.occupancyTokens })
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(
            listOf(RunnerEvent.ContextUsage(resumed.sessionId, resumed.turnId, 1_940_500L, model = "anthropic/claude-sonnet")),
            second.filterIsInstance<RunnerEvent.ContextUsage>(),
        )
    }

    @Test
    fun `m3-08 a session whose init names no model reports occupancy without a model`() {
        val success = Fixtures.lines("success")
        val noModel = listOf(success.first().replace(",\"model\":\"claude-sonnet\"", "")) + success.drop(1)
        assertTrue(noModel.first().contains("init") && !noModel.first().contains("model"), noModel.first())
        val claude = Scripted(listOf(listOf(stdout(noModel))))

        val events = run(scriptedRunner(claude))

        val occupancy = events.filterIsInstance<RunnerEvent.ContextUsage>()
        assertEquals(listOf(13L, 23L), occupancy.map { it.occupancyTokens })
        assertTrue(occupancy.all { it.model == null }, "$occupancy")
    }

    /** An event that reports progress inside a turn: an output line, an estimate or an occupancy. */
    private fun RunnerEvent.isProgress(): Boolean =
        this is RunnerEvent.Output || this is RunnerEvent.UsageEstimate || this is RunnerEvent.ContextUsage

    private fun RunnerEvent.UsageEstimate.total(): Long = inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens

    @Test
    fun `the newest init wins, the result line is the fallback when init has no session id`() {
        // Each process dies after its turn, so each turn resumes the newest id.
        val claude = FakeClaude(bin, fixture = "no_init", resumeFixture = "no_init", after = "exit 0")
        val runner = runner(claude)

        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                session.runTurn("a", 1).toList()
                session.runTurn("b", 2).toList()
                session.runTurn("c", 3).toList()
                session.stop()
            }
        }

        assertEquals(listOf("--resume", "claude-session-9"), claude.argvOf(2).drop(1).take(2))
    }

    @Test
    fun `missing runtime session id fails the continuation turn`() {
        // m3-07: only a continuation turn that needs a new process resumes, so only that one can miss the id.
        val claude = FakeClaude(bin, fixture = "no_session_id", after = "exit 0")

        val (first, second) = twoTurns(runner(claude), "Fix it", "Keep going")

        assertIs<RunnerEvent.TurnCompleted>(first.last())
        assertEquals(1, claude.invocations, "a second claude was started without a session to resume")
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        val failed = assertIs<RunnerEvent.TurnFailed>(second.last())
        assertEquals(resumed.turnId, failed.turnId)
        assertTrue(failed.reason.startsWith("missing_runtime_session_id"), failed.reason)
        assertEquals(2, second.size, "$second")
    }

    @Test
    fun `an invocation that reports no session id clears the stored one`() {
        val claude = FakeClaude(bin, fixture = "success", resumeFixture = "no_session_id", after = "exit 0")
        val runner = runner(claude)

        val third =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner.openSession(context(emptyMap())))
                    session.runTurn("a", 1).toList()
                    session.runTurn("b", 2).toList()
                    session.runTurn("c", 3).toList().also { session.stop() }
                }
            }

        assertEquals(2, claude.invocations, "the third turn resumed an older session")
        val failed = assertIs<RunnerEvent.TurnFailed>(third.last())
        assertTrue(failed.reason.startsWith("missing_runtime_session_id"), failed.reason)
    }

    /** Two turns of one session, with [pause] between them, then stop. */
    private fun twoTurns(
        runner: ClaudeCodeRunner,
        first: String,
        second: String,
        pause: Duration = Duration.ZERO,
    ): Pair<List<RunnerEvent>, List<RunnerEvent>> =
        runBlocking {
            withTimeout(20.seconds) {
                val session = checkNotNull(runner.openSession(context(emptyMap())))
                try {
                    val one = session.runTurn(first, 1).toList()
                    delay(pause)
                    one to session.runTurn(second, 2).toList()
                } finally {
                    session.stop()
                }
            }
        }

    /** The [n]th message of a background subagent, as Claude Code writes it while the main session is idle. */
    private fun subagentLine(n: Int): String =
        """{"type":"assistant","message":{"id":"msg_sub_$n","type":"message","role":"assistant","content":[{"type":"text",""" +
            """"text":"step $n"}],"usage":{"input_tokens":3,"output_tokens":7,"cache_read_input_tokens":900,""" +
            """"cache_creation_input_tokens":0}},"parent_tool_use_id":"toolu_agent","session_id":"claude-wake-session"}"""

    private fun RunnerEvent.Usage.tokens(): List<Long> = listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    private fun StreamLine.UsageTotals.tokens(): List<Long> =
        listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    /** A recorded stdout of one process split into its turns, each ending with its result line. */
    private fun stdout(lines: List<String>): List<ProcessEvent> = lines.map { ProcessEvent.Line(Stream.Stdout, it) }

    private fun List<RunnerEvent>.outputLines(): List<String> =
        filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stdout }.map { it.line }

    /** A stream-json line's `type`, then its `subtype` when it has one. */
    private fun kind(line: String): String {
        val event = Json.parseToJsonElement(line).jsonObject
        return listOfNotNull(event["type"], event["subtype"]).joinToString(" ") { it.jsonPrimitive.content }
    }

    private fun splitTurns(lines: List<String>): List<List<String>> {
        val turns = mutableListOf(mutableListOf<String>())
        lines.forEach { line ->
            turns.last() += line
            if (StreamJsonMapper.parse(line) is StreamLine.Result) turns += mutableListOf<String>()
        }
        return turns.filter { it.isNotEmpty() }
    }

    /**
     * A scripted launcher: spawn i answers its n-th stdin line with `spawns[i][n]`, then [idle] after its first turn,
     * as a process that writes between turns, and [idleLate] [idleLateDelay] later, before it reads its next stdin
     * line. Once stdin is closed it writes [atExit] and exits 0, unless [ignoresEof], then it runs until it is
     * cancelled. Every spawn's spec is kept in [specs], every stdin line it read in [inputs]; spawn i has pid 1000 + i.
     */
    private class Scripted(
        private val spawns: List<List<List<ProcessEvent>>>,
        private val idle: List<ProcessEvent> = emptyList(),
        private val atExit: List<ProcessEvent> = emptyList(),
        private val ignoresEof: Boolean = false,
        private val idleLate: List<ProcessEvent> = emptyList(),
        private val idleLateDelay: Duration = Duration.ZERO,
    ) {
        val specs = ConcurrentLinkedQueue<LaunchSpec>()
        val inputs = ConcurrentLinkedQueue<String>()

        val launch: (LaunchSpec) -> Flow<ProcessEvent> = { spec ->
            val index = specs.size
            specs += spec
            flow {
                emit(ProcessEvent.Started(1000L + index))
                val turns = spawns.getOrElse(index) { emptyList() }
                var turn = 0
                for (line in checkNotNull(spec.stdinLines)) {
                    inputs += line
                    turns.getOrNull(turn)?.forEach { emit(it) }
                    // Written once the first turn has ended, while the process is idle.
                    if (turn == 0 && idle.isNotEmpty()) {
                        delay(100)
                        idle.forEach { emit(it) }
                    }
                    if (turn == 0 && idleLate.isNotEmpty()) {
                        delay(idleLateDelay)
                        idleLate.forEach { emit(it) }
                    }
                    turn++
                }
                if (ignoresEof) awaitCancellation()
                atExit.forEach { emit(it) }
                emit(ProcessEvent.Exited(0))
            }
        }
    }

    /**
     * A launcher for the m3-12 interrupt tests. It writes [first] for the first input line, then [idle] while the
     * process is idle, and [answer] for each stream-json interrupt request it reads; an empty [answer] ignores the
     * request. It records its stdin in [inputs], and [killed] once its group is stopped, that is its flow cancelled.
     * Once stdin closes it waits [EXIT_DELAY] before it exits: the runner closes stdin right before it kills the group,
     * and an exit at once would win that race, so the kill would go unseen.
     */
    private class Interruptible(
        private val first: List<String>,
        private val idle: List<String> = emptyList(),
        private val answer: List<String>,
    ) {
        val inputs = ConcurrentLinkedQueue<String>()

        @Volatile var killed = false

        val launch: (LaunchSpec) -> Flow<ProcessEvent> = { spec ->
            flow {
                emit(ProcessEvent.Started(2000L))
                try {
                    var turn = 0
                    for (line in checkNotNull(spec.stdinLines)) {
                        inputs += line
                        if (INTERRUPT in line) {
                            answer.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                        } else if (turn++ == 0) {
                            first.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                            delay(100)
                            idle.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                        }
                    }
                    delay(EXIT_DELAY)
                    emit(ProcessEvent.Exited(0))
                } catch (e: CancellationException) {
                    killed = true
                    throw e
                }
            }
        }

        private companion object {
            val EXIT_DELAY = 1.seconds
        }
    }

    private fun scriptedRunner(
        claude: Scripted,
        settings: ClaudeCodeSettings = ClaudeCodeSettings(command = "claude"),
        stopGrace: Duration = 5.seconds,
        groupAlive: (Long) -> Boolean = { false },
    ): ClaudeCodeRunner = ClaudeCodeRunner(settings, HOST_ENV, emptySet(), claude.launch, stopGrace = stopGrace, groupAlive = groupAlive)

    /**
     * Asserts no claude process spawned by [runner] is still alive. The pids are recorded at spawn,
     * not read from a file the fake writes: a timeout can kill the fake before its first line runs.
     */
    private fun assertClaudeDead() {
        started.forEach { pid ->
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "claude $pid still alive")
        }
    }

    private fun runner(
        claude: FakeClaude,
        settings: ClaudeCodeSettings = ClaudeCodeSettings(command = claude.script.toString()),
        hostEnv: Map<String, String> = HOST_ENV,
        removedEnvNames: Set<String> = emptySet(),
        addedEnv: Map<String, String> = emptyMap(),
        stopGrace: Duration = 5.seconds,
    ): ClaudeCodeRunner =
        ClaudeCodeRunner(
            settings,
            hostEnv,
            removedEnvNames,
            ProcessGroupLauncher(start = { builder -> builder.start().also { started += it.pid() } })::launch,
            addedEnv,
            stopGrace = stopGrace,
        )

    /** A launcher that replays [script], then keeps the process open until it is cancelled. */
    private fun fakeLaunch(script: List<ProcessEvent>): (LaunchSpec) -> Flow<ProcessEvent> =
        {
            flow {
                script.forEach { emit(it) }
                awaitCancellation()
            }
        }

    private fun run(
        runner: ClaudeCodeRunner,
        env: Map<String, String> = emptyMap(),
        prompt: String = "Work on t-1",
        workspace: Path = this.workspace,
    ): List<RunnerEvent> = runBlocking { withTimeout(20.seconds) { runner.run(context(env, workspace), prompt).toList() } }

    private fun context(
        env: Map<String, String>,
        workspace: Path = this.workspace,
    ): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "t-1",
                    identifier = "t-1",
                    title = "Claude",
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

    private companion object {
        /** A host env the runner inherits: the real PATH, so sh tools resolve, and HOME. */
        val HOST_ENV: Map<String, String> =
            mapOf("PATH" to System.getenv("PATH"), "HOME" to System.getProperty("user.home"))

        /** The group probe for a fake launch, whose pids belong to no group of its own: nothing is left after a kill. */
        val NO_GROUP: (Long) -> Boolean = { false }

        /** Settings for a fake launch, whose turn timeout no test reaches. */
        val SETTINGS = ClaudeCodeSettings(command = "claude")

        /** What a stream-json interrupt request holds, as the runner writes it (m3-12). */
        const val INTERRUPT = "\"subtype\":\"interrupt\""

        /** The result line Claude Code 2.1.319 wrote after an interrupt request, trimmed, its cost made 0.42 (m3-12). */
        const val INTERRUPTED_RESULT =
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"claude-wake-session",""" +
                """"total_cost_usd":0.42,"usage":{"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0,""" +
                """"cache_creation_input_tokens":0},"model_usage_since_last_result":{"claude-haiku-5-5":{"inputTokens":1201,""" +
                """"outputTokens":16,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"costUSD":0.42}},""" +
                """"terminal_reason":"aborted_streaming","permission_denials":[]}"""
    }
}
