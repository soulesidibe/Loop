package loop.runner.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.runner.codex.protocol.AttestationGenerateParams
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.ServerRequest
import loop.runner.codex.protocol.v2.AccountUpdatedNotification
import loop.runner.codex.protocol.v2.AuthMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** m3-17: the scripted fake app server replays its steps in order, on the stream each step names. */
class FakeAppServerTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var workspace: Path

    @Test
    fun `it replays typed messages, raw and split lines on stdout and diagnostics on stderr, in order`() {
        val server =
            FakeAppServer.write(dir) {
                notification(ServerNotification.AccountUpdated(params = AccountUpdatedNotification(authMode = AuthMode.CHATGPT)))
                raw("codex warming up")
                stderr("""{"method":"error","params":{}}""")
                request(ServerRequest.AttestationGenerate(id = JsonPrimitive(3), params = AttestationGenerateParams), split = true)
                response(1, buildJsonObject { put("ok", true) })
                error(2, -32600, "bad request")
                exit(4)
            }

        val run = launch(server)

        assertEquals(4, run.exitCode)
        val frames = run.stdout.map(ProtocolFraming::decode)
        assertEquals(listOf("Notification", "Malformed", "Request", "Response", "Error"), frames.map { it::class.simpleName })
        assertEquals("account/updated", assertIs<Frame.Notification>(frames[0]).message.method)
        assertEquals("attestation/generate", assertIs<Frame.Request>(frames[2]).message.method)
        assertEquals(listOf("""{"method":"error","params":{}}"""), run.stderr)
    }

    @Test
    fun `it records each client line it reads, then waits for the end of stdin like a real server`() {
        val server =
            FakeAppServer.write(dir) {
                awaitClient()
                response(1, buildJsonObject { put("ok", true) })
            }

        val run = launch(server, stdin = listOf("""{"id":1,"method":"initialize"}""", """{"method":"initialized"}"""))

        assertEquals(0, run.exitCode)
        assertEquals(listOf("""{"id":1,"method":"initialize"}""", """{"method":"initialized"}"""), server.received.readLines())
        assertIs<Frame.Response>(ProtocolFraming.decode(run.stdout.single()))
    }

    @Test
    fun `a shell step runs in the server's working directory`() {
        val report = dir.resolve("report.txt")
        val server = FakeAppServer.write(dir) { shell("pwd -P > '$report'") }

        launch(server)

        assertEquals(workspace.toRealPath().toString(), report.readText().trim())
    }

    @Test
    fun `a replayed line must be a message of the protocol`() {
        val real = javaClass.getResource("/real/initialize_and_thread_start.jsonl")!!.readText()

        val server = FakeAppServer.write(dir) { replay(real.lines()) }
        assertEquals(7, launch(server).stdout.size)
        assertFailsWith<IllegalArgumentException> { FakeAppServer.write(dir) { replay(listOf("codex warming up")) } }
    }

    private class Run(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>,
    )

    /** Starts the fake as the runner does, by the words of its command with no shell, in the workspace, with an empty home, writes [stdin] and closes it. */
    private fun launch(
        server: FakeAppServer,
        stdin: List<String> = emptyList(),
    ): Run {
        val builder = ProcessBuilder(CodexAppServerSettings(server.command).argv).directory(workspace.toFile())
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH"))
            put("HOME", dir.toString())
        }
        val process = builder.start()
        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLines() }
        val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readLines() }
        process.outputStream.bufferedWriter().use { writer -> stdin.forEach { writer.write(it + "\n") } }
        check(process.waitFor(10, TimeUnit.SECONDS)) { "the fake app server did not exit" }
        return Run(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS))
    }
}
