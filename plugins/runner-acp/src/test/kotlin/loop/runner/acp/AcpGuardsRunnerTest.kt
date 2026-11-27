package loop.runner.acp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AcpGuardsRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var holdoutDir: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private fun options() =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("optionId", "allow_once")
                    put("kind", "allow_once")
                },
            )
            add(
                buildJsonObject {
                    put("optionId", "reject_once")
                    put("kind", "reject_once")
                },
            )
        }

    @Test
    fun `answers mid-turn permission requests using shared guards and completes the turn`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-guards")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-guards")
                awaitClient()
                // 1. Safe read_file -> allow_once
                request(
                    id = 101,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-guards")
                            put(
                                "toolCall",
                                buildJsonObject {
                                    put("name", "read_file")
                                    put("kind", "read")
                                    put("rawInput", buildJsonObject { put("Path", workspace.resolve("a.kt").toString()) })
                                },
                            )
                            put("options", options())
                        },
                )
                awaitClient()
                // 2. Holdout read_file -> reject_once
                request(
                    id = 102,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-guards")
                            put(
                                "toolCall",
                                buildJsonObject {
                                    put("name", "read_file")
                                    put("kind", "read")
                                    put("rawInput", buildJsonObject { put("Path", holdoutDir.resolve("hidden.sh").toString()) })
                                },
                            )
                            put("options", options())
                        },
                )
                awaitClient()
                // 3. Denied git push -> reject_once
                request(
                    id = 103,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-guards")
                            put(
                                "toolCall",
                                buildJsonObject {
                                    put("name", "run_command")
                                    put("kind", "execute")
                                    put("rawInput", buildJsonObject { put("CommandLine", "git push origin main") })
                                },
                            )
                            put("options", options())
                        },
                )
                awaitClient()
                // 4. Loop MCP tool -> allow_once
                request(
                    id = 104,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-guards")
                            put("toolCall", buildJsonObject { put("name", "mcp_loop_move") })
                            put("options", options())
                        },
                )
                awaitClient()
                // 5. Non-Loop MCP tool under strict_mcp -> reject_once
                request(
                    id = 105,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-guards")
                            put("toolCall", buildJsonObject { put("name", "mcp_other_server_run") })
                            put("options", options())
                        },
                )
                awaitClient()
                response(id = 4, result = buildJsonObject { put("stopReason", "end_turn") })
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val replies =
            received(server)
                .filter { "result" in it }
                .associate {
                    it["id"]!!.jsonPrimitive.content to
                        it["result"]!!
                            .jsonObject["outcome"]!!
                            .jsonObject["optionId"]!!
                            .jsonPrimitive.content
                }
        assertEquals(
            mapOf(
                "101" to "allow_once",
                "102" to "reject_once",
                "103" to "reject_once",
                "104" to "allow_once",
                "105" to "reject_once",
            ),
            replies,
        )
    }

    @Test
    fun `human-input prompt ends turn at once with TurnInputRequired and stops the session`() {
        val server =
            FakeAcpServer.write(dir) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-input")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-input")
                awaitClient()
                request(
                    id = 201,
                    method = "session/request_user_input",
                    params = buildJsonObject { put("prompt", "Which database?") },
                )
                shell("sleep 30")
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val terminal =
            events.filter {
                it is RunnerEvent.TurnCompleted ||
                    it is RunnerEvent.TurnFailed ||
                    it is RunnerEvent.TurnInputRequired
            }
        val inputRequired = assertIs<RunnerEvent.TurnInputRequired>(terminal.single())
        assertEquals("session/request_user_input", inputRequired.reason)
    }

    @Test
    fun `pre-session protected roots check fails before spawning when workspace customization file is added`() {
        workspace.resolve(".agents").createDirectories()
        workspace.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        val marker = dir.resolve("spawned.txt")
        val server =
            FakeAcpServer.write(dir) {
                shell("echo spawned > '$marker'")
                agyHandshake()
            }

        val events = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(context(), "Work").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertNull(failed.category)
        assertTrue(".agents/hooks.json" in failed.reason, failed.reason)
        assertFalse(marker.exists())
    }

    @Test
    fun `bl-254 the runner hands the context's clone mode to the protected roots check, the default keeps today's check`() {
        // A workspace with no .git: in clone mode a refusal before any spawn, in hooks mode a plain folder that passes.
        val bare = dir.resolve("bare-ws").createDirectories()
        val cloneMarker = dir.resolve("clone-spawned.txt")
        val cloneServer =
            FakeAcpServer.write(dir.resolve("clone-srv")) {
                shell("echo spawned > '$cloneMarker'")
                agyHandshake()
            }
        val cloneCtx = context(ws = bare, baseRef = "0".repeat(40)).copy(cloneWorkspace = true)
        val cloneEvents = runBlocking { withTimeout(20.seconds) { agyRunner(cloneServer).run(cloneCtx, "Work").toList() } }
        val failed = assertIs<RunnerEvent.TurnFailed>(cloneEvents.last())
        assertTrue(
            failed.reason.startsWith("protected_workspace_root: Loop runs no git in the clone $bare: ") &&
                "${bare.resolve(".git")} is missing" in failed.reason,
            failed.reason,
        )
        assertFalse(cloneMarker.exists(), "a refused clone spawns no agent")

        val hooksMarker = dir.resolve("hooks-spawned.txt")
        val hooksServer =
            FakeAcpServer.write(dir.resolve("hooks-srv")) {
                shell("echo spawned > '$hooksMarker'")
                agyHandshake()
            }
        runBlocking { withTimeout(20.seconds) { agyRunner(hooksServer).run(context(ws = bare), "Work").toList() } }
        assertTrue(hooksMarker.exists(), "the default context passes the same folder and spawns the agent, as today")
    }

    @Test
    fun `pre-turn protected roots check before turn 2 catches hooks json written between turns and stops session`() {
        val server =
            FakeAcpServer.write(dir) {
                agyHandshake(sessionId = "s-turn2", stopReason = "end_turn")
                // Turn 2 should never be sent once .agents/hooks.json is detected.
                shell("sleep 30")
            }
        val runner = agyRunner(server)

        val (turn1, turn2) =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = runner.openSession(context())
                    try {
                        val t1 = session.runTurn("Turn 1", 1).toList()
                        workspace.resolve(".agents").createDirectories()
                        workspace.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"injected"}]}""")
                        val t2 = session.runTurn("Turn 2", 2).toList()
                        t1 to t2
                    } finally {
                        session.stop()
                    }
                }
            }

        assertIs<RunnerEvent.TurnCompleted>(turn1.last())
        val failed2 = assertIs<RunnerEvent.TurnFailed>(turn2.last())
        assertNull(failed2.category)
        assertTrue(".agents/hooks.json" in failed2.reason, failed2.reason)
        // Only turn 1's session/prompt was sent, never turn 2's.
        assertEquals(1, received(server).count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
    }

    @Test
    fun `uses host AttemptContext baseRef instead of LOOP_BASE_REF in env and protects internal Loop dirs mid-turn`() {
        val repo = dir.resolve("git-ws").createDirectories()
        runGit(repo, "init", "-b", "main")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        runGit(repo, "add", ".agents/hooks.json")
        runGit(repo, "commit", "-m", "Base")
        val baseSha = runGit(repo, "rev-parse", "HEAD").trim()

        // Commit a modification to .agents/hooks.json; spoofing LOOP_BASE_REF=HEAD in env must NOT bypass baseRef.
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        runGit(repo, "add", ".agents/hooks.json")
        runGit(repo, "commit", "-m", "Evil hooks")

        val server = FakeAcpServer.write(dir.resolve("spoof-srv")) { agyHandshake() }
        val spoofedCtx =
            context(
                ws = repo,
                env = mapOf("LOOP_BASE_REF" to "HEAD"),
                baseRef = baseSha,
            )
        val spoofedEvents = runBlocking { withTimeout(20.seconds) { agyRunner(server).run(spoofedCtx, "Work").toList() } }
        val spoofedFailed = assertIs<RunnerEvent.TurnFailed>(spoofedEvents.last())
        assertTrue(".agents/hooks.json" in spoofedFailed.reason, spoofedFailed.reason)

        // Reset back to baseSha and verify mid-turn writes into the attempt dir / LOOP_TOOL_CONFIG parent are declined.
        runGit(repo, "reset", "--hard", baseSha)
        val loopHome = home.resolve(".loop")
        val attemptDir = loopHome.resolve("attempts/project-1/attempt-1")
        val guardServer =
            FakeAcpServer.write(dir.resolve("internal-srv")) {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "s-int-dir")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "s-int-dir")
                awaitClient()
                request(
                    id = 301,
                    method = AcpHandshake.METHOD_REQUEST_PERMISSION,
                    params =
                        buildJsonObject {
                            put("sessionId", "s-int-dir")
                            put(
                                "toolCall",
                                buildJsonObject {
                                    put("name", "write_file")
                                    put("kind", "edit")
                                    put("rawInput", buildJsonObject { put("Path", attemptDir.resolve("tool-config.json").toString()) })
                                },
                            )
                            put("options", options())
                        },
                )
                awaitClient()
                response(id = 4, result = buildJsonObject { put("stopReason", "end_turn") })
            }
        val cleanCtx =
            context(
                ws = repo,
                env =
                    mapOf(
                        "LOOP_HOME" to loopHome.toString(),
                        "LOOP_TOOL_CONFIG" to attemptDir.resolve("tool-config.json").toString(),
                    ),
                baseRef = baseSha,
            )
        val cleanEvents = runBlocking { withTimeout(20.seconds) { agyRunner(guardServer).run(cleanCtx, "Work").toList() } }
        assertIs<RunnerEvent.TurnCompleted>(cleanEvents.last())
        val permReply = received(guardServer).single { it["id"]?.jsonPrimitive?.content == "301" }
        assertEquals(
            "reject_once",
            permReply["result"]!!
                .jsonObject["outcome"]!!
                .jsonObject["optionId"]!!
                .jsonPrimitive.content,
        )
    }

    private fun runGit(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun agyRunner(server: FakeAcpServer): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = server.agyCommand,
                    preset = AcpSettings.PRESET_AGY,
                    model = FakeAcpServer.DEFAULT_MODEL,
                    readTimeout = FakeAcpServer.READ_TIMEOUT,
                    strictMcp = true,
                    holdoutDir = holdoutDir,
                ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
        )

    private fun received(server: FakeAcpServer): List<JsonObject> =
        server.received
            .readLines()
            .filter { it.isNotBlank() }
            .map { AcpFraming.json.parseToJsonElement(it).jsonObject }

    private fun context(
        ws: Path = workspace,
        env: Map<String, String> = mapOf("LOOP_LAUNCHER" to "/opt/loop/bin/loop"),
        baseRef: String? = null,
    ): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-05",
                    title = "Guards test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = ws,
            env = env,
            baseRef = baseRef,
        )
}
