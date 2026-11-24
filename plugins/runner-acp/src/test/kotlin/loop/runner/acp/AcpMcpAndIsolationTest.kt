package loop.runner.acp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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

class AcpMcpAndIsolationTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    @Test
    fun `AcpMcpConfig passes loop tool mcp with all 8 Loop env vars and excludes secrets`() {
        val env =
            mapOf(
                "LOOP_LAUNCHER" to "/opt/loop/bin/loop",
                "LOOP_TOOL_CONFIG" to "/tmp/tool-config.json",
                "LOOP_ATTEMPT_ID" to "attempt-42",
                "LOOP_ISSUE_ID" to "issue-99",
                "LOOP_ISSUE_IDENTIFIER" to "m4-99",
                "LOOP_TRACKER_ID" to "primary",
                "LOOP_HOME" to "/tmp/loop-home",
                "LOOP_SESSION_ID" to "thread-1-turn-1",
                "LOOP_TURN_ID" to "turn-1",
                "GH_TOKEN" to "ghp_secret",
                "GEMINI_API_KEY" to "AIzaSecret",
            )

        val servers = AcpMcpConfig.mcpServers(env)

        assertEquals(1, servers.size)
        val server = servers[0].jsonObject
        assertEquals("loop", server.string("name"))
        assertEquals("/opt/loop/bin/loop", server.string("command"))
        assertEquals(listOf("tool", "mcp"), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val passedEnv =
            server["env"]!!.jsonArray.associate { entry ->
                val obj = entry.jsonObject
                obj.string("name") to obj.string("value")
            }
        assertEquals(AcpMcpConfig.PASSED.toSet(), passedEnv.keys)
        assertEquals("/tmp/tool-config.json", passedEnv["LOOP_TOOL_CONFIG"])
        assertEquals("thread-1-turn-1", passedEnv["LOOP_SESSION_ID"])
        assertEquals("turn-1", passedEnv["LOOP_TURN_ID"])
        assertFalse("GH_TOKEN" in passedEnv)
        assertFalse("GEMINI_API_KEY" in passedEnv)
    }

    @Test
    fun `AcpMcpConfig returns empty array when LOOP_LAUNCHER or LOOP_TOOL_CONFIG is missing`() {
        assertTrue(AcpMcpConfig.mcpServers(emptyMap()).isEmpty())
        assertTrue(AcpMcpConfig.mcpServers(mapOf("LOOP_LAUNCHER" to "/opt/loop/bin/loop")).isEmpty())
        assertTrue(AcpMcpConfig.mcpServers(mapOf("LOOP_TOOL_CONFIG" to "/tmp/tool.json")).isEmpty())
        assertTrue(AcpMcpConfig.mcpServers(mapOf("LOOP_LAUNCHER" to " ", "LOOP_TOOL_CONFIG" to "/tmp/tool.json")).isEmpty())
    }

    @Test
    fun `AgyIsolationDir uses LOOP_HOME when under HOME and falls back to dot loop-agy when outside HOME`() {
        val insideLoopHome = home.resolve(".loop")
        val inside =
            AgyIsolationDir.create(
                homeDir = home,
                loopHome = insideLoopHome,
                projectId = "proj-1",
                attemptId = "att-1",
                uniqueId = "abcd1234",
            )
        assertEquals(".loop/attempts/proj-1/att-1/agy-gemini-abcd1234", inside.relativePath)
        assertFalse(Path.of(inside.relativePath).isAbsolute)
        val settingsJson =
            AcpFraming.json
                .parseToJsonElement(inside.path.resolve("antigravity-cli/settings.json").readText())
                .jsonObject
        assertEquals("strict", settingsJson.string("toolPermission"))
        assertFalse(settingsJson["allowNonWorkspaceAccess"]!!.jsonPrimitive.boolean)
        assertNull(inside.delete())
        assertFalse(inside.path.exists())

        val outsideLoopHome = dir.resolve("external-loop-home")
        val fallback =
            AgyIsolationDir.create(
                homeDir = home,
                loopHome = outsideLoopHome,
                projectId = "proj-1",
                attemptId = "att-2",
                uniqueId = "ef567890",
            )
        assertEquals(".loop-agy/att-2-ef567890", fallback.relativePath)
        assertTrue(fallback.path.resolve("antigravity-cli/settings.json").exists())
        assertNull(fallback.delete())
        assertFalse(fallback.path.exists())
        assertFalse(home.resolve(".loop-agy").exists())
    }

    @Test
    fun `AgyIsolationDir sweepStale removes dead-PID and pidless fallback directories while keeping live-PID ones`() {
        val fallbackRoot = home.resolve(AgyIsolationDir.FALLBACK_ROOT_NAME).createDirectories()
        val liveDir = fallbackRoot.resolve("att-live-11111111").createDirectories()
        liveDir.resolve(".loop-pid").writeText("424242")
        val deadDir = fallbackRoot.resolve("att-dead-22222222").createDirectories()
        deadDir.resolve(".loop-pid").writeText("999999")
        val pidlessDir = fallbackRoot.resolve("att-orphan-33333333").createDirectories()
        pidlessDir.resolve("antigravity-cli").createDirectories()

        val errors = AgyIsolationDir.sweepStale(home) { pid -> pid == 424242L }

        assertTrue(errors.isEmpty(), "$errors")
        assertTrue(liveDir.exists(), "live session directory must stay")
        assertFalse(deadDir.exists(), "dead-PID directory must be swept")
        assertFalse(pidlessDir.exists(), "pidless directory must be swept")
        assertTrue(fallbackRoot.exists(), "parent stays while live dir remains")

        // Once the live PID is gone, sweep removes it and the empty .loop-agy root.
        val errors2 = AgyIsolationDir.sweepStale(home) { false }
        assertTrue(errors2.isEmpty(), "$errors2")
        assertFalse(liveDir.exists())
        assertFalse(fallbackRoot.exists())
    }

    @Test
    fun `agy preset passes disable-telemetry, sandbox, and isolated relative gemini-dir and cleans up in stop`() {
        val capturedSettings = dir.resolve("captured-settings.json")
        val capturedArgs = dir.resolve("captured-args.txt")
        val server =
            FakeAcpServer.write(dir) {
                shell(
                    """
                    printf '%s\n' "$@" > '$capturedArgs'
                    gdir=""
                    while [ $# -gt 0 ]; do
                      if [ "$1" = "--gemini-dir" ]; then gdir="$2"; shift; fi
                      shift
                    done
                    cat "${'$'}HOME/${'$'}gdir/antigravity-cli/settings.json" > '$capturedSettings'
                    """.trimIndent(),
                )
                agyHandshake(sessionId = "agy-iso-1")
            }
        val launched = mutableListOf<LaunchSpec>()
        val launcher = ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)
        val loopHome = home.resolve(".loop")
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = FakeAcpServer.DEFAULT_MODEL,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                        strictMcp = true,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString(), "LOOP_HOME" to loopHome.toString()),
                removedEnvNames = emptySet(),
                addedEnv = emptyMap(),
                launchProcess = { spec ->
                    launched += spec
                    launcher.launch(spec)
                },
            )

        val events = runBlocking { withTimeout(20.seconds) { runner.run(context(loopEnv(loopHome)), "Work").toList() } }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val argv = launched.single().argv
        assertEquals(server.script.toString(), argv[0])
        assertEquals("acp", argv[1])
        assertTrue("--disable-telemetry" in argv, "$argv")
        assertTrue("--sandbox" in argv, "$argv")
        assertFalse("--dangerously-skip-permissions" in argv, "$argv")
        assertFalse("--effort" in argv, "$argv")
        assertFalse("--remote-agent" in argv, "$argv")
        assertFalse("--remote-control" in argv, "$argv")

        val geminiDirIdx = argv.indexOf("--gemini-dir")
        assertTrue(geminiDirIdx >= 0 && geminiDirIdx + 1 < argv.size, "$argv")
        val relGeminiDir = argv[geminiDirIdx + 1]
        assertFalse(relGeminiDir.startsWith("/"), relGeminiDir)
        assertFalse(relGeminiDir.startsWith(".."), relGeminiDir)
        assertTrue(relGeminiDir.startsWith(".loop/attempts/project-1/attempt-1/agy-gemini-"), relGeminiDir)

        // The settings file existed with strict permissions while agy ran, and was deleted on stop().
        val settingsObj = AcpFraming.json.parseToJsonElement(capturedSettings.readText()).jsonObject
        assertEquals("strict", settingsObj.string("toolPermission"))
        assertFalse(settingsObj["allowNonWorkspaceAccess"]!!.jsonPrimitive.boolean)
        assertFalse(Files.exists(home.resolve(relGeminiDir)))

        // session/new received Loop's MCP server with all 8 PASSED env vars including turn 1's ids.
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val sent = received(server)
        val newParams = sent[1]["params"]!!.jsonObject
        val mcpServers = newParams["mcpServers"]!!.jsonArray
        assertEquals(1, mcpServers.size)
        val loopServer = mcpServers[0].jsonObject
        assertEquals("loop", loopServer.string("name"))
        assertEquals("/opt/loop/bin/loop", loopServer.string("command"))
        assertEquals(listOf("tool", "mcp"), loopServer["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val mcpEnv = loopServer["env"]!!.jsonArray.associate { it.jsonObject.string("name") to it.jsonObject.string("value") }
        assertEquals(AcpMcpConfig.PASSED.toSet(), mcpEnv.keys)
        assertEquals(started.sessionId.value, mcpEnv["LOOP_SESSION_ID"])
        assertEquals(started.turnId.value, mcpEnv["LOOP_TURN_ID"])
    }

    @Test
    fun `agy preset sweeps stale fallback dirs on spawn and cleans up fallback dir on turn failure`() {
        val stale = home.resolve(".loop-agy/stale-dead-00000000").createDirectories()
        stale.resolve(".loop-pid").writeText("99999999")
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "agy-fail-42")
                awaitClient()
                error(id = 3, code = -32602, message = "Unknown model ID")
                shell("sleep 30")
            }
        val launched = mutableListOf<LaunchSpec>()
        val warnings = mutableListOf<String>()
        val launcher = ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.agyCommand,
                        preset = AcpSettings.PRESET_AGY,
                        model = FakeAcpServer.DEFAULT_MODEL,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                        strictMcp = true,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
                removedEnvNames = emptySet(),
                addedEnv = emptyMap(),
                launchProcess = { spec ->
                    launched += spec
                    launcher.launch(spec)
                },
                onWarn = { warnings += it },
            )

        val events =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = runner.openSession(context(loopEnv(dir.resolve("outside-loop-home"))))
                    try {
                        session.runTurn("Work", 1).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertIs<RunnerEvent.TurnFailed>(events.last())
        assertFalse(stale.exists(), "stale fallback dir must be swept on spawn")
        assertTrue(warnings.isEmpty(), "$warnings")

        val argv = launched.single().argv
        val relGeminiDir = argv[argv.indexOf("--gemini-dir") + 1]
        assertTrue(relGeminiDir.startsWith(".loop-agy/"), relGeminiDir)
        assertFalse(Files.exists(home.resolve(relGeminiDir)))
        assertFalse(Files.exists(home.resolve(".loop-agy")))
    }

    @Test
    fun `generic ACP resumeSession passes Loop MCP server in session load`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionLoad(id = 2, sessionId = "prev-sess-42")
                promptTurn(id = 3, stopReason = "end_turn")
            }
        val runner =
            AcpRunner(
                settings =
                    AcpSettings(
                        command = server.command,
                        strictMcp = false,
                        readTimeout = FakeAcpServer.READ_TIMEOUT,
                    ),
                hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
                removedEnvNames = emptySet(),
                addedEnv = emptyMap(),
            )

        val events =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = assertNotNull(runner.resumeSession(context(loopEnv(home.resolve(".loop"))), "prev-sess-42"))
                    try {
                        session.runTurn("Resume", 1).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val sent = received(server)
        val loadParams = sent[1]["params"]!!.jsonObject
        val mcpServers = loadParams["mcpServers"]!!.jsonArray
        assertEquals(1, mcpServers.size)
        val mcpEnv =
            mcpServers[0]
                .jsonObject["env"]!!
                .jsonArray
                .associate { it.jsonObject.string("name") to it.jsonObject.string("value") }
        assertEquals(AcpMcpConfig.PASSED.toSet(), mcpEnv.keys)
        assertEquals(started.sessionId.value, mcpEnv["LOOP_SESSION_ID"])
        assertEquals(started.turnId.value, mcpEnv["LOOP_TURN_ID"])
    }

    private fun loopEnv(loopHome: Path): Map<String, String> =
        mapOf(
            "LOOP_LAUNCHER" to "/opt/loop/bin/loop",
            "LOOP_TOOL_CONFIG" to "/tmp/tool-config.json",
            "LOOP_ATTEMPT_ID" to "attempt-1",
            "LOOP_ISSUE_ID" to "issue-1",
            "LOOP_ISSUE_IDENTIFIER" to "acp-04",
            "LOOP_TRACKER_ID" to "primary",
            "LOOP_HOME" to loopHome.toString(),
        )

    private fun received(server: FakeAcpServer): List<JsonObject> =
        server.received
            .readLines()
            .filter { it.isNotBlank() }
            .map { AcpFraming.json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun context(env: Map<String, String> = emptyMap()): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-04",
                    title = "MCP and isolation",
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
}
