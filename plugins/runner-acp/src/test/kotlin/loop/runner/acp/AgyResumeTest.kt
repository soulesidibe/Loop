package loop.runner.acp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessGroupLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * bl-213: under `preset: agy` the isolated `--gemini-dir` outlives its session, so a dead `agy` process (a respawn)
 * and a network hold (`resumeSession`) load the session with `session/load`. Each spawn runs the next fake server of
 * a list, so one test can script the first process and the one that loads the session.
 */
class AgyResumeTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var home: Path

    private val launched: MutableList<LaunchSpec> = Collections.synchronizedList(mutableListOf())
    private val exits = Channel<Int>(Channel.UNLIMITED)
    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `a hold keeps the folder and resumeSession loads the session in a new agy process with the same gemini dir`() {
        val held =
            server("held") {
                agyHandshake(sessionId = "agy-held-1", stopReason = null)
                error(id = 4, code = -32000, message = "429 Too Many Requests: quota exceeded")
                shell("sleep 30")
            }
        val loader =
            server("loader") {
                initialize(id = 1, loadSession = true)
                sessionLoad(id = 2, sessionId = "agy-held-1")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "agy-held-1")
                promptTurn(id = 4)
            }
        val runner = runner(held, loader)

        val first = turns(runner.openSession(context(attempt = "attempt-1")), "Work")
        val failed = assertIs<RunnerEvent.TurnFailed>(first.last())
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        val heldDir = home.resolve(geminiDir(0))
        assertTrue(heldDir.resolve("antigravity-cli/settings.json").exists(), "the held session's folder is kept: $heldDir")

        val resumed = assertNotNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-held-1"))
        val events = turns(resumed, "Continue")

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("agy-held-1", started.runtimeSessionId)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(geminiDir(0), geminiDir(1), "the respawned agy uses the kept folder")
        val sent = received(loader)
        assertEquals(
            listOf("initialize", "session/load", "session/set_model", "session/prompt"),
            sent.map { it.string("method") },
        )
        val load = sent[1]["params"]!!.jsonObject
        assertEquals("agy-held-1", load.string("sessionId"))
        assertEquals(workspace.toAbsolutePath().toString(), load.string("cwd"))
        assertEquals(
            "loop",
            load["mcpServers"]!!
                .jsonArray
                .single()
                .jsonObject
                .string("name"),
        )
        assertTrue(
            notes(events).any { "resumes agy session agy-held-1 of an earlier attempt" in it },
            "a loop line names the resume: ${notes(events)}",
        )
        assertTrue(heldDir.exists(), "a resumed session that stops is kept again, for a second hold")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun `a turn whose agy process is gone respawns it once with the same gemini dir and loads the session`() {
        val first =
            server("first") {
                agyHandshake(sessionId = "agy-respawn-1")
                exit(0)
            }
        val second =
            server("second") {
                initialize(id = 1, loadSession = true)
                sessionLoad(id = 2, sessionId = "agy-respawn-1")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "agy-respawn-1")
                promptTurn(id = 4)
            }
        val runner = runner(first, second)
        val session = runner.openSession(context(attempt = "attempt-1"))
        val (turn1, turn2) =
            runBlocking {
                withTimeout(30.seconds) {
                    try {
                        val one = session.runTurn("Work", 1).toList()
                        exits.receive()
                        one to session.runTurn("Go on", 2).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertIs<RunnerEvent.TurnCompleted>(turn1.last(), "$turn1")
        assertIs<RunnerEvent.TurnStarted>(turn2.first(), "$turn2")
        assertIs<RunnerEvent.TurnCompleted>(turn2.last(), "$turn2")
        assertEquals(2, launched.size)
        assertEquals(geminiDir(0), geminiDir(1))
        val sent = received(second)
        assertEquals(
            listOf("initialize", "session/load", "session/set_model", "session/prompt"),
            sent.map { it.string("method") },
        )
        val load = sent[1]["params"]!!.jsonObject
        assertEquals("agy-respawn-1", load.string("sessionId"))
        // The respawn passes the MCP servers of the turn that opened the session, LOOP_SESSION_ID and LOOP_TURN_ID included.
        assertEquals(received(first)[1]["params"]!!.jsonObject["mcpServers"], load["mcpServers"])
        assertEquals(
            "Go on",
            sent[3]["params"]!!
                .jsonObject["prompt"]!!
                .jsonArray[0]
                .jsonObject
                .string("text"),
        )
        assertTrue(notes(turn2).any { "agy process of session agy-respawn-1 is gone" in it }, "${notes(turn2)}")
    }

    @Test
    fun `a respawned agy that dies before the load answers fails the turn without a second respawn`() {
        val first =
            server("first") {
                agyHandshake(sessionId = "agy-respawn-2")
                exit(0)
            }
        val dying =
            server("dying") {
                initialize(id = 1, loadSession = true)
                awaitClient()
                exit(3)
            }
        val runner = runner(first, dying)
        val session = runner.openSession(context(attempt = "attempt-1"))
        val turn2 =
            runBlocking {
                withTimeout(30.seconds) {
                    try {
                        session.runTurn("Work", 1).toList()
                        exits.receive()
                        session.runTurn("Go on", 2).toList()
                    } finally {
                        session.stop()
                    }
                }
            }

        val failed = assertIs<RunnerEvent.TurnFailed>(turn2.last(), "$turn2")
        assertEquals("port_exit: exit code 3, before the session/load response", failed.reason)
        assertEquals(2, launched.size, "at most one respawn per turn")
    }

    @Test
    fun `resumeSession returns null when no kept folder holds the runtime session`() {
        val other = server("other") { agyHandshake(sessionId = "agy-other-issue") }
        val runner = runner(other)
        turns(runner.openSession(context(attempt = "attempt-1", issueId = "issue-9")), "Work")
        assertTrue(home.resolve(geminiDir(0)).exists())

        assertNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-never-seen"))
        assertNull(
            runner.resumeSession(context(attempt = "attempt-2"), "agy-other-issue"),
            "a folder kept for another issue is never resumed",
        )
        Files.walk(home.resolve(geminiDir(0))).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        assertNull(runner.resumeSession(context(attempt = "attempt-2", issueId = "issue-9"), "agy-other-issue"))
        assertEquals(1, launched.size, "no agy process is spawned for a missing folder")
    }

    @Test
    fun `a resume rewrites a tampered settings json with the strict settings before the load`() {
        val held = server("held") { agyHandshake(sessionId = "agy-tamper-1") }
        val captured = dir.resolve("captured-settings.json")
        val loader =
            server("loader") {
                shell(
                    """
                    gdir=""
                    while [ $# -gt 0 ]; do
                      if [ "$1" = "--gemini-dir" ]; then gdir="$2"; shift; fi
                      shift
                    done
                    cat "${'$'}HOME/${'$'}gdir/antigravity-cli/settings.json" > '$captured'
                    """.trimIndent(),
                )
                initialize(id = 1, loadSession = true)
                sessionLoad(id = 2, sessionId = "agy-tamper-1")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "agy-tamper-1")
                promptTurn(id = 4)
            }
        val runner = runner(held, loader)
        turns(runner.openSession(context(attempt = "attempt-1")), "Work")
        home
            .resolve(geminiDir(0))
            .resolve("antigravity-cli/settings.json")
            .writeText("""{"toolPermission":"default","allowNonWorkspaceAccess":true}""")

        val events = turns(assertNotNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-tamper-1")), "Continue")

        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        val settings = AcpFraming.json.parseToJsonElement(captured.readText()).jsonObject
        assertEquals("strict", settings.string("toolPermission"))
        assertFalse(settings["allowNonWorkspaceAccess"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a resume refuses a folder holding an unexpected root entry and opens a fresh session`() {
        val held = server("held") { agyHandshake(sessionId = "agy-tamper-2") }
        val fresh = server("fresh") { agyHandshake(sessionId = "agy-fresh-2") }
        val runner = runner(held, fresh)
        turns(runner.openSession(context(attempt = "attempt-1")), "Work")
        val heldDir = home.resolve(geminiDir(0))
        heldDir
            .resolve("config")
            .createDirectories()
            .resolve("hooks.json")
            .writeText("{}")

        val events = turns(assertNotNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-tamper-2")), "Continue")

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        assertEquals("agy-fresh-2", started.runtimeSessionId)
        assertIs<RunnerEvent.TurnCompleted>(events.last(), "$events")
        assertEquals(
            listOf("initialize", "session/new", "session/set_model", "session/prompt"),
            received(fresh).map { it.string("method") },
        )
        assertNotEquals(geminiDir(0), geminiDir(1), "a fresh session gets a fresh folder")
        assertFalse(heldDir.exists(), "the refused folder is removed")
        assertTrue(
            notes(events).any { "cannot resume agy session agy-tamper-2" in it && "config/hooks.json" in it },
            "${notes(events)}",
        )
    }

    @Test
    fun `an agent without loadSession or a session load error gives a fresh session`() {
        val heldA = server("held-a") { agyHandshake(sessionId = "agy-noload") }
        val noLoad =
            server("no-load") {
                initialize(id = 1)
                shell("sleep 30")
            }
        val freshA = server("fresh-a") { agyHandshake(sessionId = "agy-fresh-a") }
        val runnerA = runner(heldA, noLoad, freshA)
        turns(runnerA.openSession(context(attempt = "attempt-1")), "Work")
        val eventsA = turns(assertNotNull(runnerA.resumeSession(context(attempt = "attempt-2"), "agy-noload")), "Continue")

        assertEquals("agy-fresh-a", assertIs<RunnerEvent.SessionStarted>(eventsA.first()).runtimeSessionId)
        assertIs<RunnerEvent.TurnCompleted>(eventsA.last(), "$eventsA")
        assertEquals(listOf("initialize"), received(noLoad).map { it.string("method") })
        assertTrue(notes(eventsA).any { "cannot resume agy session agy-noload" in it && "loadSession" in it }, "${notes(eventsA)}")
        assertEquals(
            "Continue",
            received(freshA)[3]["params"]!!
                .jsonObject["prompt"]!!
                .jsonArray[0]
                .jsonObject
                .string("text"),
        )

        launched.clear()
        val heldB = server("held-b") { agyHandshake(sessionId = "agy-loaderr") }
        val loadError =
            server("load-error") {
                initialize(id = 1, loadSession = true)
                awaitClient()
                error(id = 2, code = -32002, message = "Session not found: agy-loaderr")
                shell("sleep 30")
            }
        val freshB = server("fresh-b") { agyHandshake(sessionId = "agy-fresh-b") }
        val runnerB = runner(heldB, loadError, freshB)
        turns(runnerB.openSession(context(attempt = "attempt-3", issueId = "issue-2")), "Work")
        val eventsB =
            turns(assertNotNull(runnerB.resumeSession(context(attempt = "attempt-4", issueId = "issue-2"), "agy-loaderr")), "Continue")

        assertEquals("agy-fresh-b", assertIs<RunnerEvent.SessionStarted>(eventsB.first()).runtimeSessionId)
        assertIs<RunnerEvent.TurnCompleted>(eventsB.last(), "$eventsB")
        assertTrue(
            notes(eventsB).any { "cannot resume agy session agy-loaderr" in it && "Session not found" in it },
            "${notes(eventsB)}",
        )
        assertFalse(home.resolve(geminiDir(0)).exists(), "the folder of a session that did not load is removed")
    }

    @Test
    fun `a new session of the same issue removes the kept folder, a resumed one included, and keeps other issues' folders`() {
        val heldA = server("held-a") { agyHandshake(sessionId = "agy-a") }
        val otherIssue = server("other") { agyHandshake(sessionId = "agy-other") }
        val loader =
            server("loader") {
                initialize(id = 1, loadSession = true)
                sessionLoad(id = 2, sessionId = "agy-a")
                sessionSetModel(id = 3, configUpdateModelId = FakeAcpServer.DEFAULT_MODEL, sessionId = "agy-a")
                promptTurn(id = 4)
            }
        val fresh = server("fresh") { agyHandshake(sessionId = "agy-c") }
        val runner = runner(heldA, otherIssue, loader, fresh)

        turns(runner.openSession(context(attempt = "attempt-1")), "Work")
        turns(runner.openSession(context(attempt = "attempt-9", issueId = "issue-9")), "Work")
        val keptA = home.resolve(geminiDir(0))
        val keptOther = home.resolve(geminiDir(1))
        turns(assertNotNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-a")), "Continue")
        assertTrue(keptA.exists(), "a resumed session is kept again when it stops")

        turns(runner.openSession(context(attempt = "attempt-3")), "Fresh start")

        assertFalse(keptA.exists(), "the next session of the issue supersedes the resumed one")
        assertTrue(keptOther.exists(), "a kept folder of another issue stays")
        assertTrue(home.resolve(geminiDir(3)).exists(), "the newest session is kept")
    }

    @Test
    fun `a session that never reported a runtime session id deletes its folder when it stops`() {
        val server =
            server("startup-fail") {
                initialize(id = 1)
                sessionNew(id = 2, sessionId = "agy-no-prompt")
                awaitClient()
                error(id = 3, code = -32602, message = "Unknown model ID")
                shell("sleep 30")
            }
        val runner = runner(server)
        val events = turns(runner.openSession(context(attempt = "attempt-1")), "Work")

        assertNull(assertIs<RunnerEvent.SessionStarted>(events.first()).runtimeSessionId)
        assertFalse(home.resolve(geminiDir(0)).exists())
        assertNull(runner.resumeSession(context(attempt = "attempt-2"), "agy-no-prompt"))
    }

    private fun server(
        name: String,
        steps: FakeAcpServer.Script.() -> Unit,
    ): FakeAcpServer = FakeAcpServer.write(dir.resolve(name), steps = steps)

    /** A `preset: agy` runner whose n-th spawn runs [servers]`[n]`, always with the argv Loop built. */
    private fun runner(vararg servers: FakeAcpServer): AcpRunner {
        val launcher = ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)
        return AcpRunner(
            settings =
                AcpSettings(
                    command = servers.first().agyCommand,
                    preset = AcpSettings.PRESET_AGY,
                    model = FakeAcpServer.DEFAULT_MODEL,
                    readTimeout = FakeAcpServer.READ_TIMEOUT,
                    strictMcp = true,
                ),
            hostEnv = mapOf("PATH" to System.getenv("PATH"), "HOME" to home.toString()),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = { spec ->
                val index =
                    synchronized(launched) {
                        launched += spec
                        launched.size - 1
                    }
                val script = servers[index].script.toString()
                launcher.launch(spec.copy(argv = listOf(script) + spec.argv.drop(1))).onCompletion { exits.trySend(index) }
            },
            onWarn = { warnings += it },
        )
    }

    private fun turns(
        session: AgentSession,
        input: String,
    ): List<RunnerEvent> =
        runBlocking {
            withTimeout(30.seconds) {
                try {
                    session.runTurn(input, 1).toList()
                } finally {
                    session.stop()
                }
            }
        }

    private fun geminiDir(spawn: Int): String = launched[spawn].argv.let { it[it.indexOf("--gemini-dir") + 1] }

    private fun notes(events: List<RunnerEvent>): List<String> =
        events
            .filterIsInstance<RunnerEvent.Output>()
            .filter { it.stream == Stream.Stderr && it.line.startsWith("loop: ") }
            .map { it.line }

    private fun received(server: FakeAcpServer): List<JsonObject> =
        server.received
            .readLines()
            .filter { it.isNotBlank() }
            .map { AcpFraming.json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun context(
        attempt: String,
        issueId: String = "issue-1",
    ): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = issueId,
                    identifier = "bl-213",
                    title = "Agy resume",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId(attempt),
            workspace = workspace,
            env =
                mapOf(
                    "LOOP_LAUNCHER" to "/opt/loop/bin/loop",
                    "LOOP_TOOL_CONFIG" to dir.resolve("$attempt/tool-config.json").toString(),
                    "LOOP_ATTEMPT_ID" to attempt,
                    "LOOP_ISSUE_ID" to issueId,
                    "LOOP_ISSUE_IDENTIFIER" to "bl-213",
                    "LOOP_TRACKER_ID" to "primary",
                    "LOOP_HOME" to dir.resolve("outside-home-loop").toString(),
                ),
        )
}
